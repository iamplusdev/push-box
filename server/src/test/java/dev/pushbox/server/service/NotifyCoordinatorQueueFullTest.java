package dev.pushbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mybatisflex.core.row.Db;
import dev.pushbox.core.id.Ulid;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.media.MediaFetchService;
import dev.pushbox.server.repo.PbStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §4.5 + D24 在"抓取排不上队"这一支的契约：消息绝不能因为服务端自己忙而永远没有第一帧。
 * 这里刻意把并发和队列都压到 1，为的是用两个阻塞任务就能确定性地制造出"队列已满"，
 * 而不是靠往全局池里灌几百个任务去碰运气。
 */
@EnabledIfEnvironmentVariable(named = "PB_S3_ACCESS_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"pb.media.concurrency=1", "pb.media.queue-capacity=1",
        "pb.notify.media-wait=200ms"})
class NotifyCoordinatorQueueFullTest {

  private static final String CHANNEL = "NQFU0001";
  private static final String GROUP = "nqfu-test-group";

  @Autowired
  private PbStore store;
  @Autowired
  private NotifyCoordinator coordinator;
  @Autowired
  private MediaFetchService fetcher;
  @Autowired
  private PbProperties props;

  private String msgId;

  @BeforeEach
  void seedChannel() {
    store.insertChannel(CHANNEL, "0".repeat(64), GROUP, "队列满测试频道", "error", 4,
        System.currentTimeMillis());
  }

  @AfterEach
  void cleanUp() {
    if (msgId != null) {
      Db.updateBySql("DELETE FROM media_objects WHERE msg_id = ?", msgId);
      Db.updateBySql("DELETE FROM messages WHERE msg_id = ?", msgId);
    }
    Db.updateBySql("DELETE FROM channels WHERE channel_no = ?", CHANNEL);
  }

  @Test
  @DisplayName("队列满时 accept 不抛异常：槽位即刻记 queue_full，合并窗口照常收尾")
  void acceptStillPublishesWhenTheQueueIsFull() throws InterruptedException {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch workerBusy = new CountDownLatch(1);
    fetcher.submit(() -> {
      workerBusy.countDown();
      awaitQuietly(release);
    }, () -> {});
    assertThat(workerBusy.await(5, TimeUnit.SECONDS)).as("唯一的 worker 要真的被占住").isTrue();
    fetcher.submit(() -> awaitQuietly(release), () -> {});
    assertThat(fetcher.queueDepth()).as("唯一的等待位也要被占住").isEqualTo(1);

    msgId = Ulid.next();
    long now = System.currentTimeMillis();
    store.insertMessage(msgId, CHANNEL, GROUP, "tester", "text", null, "队列满时的正文", "摘要", null,
        "info", "pending", now, null);
    store.insertMedia(msgId, 0, "https://picsum.photos/seed/nqfu/240/160", CHANNEL, GROUP, now);

    try {
      assertThatCode(() -> coordinator.accept(msgId, GROUP, true)).doesNotThrowAnyException();

      // 降级是同步做完的：accept 返回时槽位必须已经离得开 pending
      assertThat(Db.selectOneBySql("SELECT status, error FROM media_objects WHERE msg_id = ? AND idx = 0",
          msgId).getString("status")).isEqualTo("failed");
      assertThat(Db.selectOneBySql("SELECT error FROM media_objects WHERE msg_id = ? AND idx = 0", msgId)
          .getString("error")).isEqualTo(MediaFetchService.QUEUE_FULL);
      assertThat(store.findMessageWithSender(msgId).getString("media_status")).isEqualTo("failed");
    } finally {
      release.countDown();
    }
  }

  @Test
  @DisplayName("队列容量与抓取并发各管各的：容量 1 不等于只允许 1 张图在抓")
  void capacityOnlyCountsWaitingSlots() {
    assertThat(props.getMedia().getConcurrency()).isEqualTo(1);
    assertThat(props.getMedia().getQueueCapacity()).isEqualTo(1);
    // 空池时提交成功、且不占用等待位
    CountDownLatch done = new CountDownLatch(1);
    fetcher.submit(done::countDown, () -> {
      throw new AssertionError("空池不该走降级");
    });
    awaitQuietly(done);
    assertThat(fetcher.queueDepth()).isZero();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
