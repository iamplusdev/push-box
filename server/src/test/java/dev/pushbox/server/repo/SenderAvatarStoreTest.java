package dev.pushbox.server.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import dev.pushbox.core.id.Ulid;
import dev.pushbox.server.media.MediaObjects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §4.5 头像的落库侧（B3）：帧与 history 里的 {@code sender_avatar} 必须是服务端自己的读路径，
 * 而且只在真的转存成功之后出现。这两条都由一条 SQL 表达式实现，所以要在真库上验，不能只读代码。
 */
@EnabledIfEnvironmentVariable(named = "PB_S3_ACCESS_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SenderAvatarStoreTest {

  private static final String CHANNEL = "AVTR0001";
  private static final String OTHER = "AVTR0002";
  private static final String THIRD = "AVTR0003";
  private static final String GROUP = "avtr-group-mine";
  private static final String OUTSIDE = "avtr-group-other";
  private static final String BOT = "avatar-bot";
  private static final String URL = "https://cdn.example.com/avatar.png";

  @Autowired
  private PbStore store;

  @BeforeEach
  void seedChannels() {
    cleanUp();
    long now = System.currentTimeMillis();
    store.insertChannel(CHANNEL, "0".repeat(64), GROUP, "头像测试频道", "error", 4, now);
    store.insertChannel(OTHER, "1".repeat(64), OUTSIDE, "别人组的频道", "error", 4, now);
    store.insertChannel(THIRD, "2".repeat(64), GROUP, "同组第二频道", "error", 4, now);
  }

  @AfterEach
  void cleanUp() {
    Db.updateBySql("DELETE FROM messages WHERE group_id IN (?,?)", GROUP, OUTSIDE);
    Db.updateBySql("DELETE FROM senders WHERE channel_no IN (?,?,?)", CHANNEL, OTHER, THIRD);
    Db.updateBySql("DELETE FROM channels WHERE channel_no IN (?,?,?)", CHANNEL, OTHER, THIRD);
  }

  @Test
  @DisplayName("① 档案写入：avatar_url 只存来源、只在非空时覆盖，token 每次跟着 sender_id 走")
  void storesSourceNotBytes() {
    store.upsertSender(CHANNEL, BOT, "头像机器人", URL, MediaObjects.avatarToken(BOT),
        System.currentTimeMillis());

    Row row = senderRow();
    assertThat(row.getString("avatar_url")).isEqualTo(URL);
    assertThat(row.getString("avatar_token")).isEqualTo(MediaObjects.avatarToken(BOT));
    assertThat(row.getString("avatar_key")).isNull();

    // 后续消息没自报头像：来源列要保住，不能因为这条没带就把已知的头像地址擦成 NULL
    store.upsertSender(CHANNEL, BOT, null, null, MediaObjects.avatarToken(BOT),
        System.currentTimeMillis());
    assertThat(senderRow().getString("avatar_url")).isEqualTo(URL);

    // 自报了新头像就覆盖来源，计数继续累加
    store.upsertSender(CHANNEL, BOT, "头像机器人", URL + "?v=2", MediaObjects.avatarToken(BOT),
        System.currentTimeMillis());
    assertThat(senderRow().getString("avatar_url")).isEqualTo(URL + "?v=2");
    assertThat(senderRow().getInt("msg_count")).isEqualTo(3);
  }

  @Test
  @DisplayName("② 转存成功之前 sender_avatar 是 NULL，写键之后只暴露服务端读路径")
  void avatarColumnOnlyAfterKey() {
    store.upsertSender(CHANNEL, BOT, "头像机器人", URL, MediaObjects.avatarToken(BOT),
        System.currentTimeMillis());
    String msgId = message(CHANNEL, GROUP);

    assertThat(store.findMessageWithSender(msgId).getString("sender_avatar")).isNull();

    store.setSenderAvatarKey(CHANNEL, BOT,
        MediaObjects.avatarKey(CHANNEL, MediaObjects.avatarToken(BOT)), "none", null);

    String expected = "/api/v1/avatars/" + MediaObjects.avatarToken(BOT);
    Row withSender = store.findMessageWithSender(msgId);
    assertThat(withSender.getString("sender_name")).isEqualTo("头像机器人");
    assertThat(withSender.getString("sender_avatar")).isEqualTo(expected);
    // 增量拉取走另一条 SQL，两边必须给同一个读路径，否则首帧有头像、补拉没有
    assertThat(store.listMessagesAfter(GROUP, 0L, 500, null))
        .filteredOn(r -> msgId.equals(r.getString("msg_id")))
        .allSatisfy(r -> assertThat(r.getString("sender_avatar")).isEqualTo(expected));
    // 第三方给的 URL 绝不能原样出现在下发数据里
    assertThat(withSender.getString("sender_avatar")).doesNotContain("cdn.example.com");
  }

  @Test
  @DisplayName("③ 复用判断只认「来源没变且已有键」，换头像要重抓")
  void reuseRequiresSameSource() {
    String key = MediaObjects.avatarKey(CHANNEL, MediaObjects.avatarToken(BOT));
    store.upsertSender(CHANNEL, BOT, "头像机器人", URL, MediaObjects.avatarToken(BOT),
        System.currentTimeMillis());
    store.setSenderAvatarKey(CHANNEL, BOT, key, "none", null);

    Row current = store.findSenderAvatar(CHANNEL, BOT);
    assertThat(current.getString("avatar_url")).isEqualTo(URL);
    assertThat(current.getString("avatar_key")).isEqualTo(key);

    // 来源变了：即使上一张图的键还在，也不能复用，否则头像永远停在第一张
    store.upsertSender(CHANNEL, BOT, "头像机器人", "https://cdn.example.com/other.png",
        MediaObjects.avatarToken(BOT), System.currentTimeMillis());
    Row changed = store.findSenderAvatar(CHANNEL, BOT);
    assertThat(changed.getString("avatar_url")).isEqualTo("https://cdn.example.com/other.png");
    assertThat(changed.getString("avatar_key")).isEqualTo(key);

    assertThat(store.findSenderAvatar(CHANNEL, "unseen-bot")).isNull();
  }

  @Test
  @DisplayName("④ 读接口查询按 group 收口：跨组查不到，没转存成功的 token 也查不到")
  void readLookupIsGroupScoped() {
    String token = MediaObjects.avatarToken(BOT);
    String key = MediaObjects.avatarKey(CHANNEL, token);
    store.upsertSender(CHANNEL, BOT, "头像机器人", URL, token, System.currentTimeMillis());
    // 同一个 sender_id 出现在别人组的频道里：token 一样，只能靠 group 定到唯一一行
    store.upsertSender(OTHER, BOT, "同名机器人", URL, token, System.currentTimeMillis());
    store.setSenderAvatarKey(CHANNEL, BOT, key, "none", null);

    assertThat(store.senderAvatarForRead(GROUP, token).getString("avatar_key")).isEqualTo(key);
    assertThat(store.senderAvatarForRead(OUTSIDE, token)).isNull();
    // 只有来源、还没转存成功：读接口不能给出一个 404 的地址
    assertThat(store.senderAvatarForRead(GROUP, MediaObjects.avatarToken("nobody"))).isNull();
  }

  @Test
  @DisplayName("⑤ 同组两个频道共用一个 sender_id：对象键按频道编，取哪一行必须是确定的")
  void sameSenderAcrossGroupChannelsIsDeterministic() {
    String token = MediaObjects.avatarToken(BOT);
    store.upsertSender(CHANNEL, BOT, "头像机器人", URL, token, System.currentTimeMillis());
    store.upsertSender(THIRD, BOT, "头像机器人", URL, token, System.currentTimeMillis());
    store.setSenderAvatarKey(THIRD, BOT, MediaObjects.avatarKey(THIRD, token), "none", null);

    // 只有第二频道存了头像时它才是答案
    assertThat(store.senderAvatarForRead(GROUP, token).getString("avatar_key"))
        .isEqualTo(MediaObjects.avatarKey(THIRD, token));

    store.setSenderAvatarKey(CHANNEL, BOT, MediaObjects.avatarKey(CHANNEL, token), "none", null);
    // 两边都有键之后按 channel_no 取第一行：不加 ORDER BY 的话同一条 URL 每次刷新可能换一张图
    assertThat(store.senderAvatarForRead(GROUP, token).getString("avatar_key"))
        .isEqualTo(MediaObjects.avatarKey(CHANNEL, token));
  }

  private Row senderRow() {
    return Db.selectOneBySql("SELECT * FROM senders WHERE channel_no=? AND sender_id=?",
        CHANNEL, BOT);
  }

  private String message(String channelNo, String groupId) {
    String msgId = Ulid.next();
    store.insertMessage(msgId, channelNo, groupId, BOT, "text", null, "头像链路测试正文", "摘要",
        null, "info", "none", System.currentTimeMillis(), null);
    return msgId;
  }
}
