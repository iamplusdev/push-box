package dev.pushbox.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pushbox.core.crypto.FrameChannel;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.ServerKeys;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 分发只该落在"这个 Space 里、订了这个频道"的连接上。
 * 会话表按组索引之后，最容易坏的不是过滤条件，而是索引本身：旧连接被替换、关闭时没有从分桶里摘掉，
 * 表现是给一条已死的 socket 反复下发，日志里只看得到"写入失败"。
 */
class PushRegistryDispatchTest {

  private final PbStore store = mock(PbStore.class);
  private final PushRegistry registry =
      new PushRegistry(new ServerKeys(store), new PbProperties(), store);

  @Test
  @DisplayName("① 一个 Space 的广播不会碰到另一个 Space 的设备")
  void broadcastsStayInsideTheirGroup() throws Exception {
    WebSocketSession a = raw();
    WebSocketSession b = raw();
    registry.open(a, "dev_a", "grp_1", channel(), new byte[32], new byte[32]);
    registry.open(b, "dev_b", "grp_2", channel(), new byte[32], new byte[32]);

    assertThat(registry.broadcast("grp_1", Frames.Simple.ping())).isEqualTo(1);

    verify(a, times(1)).sendMessage(any(TextMessage.class));
    verify(b, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  @DisplayName("② 频道帧只发给订阅了该频道的设备，未订阅的同组设备不收")
  void channelFramesOnlyReachSubscribedDevices() throws Exception {
    WebSocketSession subscribed = raw();
    WebSocketSession other = raw();
    registry.open(subscribed, "dev_1", "grp_1", channel(), new byte[32], new byte[32]);
    registry.open(other, "dev_2", "grp_1", channel(), new byte[32], new byte[32]);
    when(store.deviceIdsAllowedForChannel("grp_1", "chan_1")).thenReturn(List.of("dev_1"));

    int sent = registry.broadcastToChannel("grp_1", "chan_1", Frames.Simple.ping());

    assertThat(sent).isEqualTo(1);
    verify(subscribed, times(1)).sendMessage(any(TextMessage.class));
    verify(other, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  @DisplayName("③ 同一设备重连带新会话时，旧会话从组索引里摘掉，消息只发一次")
  void replacingADeviceLeavesNoStaleSessionInTheGroupIndex() throws Exception {
    WebSocketSession oldRaw = raw();
    WebSocketSession newRaw = raw();
    registry.open(oldRaw, "dev_1", "grp_1", channel(), new byte[32], new byte[32]);
    registry.open(newRaw, "dev_1", "grp_1", channel(), new byte[32], new byte[32]);

    assertThat(registry.broadcast("grp_1", Frames.Simple.ping())).isEqualTo(1);

    verify(newRaw, times(1)).sendMessage(any(TextMessage.class));
    verify(oldRaw, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  @DisplayName("④ 最后一条会话关闭后分桶清空，广播直接返回 0")
  void closingTheLastSessionDropsTheGroupBucket() throws Exception {
    WebSocketSession raw = raw();
    PushRegistry.Session session =
        registry.open(raw, "dev_1", "grp_1", channel(), new byte[32], new byte[32]);

    registry.close(session, "test");
    assertThat(registry.broadcast("grp_1", Frames.Simple.ping())).isZero();

    verify(raw, never()).sendMessage(any(TextMessage.class));
  }

  private static FrameChannel channel() {
    return new FrameChannel(new byte[32], System.currentTimeMillis());
  }

  private static WebSocketSession raw() {
    WebSocketSession raw = mock(WebSocketSession.class);
    when(raw.getId()).thenReturn("ws");
    when(raw.isOpen()).thenReturn(true);
    return raw;
  }
}
