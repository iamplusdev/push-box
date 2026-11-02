package dev.pushbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.pushbox.server.repo.PbStore;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 留存清理的边界全在调度之外：默认关闭、按批循环、以及"选出来却删不掉"时不能原地打转。 */
class MessageRetentionSweepTest {

  private static final long NOW = 1_760_000_000_000L;

  @Test
  void disabledByDefaultDoesNotTouchTheStore() {
    PbStore store = mock(PbStore.class);
    MessageRetentionSweep sweep = new MessageRetentionSweep(store, 0);
    assertThat(sweep.sweep(NOW)).isZero();
    verifyNoInteractions(store);
  }

  @Test
  void deletesInFullBatchesUntilAShortBatchEndsTheSweep() {
    PbStore store = mock(PbStore.class);
    List<String> full = ids(MessageRetentionSweep.BATCH);
    List<String> rest = ids(3);
    when(store.expiredMessageIds(anyLong(), eq(MessageRetentionSweep.BATCH)))
        .thenReturn(full, rest);
    when(store.deleteMessagesByIds(full)).thenReturn(full.size());
    when(store.deleteMessagesByIds(rest)).thenReturn(rest.size());
    long cutoff = NOW - Duration.ofDays(30).toMillis();

    assertThat(new MessageRetentionSweep(store, 30).sweep(NOW))
        .isEqualTo(full.size() + rest.size());

    verify(store, times(2)).expiredMessageIds(eq(cutoff), eq(MessageRetentionSweep.BATCH));
    verify(store).markMediaDeletedMessages(full, NOW);
    verify(store).markMediaDeletedMessages(rest, NOW);
    verify(store).deleteMessagesByIds(full);
    verify(store).deleteMessagesByIds(rest);
  }

  @Test
  void anEmptyHistoryIsOneQueryAndNoWrites() {
    PbStore store = mock(PbStore.class);
    long cutoff = NOW - Duration.ofDays(7).toMillis();

    assertThat(new MessageRetentionSweep(store, 7).sweep(NOW)).isZero();

    verify(store).expiredMessageIds(cutoff, MessageRetentionSweep.BATCH);
    verifyNoMoreInteractions(store);
  }

  @Test
  void aBatchThatDeletesNothingEndsTheSweepInsteadOfSpinning() {
    PbStore store = mock(PbStore.class);
    List<String> full = ids(MessageRetentionSweep.BATCH);
    when(store.expiredMessageIds(anyLong(), anyInt())).thenReturn(full);
    when(store.deleteMessagesByIds(full)).thenReturn(0);

    assertThat(new MessageRetentionSweep(store, 30).sweep(NOW)).isZero();

    verify(store, times(1)).expiredMessageIds(anyLong(), anyInt());
    verify(store).deleteMessagesByIds(full);
  }

  private static List<String> ids(int count) {
    return IntStream.range(0, count).mapToObj(i -> "m" + i).toList();
  }
}
