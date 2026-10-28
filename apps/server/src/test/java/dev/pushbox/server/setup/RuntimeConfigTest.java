package dev.pushbox.server.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 一份 JSON 就是全部配置，所以"JSON 的字段 → Spring 的键"这条映射是整套部署的咽喉：
 * 映射错了不会报错，只会让某个开关静默失效（勾了对象存储却写在本地目录上）。
 */
class RuntimeConfigTest {

  @Test
  @DisplayName("① 两个布尔开关翻成 pb.storage.type 与 pb.s3.object-encrypt")
  void booleansDriveTheTwoStorageSwitches() {
    RuntimeConfig config = new RuntimeConfig();
    assertThat(config.toSpringProperties())
        .containsEntry("pb.storage.type", "local")
        .containsEntry("pb.s3.object-encrypt", "none");

    config.getStorage().setS3(true);
    config.getStorage().setEncrypted(true);
    assertThat(config.toSpringProperties())
        .containsEntry("pb.storage.type", "s3")
        .containsEntry("pb.s3.object-encrypt", "sse-c");
  }

  @Test
  @DisplayName("② 库地址由 host/port/name 三段拼出来，不用人填 JDBC URL")
  void jdbcUrlIsAssembledFromThreeFields() {
    RuntimeConfig config = new RuntimeConfig();
    config.getDatabase().setHost("192.0.2.20");
    config.getDatabase().setPort(3307);
    config.getDatabase().setName("pushbox_prod");

    String url = (String) config.toSpringProperties().get("spring.datasource.url");

    assertThat(url).startsWith("jdbc:mysql://192.0.2.20:3307/pushbox_prod?")
        .contains("characterEncoding=utf8")
        .contains("serverTimezone=Asia/Shanghai");
  }

  @Test
  @DisplayName("③ 空白字段一律落成空串而不是 null：占位符解析不出来会变成启动失败")
  void blanksBecomeEmptyStringsNotNull() {
    RuntimeConfig config = new RuntimeConfig();
    config.getStorage().setEndpoint(null);
    config.getStorage().setRegion("  ");
    config.getAdmin().setPassword(null);

    Map<String, Object> props = config.toSpringProperties();

    assertThat(props).doesNotContainValue(null);
    assertThat(props).containsEntry("pb.s3.endpoint", "")
        .containsEntry("pb.s3.region", "us-east-1")
        .containsEntry("pb.storage.local-path", "data/media")
        .containsEntry("pb.admin.secret", "");
  }

  @Test
  @DisplayName("④ 网段列表拼成逗号分隔：pb.security.internal-cidrs 绑的就是这个形状")
  void cidrListBecomesCommaSeparated() {
    RuntimeConfig config = new RuntimeConfig();
    config.getSecurity().setInternalCidrs(List.of("172.16.0.0/12", "10.0.0.0/8"));

    assertThat(config.toSpringProperties())
        .containsEntry("pb.security.internal-cidrs", "172.16.0.0/12,10.0.0.0/8");
  }

  @Test
  @DisplayName("⑤ JWT 签名钥留空就自动生成一把 32 字节的，且每次安装不同")
  void jwtKeyIsGeneratedWhenMissing() {
    RuntimeConfig first = new RuntimeConfig();
    RuntimeConfig second = new RuntimeConfig();

    assertThat(first.normalize()).isTrue();
    assertThat(second.normalize()).isTrue();

    assertThat(first.getAdmin().getJwtKey()).isNotEqualTo(second.getAdmin().getJwtKey());
    assertThat(java.util.Base64.getDecoder().decode(first.getAdmin().getJwtKey())).hasSize(32);
    // 已经有了就不许再动：换签名钥等于作废全部已发 token
    String kept = first.getAdmin().getJwtKey();
    assertThat(first.normalize()).isFalse();
    assertThat(first.getAdmin().getJwtKey()).isEqualTo(kept);
  }

  @Test
  @DisplayName("⑥ 写盘再读回来是同一份配置，临时文件不留残渣")
  void saveLoadRoundTrip(@TempDir Path dir) throws IOException {
    Path path = dir.resolve("nested/pushbox.json");
    RuntimeConfig config = new RuntimeConfig();
    config.setPort(8081);
    config.getDatabase().setPassword("口令 with 空格 & symbols");
    config.getStorage().setS3(true);
    config.getStorage().setEndpoint("http://192.0.2.10:9000");
    config.getAdmin().setPassword("管理台口令");
    config.getSecurity().setTrustForwardedFor(true);
    config.normalize();

    config.save(path);
    RuntimeConfig loaded = RuntimeConfig.load(path);

    assertThat(Files.exists(path)).isTrue();
    assertThat(path.resolveSibling("pushbox.json.tmp")).doesNotExist();
    assertThat(loaded.getPort()).isEqualTo(8081);
    assertThat(loaded.getDatabase().getPassword()).isEqualTo("口令 with 空格 & symbols");
    assertThat(loaded.getStorage().isS3()).isTrue();
    assertThat(loaded.getStorage().getEndpoint()).isEqualTo("http://192.0.2.10:9000");
    assertThat(loaded.getAdmin().getJwtKey()).isEqualTo(config.getAdmin().getJwtKey());
    assertThat(loaded.getSecurity().isTrustForwardedFor()).isTrue();
    // 默认值把 host/port/name/username 都填上了，加上刚写的口令就是"配好了"
    assertThat(loaded.isDatabaseConfigured()).isTrue();
  }

  @Test
  @DisplayName("⑦ 文件不存在时 loadOrDefault 给一份全默认的，不会抛")
  void missingFileYieldsDefaults(@TempDir Path dir) {
    RuntimeConfig config = RuntimeConfig.loadOrDefault(dir.resolve("nope.json"));

    assertThat(config.getPort()).isEqualTo(7404);
    assertThat(config.getStorage().isS3()).isFalse();
    assertThat(config.getAdmin().getPassword()).isEmpty();
    assertThat(RuntimeConfig.existsAt(dir.resolve("nope.json"))).isFalse();
  }

  @Test
  @DisplayName("⑧ 多出来的字段不判失败：老配置文件升级后仍然读得动")
  void unknownFieldsAreIgnored(@TempDir Path dir) throws IOException {
    Path path = dir.resolve("pushbox.json");
    Files.writeString(path, """
        {"port": 9000, "database": {"host": "db"}, "以后才有的段": {"a": 1}}""");

    RuntimeConfig config = RuntimeConfig.load(path);

    assertThat(config.getPort()).isEqualTo(9000);
    assertThat(config.getDatabase().getHost()).isEqualTo("db");
    assertThat(config.getDatabase().getPort()).isEqualTo(3306);
  }

  @Test
  @DisplayName("⑨ 库配齐了才算配好：安装页靠这一条决定显示表单还是直接启动")
  void databaseConfiguredNeedsAllFiveFields() {
    RuntimeConfig config = new RuntimeConfig();
    assertThat(config.isDatabaseConfigured()).isFalse();

    config.getDatabase().setPassword("口令");
    assertThat(config.isDatabaseConfigured()).isTrue();

    config.getDatabase().setHost("  ");
    assertThat(config.isDatabaseConfigured()).isFalse();
  }

  @Test
  @DisplayName("⑩ 图片抓取放行主机拼成 pb.media.allow-hosts，落盘前归一成小写去空白")
  void mediaAllowHostsReachTheSsrfGuard() {
    RuntimeConfig config = new RuntimeConfig();
    config.getMedia().setAllowHosts(java.util.Arrays.asList(" IMG.lan ", "10.0.0.20:8080", ""));

    // SsrfGuard 拿小写 host 做精确等值比较，大小写或空格没归一就等于这一项白填。
    assertThat(config.normalize()).isTrue();
    assertThat(config.getMedia().getAllowHosts())
        .containsExactly("img.lan", "10.0.0.20:8080");
    assertThat(config.toSpringProperties())
        .containsEntry("pb.media.allow-hosts", "img.lan,10.0.0.20:8080");

    // 归一过之后再跑一次不该报告"改动了"，否则每次启动都会重写一遍配置文件。
    assertThat(config.normalize()).isFalse();
  }

  @Test
  @DisplayName("⑪ 不认识的键被点名（warn 的依据）：拼错的键名不再静默落回默认")
  void unknownKeysAreListed() {
    // 顶层与嵌套都查：拼错一个 database.pasword，那个口令就静默落回默认空
    assertThat(RuntimeConfig.unknownKeys("""
        {"port": 9000, "database": {"host": "db", "pasword": "x"},
         "storage": {"s3": true}, "以后才有的段": {"a": 1}}"""))
        .containsExactlyInAnyOrder("以后才有的段", "database.pasword");

    // 全认识的配置（含服务端自己回写的 jwtKey）一个都不报
    RuntimeConfig full = new RuntimeConfig();
    full.normalize();
    assertThat(RuntimeConfig.unknownKeys(full.toJson())).isEmpty();

    // 解析不过的 JSON 不在这里报：那是 load() 里 readValue 的活
    assertThat(RuntimeConfig.unknownKeys("{不是 json")).isEmpty();
    assertThat(RuntimeConfig.unknownKeys("[1, 2]")).isEmpty();
  }
}
