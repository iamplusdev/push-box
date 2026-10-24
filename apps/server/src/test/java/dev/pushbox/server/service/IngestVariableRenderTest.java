package dev.pushbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.api.IngestRequest;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.config.ServerIdentity;
import dev.pushbox.server.media.MediaFetchService;
import dev.pushbox.server.push.FrameAssembler;
import dev.pushbox.server.repo.PbStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 正文变量的两条入口：第三方 {@code POST /message} 会展开，定时推送走内部入口不再展开。
 * 不用真库——这里要锁的是"哪条路渲染、渲染成什么"，落库本身由 IngestServiceTest 那批守着。
 */
class IngestVariableRenderTest {

  private static final String CHANNEL = "VAR00001";
  private static final String SECRET = "0123456789abcdef0123456789abcdef";
  private static final String IP = "203.0.113.9";

  private PbStore store;
  private AbuseControlService abuse;
  private IngestService ingest;

  @BeforeEach
  void setUp() {
    store = mock(PbStore.class);
    abuse = mock(AbuseControlService.class);
    ingest = new IngestService(store, new PbProperties(), abuse, mock(FrameAssembler.class),
        mock(NotifyCoordinator.class), mock(MediaFetchService.class),
        new ServerIdentity(Optional.empty()), new SimpleMeterRegistry());
    when(store.findMessageWithSender(anyString())).thenReturn(stored());
  }

  @Test
  @DisplayName("① 第三方正文展开变量：时区取 ${timezone:...}，频道名/发送者名来自本次请求")
  void externalContentIsRendered() {
    stubChannelAndAbuse();

    ingest.ingest(request("${timezone:Asia/Tokyo} | ${timezone} | ${channel_name} | "
        + "${sender_name} | ${version} | ${nope}"), IP);

    // ${version} 在 mvn test 下没有 build-info，恒为 unknown；认不出的 ${nope} 原样保留
    assertThat(bodySent()).isEqualTo(
        "Asia/Tokyo | Asia/Tokyo | 变量测试频道 | ddns-go | unknown | ${nope}");
  }

  @Test
  @DisplayName("② 没写时区就用默认时区，不跟部署机器的 TZ 走")
  void missingTimezoneFallsBackToDefault() {
    stubChannelAndAbuse();

    ingest.ingest(request("${timezone} / ${timezone:Mars/Olympus}"), IP);

    assertThat(bodySent()).isEqualTo("Asia/Shanghai / Asia/Shanghai");
  }

  @Test
  @DisplayName("③ 定时推送的内部入口不二次展开：正文里的占位符原样落库")
  void internalContentIsNotRendered() {
    ingest.ingestInternal(channel(), request("${now} / ${channel_name}"));

    assertThat(bodySent()).isEqualTo("${now} / ${channel_name}");
  }

  private void stubChannelAndAbuse() {
    Row channel = channel();
    when(store.findChannel(CHANNEL)).thenReturn(channel);
    when(abuse.ipAllowed(IP)).thenReturn(true);
    when(abuse.lockedUntil(channel)).thenReturn(0L);
    when(abuse.channelAllowed(channel)).thenReturn(true);
    when(abuse.senderAllowed(eq(CHANNEL), anyString(), eq(channel))).thenReturn(true);
    when(abuse.dailyQuotaAllowed(channel)).thenReturn(true);
  }

  /** 抓 ingestAccepted 真正写进 messages.body 的那一列。 */
  private String bodySent() {
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(store).insertMessage(anyString(), anyString(), anyString(), anyString(), anyString(),
        any(), body.capture(), any(), any(), any(), any(), anyLong(), any());
    return body.getValue();
  }

  private static IngestRequest request(String content) {
    return new IngestRequest(CHANNEL, SECRET, null, content, null, "ddns-go", null,
        null, null, null, null, null, null, null, null);
  }

  private static Row channel() {
    Row row = new Row();
    row.set("channel_no", CHANNEL);
    row.set("group_id", "var-test-group");
    row.set("name", "变量测试频道");
    row.set("secret_hash", ChannelService.secretHash(SECRET));
    row.set("max_level", "warning");
    row.set("max_images", 4);
    row.set("max_body_bytes", 32768);
    return row;
  }

  private static Row stored() {
    Row row = new Row();
    row.set("msg_id", "01VAR0000000000000000000");
    row.set("seq", 1L);
    row.set("summary", "摘要");
    row.set("media_status", "none");
    row.set("level", "info");
    return row;
  }
}
