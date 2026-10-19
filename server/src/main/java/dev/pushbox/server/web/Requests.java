package dev.pushbox.server.web;

import jakarta.servlet.http.HttpServletRequest;

public final class Requests {

  private Requests() {}

  /** 反代后真实来源在 X-Forwarded-For；Caddy 会覆盖该头，不存在客户端自填伪造的问题。 */
  public static String clientIp(HttpServletRequest http) {
    String forwarded = http.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return http.getRemoteAddr();
  }
}
