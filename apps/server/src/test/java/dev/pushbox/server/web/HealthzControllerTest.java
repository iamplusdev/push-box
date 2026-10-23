package dev.pushbox.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.pushbox.server.config.PbProperties;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * §5.2 健康检查的纯 JVM 用例：这里要判的是"库不通的时候必须回 503"，
 * 而库不通恰恰是线上最想测到的状态，所以不能靠真库来构造，只能把 DataSource 和 PbProperties 换成假的。
 */
class HealthzControllerTest {

  @Test
  @DisplayName("① 库可用 + 存储配置有效：200 + ok/db=up/storage=up，不带任何连接串细节")
  void reachableDbAndStorageIs200Up() {
    PbProperties props = mock(PbProperties.class);
    PbProperties.Storage storage = mock(PbProperties.Storage.class);
    when(props.getStorage()).thenReturn(storage);
    when(storage.getType()).thenReturn("local");
    when(storage.getLocalPath()).thenReturn(System.getProperty("java.io.tmpdir"));

    ResponseEntity<Map<String, Object>> resp =
        new HealthzController(dataSource(true, false), props).healthz();

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(resp.getBody())
        .containsEntry("ok", true)
        .containsEntry("db", "up")
        .containsEntry("storage", "up")
        .doesNotContainKey("datasource")
        .doesNotContainKey("details");
    assertThat(((Number) resp.getBody().get("uptime_ms")).longValue()).isNotNegative();
  }

  @Test
  @DisplayName("② 连接拿得到但 isValid=false（库在、schema 挂了）：503 + db=down")
  void invalidConnectionIs503Down() {
    PbProperties props = mock(PbProperties.class);
    PbProperties.Storage storage = mock(PbProperties.Storage.class);
    when(props.getStorage()).thenReturn(storage);
    when(storage.getType()).thenReturn("local");
    when(storage.getLocalPath()).thenReturn(System.getProperty("java.io.tmpdir"));

    ResponseEntity<Map<String, Object>> resp =
        new HealthzController(dataSource(false, false), props).healthz();

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(resp.getBody()).containsEntry("ok", false).containsEntry("db", "down");
  }

  @Test
  @DisplayName("③ getConnection 直接抛（连接池打满/库停了）：503，异常不许冒到响应里")
  void connectionFailureIs503Down() {
    PbProperties props = mock(PbProperties.class);
    PbProperties.Storage storage = mock(PbProperties.Storage.class);
    when(props.getStorage()).thenReturn(storage);
    when(storage.getType()).thenReturn("local");
    when(storage.getLocalPath()).thenReturn(System.getProperty("java.io.tmpdir"));

    ResponseEntity<Map<String, Object>> resp =
        new HealthzController(dataSource(true, true), props).healthz();

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(resp.getBody()).containsEntry("ok", false).containsEntry("db", "down");
  }

  @Test
  @DisplayName("④ 本地存储目录不可写：503 + storage=down")
  void storageDirNotWritableIs503Down() {
    PbProperties props = mock(PbProperties.class);
    PbProperties.Storage storage = mock(PbProperties.Storage.class);
    when(props.getStorage()).thenReturn(storage);
    when(storage.getType()).thenReturn("local");
    // 使用 Windows 系统保护目录（普通用户无法写入）或 Unix 的 /root
    String os = System.getProperty("os.name").toLowerCase();
    when(storage.getLocalPath()).thenReturn(os.contains("win") ? "C:\\Windows\\System32\\drivers" : "/root/nonexistent");

    ResponseEntity<Map<String, Object>> resp =
        new HealthzController(dataSource(true, false), props).healthz();

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(resp.getBody()).containsEntry("ok", false).containsEntry("storage", "down");
  }

  @Test
  @DisplayName("⑤ S3 模式配置缺失：503 + storage=down")
  void s3ConfigMissingIs503Down() {
    PbProperties props = mock(PbProperties.class);
    PbProperties.Storage storage = mock(PbProperties.Storage.class);
    PbProperties.S3 s3 = mock(PbProperties.S3.class);
    when(props.getStorage()).thenReturn(storage);
    when(props.getS3()).thenReturn(s3);
    when(storage.getType()).thenReturn("s3");
    when(s3.getEndpoint()).thenReturn(null); // 缺少 endpoint

    ResponseEntity<Map<String, Object>> resp =
        new HealthzController(dataSource(true, false), props).healthz();

    assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(resp.getBody()).containsEntry("ok", false).containsEntry("storage", "down");
  }

  private static DataSource dataSource(boolean valid, boolean throwOnGetConnection) {
    Connection connection = (Connection) Proxy.newProxyInstance(
        HealthzControllerTest.class.getClassLoader(), new Class<?>[] {Connection.class},
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "isValid" -> {
              return valid;
            }
            case "close" -> {
              return null;
            }
            default -> throw new UnsupportedOperationException(method.getName());
          }
        });
    return (DataSource) Proxy.newProxyInstance(
        HealthzControllerTest.class.getClassLoader(), new Class<?>[] {DataSource.class},
        (proxy, method, args) -> {
          if (!method.getName().equals("getConnection")) {
            throw new UnsupportedOperationException(method.getName());
          }
          if (throwOnGetConnection) {
            throw new SQLException("pool exhausted");
          }
          return connection;
        });
  }
}
