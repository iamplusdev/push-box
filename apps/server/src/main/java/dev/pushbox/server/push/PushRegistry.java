package dev.pushbox.server.push;

import dev.pushbox.core.crypto.B64;
import dev.pushbox.core.crypto.FrameChannel;
import dev.pushbox.core.crypto.SignatureTargets;
import dev.pushbox.core.crypto.X25519;
import dev.pushbox.core.protocol.Envelope;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.core.protocol.Json;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.ServerKeys;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** 在线设备会话表；§4.3 的会话密钥与帧序号状态都挂在会话上，断连即丢弃。 */
@Component
public class PushRegistry {

  private static final Logger log = LoggerFactory.getLogger(PushRegistry.class);

  public static final class Session {
    public final String deviceId;
    public final String groupId;
    public final ConcurrentWebSocketSessionDecorator outbound;
    public volatile long lastInboundMillis;
    /** 握手与二次 kx 都换成新对象，读写都在 {@link #lock} 内进行。 */
    FrameChannel channel;
    int epoch;
    private final byte[] helloNonce;
    private final byte[] deviceEncPub;
    /**
     * 换密钥与发 kx 必须和下行发送互斥：否则旧密钥的尾帧可能排在 kx 之后，
     * 对端翻完密钥就再也解不开它（§4.3"轮换帧仍用旧密钥保护"）。
     */
    final Object lock = new Object();

    Session(String deviceId, String groupId, WebSocketSession raw, FrameChannel channel,
        byte[] helloNonce, byte[] deviceEncPub) {
      this.deviceId = deviceId;
      this.groupId = groupId;
      // 每个下行发送都串行化并限时，避免多个广播线程并发写同一 socket
      this.outbound = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 256 * 1024);
      this.channel = channel;
      this.helloNonce = helloNonce;
      this.deviceEncPub = deviceEncPub;
      this.lastInboundMillis = System.currentTimeMillis();
    }
  }

  private final Map<String, Session> byDevice = new ConcurrentHashMap<>();
  /**
   * 按 Space 分桶的会话表，与 {@link #byDevice} 同时维护。
   * 每来一条消息都只该看这个组里的几台设备；从全局表里按 groupId 过滤意味着
   * 多 Space 部署下每条消息都要扫一遍所有人的连接，而扫描成本随"这台服务器上还有谁在线"增长。
   */
  private final Map<String, Map<String, Session>> byGroup = new ConcurrentHashMap<>();
  private final ServerKeys keys;
  private final PbProperties props;
  private final PbStore store;

  public PushRegistry(ServerKeys keys, PbProperties props, PbStore store) {
    this.keys = keys;
    this.props = props;
    this.store = store;
  }

  public Session open(WebSocketSession raw, String deviceId, String groupId, FrameChannel channel,
      byte[] helloNonce, byte[] deviceEncPub) {
    Session session = new Session(deviceId, groupId, raw, channel, helloNonce, deviceEncPub);
    Session previous = byDevice.put(deviceId, session);
    byGroup.compute(groupId, (k, group) -> {
      Map<String, Session> bucket = group != null ? group : new ConcurrentHashMap<>();
      bucket.put(deviceId, session);
      return bucket;
    });
    if (previous != null && previous != session) {
      // 这里走 close 而不是 closeQuietly：旧会话可能还在另一个 Space 的分桶里，
      // 只关连接不摘映射，那条死连接会被以后每一条消息再"下发"一次
      close(previous, "同一设备的新连接接管");
    }
    return session;
  }

  public void close(Session session, String reason) {
    byDevice.remove(session.deviceId, session);
    // 只摘掉"这一条"：同一设备的新连接可能已经占住这个 deviceId 的槽位
    byGroup.computeIfPresent(session.groupId, (k, group) -> {
      group.remove(session.deviceId, session);
      return group.isEmpty() ? null : group;
    });
    try {
      session.outbound.close(CloseStatus.NORMAL.withReason(reason));
    } catch (IOException | IllegalStateException e) {
      log.debug("关闭会话时忽略: {}", e.getMessage());
    }
  }

  public Session find(String deviceId) {
    return byDevice.get(deviceId);
  }

  public int onlineCount() {
    return byDevice.size();
  }

  public Map<String, Session> sessions() {
    return Map.copyOf(byDevice);
  }

  /**
   * 组级/设备级控制帧的下发：不带频道内容，永远给全组（config、read 等）。
   */
  public int broadcast(String groupId, Object frame) {
    return dispatch(groupId, null, frame);
  }

  /**
   * 频道内容帧的下发：过滤模式下的设备只收它接入过的频道。
   * {@code channelNo} 为 null 时退化为普通广播（不过滤），用于 config 帧等不携带内容的情况。
   */
  public int broadcastToChannel(String groupId, String channelNo, Object frame) {
    return dispatch(groupId, channelNo, frame);
  }

  private int dispatch(String groupId, String channelNo, Object frame) {
    Map<String, Session> group = byGroup.get(groupId);
    if (group == null || group.isEmpty()) {
      return 0;
    }
    Set<String> allowed = allowedDevices(groupId, channelNo);
    int sent = 0;
    for (Session session : group.values()) {
      if (allowed != null && !allowed.contains(session.deviceId)) {
        continue;
      }
      if (send(session, frame)) {
        sent++;
      }
    }
    return sent;
  }

  /**
   * channelNo == null → 全组（不过滤）；否则一次 SQL 拿到"该组里哪些设备能收这个频道"。
   * 吊销的设备今天也是等心跳超时才掉线，这里顺手掐掉会让"吊销"出现两条不一致的生效时机，
   * 所以不过滤 revoked_at。
   */
  private Set<String> allowedDevices(String groupId, String channelNo) {
    if (channelNo == null) {
      return null;
    }
    return new HashSet<>(store.deviceIdsAllowedForChannel(groupId, channelNo));
  }

  public boolean send(Session session, Object frame) {
    String wire;
    synchronized (session.lock) {
      rotateIfNeeded(session);
      wire = Envelope.seal(session.channel, frame);
    }
    try {
      session.outbound.sendMessage(new TextMessage(wire));
      return true;
    } catch (IOException | IllegalStateException e) {
      log.info("下发失败，移除会话 device={}: {}", session.deviceId, e.getMessage());
      close(session, "写入失败");
      return false;
    }
  }

  /**
   * §4.3 每 24h 或每 2^32 帧轮换：拿设备静态 enc 公钥重做一次 X25519，明文 kx（epoch 递增、
   * 签名仍是 eph_pub||hello nonce）先写进流里，随后本端换新密钥、帧序号从 0 重启。
   * 调用方必须持有 session.lock，这样旧密钥的尾帧一定排在 kx 之前。
   */
  private void rotateIfNeeded(Session session) {
    long now = System.currentTimeMillis();
    if (session.channel.nextFrameIndex() < props.getWs().getRotateAfterFrames()
        && !session.channel.shouldRotate(now)) {
      return;
    }
    X25519.KeyPair eph = X25519.generate();
    byte[] sig = keys.sign(SignatureTargets.kx(eph.publicKey(), session.helloNonce));
    writeText(session, Json.write(Frames.Kx.of(B64.enc(keys.identity().publicKey()),
        B64.enc(eph.publicKey()), B64.enc(sig), ++session.epoch)));
    session.channel = new FrameChannel(FrameChannel.sessionKey(
        X25519.agree(eph.privateKey(), session.deviceEncPub), session.helloNonce), now);
    log.info("下行会话密钥已轮换 device_id={} epoch={}", session.deviceId, session.epoch);
  }

  private void writeText(Session session, String wire) {
    try {
      session.outbound.sendMessage(new TextMessage(wire));
    } catch (IOException | IllegalStateException e) {
      log.debug("轮换 kx 帧发送失败 device={}: {}", session.deviceId, e.getMessage());
    }
  }

  /** 明文控制帧（hello/kx）用，不进加密通道。 */
  public void sendPlain(WebSocketSession raw, String wire) {
    try {
      raw.sendMessage(new TextMessage(wire));
    } catch (IOException e) {
      log.debug("明文帧发送失败: {}", e.getMessage());
    }
  }

}
