package dev.pushbox.server.setup;

import dev.pushbox.server.repo.PbStore;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动后把这份配置同步回两个地方：文件本身（补上自动生成的 JWT 签名钥）与库里的
 * {@code app_state.runtime_config_v1}。
 *
 * <p>库里那一份的用途是"重装时找回配置"：容器被删、{@code /app/data} 忘了挂，
 * 只要 MySQL 还在，安装页就能从库里把上一次的配置读回来，不用重新填一遍端点与口令。
 * 它不是配置的来源——来源永远是文件，库那一份是副本，两边不一致时以文件为准（重启即同步）。
 *
 * <p>写库失败只出声不拦启动：库连不上的话 Flyway 早就把进程停了，走到这里说明库是好的，
 * 剩下的失败只有权限不足一类，那属于运维要看见但不该让服务起不来的事。
 */
@Component
@Order(0)
public class RuntimeConfigSync implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(RuntimeConfigSync.class);

  /** app_state 里的键。V1 的 state_key 是 VARCHAR(32)，别改长。 */
  static final String STATE_KEY = "runtime_config_v1";

  private final PbStore store;
  private final String configFile;

  public RuntimeConfigSync(PbStore store,
      @Value("${pb.config-file:}") String configFile) {
    this.store = store;
    this.configFile = configFile;
  }

  @Override
  public void run(ApplicationArguments args) {
    Path path = Paths.get(configFile.isBlank() ? RuntimeConfig.RELATIVE_PATH : configFile);
    RuntimeConfig config = RuntimeConfig.loadOrDefault(path);
    if (config.normalize()) {
      try {
        config.save(path);
        log.info("已把自动生成的管理台 JWT 签名钥回写 {}", path);
      } catch (RuntimeException e) {
        log.warn("配置文件回写失败（{}）：本次的管理台签名钥只在内存里，重启后已发的 token 会全部失效",
            e.getMessage());
      }
    }
    try {
      store.putState(STATE_KEY, config.toJson(), System.currentTimeMillis());
    } catch (RuntimeException e) {
      log.warn("配置副本没能写进 app_state（{}）：重装时无法从库里找回配置，其余功能不受影响",
          e.getMessage());
      return;
    }
    log.info("配置已就位：来源={} 端口={} 库={}:{} 存储={} 加密={} 管理台={}",
        path, config.getPort(), config.getDatabase().getHost(), config.getDatabase().getPort(),
        config.getStorage().isS3() ? "对象存储 " + config.getStorage().getBucket()
            : "本地目录 " + config.getStorage().getLocalPath(),
        config.getStorage().isEncrypted() ? "开" : "关",
        isBlank(config.getAdmin().getPassword()) ? "未启用" : "已启用");
  }

  private static boolean isBlank(String v) {
    return v == null || v.isBlank();
  }
}
