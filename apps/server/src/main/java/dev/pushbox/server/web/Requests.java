package dev.pushbox.server.web;

import jakarta.servlet.http.HttpServletRequest;

public final class Requests {

  private static volatile boolean trustForwardedFor = false;

  private Requests() {}

  /** 由 main() 在启动时按配置文件注入；测试里直接调。 */
  public static void trustForwardedFor(boolean v) {
    trustForwardedFor = v;
  }

  /**
   * XFF 只有两种可信来源：请求来自环回（本机跑着的 Caddy 会覆盖该头，客户端碰不到），或
   * 部署者在配置里显式开了 {@code security.trustForwardedFor}。直连部署里客户端可以自填
   * XFF，无条件取首段等于把登录锁定与限流的身份键交给攻击者挑。
   */
  public static String clientIp(HttpServletRequest http) {
    if (trustForwardedFor || isLoopback(http.getRemoteAddr())) {
      String forwarded = http.getHeader("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) {
        return forwarded.split(",")[0].trim();
      }
    }
    return http.getRemoteAddr();
  }

  private static boolean isLoopback(String addr) {
    return "127.0.0.1".equals(addr) || "::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr);
  }
}
