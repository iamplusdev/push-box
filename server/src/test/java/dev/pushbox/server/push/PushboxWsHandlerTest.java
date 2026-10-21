package dev.pushbox.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.crypto.B64;
import dev.pushbox.core.crypto.Ed25519;
import dev.pushbox.core.crypto.FrameChannel;
import dev.pushbox.core.crypto.SignatureTargets;
import dev.pushbox.core.crypto.X25519;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.core.protocol.Json;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.ChannelService;
import dev.pushbox.server.service.DeviceService;
import dev.pushbox.server.service.ServerKeys;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * §5.3 长连接的握手与心跳：这些判定不碰容器，但每一条都对应线上踩过的坑。
 * 尤其是补拉阈值——它必须读 `pb.ws.gap-threshold`，否则联调时只能改代码重打包。
 */
class PushboxWsHandlerTest {

  private static final String DEVICE_ID = "dev_test";
  private static final String GROUP_ID = "grp_test";
  private static final String TOKEN = "token_test";

  private PbStore store;
  private DeviceService devices;
  private ServerKeys keys;
  private PushRegistry registry;
  private FrameAssembler assembler;
  private PbProperties props;
  private ChannelService channels;
  private PushboxWsHandler handler;

  private Ed25519.KeyPair serverIdentity;
  private Ed25519.KeyPair deviceIdentity;
  private X25519.KeyPair deviceEnc;

  @BeforeEach
  void setUp() {
    store = mock(PbStore.class);
    devices = mock(DeviceService.class);
    keys = mock(ServerKeys.class);
    registry = mock(PushRegistry.class);
    assembler = mock(FrameAssembler.class);
    props = new PbProperties();
    channels = mock(ChannelService.class);
    handler = new PushboxWsHandler(store, devices, keys, registry, assembler, props, channels);

    serverIdentity = Ed25519.generate();
    when(keys.identity()).thenReturn(serverIdentity);
    when(keys.fingerprint()).thenReturn(Ed25519.fingerprint(serverIdentity.publicKey()));
    when(keys.sign(any(byte[].class))).thenAnswer(
        call -> Ed25519.sign(serverIdentity.privateKey(), call.getArgument(0)));
    when(assembler.capFrame(any(Frames.Message.class), anyInt())).thenAnswer(
        call -> call.getArgument(0));

    deviceIdentity = Ed25519.generate();
    deviceEnc = X25519.generate();
  }

  @Test
  @DisplayName("① 建连先发明文 hello：指纹与心跳跟着配置走，nonce 留在会话上等 auth 签名")
  void sendsHelloWithNonce() {
    WebSocketSession raw = rawSession(null);

    handler.afterConnectionEstablished(raw);

    ArgumentCaptor<String> wire = ArgumentCaptor.forClass(String.class);
    verify(registry).sendPlain(eq(raw), wire.capture());
    Frames.Hello hello = Json.read(wire.getValue(), Frames.Hello.class);
    byte[] nonce = (byte[]) raw.getAttributes().get("pb.nonce");

    assertThat(hello.t()).isEqualTo(Frames.T_HELLO);
    assertThat(hello.heartbeatSec()).isEqualTo(25);
    assertThat(hello.serverPubFp()).isEqualTo(Ed25519.fingerprint(serverIdentity.publicKey()));
    assertThat(nonce).hasSize(32);
    assertThat(hello.nonceB64()).isEqualTo(B64.enc(nonce));
  }

  @Test
  @DisplayName("② 签名对不上就 auth_fail=bad_signature 并断开，绝不进入 kx")
  void rejectsBadSignature() throws Exception {
    WebSocketSession raw = rawSession(null);
    byte[] nonce = ServerKeys.randomBytes(32);
    raw.getAttributes().put("pb.nonce", nonce);
    when(devices.byToken(TOKEN)).thenReturn(deviceRow());
    long ts = System.currentTimeMillis();
    byte[] forged = Ed25519.sign(Ed25519.generate().privateKey(),
        SignatureTargets.wsAuth(DEVICE_ID, nonce, ts));

    handler.handleTextMessage(raw, new TextMessage(
        Json.write(Frames.Auth.of(DEVICE_ID, TOKEN, ts, B64.enc(forged)))));

    ArgumentCaptor<String> wire = ArgumentCaptor.forClass(String.class);
    verify(registry).sendPlain(eq(raw), wire.capture());
    assertThat(Json.read(wire.getValue(), Frames.AuthFail.class).error())
        .isEqualTo("bad_signature");
    verify(registry, never()).open(any(), any(), any(), any(), any(), any());
    verify(raw).close(CloseStatus.POLICY_VIOLATION);
  }

  @Test
  @DisplayName("③ 验签通过：kx 的签名可用服务端身份公钥验，随机挑战进会话，补拉后回 resume_ok")
  void authenticatesAndReplays() {
    WebSocketSession raw = rawSession("?since_seq=0");
    byte[] nonce = ServerKeys.randomBytes(32);
    raw.getAttributes().put("pb.nonce", nonce);
    when(devices.byToken(TOKEN)).thenReturn(deviceRow());
    when(store.latestSeq(GROUP_ID)).thenReturn(0L);
    PushRegistry.Session live = liveSession(raw, nonce);
    when(registry.open(eq(raw), eq(DEVICE_ID), eq(GROUP_ID), any(FrameChannel.class),
        any(byte[].class), any(byte[].class))).thenReturn(live);
    when(registry.send(eq(live), any())).thenReturn(true);

    handler.handleTextMessage(raw, new TextMessage(validAuth(nonce, deviceIdentity)));

    ArgumentCaptor<String> wire = ArgumentCaptor.forClass(String.class);
    verify(registry).sendPlain(eq(raw), wire.capture());
    Frames.Kx kx = Json.read(wire.getValue(), Frames.Kx.class);
    assertThat(kx.t()).isEqualTo(Frames.T_KX);
    assertThat(kx.epoch()).isZero();
    assertThat(kx.serverPubB64()).isEqualTo(B64.enc(serverIdentity.publicKey()));
    assertThat(Ed25519.verify(serverIdentity.publicKey(),
        SignatureTargets.kx(B64.dec(kx.ephPubB64()), nonce), B64.dec(kx.sigB64()))).isTrue();

    ArgumentCaptor<byte[]> nonceCaptor = ArgumentCaptor.forClass(byte[].class);
    ArgumentCaptor<byte[]> encCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(registry).open(eq(raw), eq(DEVICE_ID), eq(GROUP_ID), any(FrameChannel.class),
        nonceCaptor.capture(), encCaptor.capture());
    assertThat(nonceCaptor.getValue()).isEqualTo(nonce);
    assertThat(encCaptor.getValue()).isEqualTo(deviceEnc.publicKey());

    assertThat(raw.getAttributes().get("pb.session")).isSameAs(live);
    verify(devices).touch(DEVICE_ID);
    ArgumentCaptor<Object> frames = ArgumentCaptor.forClass(Object.class);
    verify(registry).send(eq(live), frames.capture());
    assertThat(frames.getValue()).isEqualTo(Frames.Simple.resumeOk());
    // 阈值必须跟"本组"的行高比，而不是全局 MAX(seq)
    verify(store).latestSeq(GROUP_ID);
    verify(store, never()).latestSeq();
  }

  @Test
  @DisplayName("④ 补拉循环取空所有批次才回 resume_ok，不能发完一批就当追平")
  void drainsEveryReplayBatch() {
    WebSocketSession raw = rawSession("?since_seq=0");
    byte[] nonce = ServerKeys.randomBytes(32);
    raw.getAttributes().put("pb.nonce", nonce);
    when(devices.byToken(TOKEN)).thenReturn(deviceRow());
    props.getWs().setReplayBatch(2);
    PushRegistry.Session live = liveSession(raw, nonce);
    when(registry.open(eq(raw), eq(DEVICE_ID), eq(GROUP_ID), any(FrameChannel.class),
        any(byte[].class), any(byte[].class))).thenReturn(live);
    when(registry.send(eq(live), any())).thenReturn(true);
    when(store.latestSeq(GROUP_ID)).thenReturn(5L);
    when(store.listMessagesAfter(eq(GROUP_ID), anyLong(), eq(2), any())).thenAnswer(
        call -> rowsUpTo(call.getArgument(1), 5L));
    when(assembler.toFrame(any(Row.class), eq(false))).thenAnswer(
        call -> message(((Row) call.getArgument(0)).getLong("seq")));

    handler.handleTextMessage(raw, new TextMessage(validAuth(nonce, deviceIdentity)));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Object> frames = ArgumentCaptor.forClass(Object.class);
    verify(registry, times(6)).send(eq(live), frames.capture());
    List<Object> sent = frames.getAllValues();
    assertThat(sent.subList(0, 5)).extracting(f -> ((Frames.Message) f).seq())
        .containsExactly(1L, 2L, 3L, 4L, 5L);
    assertThat(sent.get(5)).isEqualTo(Frames.Simple.resumeOk());
    verify(store, times(3)).listMessagesAfter(eq(GROUP_ID), anyLong(), eq(2), any());
  }

  @Test
  @DisplayName("⑤ 落后超过 pb.ws.gap-threshold 只回 resume_gap，不再逐条取库")
  void sendsResumeGapBeyondConfiguredThreshold() {
    props.getWs().setGapThreshold(3);
    WebSocketSession raw = rawSession("?since_seq=0");
    byte[] nonce = ServerKeys.randomBytes(32);
    raw.getAttributes().put("pb.nonce", nonce);
    when(devices.byToken(TOKEN)).thenReturn(deviceRow());
    when(store.latestSeq(GROUP_ID)).thenReturn(10L);
    PushRegistry.Session live = liveSession(raw, nonce);
    when(registry.open(eq(raw), eq(DEVICE_ID), eq(GROUP_ID), any(FrameChannel.class),
        any(byte[].class), any(byte[].class))).thenReturn(live);
    when(registry.send(eq(live), any())).thenReturn(true);

    handler.handleTextMessage(raw, new TextMessage(validAuth(nonce, deviceIdentity)));

    ArgumentCaptor<Object> frames = ArgumentCaptor.forClass(Object.class);
    verify(registry).send(eq(live), frames.capture());
    assertThat(frames.getValue()).isEqualTo(Frames.ResumeGap.of(1, 10));
    verify(store, never()).listMessagesAfter(anyString(), anyLong(), anyInt(), any());
  }

  @Test
  @DisplayName("⑥ 心跳：超 40s 无息断连，满 25s 发 ping，刚说过话的既不 ping 也不断")
  void heartbeatClosesIdleAndPingsWarmSessions() {
    long now = System.currentTimeMillis();
    PushRegistry.Session dead = liveSession(rawSession(null), ServerKeys.randomBytes(32));
    dead.lastInboundMillis = now - 41_000;
    PushRegistry.Session warm = liveSession(rawSession(null), ServerKeys.randomBytes(32));
    warm.lastInboundMillis = now - 26_000;
    PushRegistry.Session fresh = liveSession(rawSession(null), ServerKeys.randomBytes(32));
    fresh.lastInboundMillis = now - 1_000;
    when(registry.sessions()).thenReturn(Map.of("dead", dead, "warm", warm, "fresh", fresh));

    handler.heartbeat();

    verify(registry).close(dead, "heartbeat_timeout");
    verify(registry).send(warm, Frames.Simple.ping());
    verify(registry, never()).send(eq(fresh), any());
    verify(registry, never()).close(eq(warm), anyString());
  }

  // ---------- helpers ----------

  private WebSocketSession rawSession(String query) {
    WebSocketSession raw = mock(WebSocketSession.class);
    when(raw.getId()).thenReturn("ws-test");
    when(raw.getAttributes()).thenReturn(new HashMap<>());
    if (query != null) {
      when(raw.getUri()).thenReturn(URI.create("ws://localhost/ws" + query));
    }
    return raw;
  }

  private PushRegistry.Session liveSession(WebSocketSession raw, byte[] nonce) {
    return new PushRegistry.Session(DEVICE_ID, GROUP_ID, raw,
        new FrameChannel(new byte[32], System.currentTimeMillis()), nonce, deviceEnc.publicKey());
  }

  private Row deviceRow() {
    Row row = new Row();
    row.set("device_id", DEVICE_ID);
    row.set("group_id", GROUP_ID);
    row.set("revoked_at", null);
    row.set("device_pubkey", B64.enc(deviceIdentity.publicKey()));
    row.set("enc_pubkey", B64.enc(deviceEnc.publicKey()));
    return row;
  }

  private static String validAuth(byte[] nonce, Ed25519.KeyPair identity) {
    long ts = System.currentTimeMillis();
    byte[] sig = Ed25519.sign(identity.privateKey(),
        SignatureTargets.wsAuth(DEVICE_ID, nonce, ts));
    return Json.write(Frames.Auth.of(DEVICE_ID, TOKEN, ts, B64.enc(sig)));
  }

  private static List<Row> rowsUpTo(long afterSeq, long latest) {
    List<Row> rows = new java.util.ArrayList<>();
    for (long seq = afterSeq + 1; seq <= Math.min(afterSeq + 2, latest); seq++) {
      Row row = new Row();
      row.set("seq", seq);
      rows.add(row);
    }
    return rows;
  }

  private static Frames.Message message(long seq) {
    return Frames.Message.of(seq, "12345678", "MSG-" + seq, "sender", "text", "标题", "摘要",
        "正文", "info", null, List.of(), "发送者", null, 1_700_000_000_000L);
  }
}
