package dev.pushbox.server.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * 正文变量 {@code ${host}} 与 {@code ${version}} 的来源。只在定时推送和入站渲染这两处用到，
 * 不是要做一个通用的"实例信息"接口：健康检查刻意不回版本号，这里多取一份不代表那边也该开放。
 *
 * <p>两个值都只在启动时算一次。{@code ${host}} 优先取环境变量 {@code HOSTNAME}（容器里就是容器主机名），
 * 取不到再用 JVM 的主机名；两个都拿不到就给 {@code unknown}，绝不因为取不到主机名让渲染抛异常。
 */
@Component
public class ServerIdentity {

  private final String host;
  private final String version;

  public ServerIdentity(Optional<BuildProperties> build) {
    this.host = detectHost();
    // 只有 mvn 打包时会生成 build-info；mvn test 或直接从 IDE 跑就只有 unknown
    this.version = build.map(BuildProperties::getVersion)
        .or(() -> Optional.ofNullable(ServerIdentity.class.getPackage().getImplementationVersion()))
        .orElse("unknown");
  }

  public String host() {
    return host;
  }

  public String version() {
    return version;
  }

  private static String detectHost() {
    String env = System.getenv("HOSTNAME");
    if (env != null && !env.isBlank()) {
      return env.trim();
    }
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "unknown";
    }
  }
}
