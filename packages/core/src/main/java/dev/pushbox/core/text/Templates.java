package dev.pushbox.core.text;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * 正文里的 {@code ${...}} 占位符。语法只有一层：{@code ${名字}} 与 {@code ${名字:参数}}，
 * 没有条件、循环、嵌套和转义——定时推送要的是"把当前时间写进正文"，不是一个模板语言。
 *
 * <p>认不出的变量、写错的格式串、没有闭合的 {@code ${} 一律**原样留在正文里**：
 * 一个拼错的占位符不该让一条消息发不出去，也不该被悄悄替换成空串。
 *
 * <p>时区由调用方定：定时任务传任务自己的时区，第三方入站先走 {@link #resolveZone}。
 * {@link #render} 拿到哪个时区就用哪个，{@code ${timezone:xx}} 里的 xx 不参与计算——
 * "任务时区永远压过正文里写的时区"这条规则就落在这一处，而不是在每个调用点各判断一次。
 */
public final class Templates {

  /** 没有任何时区线索时的兜底；定时任务的建表默认值也是这一条，两处必须是同一个名字。 */
  public static final String DEFAULT_ZONE = "Asia/Shanghai";

  private static final DateTimeFormatter DEFAULT_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
  private static final String[] WEEKDAYS =
      {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
  private static final String TIMEZONE_PREFIX = "timezone:";

  private Templates() {}

  /**
   * 扫描正文里第一个 {@code ${timezone:区域}}，把它当作渲染时区。没有、或者区域写法非法，
   * 都回落到 {@code fallback}——第一处出现即定案，后面的不再看。
   */
  public static ZoneId resolveZone(String text, ZoneId fallback) {
    if (text == null) {
      return fallback;
    }
    int cursor = 0;
    while (true) {
      int start = text.indexOf("${", cursor);
      if (start < 0) {
        return fallback;
      }
      int end = text.indexOf('}', start + 2);
      if (end < 0) {
        return fallback;
      }
      String token = text.substring(start + 2, end);
      if (token.regionMatches(true, 0, TIMEZONE_PREFIX, 0, TIMEZONE_PREFIX.length())) {
        String id = token.substring(TIMEZONE_PREFIX.length()).trim();
        try {
          return ZoneId.of(id);
        } catch (DateTimeException | IllegalArgumentException e) {
          return fallback;
        }
      }
      cursor = end + 1;
    }
  }

  /**
   * @param text 原正文；没有 {@code ${} 时原样返回
   * @param zone 渲染时区，调用方说了算
   * @param now  渲染时刻，由调用方给，方便测试与"同一批触发共享一个时刻"
   * @param vars 额外变量（{@code channel_no} 这类）；键必须小写
   */
  public static String render(String text, ZoneId zone, Instant now, Map<String, String> vars) {
    if (text == null || text.indexOf("${") < 0) {
      return text;
    }
    ZonedDateTime at = now.atZone(zone);
    StringBuilder out = new StringBuilder(text.length() + 32);
    int cursor = 0;
    while (true) {
      int start = text.indexOf("${", cursor);
      if (start < 0) {
        out.append(text, cursor, text.length());
        return out.toString();
      }
      int end = text.indexOf('}', start + 2);
      if (end < 0) {
        out.append(text, cursor, text.length());
        return out.toString();
      }
      out.append(text, cursor, start);
      String token = text.substring(start + 2, end);
      String value = evaluate(token, at, zone, vars);
      if (value == null) {
        out.append(text, start, end + 1);
      } else {
        out.append(value);
      }
      cursor = end + 1;
    }
  }

  /** @return 替换值；null 表示"不动它"，由调用方原样输出。 */
  private static String evaluate(String token, ZonedDateTime at, ZoneId zone,
      Map<String, String> vars) {
    if (token == null || token.isBlank()) {
      return null;
    }
    int colon = token.indexOf(':');
    String name = (colon < 0 ? token : token.substring(0, colon)).trim().toLowerCase(Locale.ROOT);
    String arg = colon < 0 ? null : token.substring(colon + 1);
    switch (name) {
      case "now":
        return arg == null ? at.format(DEFAULT_TIME) : format(at, arg);
      case "date":
        return arg == null ? at.format(DATE) : null;
      case "time":
        return arg == null ? at.format(CLOCK) : null;
      case "weekday":
        return arg == null ? WEEKDAYS[at.getDayOfWeek().getValue() - 1] : null;
      case "timestamp":
        return arg == null ? Long.toString(at.toInstant().toEpochMilli()) : null;
      // 参数写什么都不算数：时区已经在 render 的入参里定死了
      case "timezone":
        return zone.getId();
      default:
        if (colon >= 0) {
          return null;
        }
        String value = vars == null ? null : vars.get(name);
        return (value == null || value.isEmpty()) ? null : value;
    }
  }

  private static String format(ZonedDateTime at, String pattern) {
    if (pattern.isBlank()) {
      return null;
    }
    try {
      return at.format(DateTimeFormatter.ofPattern(pattern));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
