package dev.pushbox.server.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.ThrowableAssert.catchThrowableOfType;

import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.setup.RuntimeConfig;
import dev.pushbox.server.test.TestConfig;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * §4.5 对象存储层与真实 RustFS 的对话，不做 mock：明文（none）与密文（sse-c）两种桶内形态都要过。
 * 没有凭据环境变量时整个类跳过，避免 CI/无存储环境红一片。
 */
@EnabledIf("dev.pushbox.server.test.TestConfig#objectStorageReady")
class MediaObjectsTest {

  private static final String RUN_PREFIX = "pushbox-test/" + System.currentTimeMillis() + "/";
  /**
   * 只为这一批临时对象存在的测试主钥：跑完就连对象一起删掉，不绑任何环境的凭据，也不进生产配置。
   * 生产的主钥来自 data/config/pushbox.json 的 storage.masterKey（§4.5），一旦定下就不能换——换了旧对象再也读不出来。
   */
  private static final String TEST_MASTER_KEY = Base64.getEncoder().encodeToString(new byte[] {
      1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
      17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32
  });

  private static MediaObjects plain;
  private static MediaObjects encrypted;
  private static final List<String> written = new ArrayList<>();

  @BeforeAll
  static void setUp() {
    plain = build("none", null);
    encrypted = build("sse-c", TEST_MASTER_KEY);
  }

  @AfterAll
  static void cleanUp() {
    for (String key : written) {
      // DeleteObject 不需要客户钥（S3 语义如此，RustFS 实测也接受），所以两种模式都能删干净
      plain.delete(key);
    }
    written.clear();
  }

  @Test
  @DisplayName("① 明文形态：5MB 原图写入后按对象键读回，字节与 Content-Type 都原样")
  void putAndReadBackLargeObject() {
    byte[] payload = randomBytes(5 * 1024 * 1024);
    String key = put(plain, RUN_PREFIX + "plain-full", payload, "image/png");

    MediaObjects.Loaded loaded = plain.load(key, null);

    assertThat(plain.exists(key)).isTrue();
    assertThat(loaded.body()).isEqualTo(payload);
    assertThat(loaded.contentType()).isEqualTo("image/png");
  }

  @Test
  @DisplayName("② sse-c 写入：库里要记的封装钥定长 61 字节，带它读回才是原图")
  void readsBackWithWrappedKey() {
    byte[] payload = randomBytes(64 * 1024);
    byte[] dataKey = encrypted.newDataKey();
    MediaObjects.PutResult result = encrypted.put(RUN_PREFIX + "sse-full", payload, "image/jpeg",
        dataKey);
    written.add(RUN_PREFIX + "sse-full");

    assertThat(result.encMode()).isEqualTo("sse-c");
    assertThat(result.sseKeyWrapped()).hasSize(61);
    assertThat(encrypted.load(RUN_PREFIX + "sse-full", result.sseKeyWrapped()).body())
        .isEqualTo(payload);
    assertThat(encrypted.exists(RUN_PREFIX + "sse-full", result.sseKeyWrapped())).isTrue();
  }

  @Test
  @DisplayName("③ 桶里落的是密文：不带客户钥读同一个对象直接被拒，读到的绝不是原字节")
  void keylessReadOfEncryptedObjectFails() {
    byte[] payload = randomBytes(4096);
    MediaObjects.PutResult result =
        encrypted.put(RUN_PREFIX + "sse-naked", payload, "image/png", encrypted.newDataKey());
    written.add(RUN_PREFIX + "sse-naked");

    // 这正是 §5.2 放弃"302 到预签名 URL"的原因：预签名带不了 SSE-C 头，客户端照着 URL 取只会拿到 400
    S3Exception keyless = catchThrowableOfType(
        () -> plain.load(RUN_PREFIX + "sse-naked", null), S3Exception.class);

    assertThat(keyless).isNotNull();
    assertThat(keyless.statusCode()).isEqualTo(400);
  }

  @Test
  @DisplayName("④ 封装钥过不了主钥就绝不开桶：主钥不对或列被改，一律当场报错而不是返回乱码")
  void rejectsUndecryptableWrappedKey() {
    byte[] payload = randomBytes(2048);
    MediaObjects.PutResult result =
        encrypted.put(RUN_PREFIX + "sse-badkey", payload, "image/png", encrypted.newDataKey());
    written.add(RUN_PREFIX + "sse-badkey");

    byte[] tampered = result.sseKeyWrapped().clone();
    tampered[20] ^= 0x01;

    assertThatThrownBy(() -> encrypted.load(RUN_PREFIX + "sse-badkey", tampered))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("解封失败");
    assertThatThrownBy(() -> build("sse-c", Base64.getEncoder().encodeToString(new byte[32]))
        .load(RUN_PREFIX + "sse-badkey", result.sseKeyWrapped()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("解封失败");
  }

  @Test
  @DisplayName("⑤ 覆盖写同一个对象键时延用旧客户钥：旧封装钥对新字节照样读得通，读侧没有空窗")
  void rewriteKeepsTheSameCustomerKey() {
    String key = RUN_PREFIX + "sse-rewrite";
    MediaObjects.PutResult first = encrypted.put(key, randomBytes(1024), "image/png",
        encrypted.newDataKey());
    written.add(key);

    byte[] second = randomBytes(2048);
    MediaObjects.PutResult again = encrypted.put(key, second, "image/png",
        encrypted.reuseKey(first.sseKeyWrapped()));

    assertThat(again.sseKeyWrapped()).isNotEqualTo(first.sseKeyWrapped());
    // 库里那一列被更新之前，拿着旧封装钥的在途读请求也得能读到东西
    assertThat(encrypted.load(key, first.sseKeyWrapped()).body()).isEqualTo(second);
  }

  @Test
  @DisplayName("⑥ 非 sse-c 模式：newDataKey/reuseKey 都返回 null，写入结果老老实实记成 none")
  void plainModeRecordsNoKey() {
    String key = RUN_PREFIX + "plain-mode";

    MediaObjects.PutResult result = plain.put(key, randomBytes(512), "image/gif",
        plain.reuseKey(plain.newDataKey()));
    written.add(key);

    assertThat(plain.usesCustomerKey()).isFalse();
    assertThat(result.encMode()).isEqualTo("none");
    assertThat(result.sseKeyWrapped()).isNull();
    assertThat(plain.load(key, null).contentType()).isEqualTo("image/gif");
  }

  @Test
  @DisplayName("⑦ 删除后 exists 转 false、load 返回 null（明文与密文对象都一样的语义）")
  void deleteRemovesObject() {
    String plainKey = put(plain, RUN_PREFIX + "gone-plain", randomBytes(256), "image/png");
    byte[] dataKey = encrypted.newDataKey();
    MediaObjects.PutResult sse = encrypted.put(RUN_PREFIX + "gone-sse", randomBytes(256),
        "image/png", dataKey);
    written.add(RUN_PREFIX + "gone-sse");

    plain.delete(plainKey);
    plain.delete(RUN_PREFIX + "gone-sse");

    assertThat(plain.exists(plainKey)).isFalse();
    assertThat(plain.load(plainKey, null)).isNull();
    assertThat(encrypted.exists(RUN_PREFIX + "gone-sse", sse.sseKeyWrapped())).isFalse();
    assertThat(encrypted.load(RUN_PREFIX + "gone-sse", sse.sseKeyWrapped())).isNull();
  }

  private static String put(MediaObjects objects, String key, byte[] data, String contentType) {
    objects.put(key, data, contentType);
    written.add(key);
    return key;
  }

  private static MediaObjects build(String mode, String masterKey) {
    PbProperties.S3 config = new PbProperties.S3();
    RuntimeConfig.Storage source = TestConfig.storage();
    config.setEndpoint(source.getEndpoint());
    config.setRegion("us-east-1");
    config.setBucket(source.getBucket());
    config.setAccessKey(source.getAccessKey());
    config.setSecretKey(source.getSecretKey());
    config.setPathStyle(true);
    config.setObjectEncrypt(mode);
    config.setMasterKey(masterKey);

    PbProperties props = new PbProperties();
    props.setS3(config);
    return new MediaObjects(S3Client.builder()
        .endpointOverride(URI.create(config.getEndpoint()))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build(), props);
  }

  private static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    new Random(20261014L).nextBytes(bytes);
    return bytes;
  }
}
