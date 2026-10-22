package dev.pushbox.server.media;

import dev.pushbox.core.crypto.B64;
import dev.pushbox.core.crypto.KeyWrap;
import dev.pushbox.core.protocol.Json;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Base64;
import java.util.Locale;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * §4.5 的媒体 KEK 来源。随机主钥模式与旧配置完全一致；口令模式把「用户记得住的一句话」
 * 经 Argon2id 派生为 32 字节 KEK，盐/KDF 参数/KCV 存 {@code app_state}，口令本身不落库。
 *
 * <p>KCV 是启动自检：错口令在启动时就会被点名，而不是等第一张老图读不出来才发现。
 * 固定盐不可取——库被清空时 {@code sse_key_wrapped} 也一起没了，固定盐救不回旧图，
 * 却会让所有「同口令」的部署共用一把 KEK。这里每个安装生成一把随机盐。
 *
 * <p>口令不是登录密码：换口令意味着旧封装钥全部解不开，本版不提供重封装工具。
 * 已有随机主钥写的 sse-c 数据也不能直接切到口令模式（见 {@link #loadOrInitPassphraseKek()}）。
 */
@Component
public class MediaKeyService implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(MediaKeyService.class);

  static final String STATE_KEY = "media_kek_v1";
  static final int MIN_PASSPHRASE_CODE_POINTS = 12;
  private static final int SALT_BYTES = 16;
  private static final byte[] KCV_PLAINTEXT =
      "pushbox-media-kek-check-v1".getBytes(StandardCharsets.UTF_8);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final PbStore store;
  private final boolean sseC;
  private final boolean passphraseMode;
  private final String passphrase;
  private final byte[] staticKey;
  private volatile byte[] kek;

  @Autowired
  public MediaKeyService(PbProperties props, PbStore store) {
    PbProperties.S3 config = props.getS3();
    this.store = store;
    this.sseC = MediaObjects.ENC_SSE_C.equals(normalizeMode(config.getObjectEncrypt()));

    boolean hasKey = !isBlank(config.getMasterKey());
    boolean hasPassphrase = !isBlank(config.getMasterPassphrase());
    if (!sseC) {
      this.passphraseMode = false;
      this.passphrase = null;
      this.staticKey = null;
      return;
    }
    if (hasKey && hasPassphrase) {
      throw new IllegalStateException(
          "PB_S3_MASTER_KEY 与 PB_S3_MASTER_PASSPHRASE 只能二选一：两种来源派生出的 KEK 不同，"
              + "同时配置无法判断该用哪一把");
    }
    if (hasPassphrase) {
      this.passphrase = normalizeAndCheckPassphrase(config.getMasterPassphrase());
      this.passphraseMode = true;
      this.staticKey = null;
    } else {
      byte[] parsed = KeyWrap.masterKeyFromConfig(config.getMasterKey());
      if (parsed == null) {
        throw new IllegalStateException(
            "pb.s3.object-encrypt=sse-c 需要 pb.s3.master-key"
                + "（base64 的 32 字节，例如 openssl rand -base64 32）"
                + "或 pb.s3.master-passphrase（至少 12 个字符），当前两种都没有可用值");
      }
      this.passphrase = null;
      this.passphraseMode = false;
      this.staticKey = parsed;
    }
  }

  /** 手工构造 MediaObjects 的测试路径：随机主钥模式不需要数据库。 */
  MediaKeyService(PbProperties props) {
    this(props, null);
  }

  public boolean usesCustomerKey() {
    return sseC;
  }

  public String source() {
    return passphraseMode ? "passphrase" : "master-key";
  }

  /** 取 KEK。口令模式下第一次调用会读/建 app_state，之后复用内存里的 32 字节。 */
  public byte[] kek() {
    if (!sseC) {
      throw new IllegalStateException("明文模式没有媒体 KEK");
    }
    if (staticKey != null) {
      return staticKey;
    }
    byte[] current = kek;
    if (current == null) {
      synchronized (this) {
        current = kek;
        if (current == null) {
          current = loadOrInitPassphraseKek();
          kek = current;
        }
      }
    }
    return current;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!sseC) {
      return;
    }
    if (passphraseMode) {
      String stored = store == null ? null : store.getState(STATE_KEY);
      if (stored == null) {
        // 空库不初始化：口令第一次打错不该在"还没有任何图片"时被钉进 app_state。
        // 但旧主钥写的对象必须现在就点名，不能等第一张图读不出来才发现模式切错了。
        if (store != null && store.hasAnyWrappedKey()) {
          throw new IllegalStateException(
              "库里有 sse-c 封装钥，但没有口令派生参数：这批对象是用 PB_S3_MASTER_KEY 写的。"
                  + "继续使用原主钥，或先做重封装；不能直接改成 PB_S3_MASTER_PASSPHRASE");
        }
        log.info("媒体加密已就绪：mode=sse-c source=passphrase"
            + "（盐与 KCV 会在第一张图片写入时初始化）");
        return;
      }
      kek();
      log.info("媒体加密已就绪：mode=sse-c source=passphrase keyBytes={}", kek().length);
      return;
    }
    if (store != null && store.getState(STATE_KEY) != null) {
      throw new IllegalStateException(
          "库里已有 PB_S3_MASTER_PASSPHRASE 派生参数（app_state.media_kek_v1）；"
              + "继续用同一个口令重启，或在确认库里没有口令模式写入的对象后，"
              + "手工删除 app_state 里的 media_kek_v1 一行再改用 PB_S3_MASTER_KEY");
    }
    log.info("媒体加密已就绪：mode=sse-c source=master-key keyBytes={}", staticKey.length);
  }

  private byte[] loadOrInitPassphraseKek() {
    if (store == null) {
      throw new IllegalStateException(
          "PB_S3_MASTER_PASSPHRASE 模式需要数据库：盐/KCV 存在 app_state，"
              + "手工构造 MediaObjects 的测试路径请改用 pb.s3.master-key");
    }
    String stored = store.getState(STATE_KEY);
    if (stored == null) {
      // 先问「库里有没有旧主钥写的对象」，再决定能不能建新参数。
      // 顺序不能反：先写盐会把一个不匹配的 KDF 状态钉进库里，旧对象以后更难救。
      if (store.hasAnyWrappedKey()) {
        throw new IllegalStateException(
            "库里有 sse-c 封装钥，但没有口令派生参数：这批对象是用 PB_S3_MASTER_KEY 写的。"
                + "继续使用原主钥，或先做重封装；不能直接改成 PB_S3_MASTER_PASSPHRASE");
      }
      return initializeState();
    }
    KekState state = parseState(stored);
    byte[] derived = deriveKek(state.salt(), state.kdf());
    verifyKcv(derived, state.kcv());
    log.info("媒体 KEK 来源=PB_S3_MASTER_PASSPHRASE，KDF={}（盐/KCV 来自 app_state）", state.kdf());
    return derived;
  }

  private byte[] initializeState() {
    byte[] salt = new byte[SALT_BYTES];
    RANDOM.nextBytes(salt);
    KdfParams params = KdfParams.defaults();
    String kdf = params.canonical();
    byte[] candidate = deriveKek(salt, kdf);
    KekState candidateState =
        new KekState(1, kdf, B64.enc(salt), sealKcv(candidate));
    String json = Json.write(candidateState);
    boolean won = store.putStateIfAbsent(STATE_KEY, json, System.currentTimeMillis());
    if (won) {
      log.info("首次生成媒体 KEK 派生参数：KDF={}（盐与 KCV 已写入 app_state；口令本身不落库）",
          kdf);
      return candidate;
    }
    KekState effective = parseState(requireState());
    byte[] derived = deriveKek(effective.salt(), effective.kdf());
    verifyKcv(derived, effective.kcv());
    log.info("另一个实例已先写入媒体 KEK 派生参数，按 KDF={} 重新派生", effective.kdf());
    return derived;
  }

  private String requireState() {
    String stored = store.getState(STATE_KEY);
    if (stored == null) {
      throw new IllegalStateException("app_state." + STATE_KEY + " 写入后读不回来，无法确定媒体 KEK");
    }
    return stored;
  }

  private KekState parseState(String stored) {
    KekState state;
    try {
      state = Json.read(stored, KekState.class);
    } catch (RuntimeException e) {
      throw new IllegalStateException("app_state." + STATE_KEY + " 不是合法的媒体 KEK 参数", e);
    }
    if (state == null || state.version() != 1 || isBlank(state.kdf())
        || isBlank(state.salt()) || isBlank(state.kcv())) {
      throw new IllegalStateException("app_state." + STATE_KEY + " 缺少 version/kdf/salt/kcv");
    }
    return state;
  }

  private byte[] deriveKek(String saltText, String kdfText) {
    KdfParams params = KdfParams.parse(kdfText);
    byte[] salt = B64.tryDec(saltText);
    if (salt == null || salt.length != SALT_BYTES) {
      throw new IllegalStateException("app_state." + STATE_KEY + " 的盐不是 " + SALT_BYTES + " 字节");
    }
    return deriveKek(salt, params);
  }

  private byte[] deriveKek(byte[] salt, String kdfText) {
    return deriveKek(salt, KdfParams.parse(kdfText));
  }

  private byte[] deriveKek(byte[] salt, KdfParams params) {
    Argon2Parameters argon2 = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(params.version())
        .withSalt(salt)
        .withIterations(params.iterations())
        .withMemoryAsKB(params.memoryKb())
        .withParallelism(params.parallelism())
        .build();
    Argon2BytesGenerator generator = new Argon2BytesGenerator();
    generator.init(argon2);
    byte[] derived = new byte[KeyWrap.DATA_KEY_BYTES];
    generator.generateBytes(passphrase.toCharArray(), derived);
    return derived;
  }

  private String sealKcv(byte[] kekBytes) {
    return B64.enc(KeyWrap.seal(kekBytes, KCV_PLAINTEXT));
  }

  private void verifyKcv(byte[] kekBytes, String kcvText) {
    byte[] sealed = B64.tryDec(kcvText);
    if (sealed == null) {
      throw new IllegalStateException("app_state." + STATE_KEY + " 的 KCV 不是合法 base64");
    }
    byte[] actual;
    try {
      actual = KeyWrap.open(kekBytes, sealed);
    } catch (RuntimeException e) {
      throw wrongPassphrase(e);
    }
    if (!MessageDigest.isEqual(KCV_PLAINTEXT, actual)) {
      throw wrongPassphrase(null);
    }
  }

  private static IllegalStateException wrongPassphrase(Throwable cause) {
    return new IllegalStateException(
        "PB_S3_MASTER_PASSPHRASE 与首次启用时不是同一个口令（KCV 校验失败）："
            + "口令本身不会被打印；换口令会让旧图片无法解密。"
            + "如果这是刚部署、库里还没有任何图片，删除 app_state 里的 "
            + STATE_KEY + " 一行后即可用新口令重新初始化",
        cause);
  }

  static String normalizeAndCheckPassphrase(String raw) {
    String normalized = Normalizer.normalize(raw.strip(), Normalizer.Form.NFC);
    int points = normalized.codePointCount(0, normalized.length());
    if (points < MIN_PASSPHRASE_CODE_POINTS) {
      throw new IllegalStateException(
          "PB_S3_MASTER_PASSPHRASE 至少需要 " + MIN_PASSPHRASE_CODE_POINTS
              + " 个字符（当前 " + points + " 个）；短口令在库泄露后可被离线穷举");
    }
    return normalized;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String normalizeMode(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * 只存 KDF 的算法与代价参数，不存口令。版本化成字符串是为了以后调参数时，
   * 旧部署仍能按当时那份参数派生出同一把 KEK。
   */
  record KdfParams(int version, int memoryKb, int iterations, int parallelism) {

    static KdfParams defaults() {
      return new KdfParams(Argon2Parameters.ARGON2_VERSION_13, 65_536, 3, 1);
    }

    String canonical() {
      return "argon2id$v=" + version + "$m=" + memoryKb + ",t=" + iterations + ",p=" + parallelism;
    }

    static KdfParams parse(String value) {
      if (value == null) {
        throw new IllegalStateException("媒体 KEK 的 KDF 参数为空");
      }
      String[] parts = value.trim().split("\\$");
      if (parts.length != 3 || !"argon2id".equals(parts[0]) || !parts[1].startsWith("v=")) {
        throw new IllegalStateException("不认识的媒体 KEK KDF：" + value);
      }
      try {
        int version = Integer.parseInt(parts[1].substring(2));
        int memoryKb = -1;
        int iterations = -1;
        int parallelism = -1;
        for (String item : parts[2].split(",")) {
          String[] kv = item.split("=", 2);
          if (kv.length != 2) {
            throw new NumberFormatException(item);
          }
          switch (kv[0]) {
            case "m" -> memoryKb = Integer.parseInt(kv[1]);
            case "t" -> iterations = Integer.parseInt(kv[1]);
            case "p" -> parallelism = Integer.parseInt(kv[1]);
            default -> throw new NumberFormatException(item);
          }
        }
        if (version != Argon2Parameters.ARGON2_VERSION_13
            || memoryKb < 8_192 || memoryKb > 1_048_576
            || iterations < 1 || iterations > 10
            || parallelism < 1 || parallelism > 8) {
          throw new NumberFormatException(value);
        }
        return new KdfParams(version, memoryKb, iterations, parallelism);
      } catch (RuntimeException e) {
        throw new IllegalStateException("媒体 KEK 的 KDF 参数不合法：" + value, e);
      }
    }
  }

  /** app_state 里那一份参数。口令不在这里，只有算法、盐与 KCV。 */
  public record KekState(int version, String kdf, String salt, String kcv) {}
}
