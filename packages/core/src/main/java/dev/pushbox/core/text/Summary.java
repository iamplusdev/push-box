package dev.pushbox.core.text;

/** §5.1：服务端入库时同步生成 summary（去 Markdown 语法 + 截断 60 字符），客户端收到即可直接发通知。 */
public final class Summary {
  public static final int DEFAULT_MAX_CHARS = 60;

  private Summary() {}

  public static String of(String title, String content) {
    return of(title, content, DEFAULT_MAX_CHARS);
  }

  public static String of(String title, String content, int maxChars) {
    String source = (title != null && !title.isBlank()) ? title : content;
    if (source == null) {
      return "";
    }
    String plain = stripMarkdown(source);
    return truncate(plain, maxChars);
  }

  /** 只做"读起来像正文"的最低限度清洗，不追求完整 CommonMark 实现。 */
  public static String stripMarkdown(String markdown) {
    String s = markdown;
    s = s.replaceAll("(?s)```.*?```", " ");              // 代码块整体丢弃
    s = s.replaceAll("`([^`]*)`", "$1");                 // 行内代码
    s = s.replaceAll("!\\[[^]]*]\\([^)]*\\)", " ");       // 图片： alt 文本对通知无用
    s = s.replaceAll("\\[([^]]*)]\\(([^)]*)\\)", "$1 $2"); // 链接：保留锚文本 + URL，"查看"类通知需要能读过去
    s = s.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");     // 标题
    s = s.replaceAll("(?m)^\\s{0,3}>\\s?", "");          // 引用
    s = s.replaceAll("(?m)^\\s*[-*+]\\s+", "");           // 列表符号
    s = s.replaceAll("(?m)^\\s*\\d+\\.\\s+", "");
    s = s.replaceAll("(?m)^\\s*(---|\\*\\*\\*|___)\\s*$", " ");
    s = s.replaceAll("[*_]{1,3}([^*_\\s][^*_]*?)[*_]{1,3}", "$1"); // 强调
    s = s.replaceAll("~~([^~]+)~~", "$1");
    s = s.replaceAll("[\\r\\n\\t]+", " ");
    s = s.replaceAll("\\s{2,}", " ");
    return s.trim();
  }

  /** 按码点截断，避免把 emoji / 代理对切半。 */
  public static String truncate(String text, int maxChars) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    int count = text.codePointCount(0, text.length());
    if (count <= maxChars) {
      return text;
    }
    int end = text.offsetByCodePoints(0, Math.max(maxChars, 0));
    return text.substring(0, end);
  }
}
