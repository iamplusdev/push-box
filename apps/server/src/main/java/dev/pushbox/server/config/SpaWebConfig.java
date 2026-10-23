package dev.pushbox.server.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 让 Spring Boot 直接托管 web-admin 打包出来的 SPA（jar 内的 {@code classpath:/static/}）。
 *
 * <p>前端用了 history 路由，深链（如 /channels/12345678）刷新时浏览器会真的去请求这个路径，
 * 而它在磁盘上并不存在——需要在命中不到静态文件时回退到 index.html 交给前端路由。
 *
 * <p>这个资源处理器挂在最低优先级的 mapping 上，{@code @RestController}（/api/**）、actuator、
 * WebSocket（/ws）、入站（/message）都在它之前匹配；但**已注册的精确路径**才会被它们接走，少一段
 * 前缀的 {@code /admin/channels} 与拼错一个字的 {@code /api/v1/admi/channels} 会一路落到这里。
 * 所以解析器必须自己认出"这看起来是后端路径"并返回 null，保住 404 的语义：2026-10-20 实测过，
 * 回退成 index.html 时客户端拿到的是 <b>HTTP 200 + 一段 HTML</b>，只看状态码的脚本会一路走下去、
 * 取字段全 {@code undefined}，症状长得像"服务端返回了空数据"而不是"URL 写错了"。
 * 单镜像把 API 与页面放进同一个端口之后，这一类误判只会更多，不会更少。
 *
 * <p>判据是"第一段命中名单"而不是"字符串以它开头"：管理台的搜索页路由是 {@code /messages}，
 * 而入站端点是 {@code /message}——按开头匹配会把前者判成后端路径，管理台刷新一下就 404 了。
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

  /**
   * 后端第一段名单（相对 servlet 上下文，即去掉开头的 "/"）。命中这些一律 404，不回退给前端。
   *
   * <p>{@code admin} 也在名单里：管理台 SPA 的路由是根下的 {@code /dashboard}、{@code /channels}、
   * {@code /messages}……没有 {@code /admin/*}（vite 没配 base），所以 {@code /admin/channels}
   * 既不是后端路径也不是前端路由，回退给 index.html 只会造出上面那条 200+HTML 的坑。
   */
  private static final List<String> BACKEND_FIRST_SEGMENTS =
      List.of("api", "actuator", "ws", "healthz", "admin");

  private final PbProperties props;

  public SpaWebConfig(PbProperties props) {
    this.props = props;
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(new SpaResourceResolver(reservedFirstSegments(props),
            new ClassPathResource("/static/index.html")));
  }

  /**
   * 常量名单加上可配的入站路径。入站端点由 {@code pb.ingest.base-path} 决定（默认 {@code /message}），
   * 所以不能写死进名单——谁改了入站路径，这里必须跟着改，否则改完的那个路径又变回"200 + HTML"。
   */
  static List<String> reservedFirstSegments(PbProperties props) {
    String ingest = props.getIngest().getBasePath().replaceAll("^/+", "").replaceAll("/+$", "");
    if (ingest.isEmpty() || ingest.contains("/") || BACKEND_FIRST_SEGMENTS.contains(ingest)) {
      // 空、或带斜杠（配成了 /a/b，那是多段路径，本名单管不了第一段的判据）、或已在名单里：都不加
      return BACKEND_FIRST_SEGMENTS;
    }
    List<String> withIngest = new ArrayList<>(BACKEND_FIRST_SEGMENTS);
    withIngest.add(ingest);
    return List.copyOf(withIngest);
  }

  /** 命中不到静态文件时决定"回退给前端路由"还是"保持 404"的那一步。 */
  static class SpaResourceResolver extends PathResourceResolver {

    private final List<String> reserved;
    private final Resource indexHtml;

    SpaResourceResolver(List<String> reserved, Resource indexHtml) {
      this.reserved = reserved;
      this.indexHtml = indexHtml;
    }

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      Resource requested = location.createRelative(resourcePath);
      if (requested.exists() && requested.isReadable()) {
        return requested;
      }
      if (isBackendPath(resourcePath)) {
        return null;
      }
      return indexHtml.exists() ? indexHtml : null;
    }

    /** 只看第一段：{@code api/x} 算，{@code apiisfast} 不算——后者更可能是前端路由或静态资源名。 */
    private boolean isBackendPath(String resourcePath) {
      int slash = resourcePath.indexOf('/');
      String first = slash < 0 ? resourcePath : resourcePath.substring(0, slash);
      return reserved.contains(first);
    }
  }
}
