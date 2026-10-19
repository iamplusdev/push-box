package dev.pushbox.server.config;

import java.io.IOException;
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
 * <p>这个资源处理器挂在最低优先级的 mapping 上，{@code @RestController}（/api/**）、
 * actuator、WebSocket（/ws）都在它之前匹配；但为了不让"拼错的 /api 路径"也被回退成 200 的 HTML，
 * 解析器对这几个前缀显式返回 null，保持它们原本的 404 语义。
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(new PathResourceResolver() {
          @Override
          protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
              return requested;
            }
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator")
                || resourcePath.startsWith("ws")) {
              return null;
            }
            Resource index = new ClassPathResource("/static/index.html");
            return index.exists() ? index : null;
          }
        });
  }
}
