package dev.pushbox.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 定时推送的正文占位符：语法、时区归属、以及"认不出就原样留着"这条底线。 */
class TemplatesTest {

  /** 2026-10-24 04:30:05Z = 上海 12:30:05，那天是周六。 */
  private static final Instant NOW = Instant.parse("2026-10-24T04:30:05Z");
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
  private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

  @Test
  void rendersAliasesInGivenZone() {
    assertEquals("2026-10-24 12:30:05", render("${now}"));
    assertEquals("2026-10-24", render("${date}"));
    assertEquals("12:30", render("${time}"));
    assertEquals("星期六", render("${weekday}"));
    assertEquals(Long.toString(NOW.toEpochMilli()), render("${timestamp}"));
    // 同一时刻换个时区，时间跟着变、日期和星期也跟着走
    assertEquals("2026-10-24 13:30:05", Templates.render("${now}", TOKYO, NOW, Map.of()));
    assertEquals("星期日", Templates.render("${weekday}", TOKYO, Instant.parse("2026-10-25T04:30:05Z"),
        Map.of()));
  }

  @Test
  void rendersCustomTimeFormat() {
    assertEquals("2026/10/24 12:30", render("${now:yyyy/MM/dd HH:mm}"));
    // 参数里的冒号属于格式串本身，只按第一个冒号切分
    assertEquals("12:30:05", render("${now:HH:mm:ss}"));
    // 大小写不敏感的是变量名，格式串原样交给 DateTimeFormatter
    assertEquals("2026", render("${NOW:yyyy}"));
  }

  @Test
  void timezoneTokenNeverOverridesTheGivenZone() {
    // 定时任务：任务时区压过正文里写的时区，但这一处仍要输出任务时区本身
    assertEquals("Asia/Shanghai", render("${timezone}"));
    assertEquals("Asia/Shanghai", render("${timezone:Asia/Tokyo}"));
    assertEquals("Asia/Tokyo", Templates.render("${timezone:Asia/Shanghai}", TOKYO, NOW, Map.of()));
  }

  @Test
  void resolveZoneReadsTheFirstValidDirective() {
    assertEquals(SHANGHAI, Templates.resolveZone(null, SHANGHAI));
    assertEquals(SHANGHAI, Templates.resolveZone("没有变量的正文", SHANGHAI));
    assertEquals(ZoneId.of("UTC"), Templates.resolveZone("时区：${timezone:UTC}", SHANGHAI));
    // 第一处出现即定案，后面的不再看
    assertEquals(ZoneId.of("UTC"),
        Templates.resolveZone("${timezone:UTC} 与 ${timezone:Asia/Tokyo}", SHANGHAI));
    // 写错区域等于没写：回落而不是 400，正文照发
    assertEquals(SHANGHAI, Templates.resolveZone("${timezone:Mars/Olympus}", SHANGHAI));
  }

  @Test
  void unknownAndMalformedTokensStayLiteral() {
    assertEquals("${nope}", render("${nope}"));
    assertEquals("${}", render("${}"));
    assertEquals("${now", render("${now"));
    // 没闭合的引号才是真正非法的格式串；中文在 DateTimeFormatter 眼里是普通模式字母，不算错
    assertEquals("前缀 ${now:yyyy'} 后缀", render("前缀 ${now:yyyy'} 后缀"));
    // ${date} 不吃参数：带上参数就退回字面量，而不是悄悄忽略参数
    assertEquals("${date:yyyy}", render("${date:yyyy}"));
    // 变量表里有名字但值为空，也等同于"这次没有这个值"
    assertEquals("${task_name}",
        Templates.render("${task_name}", SHANGHAI, NOW, Map.of("task_name", "")));
  }

  @Test
  void substitutesExtraVariables() {
    Map<String, String> vars = Map.of(
        "channel_no", "32941977",
        "task_name", "每日晨报",
        "scheduled_at", "2026-10-24 12:30:00");
    assertEquals("【每日晨报】32941977 于 2026-10-24 12:30:00 触发",
        Templates.render("【${task_name}】${channel_no} 于 ${scheduled_at} 触发", SHANGHAI, NOW, vars));
  }

  @Test
  void textWithoutTokensIsReturnedUnchanged() {
    String plain = "今日值班：张三\n没有占位符 $ dollar { brace } $${x";
    assertEquals(plain, render(plain));
  }

  private static String render(String text) {
    return Templates.render(text, SHANGHAI, NOW, Map.of());
  }
}
