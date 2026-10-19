package dev.pushbox.server.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.core.protocol.Json;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §5.3 单帧削正文（capFrame）与 §4.5 的"纯文字不查媒体表"。
 * 两者都是纯函数级的判定，不需要库：FrameAssembler 这里的 store 故意传 null，
 * 一旦某条路径真的去查库就会 NullPointerException，用例自己会红。
 */
class FrameAssemblerCapTest {

  private final FrameAssembler assembler = new FrameAssembler(null);

  @Test
  @DisplayName("① 没超上限的帧原样返回：同一个对象，truncated 不被翻成 true")
  void frameWithinLimitIsUntouched() {
    Frames.Message frame = frame("短正文".repeat(3));

    assertThat(assembler.capFrame(frame, 4096)).isSameAs(frame);
  }

  @Test
  @DisplayName("② content 为 null（只有标题的帧）不削，也不 NPE")
  void nullContentIsUntouched() {
    Frames.Message frame = frame(null);

    assertThat(assembler.capFrame(frame, 64)).isSameAs(frame);
  }

  @Test
  @DisplayName("③ ASCII 超长：削到 maxBytes 以内、以省略号结尾，其余字段一字不动")
  void truncatesAsciiToFit() {
    Frames.Message capped = assembler.capFrame(frame("a".repeat(4000)), 512);
    int bytes = Json.writeBytes(capped).length;

    assertThat(capped.truncated()).isTrue();
    assertThat(capped.content()).endsWith("…").hasSizeLessThan(4000);
    assertThat(bytes).isLessThanOrEqualTo(512);
    assertThat(capped.seq()).isEqualTo(7L);
    assertThat(capped.id()).isEqualTo("MSG-1");
    assertThat(capped.title()).isEqualTo("标题");
    assertThat(capped.summary()).isEqualTo("摘要");
  }

  @Test
  @DisplayName("④ 中文正文：一轮删字符是下界估计，必须循环到真的装得下")
  void convergesOnMultiByteContent() {
    Frames.Message capped = assembler.capFrame(frame("订单服务告警".repeat(400)), 1024);
    int contentBytes = capped.content().getBytes(StandardCharsets.UTF_8).length;

    assertThat(capped.truncated()).isTrue();
    assertThat(Json.writeBytes(capped).length).isLessThanOrEqualTo(1024);
    // 预算大部分要用上：按"超出的字节数"删字符若只删一轮，这里会剩下几百字节空格
    assertThat(contentBytes).isBetween(600, 1024);
  }

  @Test
  @DisplayName("⑤ media_status 为 null 或 none 时不查媒体表（store 是 null 就是最好的证明）")
  void textOnlyMessagesSkipMediaQuery() {
    assertThat(assembler.mediaSlots(row(null))).isEmpty();
    assertThat(assembler.mediaSlots(row("none"))).isEmpty();
  }

  @Test
  @DisplayName("⑥ 削进代理对时退回上一个完整字符：正文里不许留半个 emoji")
  void neverSplitsSurrogatePair() {
    Frames.Message capped = assembler.capFrame(frame("🚨".repeat(600)), 512);
    String kept = capped.content().substring(0, capped.content().length() - 1);

    assertThat(capped.truncated()).isTrue();
    assertThat(Json.writeBytes(capped).length).isLessThanOrEqualTo(512);
    for (int i = 0; i < kept.length(); i++) {
      char c = kept.charAt(i);
      if (Character.isHighSurrogate(c)) {
        assertThat(Character.isLowSurrogate(kept.charAt(i + 1))).isTrue();
        i++;
      } else {
        assertThat(Character.isLowSurrogate(c)).isFalse();
      }
    }
  }

  private static Frames.Message frame(String content) {
    return Frames.Message.of(7L, "12345678", "MSG-1", "grafana", "text", "标题", "摘要", content,
        "info", null, List.of(), "Grafana 告警", null, 1_700_000_000_000L);
  }

  private static Row row(String mediaStatus) {
    Row row = new Row();
    row.set("msg_id", "MSG-1");
    row.set("media_status", mediaStatus);
    return row;
  }
}
