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
import dev.pushbox.server.service.ChannelService;
import dev.pushbox.server.service.DeviceService;
import dev.pushbox.server.service.ServerKeys;
import com.mybatisflex.core.row.Row;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * §5.3 长连接：hello → auth（token 在帧里、不进 URL）→ kx → 之后一律 {t:"enc",n,data}。
 * 断线重连按 since_seq 补拉，落后过多则回 resume_gap 让客户端走 REST。
 */
@Component
public class PushboxWsHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(PushboxWsHandler.class);
  private static final String ATTR_NONCE = "pb.nonce";
  private static final String ATTR_SESSION = "pb.session";
  private static final long AUTH_WINDOW_MILLIS = 300_000L;
  /** 落后多少条就改走 REST 补拉。比的是<b>本组</b>的行高，见 {@link PbStore#latestSeq(String)}。 */
  private static final int GAP_THRESHOLD = 2_000;
  /** §5.3 "落后太多就别用 WS 重放，改走 REST 补拉"的阈值。联调用 {@code --pb.ws.gap-threshold=3} 调小。 */
  private static final int GAP_THRESHOLD_DEFAULT = 2_000;

  private final PbStore store;
  private final DeviceService devices;
  private final ServerKeys keys;
  private final PushRegistry registry;
  private final FrameAssembler assembler;
  private final PbProperties props;
  private final ChannelService channels;

  public PushboxWsHandler(PbStore store, DeviceService devices, ServerKeys keys, PushRegistry registry,
      FrameAssembler assembler, PbProperties props, ChannelService channels) {
    this.store = store;
    this.devices = devices;
    this.keys = keys;
    this.registry = registry;
    this.assembler = assembler;
    this.props = props;
    this.channels = channels;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    byte[] nonce = ServerKeys.randomBytes(32);
    session.getAttributes().put(ATTR_NONCE, nonce);
    registry.sendPlain(session,
        Json.write(Frames.Hello.of(System.currentTimeMillis(), props.getWs().getHeartbeatSec(),
            keys.fingerprint(), B64.enc(nonce))));
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    String type = Envelope.typeOf(message.getPayload());
    if (type == null) {
      log.warn("入站帧没有可用的 t 字段，忽略 session={}", session.getId());
      return;
    }
    PushRegistry.Session live = (PushRegistry.Session) session.getAttributes().get(ATTR_SESSION);
    try {
      if (live == null) {
        if (Frames.T_AUTH.equals(type)) {
          authenticate(session, message.getPayload());
        } else {
          reject(session, "expected_auth");
        }
        return;
      }
      live.lastInboundMillis = System.currentTimeMillis();
      if (Frames.T_PING.equals(type)) {
        registry.send(live, Frames.Simple.pong());
      }
    } catch (RuntimeException e) {
      // 不记日志的话异常会被容器吞掉，客户端只看到连接凭空断开（P0 排障时踩过）
      log.error("处理 {} 帧失败 session={}", type, session.getId(), e);
      closeQuietly(session, CloseStatus.SERVER_ERROR);
    }
  }

  private void reject(WebSocketSession session, String error) {
    log.warn("握手被拒: {}", error);
    registry.sendPlain(session, Json.write(Frames.AuthFail.of(error)));
    closeQuietly(session, CloseStatus.POLICY_VIOLATION);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    PushRegistry.Session live = (PushRegistry.Session) session.getAttributes().get(ATTR_SESSION);
    if (live != null) {
      registry.close(live, "客户端断开");
    }
  }

  private void authenticate(WebSocketSession session, String payload) {
    byte[] nonce = (byte[]) session.getAttributes().get(ATTR_NONCE);
    Frames.Auth auth = Json.read(payload, Frames.Auth.class);
    Row device = auth == null ? null : devices.byToken(auth.token());
    if (device == null || auth.deviceId() == null || !auth.deviceId().equals(device.getString("device_id"))
        || device.getLong("revoked_at") != null) {
      reject(session, "invalid_device");
      return;
    }
    long now = System.currentTimeMillis();
    if (Math.abs(now - auth.ts()) > AUTH_WINDOW_MILLIS) {
      reject(session, "clock_skew");
      return;
    }
    byte[] sig = B64.tryDec(auth.sigB64());
    byte[] signPub = B64.tryDec(device.getString("device_pubkey"));
    if (sig == null || signPub == null
        || !dev.pushbox.core.crypto.Ed25519.verify(signPub,
        SignatureTargets.wsAuth(auth.deviceId(), nonce, auth.ts()), sig)) {
      reject(session, "bad_signature");
      return;
    }

    // kx：临时 X25519 密钥用设备的静态 enc_pubkey 协商，签名覆盖 eph_pub||nonce（§4.3）
    X25519.KeyPair eph = X25519.generate();
    byte[] deviceEncPub = B64.dec(device.getString("enc_pubkey"));
    byte[] shared = X25519.agree(eph.privateKey(), deviceEncPub);
    byte[] key = FrameChannel.sessionKey(shared, nonce);
    byte[] kxSig = keys.sign(SignatureTargets.kx(eph.publicKey(), nonce));
    registry.sendPlain(session,
        Json.write(Frames.Kx.of(B64.enc(keys.identity().publicKey()), B64.enc(eph.publicKey()),
            B64.enc(kxSig), 0)));

    FrameChannel channel = new FrameChannel(key, now);
    // nonce 与设备 enc 公钥留在会话上，§4.3 的跨界轮换要拿它们再算一次 DH
    PushRegistry.Session live = registry.open(session, auth.deviceId(), device.getString("group_id"),
        channel, nonce, deviceEncPub);
    session.getAttributes().put(ATTR_SESSION, live);
    devices.touch(auth.deviceId());
    replay(live, sinceSeq(session), device);
    log.info("设备已连接 device_id={} group_id={} since_seq={} 在线={}", auth.deviceId(),
        device.getString("group_id"), sinceSeq(session), registry.onlineCount());
  }

  /** 补发：重放帧 notify=false（客户端已有同样内容，不该重复弹通知）。 */
  private void replay(PushRegistry.Session session, long sinceSeq, Row device) {
    java.util.Set<String> scope = channels.readScope(device);
    // 只跟自己组的行高比：全局 MAX(seq) 会让一个还没发过消息的 Space 被别的 Space 的流量顶过
    // GAP_THRESHOLD，于是每次连接只回 resume_gap、永远等不到 resume_ok（真服务体检 ② 抓到的）
    long latest = store.latestSeq(session.groupId);
    if (latest - sinceSeq > GAP_THRESHOLD) {
      registry.send(session, Frames.ResumeGap.of(sinceSeq + 1, latest));
      return;
    }
    long cursor = sinceSeq;
    // 一批要循环到取空才回 resume_ok。原先只发一批：落后条数在"一批"与 GAP_THRESHOLD 之间的设备
    // 既收不满也收不到 resume_gap，客户端把"这一批发完"当成"已经追平"，
    // 剩下的消息要等下一次断线重连才有机会补上——不重连就永久静默
    int batch = props.getWs().getReplayBatch();
    while (cursor < latest) {
      List<Row> rows = store.listMessagesAfter(session.groupId, cursor, batch, scope);
      if (rows.isEmpty()) {
        break;
      }
      for (Row row : rows) {
        // 与实时下发走同一个截断规则，否则同一帧在"在线收到"和"重连补拉"两种路径下长度不一致
        if (!registry.send(session,
            assembler.capFrame(assembler.toFrame(row, false), props.getWs().getFrameMaxBytes()))) {
          return; // 对端已断，剩下的等它下次重连
        }
      }
      cursor = rows.get(rows.size() - 1).getLong("seq");
    }
    registry.send(session, Frames.Simple.resumeOk());
  }

  private long sinceSeq(WebSocketSession session) {
    URI uri = session.getUri();
    if (uri == null || uri.getQuery() == null) {
      return 0L;
    }
    for (String pair : uri.getQuery().split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && "since_seq".equals(pair.substring(0, eq))) {
        try {
          return Long.parseLong(java.net.URLDecoder.decode(pair.substring(eq + 1),
              StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
          return 0L;
        }
      }
    }
    return 0L;
  }

  /** §5.3：25s 心跳，40s 无息判死。 */
  @Scheduled(fixedDelay = 10_000)
  void heartbeat() {
    long now = System.currentTimeMillis();
    long idleLimit = (props.getWs().getHeartbeatSec() + 15L) * 1000L;
    for (PushRegistry.Session session : registry.sessions().values()) {
      if (now - session.lastInboundMillis > idleLimit) {
        log.info("心跳超时，断开 device_id={}", session.deviceId);
        registry.close(session, "heartbeat_timeout");
        continue;
      }
      if (now - session.lastInboundMillis >= props.getWs().getHeartbeatSec() * 1000L) {
        registry.send(session, Frames.Simple.ping());
      }
    }
  }

  private void closeQuietly(WebSocketSession session, CloseStatus status) {
    try {
      session.close(status);
    } catch (Exception ignored) {
      // 已经是关闭态
    }
  }
}
