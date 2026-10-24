package dev.pushbox.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mybatisflex.core.row.Row;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.ChannelService;
import dev.pushbox.server.service.DeviceService;
import dev.pushbox.server.service.ScheduledTaskService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 绑定 {@code byte[]} 的四个管理台写端点：坏请求体必须是 400 信封，不能是 500。
 *
 * <p>这几个端点不直接绑请求记录类，而是先拿到原始字节再做两道检查（8KB / 64KB 的上限按字节判，
 * 绑成记录类就没机会读长度了）。代价是解析失败从 Spring 的
 * {@code HttpMessageNotReadableException}（有 handler，返 400 信封）变成了
 * {@code Json.readBytes} 抛的 {@code IllegalArgumentException}（没有 handler，
 * 于是 500 + Spring 默认错误体，与全站 {@code {ok,error,hint}} 纪律两套形状）。
 * 2026-10-20 在活服务端上逐个打坏请求体实测到的就是后者，收口因此落在 {@link RequestBodyJson}，
 * 设备侧那八个端点共用同一处。
 *
 * <p>⑥⑦ 管的是同一道门后面那一步：{@code POST /admin/channels} 的 {@code groupId} 从 2026-10-21
 * 起必填且必须是 {@code groups} 表里真有的一行，不再回退到 {@link DeviceGuard} 那个虚拟的
 * {@code "admin"}。
 */
class AdminControllerBodyTest {

  private final ChannelService channels = mock(ChannelService.class);
  private final DeviceService devices = mock(DeviceService.class);
  private final ScheduledTaskService scheduledTasks = mock(ScheduledTaskService.class);
  private final PbStore store = mock(PbStore.class);
  private final DeviceGuard guard = mock(DeviceGuard.class);
  private final AdminController controller =
      new AdminController(channels, devices, scheduledTasks, store, guard);

  @Test
  @DisplayName("① 四个写端点的坏 JSON 一律 400 invalid_param，并带上那句人话")
  void malformedBodiesBecomeFourHundredNotFiveHundred() {
    byte[] broken = "{\"name\": \"push".getBytes(StandardCharsets.UTF_8);
    byte[] text = "这根本不是一段 JSON".getBytes(StandardCharsets.UTF_8);
    byte[] whitespace = "   ".getBytes(StandardCharsets.UTF_8);
    // 类型不符：根节点是个数组，绑不进这三个请求记录类里的任何一个（换成"某个字段类型错"会有
    // 恰好转得过去的那一个，测试就成了在测 Jackson 的宽容度）
    byte[] wrongShape = "[1,2,3]".getBytes(StandardCharsets.UTF_8);

    for (byte[] bad : new byte[][] {broken, text, whitespace, wrongShape}) {
      assertThatThrownBy(() -> controller.createChannel(bad, request()))
          .isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.toError().error()).isEqualTo("invalid_param");
            assertThat(e.toError().hint()).isEqualTo("请求体必须是合法 JSON");
          });
      assertThatThrownBy(() -> controller.createScheduledTask(bad, request()))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
      assertThatThrownBy(() -> controller.previewScheduledTask(bad, request()))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
      assertThatThrownBy(() -> controller.updateScheduledTask("t-1", bad, request()))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
      assertThatThrownBy(() -> controller.setScheduledTaskEnabled("t-1", bad, request()))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
    }
  }

  @Test
  @DisplayName("② 空请求体也走同一条 400（此前 Spring 报的是另一句话，形状仍须是信封）")
  void emptyBodyIsRejectedLikeTheRest() {
    assertThatThrownBy(() -> controller.setScheduledTaskEnabled("t-1",
        new byte[0], request()))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(400);
          assertThat(e.toError().hint()).isEqualTo("请求体必须是合法 JSON");
        });
  }

  @Test
  @DisplayName("③ 上限那道门没被解析失败盖掉：越界仍是「请求体过大」而不是「不是合法 JSON」")
  void oversizeBodyKeepsItsOwnHint() {
    byte[] hugeChannel = repeat("{\"name\": \"", 2000).getBytes(StandardCharsets.UTF_8);
    assertThat(hugeChannel).hasSizeGreaterThan(8192);
    assertThatThrownBy(() -> controller.createChannel(hugeChannel, request()))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(400);
          assertThat(e.toError().hint()).isEqualTo("请求体过大");
        });

    byte[] hugeTask = repeat("{\"content\": \"", 9000).getBytes(StandardCharsets.UTF_8);
    assertThat(hugeTask).hasSizeGreaterThan(64 * 1024);
    assertThatThrownBy(() -> controller.createScheduledTask(hugeTask, request()))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(400);
          assertThat(e.toError().hint()).contains("65536");
        });
  }

  @Test
  @DisplayName("④ 带真实 groupId 的合法请求体照旧进得去：这道修复没把正常路径一起判死")
  void validBodyStillReachesTheService() {
    when(guard.require(any(), any())).thenReturn(
        new DeviceGuard.Authenticated(null, "admin", "admin"));
    when(store.findGroup("ops-space")).thenReturn(new Row());
    when(channels.create(any(), any(), any(), any())).thenReturn(
        new ChannelService.Created("12345678", "secret-issued-once", "运维播报", "warning", 4));
    Map<String, Object> channel = controller.createChannel(
        "{\"groupId\":\"ops-space\",\"name\":\"运维播报\"}".getBytes(StandardCharsets.UTF_8), request());
    assertThat(channel).containsEntry("ok", true).containsEntry("channel_no", "12345678");
    // 频道建在提交的那个 Space 上，不再回退到登录态那个虚拟的 "admin"
    verify(channels).create("ops-space", "运维播报", null, null);

    when(scheduledTasks.setEnabled(anyString(), anyBoolean())).thenReturn(Map.of("task_id", "t-1"));
    assertThat(controller.setScheduledTaskEnabled("t-1", "{\"enabled\":0}".getBytes(
        StandardCharsets.UTF_8), request())).containsEntry("ok", true);
    verify(scheduledTasks).setEnabled("t-1", false);
  }

  @Test
  @DisplayName("⑤ 合法对象后面拖一段垃圾：以前 200 且真执行，现在和坏体同一条 400")
  void trailingGarbageAfterAValidObjectIsRejected() {
    // 收口在 core 的 Json.MAPPER（FAIL_ON_TRAILING_TOKENS），这一层只确认它被收成 400 信封
    for (byte[] bad : new byte[][] {
        "{\"name\":\"运维播报\"}x".getBytes(StandardCharsets.UTF_8),
        "{\"enabled\":1}{\"enabled\":0}".getBytes(StandardCharsets.UTF_8)}) {
      assertThatThrownBy(() -> controller.createChannel(bad, request()))
          .isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.toError().hint()).isEqualTo("请求体必须是合法 JSON");
          });
      assertThatThrownBy(() -> controller.setScheduledTaskEnabled("t-1", bad, request()))
          .isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.toError().hint()).isEqualTo("请求体必须是合法 JSON");
          });
    }
  }

  @Test
  @DisplayName("⑥ groupId 省略或空白一律 400：不会再悄悄建进设备查不到的虚拟 admin Space")
  void missingOrBlankGroupIdIsRejected() {
    when(guard.require(any(), any())).thenReturn(
        new DeviceGuard.Authenticated(null, "admin", "admin"));
    for (byte[] body : new byte[][] {
        "{\"name\":\"运维播报\"}".getBytes(StandardCharsets.UTF_8),
        "{\"groupId\":\"\",\"name\":\"运维播报\"}".getBytes(StandardCharsets.UTF_8),
        "{\"groupId\":\"   \",\"name\":\"运维播报\"}".getBytes(StandardCharsets.UTF_8)}) {
      assertThatThrownBy(() -> controller.createChannel(body, request()))
          .isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.toError().error()).isEqualTo("invalid_param");
            assertThat(e.toError().hint()).contains("groupId");
          });
    }
    verifyNoInteractions(channels);
  }

  @Test
  @DisplayName("⑦ groups 表里查无此 Space 就 400；提交带空白的合法 id 会 trim 后落库")
  void unknownGroupIdIsRejectedKnownOneIsTrimmed() {
    when(guard.require(any(), any())).thenReturn(
        new DeviceGuard.Authenticated(null, "admin", "admin"));
    assertThatThrownBy(() -> controller.createChannel(
        "{\"groupId\":\"ghost\",\"name\":\"运维播报\"}".getBytes(StandardCharsets.UTF_8), request()))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(400);
          assertThat(e.toError().hint()).contains("groupId 不存在");
        });
    verifyNoInteractions(channels);

    when(store.findGroup("ops-space")).thenReturn(new Row());
    when(channels.create(any(), any(), any(), any())).thenReturn(
        new ChannelService.Created("12345678", "secret", "运维播报", "info", 4));
    controller.createChannel(
        "{\"groupId\":\" ops-space \",\"name\":\"运维播报\"}".getBytes(StandardCharsets.UTF_8), request());
    verify(channels).create("ops-space", "运维播报", null, null);
  }

  private static String repeat(String unit, int times) {
    return unit.repeat(times);
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/channels");
    request.addHeader("Authorization", "Bearer a.b.c");
    return request;
  }
}
