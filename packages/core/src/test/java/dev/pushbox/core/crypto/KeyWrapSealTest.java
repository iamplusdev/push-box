package dev.pushbox.core.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §4.5 KeyWrap 数据加密（seal/open）单元测试。
 */
@DisplayName("KeyWrap 数据加密")
class KeyWrapSealTest {

  @Test
  @DisplayName("① seal/open 往返一致性")
  void testSealOpenRoundTrip() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    byte[] data = "Hello, PushBox!".getBytes();

    byte[] sealed = KeyWrap.seal(key, data);
    assertNotNull(sealed);
    assertTrue(sealed.length > data.length); // 密文应包含 IV + TAG

    byte[] opened = KeyWrap.open(key, sealed);
    assertArrayEquals(data, opened);
  }

  @Test
  @DisplayName("② 每次 seal 产生不同密文（IV 随机）")
  void testDifferentIVEachTime() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    byte[] data = "Same plaintext".getBytes();

    byte[] sealed1 = KeyWrap.seal(key, data);
    byte[] sealed2 = KeyWrap.seal(key, data);

    assertNotEquals(Arrays.hashCode(sealed1), Arrays.hashCode(sealed2));

    // 但都能正确解密
    assertArrayEquals(data, KeyWrap.open(key, sealed1));
    assertArrayEquals(data, KeyWrap.open(key, sealed2));
  }

  @Test
  @DisplayName("③ 错误密钥解密失败")
  void testWrongKeyDecryptionFails() {
    byte[] correctKey = new byte[32];
    new SecureRandom().nextBytes(correctKey);
    byte[] wrongKey = new byte[32];
    new SecureRandom().nextBytes(wrongKey);

    byte[] data = "Secret data".getBytes();
    byte[] sealed = KeyWrap.seal(correctKey, data);

    assertThrows(IllegalStateException.class, () -> {
      KeyWrap.open(wrongKey, sealed);
    });
  }

  @Test
  @DisplayName("④ 篡改数据检测")
  void testDataTamperingDetected() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    byte[] data = "Tamper-proof data".getBytes();

    byte[] sealed = KeyWrap.seal(key, data);

    // 篡改最后一个字节
    sealed[sealed.length - 1] ^= 0xFF;

    assertThrows(IllegalStateException.class, () -> {
      KeyWrap.open(key, sealed);
    });
  }

  @Test
  @DisplayName("⑤ null 数据被拒绝")
  void testNullDataRejected() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);

    assertThrows(IllegalArgumentException.class, () -> {
      KeyWrap.seal(key, null);
    });
  }

  @Test
  @DisplayName("⑥ 错误长度的密钥被拒绝")
  void testWrongKeyLengthRejected() {
    byte[] shortKey = new byte[16];
    byte[] data = "data".getBytes();

    assertThrows(IllegalArgumentException.class, () -> {
      KeyWrap.seal(shortKey, data);
    });

    assertThrows(IllegalArgumentException.class, () -> {
      KeyWrap.open(shortKey, new byte[64]);
    });
  }

  @Test
  @DisplayName("⑦ null 密封数据被拒绝")
  void testNullSealedDataRejected() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);

    assertThrows(IllegalArgumentException.class, () -> {
      KeyWrap.open(key, null);
    });
  }

  @Test
  @DisplayName("⑧ 过短的密封数据被拒绝")
  void testTooShortSealedDataRejected() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);

    // seal 格式：iv(12) + ciphertext + tag(16)，至少需要 28 字节
    byte[] tooShort = new byte[10];
    new SecureRandom().nextBytes(tooShort);

    assertThrows(IllegalArgumentException.class, () -> {
      KeyWrap.open(key, tooShort);
    });
  }

  @Test
  @DisplayName("⑨ 空数据加密")
  void testEmptyDataEncryption() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    byte[] emptyData = new byte[0];

    byte[] sealed = KeyWrap.seal(key, emptyData);
    assertNotNull(sealed);

    byte[] opened = KeyWrap.open(key, sealed);
    assertArrayEquals(emptyData, opened);
    assertEquals(0, opened.length);
  }

  @Test
  @DisplayName("⑩ 大数据加密（10MB）")
  void testLargeDataEncryption() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    byte[] largeData = new byte[10 * 1024 * 1024]; // 10MB
    new SecureRandom().nextBytes(largeData);

    byte[] sealed = KeyWrap.seal(key, largeData);
    assertNotNull(sealed);
    assertTrue(sealed.length > largeData.length); // 密文略大（IV + TAG）

    byte[] opened = KeyWrap.open(key, sealed);
    assertArrayEquals(largeData, opened);
  }

  @Test
  @DisplayName("⑪ 二进制数据加密")
  void testBinaryDataEncryption() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);

    // 包含所有字节值的数据
    byte[] binaryData = new byte[256];
    for (int i = 0; i < 256; i++) {
      binaryData[i] = (byte) i;
    }

    byte[] sealed = KeyWrap.seal(key, binaryData);
    byte[] opened = KeyWrap.open(key, sealed);
    assertArrayEquals(binaryData, opened);
  }

  @Test
  @DisplayName("⑫ 密封格式验证：iv(12) + ciphertext + tag(16)")
  void testSealedFormat() {
    byte[] key = new byte[32];
    new SecureRandom().nextBytes(key);
    byte[] data = "Test format".getBytes();

    byte[] sealed = KeyWrap.seal(key, data);

    // 总长度 = 原始长度 + IV(12) + TAG(16)
    int expectedLength = data.length + 12 + 16;
    assertEquals(expectedLength, sealed.length);
  }
}
