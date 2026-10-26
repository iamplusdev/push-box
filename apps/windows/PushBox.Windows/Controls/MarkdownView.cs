using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using PushBox.Windows.Data;

namespace PushBox.Windows.Controls;

/// <summary>
/// §9.1/§9.2 落成的一组具体画刷与字号。之所以由调用方传进来而不是让渲染层自己去摸
/// <c>Application.Current</c>：这样"渲染结果"是纯函数，用例能在没有 Application 的 STA 线程上
/// 直接断言一棵树的字号与颜色，而窗口侧只需要 <see cref="MarkdownStyle.FromResources"/> 一次。
/// 两支 level 色也在这儿（<see cref="LevelError"/> / <see cref="LevelWarning"/>）：气泡左侧那根竖条
/// 认的是 <c>AccentKey</c> 这个**键名**，把键名翻译成颜色是渲染层的事，模型因此不碰画刷。
/// <see cref="Code"/> 单独一支而不是复用 <see cref="BlockBackground"/>：后者就是气泡自己的底，
/// 拿它给行内码/代码块铺底等于没画（2026-10-26 前的实际症状）。
/// </summary>
public sealed record MarkdownStyle(Brush Text, Brush Muted, Brush BlockBackground, Brush Code, Brush Bar,
    Brush Link, Brush Highlight, Brush BadgeBackground, Brush BadgeText,
    Brush LevelError, Brush LevelWarning, FontFamily Body, FontFamily Monospace)
{
    // §9.2 的字号与行高，数值直接沿用 Android 的 Typography（15/23、13/18、12/16、16/22）：
    // WPF 的 1/96 英寸与 dp 不同基准，同数值在 PC 上略大，验收按"观感一致"而不是"像素一致"（§8.2）
    //
    // 这组常量是 Windows 侧唯一的字号来源：Tokens.xaml 与各窗口的 XAML 都写
    // `{x:Static controls:MarkdownStyle.BodySize}`，不在 XAML 里留数字（TypographyTest 会扫）。
    public const double BodySize = 15;
    public const double BodyLine = 23;
    public const double SmallSize = 13;
    public const double SmallLine = 18;
    public const double LabelSize = 12;
    public const double LabelLine = 16;
    // 标题四档。行高按 §9.2 的表格取整（20→28、18→25、16→22）；H4 及以上共用正文档
    public const double H1Size = 20;
    public const double H1Line = 28;
    public const double H2Size = 18;
    public const double H2Line = 25;
    public const double H3Size = 16;
    public const double H3Line = 22;
    public const double BlockGap = 4;
    public const double ListGap = 2;
    public const double CodePadding = 8;
    public const double CodeRadius = 6;
    public const double QuoteBarWidth = 3;
    public const double QuoteIndent = 8;
    public const double ListIndent = 8;

    public static MarkdownStyle FromResources() => new(
        Brush("Fg.Primary"), Brush("Fg.Secondary"), Brush("Bg.Bubble.In"), Brush("Bg.Code"), Brush("Divider"),
        Brush("Accent"), Brush("Level.Highlight"), Brush("Bg.AccentTint"), Brush("Accent"),
        Brush("Level.Error"), Brush("Level.Warning"),
        Font("Font.UI"), Font("Font.Code"));

    private static Brush Brush(string key) =>
        Application.Current?.TryFindResource(key) as Brush ?? Brushes.Gray;

    // 字体族也走 token：窗口级那一份与这里的正文/等宽本来是同一个值写了两遍，改一处就会分叉
    private static FontFamily Font(string key) =>
        Application.Current?.TryFindResource(key) as FontFamily ?? new FontFamily("Segoe UI, Microsoft YaHei UI");
}

/// <summary>
/// 把 <see cref="Markdown"/> 解析出来的块拼成 WPF 元素。支持集与视觉参数逐条对齐 Android 的
/// <c>MarkdownBody</c>（MainActivity.kt:1102-1158），包括它的**不支持**部分：没有斜体、没有分割线，
/// 表头 + 分隔行才算一张表。
/// </summary>
public static class MarkdownView
{
    /// <summary>等宽列用的星型宽度：WPF 没有一个现成的 <c>GridLength.Star</c>，两份都要现拼。</summary>
    private static readonly GridLength Star = new(1, GridUnitType.Star);

    /// <summary>块之间的间距：Android 那边是 <c>Column spacedBy 4dp</c>。</summary>
    public static StackPanel BuildAll(IReadOnlyList<MarkdownBlock> blocks, MarkdownStyle style)
    {
        var panel = new StackPanel();
        var first = true;
        foreach (var block in blocks)
        {
            var element = Build(block, style);
            if (!first) element.Margin = new Thickness(0, MarkdownStyle.BlockGap, 0, 0);
            first = false;
            panel.Children.Add(element);
        }
        return panel;
    }

    public static FrameworkElement Build(MarkdownBlock block, MarkdownStyle style) => block switch
    {
        MarkdownParagraph p => Text(p.Text, style, MarkdownStyle.BodySize, MarkdownStyle.BodyLine,
            style.Text, false),
        // 标题级别 1..6 只分四档字号（20/18/16/15），全部加粗、顶部留 4：与 Android 的 when(level) 同一张表
        MarkdownHeading h => Text(h.Text, style, HeadingSize(h.Level), HeadingLine(h.Level),
            style.Text, true, new Thickness(0, MarkdownStyle.BlockGap, 0, 0)),
        MarkdownQuote q => Quote(q.Text, style),
        MarkdownCode c => Code(c.Text, style),
        MarkdownList l => List(l, style),
        MarkdownTable t => Table(t, style),
        _ => new TextBlock()
    };

    /// <summary>标题字号分档。4/5/6 级共用正文大小，只靠加粗区分——Android 就是这么省的。</summary>
    public static double HeadingSize(int level) => level switch
    {
        1 => MarkdownStyle.H1Size,
        2 => MarkdownStyle.H2Size,
        3 => MarkdownStyle.H3Size,
        _ => MarkdownStyle.BodySize
    };

    /// <summary>标题行高。原来全部拿 <see cref="MarkdownStyle.BodyLine"/>（23），
    /// 20px 的 H1 只剩 1.15×，比正文的 1.53× 还挤——现在按 §9.2 那张表逐档给。</summary>
    public static double HeadingLine(int level) => level switch
    {
        1 => MarkdownStyle.H1Line,
        2 => MarkdownStyle.H2Line,
        3 => MarkdownStyle.H3Line,
        _ => MarkdownStyle.BodyLine
    };

    /// <summary>
    /// 一段带内联样式的文本。**粗体**/行内码/裸 URL 三种叠在同一个 TextBlock 的 Inlines 上，
    /// 与 Android 的 AnnotatedString 同构：URL 是第二遍扫出来的，所以 <c>**https://x.cn**</c>
    /// 会同时是等宽/粗体 + 蓝下划线，而不是二选一。
    /// emoji 逐 Run 走 <see cref="EmojiText"/>：行内码这一段刻意跳过，与围栏代码块（<see cref="Code"/>）
    /// 同一个口径——代码里的字符要原样，不能被换成图形。
    /// </summary>
    public static TextBlock Text(string value, MarkdownStyle style, double size, double line,
        Brush color, bool bold, Thickness? margin = null)
    {
        var text = new TextBlock
        {
            FontFamily = style.Body,
            FontSize = size,
            LineHeight = line,
            // WPF 默认的 LineStackingStrategy 只保证"至少这么高"，跟 dp 的行高对不上；
            // 固定成 BlockLineHeight 才和 Android 的 lineHeight 是同一件事
            LineStackingStrategy = LineStackingStrategy.BlockLineHeight,
            TextWrapping = TextWrapping.Wrap,
            Foreground = color,
            Margin = margin ?? new Thickness(0)
        };
        foreach (var span in Markdown.Inline(value))
        {
            var run = new Run(span.Text)
            {
                FontWeight = bold || span.Style == InlineStyle.Bold ? FontWeights.Bold : FontWeights.Normal,
                // 链接色叠在样式之上：Android 的 SpanStyle 也是"再加一层"，不是替换
                Foreground = span.Link ? style.Link : color,
                TextDecorations = span.Link ? TextDecorations.Underline : null
            };
            if (span.Style == InlineStyle.Code)
            {
                run.FontFamily = style.Monospace;
                // 与气泡底同色时这枚"码片"根本看不出来，必须走单独的 Code 画刷
                run.Background = style.Code;
            }
            text.Inlines.Add(run);
            // 必须在 Add 之后：Run 没有父集合时替换插不进兄弟节点，而且不抛异常、只是什么都不出
            if (span.Style != InlineStyle.Code) EmojiText.Substitute(run);
        }
        return text;
    }

    /// <summary>
    /// §7.4 的命中高亮：刷主色 + 加粗，不是加背景色（Android 的 <c>highlighted</c> 就是这个样式）。
    /// 范围来自 W11 的 <see cref="SearchText.HighlightRanges"/>：整串优先，再按空格分开的词补。
    /// </summary>
    public static TextBlock Highlighted(string text, string term, MarkdownStyle style, double size)
    {
        var block = new TextBlock
        {
            FontFamily = style.Body,
            FontSize = size,
            TextWrapping = TextWrapping.Wrap,
            Foreground = style.Text
        };
        var ranges = SearchText.HighlightRanges(text, term);
        var at = 0;
        foreach (var (start, length) in ranges)
        {
            if (start > at) block.Inlines.Add(new Run(text.Substring(at, start - at)));
            block.Inlines.Add(new Run(text.Substring(start, length))
            {
                Foreground = style.Highlight,
                FontWeight = FontWeights.Bold
            });
            at = start + length;
        }
        if (at < text.Length) block.Inlines.Add(new Run(text.Substring(at)));
        EmojiText.Substitute(block);
        return block;
    }

    private static FrameworkElement Quote(string value, MarkdownStyle style)
    {
        var row = new Grid();
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(MarkdownStyle.QuoteBarWidth) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = Star });
        var bar = new Border
        {
            Background = style.Bar,
            // 竖条要跟着文本高度走，否则两行以上的引用会只有一小截（Android 用 fillMaxHeight + IntrinsicSize.Min）
            VerticalAlignment = VerticalAlignment.Stretch
        };
        Grid.SetColumn(bar, 0);
        var body = Text(value, style, MarkdownStyle.BodySize, MarkdownStyle.BodyLine, style.Muted, false,
            new Thickness(MarkdownStyle.QuoteIndent, 0, 0, 0));
        Grid.SetColumn(body, 1);
        row.Children.Add(bar);
        row.Children.Add(body);
        return row;
    }

    private static FrameworkElement Code(string value, MarkdownStyle style)
    {
        // 横向滚动、不折行：日志与命令的原样排版比"看得见全行"重要（§9.2 的代码块口径）
        var scroll = new ScrollViewer
        {
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            VerticalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = new TextBlock
            {
                Text = value, // 代码块内容一条内联规则都不走，emoji 也刻意保持单色（不走 EmojiText）
                FontFamily = style.Monospace,
                FontSize = MarkdownStyle.SmallSize,
                LineHeight = MarkdownStyle.SmallLine,
                LineStackingStrategy = LineStackingStrategy.BlockLineHeight,
                TextWrapping = TextWrapping.NoWrap,
                Foreground = style.Text,
                Margin = new Thickness(MarkdownStyle.CodePadding)
            }
        };
        return new Border
        {
            Background = style.Code,
            CornerRadius = new CornerRadius(MarkdownStyle.CodeRadius),
            Child = scroll
        };
    }

    private static FrameworkElement List(MarkdownList block, MarkdownStyle style)
    {
        var panel = new StackPanel();
        for (var i = 0; i < block.Items.Count; i++)
        {
            var bullet = block.Ordered ? $"{i + 1}." : "•";
            var row = new StackPanel { Orientation = Orientation.Horizontal };
            row.Children.Add(new TextBlock
            {
                Text = bullet,
                FontFamily = style.Body,
                FontSize = MarkdownStyle.BodySize,
                Foreground = style.Muted
            });
            row.Children.Add(Text(block.Items[i], style, MarkdownStyle.BodySize, MarkdownStyle.BodyLine,
                style.Text, false, new Thickness(MarkdownStyle.ListIndent, 0, 0, 0)));
            if (i > 0) row.Margin = new Thickness(0, MarkdownStyle.ListGap, 0, 0);
            panel.Children.Add(row);
        }
        return panel;
    }

    private static FrameworkElement Table(MarkdownTable block, MarkdownStyle style)
    {
        var panel = new StackPanel();
        for (var r = 0; r < block.Rows.Count; r++)
        {
            var cells = block.Rows[r];
            var row = new Grid { Margin = new Thickness(0, 2, 0, 2) };
            for (var c = 0; c < cells.Count; c++)
            {
                row.ColumnDefinitions.Add(new ColumnDefinition { Width = Star });
                var cell = Text(cells[c], style, MarkdownStyle.SmallSize, MarkdownStyle.SmallLine,
                    style.Text, false, new Thickness(0, 0, 6, 0));
                Grid.SetColumn(cell, c);
                row.Children.Add(cell);
            }
            panel.Children.Add(row);
            // 只有表头下面画一条线，分隔行本身在被解析时就吃掉了
            if (r == 0) panel.Children.Add(new Border
            {
                Background = style.Bar,
                Height = 1,
                Margin = new Thickness(0, 0, 0, 2)
            });
        }
        return panel;
    }
}
