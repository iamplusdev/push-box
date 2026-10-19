package dev.pushbox.server.config;

import dev.pushbox.server.media.MediaFetchService;
import dev.pushbox.server.push.PushRegistry;
import dev.pushbox.server.web.InternalAccessFilter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.io.File;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** §5.2 观测：/healthz 与 /actuator/** 收成内网 only，并把已经存在的运行状态绑成指标。 */
@Configuration
public class ObservabilityConfig {

  private static final Logger log = LoggerFactory.getLogger(ObservabilityConfig.class);

  /**
   * 白名单在这里解析一次：CIDR 写错就当场起不来，而不是等到有人拉不到指标才发现"原来那段没放行"。
   * 放行了什么段必须打进日志——运维端点能不能被别人够到，只有这一行说得准。
   */
  @Bean
  FilterRegistrationBean<InternalAccessFilter> internalAccessFilter(PbProperties props,
      Environment env) {
    List<InternalAccessFilter.Cidr> cidrs =
        InternalAccessFilter.parseCidrs(props.getSecurity().getInternalCidrs());
    log.info("运维端点（/healthz、/actuator/**）准入：默认仅环回，额外放行 {}",
        cidrs.isEmpty() ? "无" : cidrs);
    if (!cidrs.isEmpty()) {
      // 反代在前的话，getRemoteAddr 是 Caddy 的地址而不是客户端的地址：
      // 放行一个网段等于放行这个网段里的每一个进程，而 /actuator 这一侧没有鉴权。
      String strategy = env.getProperty("server.forward-headers-strategy", "none");
      if (!"framework".equals(strategy) && !"native".equals(strategy)) {
        log.warn("forward-headers-strategy={}：服务端不看 X-Forwarded-For，准入只认 getRemoteAddr()。"
            + "有 Caddy 反代在前时那是代理地址（不是客户端），按 IP 限流/锁定（如管理台登录）依赖 Caddy "
            + "覆盖 X-Forwarded-For；运维端点则对上面放行的整个网段可见，请确认这些段里没有不可信容器。", strategy);
      }
    }
    FilterRegistrationBean<InternalAccessFilter> registration =
        new FilterRegistrationBean<>(new InternalAccessFilter(cidrs));
    registration.setUrlPatterns(List.of("/healthz", "/actuator/*"));
    return registration;
  }

  /**
   * 只读现有状态，不为此往业务里塞计数器：在线设备数掉斜线就是掉线，队列长度涨高就是积压。
   * 指标名用点号，Prometheus 渲染成 pushbox_ws_online / pushbox_media_queue_depth。
   */
  @Bean
  MeterBinder pushboxGauges(PushRegistry registry, MediaFetchService fetcher, PbProperties props) {
    return binder -> {
      Gauge.builder("pushbox.ws.online", registry, PushRegistry::onlineCount)
          .description("当前持有加密长连接的设备数").register(binder);
      Gauge.builder("pushbox.media.queue.depth", fetcher, MediaFetchService::queueDepth)
          .description("抓取线程池排队中的任务数").register(binder);

      // 本地存储模式下暴露磁盘空间使用率指标
      if ("local".equalsIgnoreCase(props.getStorage().getType())) {
        String localPath = props.getStorage().getLocalPath();
        File storageDir = new File(localPath);
        Gauge.builder("pushbox.storage.disk.usage.percent", storageDir, dir -> {
          long total = dir.getTotalSpace();
          long free = dir.getFreeSpace();
          if (total <= 0) return 0.0;
          return Math.round((1.0 - (double) free / total) * 10000.0) / 100.0; // 保留两位小数
        })
        .description("本地存储目录磁盘使用率百分比")
        .baseUnit("percent")
        .register(binder);

        Gauge.builder("pushbox.storage.disk.free.bytes", storageDir, File::getFreeSpace)
        .description("本地存储目录可用字节数")
        .baseUnit("bytes")
        .register(binder);
      }
    };
  }
}
