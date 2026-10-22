package dev.pushbox.server.config;

import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * §11.2 prod 的沉默不安全默认值：图片与头像在存储里到底是明文还是密文。
 *
 * <p>{@code application-prod.yml} 里写的是 {@code object-encrypt: ${PB_S3_OBJECT_ENCRYPT:none}}，
 * 也就是说忘了配就等于"媒体的字节原样躺着"——而 {@code PB_S3_OBJECT_ENCRYPT} 又不是必填项（它带默认值），
 * 任何 {@code ${VAR:?}} 那套 fail-fast 都管不到它。空桶不会报错、健康检查会过、消息也发得出去，
 * 唯一的表现是"图片存进去了"，所以必须在这里停住。
 *
 * <p><b>判据按存储拓扑分档</b>（D76，与容器 entrypoint 同步改）：{@code pb.storage.type=s3} 时拒绝启动，
 * {@code local} 时只出声。理由是两个"明文"不是同一件事——桶那侧的明文，任何拿到同一组 AK/SK 的读者
 * （对象存储的管理台、备份工具、被误授权的第三方应用）都直接读得走；local 那侧的对象在部署者自己的
 * 那块盘上，{@code LocalFileStorage} 已经给每个对象设了 600 权限，剩下的暴露面就是"谁能读到这台 NAS 的盘"，
 * 那一层归磁盘与备份策略，不归这里的开关。把桶形态的门槛原样压到 local，代价是"拉一个镜像填两个框"
 * 这条起步路径必须凭空多配一个 {@code PB_ALLOW_PLAINTEXT_MEDIA}——一个只为通过检查而写的值，
 * 正是这套守卫想消灭的那种默认。
 *
 * <p>只管 prod：dev 联调就是明文桶，把同一个门槛压到 dev 只会让人绕过检查。
 */
@Component
public class ProdPlaintextMediaGuard implements BeanFactoryPostProcessor, EnvironmentAware {

  private static final Logger log = LoggerFactory.getLogger(ProdPlaintextMediaGuard.class);

  /** 迁移期逃生阀：显式承认"这批对象是明文"，而不是靠没人配的默认值默默承认。 */
  static final String ALLOW_PLAINTEXT_ENV = "PB_ALLOW_PLAINTEXT_MEDIA";

  private static final List<String> TRUTHY = List.of("1", "true", "yes", "on");

  private ConfigurableEnvironment environment;

  @Override
  public void setEnvironment(Environment env) {
    if (env instanceof ConfigurableEnvironment configurable) {
      this.environment = configurable;
    }
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
      throws BeansException {
    if (environment == null || !List.of(environment.getActiveProfiles()).contains("prod")) {
      return;
    }
    String mode = normalize(environment.getProperty("pb.s3.object-encrypt", "none"));
    String allowPlaintext = environment.getProperty(ALLOW_PLAINTEXT_ENV);
    // 默认按 s3 判：这一项没映射的旧配置就该走最严的那条路，别把"读不到配置"当成"选了本地盘"。
    String storageType = normalize(environment.getProperty("pb.storage.type", "s3"));
    if (mode.isEmpty()) {
      log.warn("pb.s3.object-encrypt 留空，按 none 处理：新写入的图片与头像是可读字节");
    }
    if (mustRefusePlaintext(mode, allowPlaintext)) {
      if (isLocalStorage(storageType)) {
        log.warn("""
            图片与头像将明文落在本地目录 {}（pb.s3.object-encrypt={}）。
                这里只出声不拒绝启动：本地盘的暴露面是"谁能读到这块盘"，对象已经按 600 权限写。
                要密文就设 PB_S3_OBJECT_ENCRYPT=sse-c，并二选一：
                PB_S3_MASTER_KEY（openssl rand -base64 32）或 PB_S3_MASTER_PASSPHRASE（至少 12 个字符）。
                注意 KEK 一旦用过就不可轮换。""",
            environment.getProperty("pb.storage.local-path", "/app/data/media"),
            mode.isEmpty() ? "未配置（按 none）" : mode);
        return;
      }
      throw new IllegalStateException(checkFailureMessage(mode));
    }
    if (isNoneMode(mode)) {
      // 明文桶是显式选择而不是默认结果，所以它必须在日志里看得见
      log.warn("逃生阀 {} 已打开：prod 的新对象继续按明文写入；历史对象仍按各自记的 enc_mode 读",
          ALLOW_PLAINTEXT_ENV);
    }
  }

  /** prod 且模式是明文（none/留空），而逃生阀没打开 ⇒ 该停。是否真停由存储拓扑决定。 */
  static boolean mustRefusePlaintext(String objectEncrypt, String allowPlaintextEnv) {
    return isNoneMode(objectEncrypt) && !isTruthly(allowPlaintextEnv);
  }

  static boolean isLocalStorage(String storageType) {
    return "local".equals(normalize(storageType));
  }

  private static boolean isNoneMode(String objectEncrypt) {
    String mode = normalize(objectEncrypt);
    return mode.isEmpty() || "none".equals(mode);
  }

  private static boolean isTruthly(String value) {
    return value != null && TRUTHY.contains(value.trim().toLowerCase(Locale.ROOT));
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  /** 只报键名与取值口径，任何情况下都不把主钥或口令带进这条消息。 */
  private static String checkFailureMessage(String objectEncrypt) {
    return """
        prod 拒绝以明文（pb.s3.object-encrypt=%s）存储图片与头像：PB_S3_OBJECT_ENCRYPT 没配时它默认就是 none，
            而明文桶不会报错、健康检查照过，唯一后果是媒体字节在对象存储里谁都读得动。
            两种走法，必须选一个写在 env 里：
              1) 推荐：PB_S3_OBJECT_ENCRYPT=sse-c，并给 KEK 二选一：
                 · PB_S3_MASTER_KEY=base64 的 32 字节（openssl rand -base64 32）；
                 · PB_S3_MASTER_PASSPHRASE=至少 12 个字符的口令（Argon2id 派生，盐/KCV 存 app_state，口令不落库）。
                 两个都配会拒绝启动。注意 KEK 一旦用过就不可轮换，换了旧对象的封装钥再也解不开；
                 切换只影响新写入的对象，历史对象按 media_objects/senders 各自记的 enc_mode 读，照旧能读。
              2) 迁移期临时放行明文：PB_ALLOW_PLAINTEXT_MEDIA=1。关掉它之后，之前按明文写的对象
                 不会变得读不出来——读侧按每个对象自己的 enc_mode 各自解读。
        """.formatted(normalize(objectEncrypt).isEmpty() ? "未配置（按 none）" : normalize(objectEncrypt));
  }
}
