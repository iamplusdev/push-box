package dev.pushbox.server.media;

import dev.pushbox.core.crypto.Hashes;
import java.util.Base64;
import java.util.Locale;

/**
 * §5.1 的 {@code sender_avatar} 来源。只认两种写法：http(s) URL 与 {@code data:image/*;base64,…}，
 * 其余（相对路径、javascript:、乱填的字符串）一律当成"没给头像"——头像是装饰字段，
 * 为一个坏头像把整条消息 400 掉不划算，但让它带着任意字符串进库更不划算。
 *
 * <p>内联 base64 绝不落库：{@code senders.avatar_url} 只有 1024 字符，一张正常头像就装不下，
 * 而且把几百万字符的 base64 塞进每次 JOIN 出来的列里等于把正文表当对象存储用。
 * 库里只留它的 sha256 指纹，字节走抓取器同一条转存路径，
 * 于是"来源变没变、要不要重抓"只看这一列就够了。
 */
public record AvatarSource(String source, String url, byte[] inlineBytes, String declaredMime) {

  /** 头像只用在 40dp 的圆里，超过 512KB 的内联图没有转存价值，也没有下载价值。 */
  static final int MAX_AVATAR_BYTES = 512 * 1024;
  /** base64 每 3 字节膨胀成 4 字符，先按字符数拦掉，省得为一张 10MB 的图分配解码缓冲。 */
  static final int MAX_INLINE_CHARS = MAX_AVATAR_BYTES / 3 * 4 + 8;
  /** 与 senders.avatar_url 的列宽一致：超长的 URL 会被 MySQL 截断，等于存了个取不到的地址。 */
  static final int MAX_URL_CHARS = 1024;

  /** @return 解析结果；{@code null} 表示"这次没给头像"。 */
  public static AvatarSource parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String value = raw.trim();
    String lower = value.toLowerCase(Locale.ROOT);
    if (lower.startsWith("data:")) {
      return inline(value);
    }
    if (!(lower.startsWith("http://") || lower.startsWith("https://")) || value.length() > MAX_URL_CHARS) {
      return null;
    }
    return new AvatarSource(value, value, null, null);
  }

  /** 内联图的 source 是指纹而不是 base64，所以它永远不需要出站抓取。 */
  public boolean needsFetch() {
    return url != null;
  }

  private static AvatarSource inline(String value) {
    int comma = value.indexOf(',');
    if (comma <= 0 || value.length() - comma - 1 > MAX_INLINE_CHARS) {
      return null;
    }
    String header = value.substring(0, comma).toLowerCase(Locale.ROOT);
    if (!header.startsWith("data:image/") || !header.endsWith(";base64")) {
      return null;
    }
    String declaredMime = header.substring("data:".length(), header.length() - ";base64".length());
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(value.substring(comma + 1));
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (bytes.length == 0 || bytes.length > MAX_AVATAR_BYTES) {
      return null;
    }
    return new AvatarSource("data:sha256:" + Hashes.sha256Hex(bytes), null, bytes, declaredMime);
  }
}
