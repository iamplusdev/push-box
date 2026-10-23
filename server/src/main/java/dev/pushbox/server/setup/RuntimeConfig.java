package dev.pushbox.server.setup;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.pushbox.core.protocol.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份 JSON 就是全部配置。安装页写它、服务端读它、启动后同步进 {@code app_state}。
 *
 * <p>没有环境变量这一层：一个键只有一个来源，改配置就是改这个文件（或在安装页/管理台里改），
 * 不再需要记「这个值到底来自 yml 默认值、env 还是命令行」。文件位置固定为工作目录下的
 * {@code data/config/pushbox.json}，容器里就是 {@code /app/data/config/pushbox.json}——
 * 与媒体、日志同属 {@code /app/data} 这一个持久化目录，NAS 表单只有一行要填。
 *
 * <p>口令与密钥在这个文件里是明文。这是刻意的：生产部署一定要有可复核的凭据落点，
 * 而"只从环境变量读"换来的是一堆 {@code __SET_ME__} 占位值和一个拒绝启动的入口脚本，
 * 部署者仍然要把真值写在某个文件里（compose 的 environment、NAS 的表单），只是那个文件没人管权限。
 * 这里把落点收敛成一个文件，权限也只用管这一个：容器内 600，宿主机上属于运行用户。
 */
public class RuntimeConfig {

  /** 工作目录下的相对位置。容器 WORKDIR=/app，本机跑测试时是 server/。 */
  public static final String RELATIVE_PATH = "data/config/pushbox.json";

  /** 覆盖配置文件位置的属性键（{@code --pb.config-file=...} 或 {@code -Dpb.config-file=...}）。 */
  public static final String CONFIG_FILE_PROPERTY = "pb.config-file";

  private static final SecureRandom RANDOM = new SecureRandom();

  private int port = 7404;
  private Database database = new Database();
  private Storage storage = new Storage();
  private Admin admin = new Admin();
  private Security security = new Security();
  private Media media = new Media();

  public int getPort() { return port; }
  public void setPort(int v) { this.port = v; }
  public Database getDatabase() { return database; }
  public void setDatabase(Database v) { this.database = v == null ? new Database() : v; }
  public Storage getStorage() { return storage; }
  public void setStorage(Storage v) { this.storage = v == null ? new Storage() : v; }
  public Admin getAdmin() { return admin; }
  public void setAdmin(Admin v) { this.admin = v == null ? new Admin() : v; }
  public Security getSecurity() { return security; }
  public void setSecurity(Security v) { this.security = v == null ? new Security() : v; }
  public Media getMedia() { return media; }
  public void setMedia(Media v) { this.media = v == null ? new Media() : v; }

  public static class Database {
    private String host = "127.0.0.1";
    private int port = 3306;
    private String name = "pushbox";
    private String username = "pushbox";
    private String password = "";

    public String getHost() { return host; }
    public void setHost(String v) { this.host = v; }
    public int getPort() { return port; }
    public void setPort(int v) { this.port = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { this.password = v; }
  }

  public static class Storage {
    /** false=本地目录（默认，零依赖）；true=对象存储（RustFS/MinIO/OSS）。 */
    private boolean s3 = false;
    private String localPath = "data/media";
    private String endpoint = "";
    private String region = "us-east-1";
    private String bucket = "pushbox";
    private String accessKey = "";
    private String secretKey = "";
    private boolean pathStyle = true;
    /** true=每对象客户钥（SSE-C），KEK 由 {@link #passphrase} 或 {@link #masterKey} 派生。 */
    private boolean encrypted = false;
    private String passphrase = "";
    /** 高级项：base64 的 32 字节随机主钥，与口令二选一。留空即用口令。 */
    private String masterKey = "";

    public boolean isS3() { return s3; }
    public void setS3(boolean v) { this.s3 = v; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String v) { this.localPath = v; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String v) { this.endpoint = v; }
    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
    public String getBucket() { return bucket; }
    public void setBucket(String v) { this.bucket = v; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String v) { this.accessKey = v; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String v) { this.secretKey = v; }
    public boolean isPathStyle() { return pathStyle; }
    public void setPathStyle(boolean v) { this.pathStyle = v; }
    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean v) { this.encrypted = v; }
    public String getPassphrase() { return passphrase; }
    public void setPassphrase(String v) { this.passphrase = v; }
    public String getMasterKey() { return masterKey; }
    public void setMasterKey(String v) { this.masterKey = v; }
  }

  public static class Admin {
    /** 管理台登录口令；留空即管理台禁用（登录返回 403）。 */
    private String password = "";
    /** JWT 签名钥。留空时服务端自动生成并回写本文件，部署者不需要也不应该手填。 */
    private String jwtKey = "";

    public String getPassword() { return password; }
    public void setPassword(String v) { this.password = v; }
    public String getJwtKey() { return jwtKey; }
    public void setJwtKey(String v) { this.jwtKey = v; }
  }

  public static class Security {
    /** 运维端点（/healthz、/actuator/**）的额外放行网段，环回永远放行。 */
    private List<String> internalCidrs = new ArrayList<>();

    public List<String> getInternalCidrs() { return internalCidrs; }
    public void setInternalCidrs(List<String> v) {
      this.internalCidrs = v == null ? new ArrayList<>() : v;
    }
  }

  public static class Media {
    /**
     * 图片抓取的内网豁免名单，写法是 {@code host} 或 {@code host:port}，精确匹配（{@link
     * dev.pushbox.server.media.SsrfGuard} 不接受父段或后缀）。
     *
     * <p>默认空 = 回环/私网/CGNAT 一律拒绝。NAS 与内网自建图床正好全在被禁段里，所以这一项必须能填：
     * 少了它，内网部署唯一的表现就是每条图片都 {@code ssrf_blocked}，而表单上没有任何一处能改。
     * 与之相对的 {@code pb.media.allow-private}（整段放开）刻意不进表单——豁免按主机点名，
     * 不给"一键关掉 SSRF 校验"的开关。
     */
    private List<String> allowHosts = new ArrayList<>();

    public List<String> getAllowHosts() { return allowHosts; }
    public void setAllowHosts(List<String> v) {
      this.allowHosts = v == null ? new ArrayList<>() : v;
    }
  }

  // ---------- 文件 ----------

  /** 配置文件的位置：{@code pb.config-file} 覆盖，否则工作目录下的 {@value #RELATIVE_PATH}。 */
  public static Path locate(String override) {
    if (override != null && !override.isBlank()) {
      return Paths.get(override.trim()).toAbsolutePath().normalize();
    }
    return Paths.get(RELATIVE_PATH).toAbsolutePath().normalize();
  }

  public static boolean existsAt(Path path) {
    return Files.isRegularFile(path);
  }

  public static RuntimeConfig load(Path path) {
    try {
      RuntimeConfig config = Json.MAPPER.readValue(Files.readAllBytes(path), RuntimeConfig.class);
      return config == null ? new RuntimeConfig() : config;
    } catch (IOException e) {
      throw new UncheckedIOException("配置文件读不出来: " + path, e);
    }
  }

  public static RuntimeConfig loadOrDefault(Path path) {
    return existsAt(path) ? load(path) : new RuntimeConfig();
  }

  /** 原子落盘：先写同目录临时文件再 move，避免半截 JSON 被下一次启动读到。 */
  public void save(Path path) {
    try {
      Files.createDirectories(path.getParent());
      Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
      byte[] bytes = toJson().getBytes(StandardCharsets.UTF_8);
      Files.write(tmp, bytes);
      try {
        // 只有自己能读：这份文件里有库口令与管理台口令。
        Files.setPosixFilePermissions(tmp,
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        // Windows 没有 POSIX 权限位；本机上这份文件属于当前用户，够了。
      }
      Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("配置文件写不进去: " + path, e);
    }
  }

  public String toJson() {
    try {
      return Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this) + "\n";
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 随机 32 字节 base64，用作管理台 JWT 签名钥。 */
  public static String newJwtKey() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  /**
   * 补齐可以自动生成的部分，返回是否改动过（改动过就该回写文件）。
   * JWT 签名钥是生成，放行主机那一份是归一：{@link dev.pushbox.server.media.SsrfGuard} 拿小写后的
   * host 做**精确等值**比较，所以表单里写成 {@code IMG.lan} 或前后带空格就等于没填。
   */
  @JsonIgnore
  public boolean normalize() {
    boolean changed = false;
    if (isBlank(admin.getJwtKey())) {
      admin.setJwtKey(newJwtKey());
      changed = true;
    }
    List<String> hosts = new ArrayList<>();
    for (String host : media.getAllowHosts()) {
      if (host == null || host.isBlank()) {
        changed = true;
        continue;
      }
      String trimmed = host.trim().toLowerCase(java.util.Locale.ROOT);
      changed |= !trimmed.equals(host);
      hosts.add(trimmed);
    }
    if (changed) {
      media.setAllowHosts(hosts);
    }
    return changed;
  }

  /**
   * 映射到 Spring 属性。这里是「一份 JSON → 服务端各模块」的唯一翻译点：
   * {@code pb.*} 那套键一个都没对外暴露过，改字段名不会牵动 yml。
   */
  public Map<String, Object> toSpringProperties() {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("server.port", String.valueOf(port));
    p.put("spring.datasource.url", jdbcUrl());
    p.put("spring.datasource.username", nullToEmpty(database.getUsername()));
    p.put("spring.datasource.password", nullToEmpty(database.getPassword()));
    p.put("pb.storage.type", storage.isS3() ? "s3" : "local");
    p.put("pb.storage.local-path", blankTo(storage.getLocalPath(), "data/media"));
    p.put("pb.s3.endpoint", nullToEmpty(storage.getEndpoint()));
    p.put("pb.s3.region", blankTo(storage.getRegion(), "us-east-1"));
    p.put("pb.s3.bucket", blankTo(storage.getBucket(), "pushbox"));
    p.put("pb.s3.access-key", nullToEmpty(storage.getAccessKey()));
    p.put("pb.s3.secret-key", nullToEmpty(storage.getSecretKey()));
    p.put("pb.s3.path-style", String.valueOf(storage.isPathStyle()));
    p.put("pb.s3.object-encrypt", storage.isEncrypted() ? "sse-c" : "none");
    p.put("pb.s3.master-key", nullToEmpty(storage.getMasterKey()));
    p.put("pb.s3.master-passphrase", nullToEmpty(storage.getPassphrase()));
    p.put("pb.admin.secret", nullToEmpty(admin.getPassword()));
    p.put("pb.admin.jwt-secret", nullToEmpty(admin.getJwtKey()));
    p.put("pb.security.internal-cidrs", String.join(",", security.getInternalCidrs()));
    // Spring 的宽松绑定会把逗号分隔的字符串读成 List<String>，与 internal-cidrs 同一写法。
    p.put("pb.media.allow-hosts", String.join(",", media.getAllowHosts()));
    return p;
  }

  /** 库地址由三段拼出来，不再让人手填一整条 JDBC URL（那是最容易填错的一项）。 */
  public String jdbcUrl() {
    return "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true"
        .formatted(blankTo(database.getHost(), "127.0.0.1"), database.getPort(),
            blankTo(database.getName(), "pushbox"))
        + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
  }

  /** 数据库是否已经填到可以连：安装页用它决定是显示表单还是直接启动。 */
  @JsonIgnore
  public boolean isDatabaseConfigured() {
    return !isBlank(database.getHost()) && database.getPort() > 0
        && !isBlank(database.getName()) && !isBlank(database.getUsername())
        && !isBlank(database.getPassword());
  }

  private static boolean isBlank(String v) {
    return v == null || v.isBlank();
  }

  private static String nullToEmpty(String v) {
    return v == null ? "" : v;
  }

  private static String blankTo(String v, String fallback) {
    return isBlank(v) ? fallback : v.trim();
  }
}
