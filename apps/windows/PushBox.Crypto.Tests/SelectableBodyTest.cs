using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using PushBox.Windows.Controls;
using PushBox.Windows.Data;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// 可拖选正文这一层。两条判据最关键：<b>收窄</b>（短消息贴着字、长消息用满气泡）与
/// <b>两棵树一致</b>（标尺量出来的高 = 文档画出来的高）。后者一旦不对，气泡会凭空高一行，
/// 而这条只有把两边逐档量出来才能发现。
/// </summary>
public class SelectableBodyTest
{
    private static readonly MarkdownStyle Style = Paint.Style();

    private static SelectableBody Build(string text, double width, params MarkdownBlock[] extra)
    {
        var blocks = new List<MarkdownBlock> { new MarkdownParagraph(text) };
        blocks.AddRange(extra);
        var body = new SelectableBody(blocks, Style);
        body.Measure(new Size(width, double.PositiveInfinity));
        return body;
    }

    private static MarkdownStyle Copying(Action<string> copy) => Paint.Style() with { Copy = copy };

    [Fact]
    public void ShortBodiesHugTheirTextAndLongOnesUseTheWholeBubble()
    {
        Sta.Run(() =>
        {
            // 短的一行放得下：宽度就是标尺量出来的自然宽（贴着字收），只多出那半个像素的余量
            var shortBody = Build("收到", 600);
            Assert.True(shortBody.DesiredSize.Width < 600, $"短正文收窄失败：{shortBody.DesiredSize.Width}");
            Assert.Equal(shortBody.Document.PageWidth, shortBody.DesiredSize.Width);
            Assert.InRange(shortBody.DesiredSize.Width, 29, 32);

            // 长的那条放不下：钉满整整 600 再换行。以前钉的是标尺**折过行之后**的宽（599.76），
            // 文档按这个更窄的数再折一遍，尾巴就会短掉一两个字——这正是本轮要修的东西
            var longBody = Build(string.Concat(Enumerable.Repeat("很长的一段正文。", 60)), 600);
            Assert.Equal(600, longBody.DesiredSize.Width, 0.01);
            Assert.Equal(longBody.Document.PageWidth, longBody.DesiredSize.Width);
        });
    }

    /// <summary>
    /// 天气卡片那种"几行硬换行"的正文：最宽的那一行必须原样留在一行里。
    /// 它的宽（175.577）和标尺报出来的宽（175.5767）差不到 0.001，文档却因此把末尾那个"阴"
    /// 折到下一行——用户看到的就是"下一行只剩一两个字"。最宽行本身就是气泡宽度的来源，
    /// 它自己反而被折断，这条用例就是钉住这个矛盾。
    /// </summary>
    [Fact]
    public void TheWidestHardWrappedLineIsNeverBrokenAgain()
    {
        Sta.Run(() =>
        {
            const string weather = "● 金水 · 当前 15°C 阴\n☁ 天气 晴间多云 → 阴\n和风天气 · 08:30 更新";
            var body = Build(weather, 570);

            // 三行就是三行：4 行 = 最宽那一行被折了，末尾那个字单独占一行
            Assert.Equal(3 * MarkdownStyle.BodyLine, body.DesiredSize.Height, 0.01);
            // 气泡贴着最宽的那一行收，而不是吃满 570
            Assert.InRange(body.DesiredSize.Width, 175.5, 177);
            Assert.Equal(body.Document.PageWidth, body.DesiredSize.Width);
        });
    }

    /// <summary>
    /// 真窗口上挂着 <c>TextFormattingMode="Display"</c> 与 <c>UseLayoutRounding</c>（MainWindow.xaml 顶部）。
    /// 标尺是游离的 TextBlock，继承不到这两个开关——它按 Ideal 量出来的宽比文档实际排出来的行窄
    /// 将近一个像素，最宽那一行于是被折出最后一个字。这条用例把窗口那两个开关原样搬过来。
    /// </summary>
    [Fact]
    public void TheRulerMeasuresWithTheWindowsDisplayFormattingMode()
    {
        Sta.Run(() =>
        {
            var body = new SelectableBody(new MarkdownBlock[]
            {
                new MarkdownParagraph("\U0001F4CD 临颍 · 当前 15°C 多云\n☁\uFE0F 天气 少云 → 多云\n和风天气 · 08:40 更新")
            }, Style);
            var host = new Border { Child = body };
            TextOptions.SetTextFormattingMode(host, TextFormattingMode.Display);
            host.UseLayoutRounding = true;
            host.Measure(new Size(554, double.PositiveInfinity));

            // Display 模式下每行量出来是 22.93 而不是标称的 23，所以留出一行的零点几做容差；
            // 4 行（≈91.7）离得远，这条仍然能抓住"最宽那行被折出一个字"
            Assert.InRange(body.DesiredSize.Height,
                3.0 * MarkdownStyle.BodyLine - 1, 3.0 * MarkdownStyle.BodyLine + 1);
            // 宿主的 UseLayoutRounding 会把交出去的尺寸吸到整设备像素（125% 缩放下 0.8 逻辑像素一格），
            // 所以这里只要求文档的排版宽和实际占位宽是同一个数
            Assert.Equal(body.Document.PageWidth, body.DesiredSize.Width, 1.0);
        });
    }

    /// <summary>
    /// 标尺是 <see cref="MarkdownView.BuildAll"/>（今天正文的样子），画的是 FlowDocument。
    /// 宽度变化时两者的高度必须逐档一致——这正是 RichTextBox 做不到的地方（它的 5px PagePadding
    /// 让每行长出一截，600 宽时比标尺高 25px）。
    /// </summary>
    [Fact]
    public void WrappedHeightMatchesTheTextBlockRulerAtEveryWidth()
    {
        Sta.Run(() =>
        {
            var blocks = new MarkdownBlock[]
            {
                new MarkdownParagraph(string.Concat(Enumerable.Repeat("很长的一段正文，用来量换行。", 14))),
                new MarkdownHeading(2, "二级标题也不能把气泡顶高"),
                new MarkdownQuote("引用里的文字换行时，竖条要跟着长高，而不是只画第一行。"),
            };

            foreach (var width in new[] { 600d, 420, 300, 220 })
            {
                var ruler = MarkdownView.BuildAll(blocks, Style);
                ruler.Measure(new Size(width, double.PositiveInfinity));

                var body = new SelectableBody(blocks, Style);
                body.Measure(new Size(width, double.PositiveInfinity));

                Assert.True(Math.Abs(ruler.DesiredSize.Height - body.DesiredSize.Height) < 0.01,
                    $"{width} 宽：标尺 {ruler.DesiredSize.Height:0.##}，文档 {body.DesiredSize.Height:0.##}");
            }
        });
    }

    [Fact]
    public void ParagraphsHeadingsAndQuotesBecomeNativeBlocksWithTheSameMetrics()
    {
        Sta.Run(() =>
        {
            var document = MarkdownView.BuildDocument(new MarkdownBlock[]
            {
                new MarkdownParagraph("第一段"),
                new MarkdownParagraph("第二段"),
                new MarkdownHeading(1, "标题"),
                new MarkdownQuote("引用"),
            }, Style);

            var blocks = document.Blocks.ToList();
            Assert.Equal(4, blocks.Count);

            var first = Assert.IsType<Paragraph>(blocks[0]);
            Assert.Equal(0d, first.Margin.Top);
            Assert.Equal(MarkdownStyle.BodySize, first.FontSize);
            Assert.Equal(MarkdownStyle.BodyLine, first.LineHeight);
            Assert.Equal(LineStackingStrategy.BlockLineHeight, first.LineStackingStrategy);
            Assert.Same(Style.Text, first.Foreground);

            // FlowDocument 的 Paragraph 默认 Margin 是 Auto，两块之间会多出整整一行（实测 69），
            // 所以每一块都必须显式给 4
            Assert.Equal(MarkdownStyle.BlockGap, ((Paragraph)blocks[1]).Margin.Top);

            var heading = Assert.IsType<Paragraph>(blocks[2]);
            Assert.Equal(MarkdownStyle.H1Size, heading.FontSize);
            Assert.Equal(MarkdownStyle.H1Line, heading.LineHeight);
            Assert.Equal(MarkdownStyle.BlockGap, heading.Margin.Top);
            Assert.Equal(FontWeights.Bold, heading.Inlines.OfType<Run>().Single().FontWeight);

            var quote = Assert.IsType<Paragraph>(blocks[3]);
            Assert.Equal(new Thickness(MarkdownStyle.QuoteBarWidth, 0, 0, 0), quote.BorderThickness);
            Assert.Same(Style.Bar, quote.BorderBrush);
            Assert.Equal(MarkdownStyle.QuoteIndent, quote.Padding.Left);
            Assert.Same(Style.Muted, quote.Foreground);
        });
    }

    [Fact]
    public void ListsKeepTheirMarkersAndCodeAndTablesStayNativeControls()
    {
        Sta.Run(() =>
        {
            var document = MarkdownView.BuildDocument(new MarkdownBlock[]
            {
                new MarkdownList(new[] { "第一条", "第二条" }, true),
                new MarkdownCode("docker ps"),
                new MarkdownTable(new[] { new[] { "字段", "说明" } }),
            }, Style);

            var list = Assert.IsType<System.Windows.Documents.List>(document.Blocks.FirstBlock);
            Assert.Equal(TextMarkerStyle.Decimal, list.MarkerStyle);
            Assert.Equal(MarkdownStyle.ListIndent, list.MarkerOffset);
            // 左边那一列 = 最宽符号的自然宽 + 8：符号因此落在 0 附近，文字从"符号宽 + 8"起
            Assert.True(list.Padding.Left > MarkdownStyle.ListIndent,
                $"符号列太窄，项目符号会被裁掉：{list.Padding.Left}");
            Assert.Same(Style.Muted, list.Foreground);
            Assert.Equal(2, list.ListItems.Count);
            var second = ((Paragraph)list.ListItems.Last().Blocks.FirstBlock);
            Assert.Same(Style.Text, second.Foreground);
            Assert.Equal(MarkdownStyle.ListGap, second.Margin.Top);

            // 代码块与表格仍然走各自那套控件：横向滚动与右上角那颗复制都在这儿，塞进文档流就没了
            var code = Assert.IsType<BlockUIContainer>(document.Blocks.ElementAt(1));
            Assert.IsType<Border>(code.Child);
            var table = Assert.IsType<BlockUIContainer>(document.Blocks.ElementAt(2));
            Assert.IsType<StackPanel>(table.Child);
        });
    }

    /// <summary>内联样式与 emoji 走和 TextBlock 同一份规则：粗体、行内码、链接、彩色字形都在。</summary>
    [Fact]
    public void InlinesFollowTheSameRulesInsideTheDocument()
    {
        Sta.Run(() =>
        {
            var document = MarkdownView.BuildDocument(new MarkdownBlock[]
            {
                new MarkdownParagraph("普通 **粗体** `码片` https://x.cn \U0001F389")
            }, Style);
            var paragraph = (Paragraph)document.Blocks.FirstBlock;

            var runs = paragraph.Inlines.OfType<Run>().ToList();
            Assert.Contains(runs, run => run.Text == "粗体" && run.FontWeight == FontWeights.Bold);
            Assert.Contains(runs, run => run.Text == "码片" && ReferenceEquals(run.Background, Style.Code)
                                          && run.FontFamily == Style.Monospace);
            Assert.Contains(runs, run => run.Text.Contains("https://x.cn")
                                          && ReferenceEquals(run.Foreground, Style.Link));
            // emoji 不进代码区，但在正文里一定换成彩色字形
            Assert.Single(paragraph.Inlines.OfType<InlineUIContainer>());
            Assert.DoesNotContain("\U0001F389", string.Concat(runs.Select(run => run.Text)));
        });
    }

    /// <summary>拖选底色用的是它自己那一支，不是角标的淡绿，而且必须停在"字留得下来"的那一档不透明度。
    /// 配色够不够蓝由 <see cref="ThemeTest"/> 按 token 值算，屏幕上字在不在由下面那条逐像素量。</summary>
    [Fact]
    public void TheSelectionHighlightIsItsOwnBrushAndStaysTranslucent()
    {
        Sta.Run(() =>
        {
            var body = Build("拖选的这一行要有边界", 600);
            Assert.Same(Style.TextSelection, body.SelectionBrush);
            // 反向判据：这一轮之前挂的就是角标那支淡绿，压在气泡底上看不出选了哪里
            Assert.NotSame(Style.BadgeBackground, body.SelectionBrush);
            // 高亮画在字形之上：这一层一旦不透明，整段字就被刷成选择底色（2026-11-04 的实测抱怨）
            Assert.Equal(SelectableBody.SelectionHighlightOpacity, body.SelectionOpacity);
            Assert.True(body.SelectionOpacity <= 0.5, $"不透明度 {body.SelectionOpacity} 高到会把字盖掉");
        });
    }

    /// <summary>
    /// 屏幕上真实那一帧：把气泡摆进一扇离屏窗口、选满整段正文，逐像素数"选之前是字的像素里有多少还是字"。
    /// 判据落在像素上而不是配色表上，因为这一轮的坑正是"token 的对比度算着够、屏幕上字却没了"——
    /// WPF 把选区高亮画在<b>字形之上</b>，不透明度 1 时浅色与深色两档都有 100% 的字形像素被刷成选择底色。
    /// 焦点那一路也单独量：失焦时走的是 <c>IsInactiveSelectionHighlightEnabled</c> 那一支，
    /// 它一旦改用系统的 InactiveSelectionHighlight（不透明蓝），字同样会没。
    /// </summary>
    [Fact]
    public void SelectedGlyphsSurviveTheHighlight()
    {
        foreach (var file in new[] { "Themes/Tokens.xaml", "Themes/Tokens.Dark.xaml" })
        {
            foreach (var withFocus in new[] { true, false })
            {
                (int survived, int total) measured = default;
                Sta.Run(() => measured = GlyphsUnderHighlight(file, withFocus));
                Assert.True(measured.total > 500, $"{file}: 这一帧没量到字（{measured.total} 个像素），是测量本身坏了");
                Assert.True(measured.survived >= measured.total * 0.9,
                    $"{file} 焦点={withFocus}：选中后 {measured.total - measured.survived}/{measured.total} " +
                    "个字形像素被高亮刷掉了，屏幕上就是'字被纯色盖住'");
            }
        }
    }

    /// <summary>用两份 token 各自的真实配色渲一帧，比"选之前/选之后"的字形像素。</summary>
    private static (int survived, int total) GlyphsUnderHighlight(string tokenFile, bool withFocus)
    {
        var tokens = TokenFiles.Read(tokenFile);
        Color Hex(string key) => (Color)ColorConverter.ConvertFromString(tokens[key]);
        var (ink, bubble, wash) = (Hex("Fg.Primary"), Hex("Bg.Bubble.In"), Hex("Bg.TextSelection"));
        var style = Paint.Style() with { Text = Opaque(ink), TextSelection = Opaque(wash) };

        // 八遍而不是四遍：这一格句子在 400px 宽、Display 排版下每字只留 5 个笔画核心像素，
        // 四遍（68 字）量到 365 个，落不进"总量 >500 才算量到了"那条判据——不是字少了，是样本少了。
        var body = new SelectableBody(new MarkdownBlock[]
        {
            new MarkdownParagraph(string.Concat(Enumerable.Repeat("拖选的这一行要有边界，正文要能读清。", 8)))
        }, style);
        var window = new Window
        {
            WindowStyle = WindowStyle.None,
            ShowInTaskbar = false,
            ShowActivated = false,
            WindowStartupLocation = WindowStartupLocation.Manual,
            Left = -10000,
            Top = -10000,
            Width = 400,
            Height = 300,
            Background = Opaque(bubble),
            Content = new Grid { Background = Opaque(bubble), Children = { body } }
        };
        // 与窗口那一路同一套排版开关：标尺与文档都按 Display 取整，字形像素才对得上
        TextOptions.SetTextFormattingMode(window, TextFormattingMode.Display);
        window.UseLayoutRounding = true;
        try
        {
            window.Show();
            Sta.PumpUntil(() => window.IsLoaded && body.ActualHeight > 0);
            WaitForFrames(4);
            var before = Capture(window, out var w, out var h, out var stride);

            var viewer = Tree.First<FlowDocumentScrollViewer>(body);
            if (withFocus) viewer.Focus();
            body.Selection.Select(body.Document.ContentStart, body.Document.ContentEnd);
            WaitForFrames(4);
            var after = Capture(window, out _, out _, out var strideAfter);

            var total = 0;
            var survived = 0;
            for (var y = 0; y < h; y++)
            {
                for (var x = 0; x < w; x++)
                {
                    if (!IsGlyphCore(before, stride, x, y, ink)) continue;
                    total++;
                    if (ReadsAsGlyph(after, strideAfter, x, y, ink, wash)) survived++;
                }
            }

            return (survived, total);
        }
        finally
        {
            window.Close();
        }
    }

    private static SolidColorBrush Opaque(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }

    private static byte[] Capture(Window window, out int width, out int height, out int stride)
    {
        var root = (FrameworkElement)PresentationSource.FromVisual(window)!.RootVisual;
        width = (int)Math.Ceiling(root.ActualWidth);
        height = (int)Math.Ceiling(root.ActualHeight);
        stride = width * 4;
        var pixels = new byte[height * stride];
        var target = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        target.Render(root);
        target.CopyPixels(pixels, stride, 0);
        return pixels;
    }

    /// <summary>等几帧真的合成出来：只泵 dispatcher 会抓到"选区刚失效、文字还没重画"的那一帧。</summary>
    private static void WaitForFrames(int count)
    {
        var frames = 0;
        void Handler(object? sender, EventArgs args) => frames++;
        CompositionTarget.Rendering += Handler;
        Sta.PumpUntil(() => frames >= count);
        CompositionTarget.Rendering -= Handler;
    }

    private static Color Pixel(byte[] pixels, int stride, int x, int y)
    {
        var i = y * stride + x * 4;
        return Color.FromArgb(pixels[i + 3], pixels[i + 2], pixels[i + 1], pixels[i]);
    }

    private static int Distance(Color a, Color b) =>
        Math.Abs(a.R - b.R) + Math.Abs(a.G - b.G) + Math.Abs(a.B - b.B);

    /// <summary>基线里"笔画中间那一格"：几乎就是正文色本身的像素。挑这一批而不是所有偏暗的像素，
    /// 是因为抗锯齿的边缘格在两帧之间本来就会抖，只有笔画中间这一格是"字在不在"的硬证据。
    /// 透明像素（没画到的那一圈）在 Pbgra32 里读出来是纯黑，不排掉就会把窗口边角当成字。</summary>
    private static bool IsGlyphCore(byte[] pixels, int stride, int x, int y, Color ink)
    {
        var c = Pixel(pixels, stride, x, y);
        return c.A > 250 && Distance(c, ink) <= 45;
    }

    /// <summary>选中之后这一格更像字、还是更像选择底色？高亮一旦不透明，笔画中间就被整格刷成后者——
    /// 屏幕上就是"字被纯色盖住"（2026-11-04 的实测抱怨）。判据必须拿选择底色一起比：
    /// 只拿正文色和气泡底比的话，#1A73E8 这一支饱和蓝离正文更近，会被错算成"字还在"。</summary>
    private static bool ReadsAsGlyph(byte[] pixels, int stride, int x, int y, Color ink, Color wash)
    {
        var c = Pixel(pixels, stride, x, y);
        return c.A > 250 && Distance(c, ink) < Distance(c, wash);
    }

    [Fact]
    public void SelectionIsCopyableAndCtrlCUsesWhatWasPicked()
    {
        Sta.Run(() =>
        {
            string? copied = null;
            var body = new SelectableBody(
                new MarkdownBlock[] { new MarkdownParagraph("第一段可以选中"), new MarkdownParagraph("第二段也是正文") },
                Copying(text => copied = text));
            body.Measure(new Size(600, double.PositiveInfinity));
            body.Arrange(new Rect(body.DesiredSize));

            // 拖选的落点由用户决定，这里直接给一整段：选中之后 SelectedText 要原样交出来
            var document = body.Document;
            var start = document.Blocks.FirstBlock!.ContentStart;
            var paragraph = (Paragraph)document.Blocks.FirstBlock!;
            body.Selection.Select(start, paragraph.ContentEnd);
            Assert.Equal("第一段可以选中", body.SelectedText);

            ApplicationCommands.Copy.Execute(null, body);
            Assert.Equal("第一段可以选中", copied);
        });
    }

    [Fact]
    public void AnEmptySelectionCopiesNothing()
    {
        Sta.Run(() =>
        {
            var calls = 0;
            var body = new SelectableBody(new MarkdownBlock[] { new MarkdownParagraph("正文") },
                Copying(_ => calls++));
            body.Measure(new Size(600, double.PositiveInfinity));
            Assert.Equal(string.Empty, body.SelectedText);

            ApplicationCommands.Copy.Execute(null, body);
            Assert.Equal(0, calls);
        });
    }
}
