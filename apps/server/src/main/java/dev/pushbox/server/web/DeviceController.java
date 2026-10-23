package dev.pushbox.server.web;

import dev.pushbox.core.api.DeviceApi;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.server.push.PushRegistry;
import dev.pushbox.server.service.ConfigNotifier;
import dev.pushbox.server.service.DeviceService;
import dev.pushbox.server.service.ServerKeys;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** §5.2 设备注册与恢复码加入；register/join 是开放接口（受 IP 限流），其余要设备签名。 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

  private final DeviceService devices;
  private final DeviceGuard guard;
  private final ServerKeys keys;
  private final PushRegistry registry;
  private final ConfigNotifier config;

  public DeviceController(DeviceService devices, DeviceGuard guard, ServerKeys keys,
      PushRegistry registry, ConfigNotifier config) {
    this.devices = devices;
    this.guard = guard;
    this.keys = keys;
    this.registry = registry;
    this.config = config;
  }

  /** 通用请求验证：IP 限流 + 大小限制（8KB），防止 OOM DoS。 */
  private <T> T validateAndParse(byte[] body, Class<T> type, HttpServletRequest http) {
    if (!guard.ipAllowed(http)) {
      throw ApiException.rateLimited(60);
    }
    if (body == null || body.length > 8192) {
      throw ApiException.invalidParam("请求体过大");
    }
    return RequestBodyJson.read(body, type);
  }

  @PostMapping("/register")
  public Map<String, Object> register(@RequestBody byte[] body, HttpServletRequest http) {
    DeviceApi.RegisterRequest request = validateAndParse(body, DeviceApi.RegisterRequest.class, http);
    return sealed(devices.register(request));
  }

  @PostMapping("/join")
  public Map<String, Object> join(@RequestBody byte[] body, HttpServletRequest http) {
    DeviceApi.JoinRequest request = validateAndParse(body, DeviceApi.JoinRequest.class, http);
    return sealed(devices.join(request));
  }

  @GetMapping
  public Map<String, Object> list(HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    return guard.seal(auth.device(), devices.list(auth.groupId(), auth.deviceId()));
  }

  @DeleteMapping("/{deviceId}")
  public Map<String, Object> revoke(@PathVariable String deviceId, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    devices.revoke(auth.groupId(), deviceId);
    // 吊销即时生效：token 已作废，在线连接直接踢掉
    PushRegistry.Session session = registry.find(deviceId);
    if (session != null) {
      registry.close(session, "device_revoked");
    }
    // 其余在线端要即刻知道名单少了谁，不然"设备列表"要等下次进设置页才对得上
    config.device(auth.groupId(), Frames.R_DEVICE_REVOKED, deviceId);
    return guard.seal(auth.device(), Map.of("ok", true, "device_id", deviceId));
  }

  /** §14 恢复码轮换：旧码立即失效，新码仅此次返回明文。 */
  @PostMapping("/rotate-recovery-code")
  public Map<String, Object> rotateRecoveryCode(HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    String newCode = devices.rotateRecoveryCode(auth.groupId());
    // 旧码作废这件事，同组其他端只有被通知才不会继续展示一个已经失效的恢复码
    config.group(auth.groupId(), Frames.R_RECOVERY_CODE_ROTATED);
    return guard.seal(auth.device(), Map.of("ok", true, "recovery_code", newCode));
  }

  /** token 与恢复码等价于账号口令，所以连 register/join 的响应也走 §4.3 密封盒。 */
  private Map<String, Object> sealed(DeviceService.Registration r) {
    DeviceApi.RegisterResponse response = new DeviceApi.RegisterResponse(r.deviceId(), r.groupId(),
        r.token(), r.recoveryCode(), keys.fingerprint());
    return guard.seal(devices.byId(r.deviceId()), response);
  }
}
