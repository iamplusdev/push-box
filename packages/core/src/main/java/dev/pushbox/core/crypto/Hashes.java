package dev.pushbox.core.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Hashes {
  private Hashes() {}

  public static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String sha256Hex(byte[] data) {
    return toHex(sha256(data));
  }

  public static String toHex(byte[] data) {
    StringBuilder sb = new StringBuilder(data.length * 2);
    for (byte b : data) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }
}
