package dev.pushbox.server.setup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pushbox.core.crypto.KeyWrap;
import dev.pushbox.core.protocol.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * 安装页。配置文件不存在时，{@code main} 先起这个迷你服务，让部署者在浏览器里把库地址、
 * 存储、管理台口令填出来；填完写盘，这个服务停掉，同一个 JVM 里接着起 Spring。
 *
 * <p>用 JDK 自带的 {@link HttpServer} 而不是"起一个 setup profile 的 Spring"：
 * 后者要让 DataSource / Flyway / MyBatis 全都能被条件化关掉，而这一页需要的只是
 * 一个 GET 与三个 POST。不引入自动配置的开关，就没有"开关配错了起不来"这类新故障。
 *
 * <p>它绑的是服务端将要用的那个端口（默认 7404），所以部署者从头到尾只记一个地址：
 * 没配置时打开是安装页，配置完再打开就是管理台。
 *
 * <p>只在文件不存在时监听。文件一落盘这个服务就停了，所以它不会变成一个
 * "谁都能改配置"的常驻入口——已经装好的实例重启也不会再看到这一页。
 */
public class InstallServer {

  /** 与 {@code application.yml} 的 server.port 同一个默认值。 */
  public static final int DEFAULT_PORT = 7404;
  static final int MIN_PASSPHRASE_CODE_POINTS = 12;

  /** 见 {@link #loadMysqlDriver()}：这个名字是硬编码的，所以它得和真实的连接器类对上。 */
  static final String MYSQL_DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

  private static final String PAGE_RESOURCE = "/setup/index.html";

  private final Path configPath;
  private final int port;
  private final CountDownLatch configured = new CountDownLatch(1);

  public InstallServer(Path configPath, int port) {
    this.configPath = configPath;
    this.port = port;
  }

  /** 阻塞到安装页写出配置文件为止。中途被打断就当启动失败——没有配置就没有服务。 */
  public void awaitConfiguration() {
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress(port), 0);
    } catch (IOException e) {
      throw new IllegalStateException(
          "安装页起不来：端口 %d 被占用（%s）。先停掉占用它的进程，或用 --server.port=别的端口 重试。"
              .formatted(port, e.getMessage()), e);
    }
    server.createContext("/", this::handlePage);
    server.createContext("/api/v1/setup", this::handleSetup);
    server.setExecutor(Executors.newFixedThreadPool(4));
    server.start();
    System.out.printf("""

        ┌─ PushBox 还没有配置 ────────────────────────────────────────
        │  请在浏览器里打开： http://localhost:%d/
        │  （从别的机器访问就把 localhost 换成这台机器的 IP）
        │  填完提交，服务端会自动接着启动，不需要重启容器。
        │  配置文件将写到： %s
        └────────────────────────────────────────────────────────────
        %n""", port, configPath);
    try {
      configured.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("等待安装页时被中断", e);
    } finally {
      // 给 1 秒余量：countDown 排在响应写完之后，但派发线程收尾还差最后一步，
      // stop(0) 是"立刻拆"，会把那次 POST 的连接硬断掉。
      server.stop(1);
    }
  }

  private void handlePage(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    if (!"/".equals(path) && !"/index.html".equals(path)) {
      respond(exchange, 404, "text/plain; charset=utf-8",
          "PushBox 还没配置完，只有安装页在监听。".getBytes(StandardCharsets.UTF_8));
      return;
    }
    byte[] html = readPage();
    if (html == null) {
      respond(exchange, 500, "text/plain; charset=utf-8",
          "安装页资源缺失（jar 里没有 setup/index.html）".getBytes(StandardCharsets.UTF_8));
      return;
    }
    respond(exchange, 200, "text/html; charset=utf-8", html);
  }

  private byte[] readPage() {
    try (InputStream in = InstallServer.class.getResourceAsStream(PAGE_RESOURCE)) {
      return in == null ? null : in.readAllBytes();
    } catch (IOException e) {
      return null;
    }
  }

  private void handleSetup(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "application/json; charset=utf-8",
          Json.writeBytes(Map.of("ok", false, "error", "只接受 POST")));
      return;
    }
    byte[] body = exchange.getRequestBody().readAllBytes();
    String action = exchange.getRequestURI().getQuery() == null
        ? "" : exchange.getRequestURI().getQuery();
    try {
      switch (action) {
        case "action=test" -> respondJson(exchange, 200, testDatabase(parse(body)));
        case "action=restore" -> respondJson(exchange, 200, restore(parse(body)));
        default -> {
          Map<String, Object> result = install(parse(body));
          respondJson(exchange, 200, result);
          // countDown 必须排在响应写出去之后：awaitConfiguration() 一醒就会 stop 掉这个
          // HttpServer，提前放行等于把还在回写的这次 POST 截断——部署脚本拿到空响应，
          // 于是"安装其实成功了"却报"连不上安装页"。
          if (Boolean.TRUE.equals(result.get("ok"))) {
            configured.countDown();
          }
        }
      }
    } catch (RuntimeException e) {
      respondJson(exchange, 200, Map.of("ok", false, "error", describe(e)));
    }
  }

  private static RuntimeConfig parse(byte[] body) {
    try {
      return Json.readBytes(body, RuntimeConfig.class);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("提交的内容不是合法的配置 JSON：" + e.getMessage(), e);
    }
  }

  /** 只测库：让部署者在提交整份配置之前先看到"这个地址/口令对不对"。 */
  private Map<String, Object> testDatabase(RuntimeConfig config) {
    String error = checkDatabase(config);
    return error == null
        ? Map.of("ok", true, "message", "数据库连接正常，库 %s 已就绪".formatted(config.getDatabase().getName()))
        : Map.of("ok", false, "error", error);
  }

  /** 从库里的副本找回上一次的配置（重装时用）。只带库凭据过来，回来的是整份 JSON。 */
  private Map<String, Object> restore(RuntimeConfig config) {
    String error = checkDatabase(config);
    if (error != null) {
      return Map.of("ok", false, "error", error);
    }
    try (Connection c = open(config.getDatabase(), config.getDatabase().getName());
        var ps = c.prepareStatement("SELECT state_value FROM app_state WHERE state_key = ?")) {
      ps.setString(1, RuntimeConfigSync.STATE_KEY);
      try (var rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Map.of("ok", false, "error", "库里没有上一次配置的副本（app_state."
              + RuntimeConfigSync.STATE_KEY + "），请手工填写。");
        }
        RuntimeConfig stored = Json.read(rs.getString(1), RuntimeConfig.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("config", stored);
        return result;
      }
    } catch (Exception e) {
      return Map.of("ok", false, "error", "读配置副本失败：" + describe(e));
    }
  }

  /** 全量校验并落盘。任何一项不过就不写文件，返回具体原因。 */
  Map<String, Object> install(RuntimeConfig config) {
    String error = validate(config);
    if (error == null) {
      error = checkDatabase(config);
    }
    if (error == null && config.getStorage().isS3()) {
      error = checkS3(config.getStorage());
    }
    if (error != null) {
      return Map.of("ok", false, "error", error);
    }
    config.normalize();
    try {
      config.save(configPath);
    } catch (RuntimeException e) {
      return Map.of("ok", false, "error", "配置写不进去：" + describe(e));
    }
    // 这里刻意不碰 configured：放行启动是 handleSetup 在把响应写完之后才做的事。
    return Map.of("ok", true, "message", "配置已保存到 " + configPath + "，服务端正在启动…");
  }

  /**
   * 逐项校验表单本身，返回第一条不通过的原因；全过返回 null。
   *
   * <p>这一层不碰网络：库与桶的连通性由 {@link #checkDatabase} 与 {@link #checkS3} 单独负责。
   * 分开是为了让"格式就不对"与"连不上"给出不同的话——前者改表单，后者查网络与权限。
   */
  String validate(RuntimeConfig config) {
    if (config.getPort() <= 0 || config.getPort() > 65535) {
      return "服务端口要在 1–65535 之间";
    }
    String dbError = validateDatabaseFields(config.getDatabase());
    if (dbError != null) {
      return dbError;
    }
    RuntimeConfig.Storage storage = config.getStorage();
    if (storage.isS3()) {
      if (isBlank(storage.getEndpoint())) {
        return "启用了对象存储，就要填它的地址（例如 http://192.0.2.10:9000）";
      }
      if (isBlank(storage.getBucket())) {
        return "启用了对象存储，就要填桶名";
      }
      if (isBlank(storage.getAccessKey()) || isBlank(storage.getSecretKey())) {
        return "启用了对象存储，Access Key 与 Secret Key 都要填";
      }
    } else if (isBlank(storage.getLocalPath())) {
      return "本地存储目录不能为空";
    }
    if (storage.isEncrypted()) {
      boolean hasKey = !isBlank(storage.getMasterKey());
      boolean hasPass = !isBlank(storage.getPassphrase());
      if (hasKey && hasPass) {
        return "加密口令与主钥只能填一个：两者派生出的密钥不同，同时填无法判断该用哪一把";
      }
      if (hasPass) {
        String normalized = Normalizer.normalize(storage.getPassphrase().trim(), Normalizer.Form.NFC);
        if (normalized.codePointCount(0, normalized.length()) < MIN_PASSPHRASE_CODE_POINTS) {
          return "加密口令至少 " + MIN_PASSPHRASE_CODE_POINTS + " 个字符（它要派生出加密图片的密钥，太短等于没加密）";
        }
      } else if (hasKey) {
        if (KeyWrap.masterKeyFromConfig(storage.getMasterKey()) == null) {
          return "主钥格式不对：它应当是 base64 的 32 字节（openssl rand -base64 32）";
        }
      } else {
        return "开启了存储加密，就要填一个加密口令（推荐）或一把主钥";
      }
    }
    if (!isBlank(config.getAdmin().getPassword())
        && config.getAdmin().getPassword().trim().length() < 8) {
      return "管理台口令至少 8 个字符；不想开管理台就把它留空";
    }
    for (String cidr : config.getSecurity().getInternalCidrs()) {
      if (cidr == null || cidr.isBlank() || !cidr.contains("/")) {
        return "运维网段要写成 CIDR（例如 172.16.0.0/12），逗号或换行分隔";
      }
    }
    for (String host : config.getMedia().getAllowHosts()) {
      if (host == null || !host.trim().matches("[A-Za-z0-9._\\-]+(:[0-9]{1,5})?")) {
        return "图片抓取放行主机要写成 host 或 host:port（例如 img.lan 或 10.0.0.20:8080），"
            + "不带 http:// 也不带路径";
      }
    }
    return null;
  }

  /** 库那五项的格式检查，不连网。 */
  static String validateDatabaseFields(RuntimeConfig.Database db) {
    if (isBlank(db.getHost())) {
      return "数据库地址不能为空。库和容器在同一台机器上也别填 127.0.0.1——容器里的 127.0.0.1 是容器自己。";
    }
    if (db.getPort() <= 0 || db.getPort() > 65535) {
      return "数据库端口要在 1–65535 之间";
    }
    if (isBlank(db.getName()) || !db.getName().matches("[A-Za-z0-9_$]+")) {
      return "库名只能是字母、数字、下划线与 $";
    }
    if (isBlank(db.getUsername()) || isBlank(db.getPassword())) {
      return "数据库用户名与密码都要填";
    }
    return null;
  }

  /** 连得上、库在（不在就顺手建）。返回第一条错，全过返回 null。 */
  String checkDatabase(RuntimeConfig config) {
    RuntimeConfig.Database db = config.getDatabase();
    String formatError = validateDatabaseFields(db);
    if (formatError != null) {
      return formatError;
    }
    try (Connection ignored = open(db, null)) {
      // 库不存在就顺手建：让部署者少一步"先去 MySQL 里 CREATE DATABASE"。
      // 建不动（权限不够）不算失败，下一步直连那个库时自然会给出真原因。
      try (Statement s = ignored.createStatement()) {
        s.execute("CREATE DATABASE IF NOT EXISTS `" + db.getName()
            + "` DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      } catch (Exception ignoredCreate) {
        // 见上：这里不判死。
      }
    } catch (Exception e) {
      return "连不上数据库：" + describe(e) + hint(db);
    }
    try (Connection ignored = open(db, db.getName())) {
      return null;
    } catch (Exception e) {
      return "能连上数据库服务器，但打不开库 " + db.getName() + "：" + describe(e)
          + "（多半是这个用户没有该库的权限）";
    }
  }

  private static String hint(RuntimeConfig.Database db) {
    if ("127.0.0.1".equals(db.getHost()) || "localhost".equalsIgnoreCase(db.getHost())) {
      return "\n提示：容器里的 127.0.0.1 指容器自己，要填库所在机器的局域网 IP。";
    }
    return "";
  }

  private static Connection open(RuntimeConfig.Database db, String database) throws Exception {
    String url = "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true"
        .formatted(db.getHost(), db.getPort(), database == null ? "" : database)
        + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8&connectTimeout=8000&socketTimeout=15000";
    loadMysqlDriver();
    return DriverManager.getConnection(url, db.getUsername(), db.getPassword());
  }

  /**
   * 显式装驱动，不能省。{@code DriverManager} 初始化时用**系统类加载器**跑 ServiceLoader，
   * 而 fat jar 把 mysql-connector 放在 {@code BOOT-INF/lib/} 下，只有 Spring Boot 的
   * LaunchedClassLoader 看得见——于是安装页（它在 Spring 之前跑）直接 getConnection 会报
   * "No suitable driver found"。Spring 那条路不受影响：Hikari 自己按线程上下文类加载器
   * {@code Class.forName}，所以这个坑只在安装页出现。
   *
   * <p>{@code mvn test} 与 IDE 跑的是 exploded classpath，系统类加载器本来就看得见驱动，
   * 所以单测里这条分支永远是绿的——只有真跑一次 {@code java -jar} 才验得到。
   */
  static void loadMysqlDriver() throws ClassNotFoundException {
    Class.forName(MYSQL_DRIVER_CLASS, true, InstallServer.class.getClassLoader());
  }

  /** 桶读写各来一次：只判"配置非空"的话，端口填错要等第一条带图消息才暴露。 */
  String checkS3(RuntimeConfig.Storage storage) {
    String key = "pushbox-install-check/" + UUID.randomUUID();
    try (S3Client client = s3Client(storage)) {
      client.headBucket(b -> b.bucket(storage.getBucket()));
      client.putObject(b -> b.bucket(storage.getBucket()).key(key),
          RequestBody.fromString("pushbox install check", StandardCharsets.UTF_8));
      client.deleteObject(b -> b.bucket(storage.getBucket()).key(key));
      return null;
    } catch (AwsServiceException e) {
      return "对象存储连上了但拒绝了这次操作（%s）：%s"
          .formatted(e.statusCode() + " " + e.awsErrorDetails().errorCode(), e.getMessage());
    } catch (Exception e) {
      return "连不上对象存储：" + describe(e);
    }
  }

  private static S3Client s3Client(RuntimeConfig.Storage storage) {
    return S3Client.builder()
        .endpointOverride(java.net.URI.create(storage.getEndpoint().trim()))
        .region(Region.of(isBlank(storage.getRegion()) ? "us-east-1" : storage.getRegion().trim()))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(storage.getAccessKey(), storage.getSecretKey())))
        .serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(storage.isPathStyle())
            .build())
        .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(15)))
        .build();
  }

  private static String describe(Throwable e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      message = e.getClass().getSimpleName();
    }
    // 驱动会把整条 URL（含参数）塞进消息，这里只留第一段，免得安装页上出现一大坨没人读的东西。
    int newline = message.indexOf('\n');
    return newline > 0 ? message.substring(0, newline).trim() : message.trim();
  }

  private static boolean isBlank(String v) {
    return v == null || v.isBlank();
  }

  private static void respondJson(HttpExchange exchange, int status, Map<String, Object> body)
      throws IOException {
    respond(exchange, status, "application/json; charset=utf-8", Json.writeBytes(body));
  }

  private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
}
