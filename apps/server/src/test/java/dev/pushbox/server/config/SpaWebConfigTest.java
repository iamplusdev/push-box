package dev.pushbox.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * SPA 回退解析器的用例：命不中静态文件时，什么时候回退 index.html、什么时候必须保持 404。
 *
 * <p>不起 MVC 上下文，直接调 {@code getResource}——要钉的只是这一条分支判断，而它一旦错向"回退"，
 * 客户端拿到的是 HTTP 200 加一段 HTML，比 404 难查得多；错向"404"更糟，管理台刷新一下页面就没了。
 * jar 里通常没有真的 {@code /static/index.html}（SPA 在镜像构建阶段才拷进来），
 * 所以"存在的前端页"一律用 {@code spawebfixture/exists.txt} 当替身。
 */
class SpaWebConfigTest {

  private static final Resource FIXTURE_DIR = new ClassPathResource("spawebfixture/");
  private static final Resource INDEX = new ClassPathResource("spawebfixture/exists.txt");

  private static Resource resolve(SpaWebConfig.SpaResourceResolver resolver, String path)
      throws IOException {
    return resolver.getResource(path, FIXTURE_DIR);
  }

  private static SpaWebConfig.SpaResourceResolver resolver(String ingestBasePath) {
    return new SpaWebConfig.SpaResourceResolver(
        SpaWebConfig.reservedFirstSegments(props(ingestBasePath)), INDEX);
  }

  private static PbProperties props(String ingestBasePath) {
    PbProperties props = new PbProperties();
    props.getIngest().setBasePath(ingestBasePath);
    return props;
  }

  @Test
  @DisplayName("① 前端深链与缺失的静态资源回退到 index.html")
  void spaRoutesFallBackToIndex() throws IOException {
    SpaWebConfig.SpaResourceResolver r = resolver("/message");
    assertThat(resolve(r, "channels/12345678")).isSameAs(INDEX);
    assertThat(resolve(r, "assets/index-does-not-exist.js")).isSameAs(INDEX);
    // 管理台自己的路由一个都不能被后端名单吃掉，/messages 与入站 /message 只差一个 s
    assertThat(resolve(r, "messages")).isSameAs(INDEX);
    assertThat(resolve(r, "messages?q=abc")).isSameAs(INDEX);
    assertThat(resolve(r, "dashboard")).isSameAs(INDEX);
    assertThat(resolve(r, "login")).isSameAs(INDEX);
  }

  @Test
  @DisplayName("② 后端路径保持 404，不能被回退成 200 的 HTML")
  void backendPathsStayFourOhFour() throws IOException {
    SpaWebConfig.SpaResourceResolver r = resolver("/message");
    // 精确注册过的（/healthz、/api/**）本来轮不到这里；这几条是没注册的形状
    assertThat(resolve(r, "api/v1/admi/channels")).isNull();
    assertThat(resolve(r, "admin/channels")).isNull();
    assertThat(resolve(r, "actuator/healthzX")).isNull();
    assertThat(resolve(r, "ws/x")).isNull();
    assertThat(resolve(r, "message")).isNull();
    assertThat(resolve(r, "message/abc")).isNull();
  }

  @Test
  @DisplayName("③ 判据是第一段而不是开头：撞前缀的名字不能被误杀")
  void onlyWholeFirstSegmentCounts() throws IOException {
    SpaWebConfig.SpaResourceResolver r = resolver("/message");
    // 这些不是后端路径，回退给前端才是对的
    assertThat(resolve(r, "messages2")).isSameAs(INDEX);
    assertThat(resolve(r, "wso")).isSameAs(INDEX);
    assertThat(resolve(r, "apifox")).isSameAs(INDEX);
  }

  @Test
  @DisplayName("④ 入站路径可配：改了 pb.ingest.base-path，名单要跟着换，旧路径不再算后端")
  void ingestPathIsNotHardcoded() throws IOException {
    assertThat(resolve(resolver("/push"), "push")).isNull();
    assertThat(resolve(resolver("/push"), "push/abc")).isNull();
    assertThat(resolve(resolver("/push"), "message")).isSameAs(INDEX);
  }

  @Test
  @DisplayName("⑤ base-path 配成空、带斜杠或撞名单：不重复、不炸，也不误吞前端路由")
  void oddIngestValuesKeepTheBaselineList() throws IOException {
    List<String> baseline = List.of("api", "actuator", "ws", "healthz", "admin");
    assertThat(SpaWebConfig.reservedFirstSegments(props("/"))).containsExactlyElementsOf(baseline);
    assertThat(SpaWebConfig.reservedFirstSegments(props(""))).containsExactlyElementsOf(baseline);
    assertThat(SpaWebConfig.reservedFirstSegments(props("/api"))).containsExactlyElementsOf(baseline);
    // 多段入站路径（/ingest/v1）没法用"第一段名单"表达，宁可不动名单
    assertThat(SpaWebConfig.reservedFirstSegments(props("/ingest/v1/"))).containsExactlyElementsOf(baseline);
    // 带尾斜杠的常规值要能收下，否则改了路径却漏判
    assertThat(SpaWebConfig.reservedFirstSegments(props("/push/"))).contains("push");
  }

  @Test
  @DisplayName("⑥ 真存在的静态文件优先于回退")
  void realFileWins() throws IOException {
    Resource hit = resolve(resolver("/message"), "exists.txt");
    assertThat(hit).isNotNull().isNotSameAs(INDEX);
    assertThat(hit.exists()).isTrue();
    assertThat(hit.getURL().toString()).endsWith("spawebfixture/exists.txt");
  }

  @Test
  @DisplayName("⑦ 没打进 SPA 的 jar（本地只跑 API）：回退目标不存在时返回 null，而不是抛异常")
  void missingSpaIsNotAnError() throws IOException {
    SpaWebConfig.SpaResourceResolver r = new SpaWebConfig.SpaResourceResolver(
        SpaWebConfig.reservedFirstSegments(props("/message")),
        new ClassPathResource("spawebfixture/no-index.html"));
    assertThat(resolve(r, "channels/12345678")).isNull();
    assertThat(resolve(r, "api/v1/admin/channels")).isNull();
  }

  @Test
  @DisplayName("⑧ CSP 只挂在文档响应上：管理台页面有，JS/CSS/JSON 不背这个头")
  void cspOnlyOnHtmlDocuments() throws Exception {
    jakarta.servlet.Filter filter = SpaWebConfig.cspFilter();
    jakarta.servlet.FilterChain noop = (req, res) -> { };

    org.springframework.mock.web.MockHttpServletResponse page =
        new org.springframework.mock.web.MockHttpServletResponse();
    page.setContentType("text/html;charset=UTF-8");
    filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest(), page, noop);
    assertThat(page.getHeader("Content-Security-Policy"))
        .contains("default-src 'self'").contains("script-src 'self'")
        .contains("frame-ancestors 'none'");

    org.springframework.mock.web.MockHttpServletResponse asset =
        new org.springframework.mock.web.MockHttpServletResponse();
    asset.setContentType("application/javascript");
    filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest(), asset, noop);
    assertThat(asset.getHeader("Content-Security-Policy")).isNull();

    org.springframework.mock.web.MockHttpServletResponse api =
        new org.springframework.mock.web.MockHttpServletResponse();
    api.setContentType("application/json");
    filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest(), api, noop);
    assertThat(api.getHeader("Content-Security-Policy")).isNull();
  }
}
