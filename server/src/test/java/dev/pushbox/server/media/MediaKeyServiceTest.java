package dev.pushbox.server.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 口令派生 KEK 的判据：同一口令 + 同一份 app_state ⇒ 同一把 KEK（重启/重装都算），
 * 错口令在启动时被 KCV 点名，且口令本身一个字都不落库。
 */
class MediaKeyServiceTest {

  private static final String PASSPHRASE = "correct horse battery staple";
  private static final String MASTER_KEY =
      Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  @DisplayName("① 同一口令重启后仍是同一把 KEK，app_state 里没有口令")
  void samePassphraseSurvivesRestart() {
    FakeStore store = new FakeStore();

    byte[] first = new MediaKeyService(props("sse-c", null, PASSPHRASE), store).kek();
    byte[] second = new MediaKeyService(props("sse-c", null, PASSPHRASE), store).kek();

    assertThat(first).hasSize(32);
    assertThat(second).isEqualTo(first);
    assertThat(store.rawState())
        .isNotNull()
        .contains("argon2id")
        .doesNotContain(PASSPHRASE);
  }

  @Test
  @DisplayName("② 每次安装的盐是随机的：同口令在不同 app_state 下不会共用一把 KEK")
  void eachInstallGetsItsOwnSalt() {
    byte[] a = new MediaKeyService(props("sse-c", null, PASSPHRASE), new FakeStore()).kek();
    byte[] b = new MediaKeyService(props("sse-c", null, PASSPHRASE), new FakeStore()).kek();

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  @DisplayName("③ 错口令在启动与首用时都被 KCV 拦住，不会读到一半才发现")
  void wrongPassphraseFailsKcv() {
    FakeStore store = new FakeStore();
    new MediaKeyService(props("sse-c", null, PASSPHRASE), store).kek();

    MediaKeyService wrong =
        new MediaKeyService(props("sse-c", null, "another correct horse battery"), store);

    assertThatThrownBy(() -> wrong.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("KCV");
    assertThatThrownBy(wrong::kek)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是同一个");
  }

  @Test
  @DisplayName("④ 短于 12 个字符直接拒绝：短口令在库泄露后可离线穷举")
  void shortPassphraseIsRejected() {
    assertThatThrownBy(() -> new MediaKeyService(props("sse-c", null, "12345678901"), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("至少需要 12");
  }

  @Test
  @DisplayName("⑤ 主钥与口令只能二选一：两把 KEK 不同，不能猜")
  void keyAndPassphraseAreMutuallyExclusive() {
    assertThatThrownBy(
        () -> new MediaKeyService(props("sse-c", MASTER_KEY, PASSPHRASE), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("二选一");
  }

  @Test
  @DisplayName("⑥ 库里已有口令派生参数时改回随机主钥：拒绝启动，并点名 app_state 那一行")
  void switchingBackToMasterKeyIsRefused() {
    FakeStore store = new FakeStore();
    new MediaKeyService(props("sse-c", null, PASSPHRASE), store).kek();

    MediaKeyService keyMode = new MediaKeyService(props("sse-c", MASTER_KEY, null), store);

    assertThatThrownBy(() -> keyMode.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("media_kek_v1")
        .hasMessageContaining("主钥");
  }

  @Test
  @DisplayName("⑦ 旧主钥写的封装钥 + 口令模式：拒绝且一个字节都不写进 app_state")
  void legacyWrappedKeysBlockPassphraseMode() {
    FakeStore store = new FakeStore();
    store.wrappedKey = true;

    MediaKeyService service = new MediaKeyService(props("sse-c", null, PASSPHRASE), store);

    assertThatThrownBy(() -> service.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("主钥");
    assertThatThrownBy(service::kek)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("主钥");
    assertThat(store.rawState()).isNull();
  }

  @Test
  @DisplayName("⑧ 空库启动不写参数：第一次打错的口令不该被钉死，首张图片写入时才初始化")
  void emptyDatabaseDoesNotInitializeAtStartup() {
    FakeStore store = new FakeStore();
    MediaKeyService service = new MediaKeyService(props("sse-c", null, PASSPHRASE), store);

    assertThatCode(() -> service.run(null)).doesNotThrowAnyException();
    assertThat(store.rawState()).isNull();

    assertThat(service.kek()).hasSize(32);
    assertThat(store.rawState()).isNotNull();
  }

  @Test
  @DisplayName("⑨ 随机主钥模式不需要数据库：旧配置原样能起")
  void masterKeyModeWorksWithoutStore() {
    MediaKeyService service = new MediaKeyService(props("sse-c", MASTER_KEY, null), null);

    assertThat(service.usesCustomerKey()).isTrue();
    assertThat(service.source()).isEqualTo("master-key");
    assertThat(service.kek()).hasSize(32);
    assertThatCode(() -> service.run(null)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("⑩ 口令按 NFC 归一化并去掉首尾空白：复制粘贴的差异不该变成另一把 KEK")
  void passphraseIsNormalized() {
    FakeStore store = new FakeStore();
    String composed = "café-au-lait-xxxx";
    String decomposed = "cafe\u0301-au-lait-xxxx";

    byte[] first =
        new MediaKeyService(props("sse-c", null, "  " + composed + "  "), store).kek();
    byte[] second = new MediaKeyService(props("sse-c", null, decomposed), store).kek();

    assertThat(second).isEqualTo(first);
  }

  @Test
  @DisplayName("⑪ 明文模式没有 KEK，也不读数据库")
  void plainModeHasNoKey() {
    MediaKeyService service = new MediaKeyService(props("none", null, null), null);

    assertThat(service.usesCustomerKey()).isFalse();
    assertThatThrownBy(service::kek)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("明文模式");
    assertThatCode(() -> service.run(null)).doesNotThrowAnyException();
  }

  private static PbProperties props(String mode, String masterKey, String passphrase) {
    PbProperties.S3 s3 = new PbProperties.S3();
    s3.setObjectEncrypt(mode);
    s3.setMasterKey(masterKey);
    s3.setMasterPassphrase(passphrase);
    PbProperties props = new PbProperties();
    props.setS3(s3);
    return props;
  }

  /** 只实现 app_state 与"有没有封装钥"两件事，不碰 MyBatis。 */
  private static final class FakeStore extends PbStore {
    private final Map<String, String> state = new HashMap<>();
    private boolean wrappedKey;

    FakeStore() {
      super(null);
    }

    @Override
    public String getState(String key) {
      return state.get(key);
    }

    @Override
    public boolean putStateIfAbsent(String key, String value, long now) {
      if (state.containsKey(key)) {
        return false;
      }
      state.put(key, value);
      return true;
    }

    @Override
    public boolean hasAnyWrappedKey() {
      return wrappedKey;
    }

    String rawState() {
      return state.get(MediaKeyService.STATE_KEY);
    }
  }
}
