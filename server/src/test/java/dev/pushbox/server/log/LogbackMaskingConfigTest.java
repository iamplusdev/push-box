package dev.pushbox.server.log;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.OutputStreamAppender;
import dev.pushbox.server.service.IngestService;
import dev.pushbox.server.web.GlobalExceptionHandler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * §13 那条判分的机器可读版本：日志脱敏不是"没人写过打正文的语句"，而是"打不出来"。
 *
 * <p>上下文只加载 yml 与 Logback，不带数据源与桶（所以每条 {@code mvn test} 都会跑它，
 * 不会因缺环境变量而跳过）——要验的是启动链上的日志配置本身，不是业务能跑通。
 *
 * <p>dev profile 只有 CONSOLE 一个 appender，文件 appender（{@code !dev}）无法在这里实例化，
 * 于是用 ④ 直接扫 {@code logback-spring.xml} 原文：所有 pattern 都必须引用同一条 PB_PATTERN。
 */
@SpringBootTest(classes = LogbackMaskingConfigTest.LoggingOnly.class)
class LogbackMaskingConfigTest {

  private static final String NEEDLE = "绝不该出现在日志里的正文needle";

  @Autowired
  private Environment env;

  @Configuration(proxyBeanMethods = false)
  static class LoggingOnly {
    // 故意是空类：@SpringBootTest 仍然会初始化 LoggingSystem 并绑定 application.yml，
    // 但没有 @EnableAutoConfiguration，就不会去连库和桶。
  }

  private static LoggerContext context() {
    return (LoggerContext) LoggerFactory.getILoggerFactory();
  }

  private static ch.qos.logback.classic.Logger root() {
    return context().getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
  }

  private static String consolePattern() {
    Appender<ILoggingEvent> console = root().getAppender("CONSOLE");
    assertThat(console).isInstanceOf(ConsoleAppender.class);
    return ((PatternLayoutEncoder) ((ConsoleAppender<ILoggingEvent>) console).getEncoder())
        .getPattern();
  }

  @Test
  @DisplayName("① root 上每个 appender 都用同一条含 %pbmsg/%pbex 的 pattern，没有裸 %msg")
  void everyRootAppenderFormatsThroughTheMaskingConverters() {
    String pattern = consolePattern();
    assertThat(pattern).contains("%pbmsg").contains("%pbex");
    // 负断言只查独立的 %msg / %ex，不查作为其他转换词一部分的子串
    assertThat(pattern).doesNotMatch(".*(?<!\\w)%msg(?!\\w).*");
    assertThat(pattern).doesNotMatch(".*(?<!\\w)%ex(?!\\w).*");
    var appenders = root().iteratorForAppenders();
    while (appenders.hasNext()) {
      OutputStreamAppender<ILoggingEvent> appender =
          (OutputStreamAppender<ILoggingEvent>) appenders.next();
      assertThat(((PatternLayoutEncoder) appender.getEncoder()).getPattern())
          .as(appender.getName()).isEqualTo(pattern);
    }
  }

  @Test
  @DisplayName("② 真实业务 logger 打出 body/summary 也会被遮：走的就是 shipped pattern")
  void shippedPatternStripsBodyFromBusinessLogs() {
    String out = capture(() -> LoggerFactory.getLogger(IngestService.class).info(
        "消息已受理 msg_id={} channel={} body={} summary={}", "m1", "10000007", NEEDLE, NEEDLE));

    assertThat(out).doesNotContain(NEEDLE);
    assertThat(out).contains("msg_id=m1").contains("channel=10000007")
        .contains("body=***").contains("summary=***");
  }

  @Test
  @DisplayName("③ 异常堆栈里的正文片段同样遮，而栈顶类型与 code 仍然可排障")
  void shippedPatternStripsBodyFromThrowables() {
    String out = capture(
        () -> LoggerFactory.getLogger(GlobalExceptionHandler.class).error("服务端错误 code=internal",
            new IllegalStateException("解析请求体失败: {\"body\":\"" + NEEDLE + "\"}")));

    assertThat(out).doesNotContain(NEEDLE);
    assertThat(out).contains("服务端错误 code=internal")
        .contains("IllegalStateException").contains("LogbackMaskingConfigTest");
  }

  @Test
  @DisplayName("④ 配置文件里不存在第二个 pattern：文件 appender 也只能拿到脱敏那条")
  void configFileDefinesOneMaskingPatternOnly() throws Exception {
    String xml;
    try (InputStream in = getClass().getResourceAsStream("/logback-spring.xml")) {
      assertThat(in).isNotNull();
      xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    // %pbmsg/%pbex 是允许的，但独立的 %msg / %ex / %throwable / %nopex 不允许出现
    assertThat(xml).doesNotMatch(".*(?<!\\w)%msg(?!\\w).*");
    assertThat(xml).doesNotMatch(".*(?<!\\w)%ex(?!\\w).*");
    assertThat(xml).doesNotContain("%throwable").doesNotContain("%nopex");
    assertThat(xml).contains("<pattern>${PB_PATTERN}</pattern>");
    assertThat((long) xml.split("<pattern>", -1).length - 1)
        .as("appender 数量")
        .isEqualTo((long) xml.split("<pattern>\\$\\{PB_PATTERN}</pattern>", -1).length - 1);
    // dev 之外多一个滚动文件 appender，两者同一条 pattern
    assertThat(xml).contains("RollingFileAppender").contains("conversionRule");
  }

  @Test
  @DisplayName("⑤ 框架 logger 钉在 INFO 及以上，访问日志默认关且备用 pattern 不含查询串")
  void frameworkLoggersAndAccessLogCannotLeak() {
    for (String name : new String[] {"com.mybatisflex", "org.springframework.jdbc.core",
        "org.springframework.web", "org.springframework.http", "software.amazon.awssdk"}) {
      assertThat(context().getLogger(name).getEffectiveLevel().levelInt)
          .as(name).isGreaterThanOrEqualTo(Level.INFO.levelInt);
    }
    assertThat(env.getProperty("server.tomcat.accesslog.enabled")).isEqualTo("false");
    assertThat(env.getProperty("server.tomcat.accesslog.pattern"))
        .doesNotContain("%q").doesNotContain("%r");
    // 有人把 logging.pattern.console 加回来，就等于绕过 %pbmsg 换回裸 %msg
    assertThat(env.getProperty("logging.pattern.console")).isNull();
  }

  /** 用 shipped pattern 起一个内存 appender 挂到 root，跑一次真实日志调用并取回格式化结果。 */
  private static String capture(Runnable logging) {
    LoggerContext ctx = context();
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(ctx);
    encoder.setPattern(consolePattern());
    encoder.start();
    OutputStreamAppender<ILoggingEvent> appender = new OutputStreamAppender<>();
    appender.setContext(ctx);
    appender.setEncoder(encoder);
    appender.setOutputStream(sink);
    appender.start();
    root().addAppender(appender);
    try {
      logging.run();
    } finally {
      root().detachAppender(appender);
      appender.stop();
    }
    return sink.toString(StandardCharsets.UTF_8);
  }

  /** 只为了让 ④ 的原文扫描读的是生效的那份资源：与 classpath 上的 /logback-spring.xml 同源。 */
  @Test
  @DisplayName("⑥ 主资源目录里的 logback-spring.xml 就是 classpath 上生效的那份")
  void activeConfigIsTheOneInMainResources() throws Exception {
    Path onDisk = Path.of("src/main/resources/logback-spring.xml");
    assertThat(onDisk).exists();
    String fromClasspath = new String(getClass().getResourceAsStream("/logback-spring.xml")
        .readAllBytes(), StandardCharsets.UTF_8);
    assertThat(Files.readString(onDisk)).isEqualTo(fromClasspath);
  }
}
