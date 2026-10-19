package dev.pushbox.core.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** §5.2 设备注册 / 恢复码加入。公钥均为 base64 URL 无填充的 32 字节原始密钥。 */
public final class DeviceApi {

  private DeviceApi() {}

  /** 首启：不带 group_secret 时服务端生成恢复码并只存其哈希；带上则表示用现有恢复码加入。 */
  public record RegisterRequest(
      @JsonProperty("enc_pubkey") String encPubkey,
      @JsonProperty("sign_pubkey") String signPubkey,
      String name,
      String platform,
      @JsonProperty("group_secret") String groupSecret) {}

  public record RegisterResponse(
      @JsonProperty("device_id") String deviceId,
      @JsonProperty("group_id") String groupId,
      String token,
      @JsonProperty("recovery_code") String recoveryCode,
      @JsonProperty("server_pub_fp") String serverPubFp) {}

  /** 换机 / PC 加入：只凭恢复码，同样受 5 台上限（D26）。 */
  public record JoinRequest(
      @JsonProperty("group_secret") String groupSecret,
      @JsonProperty("enc_pubkey") String encPubkey,
      @JsonProperty("sign_pubkey") String signPubkey,
      String name,
      String platform) {}

  public record DeviceView(
      @JsonProperty("device_id") String deviceId,
      String name,
      String platform,
      @JsonProperty("created_at") Long createdAt,
      @JsonProperty("last_seen_at") Long lastSeenAt,
      @JsonProperty("revoked_at") Long revokedAt,
      @JsonProperty("current") boolean current) {}
}
