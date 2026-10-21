package dev.pushbox.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.crypto.B64;
import dev.pushbox.core.crypto.Ed25519;
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
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.websocket.server.WsSci;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.socket.adapter.standard.StandardWebSocketHandlerAdapter;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;

/**
 * §5.3 的真 socket 用例：前面的 {@link PushboxWsHandlerTest} 全是 mock，握手、kx 与密文载体
 * 从没经过 TCP。这里起一个真的 Tomcat，用 JDK 的 WebSocket 客户端走完 hello → auth → kx →
 * 补拉/心跳/轮换，客户端侧用 core 的同一套密码学解帧，所以服务端与客户端的线格式不一致会直接红。
 * 不碰数据库：所有存储与业务依赖都是 mock。
 */
@Timeout(value = 30)
class PushboxWsSocketTest {

  private static final String DEVICE_ID = "dev_socket";
  private static final String GROUP_ID = "grp_socket";
  private static final String TOKEN = "token_socket";

  private PbStore store;
  private DeviceService devices;
  private ServerKeys keys;
  private FrameAssembler assembler;
  private ChannelService channels;
  private PbProperties props;
  private PushboxWsHandler handler;

  private Tomcat tomcat;
  private java.nio.file.Path baseDir;
  private int port;

  private Ed25519.KeyPair serverIdentity;
  private Ed25519.KeyPair deviceIdentity;
  private X25519.KeyPair deviceEnc;

  @BeforeEach
  void startServer() throws Exception {
    store = mock(PbStore.class);
    devices = mock(DeviceService.class);
    keys = mock(ServerKeys.class);
    assembler = mock(FrameAssembler.class);
    channels = mock(ChannelService.class);
    props = new PbProperties();

    serverIdentity = Ed25519.generate();
    when(keys.identity()).thenReturn(serverIdentity);
    when(keys.fingerprint()).thenReturn(Ed25519.fingerprint(serverIdentity.publicKey()));
    when(keys.sign(any(byte[].class))).thenAnswer(
        call -> Ed25519.sign(serverIdentity.privateKey(), call.getArgument(0)));
    when(assembler.capFrame(any(Frames.Message.class), anyInt())).thenAnswer(
        call -> call.getArgument(0));
    when(channels.readScope(any())).thenReturn(Set.of());

    deviceIdentity = Ed25519.generate();
    deviceEnc = X25519.generate();
    when(devices.byToken(TOKEN)).thenReturn(deviceRow());

    PushRegistry registry = new PushRegistry(keys, props, store);
    handler = new PushboxWsHandler(store, devices, keys, registry, assembler, props, channels);

    tomcat = new Tomcat();
    baseDir = Files.createTempDirectory("pb-ws-test-");
    tomcat.setBaseDir(baseDir.toString());
    tomcat.setPort(0);
    tomcat.getConnector();
    Context ctx = tomcat.addContext("", null);
    // 没有 Wrapper 映射时 Tomcat 在过滤器之前就 404，WsFilter 根本没机会升级握手
    Tomcat.addServlet(ctx, "default", new org.apache.catalina.servlets.DefaultServlet());
    ctx.addServletMappingDecoded("/", "default");
    ctx.addServletContainerInitializer(new WsSci(), null);
    tomcat.start();
    ServerContainer container = (ServerContainer) ctx.getServletContext()
        .getAttribute(ServerContainer.class.getName());
    container.addEndpoint(ServerEndpointConfig.Builder
        .create(StandardWebSocketHandlerAdapter.class, "/ws")
        .configurator(new ServerEndpointConfig.Configurator() {
          @Override
          public <T> T getEndpointInstance(Class<T> clazz) {
            return clazz.cast(new StandardWebSocketHandlerAdapter(handler,
                new StandardWebSocketSession(null, null, null, null)));
          }
        }).build());
    port = tomcat.getConnector().getLocalPort();
  }

  @AfterEach
  void stopServer() throws Exception {
    if (tomcat != null) {
      tomcat.stop();
      tomcat.destroy();
    }
    if (baseDir != null) {
      try (var files = Files.walk(baseDir)) {
        files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  @Test
  @DisplayName("① 真 socket 走完 hello→auth→kx→补拉两帧→resume_ok，重放帧 notify=false")
  void handshakeAndReplayOverRealSocket() throws Exception {
    when(store.latestSeq(GROUP_ID)).thenReturn(2L);
    when(store.listMessagesAfter(eq(GROUP_ID), anyLong(), anyInt(), any())).thenAnswer(
        call -> List.of(row(1), row(2)));
    when(assembler.toFrame(any(Row.class), eq(false))).thenAnswer(
        call -> message(((Row) call.getArgument(0)).getLong("seq"), false));

    Client client = connect("?since_seq=0");
    Frames.Hello hello = Json.read(client.nextOfType(Frames.T_HELLO), Frames.Hello.class);
    byte[] nonce = B64.dec(hello.nonceB64());
    assertThat(nonce).hasSize(32);
    assertThat(hello.heartbeatSec()).isEqualTo(props.getWs().getHeartbeatSec());
    assertThat(hello.serverPubFp()).isEqualTo(Ed25519.fingerprint(serverIdentity.publicKey()));

    sendAuth(client, nonce, deviceIdentity);
    Frames.Kx kx = Json.read(client.nextOfType(Frames.T_KX), Frames.Kx.class);
    assertThat(kx.epoch()).isZero();
    FrameChannel channel = clientChannel(nonce, kx);

    Frames.Message first = Json.read(open(channel, client.nextOfType(Frames.T_ENC)),
        Frames.Message.class);
    Frames.Message second = Json.read(open(channel, client.nextOfType(Frames.T_ENC)),
        Frames.Message.class);
    Frames.Simple ok = Json.read(open(channel, client.nextOfType(Frames.T_ENC)),
        Frames.Simple.class);

    assertThat(List.of(first.seq(), second.seq())).containsExactly(1L, 2L);
    assertThat(first.notifyClient()).isFalse();
    assertThat(ok.t()).isEqualTo(Frames.T_RESUME_OK);
    verify(devices).touch(DEVICE_ID);
    verify(store, never()).latestSeq();
    client.close();
  }

  @Test
  @DisplayName("② 真 socket 心跳 ping/pong；帧数到阈值时明文 kx 插进流里，新密钥序号从 0 重启")
  void heartbeatAndMidStreamRotation() throws Exception {
    props.getWs().setRotateAfterFrames(2);
    when(store.latestSeq(GROUP_ID)).thenReturn(0L);

    Client client = connect("?since_seq=0");
    Frames.Hello hello = Json.read(client.nextOfType(Frames.T_HELLO), Frames.Hello.class);
    byte[] nonce = B64.dec(hello.nonceB64());
    sendAuth(client, nonce, deviceIdentity);
    FrameChannel channel = clientChannel(nonce,
        Json.read(client.nextOfType(Frames.T_KX), Frames.Kx.class));
    assertThat(Json.read(open(channel, client.nextOfType(Frames.T_ENC)), Frames.Simple.class).t())
        .isEqualTo(Frames.T_RESUME_OK);

    client.send(Json.write(Frames.Simple.ping()));
    assertThat(Json.read(open(channel, client.nextOfType(Frames.T_ENC)), Frames.Simple.class).t())
        .isEqualTo(Frames.T_PONG);

    client.send(Json.write(Frames.Simple.ping()));
    Frames.Kx rotated = Json.read(client.nextOfType(Frames.T_KX), Frames.Kx.class);
    assertThat(rotated.epoch()).isEqualTo(1);
    FrameChannel next = clientChannel(nonce, rotated);
    assertThat(next.epoch()).isZero();
    assertThat(Json.read(open(next, client.nextOfType(Frames.T_ENC)), Frames.Simple.class).t())
        .isEqualTo(Frames.T_PONG);
    client.close();
  }

  @Test
  @DisplayName("③ 真 socket 落后超阈值只回 resume_gap，不逐条取库")
  void resumeGapOverRealSocket() throws Exception {
    props.getWs().setGapThreshold(3);
    when(store.latestSeq(GROUP_ID)).thenReturn(10L);

    Client client = connect("?since_seq=0");
    Frames.Hello hello = Json.read(client.nextOfType(Frames.T_HELLO), Frames.Hello.class);
    byte[] nonce = B64.dec(hello.nonceB64());
    sendAuth(client, nonce, deviceIdentity);
    FrameChannel channel = clientChannel(nonce,
        Json.read(client.nextOfType(Frames.T_KX), Frames.Kx.class));

    Frames.ResumeGap gap = Json.read(open(channel, client.nextOfType(Frames.T_ENC)),
        Frames.ResumeGap.class);
    assertThat(gap.missingFrom()).isEqualTo(1);
    assertThat(gap.latestSeq()).isEqualTo(10);
    verify(store, never()).listMessagesAfter(any(), anyLong(), anyInt(), any());
    client.close();
  }

  @Test
  @DisplayName("④ 真 socket 坏签名：先收 auth_fail=bad_signature，再被 1008 断开")
  void badSignatureClosesRealConnection() throws Exception {
    Client client = connect("?since_seq=0");
    Frames.Hello hello = Json.read(client.nextOfType(Frames.T_HELLO), Frames.Hello.class);
    byte[] nonce = B64.dec(hello.nonceB64());
    sendAuth(client, nonce, Ed25519.generate());

    Frames.AuthFail fail = Json.read(client.nextOfType(Frames.T_AUTH_FAIL), Frames.AuthFail.class);
    assertThat(fail.error()).isEqualTo("bad_signature");
    assertThat(client.awaitClose()).isEqualTo(1008);
    verify(assembler, never()).toFrame(any(Row.class), eq(false));
  }

  // ---------- helpers ----------

  private Client connect(String query) {
    Client client = new Client();
    client.socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        .newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
        .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws" + query), client).join();
    return client;
  }

  private void sendAuth(Client client, byte[] nonce, Ed25519.KeyPair identity) {
    long ts = System.currentTimeMillis();
    byte[] sig = Ed25519.sign(identity.privateKey(),
        SignatureTargets.wsAuth(DEVICE_ID, nonce, ts));
    client.send(Json.write(Frames.Auth.of(DEVICE_ID, TOKEN, ts, B64.enc(sig))));
  }

  private FrameChannel clientChannel(byte[] nonce, Frames.Kx kx) {
    assertThat(Ed25519.verify(B64.dec(kx.serverPubB64()),
        SignatureTargets.kx(B64.dec(kx.ephPubB64()), nonce), B64.dec(kx.sigB64()))).isTrue();
    byte[] shared = X25519.agree(deviceEnc.privateKey(), B64.dec(kx.ephPubB64()));
    return new FrameChannel(FrameChannel.sessionKey(shared, nonce), System.currentTimeMillis());
  }

  private static String open(FrameChannel channel, String wire) {
    assertThat(wire).as("服务端应发出加密帧").isNotNull();
    String plain = Envelope.open(channel, wire);
    assertThat(plain).as("密文帧必须能按序号解开").isNotNull();
    return plain;
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

  private static Row row(long seq) {
    Row row = new Row();
    row.set("seq", seq);
    return row;
  }

  private static Frames.Message message(long seq, boolean notify) {
    return new Frames.Message(Frames.T_MESSAGE, seq, "chan-1", "MSG-" + seq, "sender", "text",
        "标题", "摘要", "正文", "info", null, List.of(), "发送者", null,
        1_700_000_000_000L, notify, false);
  }

  private static final class Client implements WebSocket.Listener {

    private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private final StringBuilder partial = new StringBuilder();
    private final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private WebSocket socket;

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        frames.add(partial.toString());
        partial.setLength(0);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      closed.complete(statusCode);
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      closed.completeExceptionally(error);
    }

    void send(String wire) {
      socket.sendText(wire, true).join();
    }

    String nextOfType(String type) throws InterruptedException {
      for (int i = 0; i < 16; i++) {
        String wire = frames.poll(5, TimeUnit.SECONDS);
        if (wire == null) {
          return null;
        }
        if (type.equals(Envelope.typeOf(wire))) {
          return wire;
        }
      }
      return null;
    }

    int awaitClose() throws Exception {
      return closed.get(5, TimeUnit.SECONDS);
    }

    void close() {
      socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
    }
  }
}
