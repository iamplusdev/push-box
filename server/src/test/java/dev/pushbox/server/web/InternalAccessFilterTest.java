package dev.pushbox.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import jakarta.servlet.ServletException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * §5.2：/healthz 与 /actuator/** 的准入只看真实源地址，判错方向的代价是运维端点公网可达。
 *
 * <p>这一组用例钉住的方向性变化：RFC1918/链路本地/CGNAT 不再因为"看起来像内网"就放行。
 * 容器一上线，同桥接网段（172.18.x.x）里的任何进程都能匿名拉指标，而 /actuator 这一侧没有鉴权。
 * 要放行就得把 CIDR 写进配置，让信任决定留在配置里。
 */
class InternalAccessFilterTest {

  private static final InternalAccessFilter LOOPBACK_ONLY = new InternalAccessFilter(List.of());

  @Test
  @DisplayName("① 默认只放行环回：127.0.0.1、::1、localhost 与 v4 映射的环回")
  void loopbackIsAllowedByDefault() {
    assertThat(LOOPBACK_ONLY.allows("127.0.0.1")).isTrue();
    assertThat(LOOPBACK_ONLY.allows("127.2.3.4")).isTrue(); // 整段 127/8 都是环回
    assertThat(LOOPBACK_ONLY.allows("::1")).isTrue();
    assertThat(LOOPBACK_ONLY.allows("localhost")).isTrue();
    // 双栈监听时 getRemoteAddr 会以 ::ffff:127.0.0.1 的形态出现
    assertThat(LOOPBACK_ONLY.allows("::ffff:127.0.0.1")).isTrue();
  }

  @Test
  @DisplayName("② 「像是内网」不再等于放行：桥接段、RFC1918、链路本地、CGNAT、ULA 全拒")
  void privateRangesAreNoLongerTrustedByDefault() {
    // 这一条就是本次修的核心：172.18.x.x 是 docker 桥接，不是可信源
    assertThat(LOOPBACK_ONLY.allows("172.18.0.1")).isFalse();
    assertThat(LOOPBACK_ONLY.allows("10.0.0.5")).isFalse();
    assertThat(LOOPBACK_ONLY.allows("192.168.1.55")).isFalse();
    assertThat(LOOPBACK_ONLY.allows("169.254.1.1")).isFalse();
    assertThat(LOOPBACK_ONLY.allows("100.100.100.100")).isFalse();
    assertThat(LOOPBACK_ONLY.allows("fd00::1")).isFalse();
    assertThat(LOOPBACK_ONLY.allows("8.8.8.8")).isFalse();
    assertThat(LOOPBACK_ONLY.allows("203.0.113.9")).isFalse();
    assertThat(LOOPBACK_ONLY.allows(null)).isFalse();
    assertThat(LOOPBACK_ONLY.allows("")).isFalse();
    assertThat(LOOPBACK_ONLY.allows("   ")).isFalse();
  }

  @Test
  @DisplayName("③ 显式列出的网段才额外放行，且只覆盖前缀那几位")
  void configuredCidrsAreTrusted() {
    InternalAccessFilter filter = filterFor("10.0.0.0/24", "172.16.0.0/12", "fd00::/8");

    assertThat(filter.allows("10.0.0.5")).isTrue();
    assertThat(filter.allows("10.0.1.5")).isFalse(); // 出了 /24 就不在名单里
    assertThat(filter.allows("172.18.0.1")).isTrue(); // compose 桥接段
    assertThat(filter.allows("172.32.0.1")).isFalse(); // /12 只到 172.31
    assertThat(filter.allows("fd00::1")).isTrue();
    assertThat(filter.allows("fe00::1")).isFalse();
    // 环回始终放行，跟配了哪些段无关
    assertThat(filter.allows("127.0.0.1")).isTrue();
  }

  @Test
  @DisplayName("④ 不带前缀就是单个地址；空白项忽略（compose 传空串是常态）")
  void singleAddressAndBlankItems() {
    InternalAccessFilter filter = filterFor("  ", "203.0.113.9", "");

    assertThat(filter.allows("203.0.113.9")).isTrue();
    assertThat(filter.allows("203.0.113.10")).isFalse();
    assertThat(InternalAccessFilter.parseCidrs(java.util.Arrays.asList("", "  ", null))).isEmpty();
    assertThat(InternalAccessFilter.parseCidrs(null)).isEmpty();
  }

  @Test
  @DisplayName("⑤ CIDR 写错当场报错并说清该怎么写：宁可不启动，也不静默放行/静默封死")
  void malformedCidrHasClearError() {
    assertThatThrownBy(() -> InternalAccessFilter.parseCidr("10.0.0.0/33"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("前缀长度")
        .hasMessageContaining("0~32");
    assertThatThrownBy(() -> InternalAccessFilter.parseCidr("10.0.0.0/abc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("前缀长度不是数字");
    assertThatThrownBy(() -> InternalAccessFilter.parseCidr("10.0.0.0/"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("前缀长度不是数字");
    assertThatThrownBy(() -> InternalAccessFilter.parseCidr("不是IP/24"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("IP 字面量");
    assertThatThrownBy(() -> InternalAccessFilter.parseCidr("db.internal/24"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("主机名");
    assertThatThrownBy(() -> InternalAccessFilter.parseCidr(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("IP 字面量");
    // 10.0.0.5/24 是笔误：掩码成网段等于把放行范围悄悄放大 256 倍
    assertThatThrownBy(() -> InternalAccessFilter.parseCidr("10.0.0.5/24"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("主机位不为 0")
        .hasMessageContaining("10.0.0.0/24");
    // IPv6 同理：前缀之后的位非 0 就是没想清楚自己要放行哪一段
    assertThatThrownBy(() -> InternalAccessFilter.parseCidr("fd00::1/8"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("主机位不为 0");
  }

  @Test
  @DisplayName("⑥ 环回请求走到下游，桥接段请求拿到 404 且下游一次都不被调用")
  void filterLetsLoopbackThroughAndHidesRest() throws IOException, ServletException {
    MockFilterChain internalChain = new MockFilterChain();
    LOOPBACK_ONLY.doFilter(request("127.0.0.1"), new MockHttpServletResponse(), internalChain);
    assertThat(internalChain.getRequest()).isNotNull();

    MockFilterChain bridgeChain = new MockFilterChain();
    MockHttpServletResponse response = new MockHttpServletResponse();
    LOOPBACK_ONLY.doFilter(request("172.18.0.1"), response, bridgeChain);
    assertThat(bridgeChain.getRequest()).isNull();
    // 403 会告诉扫描器"这里有运维端点"，所以对外是不存在
    assertThat(response.getStatus()).isEqualTo(404);

    // 同一个地址在显式放行之后才走得下去
    MockFilterChain grantedChain = new MockFilterChain();
    filterFor("172.18.0.0/16").doFilter(request("172.18.0.1"), new MockHttpServletResponse(),
        grantedChain);
    assertThat(grantedChain.getRequest()).isNotNull();
  }

  private static InternalAccessFilter filterFor(String... cidrs) {
    return new InternalAccessFilter(InternalAccessFilter.parseCidrs(List.of(cidrs)));
  }

  private static MockHttpServletRequest request(String remoteAddr) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/healthz");
    request.setRemoteAddr(remoteAddr);
    return request;
  }
}
