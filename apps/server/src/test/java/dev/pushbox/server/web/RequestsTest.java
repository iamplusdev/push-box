package dev.pushbox.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * §5.2 的限流维度取自 Requests.clientIp。XFF 的可信口径（环回来源默认信、其余要显式开
 * {@code security.trustForwardedFor}）写在这里断言。纯 JVM：不依赖库和对象存储。
 */
class RequestsTest {

  @AfterEach
  void resetTrust() {
    Requests.trustForwardedFor(false);
  }

  @Test
  @DisplayName("环回来源默认信 XFF：本机反代（Caddy 会覆盖该头）不用开开关")
  void loopbackTrustsForwardedByDefault() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.1");
    request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1, 198.51.100.2");

    assertThat(Requests.clientIp(request)).isEqualTo("203.0.113.9");
  }

  @Test
  @DisplayName("非环回来源默认不信 XFF：客户端自填的头不能变成限流键")
  void forgedForwardedIgnoredByDefault() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.5");
    request.addHeader("X-Forwarded-For", "203.0.113.9");

    assertThat(Requests.clientIp(request)).isEqualTo("10.0.0.5");
  }

  @Test
  @DisplayName("开关打开后非环回也取 XFF 首段（多跳取最靠近客户端的那一跳）")
  void explicitTrustTakesFirstForwardedHop() {
    Requests.trustForwardedFor(true);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.5");
    request.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1, 198.51.100.2");

    assertThat(Requests.clientIp(request)).isEqualTo("203.0.113.9");
  }

  @Test
  @DisplayName("XFF 缺失或空白都回落 socket 地址，不能让空串变成共享限流键")
  void fallsBackToSocketAddress() {
    MockHttpServletRequest absent = new MockHttpServletRequest();
    absent.setRemoteAddr("192.0.2.44");
    MockHttpServletRequest blank = new MockHttpServletRequest();
    blank.setRemoteAddr("192.0.2.44");
    blank.addHeader("X-Forwarded-For", "   ");

    assertThat(Requests.clientIp(absent)).isEqualTo("192.0.2.44");
    assertThat(Requests.clientIp(blank)).isEqualTo("192.0.2.44");
  }
}
