package dev.pushbox.server.log;

import java.util.Locale;

/**
 * §13/§14 要求的日志脱敏机制本身：正文类字段在落进任何 appender 之前就被替换掉。
 *
 * <p>只靠"我们没写过打正文的语句"是不够的——那是一条约定，不是一个能力。这里给 Logback 的
 * {@code %msg} 与异常堆栈通路提供整词键名匹配，覆盖两种常见形态：
 * <ul>
 *   <li>logfmt：{@code body=值} —— 裸值吃到行尾或下一个 {@code key=} / {@code key:} 为止，
 *       因此含空格的中文正文会整段消失，而不是只遮第一个词；</li>
 *   <li>JSON：{@code "body":"值"} 与 {@code "body":{...}} —— 括号值按配平整段遮掉，
 *       嵌套结构不会被"遮了外层、漏了内层"。</li>
 * </ul>
 *
 * <p>方向性取舍：宁可多遮（把 {@code body:} 这类误判成敏感键）也不漏遮。
 */
public final class LogMasking {

  /** 值可能携带用户正文或凭据的键名。整词匹配、不区分大小写。 */
  private static final String[] SENSITIVE_KEYS = {
      "body", "content", "summary", "title", "text", "snippet",
      "secret", "channel_secret", "group_secret", "secret_hash",
      "token", "password", "recovery_code",
      "sender_avatar", "avatar_url", "url", "image_urls",
      "q", "query",
  };

  private static final String REPLACEMENT = "***";

  private LogMasking() {}

  public static String mask(String message) {
    if (message == null || message.isEmpty()) {
      return message;
    }
    String lower = message.toLowerCase(Locale.ROOT);
    boolean possible = false;
    for (String key : SENSITIVE_KEYS) {
      if (lower.contains(key)) {
        possible = true;
        break;
      }
    }
    if (!possible) {
      return message;
    }

    StringBuilder out = new StringBuilder(message.length());
    int i = 0;
    while (i < message.length()) {
      int valueStart = matchSensitive(message, i);
      if (valueStart < 0) {
        out.append(message.charAt(i));
        i++;
        continue;
      }
      out.append(message, i, valueStart);
      out.append(renderedValue(message, valueStart));
      i = scanValueEnd(message, valueStart);
    }
    return out.toString();
  }

  /** 带引号的值保留引号（`"body":"***"`），日志与 JSON 都还是可解析的形状。 */
  private static String renderedValue(String m, int valueStart) {
    char first = valueStart < m.length() ? m.charAt(valueStart) : '\0';
    if (first != '"' && first != '\'') {
      return REPLACEMENT;
    }
    return m.substring(valueStart, valueStart + 1) + REPLACEMENT + first;
  }

  /** 从 i 起是一个敏感键及其分隔符时，返回其值的起始下标；否则 -1。 */
  private static int matchSensitive(String m, int i) {
    if (!beforeIsBoundary(m, i)) {
      return -1;
    }
    int n = m.length();
    int p = i;
    boolean quoted = m.charAt(p) == '"';
    if (quoted) {
      p++;
    }
    int keyLength = sensitiveKeyLength(m, p);
    if (keyLength < 0) {
      return -1;
    }
    int after = p + keyLength;
    if (quoted) {
      int colon = skipSpaces(m, after + 1);
      if (after >= n || m.charAt(after) != '"' || colon >= n || m.charAt(colon) != ':') {
        return -1;
      }
      return skipSpaces(m, colon + 1);
    }
    if (after >= n) {
      return -1;
    }
    char separator = m.charAt(after);
    if (separator == '=' || separator == ':') {
      return skipSpaces(m, after + 1);
    }
    return -1;
  }

  /** 命中则返回键名长度；{@code seq}、{@code object_key} 这类因整词与分隔符条件而不会命中。 */
  private static int sensitiveKeyLength(String m, int p) {
    if (p >= m.length()) {
      return -1;
    }
    int best = -1;
    for (String key : SENSITIVE_KEYS) {
      int length = key.length();
      if (m.regionMatches(true, p, key, 0, length) && endsWord(m, p + length)) {
        // 取最长匹配，避免短键截断长键（secret / channel_secret 已由词边界挡住，这里防新加的键）
        best = Math.max(best, length);
      }
    }
    return best;
  }

  private static boolean endsWord(String m, int index) {
    return index >= m.length() || !isWordChar(m.charAt(index));
  }

  private static boolean beforeIsBoundary(String m, int i) {
    if (i == 0) {
      return true;
    }
    char before = m.charAt(i - 1);
    return before == ' ' || before == '\t' || before == '\n' || before == '\r'
        || before == '{' || before == '[' || before == '(' || before == ',' || before == '"'
        || before == '\'';
  }

  /** 值的结束下标（不含）。 */
  private static int scanValueEnd(String m, int valueStart) {
    int n = m.length();
    if (valueStart >= n) {
      return n;
    }
    char first = m.charAt(valueStart);
    if (first == '"' || first == '\'') {
      int i = valueStart + 1;
      while (i < n) {
        char c = m.charAt(i);
        if (c == '\\' && i + 1 < n) {
          i += 2;
          continue;
        }
        i++;
        if (c == first) {
          return i;
        }
      }
      return n;
    }
    if (first == '{' || first == '[') {
      return skipBalanced(m, valueStart);
    }
    // 裸值：吃到换行，或吃到下一个 key= / key: 之前的空白——含空格的整段正文都在值里
    int i = valueStart;
    while (i < n) {
      char c = m.charAt(i);
      if (c == '\n' || c == '\r') {
        break;
      }
      if ((c == ' ' || c == '\t') && startsAnotherField(m, i)) {
        break;
      }
      i++;
    }
    return i;
  }

  /** 空白串之后紧跟 {@code key=} / {@code "key":} 形态时，认为值到此结束。 */
  private static boolean startsAnotherField(String m, int spaceRunStart) {
    int afterSpaces = skipSpaces(m, spaceRunStart);
    int n = m.length();
    if (afterSpaces >= n) {
      return false;
    }
    boolean quotedKey = m.charAt(afterSpaces) == '"';
    int keyStart = quotedKey ? afterSpaces + 1 : afterSpaces;
    int keyLength = wordLength(m, keyStart);
    if (keyLength == 0 || !hasLetter(m, keyStart, keyLength)) {
      // 纯数字/纯符号的段（时间、IP:端口、金额）不是键名，否则裸值会在它们前面提前收尾
      return false;
    }
    int separator = keyStart + keyLength;
    if (quotedKey) {
      if (separator >= n || m.charAt(separator) != '"') {
        return false;
      }
      separator++;
    }
    if (separator >= n) {
      return false;
    }
    char c = m.charAt(separator);
    if (c == '=') {
      return true;
    }
    // 裸键后紧跟 :（YAML/无引号 JSON 写法）；要求冒号后是值起始符，避开 host:8080 这类结尾
    return c == ':' && (separator + 1 >= n || m.charAt(separator + 1) == ' '
        || m.charAt(separator + 1) == '\t' || m.charAt(separator + 1) == '"');
  }

  private static boolean hasLetter(String m, int start, int length) {
    for (int i = start; i < start + length; i++) {
      if (Character.isLetter(m.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  private static int wordLength(String m, int start) {
    int i = start;
    while (i < m.length() && isWordChar(m.charAt(i))) {
      i++;
    }
    return i - start;
  }

  /** 返回配平括号组的结束下标（不含闭括号后一位）；不配平就吃到行尾，宁多勿漏。 */
  private static int skipBalanced(String m, int open) {
    int depth = 0;
    boolean inString = false;
    char stringQuote = 0;
    for (int i = open; i < m.length(); i++) {
      char c = m.charAt(i);
      if (inString) {
        if (c == '\\') {
          i++;
        } else if (c == stringQuote) {
          inString = false;
        }
        continue;
      }
      if (c == '"' || c == '\'') {
        inString = true;
        stringQuote = c;
      } else if (c == '{' || c == '[') {
        depth++;
      } else if (c == '}' || c == ']') {
        depth--;
        if (depth == 0) {
          return i + 1;
        }
      }
    }
    int newline = m.indexOf('\n', open);
    return newline < 0 ? m.length() : newline;
  }

  private static int skipSpaces(String m, int i) {
    while (i < m.length() && (m.charAt(i) == ' ' || m.charAt(i) == '\t')) {
      i++;
    }
    return i;
  }

  private static boolean isWordChar(char c) {
    return c == '_' || c == '-' || c == '.' || Character.isLetterOrDigit(c);
  }
}
