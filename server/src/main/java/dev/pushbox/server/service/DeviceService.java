package dev.pushbox.server.service;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.api.DeviceApi;
import dev.pushbox.core.crypto.B64;
import dev.pushbox.core.crypto.Hashes;
import dev.pushbox.core.id.Ulid;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.web.ApiException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * §4.2 设备身份：无口令，公钥即身份；恢复码（group_secret）是唯一的"账号"，服务端只存其 sha256。
 * 同一 group 的活动设备受 D26 上限约束。
 */
@Service
public class DeviceService {

  private final PbStore store;
  private final dev.pushbox.server.config.PbProperties props;
  private final SecureRandom random = new SecureRandom();

  public DeviceService(PbStore store, dev.pushbox.server.config.PbProperties props) {
    this.store = store;
    this.props = props;
  }

  public record Registration(String deviceId, String groupId, String token, String recoveryCode) {}

  /** 首启不带恢复码 ⇒ 服务端生成并只在本次返回；带恢复码 ⇒ 等价于 join。 */
  public synchronized Registration register(DeviceApi.RegisterRequest req) {
    validateKeys(req.encPubkey(), req.signPubkey());
    String secret = req.groupSecret();
    if (secret == null || secret.isBlank()) {
      secret = newRecoveryCode();
    }
    return enroll(secret, req.encPubkey(), req.signPubkey(), req.name(), req.platform(),
        req.groupSecret() == null || req.groupSecret().isBlank());
  }

  public synchronized Registration join(DeviceApi.JoinRequest req) {
    validateKeys(req.encPubkey(), req.signPubkey());
    if (req.groupSecret() == null || req.groupSecret().isBlank()) {
      throw ApiException.invalidParam("group_secret 必填");
    }
    return enroll(req.groupSecret().trim(), req.encPubkey(), req.signPubkey(), req.name(),
        req.platform(), false);
  }

  private Registration enroll(String recoveryCode, String encPub, String signPub, String name,
      String platform, boolean isNewGroup) {
    String secretHash = Hashes.sha256Hex(recoveryCode.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Row existing = store.findGroupBySecretHash(secretHash);
    String groupId;
    long now = System.currentTimeMillis();
    if (existing == null) {
      if (!isNewGroup) {
        // join 用一个服务端没见过的恢复码：与"密钥错"同等对待，不暴露该码是否有效过
        throw ApiException.invalidCredential();
      }
      groupId = "grp_" + secretHash.substring(0, 16);
      store.insertGroup(groupId, secretHash, now);
    } else {
      groupId = existing.getString("group_id");
    }

    if (store.countActiveDevices(groupId) >= props.getDevices().getMaxPerGroup()) {
      throw ApiException.deviceLimitReached();
    }

    String deviceId = "dev_" + Ulid.next();
    String token = randomToken();
    store.insertDevice(deviceId, groupId, signPub, encPub,
        name == null ? null : trim(name, 64), platform, token, now);
    return new Registration(deviceId, groupId, token, isNewGroup ? recoveryCode : null);
  }

  public Row byToken(String token) {
    return store.findDeviceByToken(token);
  }

  public Row byId(String deviceId) {
    return store.findDevice(deviceId);
  }

  public List<DeviceApi.DeviceView> list(String groupId, String currentDeviceId) {
    List<DeviceApi.DeviceView> out = new ArrayList<>();
    for (Row row : store.listDevices(groupId)) {
      out.add(new DeviceApi.DeviceView(row.getString("device_id"), row.getString("name"),
          row.getString("platform"), row.getLong("created_at"), row.getLong("last_seen_at"),
          row.getLong("revoked_at"), currentDeviceId.equals(row.getString("device_id"))));
    }
    return out;
  }

  public void revoke(String groupId, String deviceId) {
    if (store.revokeDevice(deviceId, groupId, System.currentTimeMillis()) == 0) {
      throw ApiException.notFound("device");
    }
  }

  public void touch(String deviceId) {
    store.touchDevice(deviceId, System.currentTimeMillis());
  }

  /** §14 恢复码轮换：生成新码并更新 group_secret_hash，旧码立即失效。 */
  public synchronized String rotateRecoveryCode(String groupId) {
    Row group = store.findGroup(groupId);
    if (group == null) {
      throw ApiException.invalidParam("group 不存在");
    }
    String newCode = newRecoveryCode();
    String newHash = Hashes.sha256Hex(newCode.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    store.updateGroupSecretHash(groupId, newHash);
    return newCode;
  }

  private void validateKeys(String encPub, String signPub) {
    byte[] enc = B64.tryDec(encPub);
    byte[] sign = B64.tryDec(signPub);
    if (enc == null || enc.length != 32 || sign == null || sign.length != 32) {
      throw ApiException.invalidParam("enc_pubkey/sign_pubkey 必须是 32 字节密钥的 base64url");
    }
  }

  private String newRecoveryCode() {
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    return B64.enc(bytes);
  }

  private String randomToken() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return B64.enc(bytes);
  }

  private static String trim(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  /** Web 管理台：列出所有未吊销设备（跨 Space）。 */
  public List<Map<String, Object>> listAll() {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Row row : store.listAllDevices()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("device_id", row.getString("device_id"));
      item.put("name", row.getString("name"));
      item.put("platform", row.getString("platform"));
      item.put("group_id", row.getString("group_id"));
      item.put("created_at", row.getLong("created_at"));
      item.put("last_seen_at", row.getLong("last_seen_at"));
      item.put("revoked_at", row.getLong("revoked_at"));
      out.add(item);
    }
    return out;
  }

  /** Web 管理台：按频道号列出已接入该频道的设备。 */
  public List<Map<String, Object>> listByChannel(String channelNo) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Row row : store.listDevicesByChannel(channelNo)) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("device_id", row.getString("device_id"));
      item.put("name", row.getString("name"));
      item.put("platform", row.getString("platform"));
      item.put("group_id", row.getString("group_id"));
      item.put("created_at", row.getLong("created_at"));
      item.put("last_seen_at", row.getLong("last_seen_at"));
      out.add(item);
    }
    return out;
  }
}
