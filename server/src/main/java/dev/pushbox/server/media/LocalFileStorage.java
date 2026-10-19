package dev.pushbox.server.media;

import dev.pushbox.core.crypto.KeyWrap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地文件系统存储后端：作为 S3 的替代方案，用于无对象存储服务的部署场景。
 *
 * <p>对象键映射规则：{@code media/msg_123/0} → {@code <root>/media/msg_123/0.bin}，
 * 缩略图 {@code media/msg_123/0.thumb.jpg} → {@code <root>/media/msg_123/0.thumb.jpg.bin}。
 *
 * <p>加密策略与 S3 模式完全一致：
 * <ul>
 *   <li>{@code none}：明文落盘，字节原样读写</li>
 *   <li>{@code sse-c}：用客户钥做 AES-256-GCM 加密后落盘，封装钥存数据库</li>
 * </ul>
 */
class LocalFileStorage {

  private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);
  private static final String EXT = ".bin";

  private final Path root;
  private final String encMode;
  private final byte[] masterKey;

  LocalFileStorage(String rootPath, String encMode, byte[] masterKey) {
    this.root = Paths.get(rootPath).toAbsolutePath().normalize();
    this.encMode = encMode;
    this.masterKey = masterKey;

    try {
      Files.createDirectories(this.root);
      log.info("本地文件存储已初始化 path={} encrypt={}", this.root, encMode);
    } catch (IOException e) {
      throw new IllegalStateException("无法创建本地存储目录: " + this.root, e);
    }
  }

  /**
   * 写入对象。
   *
   * @param objectKey 对象键（如 media/msg_123/0）
   * @param data 原始字节
   * @param contentType Content-Type（本地存储不强制使用，但记录到元数据文件）
   * @param dataKey SSE-C 模式下的客户钥；传 null 时若配置为 sse-c 则自动生成一把
   * @return 封装结果（enc_mode + sse_key_wrapped）
   */
  MediaObjects.PutResult put(String objectKey, byte[] data, String contentType, byte[] dataKey) {
    validateKey(objectKey);
    byte[] customerKey = resolveKey(dataKey);
    byte[] payload = encryptIfNeeded(data, customerKey);

    Path filePath = resolvePath(objectKey);
    Path metaPath = filePath.resolveSibling(filePath.getFileName() + ".meta");

    try {
      Files.createDirectories(filePath.getParent());

      // 写入数据文件并设置权限为 600（仅属主可读写）
      Files.write(filePath, payload);
      setSecurePermissions(filePath);

      // 元数据：contentType + 原始大小（解密后的）
      String meta = "content-type=" + (contentType == null ? "" : contentType)
          + "\noriginal-size=" + data.length + "\n";
      Files.write(metaPath, meta.getBytes());
      setSecurePermissions(metaPath);

      return new MediaObjects.PutResult(
          customerKey == null ? MediaObjects.ENC_NONE : MediaObjects.ENC_SSE_C,
          customerKey == null ? null : KeyWrap.wrap(masterKey, customerKey));
    } catch (IOException e) {
      throw new RuntimeException("写入本地文件失败: " + filePath, e);
    }
  }

  /**
   * 设置文件权限为 600（rw-------），防止同一主机其他用户读取媒体文件。
   * Windows 上此方法为空操作（Windows ACL 机制不同）。
   */
  private void setSecurePermissions(Path path) {
    if (!isWindows()) {
      try {
        java.util.Set<java.nio.file.attribute.PosixFilePermission> perms = 
            java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(path, perms);
      } catch (UnsupportedOperationException | IOException e) {
        // 文件系统不支持 POSIX 权限（如 NTFS），静默忽略
      }
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }

  /**
   * 读取对象。
   *
   * @param objectKey 对象键
   * @param sseKeyWrapped 数据库中的封装客户钥；null 表示明文
   * @return 加载结果（body + contentType），对象不存在返回 null
   */
  MediaObjects.Loaded load(String objectKey, byte[] sseKeyWrapped) {
    validateKey(objectKey);
    Path filePath = resolvePath(objectKey);

    if (!Files.exists(filePath)) {
      return null;
    }

    try {
      byte[] payload = Files.readAllBytes(filePath);
      byte[] customerKey = unwrap(sseKeyWrapped);

      // SSE-C 模式下必须有客户钥，否则拒绝读取
      if (usesCustomerKey() && customerKey == null) {
        throw new IllegalStateException("库里有封装钥但 pb.storage.type=local 且未提供 sse_key_wrapped："
            + "对象是 sse-c 写的，读它必须带封装钥");
      }

      byte[] data = decryptIfNeeded(payload, customerKey);

      // 读取元数据获取 Content-Type
      Path metaPath = filePath.resolveSibling(filePath.getFileName() + ".meta");
      String contentType = readContentType(metaPath);

      return new MediaObjects.Loaded(data, contentType);
    } catch (IOException e) {
      throw new RuntimeException("读取本地文件失败: " + filePath, e);
    }
  }

  /**
   * 检查对象是否存在。
   */
  boolean exists(String objectKey, byte[] sseKeyWrapped) {
    validateKey(objectKey);
    return Files.exists(resolvePath(objectKey));
  }

  /**
   * 删除对象及其元数据文件。
   */
  void delete(String objectKey) {
    if (objectKey == null || objectKey.isEmpty()) {
      return;
    }
    validateKey(objectKey);
    Path filePath = resolvePath(objectKey);
    Path metaPath = filePath.resolveSibling(filePath.getFileName() + ".meta");

    try {
      Files.deleteIfExists(filePath);
      Files.deleteIfExists(metaPath);
    } catch (IOException e) {
      log.warn("删除本地文件失败: {}", filePath, e);
    }
  }

  /** 当前配置是否会用到客户自带钥。 */
  boolean usesCustomerKey() {
    return masterKey != null;
  }

  /** 新对象用的随机客户钥；非 sse-c 返回 null。 */
  byte[] newDataKey() {
    return usesCustomerKey() ? KeyWrap.randomDataKey() : null;
  }

  /** 复用已有对象的客户钥。 */
  byte[] reuseKey(byte[] sseKeyWrapped) {
    if (!usesCustomerKey()) {
      return null;
    }
    byte[] reused = sseKeyWrapped == null ? null : KeyWrap.unwrap(masterKey, sseKeyWrapped);
    return reused == null ? KeyWrap.randomDataKey() : reused;
  }

  private byte[] resolveKey(byte[] dataKey) {
    if (!usesCustomerKey()) {
      return null;
    }
    return dataKey == null ? KeyWrap.randomDataKey() : dataKey;
  }

  private byte[] unwrap(byte[] sseKeyWrapped) {
    if (sseKeyWrapped == null || sseKeyWrapped.length == 0) {
      return null;
    }
    if (!usesCustomerKey()) {
      throw new IllegalStateException("库里有封装钥但 pb.storage.type=local 且 pb.s3.object-encrypt="
          + encMode + "：对象是 sse-c 写的，读它必须配回同一个主钥");
    }
    byte[] dataKey = KeyWrap.unwrap(masterKey, sseKeyWrapped);
    if (dataKey == null) {
      throw new IllegalStateException("SSE-C 客户钥解封失败：pb.s3.master-key 与写入时不是同一把？");
    }
    return dataKey;
  }

  private byte[] encryptIfNeeded(byte[] data, byte[] customerKey) {
    if (customerKey == null) {
      return data;
    }
    // 简单实现：AES-256-GCM 封装（复用 KeyWrap 的逻辑）
    return KeyWrap.seal(customerKey, data);
  }

  private byte[] decryptIfNeeded(byte[] payload, byte[] customerKey) {
    if (customerKey == null) {
      return payload;
    }
    return KeyWrap.open(customerKey, payload);
  }

  private String readContentType(Path metaPath) throws IOException {
    if (!Files.exists(metaPath)) {
      return null;
    }
    String meta = Files.readString(metaPath);
    for (String line : meta.split("\n")) {
      if (line.startsWith("content-type=")) {
        String value = line.substring("content-type=".length());
        return value.isEmpty() ? null : value;
      }
    }
    return null;
  }

  private Path resolvePath(String objectKey) {
    // 对象键中的 / 映射为目录分隔符，文件名加 .bin 后缀
    String safeKey = objectKey.replace('/', java.io.File.separatorChar);
    Path resolved = root.resolve(safeKey + EXT).normalize();

    // 安全检查：防止路径遍历攻击
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("非法的对象键: " + objectKey);
    }
    return resolved;
  }

  private void validateKey(String objectKey) {
    if (objectKey == null || objectKey.isEmpty()) {
      throw new IllegalArgumentException("对象键不能为空");
    }
    // 禁止 .. 和绝对路径
    if (objectKey.contains("..") || objectKey.startsWith("/") || objectKey.startsWith("\\")) {
      throw new IllegalArgumentException("非法的对象键格式: " + objectKey);
    }
  }
}
