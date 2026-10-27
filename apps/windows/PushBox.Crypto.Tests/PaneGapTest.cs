using System.Globalization;
using System.Linq;
using System.Text.RegularExpressions;
using System.Windows;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// 「头像离左侧发送者列表太远」那一段空白带（2026-10-27 用户报的）。它不是一个数，是三段相加：
/// 会话行的右边距 + 拖拽手柄那一列 + 时间线容器的左内边距。三段各自看着都合理（16/4/12），
/// 加起来 32px 就成了一整条说不清属于谁的灰色空白——所以这里断的是<b>加起来那个数</b>，
/// 任何一段被单独改动都会当场红，而不是等下一次人眼扫过去。
/// </summary>
public class PaneGapTest
{
    private static readonly string Xaml = TokenFiles.Text("MainWindow.xaml");

    private static readonly Regex RowRoot = new(
        @"ConversationRowTemplate""\s*>\s*<Grid Margin=""([^""]+)""", RegexOptions.Compiled);

    private static readonly Regex SplitterColumn = new(
        @"GridSplitter x:Name=""ListSplitter"" Grid\.Column=""(\d+)""", RegexOptions.Compiled);

    private static readonly Regex ColumnDefinitions = new(
        @"<Grid.ColumnDefinitions>(.*?)</Grid.ColumnDefinitions>",
        RegexOptions.Compiled | RegexOptions.Singleline);

    private static readonly Regex OneColumn = new(
        @"<ColumnDefinition Width=""([^""]+)""", RegexOptions.Compiled);

    private static readonly Regex TimelineScroll = new(
        @"x:Name=""TimelineScroll""[^>]*Padding=""([^""]+)""", RegexOptions.Compiled);

    [Fact]
    public void TheBandBetweenTheListAndTheAvatarIsTwentyPixels()
    {
        var row = Read(RowRoot, "会话行根节点的 Margin");
        var timeline = Read(TimelineScroll, "时间线滚动容器的 Padding");

        // 8 + 4 + 8：三段都落在间距表的档上（{4,8,12,16}），合起来比原来的 16+4+12 少 12px
        Assert.Equal(20d, row.Right + HandleWidth() + timeline.Left);
    }

    /// <summary>手柄那一列的宽度既是列表与对话区之间那条线，也是它的命中区。
    /// 按 <c>GridSplitter</c> 自己声明的 <c>Grid.Column</c> 去那四列里取，不数注释也不认书写顺序。</summary>
    private static double HandleWidth()
    {
        var column = int.Parse(SplitterColumn.Match(Xaml).Groups[1].Value, CultureInfo.InvariantCulture);
        // 四列布局认得出来：它是唯一含 60px 菜单栏的那一组定义
        var pane = ColumnDefinitions.Matches(Xaml).Cast<Match>()
            .Single(m => m.Groups[1].Value.Contains("Width=\"60\""));
        var widths = OneColumn.Matches(pane.Groups[1].Value).Cast<Match>()
            .Select(m => m.Groups[1].Value)
            .ToList();
        // 第四列是 "*"（对话区吃掉剩余），所以按序号取到的这一列一定是像素值
        return double.Parse(widths[column], CultureInfo.InvariantCulture);
    }

    private static Thickness Read(Regex regex, string what)
    {
        var match = regex.Match(Xaml);
        Assert.True(match.Success, $"MainWindow.xaml 里找不到{what}，这一条的锚点没了");
        var parts = match.Groups[1].Value.Split(',')
            .Select(part => double.Parse(part.Trim(), CultureInfo.InvariantCulture))
            .ToArray();
        return parts.Length switch
        {
            1 => new Thickness(parts[0]),
            2 => new Thickness(parts[0], parts[1], parts[0], parts[1]),
            4 => new Thickness(parts[0], parts[1], parts[2], parts[3]),
            _ => throw new Xunit.Sdk.XunitException($"{what} 写成了看不懂的一串：{match.Groups[1].Value}"),
        };
    }
}
