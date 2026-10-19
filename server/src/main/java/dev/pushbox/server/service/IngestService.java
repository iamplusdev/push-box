package dev.pushbox.server.service;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.api.IngestRequest;
import dev.pushbox.core.api.IngestResponse;
import dev.pushbox.core.id.Ulid;
import dev.pushbox.core.model.Level;
import dev.pushbox.core.model.MediaStatus;
import dev.pushbox.core.model.MsgType;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.core.text.Summary;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.media.AvatarSource;
import dev.pushbox.server.media.MediaFetchService;
import dev.pushbox.server.media.MediaObjects;
import dev.pushbox.server.push.FrameAssembler;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.web.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * §5.1 / §4.4：POST /message 的全部受理逻辑。200 只代表"正文与抓取任务已入库"，
 * 绝不等图片抓取（第三方超时普遍 5–10s）。
 */
@Service
public class IngestService {

  private static final Logger log = LoggerFactory.getLogger(IngestService.class);
  private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
  /** §4.4：入站 ts 的容忍窗口。strict_auth 用它拦请求，非 strict 频道用它决定这个 ts 能不能信。 */
  private static final long TS_WINDOW_MILLIS = 300_000L;

  private final PbStore store;
  private final PbProperties props;
  private final AbuseControlService abuse;
  private final FrameAssembler assembler;
  private final NotifyCoordinator coordinator;
  private final MediaFetchService fetcher;
  private final io.micrometer.core.instrument.Timer ingestTimer;

  public IngestService(PbStore store, PbProperties props, AbuseControlService abuse,
      FrameAssembler assembler, NotifyCoordinator coordinator, MediaFetchService fetcher,
      io.micrometer.core.instrument.MeterRegistry meterRegistry) {
    this.store = store;
    this.props = props;
    this.abuse = abuse;
    this.assembler = assembler;
    this.coordinator = coordinator;
    this.fetcher = fetcher;
    this.ingestTimer = meterRegistry.timer("pushbox.ingest.duration",
        "description", "消息摄入总耗时（含数据库写入与媒体抓取入队）");
  }

  public IngestResponse ingest(IngestRequest request, String remoteIp) {
    return ingestTimer.record(() -> doIngest(request, remoteIp));
  }

  /**
   * 服务端内部摄入入口（管理台定时推送用）。
   *
   * <p>与 {@link #ingest} 的区别只有两处，且都是有意为之：
   * <ul>
   *   <li>不过凭据与滥用闸门——库里只存 {@code secret_hash}，明文密钥根本拿不回来，
   *       而授权已经由管理台那一侧的 JWT 完成；频道由运维亲手选定，不是外部输入。</li>
   *   <li>不吃频道日配额——配额（§14）拦的是外部脚本跑飞。自己配的定时任务若也计入配额，
   *       结果是运维把自己的入站额度用光、把真正的第三方发送者挡在门外，这没人会预期。
   *       容量报表走 {@code messages} 表实时统计，不依赖 {@code daily_counters}，所以少记一行不影响容量口径。</li>
   * </ul>
   * 正文形状相关的规则（长度上限、级别按频道截断、图片校验、静音不弹通知、幂等）一条不少，
   * 因此定时消息与外部消息在客户端看起来完全同源。
   */
  public IngestResponse ingestInternal(Row channel, IngestRequest request) {
    return ingestTimer.record(
        () -> ingestAccepted(channel, request, System.currentTimeMillis(), false));
  }

  private IngestResponse doIngest(IngestRequest request, String remoteIp) {
    if (!abuse.ipAllowed(remoteIp)) {
      throw ApiException.rateLimited(60);
    }
    if (request == null || isBlank(request.channel()) || isBlank(request.secret())) {
      throw ApiException.invalidParam("channel 与 secret 必填且只能放在请求体里");
    }
    Row channel = store.findChannel(request.channel().trim());
    if (channel == null) {
      // 频道不存在与密钥错共用 401，不给枚举频道号码留缝隙
      throw ApiException.invalidCredential();
    }
    long now = System.currentTimeMillis();
    if (abuse.lockedUntil(channel) > now) {
      throw ApiException.channelLocked();
    }
    if (channel.getLong("archived_at") != null) {
      throw ApiException.channelLocked();
    }
    if (!ChannelService.secretMatches(request.secret().trim(), channel)) {
      boolean locked = abuse.registerCredentialFailure(channel.getString("channel_no"));
      throw locked ? ApiException.channelLocked() : ApiException.invalidCredential();
    }
    if (!abuse.channelAllowed(channel)) {
      throw ApiException.rateLimited(60);
    }
    // §14：检查发送者白名单（频道开启时）
    String senderId = clamp(request.senderIdOrDefault(), 64);
    if (!abuse.senderAllowed(channel.getString("channel_no"), senderId, channel)) {
      throw ApiException.forbidden("sender_id 不在白名单中");
    }
    // §14：检查日配额
    if (!abuse.dailyQuotaAllowed(channel)) {
      throw ApiException.rateLimited(86400);
    }
    // 只有真出过凭据错误才回写计数，否则每条消息都白打一次库
    Integer failures = channel.getInt("fail_count");
    if (failures != null && failures > 0) {
      abuse.clearCredentialFailures(channel.getString("channel_no"));
    }

    // §4.4 strict_auth：频道开启时要求 ts/nonce/sign 三件套，防重放与伪造
    checkStrictAuth(request, channel);
    return ingestAccepted(channel, request, now, true);
  }

  /**
   * 外部摄入与内部摄入共用的入库+下发段。闸门（凭据、限速、白名单、日配额）都已在上游跑过，
   * 这一段只管消息本身：正文校验、级别截断、图片入库、幂等、通知与下发。
   */
  private IngestResponse ingestAccepted(Row channel, IngestRequest request, long now,
      boolean external) {
    MsgType type = MsgType.fromWire(request.type());
    String senderId = clamp(request.senderIdOrDefault(), 64);
    if (isBlank(request.content())) {
      throw ApiException.invalidParam("content 不能为空");
    }
    int maxBodyBytes = intValue(channel, "max_body_bytes", props.getRetention().getMaxMessageBytes());
    if (request.content().getBytes(StandardCharsets.UTF_8).length > maxBodyBytes) {
      throw ApiException.tooLarge();
    }

    List<String> images = validateImages(request, channel);
    String channelNo = channel.getString("channel_no");
    String groupId = channel.getString("group_id");
    // §5.2 频道静音：消息照收、照入库、照下发，只是帧里的 notify 关掉——
    // 历史不能因为静音而消失，那是"屏蔽"不是"静音"，两回事。
    boolean notifyClient = !flag(channel, "muted");

    // §4.4 strict_auth 的 ts/nonce/sign 闸门在调用方：外部路径由 checkStrictAuth 把过，
    // 内部路径（定时推送）没有明文密钥，HMAC 无法计算，整层跳过——见 ingestInternal 的类注释。

    if (!isBlank(request.msgKey())) {
      Row existing = store.findMessageByClientId(channelNo, clamp(request.msgKey().trim(), 64));
      if (existing != null) {
        // 幂等：同一 msg_key 重复提交返回原结果，不重复入库、不重复通知
        return respond(existing);
      }
    }

    String msgId = Ulid.next();
    // 设置 MDC 关联 ID，使整条消息处理链路的日志可追踪
    org.slf4j.MDC.put("msg_id", msgId);
    try {
      // ts 允许秒或毫秒（<1e11 视为秒），缺省用服务端接收时间。
      // 越出 ±300s 的 ts 一律回落到服务端时间：strict_auth 关的频道里，第三方脚本的时钟漂移、
      // 甚至一个随手填的年份，都会直接决定消息在时间线上的位置；消息时间属于事实，不该由提交方说了算。
      long createdAt = businessTime(request.ts(), now);
      Level level = Level.fromWire(request.level())
          .truncateTo(Level.fromWire(channel.getString("max_level")));
      String summary = Summary.of(request.title(), request.content(), props.getNotify().getSummaryChars());
      String mediaStatus = images.isEmpty() ? MediaStatus.NONE.wire() : MediaStatus.PENDING.wire();
      // 坏头像不拦消息（§5.1：content 才是必填），只是这条不带头像
      AvatarSource avatar = AvatarSource.parse(request.senderAvatar());

      store.upsertSender(channelNo, senderId, clamp(request.senderName(), 64),
          avatar == null ? null : avatar.source(), MediaObjects.avatarToken(senderId), now);
      store.insertMessage(msgId, channelNo, groupId, senderId, type.wire(),
          clamp(request.title(), 512), request.content(), summary, request.url(), level.wire(),
          mediaStatus, createdAt, isBlank(request.msgKey()) ? null : clamp(request.msgKey().trim(), 64));
      for (int i = 0; i < images.size(); i++) {
        store.insertMedia(msgId, i, images.get(i), channelNo, groupId, now);
      }
      // §14：更新日计数器（消息数 + 图片数 + 正文字节数）。内部摄入不记账，见类注释。
      if (external) {
        int contentBytes = request.content().getBytes(StandardCharsets.UTF_8).length;
        store.incrementDailyCounter(channelNo, images.size(), contentBytes, now);
      }
      fetcher.enqueueAvatar(channelNo, senderId, avatar);

      Row stored = store.findMessageWithSender(msgId);
      // D24：带图的消息进合并窗口，图文齐全只发一条帧；纯文字照旧立刻下发
      if (images.isEmpty()) {
        coordinator.publishNow(stored, groupId, notifyClient);
      } else {
        coordinator.accept(msgId, groupId, notifyClient);
      }
      log.info("消息已受理 msg_id={} seq={} channel={} level={} images={} media_status={}", msgId,
          stored.getLong("seq"), channelNo, level.wire(), images.size(), mediaStatus);
      return respond(stored);
    } finally {
      org.slf4j.MDC.clear();
    }
  }

  /** media_status 直接用库里的聚合值，避免接口层另造一套与帧/history 不一致的词表。 */
  private IngestResponse respond(Row message) {
    List<dev.pushbox.core.protocol.Frames.MediaSlot> slots = assembler.mediaSlots(message);
    String mediaStatus = message.getString("media_status");
    return new IngestResponse(true, message.getString("msg_id"), longValue(message, "seq", 0L),
        message.getString("summary"), mediaStatus == null ? MediaStatus.NONE.wire() : mediaStatus,
        slots.size(), message.getString("level"));
  }

  private List<String> validateImages(IngestRequest request, Row channel) {
    List<String> out = new ArrayList<>();
    if (request.images() == null || request.images().isEmpty()) {
      return out;
    }
    int max = intValue(channel, "max_images", props.getMedia().getMaxImages());
    if (request.images().size() > max) {
      throw ApiException.invalidParam("images 最多 " + max + " 条");
    }
    for (String raw : request.images()) {
      if (raw == null || raw.isBlank()) {
        throw ApiException.invalidParam("images 每项必须是非空 URL");
      }
      String value = raw.trim();
      URI uri;
      try {
        uri = URI.create(value);
      } catch (IllegalArgumentException e) {
        throw ApiException.invalidParam("images 仅支持 http(s) URL");
      }
      // data: 内联图明确不支持（§16 第 10 项）；其余 scheme 也一并拒绝，抓取器只允许 http/https
      if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())
          || uri.getHost() == null) {
        throw ApiException.invalidParam("images 仅支持 http(s) URL");
      }
      if (value.length() > 1024) {
        throw ApiException.invalidParam("images 单条 URL 不能超过 1024 字符");
      }
      out.add(value);
    }
    return out;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
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

  private static int intValue(Row row, String column, int fallback) {
    Integer v = row.getInt(column);
    return v == null || v <= 0 ? fallback : v;
  }

  /** ts 允许秒或毫秒（&lt;1e11 视为秒），这是 §5.1 定下的对外形状，两种都得收。 */
  private static long toEpochMillis(long ts) {
    return ts < 100_000_000_000L ? ts * 1000 : ts;
  }

  /** 越出 ±300s 的 ts 回落到服务端接收时间：消息落在时间线哪一格是事实，不由提交方指定。 */
  private static long businessTime(Long ts, long serverNow) {
    if (ts == null || ts <= 0) {
      return serverNow;
    }
    long client = toEpochMillis(ts);
    return Math.abs(client - serverNow) > TS_WINDOW_MILLIS ? serverNow : client;
  }

  /** 治理位是 0/1 列，不能借 intValue 读：那里 <=0 会被当成"没配"回落默认值，而 0 在这里就是"关"。 */
  private static boolean flag(Row row, String column) {
    Integer v = row.getInt(column);
    return v != null && v == 1;
  }

  private static long longValue(Row row, String column, long fallback) {
    Long v = row.getLong(column);
    return v == null ? fallback : v;
  }

  /** §4.4 strict_auth：频道开启时要求 ts/nonce/sign，防重放与伪造。 */
  private void checkStrictAuth(IngestRequest request, Row channel) {
    Integer strict = channel.getInt("strict_auth");
    if (strict == null || strict != 1) {
      return;  // 未开启，跳过
    }
    if (request.ts() == null || isBlank(request.nonce()) || isBlank(request.sign())) {
      throw ApiException.invalidParam("strict_auth 开启时 ts/nonce/sign 必填");
    }
    // 时钟窗口 ±300s
    long now = System.currentTimeMillis();
    long tsMs = request.ts() < 100_000_000_000L ? request.ts() * 1000 : request.ts();
    if (Math.abs(now - tsMs) > 300_000) {
      throw ApiException.invalidParam("clock_skew");
    }
    // nonce 去重（10min）
    if (abuse.nonceReplayed(request.nonce().trim())) {
      throw ApiException.invalidParam("replay");
    }
    // canonical = channel\n ts\n nonce\n sha256(content)
    String contentSha = sha256Hex(request.content());
    String canonical = request.channel().trim() + "\n" + request.ts() + "\n"
        + request.nonce().trim() + "\n" + contentSha;
    String expectedSign = hmacSha256Hex(request.secret().trim(), canonical);
    if (!constantTimeEquals(expectedSign, request.sign().trim())) {
      throw ApiException.invalidCredential();
    }
  }

  private static String sha256Hex(String text) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  private static String hmacSha256Hex(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(result);
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 不可用", e);
    }
  }

  /** 常量时间比较，防侧信道攻击。 */
  private static boolean constantTimeEquals(String a, String b) {
    if (a.length() != b.length()) {
      return false;
    }
    int diff = 0;
    for (int i = 0; i < a.length(); i++) {
      diff |= a.charAt(i) ^ b.charAt(i);
    }
    return diff == 0;
  }
}
