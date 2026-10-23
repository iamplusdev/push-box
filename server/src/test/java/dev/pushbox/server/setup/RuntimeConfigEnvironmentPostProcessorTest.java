package dev.pushbox.server.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 配置文件在 Spring 属性栈里的位置。这件事必须钉住：插得太靠前，探针与体检用的
 * {@code --server.port=8081}、{@code --pb.ws.replay-batch=5} 就会被文件顶掉，
 * "临时改一个值"变成"改文件再重启"；插得太靠后，yml 里的默认值又会把配置吃掉。
 */
class RuntimeConfigEnvironmentPostProcessorTest {

  private final RuntimeConfigEnvironmentPostProcessor processor =
      new RuntimeConfigEnvironmentPostProcessor();

  @AfterEach
  void clearSystemProperty() {
    System.clearProperty("server.port");
  }

  private Path writeConfig(Path dir, String json) throws IOException {
    Path path = dir.resolve("pushbox.json");
    Files.writeString(path, json);
    return path;
  }

  /** StandardEnvironment 自带 systemProperties 与 systemEnvironment，正好用来验优先级。 */
  private StandardEnvironment envWith(Path path) {
    StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources().addLast(new MapPropertySource("test",
        Map.of(RuntimeConfig.CONFIG_FILE_PROPERTY, path.toString())));
    return env;
  }

  @Test
  @DisplayName("① 文件里的值进到 environment，并且压过 yml 那一层")
  void fileValuesLandInTheEnvironment(@TempDir Path dir) throws IOException {
    Path path = writeConfig(dir, """
        {"port": 9101,
         "database": {"host": "db.internal", "port": 3307, "name": "pb", "username": "u", "password": "p"},
         "storage": {"s3": true, "encrypted": true, "passphrase": "这是一个足够长的加密口令"},
         "admin": {"password": "管理台口令"},
         "security": {"internalCidrs": ["10.0.0.0/8"]}}""");
    StandardEnvironment env = envWith(path);
    env.getPropertySources().addLast(new MapPropertySource("applicationConfig", Map.of(
        "server.port", "7404", "pb.storage.type", "local")));

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("server.port")).isEqualTo("9101");
    assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:mysql://db.internal:3307/pb?");
    assertThat(env.getProperty("spring.datasource.password")).isEqualTo("p");
    assertThat(env.getProperty("pb.storage.type")).isEqualTo("s3");
    assertThat(env.getProperty("pb.s3.object-encrypt")).isEqualTo("sse-c");
    assertThat(env.getProperty("pb.s3.master-passphrase")).isEqualTo("这是一个足够长的加密口令");
    assertThat(env.getProperty("pb.admin.secret")).isEqualTo("管理台口令");
    assertThat(env.getProperty("pb.security.internal-cidrs")).isEqualTo("10.0.0.0/8");
    assertThat(env.getProperty("pb.config-file")).isEqualTo(path.toString());
  }

  @Test
  @DisplayName("② 命令行赢过文件：探针要能临时换端口，不必去改配置")
  void commandLineWins(@TempDir Path dir) throws IOException {
    Path path = writeConfig(dir, "{\"port\": 9101, \"database\": {\"password\": \"p\"}}");
    StandardEnvironment env = envWith(path);
    env.getPropertySources().addFirst(
        new MapPropertySource("commandLineArgs", Map.of("server.port", "8081")));

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("server.port")).isEqualTo("8081");
  }

  @Test
  @DisplayName("③ -D 系统属性同样赢过文件：本机起第二个实例时用得上")
  void systemPropertyWins(@TempDir Path dir) throws IOException {
    System.setProperty("server.port", "9999");
    Path path = writeConfig(dir, "{\"port\": 9101, \"database\": {\"password\": \"p\"}}");
    StandardEnvironment env = envWith(path);

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("server.port")).isEqualTo("9999");
  }

  @Test
  @DisplayName("④ 文件不存在就什么都不加：这时跑的是安装页，不是半个服务")
  void missingFileAddsNothing(@TempDir Path dir) {
    StandardEnvironment env = envWith(dir.resolve("nope.json"));

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getPropertySources().contains(
        RuntimeConfigEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
    assertThat(env.getProperty("spring.datasource.url")).isNull();
  }

  @Test
  @DisplayName("⑤ 签名钥留空时读进来就有一把：管理台不会因为没人手填而起不来")
  void jwtKeyIsFilledOnLoad(@TempDir Path dir) throws IOException {
    Path path = writeConfig(dir, "{\"database\": {\"password\": \"p\"}, \"admin\": {\"password\": \"口令\"}}");
    StandardEnvironment env = envWith(path);

    processor.postProcessEnvironment(env, new SpringApplication());

    String jwt = env.getProperty("pb.admin.jwt-secret");
    assertThat(jwt).isNotBlank();
    assertThat(java.util.Base64.getDecoder().decode(jwt)).hasSize(32);
  }
}
