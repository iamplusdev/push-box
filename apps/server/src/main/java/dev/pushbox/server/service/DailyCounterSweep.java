package dev.pushbox.server.service;

import dev.pushbox.server.repo.PbStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * §14 日计数器的回收。
 *
 * <p>{@code daily_counters} 每个频道每天一行、只进不出：跑一年，每个频道就多出 365 行没人读的字节。
 * 配额判断只看当天那一行（{@link AbuseControlService#dailyQuotaAllowed}），历史行的用处只在排障，
 * 所以留一个排障窗口就够了。
 *
 * <p>节奏与保留天数刻意用 {@code ${...}} 占位而不是塞进 {@link dev.pushbox.server.config.PbProperties}：
 * 那个类里已经躺着三个"有 getter 没人读"的死键，新旋钮只要能绑定就别再去扩充它。
 */
@Component
public class DailyCounterSweep {

  private static final Logger log = LoggerFactory.getLogger(DailyCounterSweep.class);

  private final PbStore store;
  private final int keepDays;

  public DailyCounterSweep(PbStore store,
      @Value("${pb.counters.retention-days:90}") int keepDays) {
    this.store = store;
    this.keepDays = keepDays;
  }

  @Scheduled(initialDelayString = "${pb.counters.sweep-initial-delay:10m}",
      fixedDelayString = "${pb.counters.sweep-interval:24h}")
  public void scheduledSweep() {
    try {
      int deleted = sweep();
      if (deleted > 0) {
        log.info("日计数器回收了 {} 行（保留 {} 天）", deleted, keepDays);
      }
    } catch (RuntimeException e) {
      // 定时任务抛出去只会被 Spring 记一轮栈，回收这件事错过一晚不要紧，别把调度线程拖停
      log.warn("日计数器回收失败: {}", e.toString());
    }
  }

  /** 单独开出来是为了能在测试与 CLI 里定点跑一次，不依赖调度节奏。 */
  public int sweep() {
    return store.deleteStaleDailyCounters(keepDays);
  }
}
