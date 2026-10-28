package dev.pushbox.server.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import dev.pushbox.core.id.Ulid;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.AbuseControlService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §4.5 抓取链路的真链路测试：真 MySQL + 真 RustFS + 真公网图床，不 mock。
 * 没有对象存储凭据时整类跳过（§13 要求 SSRF 与抓取用例都在 CI 之外可复跑）。
 */
@EnabledIf("dev.pushbox.server.test.TestConfig#objectStorageReady")
// 应用上下文里有 WebSocket 配置，它要真实的 ServletContext，所以只能起随机端口的内嵌容器
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaFetchServiceTest {

  private static final String CHANNEL = "TESTMEDI";
  private static final String GROUP = "test-media-group";
  private static final String PNG = "https://httpbin.org/image/png";
  private static final String BIG_JPEG = "https://picsum.photos/1200/800";
  private static final String WEBP = "https://httpbin.org/image/webp";

  @Autowired
  private PbStore store;
  @Autowired
  private MediaObjects objects;
  @Autowired
  private MediaFetchService fetcher;
  @Autowired
  private AbuseControlService abuse;

  private final List<String> messages = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (String msgId : messages) {
      for (int idx = 0; idx < 4; idx++) {
        objects.delete(MediaObjects.fullKey(msgId, idx));
        objects.delete(MediaObjects.thumbKey(msgId, idx));
      }
      // 测试自己清自己的数据，生产代码里不留专门的删除接口
      Db.updateBySql("DELETE FROM media_objects WHERE msg_id = ?", msgId);
      Db.updateBySql("DELETE FROM messages WHERE msg_id = ?", msgId);
    }
    messages.clear();
  }

  @Test
  @DisplayName("① 公网 PNG 抓取成功：对象进 RustFS、尺寸与 sha256 记账、messages 聚合为 ready")
  void fetchesPublicPng() {
    String msgId = message(PNG);

    assertThat(fetcher.fetch(msgId)).isEqualTo("ready");

    Row row = media(msgId, 0);
    assertThat(row.getString("status")).isEqualTo("ready");
    assertThat(row.getString("object_key")).isEqualTo(MediaObjects.fullKey(msgId, 0));
    assertThat(row.getString("mime")).isEqualTo("image/png");
    assertThat(row.getInt("width")).isPositive();
    assertThat(row.getLong("size")).isPositive();
    assertThat(row.getString("sha256")).hasSize(64);
    // 100x100 的图没超过 thumb-long-edge，不该生成缩略图
    assertThat(row.getString("thumb_key")).isNull();
    assertThat(objects.load(row.getString("object_key"), null).body()).hasSize(row.getLong("size").intValue());

    Row message = store.findMessageWithSender(msgId);
    assertThat(message.getString("media_status")).isEqualTo("ready");
    assertThat(message.getString("media_keys")).contains(MediaObjects.fullKey(msgId, 0));
  }

  @Test
  @DisplayName("② 超过长边的大图生成缩略图并单独入库")
  void generatesThumbnail() {
    String msgId = message(BIG_JPEG);

    assertThat(fetcher.fetch(msgId)).isEqualTo("ready");

    Row row = media(msgId, 0);
    assertThat(row.getString("mime")).isEqualTo("image/jpeg");
    assertThat(row.getInt("width")).isGreaterThan(512);
    String thumbKey = row.getString("thumb_key");
    assertThat(thumbKey).isEqualTo(MediaObjects.thumbKey(msgId, 0));
    byte[] thumb = objects.load(thumbKey, null).body();
    assertThat(thumb).isNotNull();
    assertThat(thumb.length).isLessThan(row.getLong("size").intValue());
    assertThat(objects.load(row.getString("object_key"), null)).isNotNull();
  }

  @Test
  @DisplayName("③ WebP 照常转存（JDK 解不了就不生成缩略图，客户端回落原图）")
  void storesWebpWithoutThumbnail() {
    String msgId = message(WEBP);

    assertThat(fetcher.fetch(msgId)).isEqualTo("ready");

    Row row = media(msgId, 0);
    assertThat(row.getString("mime")).isEqualTo("image/webp");
    assertThat(row.getString("thumb_key")).isNull();
    assertThat(objects.load(row.getString("object_key"), null)).isNotNull();
  }

  @Test
  @DisplayName("④ 远端 404 记终态 http_404，消息照样判 failed，不会卡住下发")
  void recordsHttpStatus() {
    String msgId = message("https://httpbin.org/status/404");

    assertThat(fetcher.fetch(msgId)).isEqualTo("failed");

    Row row = media(msgId, 0);
    assertThat(row.getString("status")).isEqualTo("failed");
    assertThat(row.getString("error")).isEqualTo("http_404");
    assertThat(row.getInt("fetch_attempts")).isEqualTo(1);
  }

  @Test
  @DisplayName("⑤ HTML 响应被 Content-Type 与魔数双重校验拦下，绝不落进桶里")
  void rejectsNonImage() {
    String msgId = message("https://httpbin.org/html");

    assertThat(fetcher.fetch(msgId)).isEqualTo("failed");

    Row row = media(msgId, 0);
    assertThat(row.getString("error")).isEqualTo("bad_type");
    assertThat(objects.exists(MediaObjects.fullKey(msgId, 0))).isFalse();
  }

  @Test
  @DisplayName("⑥ 私网与 CGNAT 外链一律 ssrf_blocked（含我们自己的 RustFS 地址）")
  void blocksPrivateNetworkUrls() {
    String loopback = message("http://127.0.0.1:8080/a.png");
    String metadata = message("http://169.254.169.254/latest/meta-data/");
    String cgnat = message("http://100.100.100.100:7101/pushbox/anything");

    assertThat(fetcher.fetch(loopback)).isEqualTo("failed");
    assertThat(fetcher.fetch(metadata)).isEqualTo("failed");
    assertThat(fetcher.fetch(cgnat)).isEqualTo("failed");

    assertThat(media(loopback, 0).getString("error")).isEqualTo("ssrf_blocked");
    assertThat(media(metadata, 0).getString("error")).isEqualTo("ssrf_blocked");
    assertThat(media(cgnat, 0).getString("error")).isEqualTo("ssrf_blocked");
  }

  @Test
  @DisplayName("⑦ 公网跳点重定向到内网：每一跳重校验，不会跟着打进 127.0.0.1")
  void blocksRedirectIntoPrivate() {
    // 这条路原先靠 httpbin.org 扮演"公网跳点"，由它回 302 指向内网。2026-10-28 起上游对
    // "重定向到 127.0.0.1"的请求直接回 403（防开放重定向），第一跳就收不到 Location，
    // 判据再也走不到第二跳。这里把网络那一层换成脚本化的 302：验的东西没变——
    // 第一跳的响应只用来取 Location，指到内网时第二跳必须被判 ssrf_blocked。
    // 首跳用公网字面 IP，SSRF 校验不做 DNS 解析，这条用例因此不碰外网。
    String msgId = message("https://1.1.1.1/a.png");
    MediaFetchService.Transport real = fetcher.transport;
    fetcher.transport = (target, config, maxBytes) ->
        new MediaFetchService.Raw(302, "http://127.0.0.1:9/a.png", null, null);
    try {
      assertThat(fetcher.fetch(msgId)).isEqualTo("failed");

      assertThat(media(msgId, 0).getString("error")).isEqualTo("ssrf_blocked");
    } finally {
      fetcher.transport = real;
    }
  }

  @Test
  @DisplayName("⑧ 单张体积上限与抓取超时都是真上限，不是摆设")
  void enforcesSizeAndTimeoutLimits() {
    // 两条上限分开设：共用 1s 超时的话，公网图床一慢就把 too_large 变成 timeout，测的是网络不是上限
    PbProperties sizeOnly = new PbProperties();
    sizeOnly.getMedia().setMaxImageBytes(4096);
    MediaFetchService smallBytes = new MediaFetchService(store, objects, sizeOnly, abuse);

    PbProperties timeoutOnly = new PbProperties();
    timeoutOnly.getMedia().setTimeout(Duration.ofSeconds(1));
    MediaFetchService quick = new MediaFetchService(store, objects, timeoutOnly, abuse);

    String tooLarge = message(PNG);
    String slow = message("https://httpbin.org/delay/5");

    assertThat(smallBytes.fetch(tooLarge)).isEqualTo("failed");
    assertThat(quick.fetch(slow)).isEqualTo("failed");

    assertThat(media(tooLarge, 0).getString("error")).isEqualTo("too_large");
    assertThat(media(slow, 0).getString("error")).isEqualTo("timeout");
    smallBytes.shutdown();
    quick.shutdown();
    quick.shutdown();
  }

  @Test
  @DisplayName("⑨ 同组内同一张图（sha256 相同）复用已有对象键，不重复转存")
  void deduplicatesBySha256() {
    String first = message(PNG);
    assertThat(fetcher.fetch(first)).isEqualTo("ready");

    String second = message(PNG);
    assertThat(fetcher.fetch(second)).isEqualTo("ready");

    assertThat(media(second, 0).getString("object_key"))
        .isEqualTo(media(first, 0).getString("object_key"));
    assertThat(media(second, 0).getString("sha256")).isEqualTo(media(first, 0).getString("sha256"));
  }

  @Test
  @DisplayName("⑩ 一图成功一图失败：消息聚合为 partial，media_keys 只收成功的那张")
  void partialWhenOneImageFails() {
    String msgId = message(PNG, "https://httpbin.org/status/500");

    assertThat(fetcher.fetch(msgId)).isEqualTo("partial");

    assertThat(media(msgId, 0).getString("status")).isEqualTo("ready");
    assertThat(media(msgId, 1).getString("status")).isEqualTo("failed");
    assertThat(store.findMessageWithSender(msgId).getString("media_status")).isEqualTo("partial");
    assertThat(store.findMessageWithSender(msgId).getString("media_keys"))
        .contains(MediaObjects.fullKey(msgId, 0))
        .doesNotContain(MediaObjects.fullKey(msgId, 1));
  }

  @Test
  @DisplayName("⑪ 队列有界：排不进去的任务走降级回调，绝不把 RejectedExecutionException 抛回受理线程")
  void rejectsExtraTasksIntoFallbackInsteadOfThrowing() {
    PbProperties bounded = new PbProperties();
    bounded.getMedia().setConcurrency(1);
    bounded.getMedia().setQueueCapacity(1);
    MediaFetchService oneSlot = new MediaFetchService(store, objects, bounded, abuse);
    try {
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch workerBusy = new CountDownLatch(1);
      AtomicBoolean thirdRejected = new AtomicBoolean();
      AtomicBoolean thirdRan = new AtomicBoolean();

      oneSlot.submit(() -> {
        workerBusy.countDown();
        awaitQuietly(release);
      }, () -> {});
      assertThat(awaitCounted(workerBusy)).as("唯一的 worker 必须真的开始跑，否则位子是队列占的").isTrue();
      // 一个占住 worker，一个占住唯一的等待位，第三个就没地方去了
      oneSlot.submit(() -> awaitQuietly(release), () -> {});

      oneSlot.submit(() -> thirdRan.set(true), () -> thirdRejected.set(true));

      assertThat(thirdRejected).isTrue();
      assertThat(thirdRan).isFalse();
      assertThat(oneSlot.queueDepth()).isEqualTo(1);

      release.countDown();
      assertThat(awaitQueueDrained(oneSlot)).isTrue();
    } finally {
      oneSlot.shutdown();
    }
  }

  @Test
  @DisplayName("⑫ 排不上队的降级把 pending 槽位记成 queue_full 终态：消息不留悬挂，桶里也不留半个字节")
  void failPendingSettlesSlotsAsQueueFull() {
    String msgId = message(PNG, "https://example.test/second.png");

    fetcher.failPending(msgId, MediaFetchService.QUEUE_FULL);

    assertThat(media(msgId, 0).getString("status")).isEqualTo("failed");
    assertThat(media(msgId, 0).getString("error")).isEqualTo("queue_full");
    assertThat(media(msgId, 1).getString("error")).isEqualTo("queue_full");
    assertThat(store.findMessageWithSender(msgId).getString("media_status")).isEqualTo("failed");
    assertThat(store.findMessageWithSender(msgId).getString("media_keys")).doesNotContain("media/");
    assertThat(objects.exists(MediaObjects.fullKey(msgId, 0))).isFalse();
  }

  private String message(String... urls) {
    String msgId = Ulid.next();
    long now = System.currentTimeMillis();
    store.insertMessage(msgId, CHANNEL, GROUP, "tester", "text", null, "抓取链路测试正文", "摘要",
        null, "info", "pending", now, null);
    for (int i = 0; i < urls.length; i++) {
      store.insertMedia(msgId, i, urls[i], CHANNEL, GROUP, now);
    }
    messages.add(msgId);
    return msgId;
  }

  private Row media(String msgId, int idx) {
    return store.listMedia(msgId).stream().filter(r -> r.getInt("idx") == idx).findFirst()
        .orElseThrow(() -> new AssertionError("缺少 media_objects 行 msg_id=" + msgId + " idx=" + idx));
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 有界队列的前提是"任务真的开始跑了"，否则断言测的是调度时机而不是容量。 */
  private static boolean awaitCounted(CountDownLatch latch) {
    try {
      return latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static boolean awaitQueueDrained(MediaFetchService fetcher) {
    long deadline = System.currentTimeMillis() + 10_000L;
    while (System.currentTimeMillis() < deadline) {
      if (fetcher.queueDepth() == 0) {
        return true;
      }
      try {
        Thread.sleep(50L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }
}
