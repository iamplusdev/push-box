package dev.pushbox.server;

import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.setup.InstallServer;
import dev.pushbox.server.setup.RuntimeConfig;
import dev.pushbox.server.web.Requests;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(PbProperties.class)
@EnableScheduling
public class PushboxServerApplication {

  public static void main(String[] args) {
    Path configPath = RuntimeConfig.locate(option(args, RuntimeConfig.CONFIG_FILE_PROPERTY,
        System.getProperty(RuntimeConfig.CONFIG_FILE_PROPERTY)));
    if (!RuntimeConfig.existsAt(configPath)) {
      // 没有配置就没有服务：先起安装页让人把配置填出来，写完在同一个 JVM 里接着起 Spring，
      // 不需要重启容器（容器一重启，NAS 上那个"重新启动"按钮又会把人绕回一遍）。
      int port = parsePort(option(args, "server.port", System.getProperty("server.port")));
      new InstallServer(configPath, port).awaitConfiguration();
    }
    Requests.trustForwardedFor(RuntimeConfig.load(configPath).getSecurity().isTrustForwardedFor());
    SpringApplication.run(PushboxServerApplication.class, args);
  }

  /** 取 {@code --key=value} 形式的命令行值；没有就回落到 {@code fallback}。 */
  private static String option(String[] args, String key, String fallback) {
    String prefix = "--" + key + "=";
    for (String arg : args) {
      if (arg.startsWith(prefix)) {
        return arg.substring(prefix.length());
      }
    }
    return fallback;
  }

  private static int parsePort(String value) {
    if (value == null || value.isBlank()) {
      return InstallServer.DEFAULT_PORT;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return InstallServer.DEFAULT_PORT;
    }
  }
}
