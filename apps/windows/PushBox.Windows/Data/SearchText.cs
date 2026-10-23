using System;
using System.Collections.Generic;
using System.Text;

namespace PushBox.Windows.Data;

/// <summary>
/// §7.4 搜索结果的两件小事：把命中处裁成一段片段，再指出片段里哪些字符要加亮。
///
/// 服务端那条 <c>/search</c> 自己会把片段算好（core 侧 SearchService.snippet），本机这一段得自己裁。
/// 两边刻意同形（同一个 120 字窗口、同一个"命中处前面留 1/3"、同一串 <c>…</c>），
/// 这样 W12 的列表不需要为"这条来自本地、那条来自服务端"写两套渲染。
/// </summary>
public static class SearchText
{
    /// <summary>与服务端 SNIPPET_CHARS 同值：列表里一条只显示一两行，正文另有会话页。</summary>
    public const int SnippetChars = 120;

    /// <summary>命中处前面留多少，同服务端的 maxChars/3。</summary>
    private const int LeadChars = SnippetChars / 3;

    /// <summary>三列里找第一个命中处开窗，顺序与服务端一致：标题、正文、摘要。
    /// 全文索引允许命中在分词后的变形上（大小写、标点），这时至少给个开头而不是空串。</summary>
    public static string Snippet(string? term, string? title, string? body, string? summary)
    {
        string? fallback = null;
        foreach (var text in new[] { title, body, summary })
        {
            if (string.IsNullOrEmpty(text)) continue;
            fallback ??= text;
            var at = FirstHit(text!, term);
            if (at >= 0) return Window(text!, at);
        }

        return fallback == null ? "" : Window(fallback, 0);
    }

    /// <summary>片段里要加亮的区间，按出现顺序、互不重叠。整词优先，再补空格切开的各个词——
    /// 与服务端 terms() 同一条顺序，否则"CPU usage"查出来会只亮 usage。</summary>
    public static IReadOnlyList<(int Start, int Length)> HighlightRanges(string? text, string? term)
    {
        var ranges = new List<(int Start, int Length)>();
        if (string.IsNullOrEmpty(text) || string.IsNullOrWhiteSpace(term)) return ranges;

        foreach (var word in Terms(term!))
        {
            for (var at = text!.IndexOf(word, StringComparison.OrdinalIgnoreCase); at >= 0;
                 at = at + word.Length > text.Length
                     ? -1
                     : text.IndexOf(word, at + word.Length, StringComparison.OrdinalIgnoreCase))
            {
                if (!Intersects(ranges, at, word.Length)) ranges.Add((at, word.Length));
            }
        }

        ranges.Sort((a, b) => a.Start.CompareTo(b.Start));
        return ranges;
    }

    /// <summary>整串优先，再退到空格切出的各个词；单字查询本身就只有一项。</summary>
    private static IEnumerable<string> Terms(string term)
    {
        var trimmed = term.Trim();
        yield return trimmed;
        foreach (var part in trimmed.Split(' ', '\t', '\n', '\r'))
        {
            if (part.Length > 0 && part != trimmed) yield return part;
        }
    }

    private static bool Intersects(List<(int Start, int Length)> ranges, int at, int length)
    {
        foreach (var existing in ranges)
        {
            if (at < existing.Start + existing.Length && existing.Start < at + length) return true;
        }

        return false;
    }

    private static int FirstHit(string text, string? term)
    {
        var best = -1;
        foreach (var word in Terms(term ?? ""))
        {
            var at = text.IndexOf(word, StringComparison.OrdinalIgnoreCase);
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }

        return best;
    }

    private static string Window(string text, int hitAt)
    {
        if (text.Length <= SnippetChars) return text;

        var start = Math.Max(0, hitAt - LeadChars);
        if (start + SnippetChars > text.Length) start = text.Length - SnippetChars;
        var end = start + SnippetChars;

        // 别把一对代理项劈开：正文里的 emoji 会让下标正好落在半个字上，劈开就是屏上一个问号
        if (start > 0 && char.IsLowSurrogate(text[start])) start++;
        if (end < text.Length && char.IsHighSurrogate(text[end - 1])) end--;

        var sb = new StringBuilder(SnippetChars + 4);
        if (start > 0) sb.Append('…');
        sb.Append(text, start, end - start);
        if (end < text.Length) sb.Append('…');
        return sb.ToString();
    }
}
