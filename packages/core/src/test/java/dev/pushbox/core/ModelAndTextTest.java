package dev.pushbox.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pushbox.core.api.IngestRequest;
import dev.pushbox.core.api.IngestResponse;
import dev.pushbox.core.model.Level;
import dev.pushbox.core.model.MediaStatus;
import dev.pushbox.core.model.MsgType;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.core.protocol.Json;
import dev.pushbox.core.text.Summary;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelAndTextTest {

  @Test
  void summaryPrefersTitleAndStripsMarkdown() {
    String s = Summary.of("[严重] CPU 使用率 > 90%", "主机 **prod-03** 近 5 分钟均值 92%");
    assertEquals("[严重] CPU 使用率 > 90%", s);

    String fromBody = Summary.of(null, "# 标题\n- `code`\n> 引用内容\n正文 ![img](http://x) [查看](http://y)");
    assertEquals("标题 code 引用内容 正文 查看 http://y", fromBody);
  }

  @Test
  void summaryTruncatesByCodePointNotByUtf16Unit() {
    String emoji = "🟥".repeat(70);
    String cut = Summary.truncate(emoji, 60);
    assertEquals(60, cut.codePointCount(0, cut.length()));
    assertEquals("🟥".repeat(60), cut);
    assertTrue(cut.endsWith("🟥"));
  }

  @Test
  void longChineseContentIsBounded() {
    String body = "服务器磁盘使用率达到 91%".repeat(100);
    String summary = Summary.of(null, body);
    assertEquals(60, summary.codePointCount(0, summary.length()));
  }

  @Test
  void levelOnlyEverTruncatesDownward() {
    assertEquals(Level.ERROR, Level.ERROR.truncateTo(Level.ERROR));
    assertEquals(Level.WARNING, Level.ERROR.truncateTo(Level.WARNING));
    assertEquals(Level.INFO, Level.WARNING.truncateTo(Level.INFO));
    assertEquals(Level.INFO, Level.INFO.truncateTo(Level.ERROR));
    assertEquals(Level.INFO, Level.fromWire("critical"));
  }

  @Test
  void unknownMessageTypeDegradesToText() {
    assertEquals(MsgType.TEXT, MsgType.fromWire("audio"));
    assertEquals(MsgType.MARKDOWN, MsgType.fromWire("Markdown"));
    assertEquals("link", MsgType.LINK.wire());
  }

  @Test
  void mediaStatusAggregatesForMergeWindow() {
    assertEquals(MediaStatus.NONE, MediaStatus.aggregate(List.of()));
    assertEquals(MediaStatus.PENDING, MediaStatus.aggregate(List.of("ready", "pending")));
    assertEquals(MediaStatus.READY, MediaStatus.aggregate(List.of("ready", "ready")));
    assertEquals(MediaStatus.PARTIAL, MediaStatus.aggregate(List.of("ready", "failed")));
    assertEquals(MediaStatus.FAILED, MediaStatus.aggregate(List.of("failed")));
  }

  @Test
  void jsonWireUsesSnakeCaseAndToleratesUnknownFields() {
    String wire = Json.write(Frames.Hello.of(1L, 25, "fp", "nonce"));
    assertTrue(wire.contains("\"server_time\":1"), wire);
    assertTrue(wire.contains("\"heartbeat\":25"), wire);
    Frames.Hello back = Json.read(wire.replace("\"extra\":1,", "")
        .replace("\"heartbeat\":25", "\"heartbeat\":25,\"extra\":1"), Frames.Hello.class);
    assertEquals("fp", back.serverPubFp());
  }

  @Test
  void ingestRequestDefaultsUnknownSender() {
    IngestRequest blank = new IngestRequest("83271504", "sec", "text", "hi", "  ", null, null, null,
        null, null, null, null, null, null, null);
    assertEquals("unknown", blank.senderIdOrDefault());
    IngestResponse resp = IngestResponse.accepted("01J", 7L, "hi", true, 2, "info");
    assertEquals("queued", resp.mediaStatus());
    assertFalse(resp.mediaStatus().equals("none"));
  }
}
