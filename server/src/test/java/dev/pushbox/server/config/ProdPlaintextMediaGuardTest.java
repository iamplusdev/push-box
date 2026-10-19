package dev.pushbox.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pushbox.server.media.MediaObjects;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * §11.2 prod 的"忘了配就等于明文"这一关。不起整个应用：只起一个 GenericApplicationContext
 * 并按 profile 与配置取值刷新，判据就这两维（profile 是 prod、object-encrypt 是明文）。
 *
 * <p>顺带钉住另外两件事：逃生阀打开时日志与报错都不带出任何密钥值；
 * 以及 sse-c 缺主钥仍然由 {@link MediaObjects} 在构造期拒绝启动（这条早就有，回归时要还在）。
 */
class ProdPlaintextMediaGuardTest {

  private static final String MASTER_KEY_SENTINEL = "M0terKey-NEVER-PRINT-THIS-32bytes-base64";

  @Test
  @DisplayName("① prod + 明文（没配 / 留空 / 写 none）⇒ 当场拒绝启动，并说清该配什么")
  void prodRefusesPlaintextObjects() {
    for (String mode : new String[] {null, "", "   ", "none", " NONE "}) {
      assertThatThrownBy(() -> refresh("prod", mode, null))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("PB_S3_OBJECT_ENCRYPT")
          .hasMessageContaining("PB_ALLOW_PLAINTEXT_MEDIA")
          .hasMessageContaining("PB_S3_MASTER_KEY")
          // 切换只影响新对象，历史对象按各自 enc_mode 读——报错里必须把这件事说清楚
          .hasMessageContaining("enc_mode");
    }
    assertThat(ProdPlaintextMediaGuard.mustRefusePlaintext("none", null)).isTrue();
    assertThat(ProdPlaintextMediaGuard.mustRefusePlaintext("", "0")).isTrue();
  }

  @Test
  @DisplayName("② 逃生阀要显式表态：1/true/yes/on 才认，别的值等同于没打开；报错不带任何密钥值")
  void escapeHatchOnlyOpensOnAnExplicitTruthyValue() {
    for (String opened : new String[] {"1", "true", "TRUE", " yes ", "on"}) {
      assertThatCode(() -> refresh("prod", "none", opened)).doesNotThrowAnyException();
      assertThat(ProdPlaintextMediaGuard.mustRefusePlaintext("none", opened)).isFalse();
    }
    for (String notOpened : new String[] {"0", "false", "no", "off", "", "   "}) {
      assertThatThrownBy(() -> refresh("prod", "none", notOpened))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageNotContaining(MASTER_KEY_SENTINEL);
      assertThat(ProdPlaintextMediaGuard.mustRefusePlaintext("none", notOpened)).isTrue();
    }
  }

  @Test
  @DisplayName("③ 配了 sse-c 就放行；主钥那一关不由这里管")
  void sseCPassesTheGuard() {
    assertThatCode(() -> refresh("prod", "sse-c", null)).doesNotThrowAnyException();
    assertThatCode(() -> refresh("prod", "SSE-C", "0")).doesNotThrowAnyException();
    assertThat(ProdPlaintextMediaGuard.mustRefusePlaintext("sse-c", null)).isFalse();
  }

  @Test
  @DisplayName("④ dev 不归这里管：联调就是明文桶，把门槛压到 dev 只会让人绕过检查")
  void devKeepsPlainObjects() {
    assertThatCode(() -> refresh("dev", "none", null)).doesNotThrowAnyException();
    assertThatCode(() -> refresh("dev", null, null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("⑤ sse-c 却解不出 32 字节主钥：构造期就停住，静默退回明文是最坏的结果")
  void sseCWithoutUsableMasterKeyStillFailsStartup() {
    PbProperties props = new PbProperties();
    PbProperties.S3 s3 = new PbProperties.S3();
    s3.setEndpoint("http://127.0.0.1:1");
    s3.setBucket("pushbox");
    s3.setAccessKey("ak");
    s3.setSecretKey("sk");
    s3.setObjectEncrypt("sse-c");
    s3.setMasterKey("");
    props.setS3(s3);

    assertThatThrownBy(() -> new MediaObjects(null, props))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pb.s3.master-key");
  }

  private static void refresh(String profile, String objectEncrypt, String allowPlaintext) {
    GenericApplicationContext ctx = new GenericApplicationContext();
    // 摘掉进程环境：本机常常 source 过 local.env，留着就造不出"没配"的那个状态
    ctx.getEnvironment().getPropertySources().remove("systemEnvironment");
    ctx.getEnvironment().getPropertySources().remove("systemProperties");
    ctx.getEnvironment().setActiveProfiles(profile);
    Map<String, Object> props = new LinkedHashMap<>();
    if (objectEncrypt != null) {
      props.put("pb.s3.object-encrypt", objectEncrypt);
    }
    if (allowPlaintext != null) {
      props.put(ProdPlaintextMediaGuard.ALLOW_PLAINTEXT_ENV, allowPlaintext);
    }
    // 主钥放进来只为了证明报错与日志不会把它写出去
    props.put("pb.s3.master-key", MASTER_KEY_SENTINEL);
    ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", props));
    ctx.registerBean(ProdPlaintextMediaGuard.class);
    try {
      ctx.refresh();
    } finally {
      ctx.close();
    }
  }
}
