package dev.pushbox.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.mybatisflex.core.row.Row;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.core.protocol.Json;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.ChannelService;
import dev.pushbox.server.service.ConfigNotifier;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RestController;

/** §5.2 频道管理（Android"我的 → 频道管理"用）：全部要设备签名，密钥只在创建/重置时回显一次。 */
@RestController
@RequestMapping("/api/v1/channels")
public class ChannelController {

  public record CreateRequest(String name,
      @com.fasterxml.jackson.annotation.JsonProperty("max_level") String maxLevel,
      @com.fasterxml.jackson.annotation.JsonProperty("max_images") Integer maxImages) {}

  /** §14 白名单条目：enabled 省略即"加上并能发"，显式 false 是停用而不是删除。 */
  public record SenderRequest(@com.fasterxml.jackson.annotation.JsonProperty("sender_id") String senderId,
      Boolean enabled) {}

  /** §5.2 接入频道：频道号 + 密钥二次确认。 */
  public record JoinRequest(
      @com.fasterxml.jackson.annotation.JsonProperty("channel_no") String channelNo,
      String secret) {}

  /** §5.2 切换过滤模式：1=只收已接入频道，0=全收。 */
  public record FilterModeRequest(
      @com.fasterxml.jackson.annotation.JsonProperty("subscribed_only") int subscribedOnly) {}

  private final ChannelService channels;
  private final DeviceGuard guard;
  private final PbStore store;
  private final ConfigNotifier config;

  public ChannelController(ChannelService channels, DeviceGuard guard, PbStore store,
      ConfigNotifier config) {
    this.channels = channels;
    this.guard = guard;
    this.store = store;
    this.config = config;
  }

  @PostMapping
  public Map<String, Object> create(HttpServletRequest http, @RequestBody byte[] body) {
    DeviceGuard.Authenticated auth = guard.require(http, body);
    CreateRequest request = Json.readBytes(body, CreateRequest.class);
    ChannelService.Created created = channels.create(auth.groupId(), request.name(),
        request.maxLevel(), request.maxImages());
    // 建频道的那台设备默认接入它：不接的话，一开「只接收已接入频道」就会把自己刚建的频道静默掐断，
    // 而界面上没有任何一处提示过这两件事有关联
    store.subscribeChannel(auth.deviceId(), created.channelNo(), System.currentTimeMillis());
    Map<String, Object> response = new LinkedHashMap<>(ChannelService.view(store.findChannel(
        created.channelNo())));
    // 密钥只在创建与重置这两条响应里出现一次，因此它只能来自 Created，不能进 view()
    response.put("secret", created.secret());
    response.put("ok", true);
    return guard.seal(auth.device(), response);
  }

  @GetMapping
  public Map<String, Object> list(HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    List<Map<String, Object>> list = channels.list(auth.groupId(), auth.deviceId());
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("channels", list);
    resp.put("subscribed_only", intValue(auth.device(), "subscribed_only", 0));
    return guard.seal(auth.device(), resp);
  }

  /** §5.2 接入频道：本 Space 内的频道 + 密钥二次确认。 */
  @PostMapping("/join")
  public Map<String, Object> join(HttpServletRequest http, @RequestBody byte[] body) {
    DeviceGuard.Authenticated auth = guard.require(http, body);
    JoinRequest request = Json.readBytes(body, JoinRequest.class);
    Map<String, Object> result = channels.join(auth.device(), request.channelNo(), request.secret());
    // 接入只改本机订阅集，不广播 config 帧（其他设备不需要知道）
    return guard.seal(auth.device(), result);
  }

  /** §5.2 取消接入：幂等，不影响过滤模式开关。 */
  @PostMapping("/leave")
  public Map<String, Object> leave(HttpServletRequest http, @RequestBody byte[] body) {
    DeviceGuard.Authenticated auth = guard.require(http, body);
    JoinRequest request = Json.readBytes(body, JoinRequest.class);
    int removed = channels.leave(auth.device(), request.channelNo());
    return guard.seal(auth.device(),
        Map.of("ok", true, "channel_no", request.channelNo(), "removed", removed > 0));
  }

  /** §5.2 切换过滤模式：1=只收已接入频道，0=全收。 */
  @PostMapping("/subscription-mode")
  public Map<String, Object> setFilterMode(HttpServletRequest http, @RequestBody byte[] body) {
    DeviceGuard.Authenticated auth = guard.require(http, body);
    FilterModeRequest request = Json.readBytes(body, FilterModeRequest.class);
    int mode = channels.setFilterMode(auth.device(), request.subscribedOnly());
    return guard.seal(auth.device(), Map.of("ok", true, "subscribed_only", mode));
  }

  @PostMapping("/{channelNo}/rotate-secret")
  public Map<String, Object> rotate(@PathVariable String channelNo, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    ChannelService.Created created = channels.rotateSecret(channelNo, auth.groupId());
    config.channel(auth.groupId(), Frames.R_SECRET_ROTATED, channelNo);
    return guard.seal(auth.device(),
        Map.of("ok", true, "channel_no", created.channelNo(), "secret", created.secret()));
  }

  @DeleteMapping("/{channelNo}")
  public Map<String, Object> archive(@PathVariable String channelNo, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    long archivedAt = channels.archive(channelNo, auth.groupId());
    config.channel(auth.groupId(), Frames.R_CHANNEL_ARCHIVED, channelNo);
    return guard.seal(auth.device(),
        Map.of("ok", true, "channel_no", channelNo, "archived_at", archivedAt));
  }

  /**
   * §5.2/§14 改频道。这里刻意收 {@link JsonNode} 而不是记录类：治理位要区分"没提交"与"提交 null"，
   * 用记录类反序列化时两者都是 null，未提交的字段就会被当成"清空"静默写回库，
   * 客户端收到一个回显自己请求值的 200，实际什么都没改——这是最难排查的一种假成功。
   */
  @PutMapping("/{channelNo}")
  public Map<String, Object> update(@PathVariable String channelNo, HttpServletRequest http,
      @RequestBody byte[] body) {
    DeviceGuard.Authenticated auth = guard.require(http, body);
    JsonNode node = Json.readNode(body);
    ChannelService.Patch patch = new ChannelService.Patch(
        text(node, "name"), text(node, "max_level"), integer(node, "max_images"),
        integer(node, "rate_limit"), integer(node, "max_body_bytes"), integer(node, "strict_auth"),
        integer(node, "muted"), integer(node, "daily_msg_limit"), integer(node, "daily_image_limit"),
        longValue(node, "daily_bytes_limit"), integer(node, "sender_whitelist_mode"));
    Map<String, Object> view = channels.updateGovernance(channelNo, auth.groupId(), patch);
    config.channel(auth.groupId(), Frames.R_CHANNEL_UPDATED, channelNo);
    Map<String, Object> response = new LinkedHashMap<>(view);
    response.put("ok", true);
    return guard.seal(auth.device(), response);
  }

  /** §14：容量报告接口，返回**该频道**的消息数、字节数、最早时间、图片统计。 */
  @GetMapping("/{channelNo}/capacity")
  public Map<String, Object> capacity(@PathVariable String channelNo, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    Row channel = store.findChannel(channelNo);
    if (channel == null || !channel.getString("group_id").equals(auth.groupId())) {
      throw ApiException.notFound();
    }
    Row report = store.capacityReport(channelNo);
    return guard.seal(auth.device(), Map.of("ok", true, "channel_no", channelNo,
        "total_messages", report.getLong("total_messages"),
        "total_text_bytes", report.getLong("total_text_bytes"),
        "earliest_msg_at", report.getLong("earliest_msg_at") == null ? 0L : report.getLong("earliest_msg_at"),
        "total_images", report.getLong("total_images"),
        "total_image_bytes", report.getLong("total_image_bytes")));
  }

  /** §5.2 频道号码重置：换发新对外号码，旧号即刻作废，历史随迁（secret 不变）。 */
  @PostMapping("/{channelNo}/reset-channel-no")
  public Map<String, Object> resetChannelNo(@PathVariable String channelNo, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    ChannelService.Created created = channels.resetChannelNo(channelNo, auth.groupId());
    // 带新号码：客户端本地的会话仍以旧号存着，收到这条只需重拉频道面，把新号显示出来
    config.channel(auth.groupId(), Frames.R_CHANNEL_NO_RESET, created.channelNo());
    Map<String, Object> response = new LinkedHashMap<>(
        ChannelService.view(store.findChannel(created.channelNo())));
    response.put("ok", true);
    response.put("old_channel_no", channelNo);
    return guard.seal(auth.device(), response);
  }

  /** §14：白名单发送者列表（含已停用的），只有频道归属者能看。 */
  @GetMapping("/{channelNo}/senders")
  public Map<String, Object> senders(@PathVariable String channelNo, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    List<Map<String, Object>> list = channels.listSenders(channelNo, auth.groupId());
    return guard.seal(auth.device(), Map.of("ok", true, "channel_no", channelNo, "senders", list));
  }

  /** §14：加/停一个白名单发送者。 */
  @PostMapping("/{channelNo}/senders")
  public Map<String, Object> putSender(@PathVariable String channelNo, HttpServletRequest http,
      @RequestBody byte[] body) {
    DeviceGuard.Authenticated auth = guard.require(http, body);
    SenderRequest request = Json.readBytes(body, SenderRequest.class);
    Map<String, Object> result = channels.setSenderAllowed(channelNo, auth.groupId(),
        request.senderId(), request.enabled() == null || request.enabled());
    config.channel(auth.groupId(), Frames.R_SENDERS_UPDATED, channelNo);
    return guard.seal(auth.device(), result);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual()) {
      throw ApiException.invalidParam(field + " 必须是字符串");
    }
    return value.asText();
  }

  private static Integer integer(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber()) {
      throw ApiException.invalidParam(field + " 必须是整数");
    }
    return value.intValue();
  }

  /** 日字节上限是 BIGINT 列：一屏消息攒一年会越过 int，所以这一档单独按 long 收。 */
  private static Long longValue(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber()) {
      throw ApiException.invalidParam(field + " 必须是整数");
    }
    return value.longValue();
  }

  private static int intValue(Row row, String column, int fallback) {
    Integer v = row.getInt(column);
    return v == null ? fallback : v;
  }
}
