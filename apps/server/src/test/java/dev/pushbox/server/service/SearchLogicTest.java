package dev.pushbox.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.web.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 搜索的纯函数部分：这些判断（什么时候走 LIKE、通配符怎么逃、片段怎么开窗）决定了搜索结果
 * 可不可信，用不着连库就能定死，所以单独测；带 SQL 的行为在 SearchServiceTest 里验。
 */
class SearchLogicTest {

  private final SearchService service = new SearchService(null, new PbProperties());

  /** ApiException 的 getMessage() 是错误码，hint 才是给人看的那句话。 */
  private static void assertInvalidParam(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String hint) {
    assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status()).isEqualTo(400);
      assertThat(e.toError().error()).isEqualTo("invalid_param");
      assertThat(e.toError().hint()).isEqualTo(hint);
    });
  }

  @Test
  @DisplayName("① 查询词校验：空/超短/超长都是 400，而不是回一张空结果页")
  void validatesQuery() {
    assertThat(service.normalizeQuery("  CPU 告警 ")).isEqualTo("CPU 告警");
    assertInvalidParam(() -> service.normalizeQuery("   "), "q 不能为空");
    assertInvalidParam(() -> service.normalizeQuery(null), "q 不能为空");

    PbProperties props = new PbProperties();
    props.getSearch().setMinQueryLen(3);
    SearchService threeChar = new SearchService(null, props);
    assertInvalidParam(() -> threeChar.normalizeQuery("ab"), "q 至少 3 个字符");

    assertInvalidParam(
        () -> service.normalizeQuery("x".repeat(SearchService.MAX_QUERY_CHARS + 1)),
        "q 最长 100 字符");
  }

  @Test
  @DisplayName("② 短于 ngram_token_size 的词必须走 LIKE，否则 MATCH 恒零命中")
  void routesShortQueryToLike() {
    assertThat(SearchService.isNgramSearchable("报警")).isTrue();
    assertThat(SearchService.isNgramSearchable("fire")).isTrue();
    assertThat(SearchService.isNgramSearchable("警")).isFalse();
    assertThat(SearchService.isNgramSearchable("a")).isFalse();
    // 代理对按码点算：一个 emoji 就是一个词长，不能被当成两个 char
    assertThat(SearchService.isNgramSearchable("😀")).isFalse();
    assertThat(SearchService.isNgramSearchable("😀😁")).isTrue();
  }

  @Test
  @DisplayName("③ LIKE 通配符转义：用户输入的 % 与 _ 是字面量，不是通配")
  void escapesLikeWildcards() {
    assertThat(SearchService.likePattern("cpu")).isEqualTo("%cpu%");
    assertThat(SearchService.likePattern("100%")).isEqualTo("%100\\%%");
    assertThat(SearchService.likePattern("a_b")).isEqualTo("%a\\_b%");
    assertThat(SearchService.likePattern("C:\\temp")).isEqualTo("%C:\\\\temp%");
    // 转义反斜杠必须先发生，否则 "%\_%" 会变成 "%\\\\_%"
    assertThat(SearchService.likePattern("\\%")).isEqualTo("%\\\\\\%%");
  }

  @Test
  @DisplayName("④ limit 夹在 [1, max-limit]，客户端不能自己决定扫多少行")
  void clampsLimit() {
    assertThat(service.clampLimit(50)).isEqualTo(50);
    assertThat(service.clampLimit(0)).isEqualTo(1);
    assertThat(service.clampLimit(-7)).isEqualTo(1);
    assertThat(service.clampLimit(9999)).isEqualTo(200);

    PbProperties props = new PbProperties();
    props.getSearch().setMaxLimit(20);
    assertThat(new SearchService(null, props).clampLimit(9999)).isEqualTo(20);
  }

  @Test
  @DisplayName("⑤ 命中片段：优先标题，其次正文，围绕命中点开窗口并加省略号")
  void buildsSnippetAroundHit() {
    String body = "服务 order-svc 在第 3 次重试后发布成功，耗时 42s，无异常。";
    assertThat(SearchService.snippet("发布通知", body, "发布通知", "order", 120)).isEqualTo(body);

    // 标题里没有就退到正文，snippet 里要能看到查询词
    assertThat(SearchService.snippet("告警", "CPU 使用率 91%，阈值 90", null, "CPU", 120))
        .contains("91%");

    // 长正文只给窗口，且窗口必须包含命中点
    StringBuilder longText = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      longText.append("行").append(i).append(' ');
    }
    longText.append("TARGET-NEEDLE tail");
    String snippet = SearchService.snippet(null, longText.toString(), null, "TARGET-NEEDLE", 60);
    assertThat(snippet).contains("TARGET-NEEDLE").hasSizeLessThanOrEqualTo(62);
    assertThat(snippet).startsWith("…");

    // 命中点在开头时不加前导省略号，但要加尾部的
    assertThat(SearchService.snippet("head-hit tail " + "x".repeat(200), null, null, "head-hit", 40))
        .startsWith("head-hit").endsWith("…");
  }

  @Test
  @DisplayName("⑥ 片段是去 Markdown 的纯文本：通知列表里不该出现 ** 与 []()")
  void snippetIsPlainText() {
    String markdown = "### 部署 **failed**\n- 详见 [日志](https://example.com/log) 这一条记录";
    String snippet = SearchService.snippet(markdown, null, null, "部署", 120);
    assertThat(snippet).doesNotContain("###").doesNotContain("**").doesNotContain("](");
    assertThat(snippet).contains("部署").contains("failed");
  }

  @Test
  @DisplayName("⑦ 三列全空/无命中也不抛异常，返回可读的兜底片段")
  void snippetDegradates() {
    assertThat(SearchService.snippet(null, null, null, "x", 120)).isEmpty();
    assertThat(SearchService.snippet("   ", "", "", "x", 120)).isEmpty();
    // MATCH 命中在词形变化上、字面量找不到时，回正文开头
    assertThat(SearchService.snippet(null, "alpha beta gamma", null, "zzz", 120))
        .isEqualTo("alpha beta gamma");
  }

  @Test
  @DisplayName("⑧ MATCH 查询的引号换成空格：不对称的短语引号会让 AGAINST 整句报语法错")
  void matchQueryStripsQuotesOnly() {
    assertThat(SearchService.matchQuery("CPU 告警")).isEqualTo("CPU 告警");
    // 布尔模式的运算符在自然语言模式下只是分词边界，不动它们
    assertThat(SearchService.matchQuery("C++ -debug")).isEqualTo("C++ -debug");
    assertThat(SearchService.matchQuery("\"告警")).isEqualTo(" 告警");
    assertThat(SearchService.matchQuery("a\"b\"")).isEqualTo("a b ");
    assertThat(SearchService.matchQuery("\"\"")).isEqualTo("  ");
    assertThat(SearchService.matchQuery("")).isEmpty();
  }
}
