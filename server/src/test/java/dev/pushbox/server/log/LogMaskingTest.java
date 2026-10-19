package dev.pushbox.server.log;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 脱敏算法本身用例（纯 JVM，不起上下文）：§13 要求"打不出来"，
 * 所以这里钉的是两种写法（logfmt 与 JSON）各自的边界，而不是某一条业务日志。
 */
class LogMaskingTest {

  @Test
  @DisplayName("① logfmt 裸值：带空格的整段正文一起遮，后面的字段照常保留")
  void masksBareValueUpToNextField() {
    String out = LogMasking.mask("消息已受理 msg_id=m1 body=今天下午 三点在会议室 讨论排期 channel=8 level=info");

    assertThat(out).doesNotContain("今天下午").doesNotContain("三点在会议室").doesNotContain("讨论排期");
    assertThat(out).isEqualTo("消息已受理 msg_id=m1 body=*** channel=8 level=info");
  }

  @Test
  @DisplayName("② JSON 带引号的值整段遮，键名与结构留着方便排障")
  void masksJsonQuotedValue() {
    String out = LogMasking.mask("{\"msg_id\":\"m1\",\"body\":\"第一行\\\"含引号\\\" 第二行\",\"level\":\"info\"}");

    assertThat(out).doesNotContain("第一行").doesNotContain("第二行");
    assertThat(out).isEqualTo("{\"msg_id\":\"m1\",\"body\":\"***\",\"level\":\"info\"}");
  }

  @Test
  @DisplayName("③ 值是对象/数组时按配平整段遮，嵌套里层的 text/url 不会漏出来")
  void masksWholeNestedStructure() {
    String out = LogMasking.mask(
        "入站 content={\"parts\":[{\"text\":\"正文机密\"},{\"url\":\"http://x/a\"}]} channel=8");

    assertThat(out).doesNotContain("正文机密").doesNotContain("http://x/a");
    assertThat(out).isEqualTo("入站 content=*** channel=8");
  }

  @Test
  @DisplayName("④ 非敏感字段原样通过：排障要用的 id/seq/object_key 一个都不能少")
  void leavesNonSensitiveFieldsAlone() {
    String in = "图片已转存 msg_id=m1 idx=0 mime=image/png bytes=20480 reused=false "
        + "object_key=media/m1/0 seq=42 host=example.com port:8080 12:30";

    assertThat(LogMasking.mask(in)).isEqualTo(in);
  }

  @Test
  @DisplayName("⑤ 整词匹配：seq/object_key/top_query 这些包含敏感子串的键不会被牵连")
  void matchesWholeWordsOnly() {
    String in = "seq=7 object_key=k top_query=1 nearby=2 image_urls_are_trimmed=false";

    assertThat(LogMasking.mask(in)).isEqualTo(in);
    assertThat(LogMasking.mask("image_urls=http://a http://b")).isEqualTo("image_urls=***");
  }

  @Test
  @DisplayName("⑥ 键名大小写不敏感，凭据类键同样遮")
  void isCaseInsensitiveAcrossCredentialKeys() {
    assertThat(LogMasking.mask("BODY=正文 channel_secret=abc TOKEN=t1 password=pw"))
        .isEqualTo("BODY=*** channel_secret=*** TOKEN=*** password=***");
  }

  @Test
  @DisplayName("⑦ 边界形状：行尾只有键、括号被截断、值为空都不抛异常")
  void toleratesTruncatedAndEmptyValues() {
    assertThat(LogMasking.mask("body=")).isEqualTo("body=***");
    assertThat(LogMasking.mask("body")).isEqualTo("body");
    // 截断的 JSON（日志本身被限长切断）也要宁多勿漏
    assertThat(LogMasking.mask("body={\"a\":\"b")).isEqualTo("body=***");
    assertThat(LogMasking.mask("摘要 body={\"a\":1}\n第二条 body={\"b\":2}"))
        .isEqualTo("摘要 body=***\n第二条 body=***");
  }

  @Test
  @DisplayName("⑧ 搜索关键词与堆栈里的 JSON 片段同样遮（q 是 /search 的参数名）")
  void masksQueryAndStackframeBodies() {
    assertThat(LogMasking.mask("搜索 q=去年那笔转账 limit=20")).isEqualTo("搜索 q=*** limit=20");
    assertThat(LogMasking.mask(
        "com.fasterxml.jackson.core.JsonParseException: Unexpected character: {\"body\":\"用户正文\"}"))
        .doesNotContain("用户正文");
  }
}
