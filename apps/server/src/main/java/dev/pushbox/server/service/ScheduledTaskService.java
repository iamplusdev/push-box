package dev.pushbox.server.service;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.api.IngestRequest;
import dev.pushbox.core.api.IngestResponse;
import dev.pushbox.core.id.Ulid;
import dev.pushbox.core.model.Level;
import dev.pushbox.core.protocol.Json;
import dev.pushbox.core.text.Templates;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.config.ServerIdentity;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.web.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

/**
 * 管理台定时推送：运维在 Web 管理台建任务（选频道 + 写正文 + 给 cron），服务端按时把消息灌进去。
 *
 * <p>投递复用 {@link IngestService#ingestInternal}，与外部 {@code POST /message} 走同一条入库+下发链路，
 * 客户端分不出这条消息是谁发的——这是刻意的：定时推送不该有第二套语义。
 *
 * <p>每个任务自带时区（默认 Asia/Shanghai）：cron 的"每天 09:00"按它解释，正文里的
 * {@code ${now}} 这类时间变量也按它渲染。正文一律存模板原文，到触发那一刻才用
 * {@link Templates#render} 展开——建任务的那一秒不该被固化成每天的正文。
 * 正文里写 {@code ${timezone:xx}} 改不了这个时区，只会在输出里显示任务自己的时区。
 *
 * <p>{@code cron_expr} 可以写多行，每行一条（最多 {@link #MAX_CRONS} 条），
 * {@code next_fire_at} 取所有行里最近的那一次——"工作日 09:00 + 周末 12:00"因此是一条任务而不是两条。
 *
 * <p>调度用"扫表 + {@code next_fire_at} CAS 抢占"而不是给每个任务注册 Trigger：
 * 状态全在库里，重启不丢、崩溃能补跑一次、多实例天然安全，任务的增删改也不需要碰任何内存注册表。
 * 代价是触发精度受扫描周期限制（默认 15s），对分钟级以上的 cron 足够；亚分钟级 cron 会被推迟到下个扫描点，
 * 并且同一分钟内的多次触发共享一个 msg_key，被 {@code uk_msg_idem} 幂等吃掉——实际效果是每分钟一条。
 */
@Service
public class ScheduledTaskService {

  private static final Logger log = LoggerFactory.getLogger(ScheduledTaskService.class);

  /** 定时任务的 msg_key 前缀；幂等靠 messages 的 uk_msg_idem 唯一键兜底。 */
  private static final String MSG_KEY_PREFIX = "sched:";
  /** 发送者标识：定时消息统一归到这一个会话，客户端一眼能看出是自建推送。 */
  private static final String SENDER_ID = "scheduled";
  private static final int MAX_LIST_BATCH = 200;
  /** 一个任务最多几条 cron。够放下"工作日 / 周末 / 节前"这类写法，又不至于让正文被调度规则淹没。 */
  static final int MAX_CRONS = 8;
  /** 与 V11 迁移后的列宽一致：超了宁可 400，也别让 MySQL 严格模式在落库时抛 500。 */
  private static final int MAX_CRON_CHARS = 512;
  /** ${scheduled_at} 的格式：计划时隙是"哪一刻该发"，与 ${now}（实际送达）分属两件事。 */
  private static final DateTimeFormatter SLOT_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final PbStore store;
  private final IngestService ingest;
  private final PbProperties props;
  private final ServerIdentity identity;
  private final int batchSize;

  public ScheduledTaskService(PbStore store, IngestService ingest, PbProperties props,
      ServerIdentity identity, @Value("${pb.scheduled.batch-size:50}") int batchSize) {
    this.store = store;
    this.ingest = ingest;
    this.props = props;
    this.identity = identity;
    this.batchSize = Math.max(1, Math.min(batchSize, MAX_LIST_BATCH));
  }

  // ---------- CRUD ----------

  /** 新建任务。{@code next_fire_at} 由 cron 从当前时间往后算，不接受调用方指定。 */
  public Map<String, Object> create(TaskInput input) {
    long now = System.currentTimeMillis();
    Row channel = requireChannel(input.channelNo());
    ZoneId zone = zoneOf(input.timezone());
    Row task = toRow(newTaskId(), channel, input, zone, nextFireAt(input.cronExpr(), now, zone), now);
    store.insertScheduledTask(task);
    log.info("定时任务已创建 task_id={} channel={} cron={} zone={}", task.getString("task_id"),
        channel.getString("channel_no"), input.cronExpr(), zone.getId());
    return view(store.findScheduledTask(task.getString("task_id")), false);
  }

  /** 编辑任务：载荷与调度字段整体覆盖，执行结果列（last_*）原样保留；改了 cron 就重算下次触发时间。 */
  public Map<String, Object> update(String taskId, TaskInput input) {
    long now = System.currentTimeMillis();
    requireTask(taskId);
    Row channel = requireChannel(input.channelNo());
    ZoneId zone = zoneOf(input.timezone());
    Row task = toRow(taskId, channel, input, zone, nextFireAt(input.cronExpr(), now, zone), now);
    store.updateScheduledTask(task);
    return view(store.findScheduledTask(taskId), false);
  }

  public void delete(String taskId) {
    requireTask(taskId);
    store.deleteScheduledTask(taskId);
  }

  /** 启停开关。重新启用时按 cron 从当下重算下次时间，否则任务会停在过去的时隙上被立即触发。 */
  public Map<String, Object> setEnabled(String taskId, boolean enabled) {
    long now = System.currentTimeMillis();
    Row task = requireTask(taskId);
    long nextFireAt = enabled
        ? nextFireAt(task.getString("cron_expr"), now, zoneOrFallback(task.getString("timezone")))
        : longValue(task, "next_fire_at", now);
    store.setScheduledTaskEnabled(taskId, enabled ? 1 : 0, nextFireAt, now);
    return view(store.findScheduledTask(taskId), false);
  }

  public List<Map<String, Object>> listAll() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Row row : store.listScheduledTasks()) {
      out.add(view(row, false));
    }
    return out;
  }

  public Map<String, Object> detail(String taskId) {
    return view(requireTask(taskId), true);
  }

  /**
   * 表单里的「预览」：按当前时刻把正文渲染一遍，不落库、不要求正文非空。
   *
   * <p>渲染必须留在服务端：{@code ${now:yyyy-MM-dd}} 用的是 Java 的 {@code DateTimeFormatter}，
   * 前端复刻一套只会在两边慢慢长歪，预览与真正发出去的就不是同一条消息了。
   * 时区与 {@code scheduled_at} 的取法与触发时完全一致——预览看到的时区就是任务落库后的时区。
   */
  public Map<String, Object> preview(TaskInput input) {
    long now = System.currentTimeMillis();
    Row channel = requireChannel(input.channelNo());
    ZoneId zone = zoneOf(input.timezone());
    long slot = nextFireAt(input.cronExpr(), now, zone);
    String template = input.content() == null ? "" : input.content();
    String rendered = Templates.render(template, zone, Instant.ofEpochMilli(now),
        templateVars(input.name(), input.senderName(), channel, zone, slot));
    int bytes = rendered.getBytes(StandardCharsets.UTF_8).length;
    int maxBodyBytes = intValue(channel, "max_body_bytes", props.getRetention().getMaxMessageBytes());

    // 逐条列出来：多 cron 的"下次触发"只有一个时间，看不出是哪条规则撑起来的
    List<Map<String, Object>> nextFires = new ArrayList<>();
    for (Map.Entry<String, Long> e : nextFires(input.cronExpr(), now, zone).entrySet()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("cron", e.getKey());
      item.put("next_fire_at", e.getValue());
      nextFires.add(item);
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("timezone", zone.getId());
    out.put("now", now);
    out.put("next_fire_at", slot);
    out.put("next_fires", nextFires);
    out.put("content", rendered);
    out.put("bytes", bytes);
    out.put("max_body_bytes", maxBodyBytes);
    out.put("over_limit", bytes > maxBodyBytes);
    return out;
  }

  // ---------- 调度 ----------

  @Scheduled(initialDelayString = "${pb.scheduled.sweep-initial-delay:20s}",
      fixedDelayString = "${pb.scheduled.sweep-interval:15s}")
  public void scheduledSweep() {
    try {
      int fired = sweepOnce();
      if (fired > 0) {
        log.info("定时任务本轮触发 {} 条", fired);
      }
    } catch (RuntimeException e) {
      // 扫表抛出去只会被 Spring 记一轮栈，别让调度线程停在异常上
      log.warn("定时任务扫描失败: {}", e.toString());
    }
  }

  /** 单独开出来是为了能在测试里定点跑一轮，不依赖调度节奏。返回本轮真正投递的任务数。 */
  public int sweepOnce() {
    long now = System.currentTimeMillis();
    List<Row> due = store.dueScheduledTasks(now, batchSize);
    int fired = 0;
    for (Row task : due) {
      String taskId = task.getString("task_id");
      long slot = longValue(task, "next_fire_at", now);
      ZoneId zone = zoneOrFallback(task.getString("timezone"));
      // 从 now 往后算下一次：宕机几小时后不逐分钟补跑，只补当前这一次，之后回到正常节律
      long next = nextFireAt(task.getString("cron_expr"), now, zone);
      if (store.claimScheduledTaskSlot(taskId, slot, next, now) != 1) {
        continue;  // 被另一个实例抢走了
      }
      fire(task, slot, now, zone);
      fired++;
    }
    return fired;
  }

  /** 已抢到时隙，投递一条消息并把结论写回任务行。 */
  private void fire(Row task, long slot, long now, ZoneId zone) {
    String taskId = task.getString("task_id");
    Row channel = store.findChannel(task.getString("channel_no"));
    if (channel == null || channel.getLong("archived_at") != null) {
      // 频道没了就自动停用：留着它每分钟失败一次只会刷屏日志
      store.recordScheduledTaskRun(taskId, "failed", "频道已归档或不存在，任务已自动停用", null, 0, now);
      log.warn("定时任务因频道不可用而停用 task_id={} channel={}", taskId,
          task.getString("channel_no"));
      return;
    }
    // 时区取任务自己的：正文里写 ${timezone:xx} 也改不了它，只会在输出里原样显示任务时区
    String content = Templates.render(task.getString("content"), zone, Instant.ofEpochMilli(now),
        templateVars(task.getString("name"), task.getString("sender_name"), channel, zone, slot));
    IngestRequest request = new IngestRequest(channel.getString("channel_no"), null, null,
        content, SENDER_ID, stringValue(task, "sender_name"), null,
        task.getString("title"), task.getString("level"), null, readImages(task),
        msgKey(taskId, slot), null, null, null);
    try {
      IngestResponse response = ingest.ingestInternal(channel, request);
      store.recordScheduledTaskRun(taskId, "ok", null, response.id(), 1, now);
    } catch (RuntimeException e) {
      store.recordScheduledTaskRun(taskId, "failed", truncate(reason(e), 255), null, 1, now);
      log.warn("定时任务投递失败 task_id={} slot={} 原因={}", taskId, slot, reason(e));
    }
  }

  /** 同一任务在同一分钟时隙里 msg_key 恒定：崩溃后重复触发会被 uk_msg_idem 幂等吃掉。 */
  static String msgKey(String taskId, long slotMillis) {
    return MSG_KEY_PREFIX + taskId + ":" + (slotMillis / 60_000L);
  }

  /**
   * cron 的下次触发时刻（毫秒）。可以写多行，取所有行里最近的那一次。
   * 字段必须是 6 段——Spring 不认 5 段 unix cron。
   * {@code zone} 决定"09:00"指的是哪里的 09:00，cron 本身不带时区。
   */
  static long nextFireAt(String cronExpr, long fromMillis, ZoneId zone) {
    Map<String, Long> fires = nextFires(cronExpr, fromMillis, zone);
    if (fires.isEmpty()) {
      throw ApiException.invalidParam(splitCrons(cronExpr).size() > 1
          ? "这些 cron 都已经没有下一次触发了，换一个表达式"
          : "这个 cron 已经没有下一次触发了，换一个表达式");
    }
    return Collections.min(fires.values());
  }

  /** 每条 cron 各自的下次触发时刻，按填写顺序返回；已经不会再触发的那条不出现在结果里。 */
  static Map<String, Long> nextFires(String cronExpr, long fromMillis, ZoneId zone) {
    List<String> lines = splitCrons(cronExpr);
    boolean multi = lines.size() > 1;
    ZoneId effective = zone == null ? ZoneId.of(Templates.DEFAULT_ZONE) : zone;
    LocalDateTime from = LocalDateTime.ofInstant(Instant.ofEpochMilli(fromMillis), effective);
    Map<String, Long> out = new LinkedHashMap<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      LocalDateTime next;
      try {
        next = CronExpression.parse(line).next(from);
      } catch (IllegalArgumentException e) {
        // 多行时必须说清是哪一行坏了：八行里挑出一个错字段，只说"cron 不合法"等于让人逐行试
        String which = multi ? "第 " + (i + 1) + " 条 cron 不合法" : "cron 不合法";
        throw ApiException.invalidParam(which + "（需要 6 段：秒 分 时 日 月 周）：" + e.getMessage());
      }
      if (next != null) {
        out.put(line, next.atZone(effective).toInstant().toEpochMilli());
      }
    }
    return out;
  }

  /** 管理台传进来的时区；空值落到默认时区，非法值当场 400——别存进一个永远不会按预期触发的任务。 */
  static ZoneId zoneOf(String raw) {
    if (raw == null || raw.isBlank()) {
      return ZoneId.of(Templates.DEFAULT_ZONE);
    }
    try {
      return ZoneId.of(raw.trim());
    } catch (DateTimeException e) {
      throw ApiException.invalidParam(
          "timezone 不合法（例如 Asia/Shanghai、UTC、Europe/Berlin）：" + raw.trim());
    }
  }

  /** 读库用：老行或脏值都不该让一轮扫描整体失败，统一回落到默认时区。 */
  static ZoneId zoneOrFallback(String raw) {
    try {
      return zoneOf(raw);
    } catch (ApiException e) {
      return ZoneId.of(Templates.DEFAULT_ZONE);
    }
  }

  /**
   * 把 {@code cron_expr} 拆成逐条表达式：每行一条，空行与首尾空白都丢掉（管理台的 textarea 里
   * 多敲一个回车不该让任务存不下来）。
   */
  static List<String> splitCrons(String cronExpr) {
    if (cronExpr == null || cronExpr.isBlank()) {
      throw ApiException.invalidParam("cron_expr 必填");
    }
    List<String> lines = new ArrayList<>();
    for (String raw : cronExpr.split("\\R")) {
      String line = raw.trim();
      if (!line.isEmpty()) {
        lines.add(line);
      }
    }
    if (lines.isEmpty()) {
      throw ApiException.invalidParam("cron_expr 必填");
    }
    if (lines.size() > MAX_CRONS) {
      throw ApiException.invalidParam(
          "cron_expr 最多 " + MAX_CRONS + " 条（每行一条），现在是 " + lines.size() + " 条");
    }
    int total = String.join("\n", lines).length();
    if (total > MAX_CRON_CHARS) {
      throw ApiException.invalidParam(
          "cron_expr 太长：" + total + " 字符，上限 " + MAX_CRON_CHARS);
    }
    return lines;
  }

  private static List<CronExpression> parseCrons(String cronExpr) {
    List<String> lines = splitCrons(cronExpr);
    boolean multi = lines.size() > 1;
    List<CronExpression> out = new ArrayList<>(lines.size());
    for (int i = 0; i < lines.size(); i++) {
      try {
        out.add(CronExpression.parse(lines.get(i)));
      } catch (IllegalArgumentException e) {
        // 多行时必须说清是哪一行坏了：八行里挑出一个错字段，只说"cron 不合法"等于让人逐行试
        String which = multi ? "第 " + (i + 1) + " 条 cron 不合法" : "cron 不合法";
        throw ApiException.invalidParam(which + "（需要 6 段：秒 分 时 日 月 周）：" + e.getMessage());
      }
    }
    return out;
  }

  // ---------- 校验与映射 ----------

  private Row requireTask(String taskId) {
    Row task = store.findScheduledTask(taskId);
    if (task == null) {
      throw ApiException.notFound("scheduled task");
    }
    return task;
  }

  private Row requireChannel(String channelNo) {
    if (channelNo == null || channelNo.isBlank()) {
      throw ApiException.invalidParam("channel_no 必填");
    }
    Row channel = store.findChannel(channelNo.trim());
    if (channel == null) {
      throw ApiException.notFound("channel");
    }
    if (channel.getLong("archived_at") != null) {
      throw ApiException.invalidParam("频道已归档，不能作为定时推送目标");
    }
    return channel;
  }

  private void validate(Row channel, TaskInput input, ZoneId zone, long slot, long now) {
    if (input.content() == null || input.content().isBlank()) {
      throw ApiException.invalidParam("content 不能为空");
    }
    // 按字节而不是字符判：与投递时 ingestAccepted 用的是同一把尺子，
    // 否则一个"32K 个汉字"的任务会创建成功、然后每分钟失败一次。
    // 量的是渲染之后的正文：${now} 这类占位符展开后可能比模板长，只量模板会漏掉这种情况。
    int maxBodyBytes = intValue(channel, "max_body_bytes",
        props.getRetention().getMaxMessageBytes());
    String rendered = Templates.render(input.content(), zone, Instant.ofEpochMilli(now),
        templateVars(input.name(), input.senderName(), channel, zone, slot));
    int bytes = rendered.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > maxBodyBytes) {
      throw ApiException.invalidParam("正文渲染后 " + bytes + " 字节，超过该频道的 max_body_bytes "
          + maxBodyBytes);
    }
    if (input.images() != null && input.images().size() > IngestRequest.MAX_IMAGES) {
      throw ApiException.invalidParam("images 最多 " + IngestRequest.MAX_IMAGES + " 条");
    }
    if (input.images() != null) {
      for (String raw : input.images()) {
        requireHttpUrl(raw);
      }
    }
  }

  /**
   * 正文模板可用的变量。{@code scheduled_at} 是计划时隙（这一条本该什么时候发），
   * 与 {@code ${now}}（实际渲染时刻）刻意分开：扫描延迟、宕机补跑都会让两者相差几十秒到几分钟。
   */
  private Map<String, String> templateVars(String taskName, String senderName, Row channel,
      ZoneId zone, long slot) {
    Map<String, String> vars = new LinkedHashMap<>();
    putIfPresent(vars, "task_name", taskName);
    putIfPresent(vars, "channel_no", channel.getString("channel_no"));
    // findChannel 是 SELECT *，列名就是 name；模板变量名才叫 channel_name
    putIfPresent(vars, "channel_name", channel.getString("name"));
    putIfPresent(vars, "sender_name", senderName);
    vars.put("scheduled_at", SLOT_TIME.format(Instant.ofEpochMilli(slot).atZone(zone)));
    vars.put("host", identity.host());
    vars.put("version", identity.version());
    return vars;
  }

  private static void putIfPresent(Map<String, String> vars, String key, String value) {
    if (value != null && !value.isBlank()) {
      vars.put(key, value);
    }
  }

  private static void requireHttpUrl(String raw) {
    if (raw == null || raw.isBlank()) {
      throw ApiException.invalidParam("images 每项必须是非空 URL");
    }
    URI uri;
    try {
      uri = URI.create(raw.trim());
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidParam("images 仅支持 http(s) URL");
    }
    String scheme = uri.getScheme();
    if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
        || uri.getHost() == null) {
      throw ApiException.invalidParam("images 仅支持 http(s) URL");
    }
    if (raw.trim().length() > 1024) {
      throw ApiException.invalidParam("images 单条 URL 不能超过 1024 字符");
    }
  }

  private static String newTaskId() {
    return Ulid.next();
  }

  private Row toRow(String taskId, Row channel, TaskInput input, ZoneId zone, long nextFireAt,
      long now) {
    validate(channel, input, zone, nextFireAt, now);
    // 按行归一化后落库：空行与 CRLF 不进库，否则列表里显示的是"带着一片空白的 cron"
    String cron = String.join("\n", splitCrons(input.cronExpr()));
    parseCrons(cron);
    Row row = new Row();
    row.set("task_id", taskId);
    row.set("group_id", channel.getString("group_id"));
    row.set("channel_no", channel.getString("channel_no"));
    row.set("name", clamp(input.name(), 64));
    row.set("category", normalizeCategory(input.category()));
    row.set("title", clamp(input.title(), 512));
    // 存模板原文，渲染留到触发那一刻：存渲染结果的话，建任务时的那一秒会被固化成每天的正文
    row.set("content", input.content());
    // 归一化到 Level 的 wire 值：存 "warn" 这类近义写法会在触发时被 fromWire 静默降级成 info，
    // 任务看着是 warn、消息却是 info，排查起来没有任何报错可循。
    row.set("level", Level.fromWire(input.level()).wire());
    row.set("sender_name", clamp(input.senderName(), 64));
    row.set("images_json", input.images() == null || input.images().isEmpty() ? null
        : Json.write(input.images()));
    row.set("cron_expr", cron);
    row.set("timezone", zone.getId());
    row.set("enabled", input.enabled() == null || input.enabled() == 1 ? 1 : 0);
    row.set("next_fire_at", nextFireAt);
    row.set("created_at", now);
    row.set("updated_at", now);
    return row;
  }

  private static List<String> readImages(Row task) {
    String json = task.getString("images_json");
    if (json == null || json.isBlank()) {
      return List.of();
    }
    String[] parsed = Json.read(json, String[].class);
    List<String> out = new ArrayList<>();
    for (String url : parsed) {
      if (url != null && !url.isBlank()) {
        out.add(url.trim());
      }
    }
    return out;
  }

  /** {@code withContent=false} 时不回正文：列表页只需要开头几个字，正文走 detail 取。 */
  private Map<String, Object> view(Row task, boolean withContent) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("task_id", task.getString("task_id"));
    out.put("group_id", task.getString("group_id"));
    out.put("channel_no", task.getString("channel_no"));
    if (task.get("channel_name") != null) {
      out.put("channel_name", task.getString("channel_name"));
    }
    out.put("name", nullToEmpty(task.getString("name")));
    out.put("category", nullToEmpty(task.getString("category")));
    out.put("title", nullToEmpty(task.getString("title")));
    if (withContent) {
      out.put("content", task.getString("content"));
      out.put("images", readImages(task));
    } else {
      String content = task.getString("content");
      out.put("content_head", content == null ? "" : content.substring(0, Math.min(80, content.length())));
    }
    out.put("level", nullToEmpty(task.getString("level")));
    out.put("sender_name", nullToEmpty(task.getString("sender_name")));
    out.put("cron_expr", task.getString("cron_expr"));
    out.put("timezone", zoneOrFallback(task.getString("timezone")).getId());
    out.put("enabled", intValue(task, "enabled", 1));
    out.put("next_fire_at", nullToZero(longValueRaw(task, "next_fire_at")));
    out.put("last_fire_at", nullToZero(longValueRaw(task, "last_fire_at")));
    out.put("last_result", task.getString("last_result"));
    out.put("last_error", task.getString("last_error"));
    out.put("last_msg_id", task.getString("last_msg_id"));
    out.put("created_at", nullToZero(longValueRaw(task, "created_at")));
    out.put("updated_at", nullToZero(longValueRaw(task, "updated_at")));
    return out;
  }

  private static String reason(Exception e) {
    if (e instanceof ApiException api) {
      return api.toError().error()
          + (api.toError().hint() == null ? "" : ": " + api.toError().hint());
    }
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }

  private static String truncate(String s, int max) {
    if (s == null) {
      return null;
    }
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static String clamp(String s, int max) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    if (t.isEmpty()) {
      return null;
    }
    return t.length() <= max ? t : t.substring(0, max);
  }

  /**
   * 分类标签的收口：列宽 32，超了就截——与 name/title 同一套做法，而不是报 400。
   * 空串是有效值（列表上显示成"未分类"），所以这里不能像 {@link #clamp} 那样把空串折成 null：
   * 列是 NOT NULL DEFAULT ''，塞 null 进去会被 MySQL 严格模式当场拒掉，任务连建都建不出来。
   */
  private static String normalizeCategory(String s) {
    if (s == null) {
      return "";
    }
    String t = s.trim();
    return t.length() <= 32 ? t : t.substring(0, 32);
  }

  private static String stringValue(Row row, String column) {
    Object v = row.get(column);
    return v == null ? null : String.valueOf(v);
  }

  private static int intValue(Row row, String column, int fallback) {
    Integer v = row.getInt(column);
    return v == null ? fallback : v;
  }

  private static long longValue(Row row, String column, long fallback) {
    Long v = row.getLong(column);
    return v == null ? fallback : v;
  }

  private static Long longValueRaw(Row row, String column) {
    return row.getLong(column);
  }

  private static long nullToZero(Long v) {
    return v == null ? 0L : v;
  }

  private static String nullToEmpty(String v) {
    return v == null ? "" : v;
  }

  /**
   * 管理台请求体。命名沿用管理台既有接口的驼峰风格（响应才是下划线）。
   * {@code category} 与 {@code timezone} 排在最后而不是紧跟 {@code cronExpr}：
   * 这个 record 在本仓有按位置构造的调用点，插到中间会让后面每个参数集体错位，
   * 而它们大多同为 String，编译器拦不住。
   */
  public record TaskInput(String channelNo, String name, String title, String content, String level,
      String senderName, List<String> images, String cronExpr, Integer enabled, String category,
      String timezone) {}
}
