package dev.pushbox.server.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import dev.pushbox.core.id.Ulid;
import dev.pushbox.server.media.MediaObjects;
import dev.pushbox.server.service.SearchService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 管理台跨空间搜索的两条腿（FULLTEXT / LIKE）在真库上的行为。管理台搜索没有 group 边界，
 * 这两条 SQL 是整个服务里唯一"查全部空间"的读路径，列清单、JOIN 出来的频道名、
 * MATCH 的 ngram 行为都只有真库才照得出来；纯函数部分在 SearchLogicTest。
 */
@EnabledIf("dev.pushbox.server.test.TestConfig#databaseReady")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminSearchStoreTest {

  private static final String CHANNEL_A = "ADSR0001";
  private static final String CHANNEL_B = "ADSR0002";
  private static final String GROUP_A = "adsr-group-a";
  private static final String GROUP_B = "adsr-group-b";
  /** 只出现在种子行里的生造词：管理台搜索没有 group 边界，真库里躺着开发期的存量消息，
   *  拿"告警"这种常见词断言条数会把别人的数据数进来。 */
  private static final String TOKEN = "玄羽髓";

  @Autowired
  private PbStore store;

  @BeforeEach
  void seed() {
    cleanUp();
    long now = System.currentTimeMillis();
    store.insertChannel(CHANNEL_A, "0".repeat(64), GROUP_A, "A 空间频道", "error", 4, now);
    store.insertChannel(CHANNEL_B, "0".repeat(64), GROUP_B, "B 空间频道", "error", 4, now);
    insert(CHANNEL_A, "ops", GROUP_A, "text", TOKEN + "磁盘告警", TOKEN + "余量不足，需要扩容", now);
    insert(CHANNEL_B, "ops", GROUP_B, "text", null, "另一个空间里的 CPU 使用率 100% 告警，" + TOKEN, now);
  }

  @AfterEach
  void cleanUp() {
    Db.updateBySql("DELETE FROM messages WHERE group_id IN (?, ?)", GROUP_A, GROUP_B);
    Db.updateBySql("DELETE FROM senders WHERE channel_no IN (?, ?)", CHANNEL_A, CHANNEL_B);
    Db.updateBySql("DELETE FROM channels WHERE channel_no IN (?, ?)", CHANNEL_A, CHANNEL_B);
  }

  @Test
  @DisplayName("① FULLTEXT 腿：中文多字词命中，跨空间无 group 边界，频道名 JOIN 得出来")
  void fulltextHitsAcrossSpaces() {
    List<Row> rows = store.searchAdminFulltext(TOKEN, null, null, 0, 50);

    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(r -> r.getString("group_id"))
        .containsExactlyInAnyOrder(GROUP_A, GROUP_B);
    assertThat(rows).extracting(r -> r.getString("channel_name"))
        .containsExactlyInAnyOrder("A 空间频道", "B 空间频道");
  }

  @Test
  @DisplayName("② channel_no 过滤是收窄：同样的词只回那一条频道的")
  void channelNoNarrows() {
    assertThat(store.searchAdminFulltext(TOKEN, CHANNEL_A, null, 0, 50)).hasSize(1);
    assertThat(store.searchAdminFulltext(TOKEN, "ADSR9999", null, 0, 50)).isEmpty();
  }

  @Test
  @DisplayName("③ LIKE 腿：单字查询（ngram_token_size=2 让 MATCH 恒零命中）照样命中")
  void likeLegFindsSingleChar() {
    List<Row> rows = store.searchAdminLike(SearchService.likePattern("髓"), null, null, 0, 50);

    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(r -> r.getString("body"))
        .containsExactlyInAnyOrder(
            TOKEN + "余量不足，需要扩容", "另一个空间里的 CPU 使用率 100% 告警，" + TOKEN);
  }

  @Test
  @DisplayName("④ 剥过引号的查询 MATCH 正常执行：不对称短语引号不该炸成 500")
  void strippedQuoteQueryRunsOnTheIndex() {
    List<Row> rows =
        store.searchAdminFulltext(SearchService.matchQuery("\"" + TOKEN), null, null, 0, 50);

    assertThat(rows).hasSize(2);
  }

  @Test
  @DisplayName("⑤ limit 生效且按 seq 倒序")
  void limitsAndOrdersDesc() {
    List<Row> rows = store.searchAdminFulltext(TOKEN, null, null, 0, 1);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getString("group_id")).isIn(GROUP_A, GROUP_B);
  }

  private void insert(String channelNo, String senderId, String groupId, String type, String title,
      String body, long at) {
    String msgId = Ulid.next();
    store.insertMessage(msgId, channelNo, groupId, senderId, type, title, body, body, null, "info",
        "none", at, null);
    store.upsertSender(channelNo, senderId, senderId, null, MediaObjects.avatarToken(senderId), at);
  }
}
