package dev.pushbox.server.service;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.model.MediaStatus;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.media.MediaFetchService;
import dev.pushbox.server.push.FrameAssembler;
import dev.pushbox.server.push.PushRegistry;
import dev.pushbox.server.repo.PbStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * §4.5 / D24 的合并窗口：带图消息在服务端等抓取到齐（pb.notify.media-wait，默认 3s），
 * 到齐就下发**一条**图文齐全的 message 帧；窗口先到期则用已有结果下发（缺的标 pending），
 * 之后每张图到终态补一条 media_ready —— 同 id 同 seq，不重复通知、不推进已读游标。
 */
@Service
public class NotifyCoordinator {

  private static final Logger log = LoggerFactory.getLogger(NotifyCoordinator.class);

  private final PbStore store;
  private final MediaFetchService fetcher;
  private final FrameAssembler assembler;
  private final PushRegistry registry;
  private final PbProperties props;
  /** 窗口的到期时刻与抓取进度无关，必须由独立定时器触发，否则抓取线程正阻塞在下载上就没人发第一帧。 */
  private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread thread = new Thread(r, "media-window");
    thread.setDaemon(true);
    return thread;
  });

  public NotifyCoordinator(PbStore store, MediaFetchService fetcher, FrameAssembler assembler,
      PushRegistry registry, PbProperties props) {
    this.store = store;
    this.fetcher = fetcher;
    this.assembler = assembler;
    this.registry = registry;
    this.props = props;
  }

  /** 纯文字不进窗口：P0 验收①"1 秒内出通知"靠的就是这条路径不被抓取拖住。 */
  public void publishNow(Row message, String groupId, boolean notifyClient) {
    broadcast(message, groupId, notifyClient);
  }

  /** 带图消息的入口：第三方的 200 早就回了，窗口定时器与抓取都跑在服务端后台。 */
  public void accept(String msgId, String groupId, boolean notifyClient) {
    if (!props.getMedia().isEnabled()) {
      publishNow(store.findMessageWithSender(msgId), groupId, notifyClient);
      return;
    }
    Window window = new Window(msgId, groupId, notifyClient);
    ScheduledFuture<?> timer = timers.schedule(window, props.getNotify().getMediaWait().toMillis(),
        TimeUnit.MILLISECONDS);
    fetcher.submit(() -> {
      try {
        fetcher.fetch(msgId, window);
      } catch (RuntimeException e) {
        log.warn("合并窗口抓取异常 msg_id={}: {}", msgId, e.toString());
      } finally {
        timer.cancel(false);
        window.run();
      }
    }, () -> {
      // 抓取排不上队：槽位立刻记 queue_full 终态并照常下发。宁可让客户端画"图片已失效"，
      // 也不能让一条已入库的消息因为没有第一帧而永远消失。
      timer.cancel(false);
      fetcher.failPending(msgId, MediaFetchService.QUEUE_FULL);
      window.onQueueFull();
    });
  }

  private void broadcast(Row message, String groupId, boolean notifyClient) {
    Frames.Message frame = assembler.capFrame(assembler.toFrame(message, notifyClient),
        props.getWs().getFrameMaxBytes());
    int delivered = registry.broadcastToChannel(groupId, message.getString("channel_no"), frame);
    log.info("消息已下发 msg_id={} seq={} media_status={} notify={} 在线下发={}",
        message.getString("msg_id"), message.getLong("seq"), message.getString("media_status"),
        notifyClient, delivered);
  }

  /**
   * 重试 sweep 的补发：帧形态与窗口内的 publishSlot 完全一致（同 id 同 seq、不带 notify），
   * 客户端按 id 原地把"图片已失效"换成图，不会重复弹通知。
   */
  public void republish(String msgId, String groupId, List<Integer> settledIdx) {
    Row message = store.findMessageWithSender(msgId);
    if (message == null) {
      return;
    }
    List<Frames.MediaSlot> slots = assembler.mediaSlots(message);
    List<Frames.MediaSlot> changed = slots.stream()
        .filter(s -> settledIdx.contains(s.idx()) && !"pending".equals(s.status()))
        .toList();
    if (changed.isEmpty()) {
      return;
    }
    List<String> statuses = slots.stream().map(Frames.MediaSlot::status).toList();
    int delivered = registry.broadcastToChannel(groupId, message.getString("channel_no"),
        Frames.MediaReady.of(msgId, message.getLong("seq"), changed, MediaStatus.aggregate(statuses).wire()));
    log.info("media_ready 重试补发 msg_id={} 槽={} 状态={} 在线下发={}", msgId, changed.size(),
        changed.stream().map(Frames.MediaSlot::status).toList(), delivered);
  }

  /** §5.3：重试耗尽后仍全失败的合并帧，客户端显示"图片已失效"。 */
  public void republishFailed(String msgId, String groupId, List<Integer> failedIdx) {
    Row message = store.findMessageWithSender(msgId);
    if (message == null) {
      return;
    }
    List<Frames.MediaSlot> slots = assembler.mediaSlots(message);
    List<Frames.MediaSlot> failed = slots.stream()
        .filter(s -> failedIdx.contains(s.idx()) && "failed".equals(s.status()))
        .toList();
    if (failed.isEmpty()) {
      return;
    }
    List<String> statuses = slots.stream().map(Frames.MediaSlot::status).toList();
    int delivered = registry.broadcastToChannel(groupId, message.getString("channel_no"),
        Frames.MediaReady.failed(msgId, message.getLong("seq"), failed, MediaStatus.aggregate(statuses).wire()));
    log.info("media_failed 重试补发 msg_id={} 槽={} 在线下发={}", msgId, failed.size(), delivered);
  }

  /**
   * 一个窗口实例既是定时器任务（到期先发），也是抓取回调（每张图到终态）。
   * 两条路径都会抢着下发第一帧，所以整个实例上锁，第一帧只可能发出去一次。
   */
  private final class Window implements Runnable, MediaFetchService.SlotHook {

    private final String msgId;
    private final String groupId;
    /** 频道静音在受理时就已经定下（那条路径手上就有 channels 行），窗口里不再回查，避免热路径多打一次库。 */
    private final boolean notifyClient;
    private final Set<Integer> announcedSettled = new HashSet<>();
    private boolean announced;

    private Window(String msgId, String groupId, boolean notifyClient) {
      this.msgId = msgId;
      this.groupId = groupId;
      this.notifyClient = notifyClient;
    }

    @Override
    public synchronized void run() {
      if (announced) {
        return;
      }
      announced = true;
      Row message = store.findMessageWithSender(msgId);
      if (message == null) {
        return;
      }
      List<Frames.MediaSlot> slots = assembler.mediaSlots(message);
      slots.stream().filter(s -> !"pending".equals(s.status()))
          .forEach(s -> announcedSettled.add(s.idx()));
      if (!slots.isEmpty() && announcedSettled.size() < slots.size()) {
        log.info("合并窗口到期，先发正文与已到齐的图 msg_id={} 窗口={}ms 缺={}张", msgId,
            props.getNotify().getMediaWait().toMillis(), slots.size() - announcedSettled.size());
      }
      broadcast(message, groupId, notifyClient);
    }

    /** 抓取排不上队的降级：第一帧没发就照常发（帧里那些图已经是 queue_full），已发则合并成一条 media_ready 补齐。 */
    synchronized void onQueueFull() {
      if (!announced) {
        run();
        return;
      }
      Row message = store.findMessageWithSender(msgId);
      if (message == null) {
        return;
      }
      List<Integer> settled = new ArrayList<>();
      assembler.mediaSlots(message).forEach(s -> {
        if (!"pending".equals(s.status())) {
          settled.add(s.idx());
        }
      });
      republish(msgId, groupId, settled);
    }

    @Override
    public synchronized void onSlotSettled(int idx) {
      if (!announced || announcedSettled.contains(idx)) {
        // 还没发第一帧：这张图的结果会直接进那条 message 帧，不需要补发
        return;
      }
      publishSlot(idx);
    }

    /** 窗口外才走到这里：单张图原地补齐，客户端按 id 合并，不会重复计数。 */
    private void publishSlot(int idx) {
      Row message = store.findMessageWithSender(msgId);
      if (message == null) {
        return;
      }
      List<Frames.MediaSlot> slots = assembler.mediaSlots(message);
      Frames.MediaSlot settled = slots.stream().filter(s -> s.idx() == idx).findFirst().orElse(null);
      if (settled == null || "pending".equals(settled.status())) {
        return;
      }
      List<String> statuses = new ArrayList<>(slots.size());
      slots.forEach(s -> statuses.add(s.status()));
      registry.broadcastToChannel(groupId, message.getString("channel_no"),
          Frames.MediaReady.of(msgId, message.getLong("seq"), List.of(settled), MediaStatus.aggregate(statuses).wire()));
      log.info("media_ready 已补发 msg_id={} idx={} status={}", msgId, idx, settled.status());
    }
  }

  @PreDestroy
  void shutdown() {
    timers.shutdownNow();
  }
}
