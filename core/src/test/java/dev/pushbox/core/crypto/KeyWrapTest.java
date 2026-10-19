package dev.pushbox.core.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §4.5 的密钥封装是纯 JVM 逻辑，不依赖对象存储，所以这条链路最先在这里钉死：
 * 封装/解封往返、错主钥、封装字节被改、主钥配置格式，全都必须 deterministic。
 */
class KeyWrapTest {

  private static final byte[] MASTER = KeyWrap.randomDataKey();

  @Test
  @DisplayName("① 封装后能原样解回，封装形态定长且不含原始密钥字节")
  void wrapsAndUnwraps() {
    byte[] dataKey = KeyWrap.randomDataKey();

    byte[] wrapped = KeyWrap.wrap(MASTER, dataKey);

    assertEquals(KeyWrap.WRAPPED_BYTES, wrapped.length);
    assertArrayEquals(dataKey, KeyWrap.unwrap(MASTER, wrapped));
    // 库里那一列泄露出去的必须是密文：原始钥的 32 个字节一个都不该原样出现
    assertFalse(contains(wrapped, dataKey));
  }

  @Test
  @DisplayName("② 同一把钥两次封装结果不同（IV 随机），但都解回同一把钥")
  void wrapIsRandomized() {
    byte[] dataKey = KeyWrap.randomDataKey();

    byte[] first = KeyWrap.wrap(MASTER, dataKey);
    byte[] second = KeyWrap.wrap(MASTER, dataKey);

    assertNotEquals(Hashes.toHex(first), Hashes.toHex(second));
    assertArrayEquals(dataKey, KeyWrap.unwrap(MASTER, first));
    assertArrayEquals(dataKey, KeyWrap.unwrap(MASTER, second));
  }

  @Test
  @DisplayName("③ 换主钥、封装字节被篡改都解不回：绝不能拿半把错钥去开桶里的对象")
  void rejectsWrongKeyAndTampering() {
    byte[] wrapped = KeyWrap.wrap(MASTER, KeyWrap.randomDataKey());

    assertNull(KeyWrap.unwrap(KeyWrap.randomDataKey(), wrapped));

    for (int i = 0; i < wrapped.length; i++) {
      byte[] tampered = wrapped.clone();
      tampered[i] ^= 0x01;
      assertNull(KeyWrap.unwrap(MASTER, tampered), "第 " + i + " 字节被改后仍然解开了");
    }
  }

  @Test
  @DisplayName("④ 长度不对、版本号不认识、入参缺失都返回 null")
  void rejectsMalformedWrapped() {
    assertNull(KeyWrap.unwrap(MASTER, null));
    assertNull(KeyWrap.unwrap(null, KeyWrap.wrap(MASTER, KeyWrap.randomDataKey())));
    assertNull(KeyWrap.unwrap(MASTER, new byte[KeyWrap.WRAPPED_BYTES - 1]));

    byte[] futureVersion = KeyWrap.wrap(MASTER, KeyWrap.randomDataKey()).clone();
    futureVersion[0] = 2;
    assertNull(KeyWrap.unwrap(MASTER, futureVersion));
  }

  @Test
  @DisplayName("⑤ wrap 的入参长度当场就管住：主钥/数据钥不是 32 字节属于配置或代码错误")
  void requiresFullLengthKeys() {
    assertThrows(IllegalArgumentException.class,
        () -> KeyWrap.wrap(new byte[16], KeyWrap.randomDataKey()));
    assertThrows(IllegalArgumentException.class,
        () -> KeyWrap.wrap(MASTER, new byte[31]));
  }

  @Test
  @DisplayName("⑥ 主钥配置接受标准与 URL 安全两种 base64，其他一律判为没配")
  void decodesConfiguredMasterKey() {
    byte[] key = KeyWrap.randomDataKey();

    assertArrayEquals(key, KeyWrap.masterKeyFromConfig(
        Base64.getEncoder().encodeToString(key)));
    assertArrayEquals(key, KeyWrap.masterKeyFromConfig(
        "  " + B64.enc(key) + "  "));

    assertNull(KeyWrap.masterKeyFromConfig(null));
    assertNull(KeyWrap.masterKeyFromConfig(""));
    assertNull(KeyWrap.masterKeyFromConfig("   "));
    assertNull(KeyWrap.masterKeyFromConfig("不是 base64"));
    assertNull(KeyWrap.masterKeyFromConfig(Base64.getEncoder().encodeToString(new byte[31])));
    assertNull(KeyWrap.masterKeyFromConfig(Base64.getEncoder().encodeToString(new byte[33])));
  }

  @Test
  @DisplayName("⑦ SSE-C 请求头：钥是 base64(原始钥)，MD5 是 base64(md5(原始钥))，算法名固定 AES256")
  void buildsSseCustomerHeaders() {
    byte[] dataKey = KeyWrap.randomDataKey();

    assertEquals("AES256", KeyWrap.SSE_ALGORITHM);
    assertArrayEquals(dataKey,
        Base64.getDecoder().decode(KeyWrap.sseKeyHeader(dataKey)));
    assertArrayEquals(md5(dataKey),
        Base64.getDecoder().decode(KeyWrap.sseKeyMd5Header(dataKey)));
  }

  private static byte[] md5(byte[] data) {
    try {
      return MessageDigest.getInstance("MD5").digest(data);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean contains(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return true;
    }
    return false;
  }

  @Test
  @DisplayName("⑧ 数据钥本身就是 32 字节随机：随机性够，不会撞钥")
  void dataKeysAreDistinct() {
    assertNotNull(KeyWrap.randomDataKey());
    assertEquals(32, KeyWrap.randomDataKey().length);
    assertNotEquals(Hashes.toHex(KeyWrap.randomDataKey()),
        Hashes.toHex(KeyWrap.randomDataKey()));
  }
}
