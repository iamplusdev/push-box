package dev.pushbox.server.config;

import java.util.List;
import javax.sql.DataSource;
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
 */
@Component
public class DevCredentialsGuard implements BeanFactoryPostProcessor, EnvironmentAware {

  private static final List<String> REQUIRED =
      List.of("PB_DB_PASSWORD", "PB_S3_ENDPOINT", "PB_S3_ACCESS_KEY", "PB_S3_SECRET_KEY");

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
    List<String> missing = REQUIRED.stream().filter(this::isUnset).toList();
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
            命令行跑则：set -a && . deploy/env/local.env && set +a && java -jar server/target/*.jar"""
        .formatted(missing));
  }

  private boolean isUnset(String key) {
    String value = environment.getProperty(key);
    return value == null || value.isBlank();
  }
}
