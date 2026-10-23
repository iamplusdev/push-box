package dev.pushbox.server.web;

import dev.pushbox.core.protocol.Json;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.service.AbuseControlService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web 管理台登录：用 {@code pb.admin.secret}（口令）换 JWT token，token 由另一把
 * {@code pb.admin.jwt-secret} 签 HS256。口令没配置时所有请求返回 403（禁用管理台）。
 *
 * <p>口令错到阈值就按来源 IP 锁定（§4.4 同一套阈值与窗口，见 {@link AbuseControlService}）：
 * 管理台经 Caddy 公网可达，而这个接口原本对失败次数没有任何上限，等于把口令直接挂在字典攻击面前。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminAuthController {

  /** 签名钥的最短长度：HS256 的钥短于 256 位时，签名本身比口令更容易被硬算。 */
  static final int MIN_JWT_SECRET_BYTES = 32;

  private final PbProperties props;
  private final AbuseControlService abuse;
  /** 只用于签/验 JWT 的 HMAC 钥；管理台禁用时为空。绝不与登录口令共用，也绝不写进日志。 */
  private final byte[] signingKey;

  public AdminAuthController(PbProperties props, AbuseControlService abuse) {
    this.props = props;
    this.abuse = abuse;
    this.signingKey = validateSigningKey(props.getAdmin().getSecret(), props.getAdmin().getJwtSecret());
  }

  @PostMapping("/login")
  public Map<String, Object> login(@RequestBody LoginRequest request, HttpServletRequest http) {
    String secret = props.getAdmin().getSecret();
    if (secret == null || secret.isBlank()) {
      throw ApiException.forbidden("管理台未启用");
    }
    // 按来源 IP 计失败次数：X-Forwarded-For 由 Caddy 覆盖写入，与 register/join 的限流同一个口径
    String clientIp = Requests.clientIp(http);
    if (!abuse.adminLoginAllowed(clientIp)) {
      throw ApiException.loginLocked(abuse.adminLoginRetryAfterSeconds(clientIp));
    }
    if (!secretsEqual(secret, request.secret())) {
      abuse.registerAdminLoginFailure(clientIp);
      throw ApiException.unauthorized("密钥错误");
    }
    abuse.clearAdminLoginFailures(clientIp);
    // 生成一个简易 JWT（HS256，有效期 24h）
    String token = generateJwt();
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("ok", true);
    response.put("token", token);
    response.put("expires_in", 86400);
    return response;
  }

  /** 验证 token：先验 HMAC 签名，再校验 {@code exp} 未过期。 */
  public boolean validateToken(String token) {
    if (signingKey.length == 0) {
      return false;
    }
    try {
      String[] parts = token.split("\\.");
      if (parts.length != 3) {
        return false;
      }
      String headerPayload = parts[0] + "." + parts[1];
      byte[] expectedSig = hmacSha256(signingKey, headerPayload.getBytes(StandardCharsets.UTF_8));
      byte[] actualSig = Base64.getUrlDecoder().decode(parts[2]);
      if (!MessageDigest.isEqual(expectedSig, actualSig)) {
        return false;
      }
      // 验签通过后才读 exp：过期即失效，否则管理员 JWT 等于永久有效。
      String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
      // 使用 JSON 解析而非正则，防止 crafted payload 绕过 exp 校验
      try {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> payloadMap = Json.readBytes(payload.getBytes(StandardCharsets.UTF_8), java.util.Map.class);
        if (!payloadMap.containsKey("exp")) {
          return false;
        }
        Object expValue = payloadMap.get("exp");
        long exp;
        if (expValue instanceof Number) {
          exp = ((Number) expValue).longValue();
        } else if (expValue instanceof String) {
          exp = Long.parseLong((String) expValue);
        } else {
          return false;
        }
        return exp > System.currentTimeMillis() / 1000;
      } catch (Exception e) {
        return false;
      }
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * 管理台启用（配了口令）却没有可用签名钥时当场拒绝启动，而不是悄悄拿口令当签名钥：
   * 那种共用会让"改口令"顺手把全部已发 token 作废，"换签名钥"又必须动口令，两个都换不动。
   * 报错只报键名与字节数下限，绝不带出值。
   */
  static byte[] validateSigningKey(String loginSecret, String jwtSecret) {
    boolean consoleEnabled = loginSecret != null && !loginSecret.isBlank();
    if (!consoleEnabled) {
      // 管理台禁用：没有登录口，也就没有需要签名的 token
      return new byte[0];
    }
    if (jwtSecret == null || jwtSecret.isBlank()) {
      throw new IllegalStateException("""
          管理台已启用（配了登录口令）但没有 JWT 签名钥。
              正常情况下这一项由服务端自动生成并回写 data/config/pushbox.json 的 admin.jwtKey，
              走到这条说明那个文件被手工改坏了：删掉 admin.jwtKey 这一行重启即可重新生成。
              签名钥与登录口令必须是两个不同的值：同一个值既当口令又当 HMAC 钥时，谁都轮换不了。""");
    }
    byte[] key = jwtSecret.getBytes(StandardCharsets.UTF_8);
    if (key.length < MIN_JWT_SECRET_BYTES) {
      throw new IllegalStateException("""
          管理台的 JWT 签名钥（admin.jwtKey）太短：当前 %d 字节，至少需要 %d 字节。
              HS256 的签名钥短于 256 位时，离线把签名算出来比猜口令还容易。
              这一项本该由服务端自动生成；手填过就删掉它，重启后会重新生成一把。"""
          .formatted(key.length, MIN_JWT_SECRET_BYTES));
    }
    return key;
  }

  /**
   * 定长比较：{@code String.equals} 一遇到不同的字节就返回，返回耗时泄露"前缀对了几位"，
   * 一次线上爆破就是一条条前缀爬上来。{@link MessageDigest#isEqual} 是常量时间实现。
   */
  private static boolean secretsEqual(String expected, String provided) {
    byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
    byte[] providedBytes = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expectedBytes, providedBytes);
  }

  private String generateJwt() {
    try {
      long now = System.currentTimeMillis() / 1000;
      long exp = now + 86400; // 24h
      String header = Base64.getUrlEncoder().withoutPadding()
          .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
      String payload = Base64.getUrlEncoder().withoutPadding()
          .encodeToString(("{" +
              "\"sub\":\"admin\"," +
              "\"iat\":" + now + "," +
              "\"exp\":" + exp + "}"
          ).getBytes(StandardCharsets.UTF_8));
      String headerPayload = header + "." + payload;
      byte[] sig = hmacSha256(signingKey, headerPayload.getBytes(StandardCharsets.UTF_8));
      String sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
      return headerPayload + "." + sigB64;
    } catch (Exception e) {
      throw new IllegalStateException("JWT 生成失败", e);
    }
  }

  private byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data);
  }

  public record LoginRequest(String secret) {}
}
