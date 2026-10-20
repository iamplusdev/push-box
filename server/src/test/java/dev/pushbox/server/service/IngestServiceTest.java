package dev.pushbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import dev.pushbox.core.api.IngestRequest;
import dev.pushbox.core.api.IngestResponse;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.web.ApiException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §5.1 / §4.4 的受理路径。走真库是因为这条链路上最值钱的判定都落在库里：
 * 密钥摘要比对、锁定状态、msg_key 幂等、level 截断落库值、媒体行计数。
 */
@EnabledIfEnvironmentVariable(named = "PB_S3_ACCESS_KEY", matches = ".+")
// RANDOM_PORT 不是随手写的：MOCK 上下文里 WebSocketConfig 拿不到 ServerContainer，上下文起不来。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestServiceTest {

  private static final String CHANNEL = "INGE0001";
  private static final String GROUP = "ingest-test-group";
  private static final String SECRET = "0123456789abcdef0123456789abcdef";
  private static final String IP = "203.0.113.7";

  @Autowired
  private PbStore store;
  @Autowired
  private IngestService ingest;

  @BeforeEach
  void setUp() {
    store.insertChannel(CHANNEL, ChannelService.secretHash(SECRET), GROUP, "受理测试", "warning", 4,
        System.currentTimeMillis());
    resetCredentialState();
  }

  @AfterEach
  void tearDown() {
    Db.updateBySql("DELETE FROM media_objects WHERE channel_no = ?", CHANNEL);
    Db.updateBySql("DELETE FROM messages WHERE channel_no = ?", CHANNEL);
    Db.updateBySql("DELETE FROM senders WHERE channel_no = ?", CHANNEL);
    Db.updateBySql("DELETE FROM channels WHERE channel_no = ?", CHANNEL);
  }

  @Test
  @DisplayName("① 纯文字受理：200 回 id/seq/摘要，media_status=none，level 按频道上限截断")
  void textOnlyIsAcceptedAndTruncated() {
    IngestResponse resp = ingest.ingest(request(CHANNEL, SECRET, null,
        "主机 prod-03 CPU 使用率超过 90%，已经连续触发多次，需要立刻人工介入处理", "error", null), IP);

    assertThat(resp.ok()).isTrue();
    assertThat(resp.id()).isNotBlank();
    assertThat(resp.seq()).isPositive();
    assertThat(resp.mediaStatus()).isEqualTo("none");
    assertThat(resp.mediaCount()).isZero();
    // 频道 max_level=warning，error 被压到 warning（D25）
    assertThat(resp.level()).isEqualTo("warning");
    assertThat(resp.summary()).hasSizeLessThanOrEqualTo(60);
    assertThat(Db.selectOneBySql("SELECT body FROM messages WHERE msg_id = ?", resp.id())
        .getString("body")).contains("prod-03");
  }

  @Test
  @DisplayName("② 低于上限的 level 原样下发，缺省按 info")
  void levelsBelowCapAreKept() {
    IngestResponse warning = ingest.ingest(
        request(CHANNEL, SECRET, null, "磁盘余量偏低", "warning", null), IP);
    IngestResponse defaulted = ingest.ingest(
        request(CHANNEL, SECRET, null, "部署完成", null, null), IP);

    assertThat(warning.level()).isEqualTo("warning");
    assertThat(defaulted.level()).isEqualTo("info");
  }

  @Test
  @DisplayName("③ msg_key 幂等：重复提交回同一条，库里只有一行")
  void msgKeyIsIdempotent() {
    IngestRequest body = request(CHANNEL, SECRET, null, "同一事件的重复投递", "info", null);
    IngestResponse first = ingest.ingest(
        new IngestRequest(body.channel(), body.secret(), body.type(), body.content(),
            body.senderId(), body.senderName(), body.senderAvatar(), body.title(), body.level(),
            body.url(), body.images(), "evt-42", body.ts(), null, null), IP);
    IngestResponse second = ingest.ingest(
        new IngestRequest(body.channel(), body.secret(), body.type(), body.content(),
            body.senderId(), body.senderName(), body.senderAvatar(), body.title(), body.level(),
            body.url(), body.images(), "evt-42", body.ts(), null, null), IP);

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.seq()).isEqualTo(first.seq());
    assertThat(Db.selectListBySql(
        "SELECT msg_id FROM messages WHERE channel_no = ? AND client_msg_id = 'evt-42'", CHANNEL))
        .hasSize(1);
  }

  @Test
  @DisplayName("④ 频道不存在与密钥错共用 401，不给枚举频道号码留缝隙")
  void unknownChannelAndWrongSecretLookIdentical() {
    assertThatThrownBy(() -> ingest.ingest(request("00000000", SECRET, null, "x", "info", null), IP))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(401);
          assertThat(e.toError().error()).isEqualTo("invalid_credential");
        });
    assertThatThrownBy(() -> ingest.ingest(request(CHANNEL, "deadbeef", null, "x", "info", null), IP))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(401);
          assertThat(e.toError().error()).isEqualTo("invalid_credential");
        });
  }

  @Test
  @DisplayName("⑤ 错满阈值即锁频道，锁定期内连对的凭据也吃 403；清掉计数才恢复")
  void credentialFailuresLockTheChannel() {
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> ingest.ingest(
          request(CHANNEL, "00000000000000000000000000000000", null, "x", "info", null), IP))
          .isInstanceOfSatisfying(ApiException.class,
              e -> assertThat(e.status()).isIn(401, 403));
    }

    assertThatThrownBy(() -> ingest.ingest(
        request(CHANNEL, SECRET, null, "锁定期内的正确凭据", "info", null), IP))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(403);
          assertThat(e.toError().error()).isEqualTo("channel_locked");
        });

    resetCredentialState();
    assertThat(ingest.ingest(request(CHANNEL, SECRET, null, "解锁后正常", "info", null), IP).ok())
        .isTrue();
  }

  @Test
  @DisplayName("⑥ 归档频道即刻拒写（403），历史消息按 D3 保留")
  void archivedChannelRefusesWrites() {
    store.archiveChannel(CHANNEL, GROUP, System.currentTimeMillis());

    assertThatThrownBy(() -> ingest.ingest(request(CHANNEL, SECRET, null, "x", "info", null), IP))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(403);
          assertThat(e.toError().error()).isEqualTo("channel_locked");
        });
    assertThat(store.findChannel(CHANNEL).getLong("archived_at")).isPositive();
  }

  @Test
  @DisplayName("⑦ content 必填；正文超 max_body_bytes 回 413 而不是截断入库")
  void contentValidation() {
    assertThatThrownBy(() -> ingest.ingest(request(CHANNEL, SECRET, null, "   ", "info", null), IP))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(400);
          assertThat(e.toError().error()).isEqualTo("invalid_param");
        });

    String huge = "字".repeat(11_000); // 33000 字节 > 32768
    assertThatThrownBy(() -> ingest.ingest(request(CHANNEL, SECRET, null, huge, "info", null), IP))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(413));

    assertThat(Db.selectListBySql("SELECT msg_id FROM messages WHERE channel_no = ?", CHANNEL))
        .isEmpty();
  }

  @Test
  @DisplayName("⑧ images 只收 http(s) 且不超过频道上限，data: 内联一律拒")
  void imageValidation() {
    List<String> five = List.of("https://a.test/1.png", "https://a.test/2.png",
        "https://a.test/3.png", "https://a.test/4.png", "https://a.test/5.png");
    assertThatThrownBy(() -> ingest.ingest(
        request(CHANNEL, SECRET, null, "五条图", "info", five), IP))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));

    for (List<String> bad : List.of(
        List.of("data:image/png;base64,AAAA"),
        List.of("ftp://a.test/1.png"),
        List.of("https://a.test/ok.png", " "),
        List.of("no-scheme.test/1.png"))) {
      assertThatThrownBy(() -> ingest.ingest(request(CHANNEL, SECRET, null, "坏图", "info", bad), IP))
          .isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(400);
            assertThat(e.toError().error()).isEqualTo("invalid_param");
          });
    }
    // 校验在任何写库之前：四条坏图都不该留下消息行
    assertThat(Db.selectListBySql("SELECT msg_id FROM messages WHERE channel_no = ?", CHANNEL))
        .isEmpty();
  }

  @Test
  @DisplayName("⑨ 带图受理：200 立刻回且 media_status=pending，媒体行按 idx 落库")
  void imagesBecomePendingSlots() {
    IngestResponse resp = ingest.ingest(request(CHANNEL, SECRET, null, "带一条图的消息", "info",
        List.of("https://picsum.photos/seed/pb-ingest-test/240/160")), IP);

    assertThat(resp.mediaStatus()).isEqualTo("pending");
    assertThat(resp.mediaCount()).isEqualTo(1);
    assertThat(Db.selectListBySql(
        "SELECT idx, status, source_url FROM media_objects WHERE msg_id = ?", resp.id()))
        .hasSize(1);
  }

  @Test
  @DisplayName("⑩ ts 允许秒也允许毫秒（<1e11 视为秒），缺省或越出 ±300s 窗口用服务端接收时间")
  void timestampsAreNormalized() {
    // 秒级/毫秒级两种写法都必须在 ±300s 窗口内，否则 businessTime() 会直接回落到服务端时间，
    // 那条分支归下面的 stale 用例管。
    long recent = System.currentTimeMillis() - 60_000L;
    IngestResponse seconds = ingest.ingest(
        new IngestRequest(CHANNEL, SECRET, "text", "秒级时间戳", "grafana", null, null, null,
            "info", null, null, null, recent / 1000, null, null), IP);
    IngestResponse millis = ingest.ingest(
        new IngestRequest(CHANNEL, SECRET, "text", "毫秒级时间戳", "grafana", null, null, null,
            "info", null, null, null, recent, null, null), IP);
    long before = System.currentTimeMillis() - 5_000L;
    IngestResponse absent = ingest.ingest(
        new IngestRequest(CHANNEL, SECRET, "text", "不带时间戳", "grafana", null, null, null,
            "info", null, null, null, null, null, null), IP);
    IngestResponse stale = ingest.ingest(
        new IngestRequest(CHANNEL, SECRET, "text", "越窗的旧时间戳", "grafana", null, null, null,
            "info", null, null, null, 1_700_000_000_000L, null, null), IP);

    // 秒级会被截到整秒，与毫秒级同一来源只差最多 999ms
    long secCreated = Db.selectOneBySql("SELECT created_at FROM messages WHERE msg_id = ?", seconds.id())
        .getLong("created_at");
    long msCreated = Db.selectOneBySql("SELECT created_at FROM messages WHERE msg_id = ?", millis.id())
        .getLong("created_at");
    assertThat(secCreated / 1000).isEqualTo(msCreated / 1000);
    assertThat(Db.selectOneBySql("SELECT created_at FROM messages WHERE msg_id = ?", absent.id())
        .getLong("created_at")).isBetween(before, System.currentTimeMillis());
    assertThat(Db.selectOneBySql("SELECT created_at FROM messages WHERE msg_id = ?", stale.id())
        .getLong("created_at")).isBetween(before, System.currentTimeMillis());
  }

  @Test
  @DisplayName("⑪ 省略 sender_id 归到 unknown 会话；长名字截到 64 字符")
  void senderFallsBackAndClamps() {
    IngestResponse resp = ingest.ingest(
        new IngestRequest(CHANNEL, SECRET, "text", "没有发送方", null, "  " + "长".repeat(80) + "  ",
            null, null, "info", null, null, null, null, null, null), IP);

    assertThat(Db.selectOneBySql("SELECT sender_id FROM messages WHERE msg_id = ?", resp.id())
        .getString("sender_id")).isEqualTo("unknown");
    // findSenderAvatar 只取头像两列，名字要直接查 senders
    assertThat(Db.selectOneBySql(
        "SELECT display_name FROM senders WHERE channel_no = ? AND sender_id = 'unknown'", CHANNEL)
        .getString("display_name")).hasSize(64);
  }

  @Test
  @DisplayName("⑫ 内部入口（定时推送走的那条）同样按频道 max_level 截断——它跳过的只是 HMAC，不是上限")
  void internalPathIsTruncatedLikeThePublicOne() {
    Row channel = store.findChannel(CHANNEL);
    IngestRequest body = request(CHANNEL, SECRET, null, "定时任务想以 error 穿透免打扰", "error", null);

    IngestResponse resp = ingest.ingestInternal(channel, body);

    assertThat(resp.ok()).isTrue();
    assertThat(resp.level()).isEqualTo("warning");
    assertThat(Db.selectOneBySql("SELECT level FROM messages WHERE msg_id = ?", resp.id())
        .getString("level")).isEqualTo("warning");
  }

  private static IngestRequest request(String channel, String secret, String type, String content,
      String level, List<String> images) {
    return new IngestRequest(channel, secret, type, content, "grafana", "Grafana 告警", null,
        null, level, null, images, null, null, null, null);
  }

  private void resetCredentialState() {
    Db.updateBySql("UPDATE channels SET fail_count = 0, locked_until = 0 WHERE channel_no = ?",
        CHANNEL);
  }
}
