package dev.pushbox.server.web;

import com.mybatisflex.core.row.Row;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.ChannelService;
import dev.pushbox.server.service.DeviceService;
import dev.pushbox.server.service.ScheduledTaskService;
import dev.pushbox.server.service.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web 管理台专用接口（需要 JWT token）。
 * 与普通客户端接口的区别：
 * 1. 响应不加密（直接返回明文 JSON）
 * 2. 不需要 Ed25519 签名
 * 3. 可以跨 Space 查看所有数据
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

  private final ChannelService channels;
  private final DeviceService devices;
  private final ScheduledTaskService scheduledTasks;
  private final PbStore store;
  private final DeviceGuard guard;

  public AdminController(ChannelService channels, DeviceService devices,
      ScheduledTaskService scheduledTasks, PbStore store, DeviceGuard guard) {
    this.channels = channels;
    this.devices = devices;
    this.scheduledTasks = scheduledTasks;
    this.store = store;
    this.guard = guard;
  }

  /** 创建频道（管理台专用，返回明文 secret）。 */
  @PostMapping("/channels")
  public Map<String, Object> createChannel(@RequestBody byte[] body, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    CreateChannelRequest request = readCreateChannel(body);
    String groupId = requireKnownGroup(request.groupId());
    ChannelService.Created created = channels.create(groupId, request.name(), request.maxLevel(), request.maxImages());
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("channel_no", created.channelNo());
    resp.put("secret", created.secret());
    resp.put("name", created.name());
    resp.put("max_level", created.maxLevel());
    resp.put("max_images", created.maxImages());
    return resp;
  }

  /** 重置频道密钥（管理台专用，返回新 secret）。 */
  @PostMapping("/channels/{channelNo}/rotate-secret")
  public Map<String, Object> rotateSecret(@PathVariable String channelNo, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    Row channel = store.findChannel(channelNo);
    if (channel == null) {
      throw ApiException.notFound("channel");
    }
    ChannelService.Created rotated = channels.rotateSecret(channelNo, channel.getString("group_id"));
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("channel_no", rotated.channelNo());
    resp.put("secret", rotated.secret());
    resp.put("name", rotated.name());
    resp.put("max_level", rotated.maxLevel());
    resp.put("max_images", rotated.maxImages());
    return resp;
  }

  /** 获取所有频道列表（跨 Space）。 */
  @GetMapping("/channels")
  public Map<String, Object> listChannels(HttpServletRequest http) {
    // 验证 JWT（通过 guard.require 的 admin 路径分支）
    DeviceGuard.Authenticated auth = guard.require(http, null);
    List<Map<String, Object>> list = channels.listAll();
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("channels", list);
    return resp;
  }

  /** 获取指定频道的详情 + 设备列表。 */
  @GetMapping("/channels/{channelNo}")
  public Map<String, Object> channelDetail(@PathVariable String channelNo, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    Row channel = store.findChannel(channelNo);
    if (channel == null) {
      throw ApiException.notFound();
    }
    List<Map<String, Object>> deviceList = devices.listByChannel(channelNo);
    Map<String, Object> channelView = new LinkedHashMap<>(ChannelService.view(channel));
    channelView.put("group_id", channel.getString("group_id"));
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("channel", channelView);
    resp.put("devices", deviceList);
    return resp;
  }

  /** 删除频道。 */
  @DeleteMapping("/channels/{channelNo}")
  public Map<String, Object> deleteChannel(@PathVariable String channelNo, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    Row channel = store.findChannel(channelNo);
    if (channel == null) {
      throw ApiException.notFound();
    }
    // 调用现有的 archive 逻辑
    long archivedAt = channels.archive(channelNo, channel.getString("group_id"));
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("channel_no", channelNo);
    resp.put("archived_at", archivedAt);
    return resp;
  }

  /** 获取所有设备列表（跨 Space）。 */
  @GetMapping("/devices")
  public Map<String, Object> listDevices(HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    List<Map<String, Object>> list = devices.listAll();
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("devices", list);
    return resp;
  }

  /** 吊销设备。 */
  @DeleteMapping("/devices/{deviceId}")
  public Map<String, Object> revokeDevice(@PathVariable String deviceId, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    Row device = devices.byId(deviceId);
    if (device == null) {
      throw ApiException.notFound();
    }
    devices.revoke(device.getString("group_id"), deviceId);
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("device_id", deviceId);
    return resp;
  }

  /** 跨空间消息搜索（只读）。q 必填，limit 夹在 [1, 200]。 */
  @GetMapping("/messages")
  public Map<String, Object> searchMessages(@RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "channel_no", required = false) String channelNo,
      @RequestParam(name = "type", required = false) String msgType,
      @RequestParam(name = "since", required = false, defaultValue = "0") long since,
      @RequestParam(name = "limit", required = false, defaultValue = "50") int limit,
      HttpServletRequest http) {
    guard.require(http, null);
    if (q == null || q.isBlank()) {
      throw ApiException.invalidParam("q 不能为空");
    }
    if (q.codePointCount(0, q.length()) > 100) {
      throw ApiException.invalidParam("q 最长 100 字符");
    }
    int capped = Math.max(1, Math.min(limit, 200));
    // ≥2 字先走 ngram FULLTEXT；短词 MATCH 恒零命中，直接 LIKE。零命中再兜一次 LIKE：
    // ngram 索引缺失或被分词切碎都表现为零命中，而搜索给假阴性比慢一点更糟（与设备侧同一路由）。
    String query = q.trim();
    List<Row> rows = null;
    if (SearchService.isNgramSearchable(query)) {
      String matchable = SearchService.matchQuery(query);
      // 纯标点（如 ""）剥完引号只剩空白：不送进 AGAINST，直接走 LIKE 腿
      if (!matchable.isBlank()) {
        rows = store.searchAdminFulltext(matchable, channelNo, msgType, since, capped);
        if (rows.isEmpty()) {
          rows = null;
        }
      }
    }
    if (rows == null) {
      rows = store.searchAdminLike(SearchService.likePattern(query), channelNo, msgType, since,
          capped);
    }
    List<Map<String, Object>> messages = new ArrayList<>();
    for (Row row : rows) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("seq", row.getLong("seq"));
      item.put("msg_id", row.getString("msg_id"));
      item.put("channel_no", row.getString("channel_no"));
      item.put("channel_name", row.getString("channel_name"));
      item.put("group_id", row.getString("group_id"));
      item.put("sender_id", row.getString("sender_id"));
      item.put("sender_name", row.getString("sender_name"));
      item.put("msg_type", row.getString("msg_type"));
      item.put("title", row.getString("title"));
      item.put("summary", row.getString("summary"));
      item.put("body", row.getString("body"));
      item.put("level", row.getString("level"));
      item.put("media_status", row.getString("media_status"));
      item.put("created_at", row.getLong("created_at"));
      messages.add(item);
    }
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("count", messages.size());
    resp.put("messages", messages);
    return resp;
  }

  /** 管理台概览：全局计数 + 近 14 天消息趋势。 */
  @GetMapping("/overview")
  public Map<String, Object> overview(HttpServletRequest http) {
    guard.require(http, null);
    long todayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    long fourteenDaysAgo = System.currentTimeMillis() - 14L * 86_400_000L;
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("stats", store.adminOverview(todayStart));
    resp.put("trend", store.messagesByDaySince(fourteenDaysAgo));
    return resp;
  }

  // ---------- 定时推送 ----------

  @GetMapping("/scheduled-tasks")
  public Map<String, Object> listScheduledTasks(HttpServletRequest http) {
    guard.require(http, null);
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("tasks", scheduledTasks.listAll());
    return resp;
  }

  /** 单条详情：列表不带正文与图片，编辑弹窗要完整载荷就取这里。 */
  @GetMapping("/scheduled-tasks/{taskId}")
  public Map<String, Object> scheduledTaskDetail(@PathVariable String taskId,
      HttpServletRequest http) {
    guard.require(http, null);
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("task", scheduledTasks.detail(taskId));
    return resp;
  }

  @PostMapping("/scheduled-tasks")
  public Map<String, Object> createScheduledTask(@RequestBody byte[] body, HttpServletRequest http) {
    guard.require(http, null);
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("task", scheduledTasks.create(readTaskInput(body)));
    return resp;
  }

  /** 表单预览：只渲染、不落库。请求体与建任务同一份，靠 {@code /preview} 这一段落区分。 */
  @PostMapping("/scheduled-tasks/preview")
  public Map<String, Object> previewScheduledTask(@RequestBody byte[] body, HttpServletRequest http) {
    guard.require(http, null);
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("preview", scheduledTasks.preview(readTaskInput(body)));
    return resp;
  }

  @PutMapping("/scheduled-tasks/{taskId}")
  public Map<String, Object> updateScheduledTask(@PathVariable String taskId,
      @RequestBody byte[] body, HttpServletRequest http) {
    guard.require(http, null);
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("task", scheduledTasks.update(taskId, readTaskInput(body)));
    return resp;
  }

  /** 启停开关：{@code {"enabled": 1|0}}。 */
  @PostMapping("/scheduled-tasks/{taskId}/enabled")
  public Map<String, Object> setScheduledTaskEnabled(@PathVariable String taskId,
      @RequestBody byte[] body, HttpServletRequest http) {
    guard.require(http, null);
    EnabledRequest request = RequestBodyJson.read(body == null ? new byte[0] : body, EnabledRequest.class);
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("task", scheduledTasks.setEnabled(taskId, request.enabled() != null && request.enabled() == 1));
    return resp;
  }

  @DeleteMapping("/scheduled-tasks/{taskId}")
  public Map<String, Object> deleteScheduledTask(@PathVariable String taskId,
      HttpServletRequest http) {
    guard.require(http, null);
    scheduledTasks.delete(taskId);
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("task_id", taskId);
    return resp;
  }

  /**
   * 任务请求体比其它管理台接口宽：正文本身允许 32K 字符（{@code IngestRequest.MAX_CONTENT_CHARS}），
   * 8KB 的通用上限会把合法任务挡在门外。上限仍是硬编码常量，不是不校验。
   */
  private static final int MAX_TASK_BODY_BYTES = 64 * 1024;

  private CreateChannelRequest readCreateChannel(byte[] body) {
    if (body == null || body.length > 8192) {
      throw ApiException.invalidParam("请求体过大");
    }
    return RequestBodyJson.read(body, CreateChannelRequest.class);
  }

  /**
   * 频道必须建在一个真实存在的 Space 上，不接受"没提交就用登录态那个"。
   *
   * <p>管理台的 JWT 没有对应 device row，{@link DeviceGuard} 给的是虚拟身份
   * {@code Authenticated(null, "admin", "admin")}，它的 {@code groupId()} 是字符串 {@code "admin"}，
   * {@code groups} 表里并没有这一行。而设备侧的频道列表按 group_id 过滤
   * （{@code PbStore.listChannels(groupId)}），所以回退出去的频道建得过、存得下、
   * 但任何设备都永远查不到——只有照着 {@code site/admin-api.html} 用 curl 的人会踩到，
   * 管理台界面本身没有新建频道的入口。
   */
  private String requireKnownGroup(String groupId) {
    if (groupId == null || groupId.isBlank()) {
      throw ApiException.invalidParam("缺少 groupId：频道要建在设备所在的那个 Space 下，否则设备查不到");
    }
    String trimmed = groupId.trim();
    if (store.findGroup(trimmed) == null) {
      throw ApiException.invalidParam("groupId 不存在：这个 Space 里还没有设备注册过");
    }
    return trimmed;
  }

  private ScheduledTaskService.TaskInput readTaskInput(byte[] body) {
    if (body == null || body.length > MAX_TASK_BODY_BYTES) {
      throw ApiException.invalidParam("请求体过大（上限 " + MAX_TASK_BODY_BYTES + " 字节）");
    }
    return RequestBodyJson.read(body, ScheduledTaskService.TaskInput.class);
  }

  record CreateChannelRequest(String groupId, String name, String maxLevel, Integer maxImages) {}

  record EnabledRequest(Integer enabled) {}
}
