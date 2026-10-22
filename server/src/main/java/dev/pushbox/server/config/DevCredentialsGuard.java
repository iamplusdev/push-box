package dev.pushbox.server.config;

import java.util.List;
import javax.sql.DataSource;
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
 * §11.1 dev 的凭据必填检查。
 *
 * <p>IDEA 的运行配置不读 {@code deploy/env/local.env}，于是 {@code ${PB_DB_PASSWORD}} 解析不了。
 * Spring 的 Binder 对解析不了的占位符是"原样留下"而不是报错，所以症状是那个字面量被当成口令发给
 * MySQL，返回一条 {@code 1045 Access denied}——而 §13 的日志脱敏恰好又把最能定位问题的
 * {@code using password: YES/NO} 遮成了 {@code ***}，排障的人两头都看不到线索。
 *
 * <p>所以检查放在 DataSource 即将创建的那一刻：比 Flyway 连库早，比任何业务调用早，
 * 且只对"这次运行真的会去连库"的上下文生效（只加载 yml 与 Logback 的测试上下文没有 DataSource
 * 定义，不会被这条打断）。
 *
 * <p>同一处还负责反向的那半：{@code PB_STORAGE_TYPE=local} 却仍带着 {@code PB_S3_*} 时出声告警
 * （见 {@link #warnLingeringS3Credentials()}）。必填项按存储类型分档之后，"忘了设 type"不再会
 * 让启动失败，只会让整轮测试静默跑在本地目录上。
 */
@Component
public class DevCredentialsGuard implements BeanFactoryPostProcessor, EnvironmentAware {

  private static final Logger log = LoggerFactory.getLogger(DevCredentialsGuard.class);

  private static final List<String> DB_REQUIRED = List.of("PB_DB_PASSWORD");

  /** 只有 {@code pb.storage.type=s3} 才要这三样；本机联调默认走本地文件存储（见 application-dev.yml）。 */
  private static final List<String> S3_REQUIRED =
      List.of("PB_S3_ENDPOINT", "PB_S3_ACCESS_KEY", "PB_S3_SECRET_KEY");

  private ConfigurableEnvironment environment;

  @Override
  public void setEnvironment(Environment env) {
    if (env instanceof ConfigurableEnvironment configurable) {
      this.environment = configurable;
    }
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
    if (environment == null || !List.of(environment.getActiveProfiles()).contains("dev")) {
      // 只管 dev：prod 由容器里的 ${VAR:?} 与 S3Config 守，不在这儿重复一套口径
      return;
    }
    if (beanFactory.getBeanNamesForType(DataSource.class, true, false).length == 0) {
      return;
    }
    List<String> missing = new java.util.ArrayList<>(
        DB_REQUIRED.stream().filter(this::isUnset).toList());
    if (usesS3()) {
      missing.addAll(S3_REQUIRED.stream().filter(this::isUnset).toList());
    }
    warnLingeringS3Credentials();
    if (missing.isEmpty()) {
      return;
    }
    throw new IllegalStateException("""
        dev 启动缺少凭据环境变量：%s
            这些值只放在 deploy/env/local.env（已 gitignore），而 IDEA 的运行配置不会自动读它。
            少一个就会被当成口令/密钥/存储地址原样用出去，报出来的是 Access denied、403 SignatureDoesNotMatch
            或一个看不懂的 URI 解析错，而不是配置问题，所以这里直接停住。两种补法任选其一：
              1) Run/Debug Configurations → PushboxServerApplication → Environment variables，
                 把上面这几个键填进去（值从 deploy/env/local.env 抄，不要提交任何含明文的文件）；
              2) 跑一次 powershell -File deploy/env/set-user-env.ps1 写进当前用户的环境变量，
                 然后重启 IDEA——所有运行配置都会继承它，明文不进任何工程文件。
            命令行跑则：set -a && . deploy/env/local.env && set +a && java -jar server/target/*.jar
            （只想要一个能收消息的本机服务端：PB_STORAGE_TYPE 保持默认的 local，就只需要 PB_DB_PASSWORD 一个键。）"""
        .formatted(missing));
  }

  /** 走 relaxed binding：环境变量 {@code PB_STORAGE_TYPE} 就是属性 {@code pb.storage.type}。 */
  private boolean usesS3() {
    return !"local".equalsIgnoreCase(environment.getProperty("pb.storage.type", "s3").trim());
  }

  /**
   * local 模式却带着 S3 凭据时告警。这条是"分档必填"引进来的反面风险：{@code PB_STORAGE_TYPE}
   * 忘了设成 {@code s3}，闸门不再报错、服务照常起，但对象落进了本地目录——跑绿的一整套用例
   * 测的其实是另一条链路。静默走 local 比启动失败更难发现，所以这里必须出声。
   *
   * <p>只报键名与落盘目录，一个值都不打（口令与密钥的值会进控制台，也会被 CI 采集）。
   */
  private void warnLingeringS3Credentials() {
    if (usesS3()) {
      return;
    }
    List<String> setButUnused = S3_REQUIRED.stream().filter(key -> !isUnset(key)).toList();
    if (setButUnused.isEmpty()) {
      return;
    }
    log.warn("""
        pb.storage.type=local：对象会落进 {}，但环境里还配着 {} —— 这几项在 local 模式一个字都不读。
            想验桶那条链路就带上 PB_STORAGE_TYPE=s3 重启（端点与桶名照 deploy/env/local.env.example 的 ◆ 段），
            确实只要本地存储就把这几行删掉，别留着一个"看着像配了 S3"的现场。""",
        environment.getProperty("pb.storage.local-path", "(未配置)"), setButUnused);
  }

  private boolean isUnset(String key) {
    String value = environment.getProperty(key);
    return value == null || value.isBlank();
  }
}
