using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
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
            // 短的一行放得下：宽度就是标尺量出来的自然宽（贴着字收）
            var shortBody = Build("收到", 600);
            Assert.True(shortBody.DesiredSize.Width < 600, $"短正文收窄失败：{shortBody.DesiredSize.Width}");
            Assert.Equal(shortBody.Document.PageWidth, shortBody.DesiredSize.Width);

            // 长的那条放不下：钉满可用宽再换行。注意不是整整 600——CJK 换行落在整个字宽上，
            // 标尺给的是 599.76，文档按同一个数换行才对得上高度
            var ruler = MarkdownView.BuildAll(new MarkdownBlock[]
            {
                new MarkdownParagraph(string.Concat(Enumerable.Repeat("很长的一段正文。", 60)))
            }, Style);
            ruler.Measure(new Size(600, double.PositiveInfinity));

            var longBody = Build(string.Concat(Enumerable.Repeat("很长的一段正文。", 60)), 600);
            Assert.Equal(Math.Min(600, ruler.DesiredSize.Width), longBody.DesiredSize.Width, 0.01);
            Assert.True(longBody.DesiredSize.Width > 599, $"长正文没有用满气泡：{longBody.DesiredSize.Width}");
            Assert.Equal(longBody.Document.PageWidth, longBody.DesiredSize.Width);
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
