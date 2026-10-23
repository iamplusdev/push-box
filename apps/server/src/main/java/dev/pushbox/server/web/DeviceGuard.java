package dev.pushbox.server.web;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.crypto.B64;
import dev.pushbox.core.crypto.Ed25519;
import dev.pushbox.core.crypto.FrameChannel;
import dev.pushbox.core.crypto.Hashes;
import dev.pushbox.core.crypto.SignatureTargets;
import dev.pushbox.core.crypto.X25519;
import dev.pushbox.core.protocol.Json;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.service.AbuseControlService;
import dev.pushbox.server.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * §5.2 客户端接口的鉴权与响应加密：Bearer token + Ed25519 请求签名（防重放靠 nonce 缓存），
 * 响应体用 X25519 sealed box 加密后再回（§4.3：REST 历史/搜索结果同样过 K）。
 * 
 * 同时支持 Web 管理台的 JWT token（仅用于 /api/v1/admin/* 路径）。
 */
@Component
public class DeviceGuard {

  private static final long SIGN_WINDOW_MILLIS = 300_000L;

  private final DeviceService devices;
  private final AbuseControlService abuse;
  private final AdminAuthController adminAuth;
  private final PbProperties props;

  public DeviceGuard(DeviceService devices, AbuseControlService abuse,
                     AdminAuthController adminAuth, PbProperties props) {
    this.devices = devices;
    this.abuse = abuse;
    this.adminAuth = adminAuth;
    this.props = props;
  }

  public record Authenticated(Row device, String groupId, String deviceId) {}

  /** register/join 是开放接口，只按来源 IP 限流。 */
  public boolean ipAllowed(HttpServletRequest request) {
    return abuse.ipAllowed(Requests.clientIp(request));
  }

  public Authenticated require(HttpServletRequest request, byte[] requestBody) {
    String header = request.getHeader("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      throw ApiException.unauthorized("缺少设备 token");
    }
    String token = header.substring(7).trim();

    // Web 管理台 JWT token（仅用于 /api/v1/admin/*）
    if (request.getRequestURI().startsWith("/api/v1/admin/")) {
      if (!adminAuth.validateToken(token)) {
        throw ApiException.unauthorized("管理 token 无效或已过期");
      }
      // 管理 token 没有对应的 device row，返回一个虚拟的 admin 设备
      return new Authenticated(null, "admin", "admin");
    }

    // 普通设备 token + Ed25519 签名
    String deviceId = request.getHeader("X-PB-Device");
    String tsHeader = request.getHeader("X-PB-Ts");
    String nonceHeader = request.getHeader("X-PB-Nonce");
    String sigHeader = request.getHeader("X-PB-Sig");
    if (deviceId == null || tsHeader == null || nonceHeader == null || sigHeader == null) {
      throw ApiException.unauthorized("缺少签名头 X-PB-Device/Ts/Nonce/Sig");
    }
    Row device = devices.byToken(token);
    if (device == null || device.getLong("revoked_at") != null
        || !deviceId.equals(device.getString("device_id"))) {
      throw ApiException.unauthorized("设备无效或已吊销");
    }
    if (!abuse.deviceAllowed(deviceId)) {
      throw ApiException.rateLimited(60);
    }
    byte[] nonce = B64.tryDec(nonceHeader);
    if (nonce == null || nonce.length < 16) {
      throw ApiException.unauthorized("nonce 长度不足");
    }
    if (abuse.nonceReplayed(deviceId + ":" + nonceHeader)) {
      throw new ApiException(409, "replay", null);
    }
    long ts;
    try {
      ts = Long.parseLong(tsHeader);
    } catch (NumberFormatException e) {
      throw ApiException.unauthorized("ts 必须是 epoch 毫秒");
    }
    if (Math.abs(System.currentTimeMillis() - ts) > SIGN_WINDOW_MILLIS) {
      throw ApiException.unauthorized("clock_skew");
    }
    byte[] sig = B64.tryDec(sigHeader);
    byte[] signPub = B64.tryDec(device.getString("device_pubkey"));
    byte[] target = SignatureTargets.restAuth(deviceId, request.getMethod(),
        request.getRequestURI(), request.getQueryString(), requestBody, ts, nonce);
    if (sig == null || signPub == null || !Ed25519.verify(signPub, target, sig)) {
      throw ApiException.unauthorized("bad_signature");
    }
    devices.touch(deviceId);
    return new Authenticated(device, device.getString("group_id"), deviceId);
  }

  /** {v, eph_pub, salt, data} —— 客户端用自己的 enc 私钥还原明文。 */
  public Map<String, Object> seal(Row device, Object payload) {
    // Admin 请求（device == null）直接返回明文 JSON，不走加密
    if (device == null) {
      return (Map<String, Object>) payload;
    }
    X25519.KeyPair eph = X25519.generate();
    byte[] shared = X25519.agree(eph.privateKey(), B64.dec(device.getString("enc_pubkey")));
    byte[] salt = Hashes.sha256(eph.publicKey());
    FrameChannel channel = new FrameChannel(FrameChannel.sessionKey(shared, salt),
        System.currentTimeMillis());
    byte[] plain = Json.writeBytes(payload);
    FrameChannel.Sealed sealed = channel.seal(plain);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("v", 1);
    out.put("eph_pub", B64.enc(eph.publicKey()));
    out.put("salt", B64.enc(salt));
    out.put("data", sealed.data());
    out.put("n", sealed.frameIndex());
    // 顺手校验一次自封自解，避免"能加密不能解密"的沉默缺陷（开销只有一次 GCM）
    if (!MessageDigest.isEqual(plain, channel.open(sealed.frameIndex(), sealed.data()))) {
      throw new IllegalStateException("sealed box 自校验失败");
    }
    return out;
  }
}
