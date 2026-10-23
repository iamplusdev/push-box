package dev.pushbox.server.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import dev.pushbox.core.id.Ulid;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §4.5 抓取重试的落库语义（B2b）：候选集只收"再试有可能变好"的行，reset 之后抓取器的输入必须真的
 * 看到它们。账本只有 fetch_attempts 一个字段，标错一次就要么无限重试、要么永不重试。
 * 这个 sweep 是全库扫的（不属于某个分组），所以夹具的 created_at 用 1/2/3 这种绝对老的毫秒值，
 * 保证在 ORDER BY created_at 与 limit 面前不受库里历史积压干扰。
 */
@EnabledIf("dev.pushbox.server.test.TestConfig#databaseReady")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaRetryStoreTest {

  private static final String CHANNEL = "MRTRY001";
  private static final String MINE = "mrtry-group";
  private static final int RETRY_MAX = 3;
  /** pending 僵尸：老到没有任何一次正常抓取还能把它解释成"正在抓"。 */
  private static final long ZOMBIE = 1L;

  @Autowired
  private PbStore store;

  private final List<String> ids = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    if (!ids.isEmpty()) {
      String marks = String.join(",", ids.stream().map(x -> "?").toList());
      Db.updateBySql("DELETE FROM media_objects WHERE msg_id IN (" + marks + ")", ids.toArray());
    }
    Db.updateBySql("DELETE FROM messages WHERE group_id = ?", MINE);
    ids.clear();
  }

  @Test
  @DisplayName("① 候选集：瞬时失败与僵尸 pending 进，内容级终态、次数耗尽、已删除都不进")
  void picksOnlyRetryableRows() {
    String transientFail = message("瞬时失败");
    failed(transientFail, 0, "timeout", 1);
    String contentFail = message("类型不对");
    failed(contentFail, 0, "bad_type", 1);
    String tooBig = message("太大");
    failed(tooBig, 0, "too_large", 1);
    String exhausted = message("抓够次数了");
    failed(exhausted, 0, "timeout", RETRY_MAX);
    String freshPending = message("正在抓");
    media(freshPending, 0, System.currentTimeMillis());
    String zombie = message("重启留下的僵尸");
    media(zombie, 0, ZOMBIE);
    String goneMessage = message("已删除的消息");
    failed(goneMessage, 0, "timeout", 1);
    Db.updateBySql("UPDATE messages SET deleted_at=? WHERE msg_id=?", System.currentTimeMillis(),
        goneMessage);
    String goneSlot = message("槽位已删除");
    failed(goneSlot, 0, "timeout", 1);
    store.markMediaDeleted(goneSlot, System.currentTimeMillis());
    String noError = message("失败但没写原因");
    failed(noError, 0, "unknown", 1);
    Db.updateBySql("UPDATE media_objects SET error=NULL WHERE msg_id=? AND idx=0", noError);

    List<String> candidates = candidateIds();
    assertThat(candidates).contains(transientFail, zombie, noError);
    assertThat(candidates).doesNotContain(contentFail, tooBig, exhausted, freshPending, goneMessage,
        goneSlot);
  }

  @Test
  @DisplayName("② 一条消息多个待重试槽位只出一个候选，一轮只抓一次")
  void collapsesSlotsOfSameMessage() {
    String twoSlots = message("两个槽位");
    failed(twoSlots, 0, "timeout", 1);
    failed(twoSlots, 1, "unreachable", 2);

    List<Row> mine = store.mediaRetryCandidates(ZOMBIE, RETRY_MAX, 500).stream()
        .filter(r -> r.getString("msg_id").equals(twoSlots)).toList();
    assertThat(mine).hasSize(1);
    assertThat(mine.get(0).getString("group_id")).isEqualTo(MINE);
    assertThat(mine.get(0).getLong("seq")).isPositive();
  }

  @Test
  @DisplayName("③ reset 只放回还能重试的槽位：attempts 不清账、终态码与耗尽次数不动、幂等回 0")
  void resetFeedsTheFetcherAndIsIdempotent() {
    String retryable = message("该重试");
    failed(retryable, 0, "timeout", 2);
    String contentFail = message("内容级");
    failed(contentFail, 0, "bad_type", 1);
    String exhausted = message("耗尽");
    failed(exhausted, 0, "timeout", RETRY_MAX);
    String tombstonedSlot = message("已删槽位");
    failed(tombstonedSlot, 0, "timeout", 1);
    store.markMediaDeleted(tombstonedSlot, System.currentTimeMillis());

    // 放回 pending 之后必须出现在抓取器的输入里，否则这一轮只是白改状态
    assertThat(store.resetMediaForRetry(retryable, RETRY_MAX)).isEqualTo(1);
    assertThat(store.pendingMedia(retryable)).hasSize(1);
    assertThat(slot(retryable, 0).getString("status")).isEqualTo("pending");
    assertThat(slot(retryable, 0).getString("error")).isNull();
    // fetch_attempts 是账本：清掉它等于让坏链接被无限重试
    assertThat(slot(retryable, 0).getInt("fetch_attempts")).isEqualTo(2);
    assertThat(store.resetMediaForRetry(retryable, RETRY_MAX)).isZero();

    assertThat(store.resetMediaForRetry(contentFail, RETRY_MAX)).isZero();
    assertThat(slot(contentFail, 0).getString("status")).isEqualTo("failed");
    assertThat(store.resetMediaForRetry(exhausted, RETRY_MAX)).isZero();
    assertThat(store.resetMediaForRetry(tombstonedSlot, RETRY_MAX)).isZero();
    assertThat(slot(tombstonedSlot, 0).getString("status")).isEqualTo("deleted");
  }

  @Test
  @DisplayName("④ 积压按最老优先出队，limit 真的只放行最前面的那一行")
  void ordersByOldestAndHonoursLimit() {
    String oldest = message("最老的积压");
    String middle = message("中间的积压");
    String newest = message("最新的积压");
    failedAt(oldest, 0, 1L);
    failedAt(middle, 0, 2L);
    failedAt(newest, 0, 3L);

    assertThat(candidateIds(500)).containsExactly(oldest, middle, newest);
    List<Row> limited = store.mediaRetryCandidates(System.currentTimeMillis(), RETRY_MAX, 1);
    assertThat(limited).hasSize(1);
    assertThat(limited.get(0).getString("msg_id")).isEqualTo(oldest);
  }

  private List<String> candidateIds() {
    return candidateIds(500);
  }

  private List<String> candidateIds(int limit) {
    return store.mediaRetryCandidates(ZOMBIE, RETRY_MAX, limit).stream()
        .map(r -> r.getString("msg_id")).filter(ids::contains).toList();
  }

  private String message(String body) {
    String msgId = Ulid.next();
    store.insertMessage(msgId, CHANNEL, MINE, "mrtry-bot", "text", null, body, body, null, "info",
        "none", System.currentTimeMillis(), null);
    ids.add(msgId);
    return msgId;
  }

  private void media(String msgId, int idx, long createdAt) {
    store.insertMedia(msgId, idx, "https://example.com/" + msgId + "-" + idx + ".png", CHANNEL,
        MINE, createdAt);
  }

  /** markMediaFailed 每调一次加一次 attempts，所以"抓过 N 次"就是调 N 次。 */
  private void failed(String msgId, int idx, String error, int attempts) {
    media(msgId, idx, ZOMBIE);
    for (int i = 0; i < attempts; i++) {
      store.markMediaFailed(msgId, idx, error, System.currentTimeMillis());
    }
  }

  /** 只标失败一次，created_at 由用例指定，用来验证出队顺序。 */
  private void failedAt(String msgId, int idx, long createdAt) {
    media(msgId, idx, createdAt);
    store.markMediaFailed(msgId, idx, "timeout", System.currentTimeMillis());
  }

  private Row slot(String msgId, int idx) {
    return store.listMedia(msgId).stream().filter(r -> r.getInt("idx") == idx).findFirst()
        .orElseThrow(() -> new AssertionError(
            "缺少 media_objects 行 msg_id=" + msgId + " idx=" + idx));
  }
}
