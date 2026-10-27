using System;
using System.Linq;
using System.Xml.Linq;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// 键盘与无障碍这一轮的闸口。屏幕上"只认鼠标"的那几处都换成了真控件，图标钮也都有了读屏念得出的名字。
/// 这些全按 XAML 断：漏一个 <c>AutomationProperties.Name</c>、把胶囊改回 Border、把 Esc 弄丢，
/// 编译期一件都不报，坏掉只会让键盘用户在走到那一步时才发现。
/// </summary>
public class AccessibilityTest
{
    private static readonly XNamespace X = "http://schemas.microsoft.com/winfx/2006/xaml";

    private const string Main = "MainWindow.xaml";
    private const string Settings = "Views/SettingsWindow.xaml";
    private const string UpdatePrompt = "Views/UpdatePromptWindow.xaml";

    private static XDocument Parse(string file) => XDocument.Parse(TokenFiles.Text(file));

    private static XElement ByName(XDocument doc, string name) => doc.Descendants()
        .Single(e => (string?)e.Attribute(X + "Name") == name);

    private static string? AccessibleName(XElement element) =>
        (string?)element.Attribute("AutomationProperties.Name");

    /// <summary>
    /// 三颗字形钮（设置 ⚙ / 返回 ← / 关闭提示 ×）的 Content 是私用区码位（XAML 里写成 <c>&amp;#xE713;</c>，
    /// 解析完就是一个字）：读屏照念就是一句"private use"，悬停也没字。名字与 tooltip 各给一份，
    /// 键盘用户与读屏才都有着落。
    /// </summary>
    [Fact]
    public void EveryGlyphOnlyButtonCarriesANameAndATooltip()
    {
        var glyphs = Parse(Main).Descendants()
            .Where(e => e.Name.LocalName == "Button" && (string?)e.Attribute("Content") is { } content
                        && content.Length == 1 && content[0] >= '\uE000' && content[0] <= '\uF8FF')
            .ToList();

        // 这一条同时也是"别再把字形钮当成带文字的钮"的锚：一颗都没有说明断言本身失效了
        Assert.NotEmpty(glyphs);
        foreach (var button in glyphs)
        {
            var name = AccessibleName(button);
            Assert.False(string.IsNullOrWhiteSpace(name),
                $"{button.Name.LocalName} 只拿字形当内容，读屏念不出它是什么");
            Assert.NotNull(button.Attribute("ToolTip"));
        }
    }

    /// <summary>
    /// §9.3 那颗"N 条新消息"原来是个只认 <c>MouseLeftButtonUp</c> 的 Border：Tab 不到、Enter 按不动。
    /// 收成按钮之后 Space/Enter、焦点框、禁用态全是白拿的——前提是它真的还是按钮，且还挂着胶囊样式。
    /// </summary>
    [Fact]
    public void TheNewBelowPillIsARealButton()
    {
        var pill = ByName(Parse(Main), "NewBelow");

        Assert.Equal("Button", pill.Name.LocalName);
        Assert.Equal("OnNewBelowClicked", (string?)pill.Attribute("Click"));
        Assert.Equal("{DynamicResource Button.Pill}", (string?)pill.Attribute("Style"));
        Assert.False(string.IsNullOrWhiteSpace(AccessibleName(pill)));
        // 它自己那行字（"3 条新消息"）由代码按量改，得还在
        Assert.Contains(pill.Elements(), e => (string?)e.Attribute(X + "Name") == "NewBelowText");
    }

    /// <summary>
    /// 提示条整条都能点走，但那对键盘用户等于不存在：右边那颗 × 才是他们唯一的出口。
    /// </summary>
    [Fact]
    public void TheNoticeStripHasAButtonForKeyboardUsers()
    {
        var close = ByName(Parse(Main), "NoticeCloseButton");

        Assert.Equal("Button", close.Name.LocalName);
        Assert.Equal("OnNoticeClosed", (string?)close.Attribute("Click"));
        Assert.False(string.IsNullOrWhiteSpace(AccessibleName(close)));
        Assert.NotNull(close.Attribute("ToolTip"));
    }

    /// <summary>设置页这三格原来只有旁边一行小字：读屏走过去只念出"编辑框"三个字。</summary>
    [Fact]
    public void TheSettingsInputsHaveAccessibleNames()
    {
        var doc = Parse(Settings);
        foreach (var name in new[] { "ThemeBox", "QuietStart", "QuietEnd" })
        {
            Assert.False(string.IsNullOrWhiteSpace(AccessibleName(ByName(doc, name))),
                $"{name} 在读屏里没有名字");
        }
    }

    /// <summary>
    /// 更新确认框原来只有一个出路（点"下载并安装"）：Esc 关不掉，键盘用户只能硬按。
    /// "稍后"挂 IsCancel 走 Esc；安装那颗刻意不挂 IsDefault——回车误触会直接开始下载。
    /// </summary>
    [Fact]
    public void TheUpdatePromptCanBeDismissedWithEscapeButNotEnteredInto()
    {
        var doc = Parse(UpdatePrompt);

        Assert.Equal("True", (string?)ByName(doc, "LaterButton").Attribute("IsCancel"));
        Assert.Null(ByName(doc, "InstallButton").Attribute("IsDefault"));
    }

    /// <summary>三块面板的名字：列表、拖拽手柄、时间线。读屏靠它区分"这是哪一栏"。</summary>
    [Fact]
    public void TheShellNamesItsThreePanes()
    {
        var doc = Parse(Main);
        foreach (var name in new[] { "ConversationList", "ListSplitter", "Timeline" })
        {
            Assert.False(string.IsNullOrWhiteSpace(AccessibleName(ByName(doc, name))),
                $"{name} 在读屏里只剩一个类型名");
        }
    }
}
