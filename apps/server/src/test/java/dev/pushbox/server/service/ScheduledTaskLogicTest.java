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
import dev.pushbox.server.config.ServerIdentity;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.web.ApiException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 定时推送的调度规则（不碰库、不碰容器）：cron 下次时间、时区、正文变量、时隙 CAS、幂等 msg_key、
 * 止损停用与校验。真正落库的幂等与投递结果由 ScheduledTaskStoreTest 那批集成用例守着，
 * 这里只锁住"扫描一轮会发生什么"这套不会因为网络或数据库状态漂移的判断。
 */
class ScheduledTaskLogicTest {

  private static final String TASK_ID = "01TASK000000000000000000";
  private static final String CHANNEL_NO = "SCHED001";
  /** 用例统一按上海时间算，不跟跑测试那台机器的默认时区走。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private PbStore store;
  private IngestService ingest;
  private ScheduledTaskService service;

  @BeforeEach
  void setUp() {
    store = mock(PbStore.class);
    ingest = mock(IngestService.class);
    service = new ScheduledTaskService(store, ingest, new PbProperties(),
        new ServerIdentity(Optional.empty()), 50);
  }

  // ---------- cron ----------

  @Test
  @DisplayName("① cron 必须是 6 段：5 段 unix 写法直接拒，别让管理台存进一个永不触发的任务")
  void requiresSixFieldCron() {
    long now = System.currentTimeMillis();

    assertThatThrownBy(() -> ScheduledTaskService.nextFireAt("0 9 * * *", now, ZONE))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(400);
          assertThat(e.toError().hint()).contains("6 段");
        });
    assertThatThrownBy(() -> ScheduledTaskService.nextFireAt("   ", now, ZONE))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
  }

  @Test
  @DisplayName("② 下次触发是严格未来的一刻：每天 09:00 在 08:00 之后算出的就是当天 09:00")
  void computesNextFireFromCron() {
    LocalDateTime today8am = LocalDateTime.of(2026, 1, 1, 8, 0, 0);
    long from = millis(today8am);

    long next = ScheduledTaskService.nextFireAt("0 0 9 * * *", from, ZONE);
    assertThat(local(next)).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0, 0));
    // 09:00:01 再问，就该跳到第二天——不能把已经过去的那次补回来
    assertThat(local(ScheduledTaskService.nextFireAt("0 0 9 * * *", next + 1_000L, ZONE)))
        .isEqualTo(LocalDateTime.of(2026, 1, 2, 9, 0, 0));
  }

  @Test
  @DisplayName("③ 每 5 分钟与工作日 09:00 这类步长写法按 Spring 语义推进")
  void stepAndDayOfWeekCron() {
    LocalDateTime from = LocalDateTime.of(2026, 1, 1, 9, 3, 10);

    assertThat(local(ScheduledTaskService.nextFireAt("0 */5 * * * *", millis(from), ZONE)))
        .isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 5, 0));
    // 2026-01-01 是周四，下一个工作日 09:00 是周五
    assertThat(local(ScheduledTaskService.nextFireAt("0 0 9 * * 1-5", millis(from), ZONE)))
        .isEqualTo(LocalDateTime.of(2026, 1, 2, 9, 0, 0));
  }

  @Test
  @DisplayName("③b cron 不带时区：同一个表达式在东京比在上海早一小时触发")
  void cronIsInterpretedInTheTaskZone() {
    // 2026-01-01T00:00:00Z = 上海 08:00 = 东京 09:00
    long from = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();

    assertThat(Instant.ofEpochMilli(
        ScheduledTaskService.nextFireAt("0 0 9 * * *", from, ZoneId.of("Asia/Shanghai"))))
        .isEqualTo(Instant.parse("2026-01-01T01:00:00Z"));
    // 东京此刻正好 09:00，严格未来就只能是第二天 09:00 JST
    assertThat(Instant.ofEpochMilli(
        ScheduledTaskService.nextFireAt("0 0 9 * * *", from, ZoneId.of("Asia/Tokyo"))))
        .isEqualTo(Instant.parse("2026-01-02T00:00:00Z"));
  }

  @Test
  @DisplayName("③c 时区非法就 400，不落库；空值落到默认 Asia/Shanghai")
  void invalidTimezoneIsRejected() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());

    assertThatThrownBy(() -> service.create(inputWithTimezone("Mars/Olympus")))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(400);
          assertThat(e.toError().hint()).contains("timezone");
        });
    verify(store, never()).insertScheduledTask(any());

    assertThat(ScheduledTaskService.zoneOf(null).getId()).isEqualTo("Asia/Shanghai");
    assertThat(ScheduledTaskService.zoneOf("  ").getId()).isEqualTo("Asia/Shanghai");
    assertThat(ScheduledTaskService.zoneOf("UTC").getId()).isEqualTo("UTC");
  }

  // ---------- 多条 cron ----------

  @Test
  @DisplayName("③d 多条 cron 取最近的一次：工作日 09:00 + 周末 12:00，周四问是周五，周五 09:01 问是周六")
  void earliestAcrossMultipleCrons() {
    String cron = "0 0 9 * * 1-5\n0 0 12 * * 0,6";

    // 2026-01-01 周四 09:03：工作日那条给周五 09:00，周末那条给周六 12:00，取近的
    assertThat(local(ScheduledTaskService.nextFireAt(cron,
        millis(LocalDateTime.of(2026, 1, 1, 9, 3, 0)), ZONE)))
        .isEqualTo(LocalDateTime.of(2026, 1, 2, 9, 0, 0));
    // 2026-01-02 周五 09:01：工作日那条已经要等到周一，周末那条是周六 12:00
    assertThat(local(ScheduledTaskService.nextFireAt(cron,
        millis(LocalDateTime.of(2026, 1, 2, 9, 1, 0)), ZONE)))
        .isEqualTo(LocalDateTime.of(2026, 1, 3, 12, 0, 0));
  }

  @Test
  @DisplayName("③e 空行与 CRLF 不算一条：textarea 里多敲的回车不该让任务存不下来")
  void blankLinesAndCrlfAreIgnored() {
    String cron = "  0 0 9 * * 1-5  \r\n\r\n0 0 12 * * 0,6\r\n";

    assertThat(ScheduledTaskService.splitCrons(cron))
        .containsExactly("0 0 9 * * 1-5", "0 0 12 * * 0,6");
    assertThat(local(ScheduledTaskService.nextFireAt(cron,
        millis(LocalDateTime.of(2026, 1, 1, 9, 3, 0)), ZONE)))
        .isEqualTo(LocalDateTime.of(2026, 1, 2, 9, 0, 0));
  }

  @Test
  @DisplayName("③f 多行里坏了一条要说清是第几条：八行挑一个错字段，只说「cron 不合法」等于让人逐行试")
  void invalidLineIsReportedWithItsNumber() {
    assertThatThrownBy(() -> ScheduledTaskService.nextFireAt(
        "0 0 9 * * *\n0 9 * * *", System.currentTimeMillis(), ZONE))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(400);
          assertThat(e.toError().hint()).contains("第 2 条").contains("6 段");
        });
    // 单行时保持老措辞，不去编一个"第 1 条"
    assertThatThrownBy(() -> ScheduledTaskService.nextFireAt(
        "0 9 * * *", System.currentTimeMillis(), ZONE))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.toError().hint()).startsWith("cron 不合法"));
  }

  @Test
  @DisplayName("③g 超过 8 条 400：上限是为了让调度规则还看得过来，不是为了省空间")
  void tooManyCronsAreRejected() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    String nine = String.join("\n", java.util.Collections.nCopies(9, "0 0 9 * * *"));

    assertThatThrownBy(() -> ScheduledTaskService.nextFireAt(
        nine, System.currentTimeMillis(), ZONE))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(400);
          assertThat(e.toError().hint()).contains("最多 8 条").contains("9 条");
        });
    assertThatThrownBy(() -> service.create(inputWithCron(nine)))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(400);
          assertThat(e.toError().hint()).contains("最多 8 条");
        });
    verify(store, never()).insertScheduledTask(any());
  }

  @Test
  @DisplayName("③h 两条规则撞在同一秒也只触发一次：一轮扫描对每个任务只投递一条")
  void overlappingCronsFireOncePerSlot() {
    long slot = millis(LocalDateTime.of(2026, 1, 2, 9, 0, 0));
    Row task = dueTask(slot);
    task.set("cron_expr", "0 0 9 * * *\n0 0 9 * * 1-5");
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(store.dueScheduledTasks(anyLong(), anyInt())).thenReturn(List.of(task));
    when(store.claimScheduledTaskSlot(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(1);
    when(ingest.ingestInternal(any(), any())).thenReturn(new IngestResponse(true, "MSG-1", 7L,
        "摘要", "none", 0, "info"));

    assertThat(service.sweepOnce()).isEqualTo(1);

    verify(ingest, times(1)).ingestInternal(any(), any());
    ArgumentCaptor<IngestRequest> captor = ArgumentCaptor.forClass(IngestRequest.class);
    verify(ingest).ingestInternal(any(), captor.capture());
    assertThat(captor.getValue().msgKey()).isEqualTo(ScheduledTaskService.msgKey(TASK_ID, slot));
  }

  @Test
  @DisplayName("③i 落库存按行归一化后的 cron：多行用 \\n 连接、单行原样")
  void cronIsNormalizedBeforePersisting() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(store.findScheduledTask(anyString())).thenReturn(dueTask(1000L));

    service.create(inputWithCron("  0 0 9 * * *  \r\n\r\n0 0 12 * * 0,6 "));
    service.create(inputWithCron("0 0 9 * * *"));

    ArgumentCaptor<Row> captor = ArgumentCaptor.forClass(Row.class);
    verify(store, times(2)).insertScheduledTask(captor.capture());
    assertThat(captor.getAllValues().get(0).getString("cron_expr"))
        .isEqualTo("0 0 9 * * *\n0 0 12 * * 0,6");
    assertThat(captor.getAllValues().get(1).getString("cron_expr")).isEqualTo("0 0 9 * * *");
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
  @DisplayName("⑥b 触发时把任务自带的 sender_id / sender_avatar 带进内部摄入：客户端才知道该归到谁")
  void firePassesTaskSenderIdentity() {
    Row task = dueTask(1000L);
    task.set("sender_id", "OnceHuman");
    task.set("sender_avatar", "https://cdn.test/avatar.png");
    when(store.dueScheduledTasks(anyLong(), anyInt())).thenReturn(List.of(task));
    when(store.claimScheduledTaskSlot(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(1);
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(ingest.ingestInternal(any(), any())).thenReturn(new IngestResponse(true, "MSG-1", 7L,
        "摘要", "none", 0, "info"));

    service.sweepOnce();

    ArgumentCaptor<IngestRequest> captor = ArgumentCaptor.forClass(IngestRequest.class);
    verify(ingest).ingestInternal(any(), captor.capture());
    assertThat(captor.getValue().senderId()).isEqualTo("OnceHuman");
    assertThat(captor.getValue().senderAvatar()).isEqualTo("https://cdn.test/avatar.png");
  }

  @Test
  @DisplayName("⑥c 扫描行缺 sender_id 时回落到 scheduled，别把 null 送进 NOT NULL 的 senders 表")
  void missingSenderIdFallsBackToScheduled() {
    Row task = dueTask(1000L);
    task.set("sender_id", null);
    when(store.dueScheduledTasks(anyLong(), anyInt())).thenReturn(List.of(task));
    when(store.claimScheduledTaskSlot(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(1);
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(ingest.ingestInternal(any(), any())).thenReturn(new IngestResponse(true, "MSG-1", 7L,
        "摘要", "none", 0, "info"));

    service.sweepOnce();

    ArgumentCaptor<IngestRequest> captor = ArgumentCaptor.forClass(IngestRequest.class);
    verify(ingest).ingestInternal(any(), captor.capture());
    assertThat(captor.getValue().senderId()).isEqualTo("scheduled");
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

  @Test
  @DisplayName("⑩b 触发那一刻才展开变量：${scheduled_at} 是计划时隙、${timezone} 是任务时区、认不出的原样保留")
  void contentIsRenderedAtFireTime() {
    long slot = millis(LocalDateTime.of(2026, 1, 1, 9, 0, 0));
    Row task = dueTask(slot);
    task.set("timezone", "Asia/Tokyo");
    task.set("name", "晨报");
    task.set("content", "计划 ${scheduled_at}（${timezone}）发出，未知 ${nope} 保持原样");
    when(store.dueScheduledTasks(anyLong(), anyInt())).thenReturn(List.of(task));
    when(store.claimScheduledTaskSlot(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(1);
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(ingest.ingestInternal(any(), any())).thenReturn(new IngestResponse(true, "MSG-1", 7L,
        "摘要", "none", 0, "info"));

    service.sweepOnce();

    ArgumentCaptor<IngestRequest> captor = ArgumentCaptor.forClass(IngestRequest.class);
    verify(ingest).ingestInternal(any(), captor.capture());
    // 上海 09:00 的时隙落到东京是 10:00；计划时刻与实际渲染时刻分开，补跑时也不会算错
    assertThat(captor.getValue().content())
        .isEqualTo("计划 2026-01-01 10:00:00（Asia/Tokyo）发出，未知 ${nope} 保持原样");
  }

  @Test
  @DisplayName("⑩c 正文里写 ${timezone:UTC} 也压不过任务时区：渲染时区只有一个来源")
  void timezoneInContentNeverOverridesTheTaskZone() {
    Row task = dueTask(1000L);
    task.set("timezone", "Europe/Berlin");
    task.set("content", "${timezone:UTC} / ${scheduled_at}");
    when(store.dueScheduledTasks(anyLong(), anyInt())).thenReturn(List.of(task));
    when(store.claimScheduledTaskSlot(anyString(), anyLong(), anyLong(), anyLong())).thenReturn(1);
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(ingest.ingestInternal(any(), any())).thenReturn(new IngestResponse(true, "MSG-1", 7L,
        "摘要", "none", 0, "info"));

    service.sweepOnce();

    ArgumentCaptor<IngestRequest> captor = ArgumentCaptor.forClass(IngestRequest.class);
    verify(ingest).ingestInternal(any(), captor.capture());
    assertThat(captor.getValue().content())
        .isEqualTo("Europe/Berlin / 1970-01-01 01:00:01");
  }

  @Test
  @DisplayName("⑩d 表单预览：时区与 ${scheduled_at} 跟触发时同源，${channel_name} 取频道行的 name 列")
  void previewRendersLikeTheRealFire() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    ScheduledTaskService.TaskInput input = new ScheduledTaskService.TaskInput(CHANNEL_NO,
        "晨报", "标题", "${timezone} / ${channel_name} / ${scheduled_at}", "info", "定时推送",
        List.of(), "0 0 9 * * *", 1, null, "Asia/Tokyo", null, null);

    Map<String, Object> res = service.preview(input);

    long now = (Long) res.get("now");
    ZoneId tokyo = ZoneId.of("Asia/Tokyo");
    long slot = ScheduledTaskService.nextFireAt("0 0 9 * * *", now, tokyo);
    String slotText = LocalDateTime.ofInstant(Instant.ofEpochMilli(slot), tokyo)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    assertThat(res.get("timezone")).isEqualTo("Asia/Tokyo");
    assertThat(res.get("next_fire_at")).isEqualTo(slot);
    // ${channel_name} 以前读的是频道行里根本不存在的 channel_name 列，预览把它照出来了
    assertThat(res.get("content")).isEqualTo("Asia/Tokyo / 运维频道 / " + slotText);
    verify(store, never()).insertScheduledTask(any());
  }

  @Test
  @DisplayName("⑩e 表单预览：渲染后超限如实标出，但预览本身不落库、也不抛 400")
  void previewReportsOverLimitWithoutPersisting() {
    Row small = channel();
    small.set("max_body_bytes", 8);
    when(store.findChannel(CHANNEL_NO)).thenReturn(small);
    ScheduledTaskService.TaskInput input = new ScheduledTaskService.TaskInput(CHANNEL_NO,
        "晨报", null, "一二三四五六七八九十", "info", null, List.of(),
        "0 0 9 * * *", 1, null, null, null, null);

    Map<String, Object> res = service.preview(input);

    assertThat(res.get("bytes")).isEqualTo(30);
    assertThat(res.get("max_body_bytes")).isEqualTo(8);
    assertThat(res.get("over_limit")).isEqualTo(true);
    verify(store, never()).insertScheduledTask(any());
    verify(store, never()).updateScheduledTask(any());
  }

  @Test
  @DisplayName("⑩f 预览逐条回下次触发：两行 cron 各自的时刻，总的是最近的那个")
  void previewReturnsPerLineNextFire() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    ScheduledTaskService.TaskInput input = new ScheduledTaskService.TaskInput(CHANNEL_NO,
        "晨报", null, "正文", "info", null, List.of(),
        "0 0 9 * * 1-5\n0 0 12 * * 0,6", 1, null, "Asia/Shanghai", null, null);

    Map<String, Object> res = service.preview(input);

    long now = (Long) res.get("now");
    List<?> fires = (List<?>) res.get("next_fires");
    assertThat(fires).hasSize(2);
    Map<?, ?> weekday = (Map<?, ?>) fires.get(0);
    Map<?, ?> weekend = (Map<?, ?>) fires.get(1);
    // 顺序跟填写顺序一致，管理台才能把"下次触发"贴回对应的那一行
    assertThat(weekday.get("cron")).isEqualTo("0 0 9 * * 1-5");
    assertThat(weekend.get("cron")).isEqualTo("0 0 12 * * 0,6");
    assertThat(weekday.get("next_fire_at"))
        .isEqualTo(ScheduledTaskService.nextFireAt("0 0 9 * * 1-5", now, ZONE));
    assertThat(weekend.get("next_fire_at"))
        .isEqualTo(ScheduledTaskService.nextFireAt("0 0 12 * * 0,6", now, ZONE));
    assertThat(res.get("next_fire_at")).isEqualTo(Math.min((Long) weekday.get("next_fire_at"),
        (Long) weekend.get("next_fire_at")));
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
  @DisplayName("⑪d 量的是渲染后的正文：模板 ${now} 只有 6 字节，展开成 19 字节照样要被拦下")
  void bodyCapAppliesToRenderedContent() {
    Row narrow = channel();
    narrow.set("max_body_bytes", 10);
    when(store.findChannel(CHANNEL_NO)).thenReturn(narrow);

    assertThatThrownBy(() -> service.create(input("${now}")))
        .isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.toError().hint()).contains("渲染后 19 字节"));
    verify(store, never()).insertScheduledTask(any());
  }

  @Test
  @DisplayName("⑪e 任务时区随载荷落库，非法时区不落库；列表与详情都回这个字段")
  void timezoneIsStoredAndReturned() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    Row stored = dueTask(1000L);
    stored.set("timezone", "Asia/Tokyo");
    when(store.findScheduledTask(anyString())).thenReturn(stored);
    when(store.listScheduledTasks()).thenReturn(List.of(stored));

    service.create(inputWithTimezone("Asia/Tokyo"));

    ArgumentCaptor<Row> row = ArgumentCaptor.forClass(Row.class);
    verify(store).insertScheduledTask(row.capture());
    assertThat(row.getValue().getString("timezone")).isEqualTo("Asia/Tokyo");
    assertThat(service.listAll().get(0).get("timezone")).isEqualTo("Asia/Tokyo");
    assertThat(service.detail(TASK_ID).get("timezone")).isEqualTo("Asia/Tokyo");
  }

  @Test
  @DisplayName("⑪f 编辑不传 timezone 就落回默认时区，不会把老任务的时间悄悄留在旧值上")
  void blankTimezoneFallsBackToDefault() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(store.findScheduledTask(anyString())).thenReturn(dueTask(1000L));

    service.create(inputWithTimezone("  "));

    ArgumentCaptor<Row> row = ArgumentCaptor.forClass(Row.class);
    verify(store).insertScheduledTask(row.capture());
    assertThat(row.getValue().getString("timezone")).isEqualTo("Asia/Shanghai");
  }

  @Test
  @DisplayName("⑪c level 落库前归一到 wire 值：库里只能出现 info/warning/error，别存下触发时才静默降级的写法")
  void levelIsNormalizedOnSave() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(store.findScheduledTask(anyString())).thenReturn(dueTask(1000L));

    service.create(new ScheduledTaskService.TaskInput(CHANNEL_NO, "任务", null, "正文", "warning",
        null, List.of(), "0 0 9 * * *", 1, null, null, null, null));
    service.create(new ScheduledTaskService.TaskInput(CHANNEL_NO, "任务", null, "正文", "warn",
        null, List.of(), "0 0 9 * * *", 1, null, null, null, null));

    ArgumentCaptor<Row> captor = ArgumentCaptor.forClass(Row.class);
    verify(store, times(2)).insertScheduledTask(captor.capture());
    assertThat(captor.getAllValues().get(0).getString("level")).isEqualTo("warning");
    // "warn" 不是合法 wire 值，落到枚举默认档 info——存下来的永远是能被 Level.fromWire 认出的字符串
    assertThat(captor.getAllValues().get(1).getString("level")).isEqualTo("info");
  }

  @Test
  @DisplayName("⑪g sender_id 留空按 scheduled 落库、自定义值原样保留，超长截到 64")
  void senderIdFallsBackAndIsClamped() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(store.findScheduledTask(anyString())).thenReturn(dueTask(1000L));

    service.create(inputWithSender(null, null));
    service.create(inputWithSender("OnceHuman", "https://cdn.test/a.png"));
    service.create(inputWithSender("x".repeat(100), null));

    ArgumentCaptor<Row> captor = ArgumentCaptor.forClass(Row.class);
    verify(store, times(3)).insertScheduledTask(captor.capture());
    List<Row> rows = captor.getAllValues();
    assertThat(rows.get(0).getString("sender_id")).isEqualTo("scheduled");
    assertThat(rows.get(0).getString("sender_avatar")).isNull();
    assertThat(rows.get(1).getString("sender_id")).isEqualTo("OnceHuman");
    assertThat(rows.get(1).getString("sender_avatar")).isEqualTo("https://cdn.test/a.png");
    // 与外部摄入同一把尺子：sender_id 超长截断，不报 400
    assertThat(rows.get(2).getString("sender_id")).hasSize(64);
  }

  @Test
  @DisplayName("⑪h 头像只收 http(s)：data: 内联、相对路径、超 1024 都在保存前 400，不落库")
  void avatarMustBeHttpUrl() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());

    for (String bad : List.of("data:image/png;base64,AAAA", "/avatar.png",
        "https://cdn.test/" + "a".repeat(1024))) {
      assertThatThrownBy(() -> service.create(inputWithSender("OnceHuman", bad)))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
    }
    verify(store, never()).insertScheduledTask(any());
  }

  @Test
  @DisplayName("⑪i 列表与详情都回 sender_id / sender_avatar，编辑器才能把这两栏填回去")
  void senderIdentityIsReturned() {
    Row row = dueTask(1000L);
    row.set("sender_id", "OnceHuman");
    row.set("sender_avatar", "https://cdn.test/a.png");
    when(store.listScheduledTasks()).thenReturn(List.of(row));
    when(store.findScheduledTask(TASK_ID)).thenReturn(row);

    assertThat(service.listAll().get(0).get("sender_id")).isEqualTo("OnceHuman");
    assertThat(service.detail(TASK_ID).get("sender_avatar")).isEqualTo("https://cdn.test/a.png");
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

  @Test
  @DisplayName("⑰ 分类标签落库前收口成 32 字符内的非 null 串，且列表视图就带着它")
  void categoryIsNormalizedAndCarriedInView() {
    when(store.findChannel(CHANNEL_NO)).thenReturn(channel());
    when(store.findScheduledTask(anyString())).thenReturn(dueTask(1000L));

    service.create(inputWithCategory("  运维 "));
    service.create(inputWithCategory("   "));
    service.create(inputWithCategory("分类".repeat(20)));

    ArgumentCaptor<Row> captor = ArgumentCaptor.forClass(Row.class);
    verify(store, times(3)).insertScheduledTask(captor.capture());
    List<Row> saved = captor.getAllValues();
    assertThat(saved.get(0).getString("category")).isEqualTo("运维");
    // 空白落成空串而不是 null：列是 NOT NULL DEFAULT ''，null 会被 MySQL 严格模式当场拒掉，
    // 而这个空串在管理台就是"未分类"，是一个有效取值
    assertThat(saved.get(1).getString("category")).isEmpty();
    assertThat(saved.get(2).getString("category")).hasSize(32);

    // 分类下拉的选项是从列表现取的，所以 category 必须在 withContent=false 那条路上也回
    Row listed = dueTask(1000L);
    listed.set("category", "运维");
    when(store.listScheduledTasks()).thenReturn(List.of(listed));
    assertThat(service.listAll().get(0).get("category")).isEqualTo("运维");
    assertThat(service.detail(TASK_ID).get("category")).isEqualTo("");
  }

  // ---------- helpers ----------

  private static String containsReason() {
    return org.mockito.ArgumentMatchers.argThat(value -> value != null && !value.isBlank());
  }

  private static long millis(LocalDateTime time) {
    return time.atZone(ZONE).toInstant().toEpochMilli();
  }

  private static LocalDateTime local(long millis) {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE);
  }

  private static Row channel() {
    Row row = new Row();
    row.set("channel_no", CHANNEL_NO);
    row.set("name", "运维频道");
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
    row.set("sender_id", "scheduled");
    row.set("sender_avatar", null);
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
        "定时推送", List.of(), "0 0 9 * * *", 1, null, null, null, null);
  }

  private static ScheduledTaskService.TaskInput inputWithImages(String... urls) {
    return new ScheduledTaskService.TaskInput(CHANNEL_NO, "带图任务", null, "正文", "info", null,
        List.of(urls), "0 0 9 * * *", 1, null, null, null, null);
  }

  private static ScheduledTaskService.TaskInput inputWithCategory(String category) {
    return new ScheduledTaskService.TaskInput(CHANNEL_NO, "每日晨报", "晨报", "正文", "info", null,
        List.of(), "0 0 9 * * *", 1, category, null, null, null);
  }

  private static ScheduledTaskService.TaskInput inputWithTimezone(String timezone) {
    return new ScheduledTaskService.TaskInput(CHANNEL_NO, "每日晨报", "晨报", "正文", "info", null,
        List.of(), "0 0 9 * * *", 1, null, timezone, null, null);
  }

  private static ScheduledTaskService.TaskInput inputWithCron(String cron) {
    return new ScheduledTaskService.TaskInput(CHANNEL_NO, "每日晨报", "晨报", "正文", "info", null,
        List.of(), cron, 1, null, null, null, null);
  }

  private static ScheduledTaskService.TaskInput inputWithSender(String senderId, String avatar) {
    return new ScheduledTaskService.TaskInput(CHANNEL_NO, "每日晨报", "晨报", "正文", "info",
        "七日世界助手", List.of(), "0 0 9 * * *", 1, null, null, senderId, avatar);
  }
}
