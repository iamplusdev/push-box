package dev.pushbox.server.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.mybatisflex.core.row.Db;
import dev.pushbox.core.crypto.Hashes;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.AbuseControlService;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §4.5 头像转存的真链路测试（B3）：URL 头像走与正文图片同一道 SSRF 闸与同一套魔数复验，
 * 内联 data: 图解码后直接落桶、完全不出网。转存是异步的（200 早已回给第三方），所以只能轮询等结果。
 */
@EnabledIf("dev.pushbox.server.test.TestConfig#objectStorageReady")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AvatarFetchTest {

  private static final String CHANNEL = "AVTST001";
  private static final String GROUP = "avtst-group";
  private static final String PNG_URL = "https://httpbin.org/image/png";
  /** 1x1 PNG。 */
  private static final String PNG_BASE64 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

  @Autowired
  private PbStore store;
  @Autowired
  private MediaObjects objects;
  @Autowired
  private MediaFetchService fetcher;
  @Autowired
  private AbuseControlService abuse;

  private final List<String> keys = new ArrayList<>();

  @BeforeEach
  void seed() {
    cleanUp();
    store.insertChannel(CHANNEL, "0".repeat(64), GROUP, "头像抓取测试频道", "error", 4,
        System.currentTimeMillis());
  }

  @AfterEach
  void cleanUp() {
    for (String key : keys) {
      objects.delete(key);
    }
    Db.updateBySql("DELETE FROM senders WHERE channel_no = ?", CHANNEL);
    Db.updateBySql("DELETE FROM channels WHERE channel_no = ?", CHANNEL);
    keys.clear();
  }

  @Test
  @DisplayName("① 公网头像 URL 被转存：键按 (频道, sender_id 摘要) 编，桶里真的躺着那张图")
  void storesPublicAvatarUrl() {
    String sender = "avatar-url-sender";
    String key = MediaObjects.avatarKey(CHANNEL, MediaObjects.avatarToken(sender));
    keys.add(key);
    // 先按入站的样子把来源写进档案列，再交给异步转存
    store.upsertSender(CHANNEL, sender, "头像", "https://httpbin.org/image/png",
        MediaObjects.avatarToken(sender), System.currentTimeMillis());
    assertThat(store.findSenderAvatar(CHANNEL, sender).getString("avatar_key")).isNull();

    fetcher.enqueueAvatar(CHANNEL, sender, AvatarSource.parse("https://httpbin.org/image/png"));

    assertThat(awaitKey(sender)).isEqualTo(key);
    byte[] stored = objects.load(key, null).body();
    assertThat(stored).isNotNull();
    // 落库类型由魔数说了算：自报的 Content-Type 骗不过去，存的必须还是 PNG
    assertThat(isPng(stored)).isTrue();
  }

  @Test
  @DisplayName("② 内联 data:image 解码后直接落桶：库里只留指纹，一字节 base64 都不进列")
  void storesInlineWithoutNetwork() {
    String sender = "avatar-inline-sender";
    String key = MediaObjects.avatarKey(CHANNEL, MediaObjects.avatarToken(sender));
    keys.add(key);
    byte[] png = Base64.getDecoder().decode(PNG_BASE64);
    AvatarSource source = AvatarSource.parse("data:image/png;base64," + PNG_BASE64);

    store.upsertSender(CHANNEL, sender, "头像", source.source(),
        MediaObjects.avatarToken(sender), System.currentTimeMillis());
    // 来源列只有 1024 字符，塞 base64 会直接被截断成取不到的地址：这里必须是指纹
    assertThat(store.findSenderAvatar(CHANNEL, sender).getString("avatar_url"))
        .doesNotContain(PNG_BASE64)
        .isEqualTo("data:sha256:" + Hashes.sha256Hex(png));

    fetcher.enqueueAvatar(CHANNEL, sender, source);

    assertThat(awaitKey(sender)).isEqualTo(key);
    assertThat(objects.load(key, null).body()).isEqualTo(png);
  }

  @Test
  @DisplayName("③ 来源没变且已有对象键：直接复用，不再出网（坏 URL 也打不掉已存好的头像）")
  void reusesWhenSourceUnchanged() {
    String sender = "avatar-reuse-sender";
    String existing = "media/avatar/" + CHANNEL + "/preexisting";
    keys.add(existing);
    store.upsertSender(CHANNEL, sender, "头像", PNG_URL,
        MediaObjects.avatarToken(sender), System.currentTimeMillis());
    store.setSenderAvatarKey(CHANNEL, sender, existing, "none", null);

    // 同步入口：复用分支直接返回旧键，一次下载都不发起
    assertThat(fetcher.storeAvatar(CHANNEL, sender, AvatarSource.parse(PNG_URL)))
        .isEqualTo(existing);

    // 生产路径是异步的：同一个来源再来一次也不能把键换掉
    fetcher.enqueueAvatar(CHANNEL, sender, AvatarSource.parse(PNG_URL));
    assertThat(awaitKeyUnchanged(sender, 1500)).isEqualTo(existing);
  }

  @Test
  @DisplayName("④ 坏头像来源：键保持 NULL，客户端按首字占位，绝不直连第三方")
  void leavesKeyNullWhenFetchFails() {
    String sender = "avatar-broken-sender";
    // 127.0.0.1 在 SSRF 闸外面（§4.5 私网一律拒绝），失败是确定的且不依赖外网
    String source = "http://127.0.0.1:9/avatar.png";
    store.upsertSender(CHANNEL, sender, "头像", source,
        MediaObjects.avatarToken(sender), System.currentTimeMillis());

    fetcher.enqueueAvatar(CHANNEL, sender, AvatarSource.parse(source));

    assertThat(awaitKeyUnchanged(sender, 5000)).isNull();
    assertThat(store.findSenderAvatar(CHANNEL, sender).getString("avatar_key")).isNull();
    assertThat(objects.exists(MediaObjects.avatarKey(CHANNEL, MediaObjects.avatarToken(sender))))
        .isFalse();
  }

  @Test
  @DisplayName("⑤ 队列满时头像任务被拒：在跑标记必须当场回收，否则这个 sender 以后再也排不上队")
  void rejectedAvatarTaskReleasesItsInFlightMarker() {
    PbProperties bounded = new PbProperties();
    bounded.getMedia().setConcurrency(1);
    bounded.getMedia().setQueueCapacity(1);
    MediaFetchService oneSlot = new MediaFetchService(store, objects, bounded, abuse);
    String sender = "avatar-rejected-sender";
    String key = MediaObjects.avatarKey(CHANNEL, MediaObjects.avatarToken(sender));
    keys.add(key);
    // 内联图完全不出网，所以这里能不能落库只取决于标记有没有漏
    AvatarSource source = AvatarSource.parse("data:image/png;base64," + PNG_BASE64);
    store.upsertSender(CHANNEL, sender, "头像", source.source(),
        MediaObjects.avatarToken(sender), System.currentTimeMillis());

    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch workerBusy = new CountDownLatch(1);
    oneSlot.submit(() -> {
      workerBusy.countDown();
      block(release);
    }, () -> {});
    assertThat(started(workerBusy)).as("先确认 worker 真被占住，否则占位的是队列").isTrue();
    oneSlot.submit(() -> block(release), () -> {});

    oneSlot.enqueueAvatar(CHANNEL, sender, source);
    assertThat(store.findSenderAvatar(CHANNEL, sender).getString("avatar_key")).isNull();

    release.countDown();
    // 先等腾出等待位再提交：这一轮要验的是"上次被拒没把标记漏下来"，而不是"永远排不进去"
    long drainDeadline = System.currentTimeMillis() + 10_000L;
    while (oneSlot.queueDepth() > 0 && System.currentTimeMillis() < drainDeadline) {
      sleep(50L);
    }
    oneSlot.enqueueAvatar(CHANNEL, sender, source);
    assertThat(awaitKey(sender)).isEqualTo(key);
    oneSlot.shutdown();
  }

  private static void block(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean started(CountDownLatch latch) {
    try {
      return latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private String awaitKey(String sender) {
    long deadline = System.currentTimeMillis() + 30_000L;
    while (System.currentTimeMillis() < deadline) {
      String key = store.findSenderAvatar(CHANNEL, sender).getString("avatar_key");
      if (key != null) {
        return key;
      }
      sleep(200L);
    }
    throw new AssertionError("头像转存超时 sender=" + sender);
  }

  /** 反向等待：这段时间里键要是变了，说明真的重抓过一次。 */
  private String awaitKeyUnchanged(String sender, long windowMs) {
    String before = store.findSenderAvatar(CHANNEL, sender).getString("avatar_key");
    long deadline = System.currentTimeMillis() + windowMs;
    while (System.currentTimeMillis() < deadline) {
      String now = store.findSenderAvatar(CHANNEL, sender).getString("avatar_key");
      if (now != null && !now.equals(before)) {
        return "changed:" + now;
      }
      sleep(200L);
    }
    return before;
  }

  private static boolean isPng(byte[] data) {
    return data != null && data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P'
        && data[2] == 'N' && data[3] == 'G';
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
