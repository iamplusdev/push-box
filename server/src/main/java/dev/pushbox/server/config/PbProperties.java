package dev.pushbox.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** §11.1 的 pb.* 配置。字段与 yml 一一对应，未用到的段落（媒体抓取、S3 对象加密）留给 P1/P2 接线。 */
@ConfigurationProperties("pb")
public class PbProperties {

  private Ingest ingest = new Ingest();
  private Ws ws = new Ws();
  private Notify notify = new Notify();
  private Channels channels = new Channels();
  private Devices devices = new Devices();
  private Ratelimit ratelimit = new Ratelimit();
  private Storage storage = new Storage();
  private S3 s3 = new S3();
  private Media media = new Media();
  private Search search = new Search();
  private Retention retention = new Retention();
  private Admin admin = new Admin();
  private Security security = new Security();

  public Ingest getIngest() { return ingest; }
  public void setIngest(Ingest ingest) { this.ingest = ingest; }
  public Ws getWs() { return ws; }
  public void setWs(Ws ws) { this.ws = ws; }
  public Notify getNotify() { return notify; }
  public void setNotify(Notify notify) { this.notify = notify; }
  public Channels getChannels() { return channels; }
  public void setChannels(Channels channels) { this.channels = channels; }
  public Devices getDevices() { return devices; }
  public void setDevices(Devices devices) { this.devices = devices; }
  public Ratelimit getRatelimit() { return ratelimit; }
  public void setRatelimit(Ratelimit ratelimit) { this.ratelimit = ratelimit; }
  public Storage getStorage() { return storage; }
  public void setStorage(Storage v) { this.storage = v; }
  public S3 getS3() { return s3; }
  public void setS3(S3 s3) { this.s3 = s3; }
  public Media getMedia() { return media; }
  public void setMedia(Media media) { this.media = media; }
  public Search getSearch() { return search; }
  public void setSearch(Search search) { this.search = search; }
  public Retention getRetention() { return retention; }
  public void setRetention(Retention retention) { this.retention = retention; }
  public Admin getAdmin() { return admin; }
  public void setAdmin(Admin admin) { this.admin = admin; }
  public Security getSecurity() { return security; }
  public void setSecurity(Security security) { this.security = security; }

  /**
   * §4.4/§5.1 入站面。刻意没有 {@code require-https-in-prod} 这类开关：传输加密是 Caddy 的职责
   * （见 deploy/Caddyfile 的 tls + 301），应用既看不到真实的客户端协议，也没有能力拒绝，
   * 留一个看起来能管、实际没人读的键比不管更危险。
   */
  public static class Ingest {
    private String basePath = "/message";
    private int credentialFailLock = 5;
    private Duration lockWindow = Duration.ofMinutes(5);

    public String getBasePath() { return basePath; }
    public void setBasePath(String v) { this.basePath = v; }
    public int getCredentialFailLock() { return credentialFailLock; }
    public void setCredentialFailLock(int v) { this.credentialFailLock = v; }
    public Duration getLockWindow() { return lockWindow; }
    public void setLockWindow(Duration v) { this.lockWindow = v; }
  }

  public static class Ws {
    private int heartbeatSec = 25;
    private int frameMaxBytes = 32768;
    /**
     * §4.3 的帧数阈值，默认就是 core 里的 2^32。联调时可用
     * `--pb.ws.rotate-after-frames=3` 调小，好让密钥跨界这条路被真正跑到（探针 ⑬）。
     */
    private long rotateAfterFrames = dev.pushbox.core.crypto.FrameChannel.ROTATE_AFTER_FRAMES;
    /**
     * §5.3 补拉每批取多少条。默认 500，联调用 `--pb.ws.replay-batch=5` 调小，
     * 好让"落后条数 &gt; 一批"这条循环被真正跑到（探针 ㉑）——只发一批就回 resume_ok
     * 是个静默丢消息的 bug，正常配置下要攒够 501 条才看得见。
     */
    private int replayBatch = 500;
    /**
     * §5.3 "落后太多就别用 WS 重放，改走 REST 补拉"的阈值。联调用
     * `--pb.ws.gap-threshold=3` 调小，好让 resume_gap 这条路被真正跑到。
     */
    private int gapThreshold = 2_000;

    public int getHeartbeatSec() { return heartbeatSec; }
    public void setHeartbeatSec(int v) { this.heartbeatSec = v; }
    public int getFrameMaxBytes() { return frameMaxBytes; }
    public void setFrameMaxBytes(int v) { this.frameMaxBytes = v; }
    public long getRotateAfterFrames() { return rotateAfterFrames; }
    public void setRotateAfterFrames(long v) { this.rotateAfterFrames = v; }
    public int getReplayBatch() { return replayBatch; }
    public void setReplayBatch(int v) { this.replayBatch = Math.max(1, v); }
    public int getGapThreshold() { return gapThreshold; }
    public void setGapThreshold(int v) { this.gapThreshold = Math.max(1, v); }
  }

  public static class Notify {
    private int summaryChars = 60;
    private Duration mediaWait = Duration.ofSeconds(3);

    public int getSummaryChars() { return summaryChars; }
    public void setSummaryChars(int v) { this.summaryChars = v; }
    public Duration getMediaWait() { return mediaWait; }
    public void setMediaWait(Duration v) { this.mediaWait = v; }
  }

  public static class Channels {
    private int channelNoLength = 8;
    private String defaultMaxLevel = "error";
    private int defaultMaxImages = 4;
    private int secretBytes = 16;

    public int getChannelNoLength() { return channelNoLength; }
    public void setChannelNoLength(int v) { this.channelNoLength = v; }
    public String getDefaultMaxLevel() { return defaultMaxLevel; }
    public void setDefaultMaxLevel(String v) { this.defaultMaxLevel = v; }
    public int getDefaultMaxImages() { return defaultMaxImages; }
    public void setDefaultMaxImages(int v) { this.defaultMaxImages = v; }
    public int getSecretBytes() { return secretBytes; }
    public void setSecretBytes(int v) { this.secretBytes = v; }
  }

  public static class Devices {
    private int maxPerGroup = 5;

    public int getMaxPerGroup() { return maxPerGroup; }
    public void setMaxPerGroup(int v) { this.maxPerGroup = v; }
  }

  /**
   * 入站面的三维限流。抓图频率不在这里（{@code pb.media.fetch-rate-per-channel-per-min}）：
   * 消息限流管的是"这个频道每分钟能提交几条"，抓图限流管的是"服务端每分钟能替它去取几张"，
   * 两个数量级、两套失败后果，合并成一个键迟早要拆。
   */
  public static class Ratelimit {
    private int perChannelPerMin = 60;
    private int burst = 20;
    private int perIpPerMin = 120;
    private int perDevicePerMin = 300;

    public int getPerChannelPerMin() { return perChannelPerMin; }
    public void setPerChannelPerMin(int v) { this.perChannelPerMin = v; }
    public int getBurst() { return burst; }
    public void setBurst(int v) { this.burst = v; }
    public int getPerIpPerMin() { return perIpPerMin; }
    public void setPerIpPerMin(int v) { this.perIpPerMin = v; }
    public int getPerDevicePerMin() { return perDevicePerMin; }
    public void setPerDevicePerMin(int v) { this.perDevicePerMin = v; }
  }

  public static class Storage {
    /** 存储后端类型：s3（默认）| local */
    private String type = "s3";
    /** 本地存储根目录（仅当 type=local 时生效） */
    private String localPath = "./data/media";

    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String v) { this.localPath = v; }
  }

  public static class S3 {
    private String endpoint;
    private String region = "us-east-1";
    private String bucket = "pushbox";
    private String accessKey;
    private String secretKey;
    private boolean pathStyle = true;
    private Duration deleteGrace = Duration.ofHours(168);
    /** §4.5 桶内形态：none（明文）| sse-c（每对象客户钥，钥本身用 master-key 封装后落库） */
    private String objectEncrypt = "none";
    /** 随机主钥：base64 的 32 字节。与 {@link #masterPassphrase} 二选一。 */
    private String masterKey;
    /** 口令派生 KEK：与 {@link #masterKey} 二选一；盐/KDF 参数/KCV 存 app_state。 */
    private String masterPassphrase;

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
    public Duration getDeleteGrace() { return deleteGrace; }
    public void setDeleteGrace(Duration v) { this.deleteGrace = v; }
    public String getObjectEncrypt() { return objectEncrypt; }
    public void setObjectEncrypt(String v) { this.objectEncrypt = v; }
    public String getMasterKey() { return masterKey; }
    public void setMasterKey(String v) { this.masterKey = v; }
    public String getMasterPassphrase() { return masterPassphrase; }
    public void setMasterPassphrase(String v) { this.masterPassphrase = v; }
  }

  public static class Media {
    private boolean enabled = true;
    private int maxImages = 4;
    private long maxImageBytes = 10L * 1024 * 1024;
    private long maxTotalBytes = 32L * 1024 * 1024;
    private Duration timeout = Duration.ofSeconds(10);
    private int maxRedirects = 2;
    private boolean allowPrivate = false;
    private java.util.List<String> allowHosts = new java.util.ArrayList<>();
    private int concurrency = 4;
    /**
     * §4.5 抓取队列的**等待位**上限（不含正在跑的 concurrency 个）。一个任务 = 一条消息的全部图片，
     * 所以这个数就是"最多允许多少条带图消息排队抓取"。无界队列会在突发流量时一路堆内存，
     * 而且队列越长，D24 的合并窗口到期时越多消息还挂在 pending 上，等于用内存换了一个更差的通知体验。
     */
    private int queueCapacity = 256;
    private int retryMax = 3;
    private int thumbLongEdge = 512;
    /** §14：频道级图片抓取频率限制（每分钟），默认 30 次/分钟。 */
    private int fetchRatePerChannelPerMin = 30;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public int getMaxImages() { return maxImages; }
    public void setMaxImages(int v) { this.maxImages = v; }
    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long v) { this.maxImageBytes = v; }
    public long getMaxTotalBytes() { return maxTotalBytes; }
    public void setMaxTotalBytes(long v) { this.maxTotalBytes = v; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration v) { this.timeout = v; }
    public int getMaxRedirects() { return maxRedirects; }
    public void setMaxRedirects(int v) { this.maxRedirects = v; }
    public boolean isAllowPrivate() { return allowPrivate; }
    public void setAllowPrivate(boolean v) { this.allowPrivate = v; }
    public java.util.List<String> getAllowHosts() { return allowHosts; }
    public void setAllowHosts(java.util.List<String> v) { this.allowHosts = v; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int v) { this.concurrency = v; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int v) { this.queueCapacity = v; }
    public int getRetryMax() { return retryMax; }
    public void setRetryMax(int v) { this.retryMax = v; }
    public int getThumbLongEdge() { return thumbLongEdge; }
    public void setThumbLongEdge(int v) { this.thumbLongEdge = v; }
    public int getFetchRatePerChannelPerMin() { return fetchRatePerChannelPerMin; }
    public void setFetchRatePerChannelPerMin(int v) { this.fetchRatePerChannelPerMin = v; }
  }

  /** §5.2 GET /api/v1/search：ngram_token_size=2 时单字查询必须能落回 LIKE。 */
  public static class Search {
    private boolean fulltext = true;
    private int minQueryLen = 1;
    private int maxLimit = 200;

    public boolean isFulltext() { return fulltext; }
    public void setFulltext(boolean v) { this.fulltext = v; }
    public int getMinQueryLen() { return minQueryLen; }
    public void setMinQueryLen(int v) { this.minQueryLen = v; }
    public int getMaxLimit() { return maxLimit; }
    public void setMaxLimit(int v) { this.maxLimit = v; }
  }

  /** D3：消息永不自动清理，这里只有单条体积上限。 */
  public static class Retention {
    private int maxMessageBytes = 32768;

    public int getMaxMessageBytes() { return maxMessageBytes; }
    public void setMaxMessageBytes(int v) { this.maxMessageBytes = v; }
  }

  /**
   * Web 管理台：登录口令与 JWT 签名钥分开设，是为了两者都能各自轮换——
   * 同一个值既当口令又当 HMAC 钥时，改口令就等于把所有已发 token 一起作废、
   * 而换签名钥又等于改口令，运维只能选一个，于是两个都长期不换。
   * 口令留空即禁用管理台（登录返回 403）。
   */
  public static class Admin {
    private String secret;
    /** HS256 签名钥，只用于签/验 JWT；管理台启用时必填且至少 32 字节（见 AdminAuthController）。 */
    private String jwtSecret;

    public String getSecret() { return secret; }
    public void setSecret(String v) { this.secret = v; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String v) { this.jwtSecret = v; }
  }

  /**
   * §5.2 运维端点（/healthz、/actuator/**）的准入。默认只放行环回：
   * 曾经把 {@code isSiteLocalAddress()} 当"内网"，等于整个 docker 桥接网段（172.18.x.x）
   * 都能匿名读指标，而 /actuator 这一侧没有任何鉴权。要放开别的网段就显式列 CIDR。
   */
  public static class Security {
    /** 额外放行的 CIDR，逗号分隔（如 {@code 172.16.0.0/12}）；写错当场拒绝启动。 */
    private java.util.List<String> internalCidrs = new java.util.ArrayList<>();

    public java.util.List<String> getInternalCidrs() { return internalCidrs; }
    public void setInternalCidrs(java.util.List<String> v) {
      this.internalCidrs = v == null ? new java.util.ArrayList<>() : v;
    }
  }
}
