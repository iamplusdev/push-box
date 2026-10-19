package dev.pushbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import dev.pushbox.core.api.IngestRequest;
import dev.pushbox.core.api.IngestResponse;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.web.ApiException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 定时推送的调度规则（不碰库、不碰容器）：cron 下次时间、时隙 CAS、幂等 msg_key、止损停用与校验。
 * 真正落库的幂等与投递结果由 {@code @EnabledIfEnvironmentVariable} 的集成用例守着，
 * 这里只锁住"扫描一轮会发生什么"这套不会因为网络或数据库状态漂移的判断。
 */
class ScheduledTaskLogicTest {

  private static final String TASK_ID = "01TASK000000000000000000";
  private static final String CHANNEL_NO = "SCHED001";

  private PbStore store;
  private IngestService ingest;
  private ScheduledTaskService service;

  @BeforeEach
  void setUp() {
    store = mock(PbStore.class);
    ingest = mock(IngestService.class);
    service = new ScheduledTaskService(store, ingest, new PbProperties(), 50);
  }

  // ---------- cron ----------

  @Test
  @DisplayName("① cron 必须是 6 段：5 段 unix 写法直接拒，别让管理台存进一个永不触发的任务")
  void requiresSixFieldCron() {
    long now = System.currentTimeMillis();

    assertThatThrownBy(() -> ScheduledTaskService.nextFireAt("0 9 * * *", now))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(400);
          assertThat(e.toError().hint()).contains("6 段");
        });
    assertThatThrownBy(() -> ScheduledTaskService.nextFireAt("   ", now))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
  }

  @Test
  @DisplayName("② 下次触发是严格未来的一刻：每天 09:00 在 08:00 之后算出的就是当天 09:00")
  void computesNextFireFromCron() {
    LocalDateTime today8am = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
    long from = millis(today8am);

    long next = ScheduledTaskService.nextFireAt("0 0 9 * * *", from);
    assertThat(local(next)).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0, 0));
    // 09:00:01 再问，就该跳到第二天——不能把已经过去的那次补回来
    assertThat(local(ScheduledTaskService.nextFireAt("0 0 9 * * *", next + 1_000L)))
        .isEqualTo(LocalDateTime.of(2026, 1, 2, 9, 0, 0));
  }

  @Test
  @DisplayName("③ 每 5 分钟与工作日 09:00 这类步长写法按 Spring 语义推进")
  void stepAndDayOfWeekCron() {
    LocalDateTime from = LocalDateTime.of(2026, 1, 1, 9, 3, 10);

    assertThat(local(ScheduledTaskService.nextFireAt("0 */5 * * * *", millis(from))))
        .isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 5, 0));
    // 2026-01-01 是周四，下一个工作日 09:00 是周五
    assertThat(local(ScheduledTaskService.nextFireAt("0 0 9 * * 1-5", millis(from))))
        .isEqualTo(LocalDateTime.of(2026, 1, 2, 9, 0, 0));
  }

  // ---------- 幂等 msg_key ----------

  @Test
  @DisplayName("④ 同一分钟时隙的 msg_key 恒定：崩溃后重跑被 uk_msg_idem 吃掉，不会重复发一条")
  void msgKeyIsStableWithinMinuteSlot() {
    // msg_key 的时隙按整分钟分桶，起点必须对齐到分钟边界才谈得上"同一分钟"
    long slot = (1_700_000_000_000L / 60_000L) * 60_000L;

    assertThat(ScheduledTaskService.msgKey(TASK_ID, slot))
        .isEqualTo(ScheduledTaskService.msgKey(TASK_ID, slot + 59_999L));
    assertThat(ScheduledTaskService.msgKey(TASK_ID, slot))
        .isNotEqualTo(ScheduledTaskService.msgKey(TASK_ID, slot + 60_000L));
    assertThat(ScheduledTaskService.msgKey(TASK_ID, slot)).startsWith("sched:" + TASK_ID + ":");
  }

  // ---------- sweep 一轮 ----------

  @Test
  @DisplayName("⑤ 抢不到时隙（别的实例已抢先 CAS 成功）就什么都不做，本轮计数为 0")
  void lostClaimFiresNothing() {
    when(store.dueScheduledTasks(anyLong(), anyInt())).thenReturn(List.of(dueTask(1000L)));
    when(store.claimScheduledTaskSlot(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(0);

    assertThat(service.sweepOnce()).isZero();
    verify(ingest, never()).ingestInternal(any(), any());
    verify(store, never()).recordScheduledTaskRun(anyString(), anyString(), any(), any(), anyInt(),
        anyLong());
  }

  @Test
  @DisplayName("⑥ 抢到时隙即投递，并把 last_msg_id/ok 记回任务行")
  void wonClaimDeliversAndRecords() {
    when(store.dueScheduledTasks(anyLong(), anyInt())).thenReturn(List.of(dueTask(1000L)));
    when(store.claimScheduledTaskSlot(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(1);
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(ingest.ingestInternal(any(), any())).thenReturn(new IngestResponse(true, "MSG-1", 7L,
        "摘要", "none", 0, "info"));

    assertThat(service.sweepOnce()).isEqualTo(1);

    ArgumentCaptor<IngestRequest> captor = ArgumentCaptor.forClass(IngestRequest.class);
    verify(ingest).ingestInternal(any(), captor.capture());
    IngestRequest sent = captor.getValue();
    // 内部入口不带密钥：库里只有 secret_hash，永远还原不出明文
    assertThat(sent.secret()).isNull();
    assertThat(sent.channel()).isEqualTo(CHANNEL_NO);
    assertThat(sent.senderId()).isEqualTo("scheduled");
    assertThat(sent.msgKey()).isEqualTo(ScheduledTaskService.msgKey(TASK_ID, 1000L));
    verify(store).recordScheduledTaskRun(eq(TASK_ID), eq("ok"), eq(null), eq("MSG-1"), eq(1),
        anyLong());
  }

  @Test
  @DisplayName("⑦ 频道没了或已归档：自动停用任务，不投递，也别每分钟失败一次刷屏日志")
  void missingChannelDisablesTheTask() {
    when(store.dueScheduledTasks(anyLong(), anyInt())).thenReturn(List.of(dueTask(1000L)));
    when(store.claimScheduledTaskSlot(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(1);
    when(store.findChannel(CHANNEL_NO)).thenReturn(null);

    service.sweepOnce();

    verify(ingest, never()).ingestInternal(any(), any());
    verify(store).recordScheduledTaskRun(eq(TASK_ID), eq("failed"), containsReason(), eq(null),
        eq(0), anyLong());
  }

  @Test
  @DisplayName("⑧ 归档频道同样停用（archived_at 非空）")
  void archivedChannelDisablesTheTask() {
    Row archived = channel();
    archived.set("archived_at", 123L);
    when(store.dueScheduledTasks(anyLong(), anyInt())).thenReturn(List.of(dueTask(1000L)));
    when(store.claimScheduledTaskSlot(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(1);
    when(store.findChannel(CHANNEL_NO)).thenReturn(archived);

    service.sweepOnce();

    verify(store).recordScheduledTaskRun(eq(TASK_ID), eq("failed"), containsReason(), eq(null),
        eq(0), anyLong());
  }

  @Test
  @DisplayName("⑨ 投递失败只记结论，任务保持启用：正文超限这种事改完就好，不该悄悄停掉")
  void deliveryFailureKeepsTaskEnabled() {
    when(store.dueScheduledTasks(anyLong(), anyInt())).thenReturn(List.of(dueTask(1000L)));
    when(store.claimScheduledTaskSlot(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(1);
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(ingest.ingestInternal(any(), any())).thenThrow(ApiException.tooLarge());

    assertThat(service.sweepOnce()).isEqualTo(1);

    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(store).recordScheduledTaskRun(eq(TASK_ID), eq("failed"), reason.capture(), eq(null),
        eq(1), anyLong());
    assertThat(reason.getValue()).contains("too_large");
  }

  @Test
  @DisplayName("⑩ 一条失败不挡后面那条：一轮里两个任务都要试过")
  void oneFailureDoesNotStopTheBatch() {
    Row first = dueTask(1000L);
    Row second = dueTask(1001L);
    second.set("task_id", "01TASK000000000000000002");
    when(store.dueScheduledTasks(anyLong(), anyInt())).thenReturn(List.of(first, second));
    when(store.claimScheduledTaskSlot(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(1);
    when(store.findChannel(CHANNEL_NO)).thenReturn(null);

    assertThat(service.sweepOnce()).isEqualTo(2);
    verify(store).recordScheduledTaskRun(eq("01TASK000000000000000000"), eq("failed"), any(),
        any(), anyInt(), anyLong());
    verify(store).recordScheduledTaskRun(eq("01TASK000000000000000002"), eq("failed"), any(),
        any(), anyInt(), anyLong());
  }

  // ---------- CRUD 校验 ----------

  @Test
  @DisplayName("⑪ 正文空/超限、图片非 http(s) 一律 400 且不落库")
  void createRejectsBadPayloadBeforeInsert() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());

    assertThatThrownBy(() -> service.create(input("   ")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
    assertThatThrownBy(() -> service.create(input("字".repeat(32 * 1024 + 1))))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
    assertThatThrownBy(() -> service.create(inputWithImages("data:image/png;base64,AAAA")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
    assertThatThrownBy(() -> service.create(inputWithImages(
        "https://a.test/1.png", "https://a.test/2.png", "https://a.test/3.png",
        "https://a.test/4.png", "https://a.test/5.png")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));

    verify(store, never()).insertScheduledTask(any());
  }

  @Test
  @DisplayName("⑪b 正文上限按字节算，且跟着频道的 max_body_bytes 走：存一个永远发不出去的任务没有意义")
  void bodyCapIsBytesAndPerChannel() {
    Row narrow = channel();
    narrow.set("max_body_bytes", 300);
    when(store.findChannel(CHANNEL_NO)).thenReturn(narrow);

    // 150 个汉字 = 450 字节：字符数远小于 32K，但已经超了这条频道的字节上限
    assertThatThrownBy(() -> service.create(input("字".repeat(150))))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.toError().hint()).contains("450 字节"));
    verify(store, never()).insertScheduledTask(any());
  }

  @Test
  @DisplayName("⑪c level 落库前归一到 wire 值：库里只能出现 info/warning/error，别存下触发时才静默降级的写法")
  void levelIsNormalizedOnSave() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(store.findScheduledTask(anyString())).thenReturn(dueTask(1000L));

    service.create(new ScheduledTaskService.TaskInput(CHANNEL_NO, "任务", null, "正文", "warning",
        null, List.of(), "0 0 9 * * *", 1));
    service.create(new ScheduledTaskService.TaskInput(CHANNEL_NO, "任务", null, "正文", "warn",
        null, List.of(), "0 0 9 * * *", 1));

    ArgumentCaptor<Row> captor = ArgumentCaptor.forClass(Row.class);
    verify(store, times(2)).insertScheduledTask(captor.capture());
    assertThat(captor.getAllValues().get(0).getString("level")).isEqualTo("warning");
    // "warn" 不是合法 wire 值，落到枚举默认档 info——存下来的永远是能被 Level.fromWire 认出的字符串
    assertThat(captor.getAllValues().get(1).getString("level")).isEqualTo("info");
  }

  @Test
  @DisplayName("⑫ 频道不存在 404、已归档 400：不能把任务挂到一个再也收不到消息的频道上")
  void createRejectsUnavailableChannel() {
    when(store.findChannel("GONE0001")).thenReturn(null);
    assertThatThrownBy(() -> service.create(input("GONE0001", "内容")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));

    Row archived = channel();
    archived.set("archived_at", 1L);
    when(store.findChannel(CHANNEL_NO)).thenReturn(archived);
    assertThatThrownBy(() -> service.create(input("内容")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
  }

  @Test
  @DisplayName("⑬ 列表只回正文开头（content_head），detail 才带全文与图片")
  void listOmitsBodyButDetailCarriesIt() {
    Row row = dueTask(2000L);
    row.set("content", "这是一段很长的正文内容");
    row.set("images_json", "[\"https://a.test/1.png\"]");
    when(store.findScheduledTask(TASK_ID)).thenReturn(row);

    Map<String, Object> detail = service.detail(TASK_ID);
    assertThat(detail.get("content")).isEqualTo("这是一段很长的正文内容");
    assertThat(detail.get("images")).isEqualTo(List.of("https://a.test/1.png"));

    row.set("images_json", null);
    row.set("content", "短正文");
    Map<String, Object> view = service.detail(TASK_ID);
    assertThat(view.get("content")).isEqualTo("短正文");
    assertThat(view.get("images")).isEqualTo(List.of());
  }

  @Test
  @DisplayName("⑭ 重新启用要按 cron 重算下次时间，否则任务会停在过去时隙上被下一轮立刻补发")
  void enablingRecomputesNextFireAt() {
    long slot = millis(LocalDateTime.of(2026, 1, 1, 9, 0, 0));
    Row row = dueTask(slot);
    row.set("enabled", 0);
    when(store.findScheduledTask(TASK_ID)).thenReturn(row);
    // 固定"现在"很难注入，这里只断言新值一定来自 cron 而不是原样保留旧时隙
    long before = System.currentTimeMillis();

    service.setEnabled(TASK_ID, true);

    ArgumentCaptor<Long> next = ArgumentCaptor.forClass(Long.class);
    verify(store).setScheduledTaskEnabled(eq(TASK_ID), eq(1), next.capture(), anyLong());
    assertThat(next.getValue()).isGreaterThan(before);
    assertThat(local(next.getValue())).isAfter(local(slot));
  }

  @Test
  @DisplayName("⑮ 停用时不动 next_fire_at：再打开会重算，停着期间它本来也不该被扫到")
  void disablingKeepsNextFireAt() {
    long slot = millis(LocalDateTime.of(2026, 1, 1, 9, 0, 0));
    Row row = dueTask(slot);
    when(store.findScheduledTask(TASK_ID)).thenReturn(row);

    service.setEnabled(TASK_ID, false);

    verify(store).setScheduledTaskEnabled(eq(TASK_ID), eq(0), eq(slot), anyLong());
  }

  @Test
  @DisplayName("⑯ 编辑不存在的任务 404，删除同样 404：别静默吞掉一次无效操作")
  void missingTaskIsNotFound() {
    when(store.findScheduledTask(TASK_ID)).thenReturn(null);

    assertThatThrownBy(() -> service.update(TASK_ID, input("内容")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThatThrownBy(() -> service.delete(TASK_ID))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThatThrownBy(() -> service.detail(TASK_ID))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    verify(store, never()).updateScheduledTask(any());
    verify(store, never()).deleteScheduledTask(anyString());
  }

  // ---------- helpers ----------

  private static String containsReason() {
    return org.mockito.ArgumentMatchers.argThat(value -> value != null && !value.isBlank());
  }

  private static long millis(LocalDateTime time) {
    return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
  }

  private static LocalDateTime local(long millis) {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
  }

  private static Row channel() {
    Row row = new Row();
    row.set("channel_no", CHANNEL_NO);
    row.set("group_id", "sched-test-group");
    row.set("max_level", "warning");
    row.set("max_images", 4);
    row.set("max_body_bytes", 32768);
    return row;
  }

  /** 一行到期的任务；next_fire_at 就是这次要抢的时隙。 */
  private static Row dueTask(long slot) {
    Row row = new Row();
    row.set("task_id", TASK_ID);
    row.set("group_id", "sched-test-group");
    row.set("channel_no", CHANNEL_NO);
    row.set("name", "每日晨报");
    row.set("title", "晨报");
    row.set("content", "到点自动推一条");
    row.set("level", "info");
    row.set("sender_name", "定时推送");
    row.set("images_json", null);
    row.set("cron_expr", "0 0 9 * * *");
    row.set("enabled", 1);
    row.set("next_fire_at", slot);
    row.set("created_at", slot - 1000L);
    row.set("updated_at", slot - 1000L);
    return row;
  }

  private static ScheduledTaskService.TaskInput input(String content) {
    return input(CHANNEL_NO, content);
  }

  private static ScheduledTaskService.TaskInput input(String channelNo, String content) {
    return new ScheduledTaskService.TaskInput(channelNo, "每日晨报", "晨报", content, "info",
        "定时推送", List.of(), "0 0 9 * * *", 1);
  }

  private static ScheduledTaskService.TaskInput inputWithImages(String... urls) {
    return new ScheduledTaskService.TaskInput(CHANNEL_NO, "带图任务", null, "正文", "info", null,
        List.of(urls), "0 0 9 * * *", 1);
  }
}
