package dev.pushbox.server.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 安装页的表单校验。这一层不碰网络：库与桶连不连得上是另一件事（真跑一遍才算数，
 * 见 scripts/release-verify.sh 的端到端那条），这里钉的是"表单填错时给的话对不对"。
 */
class InstallServerTest {

  private final InstallServer server = new InstallServer(java.nio.file.Paths.get("unused.json"), 0);

  private static RuntimeConfig valid() {
    RuntimeConfig config = new RuntimeConfig();
    config.getDatabase().setPassword("库口令");
    return config;
  }

  @Test
  @DisplayName("① 填齐库那五项就过：本地存储 + 不加密 + 不开管理台是最小可跑组合")
  void minimalFormIsAccepted() {
    assertThat(server.validate(valid())).isNull();
  }

  @Test
  @DisplayName("② 库少一项就点名一项，且不把口令带进错误里")
  void databaseFieldsAreNamedOneByOne() {
    RuntimeConfig config = valid();
    config.getDatabase().setHost("  ");
    assertThat(server.validate(config)).contains("数据库地址").contains("127.0.0.1");

    config = valid();
    config.getDatabase().setUsername(null);
    assertThat(server.validate(config)).contains("用户名与密码");

    config = valid();
    config.getDatabase().setName("push box;drop");
    assertThat(server.validate(config)).contains("库名");
  }

  @Test
  @DisplayName("③ 勾了对象存储就要把四件套填全")
  void s3RequiresAllFourFields() {
    RuntimeConfig config = valid();
    config.getStorage().setS3(true);
    config.getStorage().setBucket("  ");
    assertThat(server.validate(config)).contains("地址");

    config.getStorage().setEndpoint("http://192.0.2.10:9000");
    assertThat(server.validate(config)).contains("桶名");

    config.getStorage().setBucket("pushbox");

    config.getStorage().setAccessKey("ak");
    assertThat(server.validate(config)).contains("Secret Key");

    config.getStorage().setSecretKey("sk");
    // 到这里表单本身已经完整，连通性要到 install 时才真去连
    assertThat(server.validate(config)).isNull();
  }

  @Test
  @DisplayName("④ 不勾对象存储时本地目录不能空")
  void localPathIsRequiredWhenS3IsOff() {
    RuntimeConfig config = valid();
    config.getStorage().setLocalPath("  ");

    assertThat(server.validate(config)).contains("本地存储目录");
  }

  @Test
  @DisplayName("⑤ 勾了加密就要给口令或主钥其中一样，两个都给也不行")
  void encryptionNeedsExactlyOneKeySource() {
    RuntimeConfig config = valid();
    config.getStorage().setEncrypted(true);
    assertThat(server.validate(config)).contains("加密口令").contains("主钥");

    config.getStorage().setPassphrase("短口令");
    assertThat(server.validate(config)).contains("至少 12");

    config.getStorage().setPassphrase("这是一个足够长的加密口令");
    assertThat(server.validate(config)).isNull();

    config.getStorage().setMasterKey(RuntimeConfig.newJwtKey());
    assertThat(server.validate(config)).contains("只能填一个");

    config.getStorage().setPassphrase("");
    assertThat(server.validate(config)).isNull();

    config.getStorage().setMasterKey("不是 base64 !!");
    assertThat(server.validate(config)).contains("主钥格式不对");
  }

  @Test
  @DisplayName("⑥ 管理台口令留空就是不开；填了就不能太短")
  void adminPasswordIsOptionalButNotWeak() {
    RuntimeConfig config = valid();
    config.getAdmin().setPassword("");
    assertThat(server.validate(config)).isNull();

    config.getAdmin().setPassword("1234567");
    assertThat(server.validate(config)).contains("至少 8");

    config.getAdmin().setPassword("12345678");
    assertThat(server.validate(config)).isNull();
  }

  @Test
  @DisplayName("⑦ 运维网段要写成 CIDR：这一项写错会被 InternalAccessFilter 当场拒绝启动")
  void cidrsMustLookLikeCidr() {
    RuntimeConfig config = valid();
    config.getSecurity().setInternalCidrs(List.of("172.16.0.0/12", "10.0.0.0/8"));
    assertThat(server.validate(config)).isNull();

    config.getSecurity().setInternalCidrs(List.of("172.16.0.0"));
    assertThat(server.validate(config)).contains("CIDR");
  }

  @Test
  @DisplayName("⑧ 端口越界要拦：0 与 70000 都不会有人当成笔误之外的东西")
  void portRangeIsChecked() {
    RuntimeConfig config = valid();
    config.setPort(0);
    assertThat(server.validate(config)).contains("端口");

    config.setPort(70000);
    assertThat(server.validate(config)).contains("端口");

    config.setPort(65535);
    assertThat(server.validate(config)).isNull();
  }

  @Test
  @DisplayName("⑨ 安装页资源在 jar 里，且表单字段与 JSON 字段一一对得上")
  void pageResourceCoversEveryField() throws IOException {
    try (InputStream in = InstallServer.class.getResourceAsStream("/setup/index.html")) {
      assertThat(in).as("jar 里没有 setup/index.html，安装页会是 500").isNotNull();
      String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String id : new String[] {"dbHost", "dbPort", "dbName", "dbUser", "dbPass", "localPath",
          "useS3", "s3Endpoint", "s3Bucket", "s3Region", "s3Ak", "s3Sk", "s3PathStyle",
          "encrypted", "passphrase", "masterKey", "adminPass", "port", "cidrs",
          "mediaAllowHosts"}) {
        assertThat(html).as("表单缺 %s 这一项", id).contains("id=\"" + id + "\"");
      }
      assertThat(html).contains("/api/v1/setup");
      // 这个占位符由 InstallServer 返回前换成真实监听端口；占位符本身刻意不带 PB_ 前缀，
      // 否则会撞上"页面里不出现任何环境变量键名"那条闸。
      assertThat(html).contains("name=\"pb-install-port\" content=\"__INSTALL_PORT__\"");
      assertThat(html).doesNotContain("PB_");
      // 自动跳转按"解析后有没有 #app 元素"判断；安装页自己不能有这个挂载点，
      // 否则交接期里它返回的 200 会被当成管理台，刷新一下就把轮询打断。
      assertThat(html).doesNotContain("<div id=\"app\"");
    }
  }

  @Test
  @DisplayName("⑩ 保存成功后配置文件真的落盘，且带上了自动生成的签名钥")
  void installWritesTheFile(@TempDir java.nio.file.Path dir) {
    java.nio.file.Path path = dir.resolve("pushbox.json");
    InstallServer installing = offline(path, 0);
    RuntimeConfig config = valid();
    config.getAdmin().setPassword("管理台口令至少要八个字符");

    assertThat(installing.install(config)).containsEntry("ok", true);

    RuntimeConfig saved = RuntimeConfig.load(path);
    assertThat(saved.getAdmin().getPassword()).isEqualTo("管理台口令至少要八个字符");
    assertThat(saved.getAdmin().getJwtKey()).isNotBlank();
  }

  @Test
  @DisplayName("⑪ 库连不上就一个字节都不写：留下半份配置比让人重填一遍更糟")
  void failedProbeLeavesNoFile(@TempDir java.nio.file.Path dir) {
    java.nio.file.Path path = dir.resolve("pushbox.json");
    InstallServer installing = new InstallServer(path, 0) {
      @Override
      String checkDatabase(RuntimeConfig config) {
        return "连不上数据库：Access denied";
      }
    };

    assertThat(installing.install(valid())).containsEntry("ok", false);
    assertThat(java.nio.file.Files.exists(path)).isFalse();
  }

  @Test
  @DisplayName("⑫ 安装页自己装 MySQL 驱动：那个类名是硬编码的，得和真实的连接器对上")
  void mysqlDriverClassNamesARealDriver() throws Exception {
    // 驱动是 runtime scope，编译期一句都不会报；连接器被挪走或改名时只有这里会红。
    // 注意这条**验不到**真正的坑：fat jar 里系统类加载器看不见 BOOT-INF/lib，
    // 而 exploded classpath（mvn test）看得见——那一步只有 scripts/install-page-smoke.sh
    // 能抓到（它真 `java -jar` 起一次，由 release-server.sh 在发布前调用）。
    InstallServer.loadMysqlDriver();
    assertThat(java.sql.DriverManager.getDriver("jdbc:mysql://h:3306/db")).isNotNull();
  }

  @Test
  @DisplayName("⑬ 安装成功的那次 POST 要拿到完整响应：放行启动必须排在写回之后")
  void installResponseSurvivesTheServerShutdown(@TempDir java.nio.file.Path dir) throws Exception {
    // 回归：原来 install() 在返回前就 countDown，awaitConfiguration() 一醒立刻 stop，
    // 于是"配置真的写好了"而客户端只拿到截断的空响应——部署脚本据此报"连不上安装页"。
    int port;
    try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    java.nio.file.Path path = dir.resolve("pushbox.json");
    InstallServer installing = offline(path, port);
    Thread waiting = new Thread(installing::awaitConfiguration, "install-page");
    waiting.start();
    try {
      awaitListening(port);
      RuntimeConfig config = valid();
      config.setPort(port);
      config.getAdmin().setPassword("管理台口令至少要八个字符");

      java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
          java.net.http.HttpRequest
              .newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/setup"))
              .header("Content-Type", "application/json")
              .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                  dev.pushbox.core.protocol.Json.write(config)))
              .build(),
          java.net.http.HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"ok\":true").contains("配置已保存到");
      assertThat(RuntimeConfig.load(path)).isNotNull();
      waiting.join(java.time.Duration.ofSeconds(10));
      assertThat(waiting.isAlive()).as("安装成功后 awaitConfiguration 没有返回").isFalse();
    } finally {
      waiting.interrupt();
      waiting.join(java.time.Duration.ofSeconds(5));
    }
  }

  @Test
  @DisplayName("⑭ 图片抓取放行主机只认 host 或 host:port：带 scheme 或路径一律拦下")
  void mediaAllowHostsMustBeBareHosts() {
    RuntimeConfig config = valid();
    config.getMedia().setAllowHosts(List.of("img.lan", "10.0.0.20:8080"));
    assertThat(server.validate(config)).isNull();

    config = valid();
    config.getMedia().setAllowHosts(List.of("http://img.lan"));
    assertThat(server.validate(config)).contains("host");

    config = valid();
    config.getMedia().setAllowHosts(List.of("img.lan/a.png"));
    assertThat(server.validate(config)).contains("路径");

    config = valid();
    config.getMedia().setAllowHosts(List.of("*.lan"));
    assertThat(server.validate(config)).isNotNull();
  }

  @Test
  @DisplayName("⑮ 未配置时非根路径 302 回 /，且页面里注入的是真实监听端口")
  void unconfiguredServerRedirectsEverythingToTheForm(@TempDir java.nio.file.Path dir) throws Exception {
    int port;
    try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
      port = probe.getLocalPort();
    }
    java.nio.file.Path path = dir.resolve("pushbox.json");
    InstallServer installing = offline(path, port);
    Thread waiting = new Thread(installing::awaitConfiguration, "install-page");
    waiting.start();
    try {
      awaitListening(port);
      java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
          .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();

      java.net.http.HttpResponse<String> page = client.send(
          java.net.http.HttpRequest.newBuilder(
              java.net.URI.create("http://127.0.0.1:" + port + "/")).build(),
          java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(page.statusCode()).isEqualTo(200);
      // location.port 在容器端口映射下是宿主那一侧，页面必须拿到服务端真实监听的那个。
      assertThat(page.body()).contains("<meta name=\"pb-install-port\" content=\"" + port + "\">");

      for (String wrong : new String[] {"/dashboard", "/login", "/admin"}) {
        java.net.http.HttpResponse<String> redirected = client.send(
            java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + port + wrong)).build(),
            java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(redirected.statusCode()).as(wrong).isEqualTo(302);
        assertThat(redirected.headers().firstValue("Location")).as(wrong).contains("/");
      }

      // 收尾：写一份合法配置让安装页自己退出，免得 interrupt 在测试输出里留一段栈。
      RuntimeConfig config = valid();
      config.setPort(port);
      java.net.http.HttpResponse<String> saved = client.send(
          java.net.http.HttpRequest.newBuilder(
              java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/setup"))
              .header("Content-Type", "application/json")
              .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                  dev.pushbox.core.protocol.Json.write(config)))
              .build(),
          java.net.http.HttpResponse.BodyHandlers.ofString());
      assertThat(saved.body()).contains("\"ok\":true");
      waiting.join(java.time.Duration.ofSeconds(10));
      assertThat(waiting.isAlive()).as("写盘后安装页没有退出").isFalse();
    } finally {
      waiting.interrupt();
      waiting.join(java.time.Duration.ofSeconds(5));
    }
  }

  /** 安装页是在 awaitConfiguration() 里面才起监听的，所以得先确认它真的在听。 */  private static void awaitListening(int port) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      try (java.net.Socket socket = new java.net.Socket()) {
        socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
        return;
      } catch (IOException ignored) {
        Thread.sleep(50);
      }
    }
    throw new IllegalStateException("安装页在 5 秒内没有监听 " + port);
  }

  /** 不碰网络的安装器：库与桶的连通性由端到端那条判据真跑，这里只管表单与落盘。 */
  private static InstallServer offline(java.nio.file.Path path, int port) {
    return new InstallServer(path, port) {
      @Override
      String checkDatabase(RuntimeConfig config) {
        return null;
      }

      @Override
      String checkS3(RuntimeConfig.Storage storage) {
        return null;
      }
    };
  }
}
