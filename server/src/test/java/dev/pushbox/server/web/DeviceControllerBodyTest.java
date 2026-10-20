package dev.pushbox.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pushbox.server.push.PushRegistry;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.ChannelService;
import dev.pushbox.server.service.ConfigNotifier;
import dev.pushbox.server.service.DeviceService;
import dev.pushbox.server.service.ServerKeys;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 设备侧那八个写端点（频道六个 + register/join）与管理台同一条纪律：坏请求体是 400 信封，不是 500。
 *
 * <p>设备签名校验排在解析之前（{@link DeviceGuard#require} 先验 Ed25519），所以 curl 打不到解析那一跳，
 * 这条缺口只能在这一层钉住。收口落在 {@link RequestBodyJson}：{@code Json.readBytes} 与
 * {@code Json.readNode} 把 Jackson 的失败一律包成 IllegalArgumentException，而
 * GlobalExceptionHandler 不接它，症状是 500 + Spring 默认错误体。
 */
class DeviceControllerBodyTest {

  private static final byte[] BROKEN = "{\"channel_no\": \"123".getBytes(StandardCharsets.UTF_8);
  private static final byte[] NOT_JSON = "这不是 JSON".getBytes(StandardCharsets.UTF_8);
  private static final byte[] WHITESPACE = "   ".getBytes(StandardCharsets.UTF_8);
  private static final byte[] ROOT_ARRAY = "[1,2,3]".getBytes(StandardCharsets.UTF_8);

  private final ChannelService channels = mock(ChannelService.class);
  private final DeviceService devices = mock(DeviceService.class);
  private final PbStore store = mock(PbStore.class);
  private final DeviceGuard guard = mock(DeviceGuard.class);
  private final ConfigNotifier config = mock(ConfigNotifier.class);
  private final ServerKeys keys = mock(ServerKeys.class);
  private final PushRegistry registry = mock(PushRegistry.class);
  private final ChannelController channelController =
      new ChannelController(channels, guard, store, config);
  private final DeviceController deviceController =
      new DeviceController(devices, guard, keys, registry, config);

  /** seal 在真机上要 X25519 密钥对，形态也不止 Map；这一层只关心解析有没有把请求送到 service。 */
  private void echoSeal() {
    when(guard.seal(any(), any())).thenAnswer(invocation ->
        invocation.getArgument(1) instanceof Map<?, ?> payload
            ? payload : Map.of("ok", true));
  }

  @Test
  @DisplayName("① 六个频道写端点的坏请求体一律 400 invalid_param，不是 500")
  void channelEndpointsRejectMalformedBodies() {
    for (byte[] bad : new byte[][] {BROKEN, NOT_JSON, WHITESPACE, ROOT_ARRAY}) {
      assertEnvelope(() -> channelController.create(request(), bad));
      assertEnvelope(() -> channelController.join(request(), bad));
      assertEnvelope(() -> channelController.leave(request(), bad));
      assertEnvelope(() -> channelController.setFilterMode(request(), bad));
      assertEnvelope(() -> channelController.update("12345678", request(), bad));
      assertEnvelope(() -> channelController.putSender("12345678", request(), bad));
    }
  }

  @Test
  @DisplayName("② register / join 同一条纪律，且空请求体也算")
  void deviceRegisterAndJoinRejectMalformedBodies() {
    when(guard.ipAllowed(any())).thenReturn(true);
    for (byte[] bad : new byte[][] {BROKEN, NOT_JSON, WHITESPACE, ROOT_ARRAY}) {
      assertEnvelope(() -> deviceController.register(bad, request()));
      assertEnvelope(() -> deviceController.join(bad, request()));
    }
    assertEnvelope(() -> deviceController.register(new byte[0], request()));
  }

  @Test
  @DisplayName("③ 上限那道门没被解析失败盖掉：越界仍是「请求体过大」")
  void oversizeBodyKeepsItsOwnHint() {
    byte[] huge = "{\"channel_no\":\"".repeat(1000).getBytes(StandardCharsets.UTF_8);
    assertThat(huge).hasSizeGreaterThan(8192);
    when(guard.ipAllowed(any())).thenReturn(true);
    assertHint(() -> deviceController.register(huge, request()), "请求体过大");
    assertHint(() -> deviceController.join(huge, request()), "请求体过大");
  }

  @Test
  @DisplayName("④ PATCH 式 PUT 的两种越界分开报：坏 JSON 与不是对象不是一回事")
  void putDistinguishesUnparseableFromWrongShape() {
    assertHint(() -> channelController.update("12345678", request(), BROKEN), "请求体必须是合法 JSON");
    assertHint(() -> channelController.update("12345678", request(), ROOT_ARRAY),
        "请求体必须是一段 JSON 对象");
    assertHint(() -> channelController.update("12345678", request(), new byte[0]),
        "请求体必须是一段 JSON 对象");
  }

  @Test
  @DisplayName("⑤ 合法请求体照旧进得去：收口没把正常路径一起判死，PATCH 语义也没变")
  void validBodiesStillReachTheServices() {
    echoSeal();
    when(guard.require(any(), any())).thenReturn(
        new DeviceGuard.Authenticated(null, "ops-space", "d-1"));
    when(channels.join(any(), any(), any())).thenReturn(Map.of("ok", true));
    assertThat(channelController.join(request(),
        "{\"channel_no\":\"12345678\",\"secret\":\"s\"}".getBytes(StandardCharsets.UTF_8)))
        .containsEntry("ok", true);
    verify(channels).join(any(), eq("12345678"), eq("s"));

    when(channels.updateGovernance(any(), any(), any())).thenReturn(Map.of("channel_no", "12345678"));
    assertThat(channelController.update("12345678", request(),
        "{\"muted\":1}".getBytes(StandardCharsets.UTF_8)))
        .containsEntry("ok", true).containsEntry("channel_no", "12345678");
    // 只提交了 muted，其余治理位仍是 null（= 不动）。收口若把这里改成绑记录类，未提交的字段会被清空
    verify(channels).updateGovernance(eq("12345678"), eq("ops-space"),
        argThat(patch -> patch.muted() != null && patch.muted() == 1
            && patch.name() == null && patch.maxLevel() == null && patch.maxImages() == null
            && patch.rateLimit() == null && patch.maxBodyBytes() == null && patch.strictAuth() == null
            && patch.dailyMsgLimit() == null && patch.dailyImageLimit() == null
            && patch.dailyBytesLimit() == null && patch.senderWhitelistMode() == null));

    when(devices.register(any())).thenReturn(
        new DeviceService.Registration("d-1", "g-1", "token", "code"));
    when(guard.ipAllowed(any())).thenReturn(true);
    deviceController.register(
        "{\"enc_pubkey\":\"enc-a\",\"sign_pubkey\":\"sign-a\"}".getBytes(StandardCharsets.UTF_8),
        request());
    verify(devices).register(argThat(request -> "enc-a".equals(request.encPubkey())
        && "sign-a".equals(request.signPubkey())));
  }

  private void assertEnvelope(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
    assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status()).isEqualTo(400);
      assertThat(e.toError().error()).isEqualTo("invalid_param");
    });
  }

  private void assertHint(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String hint) {
    assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status()).isEqualTo(400);
      assertThat(e.toError().hint()).isEqualTo(hint);
    });
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/channels/join");
    request.addHeader("Authorization", "Bearer device-token");
    return request;
  }
}
