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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §5.2 频道接入（订阅）的真库用例。两件事最要紧：
 * <b>接入是投递过滤器不是权限边界</b>（§4.1 的读凭据仍是设备 token，密钥只作二次确认），
 * 以及<b>过滤模式必须由用户显式打开</b>——接第一个频道就静默掐断其余频道，是用户没要求过的动作。
 */
@EnabledIfEnvironmentVariable(named = "PB_S3_ACCESS_KEY", matches = ".+")
// RANDOM_PORT 不是随手写的：MOCK 上下文里 WebSocketConfig 的 ServletServerContainerFactoryBean
// 拿不到 jakarta.websocket.server.ServerContainer，整个应用上下文直接启动失败。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChannelSubscriptionTest {

  private static final String MINE = "chan-sub-group";
  private static final String OTHER = "chan-sub-other";
  private static final String PHONE = "dev-sub-phone";
  private static final String PC = "dev-sub-pc";

  @Autowired
  private PbStore store;
  @Autowired
  private PbProperties props;

  private ChannelService service;
  private ChannelService.Created created;
  private ChannelService.Created second;

  @BeforeEach
  void setUp() {
    service = new ChannelService(store, props);
    created = service.create(MINE, "接入测试频道", "warning", 2);
    second = service.create(MINE, "第二个频道", "info", 1);
    service.create(OTHER, "别人组的频道", "info", 1);
    // 设备行由测试直接插：接入接口收的是 auth.device()，走真 HTTP 要整套签名，
    // 这一组用例盯的是订阅语义，签名那部分由 DeviceGuardTest 覆盖。
    insertDevice(PHONE, MINE, "tok-phone");
    insertDevice(PC, MINE, "tok-pc");
  }

  @AfterEach
  void tearDown() {
    // channel_subscriptions 对 device 与 channel 都开了 CASCADE，但测试库里有历史脏行，显式删干净
    Db.updateBySql("DELETE FROM channel_subscriptions WHERE device_id IN (?, ?)", PHONE, PC);
    Db.updateBySql("DELETE FROM devices WHERE device_id IN (?, ?)", PHONE, PC);
    List<Row> chans =
        Db.selectListBySql("SELECT channel_no FROM channels WHERE group_id IN (?, ?)", MINE, OTHER);
    for (Row c : chans) {
      Db.updateBySql("DELETE FROM messages WHERE channel_no = ?", c.getString("channel_no"));
    }
    Db.updateBySql("DELETE FROM channels WHERE group_id IN (?, ?)", MINE, OTHER);
  }

  private void insertDevice(String deviceId, String groupId, String token) {
    long now = System.currentTimeMillis();
    store.insertDevice(deviceId, groupId, pad44("pubkey-" + deviceId), pad44("encpub-" + deviceId),
        "测试设备", "test", pad43(token), now);
  }

  private static String pad44(String seed) {
    return pad(seed, 44);
  }

  private static String pad43(String seed) {
    return pad(seed, 43);
  }

  /** 列宽是写死的（CHAR(44)/CHAR(43)），测试用的假公钥与假 token 要按列宽补齐再截断。 */
  private static String pad(String seed, int width) {
    StringBuilder sb = new StringBuilder(seed);
    while (sb.length() < width) {
      sb.append('x');
    }
    return sb.substring(0, width);
  }

  private Row device(String deviceId) {
    return store.findDevice(deviceId);
  }

  @Test
  @DisplayName("① 接入成功：订阅行落库，列表里 subscribed=true，响应不带明文密钥")
  void joinRegistersSubscription() {
    Map<String, Object> resp = service.join(device(PHONE), created.channelNo(), created.secret());

    assertThat(resp).containsEntry("ok", true).containsEntry("channel_no", created.channelNo());
    assertThat(resp).doesNotContainKey("secret");
    assertThat(store.subscribedChannelNos(PHONE)).containsExactly(created.channelNo());

    List<Map<String, Object>> list = service.list(MINE, PHONE);
    assertThat(list).hasSize(2);
    assertThat(list).anySatisfy(item -> assertThat(item)
        .containsEntry("channel_no", created.channelNo()).containsEntry("subscribed", true));
    assertThat(list).anySatisfy(item -> assertThat(item)
        .containsEntry("channel_no", second.channelNo()).containsEntry("subscribed", false));
  }

  @Test
  @DisplayName("② 一台设备接入不动另一台：同 Space 的设备各管各的订阅集")
  void joinIsPerDevice() {
    service.join(device(PHONE), created.channelNo(), created.secret());

    assertThat(store.subscribedChannelNos(PC)).isEmpty();
    assertThat(service.list(MINE, PC)).allSatisfy(item -> assertThat(item).containsEntry(
        "subscribed", false));
  }

  @Test
  @DisplayName("③ 密钥错 401、密钥空 401、频道号空 400：都不是 404，好让用户知道号是对的")
  void badSecretIsUnauthorized() {
    // 造"错密钥"要避开原值：末位写死 '0' 有 1/16 概率撞上真密钥，那时断言必挂
    String wrong = created.secret().charAt(31) == '0'
        ? created.secret().substring(0, 31) + '1'
        : created.secret().substring(0, 31) + '0';
    assertThat(wrong).isNotEqualTo(created.secret());
    assertThatThrownBy(() -> service.join(device(PHONE), created.channelNo(), wrong))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(401));
    assertThatThrownBy(() -> service.join(device(PHONE), created.channelNo(), "   "))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(401));
    assertThatThrownBy(() -> service.join(device(PHONE), created.channelNo(), null))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(401));
    assertThatThrownBy(() -> service.join(device(PHONE), "  ", created.secret()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));

    // 试密钥失败不写 fail_count：那把锁保护的是公开入站路径，接入接口已经过设备签名
    assertThat(store.findChannel(created.channelNo()).getInt("fail_count")).isZero();
    assertThat(store.subscribedChannelNos(PHONE)).isEmpty();
  }

  @Test
  @DisplayName("④ 跨组、未知、已归档一律 404：不给枚举频道号码留缝隙")
  void foreignUnknownAndArchivedAreAllNotFound() {
    assertThatThrownBy(() -> service.join(device(PHONE), otherGroupChannelNo(), created.secret()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
    assertThatThrownBy(() -> service.join(device(PHONE), "00000000", created.secret()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));

    service.archive(second.channelNo(), MINE);
    assertThatThrownBy(() -> service.join(device(PHONE), second.channelNo(), second.secret()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
  }

  @Test
  @DisplayName("⑤ 重复接入幂等：只有一行，且首次的 created_at 不被刷新")
  void reJoinKeepsOneRowAndOriginalTimestamp() {
    long old = System.currentTimeMillis() - 100_000L;
    store.subscribeChannel(PHONE, created.channelNo(), old);

    service.join(device(PHONE), created.channelNo(), created.secret());

    Row row = Db.selectOneBySql(
        "SELECT created_at FROM channel_subscriptions WHERE device_id = ? AND channel_no = ?",
        PHONE, created.channelNo());
    assertThat(row).isNotNull();
    assertThat(row.getLong("created_at")).isEqualTo(old);
    assertThat(store.subscribedChannelNos(PHONE)).containsExactly(created.channelNo());
  }

  @Test
  @DisplayName("⑥ 取消接入幂等：真删掉回 1，本来就没接入回 0，未知号也回 0")
  void leaveIsIdempotent() {
    service.join(device(PHONE), created.channelNo(), created.secret());

    assertThat(service.leave(device(PHONE), created.channelNo())).isEqualTo(1);
    assertThat(store.subscribedChannelNos(PHONE)).isEmpty();
    assertThat(service.leave(device(PHONE), created.channelNo())).isZero();
    assertThat(service.leave(device(PHONE), "00000000")).isZero();
  }

  @Test
  @DisplayName("⑦ 跨组取消接入被拒：即使只是删自己的一行，语义上也不该越界")
  void leaveRejectsForeignGroup() {
    assertThatThrownBy(() -> service.leave(device(PHONE), otherGroupChannelNo()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
  }

  @Test
  @DisplayName("⑧ 过滤模式默认关：接入本身不改变这台设备能收到什么")
  void joiningDoesNotChangeDeliveryUntilModeIsOn() {
    service.join(device(PHONE), created.channelNo(), created.secret());

    assertThat(device(PHONE).getInt("subscribed_only")).isZero();
    assertThat(service.readScope(device(PHONE))).isNull();
    assertThat(store.deviceIdsAllowedForChannel(MINE, second.channelNo()))
        .containsExactlyInAnyOrder(PHONE, PC);
  }

  @Test
  @DisplayName("⑨ 开了过滤：内容帧只发已接入的频道，未接入的一个都不给")
  void filterModeNarrowsBothLiveAndReplay() {
    service.join(device(PHONE), created.channelNo(), created.secret());
    assertThat(service.setFilterMode(device(PHONE), 1)).isEqualTo(1);

    assertThat(device(PHONE).getInt("subscribed_only")).isEqualTo(1);
    assertThat(service.readScope(device(PHONE))).containsExactly(created.channelNo());
    assertThat(store.deviceIdsAllowedForChannel(MINE, created.channelNo()))
        .containsExactlyInAnyOrder(PHONE, PC);
    // 没接入第二个频道的那台开了过滤，就只剩未接入的 PC 能收到（PC 仍是全收）
    assertThat(store.deviceIdsAllowedForChannel(MINE, second.channelNo())).containsExactly(PC);

    // 关回 0 立刻恢复全收，订阅行还在——模式与订阅集是两件事，不能混成一个
    assertThat(service.setFilterMode(device(PHONE), 0)).isZero();
    assertThat(service.readScope(device(PHONE))).isNull();
    assertThat(store.subscribedChannelNos(PHONE)).containsExactly(created.channelNo());
  }

  @Test
  @DisplayName("⑩ 开了过滤但一条都没接入：读取范围是空集合，不是不过滤")
  void emptyScopeWithFilterOnSendsNothing() {
    assertThat(service.setFilterMode(device(PC), 1)).isEqualTo(1);

    // null 与 empty 是两种相反的结论，listMessagesAfter 靠这个区分「全收」与「全拒」
    assertThat(service.readScope(device(PC))).isNotNull().isEmpty();
    assertThat(store.deviceIdsAllowedForChannel(MINE, created.channelNo())).containsExactly(PHONE);
  }

  @Test
  @DisplayName("⑪ 过滤模式只认 0 与 1：越界 400，不许把随便一个非零数当成开")
  void filterModeRejectsOtherValues() {
    assertThatThrownBy(() -> service.setFilterMode(device(PHONE), 2))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
    assertThatThrownBy(() -> service.setFilterMode(device(PHONE), -1))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(400));
    assertThat(device(PHONE).getInt("subscribed_only")).isZero();
  }

  @Test
  @DisplayName("⑫ subscribed 键恒在： deviceId 已知时全 false 也要有，管理端调用才省略")
  void subscribedKeyIsAlwaysPresentForDevices() {
    List<Map<String, Object>> none = service.list(MINE, PHONE);
    assertThat(none).isNotEmpty();
    assertThat(none).allSatisfy(item -> assertThat(item).containsKey("subscribed"));

    // deviceId 为 null 是 CLI/管理端调用，这里没有"这台设备"的概念，宁可缺键也不给一个假 false
    List<Map<String, Object>> admin = service.list(MINE);
    assertThat(admin).isNotEmpty();
    assertThat(admin.get(0)).doesNotContainKey("subscribed");
  }

  @Test
  @DisplayName("⑬ 号码重置带着订阅一起迁：换号后不用逐台设备重新接入")
  void resetChannelNoCarriesSubscriptions() {
    service.join(device(PHONE), created.channelNo(), created.secret());

    ChannelService.Created reset = service.resetChannelNo(created.channelNo(), MINE);

    assertThat(reset.channelNo()).isNotEqualTo(created.channelNo());
    assertThat(store.subscribedChannelNos(PHONE)).containsExactly(reset.channelNo());
  }

  private String otherGroupChannelNo() {
    List<Row> rows = Db.selectListBySql(
        "SELECT channel_no FROM channels WHERE group_id = ? ORDER BY created_at LIMIT 1", OTHER);
    return rows.get(0).getString("channel_no");
  }
}
