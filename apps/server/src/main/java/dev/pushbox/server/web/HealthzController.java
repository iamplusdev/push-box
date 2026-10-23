package dev.pushbox.server.web;

import dev.pushbox.server.config.PbProperties;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §5.2 健康检查。这个端点不鉴权（探针、compose、Uptime Kuma 都只带一个 GET），
 * 所以响应里只有"库连得上吗"、"存储配置有效吗"和一个运行时长：版本号、SQL、连接池参数一律不回。
 */
@RestController
public class HealthzController {

  private static final Logger log = LoggerFactory.getLogger(HealthzController.class);
  /** isValid 的超时（秒）：健康检查自己不能变成一个新的慢查询。 */
  private static final int PING_TIMEOUT_SEC = 2;

  private final DataSource dataSource;
  private final PbProperties props;

  public HealthzController(DataSource dataSource, PbProperties props) {
    this.dataSource = dataSource;
    this.props = props;
  }

  @GetMapping("/healthz")
  public ResponseEntity<Map<String, Object>> healthz() {
    boolean dbUp = dbReachable();
    boolean storageConfigured = storageConfigValid();
    boolean allUp = dbUp && storageConfigured;

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("ok", allUp);
    body.put("db", dbUp ? "up" : "down");
    body.put("storage", storageConfigured ? "up" : "down");
    body.put("uptime_ms", ManagementFactory.getRuntimeMXBean().getUptime());
    return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
  }

  private boolean dbReachable() {
    try (Connection connection = dataSource.getConnection()) {
      return connection.isValid(PING_TIMEOUT_SEC);
    } catch (SQLException | RuntimeException e) {
      // 只记异常类型：JDBC 的 message 里常带 host/用户，健康检查不该把它写进日志
      log.debug("healthz 数据库探测失败: {}", e.getClass().getSimpleName());
      return false;
    }
  }

  private boolean storageConfigValid() {
    String storageType = props.getStorage().getType();
    if ("local".equalsIgnoreCase(storageType)) {
      // 本地存储：检查目录是否存在且可写
      String localPath = props.getStorage().getLocalPath();
      try {
        java.nio.file.Path path = java.nio.file.Paths.get(localPath);
        // 尝试创建目录（如果不存在），然后检查可写性
        java.nio.file.Files.createDirectories(path);
        // 验证是否能真正写入文件
        java.nio.file.Path testFile = path.resolve(".healthz_test_" + System.currentTimeMillis());
        java.nio.file.Files.write(testFile, "test".getBytes());
        java.nio.file.Files.deleteIfExists(testFile);
        return true;
      } catch (Exception e) {
        log.debug("healthz 本地存储目录不可写: {}", e.getMessage());
        return false;
      }
    } else {
      // S3 模式：检查配置是否完整
      PbProperties.S3 s3 = props.getS3();
      return s3.getEndpoint() != null && !s3.getEndpoint().isBlank()
          && s3.getBucket() != null && !s3.getBucket().isBlank();
    }
  }
}
