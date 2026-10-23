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
 * §5.2 清空整会话的落库语义（B2a）：一次 UPDATE 标完、媒体记账跟着走、重复调用幂等、
 * 别的分组一条都不动。这几条只有真 MySQL 测得出来（多表 UPDATE 的 JOIN 语法尤其）。
 */
@EnabledIf("dev.pushbox.server.test.TestConfig#databaseReady")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversationDeleteTest {

  private static final String CHANNEL = "CNVDEL01";
  private static final String OTHER_CHANNEL = "CNVDEL02";
  private static final String MINE = "cnvdel-group";
  private static final String OTHER = "cnvdel-other-group";
  private static final String SENDER = "grafana";

  @Autowired
  private PbStore store;

  private final List<String> ids = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    if (!ids.isEmpty()) {
      String marks = String.join(",", ids.stream().map(x -> "?").toList());
      Db.updateBySql("DELETE FROM media_objects WHERE msg_id IN (" + marks + ")", ids.toArray());
    }
    Db.updateBySql("DELETE FROM messages WHERE group_id IN (?, ?)", MINE, OTHER);
    ids.clear();
  }

  @Test
  @DisplayName("① 整会话一条 UPDATE 标完：范围只到本组本频道本发送者，重复调用回 0")
  void tombstonesWholeConversationOnce() {
    String a = message(CHANNEL, SENDER, MINE, "告警 一条");
    String b = message(CHANNEL, SENDER, MINE, "告警 二条");
    String otherSender = message(CHANNEL, "ci-bot", MINE, "告警 别的发送者");
    String otherChannel = message(OTHER_CHANNEL, SENDER, MINE, "告警 别的频道");
    String otherGroup = message(CHANNEL, SENDER, OTHER, "告警 别的分组");

    long now = System.currentTimeMillis();
    assertThat(store.tombstoneConversation(MINE, CHANNEL, SENDER, now)).isEqualTo(2);
    assertThat(deletedAt(a)).isEqualTo(now);
    assertThat(deletedAt(b)).isEqualTo(now);

    // 三个收窄方向都必须"没被顺手清掉"，且第二次调用不再计数
    assertThat(store.tombstoneConversation(MINE, CHANNEL, SENDER, now + 1)).isZero();
    assertThat(liveCount(MINE, CHANNEL, "ci-bot")).isEqualTo(1);
    assertThat(liveCount(MINE, OTHER_CHANNEL, SENDER)).isEqualTo(1);
    assertThat(liveCount(OTHER, CHANNEL, SENDER)).isEqualTo(1);
    assertThat(deletedAt(otherSender)).isNull();
    assertThat(deletedAt(otherChannel)).isNull();
    assertThat(deletedAt(otherGroup)).isNull();
    // 已 tombstone 的行不会被第二次删除改写时间（那是别的端收到的第二次通知）
    assertThat(deletedAt(a)).isEqualTo(now);
  }

  @Test
  @DisplayName("② 媒体记账跟着会话走：ready 槽标成待删，别的会话与已标记的槽不动")
  void marksMediaOfConversation() {
    String withMedia = message(CHANNEL, SENDER, MINE, "带图告警");
    String alsoMedia = message(CHANNEL, SENDER, MINE, "带图告警二");
    String untouched = message(CHANNEL, "ci-bot", MINE, "别人会话的图");
    media(withMedia, 0, "ready");
    media(withMedia, 1, "pending");
    media(alsoMedia, 0, "ready");
    media(untouched, 0, "ready");

    long now = System.currentTimeMillis();
    assertThat(store.tombstoneConversation(MINE, CHANNEL, SENDER, now)).isEqualTo(2);
    // pending 槽没有对象键，记账照样标上（巡检靠 deleted_at 找活，不看状态）
    assertThat(store.markMediaDeletedConversation(MINE, CHANNEL, SENDER, now)).isEqualTo(3);

    assertThat(slot(withMedia, 0).getString("status")).isEqualTo("deleted");
    assertThat(slot(withMedia, 0).getLong("deleted_at")).isEqualTo(now);
    assertThat(slot(withMedia, 1).getString("status")).isEqualTo("deleted");
    assertThat(slot(alsoMedia, 0).getString("status")).isEqualTo("deleted");
    assertThat(slot(untouched, 0).getString("status")).isEqualTo("ready");
    assertThat(slot(untouched, 0).getLong("deleted_at")).isNull();
    // 幂等：第二次记账命中 0 行，不会把 deleted_at 推后（宽限期因此不会滑动）
    assertThat(store.markMediaDeletedConversation(MINE, CHANNEL, SENDER, now + 5000)).isZero();
    assertThat(slot(withMedia, 0).getLong("deleted_at")).isEqualTo(now);
  }

  @Test
  @DisplayName("③ 清空后搜索召不回、增量补拉以 tombstone 形式出现（多端一致）")
  void disappearsFromSearchAndShowsAsTombstone() {
    long fromSeq = store.latestSeq();
    String a = message(CHANNEL, SENDER, MINE, "批量删除联调正文 甲");
    message(CHANNEL, SENDER, MINE, "批量删除联调正文 乙");

    assertThat(store.searchFulltext(MINE, "批量删除联调", null, null, null, 0, 10)).hasSize(2);
    store.tombstoneConversation(MINE, CHANNEL, SENDER, System.currentTimeMillis());

    assertThat(store.searchFulltext(MINE, "批量删除联调", null, null, null, 0, 10)).isEmpty();
    assertThat(store.searchLike(MINE, "%批量删除联调%", null, null, null, 0, 10)).isEmpty();
    // 补拉路径不能把清空当成"没有这条"，否则其他端永远留着旧消息
    assertThat(store.listMessagesAfter(OTHER, fromSeq, 500, null)).isEmpty();
    List<Row> mine = store.listMessagesAfter(MINE, fromSeq, 500, null);
    assertThat(mine).hasSize(2);
    assertThat(mine.stream().filter(r -> r.getString("msg_id").equals(a)).findFirst()
        .orElseThrow().getLong("deleted_at")).isNotNull();
  }

  private Long deletedAt(String msgId) {
    Row row = Db.selectOneBySql("SELECT deleted_at FROM messages WHERE msg_id=?", msgId);
    return row == null ? null : row.getLong("deleted_at");
  }

  private int liveCount(String groupId, String channelNo, String senderId) {
    Row row = Db.selectOneBySql("""
        SELECT COUNT(*) AS n FROM messages WHERE group_id=? AND channel_no=? AND sender_id=?
          AND deleted_at IS NULL""", groupId, channelNo, senderId);
    return row == null ? 0 : row.getInt("n");
  }

  private Row slot(String msgId, int idx) {
    return store.listMedia(msgId).stream().filter(r -> r.getInt("idx") == idx).findFirst()
        .orElseThrow(() -> new AssertionError("缺少 media_objects 行 msg_id=" + msgId + " idx=" + idx));
  }

  private void media(String msgId, int idx, String status) {
    store.insertMedia(msgId, idx, "https://example.com/" + idx + ".png", CHANNEL, MINE,
        System.currentTimeMillis());
    if ("ready".equals(status)) {
      store.markMediaReady(msgId, idx, "media/" + msgId + "/" + idx, null, "image/png", 8, 8, 8,
          Ulid.next(), "none", null, System.currentTimeMillis());
    }
  }

  private String message(String channelNo, String senderId, String groupId, String body) {
    String msgId = Ulid.next();
    store.insertMessage(msgId, channelNo, groupId, senderId, "text", null, body, body, null, "info",
        "none", System.currentTimeMillis(), null);
    ids.add(msgId);
    return msgId;
  }
}
