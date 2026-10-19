package dev.pushbox.server.service;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.crypto.Hashes;
import dev.pushbox.core.model.Level;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** §5.2 频道 CRUD：凭据只在创建/重置时明文回显一次，库里只有 sha256。 */
@Service
public class ChannelService {

  public record Created(String channelNo, String secret, String name, String maxLevel,
      int maxImages) {}

  /**
   * §5.2/§14 频道治理位。每个字段 {@code null} 都表示"不动它"，这是 PUT 能增量提交的前提——
   * 早先 PUT 复用创建请求体，未提交的字段会被静默丢弃，客户端以为自己改了、库里没变。
   */
  public record Patch(String name, String maxLevel, Integer maxImages, Integer rateLimit,
      Integer maxBodyBytes, Integer strictAuth, Integer muted, Integer dailyMsgLimit,
      Integer dailyImageLimit, Long dailyBytesLimit, Integer senderWhitelistMode) {

    boolean empty() {
      return name == null && maxLevel == null && maxImages == null && rateLimit == null
          && maxBodyBytes == null && strictAuth == null && muted == null && dailyMsgLimit == null
          && dailyImageLimit == null && dailyBytesLimit == null && senderWhitelistMode == null;
    }
  }

  private final PbStore store;
  private final PbProperties props;
  private final SecureRandom random = new SecureRandom();

  public ChannelService(PbStore store, PbProperties props) {
    this.store = store;
    this.props = props;
  }

  public synchronized Created create(String groupId, String name, String maxLevel, Integer maxImages) {
    long now = System.currentTimeMillis();
    String channelNo = allocateChannelNo();
    String secret = newSecret();
    Level max = maxLevel == null ? Level.fromWire(props.getChannels().getDefaultMaxLevel())
        : Level.fromWire(maxLevel);
    int images = clampInt("max_images", maxImages == null ? props.getChannels().getDefaultMaxImages()
        : maxImages, 0, 16);
    store.insertChannel(channelNo, secretHash(secret), groupId, trim(name, 64), max.wire(), images, now);
    return new Created(channelNo, secret, trim(name, 64), max.wire(), images);
  }

  public Created rotateSecret(String channelNo, String groupId) {
    String secret = newSecret();
    if (store.updateChannelSecret(channelNo, groupId, secretHash(secret)) == 0) {
      throw ApiException.notFound("channel");
    }
    // 重置密钥同时解除锁定，否则运维改对了凭据还在锁定期内
    store.clearCredentialFailures(channelNo);
    Row channel = store.findChannel(channelNo);
    return new Created(channelNo, secret, channel.getString("name"), channel.getString("max_level"),
        intValue(channel, "max_images", props.getChannels().getDefaultMaxImages()));
  }

  /** §5.2 频道删除＝软归档：入站即刻 403，历史消息按 D3 保留，列表不再返回。 */
  public long archive(String channelNo, String groupId) {
    long now = System.currentTimeMillis();
    if (store.archiveChannel(channelNo, groupId, now) == 0) {
      throw ApiException.notFound("channel");
    }
    return now;
  }

  /**
   * §5.2 频道号码重置：换发新的对外号码，旧号码即刻作废（第三方再往旧号发就 401/404），
   * 会话/消息/媒体/已读游标随迁（D3：历史不丢）。secret 不受影响，仍只在 rotate-secret 时轮换。
   * 新号码由 {@link #allocateChannelNo()} 保证全局唯一，不会与既有频道撞号。
   */
  @Transactional
  public Created resetChannelNo(String channelNo, String groupId) {
    Row channel = store.findChannel(channelNo);
    if (channel == null || !groupId.equals(channel.getString("group_id"))) {
      throw ApiException.notFound("channel");
    }
    String newNo = allocateChannelNo();
    store.resetChannelNo(groupId, channelNo, newNo);
    Row migrated = store.findChannel(newNo);
    return new Created(newNo, null, migrated.getString("name"), migrated.getString("max_level"),
        intValue(migrated, "max_images", props.getChannels().getDefaultMaxImages()));
  }

  /** §5.2 只改名字/强度：给"改个名"这种最小调用留的旧形状，等价于一个两字段 Patch。 */
  public Created update(String channelNo, String groupId, String name, String level) {
    Map<String, Object> after = updateGovernance(channelNo, groupId, new Patch(name, level,
        null, null, null, null, null, null, null, null, null));
    return new Created(channelNo, null, (String) after.get("name"), (String) after.get("max_level"),
        (Integer) after.get("max_images"));
  }

  /**
   * §5.2/§14 治理位落库。返回响应里的频道视图，客户端据此确认"服务端认下了哪些值"，
   * 而不是把自己发出去的那份回显一遍——回显出来的成功是最难排查的一种假成功。
   */
  public Map<String, Object> updateGovernance(String channelNo, String groupId, Patch patch) {
    owned(channelNo, groupId);
    if (patch.empty()) {
      throw ApiException.invalidParam("至少要提交一个可修改的频道字段");
    }
    Map<String, Object> values = new LinkedHashMap<>();
    if (patch.name() != null) {
      values.put("name", trim(patch.name(), 64));
    }
    if (patch.maxLevel() != null) {
      values.put("max_level", Level.fromWire(patch.maxLevel()).wire());
    }
    if (patch.maxImages() != null) {
      values.put("max_images", clampInt("max_images", patch.maxImages(), 0, 16));
    }
    // 0 在限流里读作"没配"（AbuseControlService 回落到全局默认），所以允许显式写 0 交回默认值。
    if (patch.rateLimit() != null) {
      values.put("rate_limit", clampInt("rate_limit", patch.rateLimit(), 0, 100_000));
    }
    if (patch.maxBodyBytes() != null) {
      values.put("max_body_bytes", clampInt("max_body_bytes", patch.maxBodyBytes(), 1024, 8_388_608));
    }
    if (patch.strictAuth() != null) {
      values.put("strict_auth", flag("strict_auth", patch.strictAuth()));
    }
    if (patch.muted() != null) {
      values.put("muted", flag("muted", patch.muted()));
    }
    if (patch.dailyMsgLimit() != null) {
      values.put("daily_msg_limit", clampInt("daily_msg_limit", patch.dailyMsgLimit(), 0, 10_000_000));
    }
    if (patch.dailyImageLimit() != null) {
      values.put("daily_image_limit", clampInt("daily_image_limit", patch.dailyImageLimit(), 0,
          10_000_000));
    }
    if (patch.dailyBytesLimit() != null) {
      values.put("daily_bytes_limit", clampLong("daily_bytes_limit", patch.dailyBytesLimit(), 0L,
          1_000_000_000_000L));
    }
    if (patch.senderWhitelistMode() != null) {
      int mode = flag("sender_whitelist_mode", patch.senderWhitelistMode());
      // 白名单模式一开，库里一条记录都没有的话，这个频道会当场拒掉所有发送者，
      // 而且只能靠直接改库解开——所以先拦住，让调用方明白顺序是"先加人，再开门"。
      if (mode == 1 && store.listSenderAllowlist(channelNo).isEmpty()) {
        throw ApiException.invalidParam("开启白名单模式前必须先添加发送者：POST /channels/"
            + channelNo + "/senders");
      }
      values.put("sender_whitelist_mode", mode);
    }
    store.updateChannelGovernance(channelNo, groupId, values);
    return view(store.findChannel(channelNo));
  }

  /** §14 白名单列表：enabled 一并返回，被停用的人也要看得见，否则运维只知道"少了谁"。 */
  public List<Map<String, Object>> listSenders(String channelNo, String groupId) {
    owned(channelNo, groupId);
    List<Map<String, Object>> out = new ArrayList<>();
    for (Row row : store.listSenderAllowlist(channelNo)) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("sender_id", row.getString("sender_id"));
      item.put("enabled", intValue(row, "enabled", 1));
      out.add(item);
    }
    return out;
  }

  /** §14 加/停一个白名单发送者：enabled=false 是停用不是删除，保留"谁曾经能发"这条线索。 */
  public Map<String, Object> setSenderAllowed(String channelNo, String groupId, String senderId,
      boolean enabled) {
    owned(channelNo, groupId);
    String id = trim(senderId, 64);
    if (id == null || id.isEmpty()) {
      throw ApiException.invalidParam("sender_id 不能为空");
    }
    store.upsertSenderAllowlist(channelNo, id, enabled, System.currentTimeMillis());
    return Map.of("ok", true, "channel_no", channelNo, "sender_id", id, "enabled", enabled ? 1 : 0);
  }

  /**
   * 频道的对外视图：号码、名字、强度、图片上限与全部治理位。
   * 密钥不在这里，任何读路径都不带 {@code secret}（⑦ 类用例盯的就是这一条）。
   */
  public static Map<String, Object> view(Row channel) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("channel_no", channel.getString("channel_no"));
    out.put("name", nullToEmpty(channel.getString("name")));
    out.put("max_level", channel.getString("max_level"));
    out.put("max_images", intValue(channel, "max_images", 4));
    out.put("rate_limit", intValue(channel, "rate_limit", 60));
    out.put("max_body_bytes", intValue(channel, "max_body_bytes", 32768));
    out.put("strict_auth", intValue(channel, "strict_auth", 0));
    out.put("muted", intValue(channel, "muted", 0));
    out.put("daily_msg_limit", intValue(channel, "daily_msg_limit", 0));
    out.put("daily_image_limit", intValue(channel, "daily_image_limit", 0));
    out.put("daily_bytes_limit", longValue(channel, "daily_bytes_limit", 0L));
    out.put("sender_whitelist_mode", intValue(channel, "sender_whitelist_mode", 0));
    out.put("created_at", nullToZero(channel.getLong("created_at")));
    out.put("archived_at", channel.getLong("archived_at"));
    return out;
  }

  /** 列表：只回本组未归档的频道，且永不带密钥。 */
  public List<Map<String, Object>> list(String groupId) {
    return list(groupId, null);
  }

  /**
   * 带「已接入」标记的列表：{@code deviceId} 为 null 时退化为普通列表（CLI/管理端调用），
   * 此时省略 {@code subscribed} 键；deviceId 已知时该键<b>恒在</b>，一条订阅都没有也只是全 false——
   * 客户端界面要能在"没接入任何频道"这个正常状态下读到同一个字段，而不是靠键缺失去猜。
   * {@code subscribed_only} 不在这里，那是设备行上的字段，由 Controller 从 auth.device() 取。
   */
  public List<Map<String, Object>> list(String groupId, String deviceId) {
    java.util.Set<String> joined = deviceId == null ? java.util.Set.of()
        : new java.util.HashSet<>(store.subscribedChannelNos(deviceId));
    List<Map<String, Object>> out = new ArrayList<>();
    for (Row row : store.listChannels(groupId)) {
      Map<String, Object> item = view(row);
      if (deviceId != null) {
        item.put("subscribed", joined.contains(item.get("channel_no")));
      }
      out.add(item);
    }
    return out;
  }

  /**
   * §5.2 接入频道：用频道号 + 密钥证明"我有权往这个频道发"，服务端把该频道加入这台设备的订阅集。
   * 密钥只作二次确认、不是读凭据（§4.1）；错密钥返回 401，跨组或未知频道返回 404，
   * 已归档频道对客户端等同于不存在（也回 404）。
   *
   * <p>注意：接入不触发 fail_count/locked_until 锁——fail_count 保护的是公开入站路径（外部发送者
   * 猜密钥），而接入接口本身已经过设备 token + Ed25519 签名鉴权，同一 Space 成员暴力试密钥没有意义。
   */
  public Map<String, Object> join(Row device, String channelNo, String secret) {
    String id = trim(channelNo, 8);
    if (id == null || id.isEmpty()) {
      throw ApiException.invalidParam("channel_no 必填");
    }
    if (secret == null || secret.isBlank()) {
      throw ApiException.unauthorized("secret 必填");
    }
    Row channel = store.findChannel(id);
    if (channel == null || !device.getString("group_id").equals(channel.getString("group_id"))
        || channel.getLong("archived_at") != null) {
      throw ApiException.notFound("channel");
    }
    if (!secretMatches(secret, channel)) {
      throw ApiException.unauthorized("密钥不正确");
    }
    long now = System.currentTimeMillis();
    store.subscribeChannel(device.getString("device_id"), id, now);
    Map<String, Object> resp = new LinkedHashMap<>(view(channel));
    resp.put("ok", true);
    resp.put("subscribed_only", intValue(device, "subscribed_only", 0));
    return resp;
  }

  /**
   * 取消接入：幂等，返回真删掉的行数（0 表示本来就没接入）。
   * 取消不影响过滤模式开关——开了过滤但没接任何频道时，这台设备将收不到任何内容帧，
   * 这是用户主动选择的结果，而不是意外脚枪。
   */
  public int leave(Row device, String channelNo) {
    String id = trim(channelNo, 8);
    if (id == null || id.isEmpty()) {
      throw ApiException.invalidParam("channel_no 必填");
    }
    // 归属校验：不能跨组取消别人的接入（虽然只是删一行，但语义上不该允许）
    Row channel = store.findChannel(id);
    if (channel != null && !device.getString("group_id").equals(channel.getString("group_id"))) {
      throw ApiException.notFound("channel");
    }
    return store.unsubscribeChannel(device.getString("device_id"), id);
  }

  /**
   * 切换过滤模式：{@code subscribedOnly} 为 1 时，这台设备只收已接入频道的内容帧；
   * 为 0 时恢复全收。返回当前模式值，方便客户端确认。
   */
  public int setFilterMode(Row device, int subscribedOnly) {
    if (subscribedOnly != 0 && subscribedOnly != 1) {
      throw ApiException.invalidParam("subscribed_only 只能是 0 或 1");
    }
    store.setSubscribedOnly(device.getString("device_id"), device.getString("group_id"), subscribedOnly);
    return subscribedOnly;
  }

  /**
   * 设备的读取范围：null = 不过滤（未进入过滤模式）；空集合 = 开了过滤但没接入任何频道，一条都不给；
   * 非空集合 = 只允许这些频道号的行。用于 WS 回放、REST 历史与搜索。
   */
  public java.util.Set<String> readScope(Row device) {
    if (intValue(device, "subscribed_only", 0) == 0) {
      return null;
    }
    return new java.util.LinkedHashSet<>(store.subscribedChannelNos(device.getString("device_id")));
  }

  /** 归属校验：不存在、不属于这个组都一律 404，不给枚举频道号码留缝隙。 */
  private Row owned(String channelNo, String groupId) {
    Row channel = store.findChannel(channelNo);
    if (channel == null || !groupId.equals(channel.getString("group_id"))) {
      throw ApiException.notFound("channel");
    }
    return channel;
  }

  public static String secretHash(String secret) {
    return Hashes.sha256Hex(secret.getBytes(StandardCharsets.UTF_8));
  }

  /** 常量时间比较：sha256 摘要按字节比，MessageDigest.isEqual 本身不泄漏长度差异。 */
  public static boolean secretMatches(String presented, Row channel) {
    return java.security.MessageDigest.isEqual(
        secretHash(presented).getBytes(StandardCharsets.UTF_8),
        channel.getString("secret_hash").getBytes(StandardCharsets.UTF_8));
  }

  private String allocateChannelNo() {
    int len = props.getChannels().getChannelNoLength();
    for (int attempt = 0; attempt < 20; attempt++) {
      StringBuilder sb = new StringBuilder(len);
      for (int i = 0; i < len; i++) {
        sb.append(random.nextInt(10));
      }
      String candidate = sb.toString();
      if (store.findChannel(candidate) == null) {
        return candidate;
      }
    }
    throw new IllegalStateException("无法分配空闲频道号码");
  }

  private String newSecret() {
    byte[] bytes = new byte[props.getChannels().getSecretBytes()];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static int flag(String column, Integer value) {
    if (value != 0 && value != 1) {
      throw ApiException.invalidParam(column + " 只能是 0 或 1");
    }
    return value;
  }

  private static int clampInt(String column, int value, int min, int max) {
    if (value < min || value > max) {
      throw ApiException.invalidParam(column + " 必须在 " + min + ".." + max + " 之间");
    }
    return value;
  }

  private static long clampLong(String column, long value, long min, long max) {
    if (value < min || value > max) {
      throw ApiException.invalidParam(column + " 必须在 " + min + ".." + max + " 之间");
    }
    return value;
  }

  private static int intValue(Row row, String column, int fallback) {
    Integer v = row.getInt(column);
    return v == null ? fallback : v;
  }

  private static long longValue(Row row, String column, long fallback) {
    Long v = row.getLong(column);
    return v == null ? fallback : v;
  }

  private static long nullToZero(Long v) {
    return v == null ? 0L : v;
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private static String trim(String s, int max) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.length() <= max ? t : t.substring(0, max);
  }

  /** Web 管理台：列出所有未归档频道（跨 Space）。 */
  public List<Map<String, Object>> listAll() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Row row : store.listAllChannels()) {
      Map<String, Object> item = view(row);
      // 跨 Space 列表要能区分频道归属哪一组；共用 view() 不带 group_id，这里单独补，不影响客户端契约
      item.put("group_id", row.getString("group_id"));
      out.add(item);
    }
    return out;
  }
}
