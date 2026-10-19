package dev.pushbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mybatisflex.core.row.Db;
import dev.pushbox.core.api.SearchPage;
import dev.pushbox.core.id.Ulid;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §5.2 D19 搜索的真库测试：ngram FULLTEXT 的行为（中文分词、零命中、索引缺失）只有在
 * MySQL 上才看得出来，纯函数部分在 SearchLogicTest。
 */
@EnabledIfEnvironmentVariable(named = "PB_S3_ACCESS_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SearchServiceTest {

  private static final String CHANNEL_A = "SRCH0001";
  private static final String CHANNEL_B = "SRCH0002";
  private static final String MINE = "srch-test-group";
  private static final String OTHER = "srch-other-group";

  @Autowired
  private PbStore store;
  @Autowired
  private SearchService service;

  /** fulltext=false 时一律 LIKE：⑦ 要验的是 LIKE 的转义，必须绕开 MATCH 的召回放大。 */
  private SearchService likeOnly;

  private final List<String> ids = new ArrayList<>();

  @BeforeEach
  void setUp() {
    PbProperties props = new PbProperties();
    props.getSearch().setFulltext(false);
    likeOnly = new SearchService(store, props);
  }

  @AfterEach
  void cleanUp() {
    Db.updateBySql("DELETE FROM messages WHERE group_id IN (?, ?)", MINE, OTHER);
    Db.updateBySql("DELETE FROM senders WHERE channel_no IN (?, ?)", CHANNEL_A, CHANNEL_B);
    ids.clear();
  }

  @Test
  @DisplayName("① 中文多字词命中，按 seq 倒序，带上 sender_name")
  void findsChineseTerm() {
    insert(CHANNEL_A, "ops", "markdown", "订单服务告警", "CPU 使用率超过 90%，已连续 3 次触发告警",
        MINE, null);
    insert(CHANNEL_A, "ops", "text", "无关内容", "今天的构建很顺利", MINE, null);

    SearchPage page = service.search(MINE, "告警", null, null, null, 0, 50, null);

    assertThat(page.results()).hasSize(1);
    assertThat(page.results().get(0).title()).isEqualTo("订单服务告警");
    assertThat(page.results().get(0).snippet()).contains("告警");
    assertThat(page.hasMore()).isFalse();
  }

  @Test
  @DisplayName("② 倒序 + limit + has_more：只回 limit 条，多取的那条只用来判断还有没有")
  void ordersDescAndPages() {
    for (int i = 0; i < 5; i++) {
      insert(CHANNEL_A, "ops", "text", null, "部署记录 " + i, MINE, null);
    }
    SearchPage page = service.search(MINE, "部署", null, null, null, 0, 3, null);

    assertThat(page.results()).hasSize(3);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.results().get(0).seq()).isGreaterThan(page.results().get(1).seq());
    assertThat(page.results().get(1).seq()).isGreaterThan(page.results().get(2).seq());
  }

  @Test
  @DisplayName("③ 单字查询走 LIKE 兜底：ngram_token_size=2 让 MATCH 对单字恒零命中")
  void fallsBackToLikeForSingleChar() {
    insert(CHANNEL_A, "ops", "text", null, "磁盘余量不足，需要扩容", MINE, null);
    insert(CHANNEL_A, "ops", "text", null, "网络正常", MINE, null);

    SearchPage page = service.search(MINE, "磁", null, null, null, 0, 50, null);

    assertThat(page.results()).hasSize(1);
    assertThat(page.results().get(0).snippet()).contains("磁盘余量不足");
  }

  @Test
  @DisplayName("④ group 边界：别的 Space 里同样命中的内容绝不出现在结果里")
  void isolatesGroups() {
    insert(CHANNEL_A, "ops", "text", "共享密钥告警", "只在别人组里的告警正文", OTHER, null);
    insert(CHANNEL_A, "ops", "text", "本组告警", "本组自己的告警正文", MINE, null);

    assertThat(service.search(MINE, "告警", null, null, null, 0, 50, null).results()).hasSize(1);
    // 反向也要成立：拿别人的 group 查不到本组内容
    assertThat(service.search(OTHER, "告警", null, null, null, 0, 50, null).results()).hasSize(1);
  }

  @Test
  @DisplayName("⑤ 过滤条件：channel_no、sender_id、type、since 都是收窄而不是放宽")
  void appliesFilters() {
    long old = System.currentTimeMillis() - 86_400_000L;
    long fresh = System.currentTimeMillis();
    insert(CHANNEL_A, "ops", "text", "告警 A", "频道 A 的告警正文", MINE, null);
    insert(CHANNEL_B, "ops", "text", "告警 B", "频道 B 的告警正文", MINE, null);
    insert(CHANNEL_B, "ci", "markdown", "告警 C", "发送者 ci 的告警正文", MINE, null);
    insert(CHANNEL_B, "ci", "text", "告警 D", "一天前的告警正文", MINE, old);

    assertThat(service.search(MINE, "告警", CHANNEL_B, null, null, 0, 50, null).results()).hasSize(3);
    assertThat(service.search(MINE, "告警", CHANNEL_B, "ci", null, 0, 50, null).results()).hasSize(2);
    assertThat(service.search(MINE, "告警", null, null, "markdown", 0, 50, null).results()).hasSize(1);
    assertThat(service.search(MINE, "告警", null, null, null, fresh, 50, null).results()).hasSize(3);
    assertThat(service.search(MINE, "告警", null, null, null, old - 1, 50, null).results()).hasSize(4);
  }

  @Test
  @DisplayName("⑥ 已删除的消息不出现：搜索只读，tombstone 必须在 SQL 里就滤掉")
  void skipsTombstoned() {
    String keep = insert(CHANNEL_A, "ops", "text", "告警保留", "保留的告警正文", MINE, null);
    String gone = insert(CHANNEL_A, "ops", "text", "告警删除", "删除的告警正文", MINE, null);
    assertThat(store.tombstoneMessage(gone, MINE, System.currentTimeMillis())).isEqualTo(1);

    SearchPage page = service.search(MINE, "告警", null, null, null, 0, 50, null);

    assertThat(page.results()).hasSize(1);
    assertThat(page.results().get(0).id()).isEqualTo(keep);
  }

  @Test
  @DisplayName("⑦ LIKE 的通配符在真库里也是字面量：搜 100% 不会把一切都捞出来")
  void likeWildcardsAreLiteral() {
    insert(CHANNEL_A, "ops", "text", null, "命中率 100% 的告警", MINE, null);
    insert(CHANNEL_A, "ops", "text", null, "命中率 90% 的告警", MINE, null);

    // 走 LIKE 分支：MATCH 的 ngram 会把 "100%" 切成 10/00/0%，"0%" 在第二行也存在，
    // 那是全文检索的召回特性而不是这里的被测点。被测点是转义后的 pattern 只剩字面量。
    SearchPage page = likeOnly.search(MINE, "100%", null, null, null, 0, 50, null);

    assertThat(page.results()).hasSize(1);
    assertThat(page.results().get(0).snippet()).contains("100%");
  }

  @Test
  @DisplayName("⑧ 零命中回空列表，不是错误")
  void emptyResultIsNotAnError() {
    insert(CHANNEL_A, "ops", "text", null, "普通正文内容", MINE, null);

    SearchPage page = service.search(MINE, "完全不存在词组", null, null, null, 0, 50, null);

    assertThat(page.results()).isEmpty();
    assertThat(page.hasMore()).isFalse();
  }

  private String insert(String channelNo, String senderId, String type, String title, String body,
      String groupId, Long createdAt) {
    String msgId = Ulid.next();
    long at = createdAt == null ? System.currentTimeMillis() : createdAt;
    store.insertMessage(msgId, channelNo, groupId, senderId, type, title, body, body, null, "info",
        "none", at, null);
    store.upsertSender(channelNo, senderId, senderId + "-nick", null,
        dev.pushbox.server.media.MediaObjects.avatarToken(senderId), at);
    ids.add(msgId);
    return msgId;
  }
}
