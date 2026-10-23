package dev.pushbox.server.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.mybatisflex.core.row.Row;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** §4.4：频道/设备/IP 三维限流 + 凭据错误锁定（防枚举），全部内存计数，锁定状态落库。
 * §14：图片抓取频控、日配额检查、sender 白名单验证。 */
@Service
public class AbuseControlService {

  private final PbStore store;
  private final PbProperties props;
  private final Cache<String, int[]> channelWindow = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(1)).maximumSize(10_000).build();
  private final Cache<String, int[]> ipWindow = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(1)).maximumSize(10_000).build();
  private final Cache<String, int[]> deviceWindow = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(1)).maximumSize(10_000).build();
  /** §14：按频道统计图片抓取频率（每分钟）。 */
  private final Cache<String, int[]> imageFetchWindow = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(1)).maximumSize(10_000).build();
  /** REST 签名 nonce 缓存，防重放。 */
  private final Cache<String, Boolean> seenNonces = Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofMinutes(10)).maximumSize(200_000).build();
  /**
   * 管理台登录的按 IP 失败状态：{@code [失败次数, 解锁时刻毫秒]}。
   * 与频道锁定共用同一套阈值（{@code pb.ingest.credential-fail-lock} / {@code lock-window}），
   * 只是登录没有对应的库行可写，状态只能留在内存里（进程重启即清零，这是内存态的代价，
   * 换不到"跨节点一致"，但换得到"不必为管理台再建一张表"）。
   */
  private final Cache<String, long[]> adminLoginAttempts;
  private final Counter channelRejected;
  private final Counter ipRejected;
  private final Counter deviceRejected;
  private final Counter credentialLocks;
  /** §14：图片抓取被拒计数。 */
  private final Counter imageFetchRejected;
  /** §14：日配额超限计数。 */
  private final Counter dailyQuotaExceeded;
  /** §14：白名单拒绝计数。 */
  private final Counter senderWhitelistRejected;
  /** 管理台口令被刷的锁定次数：和 ingest 那条分开，因为处置对象是运维面而不是第三方频道。 */
  private final Counter adminLoginLocks;

  public AbuseControlService(PbStore store, PbProperties props, MeterRegistry registry) {
    this.store = store;
    this.props = props;
    this.adminLoginAttempts = Caffeine.newBuilder()
        // 每条记录按自己的剩余锁期过期：没锁时失败计数只活一个窗口，锁到期就自动放行——
        // 既不会留下永久黑名单，也不用再养一个清理任务。
        .expireAfter(new Expiry<String, long[]>() {
          @Override
          public long expireAfterCreate(String key, long[] state, long currentTime) {
            return adminLoginTtlNanos(state);
          }

          @Override
          public long expireAfterUpdate(String key, long[] state, long currentTime,
              long currentDuration) {
            return adminLoginTtlNanos(state);
          }

          @Override
          public long expireAfterRead(String key, long[] state, long currentTime,
              long currentDuration) {
            return currentDuration;
          }
        })
        .maximumSize(10_000).build();
    // §5.2 限流命中是要长期盯的曲线：一旦有人刷 secret，这里先于日志报警。
    // 计数只带维度不带频道号——频道号进 tag 会让基数跟着第三方一起涨。
    this.channelRejected = registry.counter("pushbox.ratelimit.rejected", "dim", "channel");
    this.ipRejected = registry.counter("pushbox.ratelimit.rejected", "dim", "ip");
    this.deviceRejected = registry.counter("pushbox.ratelimit.rejected", "dim", "device");
    this.credentialLocks = registry.counter("pushbox.ingest.credential_locked");
    this.imageFetchRejected = registry.counter("pushbox.media.fetch_rejected", "reason", "rate_limit");
    this.dailyQuotaExceeded = registry.counter("pushbox.ingest.quota_exceeded", "type", "daily");
    this.senderWhitelistRejected = registry.counter("pushbox.ingest.sender_rejected", "reason", "whitelist");
    this.adminLoginLocks = registry.counter("pushbox.admin.login_locked");
  }

  public boolean channelAllowed(Row channel) {
    int limit = intValue(channel, "rate_limit", props.getRatelimit().getPerChannelPerMin());
    String no = channel == null ? "unknown" : channel.getString("channel_no");
    int[] hit = channelWindow.get(no, k -> new int[1]);
    boolean allowed = ++hit[0] <= limit + props.getRatelimit().getBurst();
    if (!allowed) {
      channelRejected.increment();
    }
    return allowed;
  }

  public boolean ipAllowed(String ip) {
    int[] hit = ipWindow.get(ip == null ? "unknown" : ip, k -> new int[1]);
    boolean allowed = ++hit[0] <= props.getRatelimit().getPerIpPerMin() + props.getRatelimit().getBurst();
    if (!allowed) {
      ipRejected.increment();
    }
    return allowed;
  }

  public boolean deviceAllowed(String deviceId) {
    int[] hit = deviceWindow.get(deviceId, k -> new int[1]);
    boolean allowed = ++hit[0] <= props.getRatelimit().getPerDevicePerMin();
    if (!allowed) {
      deviceRejected.increment();
    }
    return allowed;
  }

  /** §14：检查频道级图片抓取频率限制（独立于消息限流）。 */
  public boolean imageFetchAllowed(String channelNo) {
    int limit = props.getMedia().getFetchRatePerChannelPerMin();
    int[] hit = imageFetchWindow.get(channelNo, k -> new int[1]);
    boolean allowed = ++hit[0] <= limit;
    if (!allowed) {
      imageFetchRejected.increment();
    }
    return allowed;
  }

  /** §14：检查日配额（消息数、图片数与正文字节数）。返回 true 表示未超限。 */
  public boolean dailyQuotaAllowed(Row channel) {
    String channelNo = channel.getString("channel_no");
    Row counter = store.getTodayCounter(channelNo);
    int currentMsgs = counter == null || counter.getInt("msg_count") == null ? 0 : counter.getInt("msg_count");
    int currentImages = counter == null || counter.getInt("image_count") == null ? 0 : counter.getInt("image_count");
    long currentBytes = counter == null || counter.getLong("total_bytes") == null ? 0L
        : counter.getLong("total_bytes");
    int dailyMsgLimit = intValue(channel, "daily_msg_limit", 0);
    int dailyImageLimit = intValue(channel, "daily_image_limit", 0);
    // total_bytes 从 §14 第一版起就在累计，却从来没有上限可比：这里补上第三个门。
    long dailyBytesLimit = longValue(channel, "daily_bytes_limit", 0L);
    boolean msgOk = dailyMsgLimit <= 0 || currentMsgs < dailyMsgLimit;
    boolean imageOk = dailyImageLimit <= 0 || currentImages < dailyImageLimit;
    boolean bytesOk = dailyBytesLimit <= 0 || currentBytes < dailyBytesLimit;
    if (!msgOk || !imageOk || !bytesOk) {
      dailyQuotaExceeded.increment();
    }
    return msgOk && imageOk && bytesOk;
  }

  /** §14：发送者白名单模式是 0/1 列，0 就是"关"，不能走 intValue 的"<=0 视为没配并回落默认值"。 */
  public boolean senderAllowed(String channelNo, String senderId, Row channel) {
    Integer whitelistMode = channel.getInt("sender_whitelist_mode");
    if (whitelistMode == null || whitelistMode != 1) {
      return true; // 未开启白名单模式，放行
    }
    boolean allowed = store.isSenderAllowed(channelNo, senderId);
    if (!allowed) {
      senderWhitelistRejected.increment();
    }
    return allowed;
  }

  public long lockedUntil(Row channel) {
    Long v = channel.getLong("locked_until");
    return v == null ? 0L : v;
  }

  /** 记一次凭据错误：达到阈值就锁 lock_window，返回是否已锁。 */
  public boolean registerCredentialFailure(String channelNo) {
    long now = System.currentTimeMillis();
    Row channel = store.findChannel(channelNo);
    int failCount = (channel == null || channel.getInt("fail_count") == null ? 0
        : channel.getInt("fail_count")) + 1;
    int threshold = props.getIngest().getCredentialFailLock();
    long lockedUntil = failCount >= threshold ? now + props.getIngest().getLockWindow().toMillis() : 0L;
    if (channel != null) {
      store.recordCredentialFailure(channelNo, failCount, lockedUntil);
    }
    boolean locked = lockedUntil > 0;
    if (locked) {
      credentialLocks.increment();
    }
    return locked;
  }

  public void clearCredentialFailures(String channelNo) {
    store.clearCredentialFailures(channelNo);
  }

  /** 管理台登录：这个 IP 当前还能不能再试一次（锁定期内为 false）。 */
  public boolean adminLoginAllowed(String ip) {
    return attemptState(ip)[1] <= System.currentTimeMillis();
  }

  /** 锁定期还剩多少秒（向上取整）；没锁时返回 0，给 429 的 Retry-After 用。 */
  public long adminLoginRetryAfterSeconds(String ip) {
    long remaining = attemptState(ip)[1] - System.currentTimeMillis();
    return remaining <= 0 ? 0L : (remaining + 999L) / 1000L;
  }

  /**
   * 记一次管理台口令错误：阈值与窗口同频道锁定共用，达到阈值才锁，返回是否已锁。
   * 计数只增不减——错误尝试绝不能把计数器清掉，否则"错一次、对一次、再错一次"就永远不会锁，
   * 而攻击者手上正有一整把候选口令。
   */
  public boolean registerAdminLoginFailure(String ip) {
    long[] state = attemptState(ip);
    state[0]++;
    boolean locked = state[0] >= props.getIngest().getCredentialFailLock();
    if (locked) {
      state[1] = System.currentTimeMillis() + props.getIngest().getLockWindow().toMillis();
      adminLoginLocks.increment();
    }
    // 重写一次条目：Caffeine 只在写入时重算过期时间，原地改数组不会让剩余锁期生效
    adminLoginAttempts.put(adminLoginKey(ip), state);
    return locked;
  }

  /** 登录成功就把该 IP 的失败计数清掉，避免历史失败把下一个合法登录也一起锁在外面。 */
  public void clearAdminLoginFailures(String ip) {
    adminLoginAttempts.invalidate(adminLoginKey(ip));
  }

  private long[] attemptState(String ip) {
    return adminLoginAttempts.get(adminLoginKey(ip), key -> new long[2]);
  }

  private long adminLoginTtlNanos(long[] state) {
    long lockedRemaining = state[1] - System.currentTimeMillis();
    long millis = lockedRemaining > 0 ? lockedRemaining : props.getIngest().getLockWindow().toMillis();
    // Caffeine 不接受 0/负数的过期时长，最短留 1ns 让它当场回收
    return Math.max(1L, TimeUnit.MILLISECONDS.toNanos(millis));
  }

  private static String adminLoginKey(String ip) {
    return ip == null || ip.isBlank() ? "unknown" : ip;
  }

  public boolean nonceReplayed(String nonce) {
    return seenNonces.asMap().putIfAbsent(nonce, Boolean.TRUE) != null;
  }

  private int intValue(Row row, String column, int fallback) {
    Integer v = row == null ? null : row.getInt(column);
    return v == null || v <= 0 ? fallback : v;
  }

  private long longValue(Row row, String column, long fallback) {
    Long v = row == null ? null : row.getLong(column);
    return v == null || v <= 0 ? fallback : v;
  }
}
