using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using PushBox.Windows.Controls;
using PushBox.Windows.Data;
using PushBox.Windows.Media;
using PushBox.Windows.Views;
// WPF 的控件体系里也有一个 MediaState，测试里要的始终是 PushBox 那一个
using MediaState = PushBox.Windows.Media.MediaState;

namespace PushBox.Crypto.Tests;

/// <summary>
/// STA 线程 + 一轮 dispatcher：可视对象必须活在 STA 上，而 <see cref="MediaStrip"/> 的槽位是
/// <c>async void</c> 自己填的，所以"续延走完之后"必须能把 dispatcher 推一把才断言得到。
/// </summary>
internal static class Sta
{
    public static void Run(Action work)
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                work();
            }
            catch (Exception e)
            {
                failure = e;
            }
            finally
            {
                // 与 FirstLaunchWindowTest 同一收尾：这一轮的 dispatcher 不关掉，线程退出时会被拖着
                Dispatcher.CurrentDispatcher.InvokeShutdown();
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        thread.Join();
        if (failure is not null) throw failure;
    }
    /// <summary>
    /// 转到条件成立（或超时）。用的是"排一项到 SystemIdle 再推一帧"这套经典泵：
    /// 每圈都必然退出（那一项一定会跑），所以它不会把进程挂住；续延排在 SystemIdle 之前，一圈就排干。
    /// </summary>
    public static void PumpUntil(Func<bool> done, int timeoutMs = 3000)
    {
        var deadline = Environment.TickCount + timeoutMs;
        do
        {
            PumpOnce();
            if (done()) return;
            Thread.Sleep(5);
        }
        while (Environment.TickCount < deadline);

        throw new InvalidOperationException($"dispatcher 转了 {timeoutMs}ms 仍未等到条件成立");
    }

    /// <summary>
    /// 只转不问条件，用来验证**负向**判据（"那个槽位到现在还写着加载中"）：
    /// 这种时候拿 <see cref="PumpUntil"/> 断一个永远不成立的条件，超时抛出会把用例本身弄成失败。
    /// </summary>
    public static void PumpFor(int milliseconds)
    {
        var deadline = Environment.TickCount + milliseconds;
        do
        {
            PumpOnce();
            Thread.Sleep(5);
        }
        while (Environment.TickCount < deadline);
    }

    private static void PumpOnce()
    {
        var frame = new DispatcherFrame();
        Dispatcher.CurrentDispatcher.BeginInvoke(() => frame.Continue = false,
            DispatcherPriority.SystemIdle);
        Dispatcher.PushFrame(frame);
    }
}

/// <summary>一棵只有 Panel / Border / ScrollViewer / ContentControl 的树，按这几种往下走就够了。</summary>
internal static class Tree
{
    public static IEnumerable<DependencyObject> Children(DependencyObject node) => node switch
    {
        Panel panel => panel.Children.Cast<DependencyObject>(),
        Border border => One(border.Child),
        ScrollViewer scroll => One(scroll.Content as DependencyObject),
        ContentControl content => One(content.Content as DependencyObject),
        ItemsControl items => items.Items.Cast<object>().OfType<DependencyObject>(),
        Decorator decorator => One(decorator.Child),
        _ => Enumerable.Empty<DependencyObject>()
    };

    public static T First<T>(DependencyObject root) where T : DependencyObject =>
        Descendants<T>(root).FirstOrDefault() ?? throw new InvalidOperationException(
            $"这棵树里没有 {typeof(T).Name}：{root.GetType().Name}");

    /// <summary>根节点自己也算：MarkdownView 有好几种块直接返回就是我们要的那个类型。</summary>
    public static IEnumerable<T> Descendants<T>(DependencyObject root) where T : DependencyObject
    {
        if (root is T self) yield return self;
        foreach (var child in Children(root))
        {
            if (child is T match) yield return match;
            foreach (var deeper in Descendants<T>(child)) yield return deeper;
        }
    }

    public static int Count<T>(DependencyObject root) where T : DependencyObject =>
        Descendants<T>(root).Count();

    private static IEnumerable<DependencyObject> One(DependencyObject? node) =>
        node is null ? Enumerable.Empty<DependencyObject>() : new[] { node };
}

/// <summary>十三个角色各给一个可辨认的颜色，断言"落到了哪一支画刷"就不会靠巧合通过。</summary>
internal static class Paint
{
    private static SolidColorBrush Brush(byte v)
    {
        var brush = new SolidColorBrush(Color.FromRgb(v, v, v));
        brush.Freeze();
        return brush;
    }

    /// <summary>每次给一棵新的：画刷本身可以共享，但每个用例都拿自己那份，互不干扰。
    /// 参数顺序与 <see cref="MarkdownStyle"/> 的位置参数逐格对齐（Text/Muted/BlockBackground/BubbleBorder/
    /// Code/Bar/Link/Highlight/BadgeBackground/BadgeText/LevelError/LevelWarning/TextSelection + 三支字体族）。</summary>
    public static MarkdownStyle Style() => new(
        Brush(1), Brush(2), Brush(3), Brush(4), Brush(5), Brush(6), Brush(7), Brush(8), Brush(9), Brush(10),
        Brush(11), Brush(12), Brush(13), new FontFamily("Segoe UI"), new FontFamily("Consolas"),
        new FontFamily("Segoe MDL2 Assets"));
}

/// <summary>§9.2 的块级与内联渲染：字号、行高、缩进、竖条，逐条对着 Android 的 MarkdownBody 断。</summary>
public class MarkdownViewTest
{
    private static readonly MarkdownStyle Style = Paint.Style();

    [Fact]
    public void HeadingsCollapseIntoFourDocumentedSizes()
    {
        Sta.Run(() =>
        {
            var expected = new[] { 20d, 18d, 16d, 15d, 15d, 15d };
            for (var level = 1; level <= 6; level++)
            {
                var block = (TextBlock)MarkdownView.Build(new MarkdownHeading(level, "标题"), Style);
                Assert.Equal(expected[level - 1], block.FontSize);
                // 4/5/6 级与正文同字号，只靠这一路加粗区分——两端同一张表
                Assert.Equal(FontWeights.Bold, block.Inlines.OfType<Run>().Single().FontWeight);
                Assert.Equal(4d, block.Margin.Top);
            }
        });
    }

    [Fact]
    public void BodyTextIsFifteenOverTwentyThreeAndWraps()
    {
        Sta.Run(() =>
        {
            var block = (TextBlock)MarkdownView.Build(new MarkdownParagraph("正文"), Style);
            Assert.Equal(15d, block.FontSize);
            Assert.Equal(23d, block.LineHeight);
            // 行高要"就是这么多"而不是"至少这么多"，否则与 Android 的 lineHeight 不是同一件事
            Assert.Equal(LineStackingStrategy.BlockLineHeight, block.LineStackingStrategy);
            Assert.Equal(TextWrapping.Wrap, block.TextWrapping);
            Assert.Same(Style.Text, block.Foreground);
        });
    }

    [Fact]
    public void BoldAndInlineCodeBecomeRunsOnTheSameLine()
    {
        Sta.Run(() =>
        {
            var runs = MarkdownView.Text("看 **重点** 与 `code`", Style, 15, 23, Style.Text, false)
                .Inlines.OfType<Run>().ToList();
            Assert.Equal(new[] { "看 ", "重点", " 与 ", "code" }, runs.Select(r => r.Text).ToArray());
            Assert.Equal(FontWeights.Normal, runs[0].FontWeight);
            Assert.Equal(FontWeights.Bold, runs[1].FontWeight);
            // 行内码：等宽 + 底色，字号与颜色都跟着正文。底色必须是 Code 那支而不是气泡底，
            // 否则码片与气泡同色、看起来就是"没有样式"（与 Android 的 surfaceVariant 同口径）
            Assert.Equal(Style.Monospace.Source, runs[3].FontFamily.Source);
            Assert.Same(Style.Code, runs[3].Background);
            Assert.Equal(FontWeights.Normal, runs[3].FontWeight);
        });
    }

    [Fact]
    public void AUrlInsideBoldIsBothBoldAndUnderlinedBlue()
    {
        Sta.Run(() =>
        {
            var run = MarkdownView.Text("**https://x.cn**", Style, 15, 23, Style.Text, false)
                .Inlines.OfType<Run>().Single();
            // 链接是第二遍扫出来**叠**在粗体上的，不是第四种样式：两端都得是这个结果才算一致
            Assert.Equal("https://x.cn", run.Text);
            Assert.Equal(FontWeights.Bold, run.FontWeight);
            Assert.Same(Style.Link, run.Foreground);
            Assert.NotEmpty(run.TextDecorations);
        });
    }

    [Fact]
    public void ACodeBlockKeepsEveryByteAndScrollsInsteadOfWrapping()
    {
        Sta.Run(() =>
        {
            var source = "```\n**粗** | a | b\nlong line\n```\n";
            var frame = MarkdownView.Build(Markdown.Parse(source).Single(), Style);
            var text = Tree.First<TextBlock>(frame);
            Assert.Equal("**粗** | a | b\nlong line", text.Text);
            Assert.Equal(TextWrapping.NoWrap, text.TextWrapping);
            Assert.Equal(Style.Monospace.Source, text.FontFamily.Source);
            Assert.Equal(13d, text.FontSize);
            Assert.Equal(8d, text.Margin.Left);
            var scroll = Tree.First<ScrollViewer>(frame);
            Assert.Equal(ScrollBarVisibility.Auto, scroll.HorizontalScrollBarVisibility);
            var plate = (Border)frame;
            Assert.Equal(6d, plate.CornerRadius.TopLeft);
            // 代码块与行内码同一支 Code 底：它若等于气泡底，整块代码就没有"被框起来"的观感
            Assert.Same(Style.Code, plate.Background);
        });
    }

    [Fact]
    public void AQuoteIsAThreePixelBarPlusEightPixelIndent()
    {
        Sta.Run(() =>
        {
            var grid = (Grid)MarkdownView.Build(new MarkdownQuote("引用一行"), Style);
            Assert.Equal(3d, grid.ColumnDefinitions[0].Width.Value);
            var bar = (Border)grid.Children[0];
            Assert.Same(Style.Bar, bar.Background);
            Assert.Equal(VerticalAlignment.Stretch, bar.VerticalAlignment);
            var body = (TextBlock)grid.Children[1];
            Assert.Equal(8d, body.Margin.Left);
            Assert.Same(Style.Muted, body.Foreground);
        });
    }

    [Fact]
    public void ListsBulletWithADotOrAnOrdinal()
    {
        Sta.Run(() =>
        {
            var unordered = (StackPanel)MarkdownView.Build(new MarkdownList(new[] { "甲", "乙" }, false), Style);
            Assert.Equal(new[] { "•", "•" }, Bullets(unordered));

            var ordered = (StackPanel)MarkdownView.Build(new MarkdownList(new[] { "甲", "乙" }, true), Style);
            Assert.Equal(new[] { "1.", "2." }, Bullets(ordered));
            // 项间距只给第二条起（Android 的 Column spacedBy 2dp 同形）
            Assert.Equal(0d, ((StackPanel)ordered.Children[0]).Margin.Top);
            Assert.Equal(2d, ((StackPanel)ordered.Children[1]).Margin.Top);
            // 条目正文照走内联解析，列表里的 **粗** 不会变成明文
            var nested = (StackPanel)MarkdownView.Build(new MarkdownList(new[] { "**粗** 项" }, false), Style);
            Assert.Equal(FontWeights.Bold,
                ((TextBlock)((StackPanel)nested.Children[0]).Children[1]).Inlines.OfType<Run>().First().FontWeight);
        });
    }

    [Fact]
    public void ATableDrawsOneRuleUnderTheHeaderOnly()
    {
        Sta.Run(() =>
        {
            var source = "| 服务 | 状态 |\n| --- | --- |\n| api | up |\n";
            var table = (StackPanel)MarkdownView.Build(Markdown.Parse(source).Single(), Style);
            Assert.Equal(3, table.Children.Count);
            var header = (Grid)table.Children[0];
            Assert.Equal(2, header.ColumnDefinitions.Count);
            Assert.True(header.ColumnDefinitions[0].Width.IsStar);
            Assert.Equal("服务", ((TextBlock)header.Children[0]).Inlines.OfType<Run>().Single().Text);
            var rule = (Border)table.Children[1];
            Assert.Equal(1d, rule.Height);
            Assert.Same(Style.Bar, rule.Background);
            Assert.Equal("api", ((TextBlock)((Grid)table.Children[2]).Children[0]).Inlines.OfType<Run>().Single().Text);
        });
    }

    [Fact]
    public void BlocksSitFourApartAndTheFirstOneDoesNot()
    {
        Sta.Run(() =>
        {
            var panel = MarkdownView.BuildAll(new MarkdownBlock[]
            {
                new MarkdownParagraph("第一段"), new MarkdownParagraph("第二段")
            }, Style);
            Assert.Equal(0d, ((TextBlock)panel.Children[0]).Margin.Top);
            Assert.Equal(4d, ((TextBlock)panel.Children[1]).Margin.Top);
        });
    }

    [Fact]
    public void HighlightingIsColorPlusBoldNotABackgroundWash()
    {
        Sta.Run(() =>
        {
            var runs = MarkdownView.Highlighted("阈值 100% 已超限", "阈值", Style, 15)
                .Inlines.OfType<Run>().ToList();
            Assert.Equal("阈值", runs[0].Text);
            Assert.Same(Style.Highlight, runs[0].Foreground);
            Assert.Equal(FontWeights.Bold, runs[0].FontWeight);
            Assert.Null(runs[0].Background);
            Assert.Equal(FontWeights.Normal, runs[1].FontWeight);
        });
    }

    [Fact]
    public void AnEmptyParagraphStillRendersNothingRatherThanThrowing()
    {
        Sta.Run(() =>
        {
            // 空串 → 零个 Run：渲染层因此不必为"这一条到底有没有正文"分叉
            Assert.Empty(MarkdownView.Text(string.Empty, Style, 15, 23, Style.Text, false).Inlines);
        });
    }

    /// <summary>
    /// WPF 的文本栈不认彩色字形表，正文因此逐 Run 过一遍 Emoji.Wpf：emoji 从 Run 里搬进一个
    /// InlineUIContainer 兄弟节点（画的是 Geometry，不是字符）。这条同时钉住反面——
    /// 没有 emoji 的正文一个 Inline 都不多，普通消息不为这个特性付任何结构变化。
    /// </summary>
    [Fact]
    public void AnEmojiInTheBodyBecomesAGraphicInlineWhilePlainTextDoesNot()
    {
        Sta.Run(() =>
        {
            var plain = MarkdownView.Text("部署完成", Style, 15, 23, Style.Text, false);
            Assert.Empty(plain.Inlines.OfType<InlineUIContainer>());
            Assert.Equal("部署完成", plain.Inlines.OfType<Run>().Single().Text);

            var body = MarkdownView.Text("部署完成 \U0001F389", Style, 15, 23, Style.Text, false);
            Assert.Single(body.Inlines.OfType<InlineUIContainer>());
            // 字形搬走了 Run 里就不该再留着那两枚代理项，否则同一枚 emoji 会画两遍（其中一遍是单色）
            var rest = string.Concat(body.Inlines.OfType<Run>().Select(r => r.Text));
            Assert.Contains("部署完成", rest);
            Assert.DoesNotContain("\U0001F389", rest);
        });
    }

    /// <summary>
    /// 代码里的字符要能选中、能复制，所以围栏代码块与行内 `code` 都<b>不</b>替换：
    /// 这是刻意留下的单色区，不是漏接。
    /// </summary>
    [Fact]
    public void CodeStaysMonochromeSoItsCharactersSurviveVerbatim()
    {
        Sta.Run(() =>
        {
            var fence = (TextBlock)((ScrollViewer)((Border)MarkdownView.Build(new MarkdownCode("echo \U0001F389"), Style))
                .Child).Content;
            // 走 Text 属性那一路：WPF 自己会把它物化成一个 Run，但绝不经过 EmojiText
            Assert.Empty(fence.Inlines.OfType<InlineUIContainer>());
            Assert.Contains("\U0001F389", fence.Text);

            var inline = MarkdownView.Text("`echo \U0001F389`", Style, 15, 23, Style.Text, false);
            Assert.Empty(inline.Inlines.OfType<InlineUIContainer>());
            Assert.Contains("\U0001F389", inline.Inlines.OfType<Run>().Single().Text);
        });
    }

    private static List<string> Bullets(StackPanel list) => list.Children
        .Cast<StackPanel>()
        .Select(row => ((TextBlock)row.Children[0]).Text)
        .ToList();
}

/// <summary>
/// 会话列表摘要那一格。文本来自 Binding，而 Emoji.Wpf 那个 <c>TextBlock.Text</c> 是 <c>new</c> 出来的
/// 普通 CLR 属性、不是依赖属性，XAML 里的 Binding 挂不上去，所以自己写了这个带真 DP 的薄控件。
/// 这条钉的是"换 Source 就整段重画"：列表滚动会复用行，留着上一条的字形比不出彩色更难查。
/// </summary>
public class EmojiTextBlockTest
{
    [Fact]
    public void ChangingTheSourceReplacesTheGlyphsInsteadOfStackingThem()
    {
        Sta.Run(() =>
        {
            var block = new EmojiTextBlock();
            Assert.Empty(block.Inlines);

            block.Source = "构建通过 \U0001F389";
            Assert.Single(block.Inlines.OfType<InlineUIContainer>());

            block.Source = "构建失败";
            Assert.Empty(block.Inlines.OfType<InlineUIContainer>());
            Assert.Equal("构建失败", block.Inlines.OfType<Run>().Single().Text);

            block.Source = null;
            Assert.Empty(block.Inlines);
        });
    }
}

/// <summary>一条消息的气泡：类型标签 → 竖条 + 气泡 → 打开链接 → 时间，顺序与 Android 的 MessageBubble 同。</summary>
public class BubbleViewTest
{
    private static readonly MarkdownStyle Style = Paint.Style();

    private static MessageItem Item(string text = "正文", string level = "info", string? typeLabel = null,
        int mediaCount = 0, string? link = null) =>
        new("01HXYZ", 7, "ch", "sender", text, level, typeLabel, 1_700_000_000_000, mediaCount, null, link);

    /// <summary>
    /// Build 返回外层三列 Grid（头像 / 间距 / 气泡），第三列是 BubbleView 内部那个两行 wrapper：
    /// 第一行放气泡主体，第二行放时间戳。按 Grid.GetColumn==2 定位而不是 OfType&lt;Grid&gt;()——
    /// 头像 AvatarBox 自己也是 Grid，按类型取会撞上它。
    /// </summary>
    private static Grid Wrapper(FrameworkElement bubble) =>
        ((Grid)bubble).Children.OfType<Grid>().Single(w => Grid.GetColumn(w) == 2);

    /// <summary>气泡内容列：类型标签、竖条+气泡那一行、打开链接都在这一个 StackPanel 里（时间戳不在）。</summary>
    private static StackPanel BubbleContent(FrameworkElement bubble) =>
        Wrapper(bubble).Children.OfType<StackPanel>().Single();

    /// <summary>
    /// 气泡外面那一层：竖条占第 0 列，气泡本身占第 1 列。按类型找而不是按序号——
    /// 没有类型标签时它才是 Children[0]，写死下标等于把"有没有标签"这件事焊进测试。
    /// </summary>
    private static Grid BarRow(FrameworkElement bubble) =>
        BubbleContent(bubble).Children.OfType<Grid>().Single();

    private static Border Surface(FrameworkElement bubble) =>
        BarRow(bubble).Children.OfType<Border>().First(b => Grid.GetColumn(b) == 1);

    private static StackPanel Body(FrameworkElement bubble) => (StackPanel)Surface(bubble).Child;

    [Fact]
    public void OnlyWarningAndErrorGetTheLeftBar()
    {
        Sta.Run(() =>
        {
            Assert.Null(BubbleView.AccentBrush(null, Style));
            Assert.Same(Style.LevelError, BubbleView.AccentBrush("Level.Error", Style));
            Assert.Same(Style.LevelWarning, BubbleView.AccentBrush("Level.Warning", Style));

            // info 那一档连 Border 都不生成，气泡左边缘跟着贴齐（与 Android 的 if (accent != null) 同形）
            Assert.Single(BarRow(BubbleView.Build(Item(), Style)).Children);

            var row = BarRow(BubbleView.Build(Item(level: "warning"), Style));
            Assert.Equal(2, row.Children.Count);
            var accent = (Border)row.Children[0];
            Assert.Same(Style.LevelWarning, accent.Background);
            Assert.Equal(3d, accent.Width);
        });
    }

    /// <summary>
    /// 色条画在气泡**外面**那一列，所以正文内边距两档相同（Android 同理：条贴在气泡底边上、
    /// padding 恒 10dp）。原来还给 body 补了一记 3px 左外边距，等于同一道条量两次。
    /// 顺带钉住整行的横向落位：普通消息的气泡从 40（头像）+ 8（间距）起，竖条那 3px 只在真有条时
    /// 才多出来——info 档那格原来是写死的 3px，等于给每一条普通消息留一条看不见的空槽。
    /// </summary>
    [Fact]
    public void TheBarIsOneColumnOutsideTheBubbleAndNeverShiftsTheBody()
    {
        Sta.Run(() =>
        {
            Assert.Equal(0d, Body(BubbleView.Build(Item(), Style)).Margin.Left);
            Assert.Equal(0d, Body(BubbleView.Build(Item(level: "error"), Style)).Margin.Left);

            Assert.Equal(48d, BubbleLeft(BubbleView.Build(Item(), Style)));
            Assert.Equal(51d, BubbleLeft(BubbleView.Build(Item(level: "error"), Style)));
        });
    }

    /// <summary>按 600 宽量排一次，取气泡左边缘相对整行的 x（头像与间距都在它左边）。</summary>
    private static double BubbleLeft(FrameworkElement node)
    {
        node.Measure(new Size(600, double.PositiveInfinity));
        node.Arrange(new Rect(0, 0, node.DesiredSize.Width, node.DesiredSize.Height));
        node.UpdateLayout();
        return Surface(node).TranslatePoint(new Point(0, 0), node).X;
    }

    [Fact]
    public void MediaSitsAboveTheText()
    {
        Sta.Run(() =>
        {
            var marker = new TextBlock { Text = "图位" };
            var body = Body(BubbleView.Build(Item(mediaCount: 1), Style, marker));
            Assert.Same(marker, body.Children[0]);
            Assert.IsType<SelectableBody>(body.Children[1]);
        });
    }

    [Fact]
    public void TheTypeLabelAndTheStampBookendTheBubble()
    {
        Sta.Run(() =>
        {
            var built = BubbleView.Build(Item(typeLabel: "Markdown"), Style);
            var column = BubbleContent(built);
            
            // 类型标签现在是 Border（Badge），内部包含 TextBlock
            var badge = (Border)column.Children[0];
            var label = (TextBlock)badge.Child;
            Assert.Equal("Markdown", label.Text);
            // 标签底与字都走 token：原来写死的 #0ABF5E 是退役主色，浅色气泡上几乎看不出底色
            Assert.Same(Style.BadgeBackground, badge.Background);
            Assert.Same(Style.BadgeText, label.Foreground);
            Assert.IsType<Grid>(column.Children[1]);
            // 正文改成可拖选的 FlowDocument 之后，字在文档的 Run 里（TextBlock.Text 那条路已经没有了）
            var selectable = Assert.IsType<SelectableBody>(Body(built).Children[0]);
            Assert.Equal("正文", string.Concat(selectable.Document.Blocks
                .OfType<Paragraph>()
                .SelectMany(block => block.Inlines.OfType<Run>())
                .Select(run => run.Text)));
            // 时间戳在 wrapper 的第二行，不在气泡内容列里（那样它才不会把气泡顶偏）
            Assert.Equal(TimeFormat.Stamp(1_700_000_000_000), ((TextBlock)Wrapper(built).Children[^1]).Text);
        });
    }

    [Fact]
    public void TheOpenLinkRowFollowsTheUrlNotTheCallback()
    {
        Sta.Run(() =>
        {
            // 回调一直在（窗口那边永远传得出来），有没有这一行只由 link_url 说了算
            Assert.DoesNotContain("打开链接", Labels(BubbleView.Build(Item(), Style, null, _ => { })));
            Assert.Contains("打开链接", Labels(BubbleView.Build(Item(link: "https://x.cn/alert"), Style, null, _ => { })));
            Assert.DoesNotContain("打开链接", Labels(BubbleView.Build(Item(link: "https://x.cn"), Style)));
        });
    }

    [Fact]
    public void TheBubbleIsTwelveByTenInsideWithEightPixelCorners()
    {
        Sta.Run(() =>
        {
            var bubble = Surface(BubbleView.Build(Item(), Style));
            Assert.Equal(12d, bubble.Padding.Left);
            Assert.Equal(10d, bubble.Padding.Top);
            Assert.Equal(8d, bubble.CornerRadius.TopLeft);
            Assert.Same(Style.BlockBackground, bubble.Background);
            // 描边走 token：原来那支 20 透明度的黑落在深色气泡上等于没画
            Assert.Same(Style.BubbleBorder, bubble.BorderBrush);
        });
    }

    [Fact]
    public void TheActionRowIconIsAnIconFontGlyphTintedLikeItsLabel()
    {
        Sta.Run(() =>
        {
            var column = BubbleContent(BubbleView.Build(Item(link: "https://x.cn/a"), Style, null, _ => { }));
            var line = column.Children.OfType<StackPanel>().Single();
            var icon = (TextBlock)line.Children[0];
            var label = (TextBlock)line.Children[1];
            // 彩符 🔗 染不上 accent、深色底上还会回退成灰字；换成 MDL2 字形后颜色跟着这一行走
            Assert.Equal(BubbleView.LinkGlyph, icon.Text);
            Assert.Equal(Style.Icon.Source, icon.FontFamily.Source);
            Assert.Same(Style.Link, icon.Foreground);
            Assert.Same(Style.Link, label.Foreground);
        });
    }

    [Fact]
    public void AMessageWithNothingToSayStillGetsABubble()
    {
        Sta.Run(() =>
        {
            // 正文三选一全空时内容那一栏直接不给，气泡本体照样在：绝不出现"消息到了但整行没了"
            Assert.Empty(Body(BubbleView.Build(Item(text: string.Empty), Style)).Children);
            Assert.NotNull(Surface(BubbleView.Build(Item(text: string.Empty), Style)));
        });
    }

    [Fact]
    public void TheDividerIsCenteredTwelvePointGrey()
    {
        Sta.Run(() =>
        {
            // Divider 现在返回的是"全宽居中的 Grid 容器"，字号/对齐/颜色落在内层 TextBlock 上
            var label = Tree.First<TextBlock>(BubbleView.Divider("今天", Style));
            Assert.Equal(12d, label.FontSize);
            Assert.Equal(TextAlignment.Center, label.TextAlignment);
            Assert.Same(Style.Muted, label.Foreground);
        });
    }

    private static List<string> Labels(FrameworkElement bubble)
    {
        var result = new List<string>();
        foreach (var child in BubbleContent(bubble).Children)
        {
            if (child is TextBlock tb)
            {
                result.Add(tb.Text);
            }
            else if (child is StackPanel panel) // ActionLine 现在是 StackPanel
            {
                foreach (var subChild in panel.Children)
                {
                    if (subChild is TextBlock subTb)
                        result.Add(subTb.Text);
                }
            }
        }
        return result;
    }

    /// <summary>
    /// 按主窗那一层把一条消息真摆一遍：ScrollViewer（横向滚动关掉）→ Grid → ItemsControl
    /// （<see cref="MainModel.BubbleMaxWidth"/> 是它的上限，与 <c>ApplyPaneWidth</c> 同一个数）。
    /// 只量"能不能装下"这类事，所以不需要窗口，一条 732 宽的视口就够。
    /// </summary>
    private static ItemsControl LayOutInTimeline(FrameworkElement node, double viewport = 732)
    {
        var items = new ItemsControl
        {
            MaxWidth = MainModel.BubbleMaxWidth(viewport - 24, false),
            HorizontalAlignment = HorizontalAlignment.Left
        };
        items.Items.Add(node);
        var host = new Grid();
        host.Children.Add(items);
        var scroll = new ScrollViewer
        {
            Width = viewport,
            Height = 600,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            Padding = new Thickness(12),
            Content = host
        };
        scroll.Measure(new Size(viewport, 600));
        scroll.Arrange(new Rect(0, 0, viewport, 600));
        scroll.UpdateLayout();
        return items;
    }

    [Fact]
    public void LongBodyWrapsInsideTheTimelineInsteadOfRunningOffTheRightEdge()
    {
        Sta.Run(() =>
        {
            // 回归：气泡列曾经是 Auto，WPF 用无限宽量 Auto 列里的子元素，正文一行都不折，
            // 排成一条一万多像素的长条，超出视口的那截被直接裁掉（"消息内容显示不全"）
            var longText = string.Concat(Enumerable.Repeat("这是一段很长的正文，它必须折行。", 30));
            var node = BubbleView.Build(Item(text: longText), Style);

            var items = LayOutInTimeline(node);
            var body = Tree.First<SelectableBody>(Body(node));

            Assert.True(node.ActualWidth <= items.ActualWidth + 0.5,
                $"消息行 {node.ActualWidth:0.#} 比列表 {items.ActualWidth:0.#} 还宽，右侧会被裁掉");
            Assert.True(body.ActualWidth <= items.ActualWidth,
                $"正文 {body.ActualWidth:0.#} 比列表 {items.ActualWidth:0.#} 还宽");
            Assert.True(body.ActualHeight > MarkdownStyle.BodyLine * 3,
                $"正文只有 {body.ActualHeight:0.#} 高，说明压根没折行");
        });
    }

    [Fact]
    public void ShortBodyKeepsTheBubbleHuggingTheText()
    {
        Sta.Run(() =>
        {
            // 折行那一半是"星号列 + 气泡靠左"换来的，这条钉住另一半：短消息不能被拉成整条白框
            var node = BubbleView.Build(Item(text: "收到"), Style);
            var items = LayOutInTimeline(node);

            Assert.True(items.ActualWidth < 200, $"短消息占了 {items.ActualWidth:0.#} 宽，气泡被拉宽了");
            var stamp = Wrapper(node).Children.OfType<TextBlock>().Last();
            Assert.True(stamp.ActualWidth + 48 >= stamp.DesiredSize.Width - 0.5,
                $"时间戳被窄气泡挤压：{stamp.ActualWidth:0.#} < {stamp.DesiredSize.Width - 48:0.#}");
        });
    }
}

/// <summary>
/// 图位是<b>竖排九宫格</b>（与 Android 的 <c>MediaGrid</c> 同一张表），不是一行横排；
/// 格子的几种"没有字节"各说各的话（§4.5 / W10 的口径）。
/// </summary>
public class MediaStripTest
{
    private static readonly MarkdownStyle Style = Paint.Style();

    [Fact]
    public void ThreeImagesAreThreeSquareCellsInOneRowOfTheGrid()
    {
        Sta.Run(() =>
        {
            var grid = MediaStrip.Build(3, _ => Task.FromResult(Pending()), Style);
            var column = (StackPanel)grid;
            Assert.Equal(Orientation.Vertical, column.Orientation);
            // 三张只有一行，行是那三个方格的容器；超过三张才会长出第二行
            Assert.Single(column.Children);
            var row = (StackPanel)column.Children[0];
            Assert.Equal(Orientation.Horizontal, row.Orientation);
            Assert.Equal(3, row.Children.Count);

            var second = (Border)row.Children[1];
            Assert.Equal(88d, second.Width);
            Assert.Equal(88d, second.Height);
            Assert.Equal(0d, ((Border)row.Children[0]).Margin.Left);
            Assert.Equal(MediaStrip.Gutter, second.Margin.Left);
            Assert.Equal(8d, second.CornerRadius.TopLeft);
        });
    }

    [Fact]
    public void TenImagesWrapIntoRowsAndTheTenthFoldsIntoOneLineOfText()
    {
        Sta.Run(() =>
        {
            var column = (StackPanel)MediaStrip.Build(10, _ => Task.FromResult(Pending()), Style);
            // 3 + 3 + 3 = 9 格，剩下那张折成一句话（Android 的"另有 N 张"）
            Assert.Equal(4, column.Children.Count);
            Assert.Equal(3, ((StackPanel)column.Children[0]).Children.Count);
            Assert.Equal(3, ((StackPanel)column.Children[2]).Children.Count);
            Assert.Equal("另有 1 张", ((TextBlock)column.Children[3]).Text);
            // 行间那道缝只在第二行起出现，第一行贴着头一行正文
            Assert.Equal(0d, ((StackPanel)column.Children[0]).Margin.Top);
            Assert.Equal(MediaStrip.Gutter, ((StackPanel)column.Children[1]).Margin.Top);
        });
    }

    [Fact]
    public void OneImageGetsTheBigSlotAtFourThreeUntilBytesArrive()
    {
        Sta.Run(() =>
        {
            var column = (StackPanel)MediaStrip.Build(1, _ => Task.FromResult(Pending()), Style);
            var single = (Border)column.Children[0];
            Assert.Equal(220d, single.Width);
            Assert.Equal(165d, single.Height);
        });
    }

    [Fact]
    public void ASlotWaitsOnTheNetworkWithoutClaimingTheImageIsGone()
    {
        Sta.Run(() =>
        {
            var grid = MediaStrip.Build(1, _ => NeverReturns(), Style);
            MediaStrip.Realize(grid);
            Assert.Equal("加载中…", Text(Cell(grid, 0)));
            // 转 120ms 让"如果它自己会变"的那些续延都跑完，再回头确认它纹丝不动
            Sta.PumpFor(120);
            Assert.Equal("加载中…", Text(Cell(grid, 0)));
        });
    }

    [Fact]
    public void BytesBecomeAnImageAndTheGridCellCropsWhileTheSingleSlotFits()
    {
        Sta.Run(() =>
        {
            var png = PngBytes();
            var single = MediaStrip.Build(1,
                _ => Task.FromResult(new MediaAsset(MediaState.Ready, png, "image/png")), Style);
            MediaStrip.Realize(single);
            Sta.PumpUntil(() => Tree.Descendants<Image>(single).Any());
            var big = Tree.First<Image>(single);
            Assert.NotNull(big.Source);
            Assert.Equal(220d, big.Width);
            // 单图不裁（微信口径）：一张竖长截图裁成 4:3 会把内容切掉
            Assert.Equal(Stretch.Uniform, big.Stretch);

            var grid = MediaStrip.Build(2,
                _ => Task.FromResult(new MediaAsset(MediaState.Ready, png, "image/png")), Style);
            MediaStrip.Realize(grid);
            Sta.PumpUntil(() => Tree.Descendants<Image>(grid).Any());
            Assert.Equal(Stretch.UniformToFill, Tree.First<Image>(grid).Stretch);
            Assert.Equal(88d, Cell(grid, 1).Width);
        });
    }

    [Fact]
    public void AByteThatWillNeverArriveSaysSoAndOffersOneRetry()
    {
        Sta.Run(() =>
        {
            var grid = MediaStrip.Build(1, _ => Task.FromResult(new MediaAsset(MediaState.Purged)), Style);
            MediaStrip.Realize(grid);
            Sta.PumpUntil(() => Text(Cell(grid, 0)) == "图片已失效");
            Assert.Equal("点击重试", Tree.First<TextBlock>(grid).ToolTip);
        });
    }

    [Fact]
    public void ANetworkHiccupIsNotReportedAsAMissingImage()
    {
        Sta.Run(() =>
        {
            var grid = MediaStrip.Build(1,
                _ => Task.FromResult(new MediaAsset(MediaState.NetworkError, Reason: "连不上服务端")), Style);
            MediaStrip.Realize(grid);
            Sta.PumpUntil(() => Text(Cell(grid, 0)) == "连不上服务端");
            Assert.Equal("点击重试", Tree.First<TextBlock>(grid).ToolTip);
        });
    }

    [Fact]
    public void APendingSlotKeepsSpinningAndANoSourceSlotStaysQuiet()
    {
        Sta.Run(() =>
        {
            var pending = MediaStrip.Build(1, _ => Task.FromResult(Pending()), Style);
            MediaStrip.Realize(pending);
            Sta.PumpUntil(() => Text(Cell(pending, 0)) == "图片加载中");

            // 这一格压根没有图源：文案留空、也不给重试（重试变不出图源来），由会话行的首字头像继续顶着
            var none = MediaStrip.Build(1, _ => Task.FromResult(new MediaAsset(MediaState.NoSource)), Style);
            MediaStrip.Realize(none);
            Sta.PumpUntil(() => Cell(none, 0).Child is not null);
            Assert.Equal(string.Empty, Text(Cell(none, 0)));
            Assert.Null(((TextBlock)Cell(none, 0).Child).ToolTip);
        });
    }

    [Fact]
    public void AThrowingLoaderLeavesARetryableSlot()
    {
        Sta.Run(() =>
        {
            var grid = MediaStrip.Build(1,
                _ => Task.FromException<MediaAsset>(new InvalidOperationException("解码器炸了")), Style);
            MediaStrip.Realize(grid);
            Sta.PumpUntil(() => Text(Cell(grid, 0)) == "图片暂时取不到");
            Assert.Equal("点击重试", Tree.First<TextBlock>(grid).ToolTip);
        });
    }

    [Fact]
    public void ZeroImagesOccupyNothing()
    {
        Sta.Run(() =>
        {
            var empty = (StackPanel)MediaStrip.Build(0, _ => Task.FromResult(Pending()), Style);
            Assert.Empty(empty.Children);
        });
    }

    /// <summary>
    /// 没被装载的格子一个请求都不发。切会话要重建整条时间线，几百条消息就是几百张图：
    /// 全在构造那一刻发车，等于把几百次网络往返和它们的字节解码压进同一次界面刷新。
    /// 这条断的是"<b>不发</b>"，所以它用 <see cref="Sta.PumpFor"/> 断一个永远不成立的条件——
    /// 换成 <c>PumpUntil</c> 会把超时抛出当成用例失败。
    /// </summary>
    [Fact]
    public void AnUnrealizedSlotSendsNothingAndDrawsNothingYet()
    {
        Sta.Run(() =>
        {
            var calls = 0;
            var grid = MediaStrip.Build(3, _ =>
            {
                calls++;
                return Task.FromResult(Pending());
            }, Style);

            Sta.PumpFor(60);
            Assert.Equal(0, calls);
            // 但方框照旧占着那一格：宽高是构造时按表定死的，晚点取字节不会让版面跳一下
            Assert.Null(Cell(grid, 0).Child);
            Assert.Equal(88d, Cell(grid, 2).Width);
            Assert.Equal(88d, Cell(grid, 2).Height);
        });
    }

    /// <summary>
    /// 一个格子一辈子只发一次。滚出屏幕再滚回来时 <c>Loaded</c> 会第二次敲门，那时字节早就画上了；
    /// 按<b>格</b>计而不是按这条消息计，是因为九宫格那几格各有各的对象键（<c>{msgId}#{idx}</c>）。
    /// </summary>
    [Fact]
    public void OneSlotPaysExactlyOneRequestHoweverManyTimesItIsLoaded()
    {
        Sta.Run(() =>
        {
            var calls = 0;
            Task<MediaAsset> Count()
            {
                calls++;
                return Task.FromResult(Pending());
            }

            var single = MediaStrip.Build(1, _ => Count(), Style);
            MediaStrip.Realize(single);
            MediaStrip.Realize(single);
            Sta.PumpFor(60);
            Assert.Equal(1, calls);

            var three = MediaStrip.Build(3, _ => Count(), Style);
            MediaStrip.Realize(three);
            Sta.PumpFor(60);
            Assert.Equal(4, calls);
        });
    }

    private static MediaAsset Pending() => new(MediaState.Pending);

    private static Task<MediaAsset> NeverReturns() => new TaskCompletionSource<MediaAsset>().Task;

    /// <summary>按序号找到那一格：单图时列的直接子就是格子，九宫格时它在一行里。</summary>
    private static Border Cell(FrameworkElement grid, int idx)
    {
        var column = (StackPanel)grid;
        if (column.Children[0] is Border single) return single;
        var row = (StackPanel)column.Children[idx / MediaStrip.GridColumns];
        return (Border)row.Children[idx % MediaStrip.GridColumns];
    }

    private static string Text(Border slot) => (slot.Child as TextBlock)?.Text ?? string.Empty;

    /// <summary>现画一张 4×4 的 PNG：与其在测试里嵌一段来路不明的 base64，不如让 WPF 自己编码。</summary>
    private static byte[] PngBytes()
    {
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen()) dc.DrawRectangle(Brushes.Red, null, new Rect(0, 0, 4, 4));
        var target = new RenderTargetBitmap(4, 4, 96, 96, PixelFormats.Pbgra32);
        target.Render(visual);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(target));
        using var stream = new MemoryStream();
        encoder.Save(stream);
        return stream.ToArray();
    }
}

/// <summary>占位头像的两个维度（首字与定色）和三个绑定时换算的转换器。</summary>
public class AvatarTest
{
    [Fact]
    public void TheInitialIsTheUpperCaseFirstLetterOrAQuestionMark()
    {
        Assert.Equal("G", AvatarVisual.Initial("grafana"));
        Assert.Equal("阈", AvatarVisual.Initial("  阈值 超限 "));
        Assert.Equal("?", AvatarVisual.Initial(null));
        Assert.Equal("?", AvatarVisual.Initial("   "));
    }

    [Fact]
    public void ThePaletteIsTheSixColorsAndroidUses()
    {
        Assert.Equal(new[] { 0x07C160, 0x198CFF, 0xFA5151, 0xB06900, 0x7B61FF, 0x00B3A7 },
            AvatarVisual.Palette.Select(c => c.R << 16 | c.G << 8 | c.B).ToArray());
    }

    [Fact]
    public void TheColorIsTheJavaThirtyOnePolynomialNotTheProcessRandomOne()
    {
        // "hello".hashCode() == 99162322 是 Java/Kotlin 那份的实现，两端同名同色靠的就是这个
        Assert.Equal(99162322, AvatarVisual.StableHash("hello"));
        var first = AvatarVisual.ColorOf("Grafana 告警");
        Assert.Equal(AvatarVisual.ColorOf("Grafana 告警"), first);
        Assert.Contains(first, AvatarVisual.Palette);
        // 取模前抹符号位：C# 的 % 跟被除数同号，负 hash 会直接摸到 Palette[-1]
        Assert.Contains(AvatarVisual.ColorOf("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"), AvatarVisual.Palette);
        Assert.Equal(AvatarVisual.Palette[0], AvatarVisual.ColorOf(null));
    }

    [Fact]
    public void ConvertersTurnNullIntoNothingVisible()
    {
        Sta.Run(() =>
        {
            var visibility = new NullOrEmptyToVisibilityConverter();
            Assert.Equal(Visibility.Collapsed, visibility.Convert(null, typeof(Visibility), null, CultureInfo.InvariantCulture));
            Assert.Equal(Visibility.Collapsed, visibility.Convert(string.Empty, typeof(Visibility), null, CultureInfo.InvariantCulture));
            Assert.Equal(Visibility.Visible, visibility.Convert("3", typeof(Visibility), null, CultureInfo.InvariantCulture));
            // 圆点没有键就是透明：绑定的可见性已经把整格收掉了，这里再兜一层不画任何颜色
            Assert.Same(Brushes.Transparent, new TokenBrushConverter().Convert(null, typeof(Brush), null, CultureInfo.InvariantCulture));
        });
    }

    [Fact]
    public void AvatarBoxDrawsFirstLetterPlaceholderUntilBytesArrive()
    {
        Sta.Run(() =>
        {
            // 没有 Loader（还没注册、或跑用例的这台机器根本没身份）时，这一格必须自己站住：
            // 首字 + 名字定色的底，跟 Android 的 Avatar() 落不到图时是同一张脸
            var box = new AvatarBox { DisplayName = "Grafana 告警", Side = 44 };
            var shell = Assert.IsType<Border>(box.Children[0]);
            Assert.Equal(new SolidColorBrush(AvatarVisual.ColorOf("Grafana 告警")).Color,
                ((SolidColorBrush)shell.Background).Color);
            Assert.Equal("G", ((TextBlock)shell.Child).Text);

            // 换人重画：底色与首字跟着新的显示名走，而不是留着上一张
            box.DisplayName = "push-bot";
            Assert.Equal("P", ((TextBlock)((Border)box.Children[0]).Child).Text);
        });
    }

    [Fact]
    public void AnAvatarAlreadyPaintedRepaintsWhenItsTokenIsInvalidated()
    {
        Sta.Run(() =>
        {
            // 服务端换头像不换 token，界面收不到"地址变了"这回事：装配层在帧带着 sender_avatar 时
            // 调 AvatarBox.Invalidate(token)（缓存已经在那一步作废），画着它的那一格必须自己再画一遍
            var file = WritePng();
            try
            {
                var box = new AvatarBox { DisplayName = "Grafana", Token = "t0k3n", LocalPath = file };
                // Loaded 只有进了可视树才会来；用例里不进窗口，手动把那一下补上
                box.RaiseEvent(new RoutedEventArgs(FrameworkElement.LoadedEvent));
                var painted = box.Children[0];

                // 别的 token 换了头像不该让这一格白画一次
                AvatarBox.Invalidate("someone-else");
                Assert.Same(painted, box.Children[0]);

                AvatarBox.Invalidate("t0k3n");
                Assert.NotSame(painted, box.Children[0]);
            }
            finally
            {
                File.Delete(file);
            }
        });
    }

    /// <summary>一张能解开的小 PNG：走本地头像那条路要真的解出像素，落到占位分支就不算数了。</summary>
    private static string WritePng()
    {
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen()) dc.DrawRectangle(Brushes.Green, null, new Rect(0, 0, 4, 4));
        var bitmap = new RenderTargetBitmap(4, 4, 96, 96, PixelFormats.Pbgra32);
        bitmap.Render(visual);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        var path = Path.Combine(Path.GetTempPath(), "pushbox-avatar-" + Guid.NewGuid().ToString("N") + ".png");
        using var stream = File.Create(path);
        encoder.Save(stream);
        return path;
    }
}

/// <summary>
/// 时间线的行模板（§8.1）：<c>ItemsSource</c> + <see cref="TimelineRow"/> 之后，屏外的条目只是一个数据对象，
/// 建控件这一步挪进了模板。所以这里断的是"数据到了才建、建出来的正是这一条该有的样子"。
/// </summary>
public class TimelineRowTest
{
    private static readonly MarkdownStyle Style = Paint.Style();

    private static MessageItem Item(string text = "正文", int mediaCount = 0, string? link = null) =>
        new("01HXYZ", 7, "ch", "sender", text, "info", null, 1_700_000_000_000, mediaCount, null, link);

    /// <summary>行要的那几样：这里只关心"建出来的是哪一支"，取字节与动作都给空。</summary>
    private static TimelineConfig Config(UIElement? media = null) => new(Style,
        _ => media, null, null,
        new MessageCommands(_ => { }, _ => { }, _ => { }, _ => { }));

    [Fact]
    public void AMessageRowIsABubbleAndADateRowIsADivider()
    {
        Sta.Run(() =>
        {
            var config = Config();

            // 消息走气泡：三列 Grid（头像 / 间距 / 气泡），和 BubbleView 的契约一致
            var bubble = Assert.IsType<Grid>(config.Build(Item()));
            Assert.Equal(3, bubble.ColumnDefinitions.Count);
            Assert.Equal(HorizontalAlignment.Left, bubble.HorizontalAlignment);

            // 日期头是居中的那行灰字，不是气泡
            var divider = Assert.IsType<Grid>(config.Build(new DateDivider("2026年10月28日")));
            Assert.Equal("2026年10月28日", Assert.IsType<TextBlock>(divider.Children[0]).Text);
            Assert.Same(Style.Muted, ((TextBlock)divider.Children[0]).Foreground);
        });
    }

    /// <summary>两样缺一样就不该建：绑定落地顺序不定，谁后到谁触发，早到的那一半不能先建一棵空树。</summary>
    [Fact]
    public void TheRowWaitsForBothTheItemAndTheConfig()
    {
        Sta.Run(() =>
        {
            var row = new TimelineRow { Item = Item() };
            Assert.Null(row.Child);

            row = new TimelineRow { Config = Config() };
            Assert.Null(row.Child);

            row.Item = Item();
            Assert.NotNull(row.Child);
            row.Config = null;
            Assert.Null(row.Child);
        });
    }

    /// <summary>
    /// 回收模式下同一个行实例会被换上下一条数据，所以 <see cref="TimelineRow.Item"/> 一变就得重建——
    /// 菜单也跟着这一条走：纯图那一条不该留着上一条"复制正文"的项。
    /// </summary>
    [Fact]
    public void RecyclingARowBuildsTheNextMessageInsteadOfKeepingTheFirst()
    {
        Sta.Run(() =>
        {
            var row = new TimelineRow { Config = Config(), Item = Item("有正文") };
            var first = (FrameworkElement)row.Child!;
            Assert.Contains(BubbleView.CopyTextTag, Tags(first));

            row.Item = Item(text: string.Empty, mediaCount: 1);
            Assert.NotSame(first, row.Child);
            var next = (FrameworkElement)row.Child!;
            Assert.Contains(BubbleView.CopyImageTag, Tags(next));
            Assert.DoesNotContain(BubbleView.CopyTextTag, Tags(next));
        });
    }

    private static List<string> Tags(FrameworkElement node) =>
        node.ContextMenu?.Items.OfType<MenuItem>().Select(item => item.Tag as string ?? string.Empty).ToList()
        ?? new List<string>();
}
