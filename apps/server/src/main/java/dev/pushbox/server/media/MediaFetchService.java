package dev.pushbox.server.media;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.model.MediaStatus;
import dev.pushbox.core.protocol.Json;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.AbuseControlService;
import jakarta.annotation.PreDestroy;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * §4.5 抓取阶段：外链 → SSRF 校验 → 下载（体积/超时上限、逐跳重校验）→ 类型复验 → sha256 组内去重
 * → 缩略图 → PutObject → 回写 media_objects 与 messages.media_status。
 * 失败一律记终态：消息绝不能因为抓不到图而不发（客户端该图位显示"图片已失效"）。
 */
@Component
public class MediaFetchService {

  private static final Logger log = LoggerFactory.getLogger(MediaFetchService.class);
  private static final Set<String> ALLOWED_MIME = Set.of("image/jpeg", "image/png", "image/gif",
      "image/webp");
  /** 不少图床对图片也只写这个通用类型，此时以魔数为准。 */
  private static final String GENERIC_MIME = "application/octet-stream";
  private static final int CHUNK = 16 * 1024;

  /** §5.2 队列满时写进 media_objects.error 的终态码：客户端照 failed 画"图片已失效"。 */
  public static final String QUEUE_FULL = "queue_full";
  public static final String RATE_LIMITED = "rate_limited";

  private final PbStore store;
  private final MediaObjects objects;
  private final PbProperties.Media media;
  private final SsrfGuard guard;
  private final AbuseControlService abuse;
  private final ThreadPoolExecutor workers;
  /** 只在任务运行期间占用，条目数上限就是抓取池大小，不需要额外的过期策略。 */
  private final Set<String> avatarInFlight = ConcurrentHashMap.newKeySet();

  /** 一次跳的 GET。默认走钉定 IP 的真客户端，用例可以换成只回脚本响应的实现。 */
  @FunctionalInterface
  interface Transport {

    Raw get(SsrfGuard.Target target, RequestConfig config, long maxBytes) throws IOException;
  }

  Transport transport = this::httpGet;

  public MediaFetchService(PbStore store, MediaObjects objects, PbProperties props,
      AbuseControlService abuse) {
    this.store = store;
    this.objects = objects;
    this.media = props.getMedia();
    this.abuse = abuse;
    this.guard = new SsrfGuard(media);
    int concurrency = Math.max(1, media.getConcurrency());
    // 有界队列（§4.5）：newFixedThreadPool 的无界队列会把突发流量整个堆进内存，
    // 而且排得越久，合并窗口到期时越多消息还挂在 pending 上——通知体验和水位同时变差。
    this.workers = new ThreadPoolExecutor(concurrency, concurrency, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(Math.max(1, media.getQueueCapacity())), runnable -> {
          Thread thread = new Thread(runnable, "media-fetch");
          thread.setDaemon(true);
          return thread;
        });
  }

  /** §5.2 队列积压指标：抓取排了多久看不看得到，取决于这里能不能读到队列长度。 */
  public int queueDepth() {
    return workers.getQueue().size();
  }

  /**
   * §4.5 合并窗口的回调：每张图片到终态（写库之后）触发一次。
   * 窗口该在什么时候先发正文由调用方决定，抓取这边只管"这张图有结果了"。
   */
  public interface SlotHook {

    void onSlotSettled(int idx);
  }

  /**
   * 抓取线程池的入口：合并窗口要在抓取之间插入下发时机，所以由协调器提交任务。
   * **队列满时绝不向调用方抛 RejectedExecutionException**——提交方是受理线程，抛出去就等于
   * 一条已入库的消息永远没有第一帧；降级动作（记终态、照常下发）必须由 onRejected 当场做完。
   */
  public void submit(Runnable task, Runnable onRejected) {
    try {
      workers.execute(task);
    } catch (RejectedExecutionException e) {
      log.warn("抓取队列已满（深度 {} / 容量 {}），本次抓取改走降级", queueDepth(),
          media.getQueueCapacity());
      onRejected.run();
    }
  }

  /** 入站受理后的异步入口：200 已经回给第三方，抓取不能再拖它的超时。 */
  public void enqueue(String msgId) {
    if (!media.isEnabled()) {
      return;
    }
    submit(() -> {
      try {
        run(msgId, null);
      } catch (RuntimeException e) {
        log.warn("抓取任务异常 msg_id={}: {}", msgId, e.toString());
      }
    }, () -> failPending(msgId, QUEUE_FULL));
  }

  /**
   * 队列满的降级：把这条消息还挂在 pending 上的槽位全记成终态并回写聚合状态。
   * 语义与"抓取失败"完全一致（消息照发、图位显示失效），只是原因是服务端自己排满了。
   */
  public void failPending(String msgId, String code) {
    long now = System.currentTimeMillis();
    for (Row row : store.pendingMedia(msgId)) {
      store.markMediaFailed(msgId, intValue(row, "idx"), code, now);
    }
    refresh(msgId);
  }

  /** 同步抓完一条消息的全部待抓外链，返回聚合后的消息级 media_status。 */
  public String fetch(String msgId) {
    return run(msgId, null);
  }

  /** 带回调的抓取：D24 的合并窗口靠它在图片到齐后原地补发 media_ready。 */
  public String fetch(String msgId, SlotHook hook) {
    return run(msgId, hook);
  }

  /**
   * §4.5 的头像转存：第三方自报的头像 URL 走**同一道** SSRF 闸与同一套魔数复验，
   * 内联 data: 图则完全不出网（只解码 + 复验）。只在入站时提交，抓取失败不重试也不影响消息 ——
   * 头像是装饰字段，客户端拿不到 key 就画首字占位。
   * 同一个 (频道, 发送者) 只允许一个任务在跑，否则一条 webhook 打过来的十条消息会抓十次同一张图。
   */
  public void enqueueAvatar(String channelNo, String senderId, AvatarSource source) {
    if (!media.isEnabled() || source == null) {
      return;
    }
    String marker = channelNo + "/" + MediaObjects.avatarToken(senderId);
    if (!avatarInFlight.add(marker)) {
      return;
    }
    submit(() -> {
      try {
        storeAvatar(channelNo, senderId, source);
      } catch (RuntimeException e) {
        log.warn("头像转存异常 channel={} sender={}: {}", channelNo, senderId, e.toString());
      } finally {
        avatarInFlight.remove(marker);
      }
      // 排不上队就直接丢标记：头像是装饰字段，等第三方下次自报头像时自然会再试
    }, () -> avatarInFlight.remove(marker));
  }

  /** @return 落库的对象键；null 表示这次没存成（来源列已经写好，等第三方换头像时自然再试一次）。 */
  String storeAvatar(String channelNo, String senderId, AvatarSource source) {
    String token = MediaObjects.avatarToken(senderId);
    Row current = store.findSenderAvatar(channelNo, senderId);
    if (current != null && source.source().equals(current.getString("avatar_url"))
        && current.getString("avatar_key") != null) {
      return current.getString("avatar_key");
    }
    byte[] body;
    String mime;
    try {
      if (source.needsFetch()) {
        long deadline = System.currentTimeMillis()
            + media.getTimeout().toMillis() * (1L + Math.max(0, media.getMaxRedirects()));
        Downloaded downloaded = download(source.url(), AvatarSource.MAX_AVATAR_BYTES, deadline);
        body = downloaded.body();
        mime = sniff(downloaded);
      } else {
        // 内联图的 Content-Type 也是第三方自报的，照样由魔数说了算
        body = source.inlineBytes();
        mime = sniff(new Downloaded(body, source.declaredMime()));
      }
    } catch (MediaFetchException e) {
      // 完整 URL 可能带签名 query，日志只留主机名（§14 脱敏）
      log.warn("头像转存失败 channel={} sender={} error={} host={}", channelNo, senderId, e.code,
          source.url() == null ? "-" : hostOf(source.url()));
      return null;
    }
    AvatarImage.Normalized avatar = AvatarImage.normalize(body, mime, media.getAvatarSize());
    String key = MediaObjects.avatarKey(channelNo, token);
    // 换头像是在同一个对象键上覆盖写：延用库里那把客户钥，读侧就不会撞上"新字节配旧封装钥"的空窗
    MediaObjects.PutResult stored = objects.put(key, avatar.body(), avatar.mime(),
        objects.reuseKey(current == null ? null : current.getBytes("sse_key_wrapped")));
    store.setSenderAvatarKey(channelNo, senderId, key, stored.encMode(), stored.sseKeyWrapped());
    log.info("头像已转存 channel={} sender={} mime={} bytes={} rawBytes={} {}x{}", channelNo, senderId,
        avatar.mime(), avatar.body().length, body.length, avatar.width(), avatar.height());
    return key;
  }

  private String run(String msgId, SlotHook hook) {
    List<Row> pending = store.pendingMedia(msgId);
    if (pending.isEmpty()) {
      return refresh(msgId);
    }
    // 抓取预算与合并窗口无关：窗口只决定"什么时候先发正文"，不能反过来掐死抓取
    long deadline = System.currentTimeMillis() + media.getTimeout().toMillis() * pending.size();
    long budget = media.getMaxTotalBytes();
    for (Row row : pending) {
      int idx = intValue(row, "idx");
      // §14：抓图比发消息贵，按频道单独限频。超频只把这一格标 failed（消息与通知照发），
      // 与"抓不到图"同一口径，绝不因为限流把整条消息卡在永远没有第一帧。
      if (!abuse.imageFetchAllowed(row.getString("channel_no"))) {
        store.markMediaFailed(msgId, idx, RATE_LIMITED, System.currentTimeMillis());
        if (hook != null) {
          hook.onSlotSettled(idx);
        }
        continue;
      }
      long size = fetchOne(msgId, idx, row.getString("group_id"), row.getString("source_url"),
          Math.min(media.getMaxImageBytes(), Math.max(1L, budget)), deadline);
      budget -= size;
      if (hook != null) {
        hook.onSlotSettled(idx);
      }
    }
    return refresh(msgId);
  }

  /** 抓一张图并写库，返回它占用的字节数（失败为 0）；失败是终态，绝不向上抛。 */
  private long fetchOne(String msgId, int idx, String groupId, String sourceUrl, long maxBytes,
      long deadline) {
    try {
      Stored stored = storeImage(msgId, idx, groupId, sourceUrl, maxBytes, deadline);
      log.info("图片已转存 msg_id={} idx={} mime={} bytes={} reused={}", msgId, idx, stored.mime(),
          stored.size(), stored.reused());
      return stored.size();
    } catch (MediaFetchException e) {
      store.markMediaFailed(msgId, idx, e.code, System.currentTimeMillis());
      // 完整 source_url 可能带签名 query，日志只留主机名（§14 脱敏）
      log.warn("图片抓取失败 msg_id={} idx={} error={} host={}", msgId, idx, e.code,
          hostOf(sourceUrl));
      return 0;
    } catch (RuntimeException e) {
      store.markMediaFailed(msgId, idx, MediaFetchException.UNREACHABLE, System.currentTimeMillis());
      log.warn("图片抓取异常 msg_id={} idx={} host={}: {}", msgId, idx, hostOf(sourceUrl),
          e.getMessage());
      if (log.isDebugEnabled()) {
        log.debug("完整堆栈", e);
      }
      return 0;
    }
  }

  /** 全部到终态后把聚合状态与对象键列表写回 messages；下发帧与 history 都读这一份。 */
  private String refresh(String msgId) {
    List<Row> rows = store.listMedia(msgId);
    List<String> statuses = new ArrayList<>(rows.size());
    List<String> keys = new ArrayList<>(rows.size());
    for (Row row : rows) {
      statuses.add(row.getString("status"));
      String key = row.getString("object_key");
      if (key != null && !key.isBlank()) {
        keys.add(key);
      }
    }
    MediaStatus aggregate = MediaStatus.aggregate(statuses);
    store.updateMediaStatus(msgId, aggregate.wire(), Json.write(keys));
    return aggregate.wire();
  }

  private Stored storeImage(String msgId, int idx, String groupId, String sourceUrl, long maxBytes,
      long deadline) throws MediaFetchException {
    Downloaded downloaded = download(sourceUrl, maxBytes, deadline);
    String mime = sniff(downloaded);
    String sha256 = sha256Hex(downloaded.body());

    Row reusable = store.reusableMedia(groupId, sha256);
    long now = System.currentTimeMillis();
    if (reusable != null) {
      // 复用意味着这个槽位指向**同一个对象键**，加密形态必须一起复制，否则读侧拿不到那把客户钥
      store.markMediaReady(msgId, idx, reusable.getString("object_key"),
          reusable.getString("thumb_key"), reusable.getString("mime"), reusable.getInt("width"),
          reusable.getInt("height"), longValue(reusable, "size"), sha256,
          reusable.getString("enc_mode"), reusable.getBytes("sse_key_wrapped"), now);
      return new Stored(reusable.getString("mime"), longValue(reusable, "size"), true);
    }

    String objectKey = MediaObjects.fullKey(msgId, idx);
    // 一个槽位的原图与缩略图共用同一把客户钥，media_objects 那一列 sse_key_wrapped 才够用
    byte[] dataKey = objects.newDataKey();
    MediaObjects.PutResult full = objects.put(objectKey, downloaded.body(), mime, dataKey);

    Image image = decode(downloaded.body());
    String thumbKey = null;
    if (image.thumb() != null) {
      thumbKey = MediaObjects.thumbKey(msgId, idx);
      objects.put(thumbKey, image.thumb(), "image/jpeg", dataKey);
    }
    store.markMediaReady(msgId, idx, objectKey, thumbKey, mime, image.width(), image.height(),
        downloaded.body().length, sha256, full.encMode(), full.sseKeyWrapped(), now);
    return new Stored(mime, downloaded.body().length, false);
  }

  private Downloaded download(String sourceUrl, long maxBytes, long deadline)
      throws MediaFetchException {
    SsrfGuard.Target target = guard.open(sourceUrl);
    int hops = 0;
    while (true) {
      long remaining = Math.max(1L, deadline - System.currentTimeMillis());
      RequestConfig config = RequestConfig.custom()
          .setConnectTimeout(Timeout.ofSeconds(5))
          .setResponseTimeout(Timeout.ofMilliseconds(Math.min(media.getTimeout().toMillis(), remaining)))
          .setRedirectsEnabled(false)
          .build();
      try {
        Raw raw = transport.get(target, config, maxBytes);
        if (raw.status() >= 300 && raw.status() < 400 && raw.location() != null) {
          if (hops >= media.getMaxRedirects()) {
            throw new MediaFetchException(MediaFetchException.SSRF_BLOCKED, "重定向超过 " + hops + " 跳");
          }
          hops++;
          target = guard.redirect(target, raw.location());
          continue;
        }
        if (raw.status() != 200) {
          throw new MediaFetchException("http_" + raw.status(), "远端返回 " + raw.status());
        }
        return new Downloaded(raw.body(), raw.contentType());
      } catch (IOException e) {
        throw translate(e);
      }
    }
  }

  private Raw httpGet(SsrfGuard.Target target, RequestConfig config, long maxBytes)
      throws IOException {
    try (CloseableHttpClient client = pinnedClient(target, config)) {
      return client.execute(new HttpGet(target.uri()), response -> read(response, maxBytes));
    }
  }

  /** §4.5：连接目标钉成已通过校验的 IP，Host 头与 SNI/证书校验仍用原域名。 */
  private CloseableHttpClient pinnedClient(SsrfGuard.Target target, RequestConfig config) {
    InetAddress[] pinned = target.addresses().toArray(new InetAddress[0]);
    DnsResolver resolver = new DnsResolver() {
      @Override
      public InetAddress[] resolve(String host) {
        return pinned;
      }

      @Override
      public String resolveCanonicalHostname(String host) {
        return host;
      }
    };
    // 每次抓取都新建连接管理器：不同 URL 的钉定 IP 不能共用一条池化连接
    return HttpClients.custom()
        .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(resolver)
            .build())
        .setDefaultRequestConfig(config)
        .disableRedirectHandling()
        .disableCookieManagement()
        .build();
  }

  private static Raw read(ClassicHttpResponse response, long maxBytes) throws IOException {
    int status = response.getCode();
    HttpEntity entity = response.getEntity();
    Header contentTypeHeader = response.getFirstHeader("Content-Type");
    if (status >= 300 && status < 400) {
      Header location = response.getFirstHeader("Location");
      EntityUtils.consumeQuietly(entity);
      return new Raw(status, location == null ? null : location.getValue(), null, null);
    }
    if (status != 200) {
      EntityUtils.consumeQuietly(entity);
      return new Raw(status, null, null, null);
    }
    if (entity == null) {
      throw new MediaFetchException(MediaFetchException.BAD_TYPE, "响应没有实体");
    }
    long declared = entity.getContentLength();
    if (declared > maxBytes) {
      throw new MediaFetchException(MediaFetchException.TOO_LARGE, "声明体积 " + declared + " 超过上限");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxBytes, 512 * 1024L));
    byte[] buffer = new byte[CHUNK];
    long total = 0;
    try (InputStream in = entity.getContent()) {
      int n;
      while ((n = in.read(buffer)) > 0) {
        total += n;
        if (total > maxBytes) {
          throw new MediaFetchException(MediaFetchException.TOO_LARGE, "实际体积超过上限");
        }
        out.write(buffer, 0, n);
      }
    }
    if (total == 0) {
      throw new MediaFetchException(MediaFetchException.BAD_TYPE, "空响应");
    }
    return new Raw(status, null, contentTypeHeader == null ? null : contentTypeHeader.getValue(),
        out.toByteArray());
  }

  /** Content-Type 白名单 + 魔数复验：两者都得允许且一致，SVG（可嵌脚本）在第一关就被拒。 */
  private static String sniff(Downloaded downloaded) throws MediaFetchException {
    String byHeader = normalize(downloaded.contentType());
    if (byHeader != null && !byHeader.equals(GENERIC_MIME) && !ALLOWED_MIME.contains(byHeader)) {
      throw new MediaFetchException(MediaFetchException.BAD_TYPE, "Content-Type " + byHeader);
    }
    String byMagic = magicMime(downloaded.body());
    if (byMagic == null) {
      throw new MediaFetchException(MediaFetchException.BAD_TYPE, "魔数不是允许的图片类型");
    }
    if (byHeader != null && !GENERIC_MIME.equals(byHeader) && !byHeader.equals(byMagic)) {
      throw new MediaFetchException(MediaFetchException.BAD_TYPE,
          "Content-Type " + byHeader + " 与魔数 " + byMagic + " 不一致");
    }
    return byMagic;
  }

  private static String normalize(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return null;
    }
    String value = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return value.isEmpty() ? null : "image/jpg".equals(value) ? "image/jpeg" : value;
  }

  private static String magicMime(byte[] data) {
    if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N'
        && data[3] == 'G') {
      return "image/png";
    }
    if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8
        && (data[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') {
      return "image/gif";
    }
    if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
        && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
      return "image/webp";
    }
    return null;
  }

  /**
   * 尺寸与缩略图共用一次解码。JDK 的 ImageIO 读不了 WebP，这类图照常转存、只是没有缩略图，
   * 客户端取 thumb 失败会回落原图（挂 WebP 解码器属于 P2 的抓取加固）。
   */
  private Image decode(byte[] data) {
    BufferedImage image;
    try {
      image = ImageIO.read(new ByteArrayInputStream(data));
    } catch (IOException | RuntimeException e) {
      image = null;
    }
    if (image == null) {
      return new Image(null, null, null);
    }
    int width = image.getWidth();
    int height = image.getHeight();
    int longEdge = media.getThumbLongEdge();
    if (longEdge <= 0 || Math.max(width, height) <= longEdge) {
      return new Image(width, height, null);
    }
    double scale = (double) longEdge / Math.max(width, height);
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Thumbnails.of(opaque(image))
          .size(Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)))
          .outputQuality(0.8)
          .outputFormat("jpg")
          .toOutputStream(out);
      return new Image(width, height, out.toByteArray());
    } catch (IOException | RuntimeException e) {
      log.warn("缩略图生成失败，只保留原图: {}", e.toString());
      return new Image(width, height, null);
    }
  }

  /** JPEG 没有 Alpha 通道，带透明的原图先铺白底，否则缩略图整片发黑。 */
  private static BufferedImage opaque(BufferedImage image) {
    if (!image.getColorModel().hasAlpha()) {
      return image;
    }
    BufferedImage flat = new BufferedImage(image.getWidth(), image.getHeight(),
        BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = flat.createGraphics();
    graphics.drawImage(image, 0, 0, Color.WHITE, null);
    graphics.dispose();
    return flat;
  }

  /** HttpClient 会把 handler 抛出的异常再包一层，这里还原成抓取终态码。 */
  private static MediaFetchException translate(IOException e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      if (t instanceof MediaFetchException mediaException) {
        return mediaException;
      }
      if (t instanceof SocketTimeoutException || t instanceof ConnectException) {
        return new MediaFetchException(MediaFetchException.TIMEOUT, t.getClass().getSimpleName());
      }
      if (t instanceof UnknownHostException) {
        return new MediaFetchException(MediaFetchException.SSRF_BLOCKED, "DNS 失败");
      }
    }
    return new MediaFetchException(MediaFetchException.UNREACHABLE, e.getClass().getSimpleName());
  }

  private static String sha256Hex(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 没有 SHA-256", e);
    }
  }

  private static String hostOf(String url) {
    try {
      return new URI(url).getHost();
    } catch (URISyntaxException | IllegalArgumentException e) {
      return "?";
    }
  }

  private static int intValue(Row row, String column) {
    Integer value = row.getInt(column);
    return value == null ? 0 : value;
  }

  private static long longValue(Row row, String column) {
    Long value = row.getLong(column);
    return value == null ? 0L : value;
  }

  @PreDestroy
  void shutdown() {
    workers.shutdown();
    try {
      workers.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  record Raw(int status, String location, String contentType, byte[] body) {}

  private record Downloaded(byte[] body, String contentType) {}

  private record Stored(String mime, long size, boolean reused) {}

  private record Image(Integer width, Integer height, byte[] thumb) {}
}
