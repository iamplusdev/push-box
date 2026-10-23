package dev.pushbox.server.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * §5.2：/healthz 与 /actuator/** 的准入。判定只看 {@code getRemoteAddr()}，
 * 不看 X-Forwarded-For——那个头是客户端自己写的，拿它做准入等于没有准入。
 *
 * <p>默认只放行环回。曾经这里把 {@code isSiteLocalAddress()} 当成"内网"，代价是容器一上线，
 * 同一个 docker 桥接网段（172.18.x.x）里的任何进程都能匿名拉 /actuator/prometheus——
 * 而 /actuator 这一侧没有任何鉴权。要放开别的源段必须显式写进 {@code pb.security.internal-cidrs}，
 * 让"信任谁"这件事留在配置里，而不是留在一段地址分类的启发式里。
 */
public class InternalAccessFilter implements Filter {

  private final List<Cidr> internalCidrs;

  public InternalAccessFilter(List<Cidr> internalCidrs) {
    this.internalCidrs = List.copyOf(internalCidrs);
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (request instanceof HttpServletRequest http && allows(http.getRemoteAddr())) {
      chain.doFilter(request, response);
      return;
    }
    // 对外一律 404 而不是 403：扫描器不该从响应里确认"这里确实有个运维端点"
    ((HttpServletResponse) response).sendError(HttpServletResponse.SC_NOT_FOUND);
  }

  /** 环回永远放行；其余地址必须落在显式配置的网段里。 */
  public boolean allows(String address) {
    if (address == null || address.isBlank()) {
      return false;
    }
    InetAddress inet;
    try {
      // getRemoteAddr 给的是已解析的字面量，这里不会触发 DNS 反查
      inet = InetAddress.getByName(address);
    } catch (UnknownHostException e) {
      return false;
    }
    if (isLoopback(inet)) {
      return true;
    }
    byte[] octets = inet.getAddress();
    for (Cidr cidr : internalCidrs) {
      if (cidr.contains(octets)) {
        return true;
      }
    }
    return false;
  }

  /** 双栈上 127.0.0.1 会以 {@code ::ffff:127.0.0.1} 的形态出现，这里按 v4 环回认。 */
  private static boolean isLoopback(InetAddress inet) {
    if (inet.isLoopbackAddress()) {
      return true;
    }
    byte[] octets = inet.getAddress();
    if (octets.length != 16) {
      return false;
    }
    for (int i = 0; i < 10; i++) {
      if (octets[i] != 0) {
        return false;
      }
    }
    if ((octets[10] & 0xFF) != 0xFF || (octets[11] & 0xFF) != 0xFF) {
      return false;
    }
    return (octets[12] & 0xFF) == 127;
  }

  /** 显式放行的网段：网络地址 + 前缀长度，匹配时只比前缀那几位。 */
  public record Cidr(byte[] network, int prefixBits, String literal) {

    boolean contains(byte[] address) {
      if (address.length != network.length) {
        return false;
      }
      int fullBytes = prefixBits / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (address[i] != network[i]) {
          return false;
        }
      }
      int restBits = prefixBits % 8;
      if (restBits == 0) {
        return true;
      }
      int mask = 0xFF << (8 - restBits) & 0xFF;
      return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    @Override
    public String toString() {
      return literal;
    }
  }

  /**
   * 解析 CIDR 列表：空白项忽略（compose 传空串是常态），写错的当场抛出让进程起不来——
   * 一个拼错的白名单静默生效成"谁都不放行"或"谁都放行"都比不起更糟。
   */
  public static List<Cidr> parseCidrs(List<String> configured) {
    List<Cidr> parsed = new ArrayList<>();
    for (String item : configured == null ? List.<String>of() : configured) {
      if (item != null && !item.isBlank()) {
        parsed.add(parseCidr(item));
      }
    }
    return parsed;
  }

  static Cidr parseCidr(String text) {
    String trimmed = text.trim();
    int slash = trimmed.indexOf('/');
    String host = slash < 0 ? trimmed : trimmed.substring(0, slash);
    if (host.isEmpty() || !isAddressLiteral(host)) {
      throw new IllegalArgumentException("内网白名单必须是 IP 字面量（如 172.16.0.0/12 或 ::1），当前是 \""
          + trimmed + "\"；主机名会在启动后才反查出不一致的地址，不接受");
    }
    InetAddress inet;
    try {
      inet = InetAddress.getByName(host);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("内网白名单 \"" + trimmed + "\" 的 IP 段无法解析");
    }
    byte[] address = inet.getAddress();
    int prefixBits;
    if (slash < 0) {
      prefixBits = address.length * 8; // 不带前缀就是单个地址
    } else {
      try {
        prefixBits = Integer.parseInt(trimmed.substring(slash + 1).trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("内网白名单 \"" + trimmed + "\" 的前缀长度不是数字");
      }
      if (prefixBits < 0 || prefixBits > address.length * 8) {
        throw new IllegalArgumentException("内网白名单 \"" + trimmed + "\" 的前缀长度超出 0~"
            + address.length * 8);
      }
    }
    byte[] network = mask(address, prefixBits);
    if (!java.util.Arrays.equals(network, address)) {
      // 10.0.0.5/24 这种写法要么是笔误要么是没想清楚，就地掩码等于悄悄把放行范围放大 256 倍
      throw new IllegalArgumentException("内网白名单 \"" + trimmed + "\" 的主机位不为 0，应写成网段地址 "
          + literal(network, prefixBits) + "/" + prefixBits);
    }
    return new Cidr(network, prefixBits, trimmed);
  }

  /**
   * 只认字面量：v4 是数字加点，v6 是十六进制、冒号（可选尾部 v4 段）。
   * 主机名放进来就会在启动那一刻做一次 DNS 反查，而反查结果与运维当时看到的并不是一回事。
   */
  private static boolean isAddressLiteral(String host) {
    if (host.indexOf(':') >= 0) {
      return host.chars().allMatch(c -> Character.isDigit(c) || (c >= 'a' && c <= 'f')
          || (c >= 'A' && c <= 'F') || c == ':' || c == '.');
    }
    return host.chars().allMatch(c -> Character.isDigit(c) || c == '.');
  }

  /** 前缀之外清零：{@code 10.0.0.5/24} 的网段地址是 {@code 10.0.0.0}。 */
  private static byte[] mask(byte[] address, int prefixBits) {
    byte[] masked = address.clone();
    int fullBytes = prefixBits / 8;
    int restBits = prefixBits % 8;
    int zeroFrom = fullBytes;
    if (restBits > 0) {
      if (fullBytes >= masked.length) {
        return masked;
      }
      masked[fullBytes] = (byte) (masked[fullBytes] & (0xFF << (8 - restBits) & 0xFF));
      zeroFrom = fullBytes + 1;
    }
    for (int i = zeroFrom; i < masked.length; i++) {
      masked[i] = 0;
    }
    return masked;
  }

  private static String literal(byte[] network, int prefixBits) {
    try {
      return InetAddress.getByAddress(network).getHostAddress();
    } catch (UnknownHostException e) {
      return "<" + prefixBits + " 位前缀>";
    }
  }
}
