package dev.pushbox.server.service;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.media.MediaFetchService;
import dev.pushbox.server.repo.PbStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * §4.5 的抓取重试：入站那一次抓取失败（远端 5xx、超时、DNS 抽风）不代表这张图永远拿不到，
 * 但重试绝不能挡住主链路，所以放在定时任务里，每轮最多重抓 BATCH 条消息。
 * 退避就是巡检间隔本身：pb.media.retry-interval 是同一张图两次尝试之间的最小距离，
 * 不再逐槽位维护指数退避表 —— 一张失效外链值不值得为它加一张表，答案是不值。
 * 顺带回收进程重启留下的 pending 僵尸（抓取到一半被打断，那行永远停在 pending）。
 */
@Service
public class MediaRetryService {

  private static final Logger log = LoggerFactory.getLogger(MediaRetryService.class);

  /** 一轮最多重抓这么多条消息：某个图床挂了几天留下的积压要慢慢消化，不能一次占满抓取池。 */
  static final int BATCH = 50;

  /** pending 要老到这个程度才算僵尸：比这年轻的可能正被入站那条抓取线程抓着，重复抓只是白费流量。 */
  static final long MIN_PENDING_STALE_MS = 60_000L;

  private final PbStore store;
  private final MediaFetchService fetcher;
  private final NotifyCoordinator notifier;
  private final PbProperties.Media config;
  /** 巡检跑在抓取线程池上，fixedDelay 只量定时器线程的空闲，所以上一轮没完时这一轮必须让路。 */
  private final AtomicBoolean running = new AtomicBoolean();

  public MediaRetryService(PbStore store, MediaFetchService fetcher, NotifyCoordinator notifier,
      PbProperties props) {
    this.store = store;
    this.fetcher = fetcher;
    this.notifier = notifier;
    this.config = props.getMedia();
  }

  @Scheduled(initialDelayString = "${pb.media.retry-initial-delay:2m}",
      fixedDelayString = "${pb.media.retry-interval:5m}")
  public void scheduledSweep() {
    // retry-max<=1 就是"只认入站那一次"，语义上是关掉重试
    if (!config.isEnabled() || config.getRetryMax() <= 1 || !running.compareAndSet(false, true)) {
      return;
    }
    fetcher.submit(() -> {
      try {
        int republished = sweep();
        if (republished > 0) {
          log.info("抓取重试补发了 {} 条消息的图片状态", republished);
        }
      } catch (RuntimeException e) {
        // 抛出去只会让 Spring 记一次栈，下一轮照常，所以这里吞掉
        log.warn("抓取重试巡检失败: {}", e.toString());
      } finally {
        running.set(false);
      }
      // 队列满就这一轮不做了：running 必须当场放开，否则一次排不上队就永久关掉重试
    }, () -> running.set(false));
  }

  /** 返回这一轮真的改了结论、因而补发了帧的消息数。 */
  public int sweep() {
    List<Row> due = store.mediaRetryCandidates(
        pendingStaleBefore(System.currentTimeMillis(), config.getTimeout(), config.getMaxImages()),
        config.getRetryMax(), BATCH);
    int republished = 0;
    for (Row row : due) {
      if (retryOne(row.getString("msg_id"), row.getString("group_id"))) {
        republished++;
      }
    }
    return republished;
  }

  /** 抓取本身失败也会记账（fetch_attempts 已经加过），下一轮再来自会到 retry-max 为止。 */
  private boolean retryOne(String msgId, String groupId) {
    // 快照必须取在 reset 之前：reset 会把 failed 改成 pending，取晚了就等于"pending→failed"，
    // 于是一张坏图每轮都被当成新结论补发一帧，直到次数用完（首跑日志里就是这么刷屏的）。
    Map<Integer, String> before = slotStatuses(msgId);
    store.resetMediaForRetry(msgId, config.getRetryMax());
    fetcher.fetch(msgId);
    List<Integer> changed = changedSlots(before, slotStatuses(msgId));
    if (changed.isEmpty()) {
      return false;
    }
    // §5.3：如果所有槽位都变成 failed，发一条 media_failed 合并帧，客户端显示"图片已失效"。
    Map<Integer, String> after = slotStatuses(msgId);
    boolean allFailed = after.values().stream().allMatch(s -> "failed".equals(s));
    if (allFailed && !after.isEmpty()) {
      notifier.republishFailed(msgId, groupId, new ArrayList<>(after.keySet()));
    } else {
      notifier.republish(msgId, groupId, changed);
    }
    return true;
  }

  private Map<Integer, String> slotStatuses(String msgId) {
    Map<Integer, String> statuses = new LinkedHashMap<>();
    for (Row row : store.listMedia(msgId)) {
      Integer idx = row.getInt("idx");
      statuses.put(idx == null ? 0 : idx, row.getString("status"));
    }
    return statuses;
  }

  /**
   * 只补发"结论变了"的槽位：还是 failed 的客户端早就看过了，重复推同一条帧只是流量；
   * 还在 pending 的不算结论（窗口那条路径负责它）。
   */
  static List<Integer> changedSlots(Map<Integer, String> before, Map<Integer, String> after) {
    List<Integer> changed = new ArrayList<>();
    for (Map.Entry<Integer, String> entry : after.entrySet()) {
      String status = entry.getValue();
      if (!"pending".equals(status) && !status.equals(before.get(entry.getKey()))) {
        changed.add(entry.getKey());
      }
    }
    return changed;
  }

  static long pendingStaleBefore(long now, Duration timeout, int maxImages) {
    return now - Math.max(MIN_PENDING_STALE_MS, timeout.toMillis() * Math.max(1, maxImages) * 2);
  }
}
