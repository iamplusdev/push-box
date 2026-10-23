package dev.pushbox.server.media;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * §4.5 本地文件系统存储测试：明文与 SSE-C 加密两种模式，以及路径安全校验。
 */
@DisplayName("本地文件存储")
class LocalFileStorageTest {

  @TempDir
  Path tempDir;

  private LocalFileStorage storage;
  private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @BeforeEach
  void setUp() {
    // 默认使用明文模式
    storage = new LocalFileStorage(tempDir.toString(), MediaObjects.ENC_NONE, (byte[]) null);
  }

  @AfterEach
  void tearDown() throws IOException {
    // 清理临时目录
    if (Files.exists(tempDir)) {
      Files.walk(tempDir)
          .sorted((a, b) -> b.compareTo(a))
          .forEach(p -> {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
          });
    }
  }

  @Test
  @DisplayName("① 明文写入与读取往返一致")
  void testPlaintextRoundTrip() {
    String key = "media/msg_123/0";
    byte[] data = "Hello, PushBox!".getBytes();
    String contentType = "image/png";

    MediaObjects.PutResult result = storage.put(key, data, contentType, null);
    assertEquals(MediaObjects.ENC_NONE, result.encMode());
    assertNull(result.sseKeyWrapped());

    MediaObjects.Loaded loaded = storage.load(key, null);
    assertNotNull(loaded);
    assertArrayEquals(data, loaded.body());
    assertEquals(contentType, loaded.contentType());
  }

  @Test
  @DisplayName("② SSE-C 加密写入与读取往返一致")
  void testSseCRoundTrip() throws Exception {
    // 生成 32 字节主钥
    byte[] masterKey = new byte[32];
    new java.security.SecureRandom().nextBytes(masterKey);
    String base64MasterKey = Base64.getEncoder().encodeToString(masterKey);

    storage = new LocalFileStorage(tempDir.toString(), MediaObjects.ENC_SSE_C, masterKey);

    String key = "media/msg_456/0";
    byte[] data = "Secret image data".getBytes();
    String contentType = "image/jpeg";

    MediaObjects.PutResult result = storage.put(key, data, contentType, null);
    assertEquals(MediaObjects.ENC_SSE_C, result.encMode());
    assertNotNull(result.sseKeyWrapped());
    assertEquals(61, result.sseKeyWrapped().length); // KeyWrap 封装格式固定 61 字节

    // 用封装钥读取
    MediaObjects.Loaded loaded = storage.load(key, result.sseKeyWrapped());
    assertNotNull(loaded);
    assertArrayEquals(data, loaded.body());
    assertEquals(contentType, loaded.contentType());
  }

  @Test
  @DisplayName("③ 不带封装钥读取 SSE-C 对象应失败")
  void testReadSseCWithoutKey() throws Exception {
    byte[] masterKey = new byte[32];
    new java.security.SecureRandom().nextBytes(masterKey);
    storage = new LocalFileStorage(tempDir.toString(), MediaObjects.ENC_SSE_C, masterKey);

    String key = "media/msg_789/0";
    byte[] data = "Encrypted data".getBytes();
    storage.put(key, data, "image/png", null);

    // 不带封装钥读取应抛异常
    assertThrows(IllegalStateException.class, () -> {
      storage.load(key, null);
    });
  }

  @Test
  @DisplayName("④ 错误的主钥读取应失败")
  void testWrongMasterKey() throws Exception {
    byte[] correctKey = new byte[32];
    new java.security.SecureRandom().nextBytes(correctKey);
    byte[] wrongKey = new byte[32];
    new java.security.SecureRandom().nextBytes(wrongKey);

    storage = new LocalFileStorage(tempDir.toString(), MediaObjects.ENC_SSE_C, correctKey);
    String key = "media/msg_wrong/0";
    MediaObjects.PutResult result = storage.put(key, "data".getBytes(), "image/png", null);

    // 用错误主钥构造新存储实例
    storage = new LocalFileStorage(tempDir.toString(), MediaObjects.ENC_SSE_C, wrongKey);

    assertThrows(IllegalStateException.class, () -> {
      storage.load(key, result.sseKeyWrapped());
    });
  }

  @Test
  @DisplayName("⑤ 不存在的对象返回 null")
  void testNonExistentObject() {
    MediaObjects.Loaded loaded = storage.load("media/nonexistent/0", null);
    assertNull(loaded);
  }

  @Test
  @DisplayName("⑥ exists 检查正确")
  void testExists() {
    String key = "media/msg_exists/0";
    assertFalse(storage.exists(key, null));

    storage.put(key, "data".getBytes(), "image/png", null);
    assertTrue(storage.exists(key, null));
  }

  @Test
  @DisplayName("⑦ 删除对象及其元数据")
  void testDelete() {
    String key = "media/msg_delete/0";
    storage.put(key, "data".getBytes(), "image/png", null);
    assertTrue(storage.exists(key, null));

    storage.delete(key);
    assertFalse(storage.exists(key, null));

    // 验证元数据文件也被删除
    Path metaPath = tempDir.resolve("media").resolve("msg_delete").resolve("0.bin.meta");
    assertFalse(Files.exists(metaPath));
  }

  @Test
  @DisplayName("⑧ 路径遍历攻击被拒绝")
  void testPathTraversalAttack() {
    assertThrows(IllegalArgumentException.class, () -> {
      storage.put("../etc/passwd", "attack".getBytes(), "text/plain", null);
    });

    assertThrows(IllegalArgumentException.class, () -> {
      storage.put("..\\..\\windows\\system32\\config", "attack".getBytes(), "text/plain", null);
    });
  }

  @Test
  @DisplayName("⑨ 绝对路径被拒绝")
  void testAbsolutePathRejected() {
    assertThrows(IllegalArgumentException.class, () -> {
      storage.put("/etc/passwd", "attack".getBytes(), "text/plain", null);
    });
  }

  @Test
  @DisplayName("⑩ 空对象键被拒绝")
  void testEmptyKeyRejected() {
    assertThrows(IllegalArgumentException.class, () -> {
      storage.put("", "data".getBytes(), "image/png", null);
    });

    assertThrows(IllegalArgumentException.class, () -> {
      storage.put(null, "data".getBytes(), "image/png", null);
    });
  }

  @Test
  @DisplayName("⑪ 缩略图键正确处理")
  void testThumbnailKey() {
    String fullKey = "media/msg_thumb/0";
    String thumbKey = "media/msg_thumb/0.thumb.jpg";

    storage.put(fullKey, "full image".getBytes(), "image/png", null);
    storage.put(thumbKey, "thumbnail".getBytes(), "image/jpeg", null);

    MediaObjects.Loaded full = storage.load(fullKey, null);
    MediaObjects.Loaded thumb = storage.load(thumbKey, null);

    assertNotNull(full);
    assertNotNull(thumb);
    assertArrayEquals("full image".getBytes(), full.body());
    assertArrayEquals("thumbnail".getBytes(), thumb.body());
  }

  @Test
  @DisplayName("⑫ 头像键正确处理")
  void testAvatarKey() {
    String avatarKey = "media/avatar/channel_123/abc123def456";
    byte[] avatarData = "avatar image bytes".getBytes();

    storage.put(avatarKey, avatarData, "image/png", null);
    MediaObjects.Loaded loaded = storage.load(avatarKey, null);

    assertNotNull(loaded);
    assertArrayEquals(avatarData, loaded.body());
  }

  @Test
  @DisplayName("⑬ newDataKey 和 reuseKey 行为正确")
  void testDataKeyManagement() {
    // 明文模式下 newDataKey 返回 null
    assertNull(storage.newDataKey());
    assertNull(storage.reuseKey(null));

    // SSE-C 模式下 newDataKey 返回随机密钥
    byte[] masterKey = new byte[32];
    new java.security.SecureRandom().nextBytes(masterKey);
    LocalFileStorage sseStorage = new LocalFileStorage(tempDir.toString(), MediaObjects.ENC_SSE_C, masterKey);

    byte[] newKey = sseStorage.newDataKey();
    assertNotNull(newKey);
    assertEquals(32, newKey.length);

    // reuseKey 能正确解封已有封装钥
    byte[] reused = sseStorage.reuseKey(dev.pushbox.core.crypto.KeyWrap.wrap(masterKey, newKey));
    assertNotNull(reused);
    assertArrayEquals(newKey, reused);
  }

  @Test
  @DisplayName("⑭ 覆盖写入同一对象键")
  void testOverwriteSameKey() {
    String key = "media/msg_overwrite/0";

    storage.put(key, "version 1".getBytes(), "image/png", null);
    MediaObjects.Loaded v1 = storage.load(key, null);
    assertArrayEquals("version 1".getBytes(), v1.body());

    // 覆盖写入
    storage.put(key, "version 2".getBytes(), "image/jpeg", null);
    MediaObjects.Loaded v2 = storage.load(key, null);
    assertArrayEquals("version 2".getBytes(), v2.body());
    assertEquals("image/jpeg", v2.contentType());
  }

  @Test
  @DisplayName("⑮ 大文件写入（1MB）")
  void testLargeFile() {
    byte[] largeData = new byte[1024 * 1024]; // 1MB
    new java.security.SecureRandom().nextBytes(largeData);

    String key = "media/msg_large/0";
    storage.put(key, largeData, "image/png", null);

    MediaObjects.Loaded loaded = storage.load(key, null);
    assertNotNull(loaded);
    assertArrayEquals(largeData, loaded.body());
  }
}
