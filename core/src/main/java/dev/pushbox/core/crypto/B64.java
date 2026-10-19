package dev.pushbox.core.crypto;

import java.util.Base64;

/** Base64 无填充 URL 安全变体，协议里所有密钥/签名都用它。 */
public final class B64 {
  private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DEC = Base64.getUrlDecoder();

  private B64() {}

  public static String enc(byte[] data) {
    return ENC.encodeToString(data);
  }

  public static byte[] dec(String text) {
    return DEC.decode(text);
  }

  /** 解码失败或入参缺失都返回 null，调用方据此回 400/401，而不是让 NPE 变成 500。 */
  public static byte[] tryDec(String text) {
    if (text == null) {
      return null;
    }
    try {
      return DEC.decode(text);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
