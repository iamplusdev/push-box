package dev.pushbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 重试 sweep 的两个纯判断（B2b）：哪些槽位值得补发、pending 僵尸的时间门槛。
 * 抓取本身要真联网，交给 MediaRetryStoreTest 与验收探针，这里只锁住不会随网络变化的规则。
 */
class MediaRetryLogicTest {

  private static Map<Integer, String> slots(Object... pairs) {
    Map<Integer, String> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((Integer) pairs[i], (String) pairs[i + 1]);
    }
    return map;
  }

  @Test
  @DisplayName("① 只有结论真的变了才补发：failed→ready 补，还是 failed 不补，pending 不算结论")
  void onlyChangedVerdictsAreRepublished() {
    Map<Integer, String> before = slots(0, "failed", 1, "failed", 2, "pending", 3, "ready");
    Map<Integer, String> after = slots(0, "ready", 1, "failed", 2, "pending", 3, "ready");

    assertThat(MediaRetryService.changedSlots(before, after)).containsExactly(0);
  }

  @Test
  @DisplayName("② 补发按 idx 升序且一次带上全部变化槽位（一帧多发，客户端按 idx 合并）")
  void keepsSlotOrderAndGroupsSlots() {
    Map<Integer, String> before = slots(0, "failed", 1, "failed", 2, "failed");
    Map<Integer, String> after = slots(0, "ready", 1, "failed", 2, "ready");

    assertThat(MediaRetryService.changedSlots(before, after)).containsExactly(0, 2);
  }

  @Test
  @DisplayName("③ 僵尸判定用抓取预算下界：小超时也要至少留 60s，免得和入站那次抓取抢同一行")
  void pendingStaleWindowNeverShorterThanFloor() {
    long now = 1_000_000_000_000L;
    // 10s × 4 张 × 2 = 80s，比 60s 的下界长，取预算
    assertThat(MediaRetryService.pendingStaleBefore(now, Duration.ofSeconds(10), 4))
        .isEqualTo(now - 80_000L);
    // 1s × 1 张 × 2 = 2s，太短，落到 60s 下界
    assertThat(MediaRetryService.pendingStaleBefore(now, Duration.ofSeconds(1), 1))
        .isEqualTo(now - MediaRetryService.MIN_PENDING_STALE_MS);
    assertThat(MediaRetryService.pendingStaleBefore(now, Duration.ofSeconds(10), 0))
        .isEqualTo(now - 60_000L);
  }
}
