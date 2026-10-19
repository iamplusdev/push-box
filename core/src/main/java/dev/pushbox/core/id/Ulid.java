package dev.pushbox.core.id;

import java.security.SecureRandom;

/** ULID（26 位 Crockford base32，48 位毫秒时间 + 80 位随机），msg_id 用它，天然按时间可排序。 */
public final class Ulid {
  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  private Ulid() {}

  public static String next() {
    return next(System.currentTimeMillis());
  }

  static String next(long millis) {
    byte[] bytes = new byte[16];
    bytes[0] = (byte) ((millis >>> 40) & 0xFF);
    bytes[1] = (byte) ((millis >>> 32) & 0xFF);
    bytes[2] = (byte) ((millis >>> 24) & 0xFF);
    bytes[3] = (byte) ((millis >>> 16) & 0xFF);
    bytes[4] = (byte) ((millis >>> 8) & 0xFF);
    bytes[5] = (byte) (millis & 0xFF);
    for (int i = 6; i < 16; i++) {
      bytes[i] = (byte) RANDOM.nextInt(256);
    }
    return encode(bytes);
  }

  private static String encode(byte[] data) {
    // 128 bit → 26 个 base32 字符（末字符只用 2 bit，其余 3 bit 补 0）
    char[] out = new char[26];
    int buffer = 0;
    int bits = 0;
    int pos = 25;
    for (int i = data.length - 1; i >= 0; i--) {
      buffer = (buffer << 8) | (data[i] & 0xFF);
      bits += 8;
      while (bits >= 5) {
        out[pos--] = ALPHABET[(buffer >>> (bits - 5)) & 0x1F];
        bits -= 5;
      }
    }
    if (bits > 0) {
      out[pos] = ALPHABET[(buffer << (5 - bits)) & 0x1F];
    }
    return new String(out);
  }
}
