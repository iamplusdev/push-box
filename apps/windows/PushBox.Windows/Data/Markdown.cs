using System;
using System.Collections.Generic;
using System.Text;
using System.Text.RegularExpressions;

namespace PushBox.Windows.Data;

/// <summary>块级语法。与 Android 的 <c>MdBlock</c> 一一对应（MainActivity.kt 的 parseMarkdownBlocks）。</summary>
public abstract record MarkdownBlock;

public sealed record MarkdownParagraph(string Text) : MarkdownBlock;

/// <summary>标题级别 1..6 原样留着，字号分档交给渲染层——Android 也是这么分的（1→20、2→18、3→16、4-6→15）。</summary>
public sealed record MarkdownHeading(int Level, string Text) : MarkdownBlock;

public sealed record MarkdownQuote(string Text) : MarkdownBlock;

/// <summary>围栏代码块：内容整段原样保留，一条内联规则都不走。</summary>
public sealed record MarkdownCode(string Text) : MarkdownBlock;

public sealed record MarkdownList(IReadOnlyList<string> Items, bool Ordered) : MarkdownBlock;

/// <summary>表格只存切好的单元格；识别条件（表头 + 分隔行）在解析阶段就判完了。</summary>
public sealed record MarkdownTable(IReadOnlyList<IReadOnlyList<string>> Rows) : MarkdownBlock;

public enum InlineStyle
{
    Plain,
    Bold,

    /// <summary>行内码：等宽 + 底色，颜色仍走正文色（与 Android 的 surfaceVariant 底一致口径）。</summary>
    Code
}

/// <summary>
/// 一段带样式的最小文本。<see cref="Link"/> 是**叠加**在 <see cref="Style"/> 上的，不是第四种样式：
/// Android 先把整串拼成 AnnotatedString、再在拼好的明文上扫裸 URL 上色，所以 <c>**https://x.cn**</c>
/// 同时是粗体和蓝下划线。这里照这个语义拆成两个维度，否则同一条消息两端会渲染成两种样子。
/// </summary>
public sealed record InlineSpan(string Text, InlineStyle Style, bool Link);

/// <summary>
/// §9.2 的轻量 Markdown（D19 同款克制）：**逐行分块 + 两条内联正则**，不是 CommonMark。
/// 这份实现的每一条规则都对齐 Android <c>MainActivity.kt</c> 的 <c>parseMarkdownBlocks</c> 与
/// <c>renderRichText</c>，因为 §12 P3 验收③ 判的是"同一条消息两端渲染结果一致"——
/// §9.2 写的支持集（含斜体）与 Android 实际支持的（含表格、无斜体、无分割线）并不完全重合，
/// 这里以**已上线那一端**为准，分歧记在设计方案 §8.4 W12。
/// </summary>
public static class Markdown
{
    private static readonly Regex Heading = new(@"^(#{1,6})\s+(.*)$", RegexOptions.Compiled);
    private static readonly Regex Unordered = new(@"^\s*[-*+]\s+(.*)$", RegexOptions.Compiled);
    private static readonly Regex Ordered = new(@"^\s*\d+\.\s+(.*)$", RegexOptions.Compiled);
    private static readonly Regex Quote = new(@"^\s*>\s?(.*)$", RegexOptions.Compiled);
    private static readonly Regex TableSeparator = new(@"[-|: ]+\|", RegexOptions.Compiled);

    // 内联只有两条：成对的 **粗体** 与 `行内码`。非贪婪 ⇒ 未闭合的 ** 原样留作正文，
    // 这条是 Android 注释里写明的"不手搓解析器以免边界 bug"的同一份克制。
    private static readonly Regex InlineMd = new(@"\*\*(.+?)\*\*|`(.+?)`", RegexOptions.Compiled);

    private static readonly Regex InlineUrl = new(@"https?://[^\s<>""']+", RegexOptions.Compiled);

    // ---- 分块缓存 ----------------------------------------------------------------------------------

    /// <summary>
    /// 缓存上限。一屏几十条，往上翻也就几百条；再大就是在拿内存换一条早就划出屏幕的消息。
    /// 它是<b>按正文</b>而不是按 msgId 计的：监控类消息一分钟推同一段话进来，那几口气泡共用一份块表。
    /// </summary>
    public const int CacheBound = 400;

    /// <summary>链表节点存着键，淘汰时才不用反查字典。</summary>
    private readonly record struct Entry(string Key, IReadOnlyList<MarkdownBlock> Blocks);

    private static readonly Dictionary<string, LinkedListNode<Entry>> Cache = new(StringComparer.Ordinal);
    private static readonly LinkedList<Entry> Order = new();

    /// <summary>
    /// 按正文取块表：同一条正文第二次拿到<b>同一个对象</b>，不再重走一遍正则。
    ///
    /// 缓存不需要跟着主题失效——<see cref="MarkdownBlock"/> 这一族是纯语法数据，一个颜色都不认，
    /// 样式只在 <c>MarkdownView.Build*</c> 那一步才叠上去（这也是它能被跨样式复用的原因）。
    /// 命中的那一下要挪到 LRU 尾巴：切回一个会话时，它顶上那几条正是马上又要用的。
    ///
    /// 和渲染层别处一样，只在 UI 线程上碰。
    /// </summary>
    public static IReadOnlyList<MarkdownBlock> BlocksFor(string source)
    {
        var key = source ?? string.Empty;
        if (Cache.TryGetValue(key, out var node))
        {
            if (!ReferenceEquals(node, Order.Last))
            {
                Order.Remove(node);
                Order.AddLast(node);
            }
            return node.Value.Blocks;
        }

        var blocks = Parse(key);
        Cache[key] = Order.AddLast(new Entry(key, blocks));
        while (Cache.Count > CacheBound)
        {
            var oldest = Order.First!;
            Order.RemoveFirst();
            Cache.Remove(oldest.Value.Key);
        }
        return blocks;
    }

    /// <summary>清空分块缓存。生产代码不调它——留给用例把"命中还是重解析"摆成可比的对象引用。</summary>
    public static void ClearCache()
    {
        Cache.Clear();
        Order.Clear();
    }

    /// <summary>
    /// 整条正文分块，<b>每次调用真的重算一遍</b>。界面上画气泡走的是 <see cref="BlocksFor"/>
    /// （那条带缓存）；这里留着不带缓存的入口，是给"要的就是这一趟解析本身"的用例用的。
    /// 空串也会得到一个空段落，渲染层因此不必判 null。
    /// </summary>
    public static IReadOnlyList<MarkdownBlock> Parse(string source)
    {
        var text = source ?? string.Empty;
        var lines = text.Split('\n');
        var outBlocks = new List<MarkdownBlock>();
        var paragraph = new StringBuilder();
        var i = 0;

        void FlushParagraph()
        {
            if (paragraph.Length == 0) return;
            outBlocks.Add(new MarkdownParagraph(TrimEnd(paragraph.ToString())));
            paragraph.Clear();
        }

        while (i < lines.Length)
        {
            var line = lines[i];
            if (line.Trim().StartsWith("```", StringComparison.Ordinal))
            {
                FlushParagraph();
                i++;
                var code = new StringBuilder();
                while (i < lines.Length && !lines[i].Trim().StartsWith("```", StringComparison.Ordinal))
                {
                    code.Append(lines[i]).Append('\n');
                    i++;
                }
                i++; // 关掉围栏；没有闭合围栏就到此为止，与 Android 一样不报错
                outBlocks.Add(new MarkdownCode(TrimEnd(code.ToString())));
            }
            else if (line.Contains('|') && i + 1 < lines.Length
                     && TableSeparator.IsMatch(lines[i + 1]) && lines[i + 1].Contains('-'))
            {
                FlushParagraph();
                var rows = new List<IReadOnlyList<string>> { SplitTableRow(line) };
                i += 2;
                while (i < lines.Length && lines[i].Contains('|'))
                {
                    rows.Add(SplitTableRow(lines[i]));
                    i++;
                }
                outBlocks.Add(new MarkdownTable(rows));
            }
            else if (Heading.IsMatch(line))
            {
                FlushParagraph();
                var m = Heading.Match(line);
                outBlocks.Add(new MarkdownHeading(m.Groups[1].Value.Length, m.Groups[2].Value.Trim()));
                i++;
            }
            else if (Quote.IsMatch(line))
            {
                FlushParagraph();
                var quoted = new StringBuilder();
                while (i < lines.Length && Quote.IsMatch(lines[i]))
                {
                    if (quoted.Length > 0) quoted.Append('\n');
                    quoted.Append(Quote.Match(lines[i]).Groups[1].Value);
                    i++;
                }
                outBlocks.Add(new MarkdownQuote(quoted.ToString()));
            }
            else if (Unordered.IsMatch(line) || Ordered.IsMatch(line))
            {
                FlushParagraph();
                var ordered = Ordered.IsMatch(line);
                var rule = ordered ? Ordered : Unordered;
                var items = new List<string>();
                while (i < lines.Length && rule.IsMatch(lines[i]))
                {
                    items.Add(rule.Match(lines[i]).Groups[1].Value);
                    i++;
                }
                outBlocks.Add(new MarkdownList(items, ordered));
            }
            else if (string.IsNullOrWhiteSpace(line))
            {
                FlushParagraph();
                i++;
            }
            else
            {
                if (paragraph.Length > 0) paragraph.Append('\n');
                paragraph.Append(line);
                i++;
            }
        }

        FlushParagraph();
        // 一行都不认识时整条原样回落成段落：绝不出现"消息到了但气泡是空的"
        if (outBlocks.Count == 0) outBlocks.Add(new MarkdownParagraph(text));
        return outBlocks;
    }

    /// <summary>块内文本的内联拆解：粗体 / 行内码 / 裸 URL。空串返回空表，让渲染层直接不给这个 Run。</summary>
    public static IReadOnlyList<InlineSpan> Inline(string text)
    {
        if (string.IsNullOrEmpty(text)) return Array.Empty<InlineSpan>();

        var spans = new List<InlineSpan>();
        var cursor = 0;
        foreach (Match m in InlineMd.Matches(text))
        {
            if (m.Index > cursor) spans.Add(new InlineSpan(text.Substring(cursor, m.Index - cursor), InlineStyle.Plain, false));
            var bold = m.Groups[1].Value;
            var code = m.Groups[2].Value;
            spans.Add(new InlineSpan(bold.Length > 0 ? bold : code,
                bold.Length > 0 ? InlineStyle.Bold : InlineStyle.Code, false));
            cursor = m.Index + m.Length;
        }
        if (cursor < text.Length) spans.Add(new InlineSpan(text.Substring(cursor), InlineStyle.Plain, false));

        // URL 是叠在样式之上的第二遍，与 Android 在拼好的明文上扫描同构
        var linkified = new List<InlineSpan>();
        foreach (var span in spans) LinkInto(linkified, span);
        return linkified;
    }

    private static void LinkInto(List<InlineSpan> into, InlineSpan span)
    {
        var at = 0;
        foreach (Match url in InlineUrl.Matches(span.Text))
        {
            if (url.Index > at) into.Add(new InlineSpan(span.Text.Substring(at, url.Index - at), span.Style, false));
            into.Add(new InlineSpan(url.Value, span.Style, true));
            at = url.Index + url.Length;
        }
        if (at < span.Text.Length) into.Add(new InlineSpan(span.Text.Substring(at), span.Style, false));
    }

    private static IReadOnlyList<string> SplitTableRow(string line)
    {
        var cells = TrimEnd(line.Trim()).Trim('|').Split('|');
        var outCells = new List<string>(cells.Length);
        foreach (var cell in cells) outCells.Add(cell.Trim());
        return outCells;
    }

    /// <summary>Kotlin 的 trimEnd() 只削尾不削头，C# 的 TrimEnd() 默认削全部空白字符，语义一致。</summary>
    private static string TrimEnd(string value) => value.TrimEnd();
}
