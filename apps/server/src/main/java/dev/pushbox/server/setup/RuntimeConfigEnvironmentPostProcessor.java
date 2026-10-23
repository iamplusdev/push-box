package dev.pushbox.server.setup;

import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 把 {@code data/config/pushbox.json} 翻译成 Spring 属性。
 *
 * <p>优先级刻意排在命令行、{@code -D} 系统属性与环境变量之后、yml 之前：探针与体检要用
 * {@code --server.port=8081}、{@code --pb.ws.replay-batch=5} 这类临时覆盖，
 * 它们必须赢过配置文件，否则"临时改一个值"就变成了"改文件再重启"。
 *
 * <p>文件不存在时这里什么都不做：{@code PushboxServerApplication.main} 会先起安装页，
 * 让部署者在浏览器里把这份文件填出来，而不是让 Spring 拿着半套配置起一半。
 */
public class RuntimeConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  /** 属性源名字。要临时看生效值就用它认。 */
  public static final String PROPERTY_SOURCE_NAME = "pushboxRuntimeConfig";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
    Path path = RuntimeConfig.locate(environment.getProperty(RuntimeConfig.CONFIG_FILE_PROPERTY));
    if (!RuntimeConfig.existsAt(path)) {
      return;
    }
    RuntimeConfig config = RuntimeConfig.load(path);
    config.normalize();
    Map<String, Object> props = config.toSpringProperties();
    props.put("pb.config-file", path.toString());
    MapPropertySource source = new MapPropertySource(PROPERTY_SOURCE_NAME, props);
    String after = lowestOf(environment, "systemEnvironment", "systemProperties", "commandLineArgs");
    if (after == null) {
      environment.getPropertySources().addFirst(source);
    } else {
      environment.getPropertySources().addAfter(after, source);
    }
  }

  /**
   * 我们要插在它后面：环境变量 > 本文件 > yml。Spring 的标准顺序是
   * commandLineArgs → systemProperties → systemEnvironment，所以取这三者中最靠后的那一个
   * （即最先命中的 systemEnvironment），插到它后面就正好压住 yml、又不挡住 -D 与命令行。
   */
  private static String lowestOf(ConfigurableEnvironment environment, String... names) {
    for (String name : names) {
      if (environment.getPropertySources().contains(name)) {
        return name;
      }
    }
    return null;
  }

  @Override
  public int getOrder() {
    // 比 ConfigData（yml 加载）晚，所以这里读到的 pb.config-file 已经含 yml 里的值；
    // 而我们 addAfter 的位置只跟属性源顺序有关，与加载顺序无关。
    return Ordered.LOWEST_PRECEDENCE;
  }
}
