package dev.pushbox.server.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pushbox.server.config.PbProperties;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §4.5 的加密开关在启动阶段就得把话说死，所以这些用例不需要对象存储：构造 MediaObjects 只做配置校验。
 * 核心是"配了 sse-c 却没有可用主钥 ⇒ 拒绝启动"——静默退回明文是这个功能最坏的失败方式。
 */
class MediaObjectsConfigTest {

  private static final byte[] MASTER_KEY_BYTES = keyMaterial();
  private static final String MASTER_KEY = Base64.getEncoder().encodeToString(MASTER_KEY_BYTES);

  @Test
  @DisplayName("① sse-c 必须配上能解出 32 字节的主钥：没配、乱码、长度不对都拒绝启动")
  void sseCRequiresMasterKey() {
    assertThat(build("sse-c", MASTER_KEY).usesCustomerKey()).isTrue();

    for (String unusable : new String[] {null, "", "   ", "不是 base64 !!",
        Base64.getEncoder().encodeToString(new byte[31]),
        Base64.getEncoder().encodeToString(new byte[33])}) {
      assertThatThrownBy(() -> build("sse-c", unusable))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("主钥");
    }
  }

  @Test
  @DisplayName("② 标准与 URL 安全两种 base64 都是同一把钥：不该因为运维用了别的工具就启动失败")
  void acceptsBothBase64Alphabets() {
    assertThat(build("sse-c", MASTER_KEY).configuredEncMode())
        .isEqualTo(MediaObjects.ENC_SSE_C);
    assertThat(build("sse-c",
        Base64.getUrlEncoder().withoutPadding().encodeToString(MASTER_KEY_BYTES))
        .configuredEncMode()).isEqualTo(MediaObjects.ENC_SSE_C);
  }

  @Test
  @DisplayName("③ 留空与写 none 都是明文形态：光配了主钥不会偷偷开始加密")
  void blankModeMeansPlain() {
    assertThat(build(null, MASTER_KEY).configuredEncMode()).isEqualTo(MediaObjects.ENC_NONE);
    assertThat(build("", MASTER_KEY).usesCustomerKey()).isFalse();
    assertThat(build("  NONE ", null).configuredEncMode()).isEqualTo(MediaObjects.ENC_NONE);
  }

  @Test
  @DisplayName("④ 不认识的取值一律拒绝启动：sse-s3 要 RustFS 侧先配主密钥，本期没接线就别装作支持")
  void rejectsUnknownMode() {
    assertThatThrownBy(() -> build("sse-s3", MASTER_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("none|sse-c");
    assertThatThrownBy(() -> build("aes-256", MASTER_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("none|sse-c");
  }

  private static byte[] keyMaterial() {
    byte[] key = new byte[32];
    for (int i = 0; i < key.length; i++) {
      key[i] = (byte) (i * 7 + 1);
    }
    return key;
  }

  private static MediaObjects build(String mode, String masterKey) {
    PbProperties.S3 s3 = new PbProperties.S3();
    s3.setEndpoint("http://127.0.0.1:1");
    s3.setBucket("pushbox");
    s3.setAccessKey("ak");
    s3.setSecretKey("sk");
    s3.setObjectEncrypt(mode);
    s3.setMasterKey(masterKey);
    PbProperties props = new PbProperties();
    props.setS3(s3);
    // 使用本地存储后端，避免需要真实的 S3Client
    PbProperties.Storage storage = new PbProperties.Storage();
    storage.setType("local");
    storage.setLocalPath(System.getProperty("java.io.tmpdir") + "/pb-test-" + System.currentTimeMillis());
    props.setStorage(storage);
    // 构造函数只校验配置、不碰客户端，所以这里不需要真连上任何桶
    return new MediaObjects(null, props);
  }
}
