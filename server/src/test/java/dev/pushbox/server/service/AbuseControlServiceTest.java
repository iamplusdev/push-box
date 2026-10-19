package dev.pushbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §4.4 的限流与凭据锁定。锁定状态在库里（{@code channels.fail_count/locked_until}），
 * 所以这条必须跑真库；窗口计数是内存的，每个用例都 new 一个新 service 保证互不串扰。
 */
@EnabledIfEnvironmentVariable(named = "PB_S3_ACCESS_KEY", matches = ".+")
// RANDOM_PORT 不是随手写的：MOCK 上下文里 WebSocketConfig 的 ServletServerContainerFactoryBean
// 拿不到 jakarta.websocket.server.ServerContainer，整个应用上下文直接启动失败。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AbuseControlServiceTest {

  private static final String CHANNEL = "ABUS0001";
  private static final String GROUP = "abus-test-group";

  @Autowired
  private PbStore store;

  private SimpleMeterRegistry meters;
  private PbProperties props;
  private AbuseControlService abuse;

  @BeforeEach
  void setUp() {
    meters = new SimpleMeterRegistry();
    props = new PbProperties();
    // 把阈值压到个位数，用例才不必真打 60 次；burst 设 0，避免它把小阈值淹掉
    props.getRatelimit().setPerChannelPerMin(2);
    props.getRatelimit().setPerIpPerMin(1);
    props.getRatelimit().setPerDevicePerMin(2);
    props.getRatelimit().setBurst(0);
    props.getIngest().setCredentialFailLock(3);
    props.getIngest().setLockWindow(Duration.ofMinutes(5));
    abuse = new AbuseControlService(store, props, meters);
    long now = System.currentTimeMillis();
    store.insertChannel(CHANNEL, ChannelService.secretHash("abus-secret"), GROUP, "限流测试",
        "error", 4, now);
    resetCredentialState();
  }

  @AfterEach
  void tearDown() {
    Db.updateBySql("DELETE FROM channels WHERE channel_no = ?", CHANNEL);
  }

  @Test
  @DisplayName("① 频道窗口以行上的 rate_limit 为准，命中只给 dim=channel 的 counter 计数")
  void channelWindowUsesTheRowLimit() {
    // channels.rate_limit 是 NOT NULL DEFAULT 60，建频道时不写这一列，所以"行值优先"是常态；
    // props 这里给 2，若代码错读了 props 就会放行到第 3 条。
    Db.updateBySql("UPDATE channels SET rate_limit = 1 WHERE channel_no = ?", CHANNEL);
    Row channel = store.findChannel(CHANNEL);

    assertThat(abuse.channelAllowed(channel)).isTrue();
    assertThat(abuse.channelAllowed(channel)).isFalse();
    assertThat(counter("channel")).isEqualTo(1.0);
    assertThat(counter("ip")).isZero();
  }

  @Test
  @DisplayName("② rate_limit=0 不代表「一条都不许发」，而是「这列没配」：回退到全局默认")
  void zeroRowLimitFallsBackToProperties() {
    Db.updateBySql("UPDATE channels SET rate_limit = 0 WHERE channel_no = ?", CHANNEL);
    Row channel = store.findChannel(CHANNEL);

    // 若把 0 当成真上限，任何写 0 的迁移都会静默封掉整个频道
    assertThat(abuse.channelAllowed(channel)).isTrue();
    assertThat(abuse.channelAllowed(channel)).isTrue();
    assertThat(abuse.channelAllowed(channel)).isFalse();
    assertThat(counter("channel")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("③ IP 与设备维度各自独立计数")
  void ipAndDeviceWindows() {
    assertThat(abuse.ipAllowed("203.0.113.9")).isTrue();
    assertThat(abuse.ipAllowed("203.0.113.9")).isFalse();
    assertThat(abuse.ipAllowed("203.0.113.10")).isTrue();

    assertThat(abuse.deviceAllowed("dev-a")).isTrue();
    assertThat(abuse.deviceAllowed("dev-a")).isTrue();
    assertThat(abuse.deviceAllowed("dev-a")).isFalse();
    assertThat(abuse.deviceAllowed("dev-b")).isTrue();

    assertThat(counter("ip")).isEqualTo(1.0);
    assertThat(counter("device")).isEqualTo(1.0);
  }

  @Test
  @DisplayName("④ nonce 只认第一次：第二次算重放")
  void nonceIsReplayOnSecondUse() {
    assertThat(abuse.nonceReplayed("dev-a:nonce-1")).isFalse();
    assertThat(abuse.nonceReplayed("dev-a:nonce-1")).isTrue();
    assertThat(abuse.nonceReplayed("dev-b:nonce-1")).isFalse();
  }

  @Test
  @DisplayName("⑤ 凭据错误到达阈值才锁，locked_until 落库；清一次就解锁")
  void credentialFailuresLockTheChannel() {
    Row channel = store.findChannel(CHANNEL);
    assertThat(abuse.lockedUntil(channel)).isZero();

    assertThat(abuse.registerCredentialFailure(CHANNEL)).isFalse();
    assertThat(abuse.registerCredentialFailure(CHANNEL)).isFalse();
    long beforeLock = System.currentTimeMillis();
    assertThat(abuse.registerCredentialFailure(CHANNEL)).isTrue();

    Row locked = store.findChannel(CHANNEL);
    assertThat(locked.getInt("fail_count")).isEqualTo(3);
    assertThat(abuse.lockedUntil(locked)).isGreaterThan(beforeLock);
    assertThat(counter("channel")).isZero();
    assertThat(meters.get("pushbox.ingest.credential_locked").counter().count()).isEqualTo(1.0);

    abuse.clearCredentialFailures(CHANNEL);
    Row cleared = store.findChannel(CHANNEL);
    assertThat(cleared.getInt("fail_count")).isZero();
    assertThat(abuse.lockedUntil(cleared)).isZero();
  }

  @Test
  @DisplayName("⑥ 未知频道的凭据错误不炸库，也只记一次内存计数")
  void unknownChannelFailureIsTolerated() {
    assertThat(abuse.registerCredentialFailure("99999999")).isFalse();

    Row channel = store.findChannel("99999999");
    assertThat(channel).isNull();
  }

  private void resetCredentialState() {
    // 两列都是 NOT NULL DEFAULT 0，"没锁"在这张表里是 0 而不是 NULL
    Db.updateBySql("UPDATE channels SET fail_count = 0, locked_until = 0 WHERE channel_no = ?",
        CHANNEL);
  }

  private double counter(String dim) {
    return meters.get("pushbox.ratelimit.rejected").tag("dim", dim).counter().count();
  }
}
