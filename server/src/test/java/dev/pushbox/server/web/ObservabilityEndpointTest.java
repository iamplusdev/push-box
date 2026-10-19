package dev.pushbox.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * §5.2 观测端点的真上下文用例：过滤器和指标只有在完整启动链上才看得出问题
 * （FilterRegistrationBean 的 URL pattern、MeterBinder 有没有被 registry 收到）。
 * 上下文要连库和桶，所以与其余 DB/S3 用例共用同一道环境变量门。
 *
 * <p>{@code @AutoConfigureObservability} 不是可有可无的：Boot 在测试上下文里默认把指标导出关掉
 * （{@code management.defaults.metrics.export.enabled=false}），于是 `/actuator/prometheus`
 * 在测试里 404、在真实进程里 200。不加这个注解，下面 ② 就是在断言一个不存在的端点。
 */
@EnabledIfEnvironmentVariable(named = "PB_S3_ACCESS_KEY", matches = ".+")
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityEndpointTest {

  @Autowired
  private TestRestTemplate rest;

  @Test
  @DisplayName("① GET /healthz 不需要任何凭据，库通就回 200 + db=up")
  void healthzAnswersWithoutCredentials() {
    ResponseEntity<Map> resp = rest.getForEntity("/healthz", Map.class);

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody()).containsEntry("ok", true).containsEntry("db", "up");
    assertThat(((Number) resp.getBody().get("uptime_ms")).longValue()).isPositive();
  }

  @Test
  @DisplayName("② /actuator/prometheus 渲染出 pushbox 自己的 gauge 与限流 counter")
  void prometheusExposesPushboxMetrics() {
    String body = rest.getForObject("/actuator/prometheus", String.class);

    assertThat(body)
        .contains("# TYPE pushbox_ws_online gauge")
        .contains("# TYPE pushbox_media_queue_depth gauge")
        // counter 在 AbuseControlService 构造时就注册，没命中也应该是 0，不能整个消失
        .contains("pushbox_ratelimit_rejected_total")
        .contains("dim=\"channel\"")
        .contains("dim=\"ip\"")
        .contains("dim=\"device\"")
        .contains("pushbox_ingest_credential_locked_total");
  }

  @Test
  @DisplayName("③ /actuator/health 只回状态：show-details=never，组件明细一个都不给")
  void healthEndpointOmitsDetails() {
    Map<String, Object> body = rest.getForObject("/actuator/health", Map.class);

    assertThat(body).containsEntry("status", "UP");
    assertThat(body).doesNotContainKey("components");
    assertThat(body).doesNotContainKey("groups");
  }

  @Test
  @DisplayName("④ 没暴露的端点（env/loggers）仍然是 404，暴露面只有 health、info、prometheus")
  void unexposedEndpointsStayClosed() {
    assertThat(rest.getForEntity("/actuator/env", Map.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(rest.getForEntity("/actuator/loggers", Map.class).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(rest.getForEntity("/actuator/info", Map.class).getStatusCode())
        .isEqualTo(HttpStatus.OK);
  }
}
