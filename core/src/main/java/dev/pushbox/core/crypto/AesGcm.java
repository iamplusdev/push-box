package dev.pushbox.core.crypto;

import java.nio.ByteBuffer;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-256-GCM：nonce 由帧序号生成（8 字节大端序号 ‖ 4 字节 0），AAD 就是那 8 字节序号（§4.3）。 */
public final class AesGcm {
  public static final int KEY_BYTES = 32;
  public static final int TAG_BITS = 128;

  private AesGcm() {}

  public static byte[] nonceOf(long frameIndex) {
    ByteBuffer bb = ByteBuffer.allocate(12);
    bb.putLong(frameIndex);
    return bb.array();
  }

  public static byte[] aadOf(long frameIndex) {
    return ByteBuffer.allocate(8).putLong(frameIndex).array();
  }

  /** 返回 密文‖16 字节 tag。 */
  public static byte[] seal(byte[] key, long frameIndex, byte[] plaintext) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_BITS, nonceOf(frameIndex)));
      cipher.updateAAD(aadOf(frameIndex));
      return cipher.doFinal(plaintext);
    } catch (Exception e) {
      throw new IllegalStateException("AES-GCM seal failed", e);
    }
  }

  /** tag 校验失败（篡改 / 序号错 / 密钥错）返回 null，让上层按"解密失败"处理而不是崩在异常上。 */
  public static byte[] open(byte[] key, long frameIndex, byte[] ciphertextWithTag) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_BITS, nonceOf(frameIndex)));
      cipher.updateAAD(aadOf(frameIndex));
      return cipher.doFinal(ciphertextWithTag);
    } catch (AEADBadTagException e) {
      return null;
    } catch (Exception e) {
      return null;
    }
  }
}
