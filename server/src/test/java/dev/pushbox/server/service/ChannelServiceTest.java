package dev.pushbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.web.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §5.2 频道 CRUD 的真库用例。这里最要紧的是两件事：
 * 库里只留 sha256（明文只在创建/重置那一次的响应里出现），以及跨组改不动别人的频道。
 */
@EnabledIf("dev.pushbox.server.test.TestConfig#databaseReady")
// RANDOM_PORT 不是随手写的：MOCK 上下文里 WebSocketConfig 的 ServletServerContainerFactoryBean
// 拿不到 jakarta.websocket.server.ServerContainer，整个应用上下文直接启动失败。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChannelServiceTest {

  private static final String MINE = "chan-test-group";
  private static final String OTHER = "chan-other-group";

  @Autowired
  private PbStore store;
  @Autowired
  private PbProperties props;

  private ChannelService service;
  private ChannelService.Created created;

  @BeforeEach
  void setUp() {
    service = new ChannelService(store, props);
    created = service.create(MINE, "  运维告警频道  ", "warning", 2);
  }

  @AfterEach
  void tearDown() {
    // 频道号重置用例会把消息/发送者/已读迁到新号码下，删除前要先按当前 channels 收集号码
    List<Row> chans =
        Db.selectListBySql("SELECT channel_no FROM channels WHERE group_id IN (?, ?)", MINE, OTHER);
    for (Row c : chans) {
      String no = c.getString("channel_no");
      Db.updateBySql("DELETE FROM messages WHERE channel_no = ?", no);
      Db.updateBySql("DELETE FROM media_objects WHERE channel_no = ?", no);
      Db.updateBySql("DELETE FROM read_state WHERE channel_no = ?", no);
      Db.updateBySql("DELETE FROM senders WHERE channel_no = ?", no);
      // daily_counters / sender_allowlist 有 ON DELETE CASCADE，随 channels 一起走，这里显式清也无害
      Db.updateBySql("DELETE FROM daily_counters WHERE channel_no = ?", no);
      Db.updateBySql("DELETE FROM sender_allowlist WHERE channel_no = ?", no);
    }
    Db.updateBySql("DELETE FROM channels WHERE group_id IN (?, ?)", MINE, OTHER);
  }

  @Test
  @DisplayName("① 创建：号码是 8 位数字、密钥 32 位十六进制，库里只有摘要")
  void createPersistsOnlyTheHash() {
    assertThat(created.channelNo()).hasSize(8).matches("\\d{8}");
    assertThat(created.secret()).matches("[0-9a-f]{32}");
    assertThat(created.name()).isEqualTo("运维告警频道");
    assertThat(created.maxLevel()).isEqualTo("warning");
    assertThat(created.maxImages()).isEqualTo(2);

    Row row = store.findChannel(created.channelNo());
    assertThat(row.getString("secret_hash")).isEqualTo(ChannelService.secretHash(created.secret()));
    // 明文一旦进库就再也收不回来：这一列必须与密钥本身完全不同
    assertThat(row.getString("secret_hash")).doesNotContain(created.secret());
    assertThat(ChannelService.secretMatches(created.secret(), row)).isTrue();
    // 造"错密钥"要避开原值：末位直接写死 '0' 有 1/16 概率撞上真密钥，那时断言必挂
    String wrong =
        created.secret().charAt(31) == '0'
            ? created.secret().substring(0, 31) + '1'
            : created.secret().substring(0, 31) + '0';
    assertThat(wrong).isNotEqualTo(created.secret());
    assertThat(ChannelService.secretMatches(wrong, row)).isFalse();
  }

  @Test
  @DisplayName("② 缺省值走配置：不给 level/images 时用 channels.default_*")
  void createUsesDefaults() {
    ChannelService.Created plain = service.create(MINE, null, null, null);

    assertThat(plain.maxLevel()).isEqualTo(props.getChannels().getDefaultMaxLevel());
    assertThat(plain.maxImages()).isEqualTo(props.getChannels().getDefaultMaxImages());
    assertThat(plain.name()).isNull();
  }

  @Test
  @DisplayName("③ 重置密钥：旧的立刻失效，锁定计数一并解除，号码不变")
  void rotateInvalidatesOldSecretAndUnlocks() {
    // 先进入锁定态：5 次错凭据（阈值之上就够）
    store.recordCredentialFailure(created.channelNo(), 9, System.currentTimeMillis() + 60_000L);
    assertThat(store.findChannel(created.channelNo()).getLong("locked_until")).isPositive();

    ChannelService.Created rotated = service.rotateSecret(created.channelNo(), MINE);

    assertThat(rotated.channelNo()).isEqualTo(created.channelNo());
    assertThat(rotated.secret()).isNotEqualTo(created.secret());
    Row row = store.findChannel(created.channelNo());
    assertThat(ChannelService.secretMatches(rotated.secret(), row)).isTrue();
    assertThat(ChannelService.secretMatches(created.secret(), row)).isFalse();
    // 运维改对凭据却还在锁定期内是最难排查的一种"我明明写对了"
    assertThat(row.getLong("locked_until")).isZero();
    assertThat(row.getInt("fail_count")).isZero();
    assertThat(rotated.maxLevel()).isEqualTo("warning");
  }

  @Test
  @DisplayName("④ 跨组改不动：重置与归档都按 (channel_no, group_id) 收口，失败一律 404")
  void otherGroupCannotTouchChannel() {
    assertThatThrownBy(() -> service.rotateSecret(created.channelNo(), OTHER))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThatThrownBy(() -> service.archive(created.channelNo(), OTHER))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThatThrownBy(() -> service.update(created.channelNo(), OTHER, "改名", null))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThatThrownBy(() -> service.archive("00000000", MINE))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));

    // archived_at 是可空列，"没归档"在库里是 NULL 而不是 0
    assertThat(store.findChannel(created.channelNo()).getLong("archived_at")).isNull();
  }

  @Test
  @DisplayName("⑤ 归档是软归档：archived_at 打标，重复归档 404，列表不再返回")
  void archiveIsSoftAndSingleShot() {
    long now = service.archive(created.channelNo(), MINE);

    Row row = store.findChannel(created.channelNo());
    assertThat(row.getLong("archived_at")).isGreaterThanOrEqualTo(now);
    assertThatThrownBy(() -> service.archive(created.channelNo(), MINE))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThat(service.list(MINE)).isEmpty();
  }

  @Test
  @DisplayName("⑥ 改名/改强度：null 表示不动它，名字截到 64，强度走 Level 归一")
  void updateOnlyChangesWhatIsGiven() {
    ChannelService.Created renamed = service.update(created.channelNo(), MINE,
        "  " + "长".repeat(80) + "  ", null);

    assertThat(renamed.name()).hasSize(64).startsWith("长");
    assertThat(renamed.maxLevel()).isEqualTo("warning");
    assertThat(renamed.secret()).isNull();

    ChannelService.Created raised = service.update(created.channelNo(), MINE, null, "info");
    assertThat(raised.name()).isEqualTo(renamed.name());
    assertThat(raised.maxLevel()).isEqualTo("info");
    assertThat(store.findChannel(created.channelNo()).getString("name")).isEqualTo(renamed.name());
  }

  @Test
  @DisplayName("⑦ 列表永不带密钥：连 secret 这个键都不出现，治理位却必须看得见")
  void listNeverCarriesSecret() {
    service.create(OTHER, "别人组", "error", 1);

    List<Map<String, Object>> mine = service.list(MINE);

    assertThat(mine).hasSize(1);
    // 只断言"secret 为 null"是不够的：视图里根本没有这个键，客户端再怎么解析也拿不到
    assertThat(mine.get(0)).doesNotContainKey("secret");
    assertThat(mine.get(0)).containsEntry("channel_no", created.channelNo());
    assertThat(mine.get(0)).containsEntry("max_level", "warning").containsEntry("max_images", 2);
    // §14：改不动的东西至少得看得见，否则"我没配过"和"配了没生效"分不开
    assertThat(mine.get(0)).containsEntry("strict_auth", 0).containsEntry("muted", 0)
        .containsEntry("sender_whitelist_mode", 0).containsEntry("daily_msg_limit", 0);
  }

  @Test
  @DisplayName("⑩ 治理位写进去才读得出来：null 表示不动它，越界一律 400")
  void governanceRoundTrips() {
    Map<String, Object> after = service.updateGovernance(created.channelNo(), MINE,
        new ChannelService.Patch(null, null, null, 30, 65536, 1, 1, 500, 200, 4_000_000L, null));

    assertThat(after).containsEntry("rate_limit", 30).containsEntry("max_body_bytes", 65536)
        .containsEntry("strict_auth", 1).containsEntry("muted", 1)
        .containsEntry("daily_msg_limit", 500).containsEntry("daily_image_limit", 200)
        .containsEntry("daily_bytes_limit", 4_000_000L);
    // 没提交的那一列保持原值，这是 PUT 能增量提交的全部意义
    assertThat(after).containsEntry("max_images", 2).containsEntry("max_level", "warning");
    assertThat(store.findChannel(created.channelNo()).getInt("strict_auth")).isEqualTo(1);

    assertThatThrownBy(() -> service.updateGovernance(created.channelNo(), MINE,
        new ChannelService.Patch(null, null, null, -1, null, null, null, null, null, null, null)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
    assertThatThrownBy(() -> service.updateGovernance(created.channelNo(), MINE,
        new ChannelService.Patch(null, null, null, null, null, 2, null, null, null, null, null)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
    // 一个字段都不提交不是一次"成功的空操作"，是一次说错了的请求
    assertThatThrownBy(() -> service.updateGovernance(created.channelNo(), MINE,
        new ChannelService.Patch(null, null, null, null, null, null, null, null, null, null, null)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
  }

  @Test
  @DisplayName("⑪ 白名单：先加人再开门，空列表就开门要当场拒")
  void whitelistNeedsMembersBeforeItOpens() {
    assertThat(service.listSenders(created.channelNo(), MINE)).isEmpty();

    assertThatThrownBy(() -> service.updateGovernance(created.channelNo(), MINE,
        new ChannelService.Patch(null, null, null, null, null, null, null, null, null, null, 1)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));

    service.setSenderAllowed(created.channelNo(), MINE, "  ops-bot  ", true);
    Map<String, Object> after = service.updateGovernance(created.channelNo(), MINE,
        new ChannelService.Patch(null, null, null, null, null, null, null, null, null, null, 1));
    assertThat(after).containsEntry("sender_whitelist_mode", 1);

    // 停用不是删除：这条记录还在列表里，只是 enabled=0
    service.setSenderAllowed(created.channelNo(), MINE, "audit-bot", false);
    assertThat(service.listSenders(created.channelNo(), MINE))
        .extracting(item -> item.get("sender_id"))
        .containsExactlyInAnyOrder("ops-bot", "audit-bot");
    assertThat(store.isSenderAllowed(created.channelNo(), "ops-bot")).isTrue();
    assertThat(store.isSenderAllowed(created.channelNo(), "audit-bot")).isFalse();

    assertThatThrownBy(() -> service.listSenders(created.channelNo(), OTHER))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThatThrownBy(() -> service.setSenderAllowed(created.channelNo(), MINE, "   ", true))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
  }

  @Test
  @DisplayName("⑧ 频道号码重置：换新对外号，旧号即刻失效，会话/消息随迁，secret 与配置保留")
  void resetChannelNoMigratesConversations() {
    String oldNo = created.channelNo();
    long now = System.currentTimeMillis();
    store.upsertSender(oldNo, "ops-bot", "运维机器人", null, "tok", now);
    store.insertMessage("m_reset_1", oldNo, MINE, "ops-bot", "text", "标题", "正文", "摘要", null,
        "warning", "none", now, null);

    ChannelService.Created reset = service.resetChannelNo(oldNo, MINE);

    assertThat(reset.channelNo()).isNotEqualTo(oldNo).matches("\\d{8}");
    // 旧号即刻作废：第三方再往它发消息会走"频道不存在→401"，等于对外地址换了
    assertThat(store.findChannel(oldNo)).isNull();
    assertThat(store.findChannel(reset.channelNo())).isNotNull();
    // secret 不受影响：库里摘要仍是创建时那把（重置号码只换对外地址，不换凭据）
    assertThat(store.findChannel(reset.channelNo()).getString("secret_hash"))
        .isEqualTo(ChannelService.secretHash(created.secret()));
    // 会话与消息迁到新号，名字/强度保留
    Row moved = Db.selectOneBySql("SELECT channel_no FROM messages WHERE msg_id = ?", "m_reset_1");
    assertThat(moved.getString("channel_no")).isEqualTo(reset.channelNo());
    assertThat(Db.selectOneBySql(
        "SELECT sender_id FROM senders WHERE channel_no = ? AND sender_id = ?", reset.channelNo(),
        "ops-bot")).isNotNull();
    assertThat(reset.name()).isEqualTo("运维告警频道");
    assertThat(reset.maxLevel()).isEqualTo("warning");
  }

  @Test
  @DisplayName("⑨ 跨组重置不动别人的号：按 (channel_no, group_id) 收口，失败 404")
  void otherGroupCannotResetChannelNo() {
    assertThatThrownBy(() -> service.resetChannelNo(created.channelNo(), OTHER))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    // 归属校验失败时旧号必须还活着，不能被半途迁移
    assertThat(store.findChannel(created.channelNo())).isNotNull();
  }
}
