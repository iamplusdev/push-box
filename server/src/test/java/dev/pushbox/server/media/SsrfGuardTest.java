package dev.pushbox.server.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pushbox.server.config.PbProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** §13 的 SSRF 负例：抓取器的地址闸必须一段私网都不放过，白名单只作为显式逃生阀。 */
class SsrfGuardTest {

  @Test
  @DisplayName("① 私网/回环/链路本地/CGNAT/组播/IPv6 本地段全部拦下")
  void blocksPrivateAndReservedRanges() {
    List<String> forbidden = List.of(
        "http://127.0.0.1/a.png",
        "http://127.2.3.4/a.png",
        "http://0.0.0.0/a.png",
        "http://10.1.2.3/a.png",
        "http://172.16.0.9/a.png",
        "http://192.168.4.1/a.png",
        "http://169.254.169.254/latest/meta-data/",
        "http://100.100.100.100:7101/bucket/key",
        "http://224.0.0.5/a.png",
        "http://255.255.255.255/a.png",
        "http://[::1]/a.png",
        "http://[fd00::1234]/a.png",
        "http://[fe80::1]/a.png",
        "http://[::ffff:127.0.0.1]/a.png");
    SsrfGuard guard = guard(List.of(), false);

    for (String url : forbidden) {
      assertThatThrownBy(() -> guard.open(url))
          .as(url)
          .isInstanceOfSatisfying(MediaFetchException.class, e -> assertThat(e.code)
              .isEqualTo(MediaFetchException.SSRF_BLOCKED));
    }
  }

  @Test
  @DisplayName("② 非 http(s)、缺主机名、带 userinfo 的地址在解析前就被拒")
  void rejectsMalformedOrWrongScheme() {
    SsrfGuard guard = guard(List.of(), false);

    assertThatThrownBy(() -> guard.open("file:///etc/passwd")).isInstanceOf(MediaFetchException.class);
    assertThatThrownBy(() -> guard.open("gopher://10.0.0.1:1111/")).isInstanceOf(MediaFetchException.class);
    assertThatThrownBy(() -> guard.open("http://")).isInstanceOf(MediaFetchException.class);
    assertThatThrownBy(() -> guard.open("http://good.example@127.0.0.1/"))
        .isInstanceOfSatisfying(MediaFetchException.class,
            e -> assertThat(e.code).isEqualTo(MediaFetchException.SSRF_BLOCKED));
  }

  @Test
  @DisplayName("③ allow-hosts 精确匹配（含端口）：端口不同即不同条目，前缀/后缀一律不算命中")
  void whitelistIsExactAndCheckedBeforeIp() throws Exception {
    SsrfGuard guard = guard(List.of("127.0.0.1:8080"), false);

    SsrfGuard.Target hit = guard.open("http://127.0.0.1:8080/a.png");
    assertThat(hit.whitelisted()).isTrue();
    assertThat(hit.port()).isEqualTo(8080);

    assertThatThrownBy(() -> guard.open("http://127.0.0.1/a.png"))
        .as("端口不同即不同条目")
        .isInstanceOfSatisfying(MediaFetchException.class,
            e -> assertThat(e.code).isEqualTo(MediaFetchException.SSRF_BLOCKED));

    SsrfGuard prefix = guard(List.of("192.168.1.1"), false);
    assertThatThrownBy(() -> prefix.open("http://192.168.1.10/a.png"))
        .as("白名单条目不能当字符串前缀命中")
        .isInstanceOf(MediaFetchException.class);
  }

  @Test
  @DisplayName("④ 白名单 host 不许把请求带到站外；重定向每一跳都重跑校验")
  void redirectsAreRevalidated() throws Exception {
    SsrfGuard guard = guard(List.of("127.0.0.1"), false);
    SsrfGuard.Target from = guard.open("http://127.0.0.1/start");

    assertThatThrownBy(() -> guard.redirect(from, "http://example.com/a.png"))
        .as("白名单 host 跳出站外")
        .isInstanceOfSatisfying(MediaFetchException.class,
            e -> assertThat(e.code).isEqualTo(MediaFetchException.SSRF_BLOCKED));
    assertThatThrownBy(() -> guard.redirect(from, ""))
        .isInstanceOf(MediaFetchException.class);

    SsrfGuard open = guard(List.of(), false);
    SsrfGuard.Target publicHop = open.open("http://93.184.216.34/start");
    assertThatThrownBy(() -> open.redirect(publicHop, "http://10.0.0.1/a.png"))
        .as("公开跳点转向内网")
        .isInstanceOfSatisfying(MediaFetchException.class,
            e -> assertThat(e.code).isEqualTo(MediaFetchException.SSRF_BLOCKED));
  }

  @Test
  @DisplayName("⑤ allow-private=true 是联调开关：私网放行，非法 scheme 依旧拒绝")
  void allowPrivateOpensLocalFetchForDebugging() throws Exception {
    SsrfGuard guard = guard(List.of(), true);

    assertThat(guard.open("http://127.0.0.1/a.png").addresses()).hasSize(1);
    assertThatThrownBy(() -> guard.open("data:image/png;base64,AAAA"))
        .isInstanceOf(MediaFetchException.class);
  }

  private static SsrfGuard guard(List<String> allowHosts, boolean allowPrivate) {
    PbProperties.Media media = new PbProperties.Media();
    media.setAllowHosts(allowHosts);
    media.setAllowPrivate(allowPrivate);
    return new SsrfGuard(media);
  }
}
