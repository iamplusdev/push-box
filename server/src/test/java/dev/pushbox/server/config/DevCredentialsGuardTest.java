package dev.pushbox.server.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * dev 凭据必填检查的用例。这里不起整个应用：只起一个 GenericApplicationContext，
 * 按需要注册/不注册 DataSource 定义——拦不拦的判据就这两维（profile 是 dev、这次运行真会连库）。
 *
 * <p>另外钉住"报错只报键名、绝不带值"，因为这条消息会整段进控制台与 CI 日志。
 */
class DevCredentialsGuardTest {

  private static final String SENTINEL = "绝不该出现在报错里的口令SENTINEL";

  @Test
  @DisplayName("① dev + 会连库 + 缺口令：当场停住，并且点名缺哪一个")
  void missingCredentialStopsBeforeFlywayConnects() {
    assertThatThrownBy(() -> refresh("dev", Map.of("PB_S3_ENDPOINT", SENTINEL,
        "PB_S3_ACCESS_KEY", SENTINEL, "PB_S3_SECRET_KEY", SENTINEL), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PB_DB_PASSWORD")
        .hasMessageContaining("deploy/env/local.env")
        // 只报键名：口令与桶密钥的值一个字都不能进这条消息
        .hasMessageNotContaining(SENTINEL);
  }

  @Test
  @DisplayName("② 缺几个列几个，并给出让 IDEA 继承环境的那条路")
  void listsEveryMissingKey() {
    assertThatThrownBy(() -> refresh("dev", Map.of(), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PB_DB_PASSWORD")
        .hasMessageContaining("PB_S3_ENDPOINT")
        .hasMessageContaining("PB_S3_ACCESS_KEY")
        .hasMessageContaining("PB_S3_SECRET_KEY")
        .hasMessageContaining("set-user-env.ps1");
  }

  @Test
  @DisplayName("③ 配齐就放行：主钥是可选项（明文形态下它就是空的），不能当成必填拦下来")
  void completeCredentialsPassAndMasterKeyStaysOptional() {
    Map<String, String> all = Map.of("PB_DB_PASSWORD", SENTINEL, "PB_S3_ENDPOINT", SENTINEL,
        "PB_S3_ACCESS_KEY", SENTINEL, "PB_S3_SECRET_KEY", SENTINEL);
    assertThatCode(() -> refresh("dev", all, true)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("④ 空白值等同于没配：source 了文件但那一行写成 PB_DB_PASSWORD=\"\" 也是连不上")
  void blankValueCountsAsMissing() {
    assertThatThrownBy(() -> refresh("dev", Map.of("PB_DB_PASSWORD", "   ", "PB_S3_ENDPOINT", SENTINEL,
        "PB_S3_ACCESS_KEY", SENTINEL, "PB_S3_SECRET_KEY", SENTINEL), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PB_DB_PASSWORD");
  }

  @Test
  @DisplayName("⑤ prod 不归这里管：容器里必填项由 compose 的 ${VAR:?} 与 S3Config 守")
  void prodIsLeftToTheContainerGuards() {
    assertThatCode(() -> refresh("prod", Map.of(), true)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("⑥ 不建 DataSource 的上下文不受打断：LogbackMaskingConfigTest 那种只加载 yml+Logback 的必须照跑")
  void contextWithoutDataSourceIsNotBlocked() {
    assertThatCode(() -> refresh("dev", Map.of(), false)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("⑦ PB_STORAGE_TYPE=local 时三件 S3 键不再是必填：本机只装一个 MySQL 就要能起来")
  void localStorageOnlyNeedsTheDbPassword() {
    assertThatCode(() -> refresh("dev",
        Map.of("PB_DB_PASSWORD", SENTINEL, "pb.storage.type", "local"), true))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("⑧ 同上但连口令都没给：只点 PB_DB_PASSWORD 一个名，不许把用不上的 PB_S3_* 一起报出来")
  void localStorageFailureNamesOnlyTheRealGap() {
    assertThatThrownBy(() -> refresh("dev", Map.of("pb.storage.type", "local"), true))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PB_DB_PASSWORD")
        .hasMessageNotContaining("PB_S3_ENDPOINT");
  }

  private static void refresh(String profile, Map<String, String> props, boolean withDataSource) {
    GenericApplicationContext ctx = new GenericApplicationContext();
    // 摘掉进程环境与系统属性：mvn test 常常是在 source 过 local.env 的终端里跑的，
    // 留着它们的话"缺凭据"这几条用例根本造不出缺东西的状态。
    ctx.getEnvironment().getPropertySources().remove("systemEnvironment");
    ctx.getEnvironment().getPropertySources().remove("systemProperties");
    ctx.getEnvironment().setActiveProfiles(profile);
    if (!props.isEmpty()) {
      Map<String, Object> typed = new LinkedHashMap<>(props);
      ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", typed));
    }
    if (withDataSource) {
      // 无参 HikariDataSource 不会在构造期连库，这里只是让"这次运行会建数据源"这件事成立
      ctx.registerBean(DataSource.class, () -> new HikariDataSource());
    }
    ctx.registerBean(DevCredentialsGuard.class);
    try {
      ctx.refresh();
    } finally {
      ctx.close();
    }
  }
}
