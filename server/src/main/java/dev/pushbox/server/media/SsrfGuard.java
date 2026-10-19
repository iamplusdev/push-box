package dev.pushbox.server.media;

import dev.pushbox.server.config.PbProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * §4.5 的 SSRF 校验，抓取器的第一道闸：先解析 DNS、再校验 IP，命中回环/私网/链路本地/组播/CGNAT
 * 一律拒绝（自建对象存储常用 Tailscale 地址，正落在 100.64.0.0/10，所以这一段不留例外）。
 * 校验通过的地址必须被调用方钉成连接目标（TOCTOU），并每一跳重跑一次。
 */
public final class SsrfGuard {

  /** @param whitelisted 命中了 pb.media.allow-hosts，私有地址豁免只对它开放。 */
  public record Target(URI uri, String host, int port, List<InetAddress> addresses,
      boolean whitelisted) {}

  private final PbProperties.Media media;

  public SsrfGuard(PbProperties.Media media) {
    this.media = media;
  }

  public Target open(String rawUrl) throws MediaFetchException {
    URI uri = strictUri(rawUrl);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    int port = uri.getPort() == -1 ? defaultPort(uri.getScheme()) : uri.getPort();
    List<InetAddress> addresses;
    try {
      addresses = List.of(InetAddress.getAllByName(host));
    } catch (UnknownHostException e) {
      throw new MediaFetchException(MediaFetchException.SSRF_BLOCKED, "DNS 解析失败 host=" + host);
    }
    if (addresses.isEmpty()) {
      throw new MediaFetchException(MediaFetchException.SSRF_BLOCKED, "DNS 无应答 host=" + host);
    }
    // 白名单判断必须在 IP 校验之前，否则内网 host 解析出的私有地址会先把请求拦下（§4.5）
    boolean whitelisted = isWhitelisted(host, port);
    if (whitelisted) {
      return new Target(uri, host, port, addresses, true);
    }
    if (!media.isAllowPrivate()) {
      for (InetAddress address : addresses) {
        if (isBlocked(address)) {
          throw new MediaFetchException(MediaFetchException.SSRF_BLOCKED,
              "解析到被禁地址 host=" + host + " addr=" + address.getHostAddress());
        }
      }
    }
    return new Target(uri, host, port, addresses, false);
  }

  /** 每一跳重定向都重新走一遍完整校验；白名单 host 不允许把请求带出站。 */
  public Target redirect(Target from, String location) throws MediaFetchException {
    if (location == null || location.isBlank()) {
      throw new MediaFetchException(MediaFetchException.SSRF_BLOCKED, "重定向缺少 Location");
    }
    URI next;
    try {
      next = from.uri().resolve(location);
    } catch (IllegalArgumentException e) {
      throw new MediaFetchException(MediaFetchException.SSRF_BLOCKED, "重定向目标无法解析");
    }
    Target target = open(next.toString());
    if (from.whitelisted() && !target.whitelisted()) {
      throw new MediaFetchException(MediaFetchException.SSRF_BLOCKED,
          "白名单 host 跳转到站外 " + target.host());
    }
    return target;
  }

  private static URI strictUri(String rawUrl) throws MediaFetchException {
    URI uri;
    try {
      uri = new URI(rawUrl.trim());
    } catch (URISyntaxException | IllegalArgumentException e) {
      throw new MediaFetchException(MediaFetchException.SSRF_BLOCKED, "URL 非法");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    // data: 内联图在 §5.1 就被 400 拦掉了，这里再兜一层：抓取只认 http/https
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new MediaFetchException(MediaFetchException.SSRF_BLOCKED, "scheme 只允许 http/https");
    }
    if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
      throw new MediaFetchException(MediaFetchException.SSRF_BLOCKED, "缺少主机名或带 userinfo");
    }
    return uri;
  }

  private boolean isWhitelisted(String host, int port) {
    for (String entry : media.getAllowHosts()) {
      if (entry == null) {
        continue;
      }
      String allowed = entry.trim().toLowerCase(Locale.ROOT);
      // 精确等值匹配（含端口），禁止父段或后缀：img.internal.lan 绝不匹配 img.internal.lan.evil.com
      if (allowed.equals(host) || allowed.equals(host + ":" + port)) {
        return true;
      }
    }
    return false;
  }

  static boolean isBlocked(InetAddress address) {
    byte[] bytes = address.getAddress();
    if (bytes.length == 16) {
      if ((bytes[0] & 0xFE) == 0xFC) {
        return true; // fc00::/7 唯一本地地址
      }
      if (isIpv4Mapped(bytes)) {
        byte[] mapped = new byte[4];
        System.arraycopy(bytes, 12, mapped, 0, 4);
        return isBlockedIpv4(mapped); // ::ffff:127.0.0.1 这类写法不能绕过
      }
      return address.isLoopbackAddress() || address.isAnyLocalAddress()
          || address.isLinkLocalAddress() || address.isSiteLocalAddress()
          || address.isMulticastAddress();
    }
    return bytes.length == 4 && isBlockedIpv4(bytes);
  }

  /** §4.5 列出的段逐个显式判断，不依赖 JDK 的 siteLocal 词表（它对 100.64/10 与 0/8 都不敏感）。 */
  private static boolean isBlockedIpv4(byte[] bytes) {
    int a = bytes[0] & 0xFF;
    int b = bytes[1] & 0xFF;
    if (a == 0 || a >= 224) {
      return true; // 0.0.0.0/8、组播 224/4 与保留段（含 255.255.255.255）
    }
    if (a == 10 || a == 127) {
      return true; // 10/8、127/8
    }
    if (a == 100 && b >= 64 && b <= 127) {
      return true; // 100.64.0.0/10 CGNAT，我们的 RustFS 就在这段里
    }
    if (a == 169 && b == 254) {
      return true; // 169.254.0.0/16 链路本地
    }
    if (a == 172 && b >= 16 && b <= 31) {
      return true; // 172.16.0.0/12
    }
    if (a == 192 && b == 168) {
      return true; // 192.168.0.0/16
    }
    if (a == 198 && (b == 18 || b == 19)) {
      return true; // 198.18.0.0/15 基准测试段
    }
    return a == 192 && b == 0; // 192.0.0.0/24 IETF 协议分配
  }

  private static boolean isIpv4Mapped(byte[] bytes) {
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
  }

  private static int defaultPort(String scheme) {
    return "https".equals(scheme.toLowerCase(Locale.ROOT)) ? 443 : 80;
  }
}
