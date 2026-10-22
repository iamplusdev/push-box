package dev.pushbox.server.media;

import dev.pushbox.core.crypto.Hashes;
import dev.pushbox.core.crypto.KeyWrap;
import dev.pushbox.server.config.PbProperties;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * §4.5 的对象存储门面：抓取到的图片与缩略图在这里落盘，读侧由服务端取出明文字节后再返回（§5.2）。
 * 对象键统一为 {@code media/{msg_id}/{idx}}，缩略图加 {@code .thumb.jpg} 后缀。
 *
 * <p>支持两种存储后端（由 {@code pb.storage.type} 控制）：
 * <ul>
 *   <li>{@code s3}（默认）：走 S3 协议对接 RustFS/MinIO/OSS</li>
 *   <li>{@code local}：本地文件系统，用于无对象存储服务的部署</li>
 * </ul>
 *
 * <p>{@code pb.s3.object-encrypt} 决定存储里落的是明文还是密文：
 * <ul>
 *   <li>{@code none}：明文。对象键与字节都按原样读写，历史对象永远走这条路。</li>
 *   <li>{@code sse-c}：每个对象一把随机 32 字节客户钥，用它做服务端加解密；
 *       客户钥本身以 {@link KeyWrap} 封装后的形态存进 {@code sse_key_wrapped}，
 *       主钥只在服务端进程内存里。切换开关只影响新写入的对象，历史对象按各自记录的形态读。</li>
 * </ul>
 * 一个槽位的原图与缩略图**共用同一把客户钥**，所以 {@code media_objects} 一列就够；
 * 头像同理（一个对象键一把钥）。
 */
@Component
public class MediaObjects {

  private static final Logger log = LoggerFactory.getLogger(MediaObjects.class);

  public static final String ENC_NONE = "none";
  public static final String ENC_SSE_C = "sse-c";

  /** 写入结果就是落库要记的那两列：enc_mode 说明字节当时是怎么存的，封装钥只在 sse-c 下非空。 */
  public record PutResult(String encMode, byte[] sseKeyWrapped) {}

  /** 读到的字节 + 当初写进去的 Content-Type（S3 按它回 Content-Type，代理返回时照抄）。 */
  public record Loaded(byte[] body, String contentType) {}

  private final S3Client s3;
  private final LocalFileStorage localStorage;
  private final PbProperties.S3 config;
  private final String encMode;
  /** sse-c 的 KEK 来源：随机主钥或口令派生，两条都在这里统一。 */
  private final MediaKeyService keyService;
  private final boolean useLocalStorage;

  /** 手工构造路径（单测）：没有数据库，只支持随机主钥。 */
  public MediaObjects(@Nullable S3Client s3, PbProperties props) {
    this(s3, props, new MediaKeyService(props));
  }

  @Autowired
  public MediaObjects(@Nullable S3Client s3, PbProperties props, MediaKeyService keyService) {
    this.config = props.getS3();
    this.keyService = keyService;
    String storageType = props.getStorage().getType().trim();
    this.useLocalStorage = "local".equalsIgnoreCase(storageType);
    this.encMode = normalizeMode(config.getObjectEncrypt());

    if (!ENC_SSE_C.equals(encMode) && !ENC_NONE.equals(encMode)) {
      throw new IllegalStateException("pb.s3.object-encrypt 只支持 none|sse-c，当前是 " + encMode);
    }

    if (useLocalStorage) {
      this.s3 = null;
      String localPath = props.getStorage().getLocalPath();
      this.localStorage = new LocalFileStorage(localPath, encMode, keyService);
      log.info("对象存储后端=本地文件系统 path={} encrypt={}", localPath, encMode);
    } else {
      if (s3 == null) {
        throw new IllegalStateException("pb.storage.type=" + storageType
            + " 需要 S3Client，但 Spring 没有注入：这一项只认 s3|local，"
            + "拼成别的（local 少个 l、s3 后面多个空格）两边都不沾");
      }
      this.s3 = s3;
      this.localStorage = null;
      log.info("对象存储后端=S3 endpoint={} bucket={} encrypt={}",
          config.getEndpoint(), config.getBucket(), encMode);
    }

    log.info("对象存储加密模式={}（只影响新写入对象，历史对象按各自 enc_mode 读）", encMode);
  }

  public static String fullKey(String msgId, int idx) {
    return "media/" + msgId + "/" + idx;
  }

  public static String thumbKey(String msgId, int idx) {
    return fullKey(msgId, idx) + ".thumb.jpg";
  }

  /**
   * §4.5 的头像身份：{@code sender_id} 是第三方自报的任意字符串（可以含 {@code /}、{@code ..}、中文），
   * 拼进对象键等于让外部输入决定桶的目录结构，拼进 REST 路径就要指望客户端正确转义且服务端不把
   * {@code %2F} 再拆开，两头都不可控，所以统一换成它的 sha256 —— 客户端本地也能算出来。
   */
  public static String avatarToken(String senderId) {
    return Hashes.sha256Hex(senderId.getBytes(StandardCharsets.UTF_8));
  }

  /** 按 (频道, 发送者) 覆盖写，换头像不留孤儿对象，所以头像不需要进 §5.2 的宽限期巡检。 */
  public static String avatarKey(String channelNo, String avatarToken) {
    return "media/avatar/" + channelNo + "/" + avatarToken;
  }

  /** 当前配置是否会用到客户自带钥：抓取侧据此决定要不要生成并落库。 */
  public boolean usesCustomerKey() {
    return keyService.usesCustomerKey();
  }

  /** 配置里要求的桶内形态；某个对象实际的形态以 {@link PutResult#encMode()} 与库里那列为准。 */
  public String configuredEncMode() {
    return encMode;
  }

  /** 新对象用的随机客户钥；非 sse-c 返回 null（明文写）。 */
  public byte[] newDataKey() {
    return usesCustomerKey() ? KeyWrap.randomDataKey() : null;
  }

  /**
   * 覆盖写同一个对象键时延用库里那把老钥：头像换图是就地覆盖，换成新钥会让读侧撞上
   * "对象已经是新字节、库里还是旧封装钥"的空窗。解不开（主钥换过或列被改）才换新钥。
   */
  public byte[] reuseKey(byte[] sseKeyWrapped) {
    if (!usesCustomerKey()) {
      return null;
    }
    byte[] reused = sseKeyWrapped == null ? null : KeyWrap.unwrap(keyService.kek(), sseKeyWrapped);
    return reused == null ? KeyWrap.randomDataKey() : reused;
  }

  public PutResult put(String objectKey, byte[] data, String contentType) {
    return put(objectKey, data, contentType, null);
  }

  /**
   * @param dataKey sse-c 模式下同一个槽位的原图与缩略图要传同一把；传 null 时这里自己生成一把，
   *     绝不允许"配置要加密、结果落了明文"。非 sse-c 模式忽略它。
   */
  public PutResult put(String objectKey, byte[] data, String contentType, byte[] dataKey) {
    if (useLocalStorage) {
      return localStorage.put(objectKey, data, contentType, dataKey);
    }

    byte[] customerKey = resolveKey(dataKey);
    PutObjectRequest.Builder builder = PutObjectRequest.builder()
        .bucket(config.getBucket())
        .key(objectKey)
        .contentType(contentType)
        .contentLength((long) data.length);
    if (customerKey != null) {
      applySse(builder::sseCustomerAlgorithm, builder::sseCustomerKey, builder::sseCustomerKeyMD5,
          customerKey);
    }
    s3.putObject(builder.build(), RequestBody.fromBytes(data));
    return new PutResult(customerKey == null ? ENC_NONE : ENC_SSE_C,
        customerKey == null ? null : KeyWrap.wrap(keyService.kek(), customerKey));
  }

  /**
   * §5.2 读侧的取字节入口：服务端自己把对象取回来（必要时带客户钥解密）再交给客户端，
   * 所以桶的可达性与预签名都不需要暴露给设备。
   *
   * @param sseKeyWrapped 库里那一列。非空时先用主钥解出客户钥再读——桶里落的是密文，
   *     不带钥读直接失败；为 null 就是这个对象当年按明文写的。
   *     对象不存在返回 null。
   */
  public Loaded load(String objectKey, byte[] sseKeyWrapped) {
    if (useLocalStorage) {
      return localStorage.load(objectKey, sseKeyWrapped);
    }

    byte[] customerKey = unwrap(sseKeyWrapped);
    GetObjectRequest.Builder builder = GetObjectRequest.builder()
        .bucket(config.getBucket())
        .key(objectKey);
    if (customerKey != null) {
      applySse(builder::sseCustomerAlgorithm, builder::sseCustomerKey, builder::sseCustomerKeyMD5,
          customerKey);
    }
    try {
      ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(builder.build());
      return new Loaded(bytes.asByteArray(), bytes.response().contentType());
    } catch (NoSuchKeyException e) {
      return null;
    }
  }

  /** HEAD 也要带钥：SSE-C 对象的元数据同样只有客户钥能看。 */
  public boolean exists(String objectKey, byte[] sseKeyWrapped) {
    if (useLocalStorage) {
      return localStorage.exists(objectKey, sseKeyWrapped);
    }

    byte[] customerKey = unwrap(sseKeyWrapped);
    HeadObjectRequest.Builder builder = HeadObjectRequest.builder()
        .bucket(config.getBucket())
        .key(objectKey);
    if (customerKey != null) {
      applySse(builder::sseCustomerAlgorithm, builder::sseCustomerKey, builder::sseCustomerKeyMD5,
          customerKey);
    }
    try {
      s3.headObject(builder.build());
      return true;
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return false;
      }
      throw e;
    }
  }

  public boolean exists(String objectKey) {
    return exists(objectKey, null);
  }

  /**
   * §5.2 宽限期到点后的真删。S3 语义里 DeleteObject 不需要客户钥，本地文件系统也不需要。
   */
  public void delete(String objectKey) {
    if (objectKey == null || objectKey.isEmpty()) {
      return;
    }
    if (useLocalStorage) {
      localStorage.delete(objectKey);
    } else {
      s3.deleteObject(DeleteObjectRequest.builder().bucket(config.getBucket()).key(objectKey).build());
    }
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
      throw new IllegalStateException("库里有封装钥但 pb.s3.object-encrypt=" + encMode
          + "：对象是 sse-c 写的，读它必须配回同一个主钥或口令");
    }
    byte[] dataKey = KeyWrap.unwrap(keyService.kek(), sseKeyWrapped);
    if (dataKey == null) {
      throw new IllegalStateException(
          "SSE-C 客户钥解封失败：PB_S3_MASTER_KEY / PB_S3_MASTER_PASSPHRASE 与写入时不是同一个？");
    }
    return dataKey;
  }

  private static void applySse(Consumer<String> algorithm, Consumer<String> key,
      Consumer<String> md5, byte[] customerKey) {
    algorithm.accept(KeyWrap.SSE_ALGORITHM);
    key.accept(KeyWrap.sseKeyHeader(customerKey));
    md5.accept(KeyWrap.sseKeyMd5Header(customerKey));
  }

  private static String normalizeMode(String configured) {
    return configured == null || configured.isBlank() ? ENC_NONE
        : configured.trim().toLowerCase(Locale.ROOT);
  }
}
