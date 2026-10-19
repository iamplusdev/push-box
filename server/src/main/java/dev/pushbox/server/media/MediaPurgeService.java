package dev.pushbox.server.media;

import com.mybatisflex.core.row.Row;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * §4.5 的 media sweep：tombstone 只把对象标成待删，宽限期（默认 168h）内还能回滚，
 * 到期后由这个定时任务真正 DeleteObject。之所以不交给桶生命周期规则，是因为 tombstone 是
 * 对象粒度的，prefix 规则做不到"只删这一条消息的图"。
 */
@Component
public class MediaPurgeService {

  private static final Logger log = LoggerFactory.getLogger(MediaPurgeService.class);

  /** 一轮最多删这么多：宽限期一次性到期（比如清理了几天积压）不该把 S3 打满。 */
  private static final int BATCH = 200;

  private final PbStore store;
  private final MediaObjects objects;
  private final PbProperties.S3 config;

  public MediaPurgeService(PbStore store, MediaObjects objects, PbProperties props) {
    this.store = store;
    this.objects = objects;
    this.config = props.getS3();
  }

  @Scheduled(initialDelayString = "${pb.s3.purge-initial-delay:60s}",
      fixedDelayString = "${pb.s3.purge-interval:1h}")
  public void scheduledSweep() {
    try {
      int purged = sweep();
      if (purged > 0) {
        log.info("宽限期巡检真删了 {} 个媒体对象", purged);
      }
    } catch (RuntimeException e) {
      // 定时任务抛出去会被 Spring 记一次栈然后停掉这一轮的后续，这里吞掉等下一轮
      log.warn("宽限期巡检失败: {}", e.toString());
    }
  }

  /** 返回这一轮真删掉的对象数。删除顺序固定为"先删对象、后记状态"：中途崩溃只会重复删。 */
  public int sweep() {
    long notAfter = System.currentTimeMillis() - config.getDeleteGrace().toMillis();
    List<Row> due = store.mediaDueForPurge(notAfter, BATCH);
    int purged = 0;
    for (Row row : due) {
      objects.delete(row.getString("object_key"));
      objects.delete(row.getString("thumb_key"));
      store.markMediaPurged(row.getLong("id"));
      purged++;
      log.debug("媒体对象已清理 msg_id={} idx={} key={}", row.getString("msg_id"),
          row.getInt("idx"), row.getString("object_key"));
    }
    return purged;
  }
}
