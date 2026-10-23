package dev.pushbox.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * §5.2 的限流维度取自 Requests.clientIp，X-Forwarded-For 与回落 remoteAddr 的优先级写在这里断言。
 * 纯 JVM：不依赖库和对象存储，没配联调环境变量时也要跑。
 */
class RequestsTest {

  @Test
  @DisplayName("XFF 有多跳时只取第一段（最靠近客户端的那个）")
  void firstForwardedHopWins() {
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
