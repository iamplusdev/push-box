package dev.pushbox.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.service.AbuseControlService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * §5.2 管理台登录：失败锁定 + 定长比较 + 口令/签名钥分家。
 *
 * <p>这些用例不起 Spring 上下文、不连库也不碰桶：锁定状态是内存计数（见
 * {@link AbuseControlService#registerAdminLoginFailure}），store 传 null 就够，
 * 于是它们真的能在默认的 {@code mvn test} 里跑，而不是被 PB_S3_ACCESS_KEY 那道门挡掉。
 */
class AdminAuthControllerTest {

  private static final String LOGIN_SECRET = "console-passphrase";
  private static final String JWT_SECRET = "jwt-signing-key-32-bytes-or-more!!";
  private static final String CLIENT_IP = "203.0.113.9";

  @Test
  @DisplayName("① 错满阈值次数才锁：前 5 次 401「密钥错误」，第 6 次起 429 并带上剩余秒数")
  void failuresLockTheIpAfterTheThreshold() {
    AdminAuthController controller = controller(5, Duration.ofMinutes(5));

    for (int attempt = 1; attempt <= 5; attempt++) {
      final AdminAuthController.LoginRequest bad = request("wrong-" + attempt);
      assertThatThrownBy(() -> controller.login(bad, client()))
          .isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(401);
            // 话术必须与改前一致：控制台的界面按这四个字显示错误
            assertThat(e.toError().hint()).isEqualTo("密钥错误");
          });
    }

    assertThatThrownBy(() -> controller.login(request("wrong-6"), client()))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          // 锁定回 429 而不是 401：控制台要能把"被锁了"和"口令错了"分开显示
          assertThat(e.status()).isEqualTo(429);
          assertThat(e.toError().error()).isEqualTo("login_locked");
          assertThat(e.retryAfterSeconds()).isBetween(1L, 300L);
        });
    // 锁定期内连正确口令都进不来，否则锁定等于没有
    assertThatThrownBy(() -> controller.login(request(LOGIN_SECRET), client()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(429));
  }

  @Test
  @DisplayName("② 错误的口令不重置计数器：每次错只加一，五个不同的错口令照样把 IP 锁上")
  void wrongSecretsNeverResetTheCounter() {
    AdminAuthController controller = controller(5, Duration.ofMinutes(5));

    for (int attempt = 1; attempt <= 5; attempt++) {
      // 每次都换一个不同的错口令：若"错了"会顺手清零，攻击者就能用一整个字典一直刷下去
      final AdminAuthController.LoginRequest bad = request("candidate-" + attempt);
      assertThatThrownBy(() -> controller.login(bad, client()))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(401));
    }
    assertThatThrownBy(() -> controller.login(request("candidate-6"), client()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(429));
    // 别的 IP 不受牵连：锁定按来源地址，不是全局开关
    assertThat(controller.login(request(LOGIN_SECRET), clientFrom("198.51.100.7")))
        .containsEntry("ok", true);
  }

  @Test
  @DisplayName("③ 登录成功清零该 IP 的失败计数；窗口过后同一个 IP 又能试")
  void successClearsTheCounterAndLockExpires() {
    AdminAuthController controller = controller(5, Duration.ofMillis(400));

    for (int attempt = 0; attempt < 4; attempt++) {
      assertThatThrownBy(() -> controller.login(request("nope"), client()))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(401));
    }
    assertThat(controller.login(request(LOGIN_SECRET), client())).containsEntry("ok", true);

    // 计数已清：又得错满 5 次才锁，中途每一次都是 401
    for (int attempt = 0; attempt < 5; attempt++) {
      assertThatThrownBy(() -> controller.login(request("nope"), client()))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(401));
    }
    assertThatThrownBy(() -> controller.login(request("nope"), client()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(429));

    sleepPast(Duration.ofMillis(400));
    assertThat(controller.login(request(LOGIN_SECRET), client())).containsEntry("ok", true);
  }

  @Test
  @DisplayName("④ 定长比较的行为面：错口令一律同一句 401，正确口令换到可用的三段 token")
  void constantTimeCompareIsBehaviourallyIndistinguishable() {
    AdminAuthController controller = controller(5, Duration.ofMinutes(5));
    String[] bad = {"console", "console-passphras", "console-passphrase-x", "", "不相关的一个口令",
        "CONSOLE-PASSPHRASE", null};

    for (int index = 0; index < bad.length; index++) {
      final String provided = bad[index];
      // 每个错口令换一个来源 IP：锁定阈值是 5 次，别让它把后面的用例提前变成 429
      // 前缀与正确口令完全相同、只差结尾：逐字节短路的 equals 会在这里露出耗时差，
      // 而对调用方必须是与"完全不相干"完全一样的响应（大小写不同也一样是 401）
      final MockHttpServletRequest http = clientFrom("192.0.2." + (index + 1));
      assertThatThrownBy(() -> controller.login(request(provided), http))
          .isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(401);
            assertThat(e.toError().error()).isEqualTo("unauthorized");
            assertThat(e.toError().hint()).isEqualTo("密钥错误");
          });
    }

    Map<String, Object> response = controller.login(request(LOGIN_SECRET), client());
    String token = (String) response.get("token");
    assertThat(response).containsEntry("ok", true).containsEntry("expires_in", 86400);
    assertThat(token.split("\\.")).hasSize(3);
    assertThat(controller.validateToken(token)).isTrue();
    assertThat(controller.validateToken(token + "x")).isFalse();
    assertThat(controller.validateToken("不是 JWT")).isFalse();
  }

  @Test
  @DisplayName("⑤ 口令与签名钥分家：拿口令当钥签出来的 token 验不过，换钥即作废全部 token")
  void signingKeyIsSeparateFromTheLoginSecret() {
    AdminAuthController controller = controller(5, Duration.ofMinutes(5));
    String token = (String) controller.login(request(LOGIN_SECRET), client()).get("token");

    // 同一个口令、另一把签名钥：登录照样过，但旧 token 从此验不过——这正是分家要换来的轮换能力
    AdminAuthController rotated = controllerWith(LOGIN_SECRET, "another-signing-key-32-bytes-long!!");
    assertThat(rotated.login(request(LOGIN_SECRET), clientFrom("198.51.100.8")))
        .containsEntry("ok", true);
    assertThat(rotated.validateToken(token)).isFalse();
  }

  @Test
  @DisplayName("⑥ 没配口令就是禁用管理台：403 一句话，不区分「口令错」")
  void disabledConsoleAnswersForbidden() {
    AdminAuthController disabled = controllerWith("", JWT_SECRET);

    assertThatThrownBy(() -> disabled.login(request(""), client()))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(403);
          assertThat(e.toError().hint()).isEqualTo("管理台未启用");
        });
    assertThat(disabled.validateToken("a.b.c")).isFalse();
  }

  @Test
  @DisplayName("⑦ 管理台启用却没有可用签名钥 ⇒ 启动就失败，并点名该配什么")
  void blankOrShortSigningKeyFailsStartup() {
    for (String unusable : new String[] {null, "", "   ", "短于此的口令钥"}) {
      assertThatThrownBy(() -> controllerWith(LOGIN_SECRET, unusable))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("pb.admin.jwt-secret")
          .hasMessageContaining("PB_ADMIN_JWT_SECRET");
    }
    // 31 字节差一个字节都不行：HS256 的钥短于 256 位时算签名比猜口令还容易
    String thirtyOne = "k".repeat(AdminAuthController.MIN_JWT_SECRET_BYTES - 1);
    String thirtyTwo = "k".repeat(AdminAuthController.MIN_JWT_SECRET_BYTES);
    assertThat(thirtyOne.getBytes(StandardCharsets.UTF_8)).hasSize(31);
    assertThatThrownBy(() -> controllerWith(LOGIN_SECRET, thirtyOne))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("太短")
        .hasMessageContaining("32");
    assertThatCode(() -> controllerWith(LOGIN_SECRET, thirtyTwo)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("⑧ 管理台禁用时签名钥留空是合法配置；报错只报键名，绝不带出值")
  void disabledConsoleNeedsNoSigningKeyAndErrorsLeakNothing() {
    String sentinel = "super-secret-jwt-key-material-32b-ok";
    assertThatCode(() -> controllerWith("", null)).doesNotThrowAnyException();
    assertThatThrownBy(() -> controllerWith(LOGIN_SECRET, sentinel.substring(0, 8)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(sentinel);
  }

  private AdminAuthController controller(int failLock, Duration lockWindow) {
    PbProperties props = new PbProperties();
    props.getAdmin().setSecret(LOGIN_SECRET);
    props.getAdmin().setJwtSecret(JWT_SECRET);
    props.getIngest().setCredentialFailLock(failLock);
    props.getIngest().setLockWindow(lockWindow);
    return new AdminAuthController(props, new AbuseControlService(null, props,
        new SimpleMeterRegistry()));
  }

  private AdminAuthController controllerWith(String loginSecret, String jwtSecret) {
    PbProperties props = new PbProperties();
    props.getAdmin().setSecret(loginSecret);
    props.getAdmin().setJwtSecret(jwtSecret);
    return new AdminAuthController(props, new AbuseControlService(null, props,
        new SimpleMeterRegistry()));
  }

  private static AdminAuthController.LoginRequest request(String provided) {
    return new AdminAuthController.LoginRequest(provided);
  }

  /** Caddy 覆盖写入 X-Forwarded-For 之后的真实来源；限流按它计数（见 Requests.clientIp）。 */
  private static MockHttpServletRequest client() {
    return clientFrom(CLIENT_IP);
  }

  private static MockHttpServletRequest clientFrom(String ip) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/login");
    request.addHeader("X-Forwarded-For", ip);
    return request;
  }

  private static void sleepPast(Duration duration) {
    try {
      Thread.sleep(duration.toMillis() + 100L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
