package dev.pushbox.core.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * §5.3 长连接帧。一行一帧 JSON；除 hello/kx/auth 外，下行帧都包在 {@link Enc} 里（§4.3）。
 * 客户端解析未知类型时必须忽略而不是崩溃（Json 已配 FAIL_ON_UNKNOWN_PROPERTIES=false）。
 */
public final class Frames {

  public static final String T_HELLO = "hello";
  public static final String T_KX = "kx";
  public static final String T_AUTH = "auth";
  public static final String T_AUTH_OK = "auth_ok";
  public static final String T_AUTH_FAIL = "auth_fail";
  public static final String T_ENC = "enc";
  public static final String T_MESSAGE = "message";
  public static final String T_MEDIA_READY = "media_ready";
  /** §5.3：抓取重试耗尽后仍失败的合并帧，结构与 media_ready 一致，只是 t 不同。 */
  public static final String T_MEDIA_FAILED = "media_failed";
  public static final String T_READ = "read";
  public static final String T_TOMBSTONE = "tombstone";
  public static final String T_PING = "ping";
  public static final String T_PONG = "pong";
  public static final String T_RESUME_OK = "resume_ok";
  public static final String T_RESUME_GAP = "resume_gap";
  /**
   * §5.3 配置变更通知：不带 seq，所以断线期间的这类帧会丢，但客户端每次建连都会重新拉频道面，
   * 它只负责"在线的那一端即刻知道"，不承担可靠性。
   */
  public static final String T_CONFIG = "config";

  public static final String R_SECRET_ROTATED = "secret_rotated";
  public static final String R_CHANNEL_UPDATED = "channel_updated";
  public static final String R_CHANNEL_ARCHIVED = "channel_archived";
  public static final String R_CHANNEL_NO_RESET = "channel_no_reset";
  public static final String R_SENDERS_UPDATED = "senders_updated";
  public static final String R_RECOVERY_CODE_ROTATED = "recovery_code_rotated";
  public static final String R_DEVICE_REVOKED = "device_revoked";

  private Frames() {}

  /** 建连即发（明文）。nonce 是客户端签名的挑战值，server_pub_fp 用于 TOFU 固定。 */
  public record Hello(@JsonProperty("t") String t,
      @JsonProperty("server_time") long serverTime,
      @JsonProperty("heartbeat") int heartbeatSec,
      @JsonProperty("server_pub_fp") String serverPubFp,
      @JsonProperty("nonce") String nonceB64) {
    public static Hello of(long serverTime, int heartbeatSec, String fp, String nonceB64) {
      return new Hello(T_HELLO, serverTime, heartbeatSec, fp, nonceB64);
    }
  }

  /** 客户端 → 服务端：token 不进 URL（避免落进 access log / 反代日志），改由签名挑战证明持钥。 */
  public record Auth(@JsonProperty("t") String t,
      @JsonProperty("device_id") String deviceId,
      @JsonProperty("token") String token,
      @JsonProperty("ts") long ts,
      @JsonProperty("sig") String sigB64) {
    public static Auth of(String deviceId, String token, long ts, String sigB64) {
      return new Auth(T_AUTH, deviceId, token, ts, sigB64);
    }
  }

  /**
   * 客户端只有指纹无法验签，所以 kx 同时带服务端身份公钥：客户端先比对
   * sha256(server_pub) 前 16 位与已固定指纹（首连即 TOFU 固定），再用该公钥验 sig。
   */
  public record Kx(@JsonProperty("t") String t,
      @JsonProperty("server_pub") String serverPubB64,
      @JsonProperty("eph_pub") String ephPubB64,
      @JsonProperty("sig") String sigB64,
      @JsonProperty("epoch") int epoch) {
    public static Kx of(String serverPubB64, String ephPubB64, String sigB64, int epoch) {
      return new Kx(T_KX, serverPubB64, ephPubB64, sigB64, epoch);
    }
  }

  /** 唯一的密文载体：data = base64(AES-256-GCM(K, nonce=n, AAD=n))。 */
  public record Enc(@JsonProperty("t") String t,
      long n,
      String data) {
    public static Enc of(long n, String data) {
      return new Enc(T_ENC, n, data);
    }
  }

  public record MediaSlot(int idx, String status, String key, String mime, Long size, Integer w,
      Integer h, String thumb) {}

  /** D24：到达客户端时 media[] 通常已是终态，收到即可直接带图发通知。 */
  public record Message(@JsonProperty("t") String t,
      long seq,
      @JsonProperty("channel_no") String channelNo,
      String id,
      @JsonProperty("sender_id") String senderId,
      String type,
      String title,
      String summary,
      String content,
      String level,
      String url,
      List<MediaSlot> media,
      @JsonProperty("sender_name") String senderName,
      @JsonProperty("sender_avatar") String senderAvatar,
      @JsonProperty("created_at") long createdAt,
      @JsonProperty("notify") boolean notifyClient,
      boolean truncated) {
    public static Message of(long seq, String channelNo, String id, String senderId, String type,
        String title, String summary, String content, String level, String url,
        List<MediaSlot> media, String senderName, String senderAvatar, long createdAt) {
      return new Message(T_MESSAGE, seq, channelNo, id, senderId, type, title, summary, content,
          level, url, media, senderName, senderAvatar, createdAt, true, false);
    }
  }

  /** 兜底帧：不带新 seq，客户端按 id 原地补图，不重复通知、不推进已读游标。 */
  public record MediaReady(@JsonProperty("t") String t, String id, long seq,
      List<MediaSlot> media, String status) {
    public static MediaReady of(String id, long seq, List<MediaSlot> media, String status) {
      return new MediaReady(T_MEDIA_READY, id, seq, media, status);
    }
    /** §5.3：重试耗尽后仍失败的合并帧。 */
    public static MediaReady failed(String id, long seq, List<MediaSlot> media, String status) {
      return new MediaReady(T_MEDIA_FAILED, id, seq, media, status);
    }
  }

  public record Read(@JsonProperty("t") String t,
      @JsonProperty("channel_no") String channelNo,
      @JsonProperty("sender_id") String senderId,
      @JsonProperty("last_read_seq") long lastReadSeq,
      @JsonProperty("device_id") String deviceId) {
    public static Read of(String channelNo, String senderId, long lastReadSeq, String deviceId) {
      return new Read(T_READ, channelNo, senderId, lastReadSeq, deviceId);
    }
  }

  /**
   * §5.3 删除同步：{@code id} 定位单条，{@code scope} 定位整个会话（二者互斥，客户端先看 id）。
   * scope 是对象而不是拼接字符串，因为 sender_id 允许含任意字符，分隔符方案迟早会撞上一次。
   */
  public record Tombstone(@JsonProperty("t") String t, String id, Scope scope,
      @JsonProperty("deleted_at") long deletedAt) {
    public record Scope(@JsonProperty("channel_no") String channelNo,
                        @JsonProperty("sender_id") String senderId) {}

    public static Tombstone of(String id, long deletedAt) {
      return new Tombstone(T_TOMBSTONE, id, null, deletedAt);
    }

    public static Tombstone ofScope(String channelNo, String senderId, long deletedAt) {
      return new Tombstone(T_TOMBSTONE, null, new Scope(channelNo, senderId), deletedAt);
    }
  }

  public record Simple(@JsonProperty("t") String t) {
    public static Simple pong() {
      return new Simple(T_PONG);
    }

    public static Simple ping() {
      return new Simple(T_PING);
    }

    public static Simple resumeOk() {
      return new Simple(T_RESUME_OK);
    }
  }

  public record ResumeGap(@JsonProperty("t") String t,
      @JsonProperty("missing_from") long missingFrom,
      @JsonProperty("latest_seq") long latestSeq) {
    public static ResumeGap of(long missingFrom, long latestSeq) {
      return new ResumeGap(T_RESUME_GAP, missingFrom, latestSeq);
    }
  }

  /**
   * 配置/凭据变更：{@code reason} 用上面的 R_* 常量，{@code channel_no} 只对频道类变更有意义，
   * 设备类变更带 {@code device_id}，两者都可能是 null，客户端按"整面刷新"处理最省事。
   */
  public record Config(@JsonProperty("t") String t, String reason,
      @JsonProperty("channel_no") String channelNo,
      @JsonProperty("device_id") String deviceId) {
    public static Config ofChannel(String reason, String channelNo) {
      return new Config(T_CONFIG, reason, channelNo, null);
    }

    public static Config ofDevice(String reason, String deviceId) {
      return new Config(T_CONFIG, reason, null, deviceId);
    }

    public static Config ofGroup(String reason) {
      return new Config(T_CONFIG, reason, null, null);
    }
  }

  public record AuthFail(@JsonProperty("t") String t, String error) {
    public static AuthFail of(String error) {
      return new AuthFail(T_AUTH_FAIL, error);
    }
  }
}
