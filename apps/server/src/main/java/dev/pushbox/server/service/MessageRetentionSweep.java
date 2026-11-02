package dev.pushbox.server.service;

import dev.pushbox.server.repo.PbStore;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按保留窗口清理旧消息，默认关闭（{@code pb.retention.message-days=0}）。
 *
 * <p>D3 的既定口径是"消息永不自动清理"，这里不是把它改掉，而是给持续写入的家庭/小团队部署留一个
 * 可选出口：消息表只涨不落，几年之后库和备份都会被历史撑大。所以默认值必须保持关闭——打开它等于
 * 部署者自己决定"超过窗口的历史不要了"，客户端本地已有的副本不受影响，但离线超过窗口的设备补拉
 * 时只会拿到剩下的部分，会话里会出现 seq 空洞（客户端按 after_seq 递增补拉，能接受空洞）。
 *
 * <p>一轮按批来：先给这批消息的图片标待删（对象仍由 MediaPurgeService 过宽限期真删），再硬删
 * messages 行。批大小和 MediaPurgeService 一样是常数，不跟着保留天数走。
 */
@Component
public class MessageRetentionSweep {

  private static final Logger log = LoggerFactory.getLogger(MessageRetentionSweep.class);

  /** 一轮删这么多条：一次大 DELETE 会长时间持锁，而清理本来就不需要争分夺秒。 */
  static final int BATCH = 500;

  private final PbStore store;
  private final int keepDays;

  public MessageRetentionSweep(PbStore store,
      @Value("${pb.retention.message-days:0}") int keepDays) {
    this.store = store;
    this.keepDays = keepDays;
  }

  @Scheduled(initialDelayString = "${pb.retention.sweep-initial-delay:10m}",
      fixedDelayString = "${pb.retention.sweep-interval:6h}")
  public void scheduledSweep() {
    try {
      int deleted = sweep(System.currentTimeMillis());
      if (deleted > 0) {
        log.info("按留存策略清理了 {} 条旧消息（保留 {} 天）", deleted, keepDays);
      }
    } catch (RuntimeException e) {
      // 和日计数器回收一样：这一轮失败就等下一轮，别让异常把调度线程拖停
      log.warn("消息留存清理失败: {}", e.toString());
    }
  }

  /** 单独开出来为了能在测试里定点跑一次；返回这一轮真删掉的消息条数。 */
  public int sweep(long now) {
    if (keepDays <= 0) {
      return 0;
    }
    long cutoff = now - Duration.ofDays(keepDays).toMillis();
    int total = 0;
    while (true) {
      List<String> ids = store.expiredMessageIds(cutoff, BATCH);
      if (ids.isEmpty()) {
        return total;
      }
      store.markMediaDeletedMessages(ids, now);
      int deleted = store.deleteMessagesByIds(ids);
      total += deleted;
      if (deleted == 0 || ids.size() < BATCH) {
        // 选出来了却删不掉：同样的条件下一轮还会选到它们，留在原地打转不如直接收工
        return total;
      }
    }
  }
}
