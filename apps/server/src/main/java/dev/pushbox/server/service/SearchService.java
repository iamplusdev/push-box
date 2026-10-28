package dev.pushbox.server.service;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.api.SearchPage;
import dev.pushbox.core.model.MsgType;
import dev.pushbox.core.text.Summary;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * §5.2 D19 服务端全文搜索：搜索词一律参数绑定，结果只在请求设备的 group 内。
 */
@Service
public class SearchService {

  /** 搜索词上限：MATCH 与 LIKE 的代价都随词长波动，给个明确的 400 比让它扫全表好。 */
  static final int MAX_QUERY_CHARS = 100;
  static final int SNIPPET_CHARS = 120;
  /** §3.2 建库时定的 ngram_token_size=2，短于它的词 MATCH 一定召不回，只能 LIKE。 */
  static final int NGRAM_TOKEN_SIZE = 2;

  private final PbStore store;
  private final PbProperties config;

  public SearchService(PbStore store, PbProperties config) {
    this.store = store;
    this.config = config;
  }

  public SearchPage search(String groupId, String rawQuery, String channelNo, String senderId,
      String rawType, long since, int requestedLimit) {
    return search(groupId, rawQuery, channelNo, senderId, rawType, since, requestedLimit, null);
  }

  /**
   * 带订阅过滤的搜索：{@code scope} 为 null 表示不过滤；空集合表示开了过滤但没接入任何频道，一条都不给。
   */
  public SearchPage search(String groupId, String rawQuery, String channelNo, String senderId,
      String rawType, long since, int requestedLimit, java.util.Collection<String> scope) {
    String query = normalizeQuery(rawQuery);
    String type = (rawType == null || rawType.isBlank()) ? null : MsgType.fromWire(rawType).wire();
    int limit = clampLimit(requestedLimit);
    // 多取一条只为判断 has_more，本身不进结果
    int fetch = limit + 1;

    List<Row> rows = null;
    if (config.getSearch().isFulltext() && isNgramSearchable(query)) {
      rows = store.searchFulltext(groupId, query, channelNo, senderId, type, since, fetch, scope);
      // ngram 索引缺失或被分词切碎都表现为"零命中"而不是报错，而搜索给假阴性比慢一点更糟，
      // 所以零命中时再用 LIKE 兜一次。
      if (rows.isEmpty()) {
        rows = null;
      }
    }
    if (rows == null) {
      rows = store.searchLike(groupId, likePattern(query), channelNo, senderId, type, since, fetch, scope);
    }

    boolean hasMore = rows.size() > limit;
    List<SearchPage.SearchHit> hits = new ArrayList<>();
    for (int i = 0; i < Math.min(limit, rows.size()); i++) {
      hits.add(toHit(rows.get(i), query));
    }
    return new SearchPage(hits, hasMore);
  }

  String normalizeQuery(String rawQuery) {
    if (rawQuery == null || rawQuery.isBlank()) {
      throw ApiException.invalidParam("q 不能为空");
    }
    String query = rawQuery.trim();
    int chars = query.codePointCount(0, query.length());
    if (chars < Math.max(config.getSearch().getMinQueryLen(), 1)) {
      throw ApiException.invalidParam("q 至少 " + Math.max(config.getSearch().getMinQueryLen(), 1)
          + " 个字符");
    }
    if (chars > MAX_QUERY_CHARS) {
      throw ApiException.invalidParam("q 最长 " + MAX_QUERY_CHARS + " 字符");
    }
    return query;
  }

  public static boolean isNgramSearchable(String query) {
    return query.codePointCount(0, query.length()) >= NGRAM_TOKEN_SIZE;
  }

  /**
   * MATCH…AGAINST 走 NATURAL LANGUAGE MODE，运算符（+ - ~ * 等）只有 BOOLEAN MODE 才认，
   * 唯独双引号有语法意义（短语引号）：不对称的引号会让整句 AGAINST 直接报语法错。
   * 换成空格——它是分词边界，词本身一个都不少；零命中时反正还有 LIKE 兜底。
   */
  public static String matchQuery(String query) {
    return query.replace('"', ' ');
  }

  /** LIKE 的三个通配符都得转义，否则用户输入一个 % 就是"匹配一切"。 */
  public static String likePattern(String query) {
    StringBuilder sb = new StringBuilder(query.length() + 3);
    sb.append('%');
    for (int i = 0; i < query.length(); i++) {
      char c = query.charAt(i);
      if (c == '\\' || c == '%' || c == '_') {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.append('%').toString();
  }

  /**
   * 命中片段：在三列纯文本里找第一个命中的词，围绕它开一个窗口。
   * 一次最多回 200 条，直接给正文会把响应撑到几百 KB，而列表只需要一两行。
   */
  static String snippet(String title, String body, String summary, String query, int maxChars) {
    List<String> terms = terms(query);
    List<String> candidates = new ArrayList<>(3);
    addPlain(candidates, title);
    addPlain(candidates, body);
    addPlain(candidates, summary);
    if (candidates.isEmpty()) {
      return "";
    }
    for (String text : candidates) {
      int at = firstHit(text, terms);
      if (at >= 0) {
        return window(text, at, maxChars);
      }
    }
    // MATCH 允许命中在分词后的变形上（大小写、标点），此时至少给个开头
    return window(candidates.get(0), 0, maxChars);
  }

  private static void addPlain(List<String> out, String raw) {
    if (raw == null || raw.isBlank()) {
      return;
    }
    String plain = Summary.stripMarkdown(raw);
    if (!plain.isBlank()) {
      out.add(plain);
    }
  }

  /** 整词优先，再退到空格切出的各个词；单字查询本身就只有一项。 */
  private static List<String> terms(String query) {
    List<String> terms = new ArrayList<>();
    String lower = query.toLowerCase(Locale.ROOT);
    terms.add(lower);
    for (String part : lower.split("\\s+")) {
      if (!part.isEmpty() && !terms.contains(part)) {
        terms.add(part);
      }
    }
    return terms;
  }

  private static int firstHit(String text, List<String> terms) {
    String lower = text.toLowerCase(Locale.ROOT);
    int best = -1;
    for (String term : terms) {
      int at = lower.indexOf(term);
      if (at >= 0 && (best < 0 || at < best)) {
        best = at;
      }
    }
    return best;
  }

  private static String window(String text, int hit, int maxChars) {
    int total = text.codePointCount(0, text.length());
    if (total <= maxChars) {
      return text;
    }
    int hitChars = text.codePointCount(0, hit);
    int start = Math.max(0, hitChars - maxChars / 3);
    if (start + maxChars > total) {
      start = total - maxChars;
    }
    int end = start + maxChars;
    StringBuilder sb = new StringBuilder(maxChars + 2);
    if (start > 0) {
      sb.append('…');
    }
    sb.append(text, text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
    if (end < total) {
      sb.append('…');
    }
    return sb.toString();
  }

  private SearchPage.SearchHit toHit(Row row, String query) {
    return new SearchPage.SearchHit(
        row.getLong("seq"),
        row.getString("msg_id"),
        row.getString("channel_no"),
        row.getString("sender_id"),
        row.getString("sender_name"),
        row.getString("msg_type"),
        row.getString("title"),
        snippet(row.getString("title"), row.getString("body"), row.getString("summary"), query,
            SNIPPET_CHARS),
        row.getString("level"),
        row.getString("media_status"),
        row.getLong("created_at"));
  }

  int clampLimit(int requestedLimit) {
    return Math.min(Math.max(requestedLimit, 1), Math.max(config.getSearch().getMaxLimit(), 1));
  }
}
