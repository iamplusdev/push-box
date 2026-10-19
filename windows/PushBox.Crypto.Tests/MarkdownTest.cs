using System;
using System.Collections.Generic;
using System.Linq;
using PushBox.Windows.Data;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// §9.2 的那份轻量 Markdown。这里的每一条断言都对着 Android <c>parseMarkdownBlocks</c> 的实际行为写，
/// 而不是对着"标准 Markdown 应该怎样"写——§12 P3 验收③ 判的是同一条消息在两个端上渲染结果一致，
/// 所以"文档写了斜体但上线的那端没有斜体"这一类分歧，以已上线那端为准（记在 §8.4 W12）。
/// </summary>
public class MarkdownTest
{
    // ---- 分块 -------------------------------------------------------------------------------------

    [Fact]
    public void AFencedBlockKeepsItsContentVerbatim()
    {
        var blocks = Markdown.Parse("前面一句\n```\nERROR **disk** `full`\n```\n后面一句");

        Assert.IsType<MarkdownParagraph>(blocks[0]);
        var code = Assert.IsType<MarkdownCode>(blocks[1]);
        // 围栏里一条内联规则都不走：日志和命令的原样排版比"看起来像 markdown"重要
        Assert.Equal("ERROR **disk** `full`", code.Text);
        Assert.IsType<MarkdownParagraph>(blocks[2]);
    }

    [Fact]
    public void AnUnclosedFenceStillBecomesACodeBlock()
    {
        // Android 那边闭合围栏找不到就走到头，PC 照做：不报错、不把剩下的行吞成段落
        var code = Assert.IsType<MarkdownCode>(Assert.Single(Markdown.Parse("```\n没有收口的代码")));

        Assert.Equal("没有收口的代码", code.Text);
    }

    [Fact]
    public void ATableNeedsAHeaderAndASeparatorRow()
    {
        var withSeparator = Markdown.Parse("| 服务 | 状态 |\n| --- | --- |\n| api | 正常 |");
        var table = Assert.IsType<MarkdownTable>(Assert.Single(withSeparator));
        Assert.Equal(new[] { "服务", "状态" }, table.Rows[0]);
        Assert.Equal(new[] { "api", "正常" }, table.Rows[1]);
        // 分隔行本身不是一门，被吃掉；正文里剩下的行都算数据行
        Assert.Equal(2, table.Rows.Count);

        // 只有竖线、没有分隔行 = 不是表（§9.2 的识别条件与 Android 同一条）
        Assert.IsType<MarkdownParagraph>(Assert.Single(Markdown.Parse("| 服务 | 状态 |")));
    }

    [Theory]
    [InlineData("# 一级", 1, "一级")]
    [InlineData("###### 六级", 6, "六级")]
    public void HeadingKeepsItsLevelForTheRendererToMap(string source, int level, string text)
    {
        var heading = Assert.IsType<MarkdownHeading>(Assert.Single(Markdown.Parse(source)));

        Assert.Equal(level, heading.Level);
        Assert.Equal(text, heading.Text);
    }

    [Fact]
    public void HashWithoutASpaceIsJustText()
    {
        // 与 Android 的同一条：#1287 这种工单号很常见，认成语法就会把整行吃掉
        var paragraph = Assert.IsType<MarkdownParagraph>(Assert.Single(Markdown.Parse("#1287 还没修")));

        Assert.Equal("#1287 还没修", paragraph.Text);
    }

    [Fact]
    public void ConsecutiveQuoteLinesMergeIntoOneBlock()
    {
        var quote = Assert.IsType<MarkdownQuote>(Assert.Single(Markdown.Parse("> 第一行\n> 第二行")));

        Assert.Equal("第一行\n第二行", quote.Text);
    }

    [Fact]
    public void AnOrderedListItemStartsAnOrderedListAndABulletStartsAnother()
    {
        var ordered = Assert.IsType<MarkdownList>(Assert.Single(Markdown.Parse("1. 先做这个\n2. 再做这个")));
        Assert.True(ordered.Ordered);
        Assert.Equal(new[] { "先做这个", "再做这个" }, ordered.Items);

        var bullets = Assert.IsType<MarkdownList>(Assert.Single(Markdown.Parse("- 甲\n* 乙\n+ 丙")));
        Assert.False(bullets.Ordered);
        Assert.Equal(3, bullets.Items.Count);
    }

    [Fact]
    public void AListStopsWhereTheTextResumes()
    {
        var blocks = Markdown.Parse("- 项目一\n中间这句没有前缀\n- 项目二");

        Assert.Equal(3, blocks.Count);
        Assert.IsType<MarkdownList>(blocks[0]);
        Assert.IsType<MarkdownParagraph>(blocks[1]);
        Assert.IsType<MarkdownList>(blocks[2]);
    }

    [Fact]
    public void AHorizontalRuleIsNotSupportedAndBecomesPlainText()
    {
        // §9.2 的支持集里没有分割线，Android 也没实现：--- 就照原样当正文，两端一致比"更像 markdown"重要
        var paragraph = Assert.IsType<MarkdownParagraph>(Assert.Single(Markdown.Parse("---")));

        Assert.Equal("---", paragraph.Text);
    }

    [Fact]
    public void AnEmptyBodyStillRendersOneParagraphRatherThanNothing()
    {
        // 消息到了但气泡是空的，这条是验收里最丑的一种失败
        var blocks = Markdown.Parse(string.Empty);

        var paragraph = Assert.IsType<MarkdownParagraph>(Assert.Single(blocks));
        Assert.Equal(string.Empty, paragraph.Text);
    }

    [Fact]
    public void ABoldStitchingAcrossLinesIsOneBlockPerLineGroup()
    {
        // 段落是"连续的非语法行"，中间不空行就还是一段，换行保住
        var paragraph = Assert.IsType<MarkdownParagraph>(Assert.Single(Markdown.Parse("第一行\n第二行")));

        Assert.Equal("第一行\n第二行", paragraph.Text);
    }

    // ---- 内联 -------------------------------------------------------------------------------------

    [Fact]
    public void BoldAndInlineCodeBecomeStyledSpans()
    {
        var spans = Markdown.Inline("构建 **失败**，看 `build.log` 吧");

        Assert.Equal("构建 ", spans[0].Text);
        Assert.Equal(InlineStyle.Plain, spans[0].Style);
        Assert.Equal(("失败", InlineStyle.Bold), (spans[1].Text, spans[1].Style));
        Assert.Equal("，看 ", spans[2].Text);
        Assert.Equal(("build.log", InlineStyle.Code), (spans[3].Text, spans[3].Style));
        Assert.Equal(" 吧", spans[^1].Text);
    }

    [Fact]
    public void AnUnterminatedMarkerStaysLiteralText()
    {
        // 非贪婪 + 只认成对：落单的 ** 不猜，正文原样出来（这条克制的理由写在 Android 的注释里）
        var spans = Markdown.Inline("还没 **收尾");

        Assert.Equal("还没 **收尾", Assert.Single(spans).Text);
    }

    [Fact]
    public void ItalicIsNotPartOfTheSupportSet()
    {
        var spans = Markdown.Inline("这是 *斜体* 但不支持");

        Assert.Equal("这是 *斜体* 但不支持", string.Concat(spans.Select(s => s.Text)));
        Assert.All(spans, s => Assert.Equal(InlineStyle.Plain, s.Style));
    }

    [Fact]
    public void ABareUrlIsOverlaidOnTopOfWhateverStyleItAlreadyHas()
    {
        // Android 是先拼好明文、再在拼好的串上扫 URL，所以 **https://x.cn** 同时是粗体和链接，不是二选一
        var spans = Markdown.Inline("**https://x.cn** 值得点开");

        Assert.True(spans[0].Link);
        Assert.Equal(InlineStyle.Bold, spans[0].Style);
        Assert.Equal("https://x.cn", spans[0].Text);
        Assert.False(spans[1].Link);
        Assert.Equal(" 值得点开", spans[1].Text);
    }

    [Fact]
    public void ABareUrlStopsOnlyAtWhitespaceAndAngleQuote()
    {
        var spans = Markdown.Inline("日志在 https://ci.iamplus.cn/job/1287/console");

        Assert.Equal("日志在 ", spans[0].Text);
        Assert.False(spans[0].Link);
        Assert.Equal("https://ci.iamplus.cn/job/1287/console", spans[1].Text);
        Assert.True(spans[1].Link);
        // 裸 URL 的字符类只挡空白和 <>"'，中文句号会跟着进链接——Android 用的是同一份正则，
        // 两端一起"不够聪明"也比一端聪明好。这条钉的是"照抄"，不是"应该这样"。
        var withTrailing = Markdown.Inline("看 https://a.cn/x）。");
        Assert.Equal("https://a.cn/x）。", withTrailing[1].Text);
    }

    [Fact]
    public void AnEmptyInlineProducesNoRuns()
    {
        Assert.Empty(Markdown.Inline(string.Empty));
    }
}
