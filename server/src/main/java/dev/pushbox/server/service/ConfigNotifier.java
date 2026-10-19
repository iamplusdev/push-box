package dev.pushbox.server.service;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.server.push.PushRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * §5.3 配置变更的即时通知。
 *
 * <p>动机：轮换密钥、重置频道号码、吊销设备这些动作过去只改库，在线的其他端要等到下一次
 * 401 才知道出过事——而 401 往往发生在这条链路的深处，排查成本全压在用户身上。
 * 这里补的只是"在线的一端即刻知道"：{@code config} 帧不带 seq，断线期间的变更靠建连后
 * 重新拉频道面兜住，不承诺可靠投递。
 */
@Service
public class ConfigNotifier {

  private static final Logger log = LoggerFactory.getLogger(ConfigNotifier.class);

  private final PushRegistry registry;

  public ConfigNotifier(PushRegistry registry) {
    this.registry = registry;
  }

  public void channel(Row channel, String reason) {
    channel(channel.getString("group_id"), reason, channel.getString("channel_no"));
  }

  public void channel(String groupId, String reason, String channelNo) {
    int delivered = registry.broadcast(groupId, Frames.Config.ofChannel(reason, channelNo));
    log.info("config 已广播 reason={} channel={} 在线下发={}", reason, channelNo, delivered);
  }

  public void device(String groupId, String reason, String deviceId) {
    int delivered = registry.broadcast(groupId, Frames.Config.ofDevice(reason, deviceId));
    log.info("config 已广播 reason={} device={} 在线下发={}", reason, deviceId, delivered);
  }

  /** 不针对具体频道或设备的组级变更（恢复码轮换就是这一类）。 */
  public void group(String groupId, String reason) {
    int delivered = registry.broadcast(groupId, Frames.Config.ofGroup(reason));
    log.info("config 已广播 reason={} 在线下发={}", reason, delivered);
  }
}
