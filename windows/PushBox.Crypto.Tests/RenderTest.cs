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

/// <summary>八个角色各给一个可辨认的颜色，断言"落到了哪一支画刷"就不会靠巧合通过。</summary>
internal static class Paint
{
    private static SolidColorBrush Brush(byte v)
    {
        var brush = new SolidColorBrush(Color.FromRgb(v, v, v));
        brush.Freeze();
        return brush;
    }

    /// <summary>每次给一棵新的：画刷本身可以共享，但每个用例都拿自己那份，互不干扰。</summary>
    public static MarkdownStyle Style() => new(
        Brush(1), Brush(2), Brush(3), Brush(4), Brush(5), Brush(6), Brush(7), Brush(8),
        new FontFamily("Segoe UI"), new FontFamily("Consolas"));
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
            // 行内码：等宽 + 底色，字号与颜色都跟着正文（与 Android 的 surfaceVariant 底同口径）
            Assert.Equal(Style.Monospace.Source, runs[3].FontFamily.Source);
            Assert.Same(Style.BlockBackground, runs[3].Background);
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
            Assert.Same(Style.BlockBackground, plate.Background);
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

    private static List<string> Bullets(StackPanel list) => list.Children
        .Cast<StackPanel>()
        .Select(row => ((TextBlock)row.Children[0]).Text)
        .ToList();
}

/// <summary>一条消息的气泡：类型标签 → 竖条 + 气泡 → 打开链接 → 时间，顺序与 Android 的 MessageBubble 同。</summary>
public class BubbleViewTest
{
    private static readonly MarkdownStyle Style = Paint.Style();

    private static MessageItem Item(string text = "正文", string level = "info", string? typeLabel = null,
        int mediaCount = 0, string? link = null) =>
        new("01HXYZ", 7, "ch", "sender", text, level, typeLabel, 1_700_000_000_000, mediaCount, null, link);

    /// <summary>
    /// 气泡外面那一层：竖条占第 0 列，气泡本身占第 1 列。按类型找而不是按序号——
    /// 没有类型标签时它才是 Children[0]，写死下标等于把"有没有标签"这件事焊进测试。
    /// </summary>
    /// <summary>
    /// 新版 BubbleView.Build 返回的是外层三列 Grid（头像 + 间距 + 气泡内容），
    /// BarRow 在第三列的 StackPanel 里。
    /// </summary>
    private static Grid BarRow(FrameworkElement bubble)
    {
        // bubble 是外层 Grid，第三列是气泡内容的 StackPanel
        var outer = bubble as Grid;
        if (outer is null) throw new InvalidCastException("BubbleView.Build must return a Grid");
        var bubbleContent = outer.Children.OfType<StackPanel>().FirstOrDefault();
        if (bubbleContent is null) throw new InvalidOperationException("No bubble content StackPanel found");
        // 从气泡内容里找 BarRow Grid
        return bubbleContent.Children.OfType<Grid>().Single();
    }

    /// <summary>从外层 Grid 取出气泡内容的 StackPanel（第三列）。方便那些直接遍历 children 的测试。</summary>
    private static StackPanel BubbleContent(FrameworkElement bubble)
    {
        var outer = bubble as Grid;
        if (outer is null) throw new InvalidCastException("BubbleView.Build must return a Grid");
        return outer.Children.OfType<StackPanel>().First();
    }

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

    [Fact]
    public void TheBodyShiftsByTheBarOnlyWhenThereIsABar()
    {
        Sta.Run(() =>
        {
            Assert.Equal(0d, Body(BubbleView.Build(Item(), Style)).Margin.Left);
            Assert.Equal(3d, Body(BubbleView.Build(Item(level: "error"), Style)).Margin.Left);
        });
    }

    [Fact]
    public void MediaSitsAboveTheText()
    {
        Sta.Run(() =>
        {
            var marker = new TextBlock { Text = "图位" };
            var body = Body(BubbleView.Build(Item(mediaCount: 1), Style, marker));
            Assert.Same(marker, body.Children[0]);
            Assert.IsType<StackPanel>(body.Children[1]);
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
            Assert.IsType<Grid>(column.Children[1]);
            // 正文是拼进 Inlines 的，TextBlock.Text 那个 getter 在这种写法下回空串，所以断言读 Run
            Assert.Equal("正文", string.Concat(Tree.First<TextBlock>(Body(built)).Inlines
                .OfType<Run>().Select(r => r.Text)));
            Assert.Equal(TimeFormat.Stamp(1_700_000_000_000), ((TextBlock)column.Children[^1]).Text);
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
            var label = (TextBlock)BubbleView.Divider("今天", Style);
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
            Sta.PumpUntil(() => Tree.Descendants<Image>(single).Any());
            var big = Tree.First<Image>(single);
            Assert.NotNull(big.Source);
            Assert.Equal(220d, big.Width);
            // 单图不裁（微信口径）：一张竖长截图裁成 4:3 会把内容切掉
            Assert.Equal(Stretch.Uniform, big.Stretch);

            var grid = MediaStrip.Build(2,
                _ => Task.FromResult(new MediaAsset(MediaState.Ready, png, "image/png")), Style);
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
            Sta.PumpUntil(() => Text(Cell(pending, 0)) == "图片加载中");

            // 这一格压根没有图源：文案留空、也不给重试（重试变不出图源来），由会话行的首字头像继续顶着
            var none = MediaStrip.Build(1, _ => Task.FromResult(new MediaAsset(MediaState.NoSource)), Style);
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
}
