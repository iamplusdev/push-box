package dev.pushbox.server.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import dev.pushbox.core.crypto.Hashes;
import dev.pushbox.core.id.Ulid;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §4.5 的宽限期巡检测试：真 MySQL + 真 RustFS，但图片字节由测试自己 PutObject，
 * 不依赖公网图床——这里要验的是"什么时候才允许 DeleteObject"，不是抓取。
 */
@EnabledIf("dev.pushbox.server.test.TestConfig#objectStorageReady")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaPurgeServiceTest {

  private static final String CHANNEL = "TESTPURG";
  private static final String GROUP = "test-purge-group";
  private static final byte[] BYTES = "pushbox-media-purge-test".getBytes(StandardCharsets.UTF_8);
  private static final long DAY_MILLIS = 86_400_000L;

  @Autowired
  private PbStore store;
  @Autowired
  private MediaObjects objects;

  private final List<String> messages = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (String msgId : messages) {
      objects.delete(MediaObjects.fullKey(msgId, 0));
      objects.delete(MediaObjects.thumbKey(msgId, 0));
      Db.updateBySql("DELETE FROM media_objects WHERE msg_id = ?", msgId);
      Db.updateBySql("DELETE FROM messages WHERE msg_id = ?", msgId);
    }
    messages.clear();
  }

  @Test
  @DisplayName("① 宽限期未到、或消息没被删除：巡检一律不动对象")
  void keepsObjectsInsideGraceWindow() {
    String insideGrace = readyMessage();
    String neverDeleted = readyMessage();
    // deleted_at 写在两天前，168h 的宽限期还没走完
    store.markMediaDeleted(insideGrace, System.currentTimeMillis() - 2 * DAY_MILLIS);

    assertThat(purger(Duration.ofHours(168)).sweep()).isZero();
    assertThat(objects.exists(MediaObjects.fullKey(insideGrace, 0))).isTrue();
    assertThat(slot(insideGrace, 0).getString("status")).isEqualTo("deleted");
    assertThat(objects.exists(MediaObjects.fullKey(neverDeleted, 0))).isTrue();
    assertThat(slot(neverDeleted, 0).getString("status")).isEqualTo("ready");
  }

  @Test
  @DisplayName("② 过了宽限期：原图与缩略图都从桶里真删，状态转 purged，重复扫描不再计数")
  void purgesAfterGrace() {
    String msgId = readyMessage();
    String key = MediaObjects.fullKey(msgId, 0);
    String thumb = MediaObjects.thumbKey(msgId, 0);
    store.markMediaDeleted(msgId, System.currentTimeMillis() - 2 * DAY_MILLIS);

    assertThat(purger(Duration.ofHours(1)).sweep()).isGreaterThanOrEqualTo(1);

    assertThat(objects.exists(key)).isFalse();
    assertThat(objects.exists(thumb)).isFalse();
    assertThat(slot(msgId, 0).getString("status")).isEqualTo("purged");
    assertThat(purger(Duration.ofHours(1)).sweep()).isZero();
  }

  private MediaPurgeService purger(Duration grace) {
    PbProperties props = new PbProperties();
    props.getS3().setDeleteGrace(grace);
    return new MediaPurgeService(store, objects, props);
  }

  /** 造一条"已经转存好"的消息：直接 PutObject + 记账，绕开抓取。 */
  private String readyMessage() {
    String msgId = Ulid.next();
    long now = System.currentTimeMillis();
    store.insertMessage(msgId, CHANNEL, GROUP, "tester", "text", null, "宽限期巡检正文", "摘要",
        null, "info", "ready", now, null);
    store.insertMedia(msgId, 0, "https://example.com/purge.png", CHANNEL, GROUP, now);
    objects.put(MediaObjects.fullKey(msgId, 0), BYTES, "image/png");
    objects.put(MediaObjects.thumbKey(msgId, 0), BYTES, "image/jpeg");
    store.markMediaReady(msgId, 0, MediaObjects.fullKey(msgId, 0), MediaObjects.thumbKey(msgId, 0),
        "image/png", 10, 10, BYTES.length, Hashes.sha256Hex(BYTES), "none", null, now);
    messages.add(msgId);
    assertThat(objects.exists(MediaObjects.fullKey(msgId, 0))).isTrue();
    return msgId;
  }

  private Row slot(String msgId, int idx) {
    return store.listMedia(msgId).stream().filter(r -> r.getInt("idx") == idx).findFirst()
        .orElseThrow(() -> new AssertionError("缺少 media_objects 行 msg_id=" + msgId));
  }
}
