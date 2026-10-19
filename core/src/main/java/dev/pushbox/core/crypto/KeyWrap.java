package dev.pushbox.core.crypto;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * §4.5 的密钥封装：每个对象一把随机 32 字节 SSE-C 客户钥，落库的必须是它用 `pb.s3.master-key`
 * 做 AES-256-GCM 封装之后的形态——主钥只存在于服务端进程内存，桶整个被下载也读不出图。
 *
 * 封装格式定长 61 字节（{@code media_objects.sse_key_wrapped VARBINARY(128)} 存得下），
 * P3 的 Windows 服务端要按同一份格式读同一个桶：
 * {@code version(1=0x01) ‖ iv(12) ‖ ciphertext(32) ‖ tag(16)}
 */
public final class KeyWrap {

  public static final int DATA_KEY_BYTES = 32;
  public static final int WRAPPED_BYTES = 1 + 12 + 32 + 16;
  /** §4.5：SSE-C 只认 AES256，这一串要原样进 x-amz-server-side-encryption-customer-algorithm。 */
  public static final String SSE_ALGORITHM = "AES256";

  private static final byte VERSION = 1;
  private static final int IV_BYTES = 12;
  private static final SecureRandom RANDOM = new SecureRandom();

  private KeyWrap() {}

  public static byte[] randomDataKey() {
    byte[] key = new byte[DATA_KEY_BYTES];
    RANDOM.nextBytes(key);
    return key;
  }

  /** SSE-C 请求头要的是 base64 的客户钥。 */
  public static String sseKeyHeader(byte[] dataKey) {
    return Base64.getEncoder().encodeToString(dataKey);
  }

  /**
   * SSE-C 还要求 key-MD5：AWS SDK v2 的这一版不会替我们算，缺这个头 RustFS 直接拒
   * （实测见 deploy/probe/rustfs-capability.mjs）。MD5 在这里只是协议规定的校验和，不是密码学强度。
   */
  public static String sseKeyMd5Header(byte[] dataKey) {
    try {
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(dataKey));
    } catch (Exception e) {
      throw new IllegalStateException("JVM 没有 MD5", e);
    }
  }

  public static byte[] wrap(byte[] masterKey, byte[] dataKey) {
    checkLength(masterKey, DATA_KEY_BYTES, "master-key");
    checkLength(dataKey, DATA_KEY_BYTES, "data-key");
    byte[] iv = new byte[IV_BYTES];
    RANDOM.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
          new GCMParameterSpec(AesGcm.TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(dataKey);
      return ByteBuffer.allocate(WRAPPED_BYTES).put(VERSION).put(iv).put(ciphertext).array();
    } catch (Exception e) {
      throw new IllegalStateException("密钥封装失败", e);
    }
  }

  /** 主钥不对、字节被改过、版本不认识一律返回 null：调用方按"这个对象读不出来"处理。 */
  public static byte[] unwrap(byte[] masterKey, byte[] wrapped) {
    if (masterKey == null || wrapped == null || wrapped.length != WRAPPED_BYTES
        || wrapped[0] != VERSION) {
      return null;
    }
    try {
      byte[] iv = ByteBuffer.allocate(IV_BYTES).put(wrapped, 1, IV_BYTES).array();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
          new GCMParameterSpec(AesGcm.TAG_BITS, iv));
      return cipher.doFinal(wrapped, 1 + IV_BYTES, wrapped.length - 1 - IV_BYTES);
    } catch (AEADBadTagException e) {
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * {@code pb.s3.master-key} 配的是 base64 的 32 字节。标准与 URL 安全两种字母表都收：
   * 运维用 {@code openssl rand -base64 32} 还是别的工具生成，不该变成一次启动失败。
   * 解不出、长度不对、没配都返回 null，由调用方在启动阶段说人话。
   */
  public static byte[] masterKeyFromConfig(String configured) {
    if (configured == null || configured.isBlank()) {
      return null;
    }
    String value = configured.trim();
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException standardFailed) {
      try {
        decoded = Base64.getUrlDecoder().decode(value);
      } catch (IllegalArgumentException alsoFailed) {
        return null;
      }
    }
    return decoded.length == DATA_KEY_BYTES ? decoded : null;
  }

  /**
   * SSE-C 数据加密：用客户钥对原始字节做 AES-256-GCM 加密。
   * 格式：{@code iv(12) ‖ ciphertext ‖ tag(16)}，总长度 = 原始长度 + 28。
   *
   * @param customerKey 32 字节的客户钥
   * @param data 原始数据
   * @return 加密后的字节（含 IV + 密文 + TAG）
   */
  public static byte[] seal(byte[] customerKey, byte[] data) {
    checkLength(customerKey, DATA_KEY_BYTES, "customer-key");
    if (data == null) {
      throw new IllegalArgumentException("data 不能为 null");
    }
    byte[] iv = new byte[IV_BYTES];
    RANDOM.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(customerKey, "AES"),
          new GCMParameterSpec(AesGcm.TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(data);
      return ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
    } catch (Exception e) {
      throw new IllegalStateException("数据加密失败", e);
    }
  }

  /**
   * SSE-C 数据解密：还原 {@link #seal} 加密的数据。
   *
   * @param customerKey 32 字节的客户钥
   * @param sealed 加密后的字节（iv + ciphertext + tag）
   * @return 原始数据
   */
  public static byte[] open(byte[] customerKey, byte[] sealed) {
    checkLength(customerKey, DATA_KEY_BYTES, "customer-key");
    if (sealed == null || sealed.length < IV_BYTES + 16) {
      throw new IllegalArgumentException("密封数据格式错误");
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      System.arraycopy(sealed, 0, iv, 0, IV_BYTES);
      byte[] ciphertext = new byte[sealed.length - IV_BYTES];
      System.arraycopy(sealed, IV_BYTES, ciphertext, 0, ciphertext.length);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(customerKey, "AES"),
          new GCMParameterSpec(AesGcm.TAG_BITS, iv));
      return cipher.doFinal(ciphertext);
    } catch (AEADBadTagException e) {
      throw new IllegalStateException("数据解密失败：客户钥错误或数据被篡改", e);
    } catch (Exception e) {
      throw new IllegalStateException("数据解密失败", e);
    }
  }

  private static void checkLength(byte[] key, int bytes, String name) {
    if (key == null || key.length != bytes) {
      throw new IllegalArgumentException(name + " 必须是 " + bytes + " 字节");
    }
  }
}
