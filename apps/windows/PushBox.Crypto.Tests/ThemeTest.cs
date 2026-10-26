using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using PushBox.Windows.Themes;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// §9.1 的 token 面。两份字典是 <see cref="ThemeManager"/> 换顺序来切深浅色的，
/// 所以"键名完全一致"是它成立的前提：少一个键就意味着切到深色时那处颜色悄悄退回旧值。
/// 这里按文本读（不解析 XAML），因为要比的就是"两个文件写没写同一批键"。
/// </summary>
public static class TokenFiles
{
    private static readonly XNamespace X = "http://schemas.microsoft.com/winfx/2006/xaml";

    private static readonly Regex Used = new(
        @"\{DynamicResource\s+([^}]+)\}", RegexOptions.Compiled);

    private static readonly string Root = Path.Combine(AppContext.BaseDirectory,
        "..", "..", "..", "..", "PushBox.Windows");

    public static string Text(string file) => File.ReadAllText(Path.Combine(Root, file));

    /// <summary>按 XML 读而不是按正则读：这份文件是给人写的，属性顺序、注释、换行都不该影响断言。</summary>
    public static Dictionary<string, string> Read(string file) =>
        XDocument.Parse(Text(file)).Root!.Elements()
            .Where(e => e.Attribute(X + "Key") is not null && e.Attribute("Color") is not null)
            .ToDictionary(e => e.Attribute(X + "Key")!.Value,
                e => e.Attribute("Color")!.Value.ToUpperInvariant());

    /// <summary>两份字典里定义过的每一个键名，包括 Style 与 FontFamily。
    /// 窗口的 <c>{DynamicResource Text.Small}</c> 拿不到键时不报错，只是那一行悄悄退回默认字号，
    /// 所以"两边键名一致"这件事必须连样式一起断，而不是只断色值。</summary>
    public static List<string> AllKeys(string file) =>
        XDocument.Parse(Text(file)).Root!.Elements()
            .Where(e => e.Attribute(X + "Key") is not null)
            .Select(e => e.Attribute(X + "Key")!.Value)
            .ToList();

    /// <summary>各窗口 XAML 里用到的每一个 token 键名。设置窗与更新框也是同一套色，漏一个键就是漏一处反色。</summary>
    public static List<string> UsedKeys() => new[]
        {
            "MainWindow.xaml", "Views/FirstLaunchWindow.xaml", "Views/SettingsWindow.xaml",
            "Views/UpdatePromptWindow.xaml",
        }
        .SelectMany(file => Used.Matches(Text(file)).Cast<Match>())
        .Select(m => m.Groups[1].Value.Trim())
        .Distinct()
        .ToList();
}

public class ThemeTest
{
    private const string Light = "Themes/Tokens.xaml";
    private const string Dark = "Themes/Tokens.Dark.xaml";

    /// <summary>§9.1 表格的浅色那一列，逐格抄下来。
    /// 底色是分层的：页面 <c>Bg.Page</c> 最浅的一档灰，菜单与卡片 <c>Bg.Elevated</c> 是白、
    /// 会话列表 <c>Bg.SenderList</c> 再深一档——微信 PC 那三块本来就肉眼可分。
    /// 卡片必须比页面亮、分割线必须比两者都深：三者同色时那 1px 边框和菜单右边框都等于没画。</summary>
    private static readonly Dictionary<string, string> DocumentedLight = new()
    {
        ["Bg.Page"] = "#FAFAFA",
        ["Bg.Elevated"] = "#FFFFFF",
        ["Bg.Bubble.In"] = "#E8E8EA",
        ["Bg.SenderList"] = "#EEEFF0",
        // 选中行是比列表底深一档的灰（微信 PC 那档），整行染绿太吵；accent 只留在 3px 竖条上
        ["Bg.Selected"] = "#E2E2E2",
        ["Bg.AccentTint"] = "#E6F9EF",
        ["Fg.Primary"] = "#19191A",
        // 13px 次要字要落在页面/卡片/列表三种底上，4.5:1 的门槛由这个深度兜住
        ["Fg.Secondary"] = "#5F5F5F",
        // 主色同时当按钮底（白字 5.4:1）和正文色（页面底 5.1:1）
        ["Accent"] = "#0F7A4F",
        ["OnAccent"] = "#FFFFFF",
        ["Badge.Unread"] = "#F5222D",
        ["Divider"] = "#CFCFCF",
        ["State.Offline"] = "#FF8C00",
        ["Level.Error"] = "#F5222D",
        ["Level.Warning"] = "#FF8C00",
        ["Level.Info"] = "#757575",
        ["Level.Highlight"] = "#1A73E8",
        // 滚动条三态：静止最浅、悬停加深、拖动最深。轨道与箭头都不画，所以只有这三个值
        ["ScrollBar.Thumb"] = "#A1A1A2",
        ["ScrollBar.ThumbHover"] = "#888889",
        ["ScrollBar.ThumbDrag"] = "#6E6E6F",
    };

    /// <summary>§9.1 的深色那一列：整站反色且不碰纯黑。
    /// 抬升方向与浅色一致（卡片比页面亮），主色则要反过来变亮，压在它上面的字改用深墨绿。
    /// 滚动条的深浅顺序在这里反过来（静止最深、拖动最浅），因为深底上要靠"变亮"来表示被抓住。</summary>
    private static readonly Dictionary<string, string> DocumentedDark = new()
    {
        ["Bg.Page"] = "#1A1A1A",
        ["Bg.Elevated"] = "#2A2A2A",
        ["Bg.Bubble.In"] = "#2A2A2C",
        ["Bg.SenderList"] = "#1E1E1E",
        ["Bg.Selected"] = "#2E2E2E",
        ["Bg.AccentTint"] = "#223B2C",
        ["Fg.Primary"] = "#E0E0E0",
        ["Fg.Secondary"] = "#9E9E9E",
        // 深底上 #0A8F4A 只有 3.7:1，得提亮到 #3FBF7A（6.6:1）才能当正文色
        ["Accent"] = "#3FBF7A",
        ["OnAccent"] = "#08170E",
        ["Badge.Unread"] = "#FF4D4F",
        ["Divider"] = "#333333",
        ["State.Offline"] = "#FFA726",
        ["Level.Error"] = "#FF5252",
        ["Level.Warning"] = "#FFB74D",
        ["Level.Info"] = "#9E9E9E",
        ["Level.Highlight"] = "#1A73E8",
        ["ScrollBar.Thumb"] = "#6E6E6F",
        ["ScrollBar.ThumbHover"] = "#888889",
        ["ScrollBar.ThumbDrag"] = "#A1A1A2",
    };

    [Fact]
    public void TheTwoDictionariesCarryTheSameKeys()
    {
        var light = TokenFiles.Read(Light);
        var dark = TokenFiles.Read(Dark);
        // 缺任何一边：ThemeManager 换顺序时那一处颜色会留在上一份的值上，切完主题界面少半壁
        Assert.Equal(light.Keys.OrderBy(k => k), dark.Keys.OrderBy(k => k));
        // 色值之外的键（Style、FontFamily）同一条规矩：主窗口的字号档位也是靠换字典生效的
        Assert.Equal(TokenFiles.AllKeys(Light).OrderBy(k => k), TokenFiles.AllKeys(Dark).OrderBy(k => k));
    }

    [Fact]
    public void BothSidesAreTheDocumentedHexValues()
    {
        Assert.Equal(DocumentedLight, TokenFiles.Read(Light));
        Assert.Equal(DocumentedDark, TokenFiles.Read(Dark));
    }

    [Fact]
    public void DarkIsNotPureBlack()
    {
        // §9.1"降低纯黑使用"：#000000 一出现在底色里就意味着对比度过冲
        Assert.DoesNotContain("#000000", TokenFiles.Read(Dark).Values);
    }

    [Fact]
    public void TheHitHighlightIsTheSameBlueInBothThemes()
    {
        // §7.4 的命中色两端、两主题都只有一个值，Android 那边也是硬编码——它靠加粗而不是靠底色说话
        Assert.Equal("#1A73E8", TokenFiles.Read(Light)["Level.Highlight"]);
        Assert.Equal("#1A73E8", TokenFiles.Read(Dark)["Level.Highlight"]);
    }

    [Fact]
    public void EveryTokenTheWindowsReachForExists()
    {
        // 键名写错时 DynamicResource 不报错，只是那一处悄悄留着上一份值（或退成默认字号）——所以这条必须当场拦下
        var keys = new HashSet<string>(TokenFiles.AllKeys(Light));
        var missing = TokenFiles.UsedKeys().Where(k => !keys.Contains(k)).ToList();
        Assert.Empty(missing);
        Assert.NotEmpty(TokenFiles.UsedKeys());
    }

    [Fact]
    public void TheMainWindowReachesTheFontStylesThroughDynamicResource()
    {
        // 字号样式只在 App 级那两份 token 字典里，而 MainWindowTest 是在一个裸 STA 线程上把窗口建出来的
        // （没有 Application，也就没人换过字典）。同一种写法用 StaticResource 会当场抛 XamlParseException，
        // DynamicResource 只是暂时拿不到值——换主题时字号本来也不变，所以这一条只锁"窗口建得出来"。
        var staticText = new Regex("Style=\"\\{StaticResource\\s+Text\\.");
        var offenders = TokenFiles.Text("MainWindow.xaml").Split('\n')
            .Where(line => staticText.IsMatch(line)).ToList();
        Assert.Empty(offenders);
        Assert.Contains("Style=\"{DynamicResource Text.", TokenFiles.Text("MainWindow.xaml"));
    }

    [Fact]
    public void EveryStyleReferenceMatchesTheControlItIsPutOn()
    {
        // 带 TargetType="TextBlock" 的样式挂到 Button 上，编译一路绿灯，只在窗口加载那一刻抛
        // XamlParseException（实测两次：顶栏 ⚙ 拿了 Text.H2、返回 ← 拿了 Text.H3）。
        // 按 XML 逐元素判：样式引用常常和别的属性换行写在同一个元素上，按行匹配必然漏。
        var targets = StyleTargets();
        var reference = new Regex("\\{(?:Static|Dynamic)Resource\\s+([^}]+)\\}");
        var offenders = new List<string>();
        foreach (var file in new[] { "MainWindow.xaml", "Views/SettingsWindow.xaml",
                    "Views/FirstLaunchWindow.xaml", "Views/UpdatePromptWindow.xaml" })
        {
            foreach (var e in XDocument.Parse(TokenFiles.Text(file)).Root!.Descendants())
            {
                var match = reference.Match(e.Attribute("Style")?.Value ?? string.Empty);
                if (!match.Success) continue;
                var key = match.Groups[1].Value.Trim();
                // 只在 token 字典里定义的样式才查得到目标类型；本文件内的隐式样式没有键，也管不着
                if (!targets.TryGetValue(key, out var target)) continue;
                if (e.Name.LocalName == target) continue;
                offenders.Add($"{file}: <{e.Name.LocalName}> 拿了 TargetType={target} 的 {key}");
            }
        }

        Assert.Empty(offenders);
    }

    /// <summary>每一个样式键 → 它的 TargetType，来源是两份 token 字典加各窗口自己那一页资源。</summary>
    private static Dictionary<string, string> StyleTargets()
    {
        var x = XNamespace.Get("http://schemas.microsoft.com/winfx/2006/xaml");
        var map = new Dictionary<string, string>();
        foreach (var file in new[] { Light, Dark, "MainWindow.xaml", "Views/SettingsWindow.xaml",
                    "Views/FirstLaunchWindow.xaml", "Views/UpdatePromptWindow.xaml" })
        {
            foreach (var e in XDocument.Parse(TokenFiles.Text(file)).Root!.Descendants()
                         .Where(e => e.Name.LocalName == "Style" && e.Attribute(x + "Key") is not null))
            {
                var target = e.Attribute("TargetType")?.Value;
                if (target is null) continue;
                // TargetType 写成 "TextBlock" 或 "{x:Type TextBlock}" 都算，取最后一段
                map[e.Attribute(x + "Key")!.Value] =
                    target.TrimStart('{').Split(' ').Last().TrimEnd('}').Split('.').Last();
            }
        }

        return map;
    }

    [Fact]
    public void NothingInTheXamlHardcodesAColor()
    {
        var hex = new Regex("Color=\"#|Foreground=\"#|Background=\"#[0-9A-Fa-f]{3,8}");
        // 唯一允许的例外是头像圈里那个白色首字（§9.1 没给这个 token，两端也都写死白）
        var offenders = TokenFiles.Text("MainWindow.xaml")
            .Split('\n')
            .Where(line => hex.IsMatch(line) && !line.Contains("Foreground=\"White\""))
            .ToList();
        Assert.Empty(offenders);
    }

    [Fact]
    public void OnlyAnExplicitZeroMeansDark()
    {
        Assert.True(ThemeManager.IsDark(0));
        Assert.True(ThemeManager.IsDark(0L));
        Assert.True(ThemeManager.IsDark((short)0));
        Assert.False(ThemeManager.IsDark(1));
        Assert.False(ThemeManager.IsDark(null));
        // 读不到那个键（组策略删了那行）就当浅色，而不是把整站判成深色
        Assert.False(ThemeManager.IsDark("0"));
    }

    [Fact]
    public void TheLevelAccentsAreTheTwoDocumentedOnes()
    {
        Assert.Equal("Level.Error", PushBox.Windows.Views.TimeFormat.LevelAccent("error"));
        Assert.Equal("Level.Warning", PushBox.Windows.Views.TimeFormat.LevelAccent("warning"));
        // §9.1 的克制：info 什么都不给，于是竖条和小圆点都不会出现
        Assert.Null(PushBox.Windows.Views.TimeFormat.LevelAccent("info"));
        Assert.Null(PushBox.Windows.Views.TimeFormat.LevelAccent(null));
    }
}
