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

    /// <summary>渲染层在代码里取资源的两个调用：<c>Brush("键")</c> 与 <c>Font("键", 兜底族名)</c>。
    /// 只取第一个字符串参数——后面那支是字体族名，不是 token。</summary>
    private static readonly Regex CodeKey = new(
        @"\b(?:Brush|Font)\(""([^""]+)""", RegexOptions.Compiled);

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

    /// <summary>各窗口 XAML 里用到的每一个 token 键名。设置窗与更新框也是同一套色，漏一个键就是漏一处反色。
    /// 渲染层在代码里摸的那批键也算进来（<c>MarkdownStyle.FromResources</c>）：那边写错时
    /// <c>TryFindResource</c> 不抛异常，只给一支灰——屏幕上就是"某处颜色不对"，没有任何线索。</summary>
    public static List<string> UsedKeys() => new[]
        {
            "MainWindow.xaml", "Views/FirstLaunchWindow.xaml", "Views/SettingsWindow.xaml",
            "Views/UpdatePromptWindow.xaml",
        }
        .SelectMany(file => Used.Matches(Text(file)).Cast<Match>())
        .Select(m => m.Groups[1].Value.Trim())
        .Concat(CodeKey.Matches(Text("Controls/MarkdownView.cs")).Cast<Match>()
            .Select(m => m.Groups[1].Value))
        .Distinct()
        .ToList();
}

public class ThemeTest
{
    private const string Light = "Themes/Tokens.xaml";
    private const string Dark = "Themes/Tokens.Dark.xaml";
    /// <summary>两份 token 字典各自 merge 的共享层：字体族、字号样式、按钮体系、焦点框。
    /// 颜色才分深浅两份，样式只有一份——少这个文件，两份字典里的 DynamicResource 引用会全体落空。</summary>
    private const string Common = "Themes/Common.xaml";

    private static readonly XNamespace X = "http://schemas.microsoft.com/winfx/2006/xaml";

    /// <summary>§9.1 表格的浅色那一列，逐格抄下来。
    /// 底色是分层的：页面 <c>Bg.Page</c> 最浅的一档灰，菜单与卡片 <c>Bg.Elevated</c> 是白、
    /// 会话列表 <c>Bg.SenderList</c> 再深一档——微信 PC 那三块本来就肉眼可分。
    /// 卡片必须比页面亮、分割线必须比两者都深：三者同色时那 1px 边框和菜单右边框都等于没画。</summary>
    private static readonly Dictionary<string, string> DocumentedLight = new()
    {
        ["Bg.Page"] = "#FAFAFA",
        ["Bg.Elevated"] = "#FFFFFF",
        ["Bg.Bubble.In"] = "#E8E8EA",
        // 行内码与代码块的底：比气泡底深一档，同色时"码片"就没有边界
        ["Bg.Code"] = "#D8D8DC",
        // 气泡描边：比 Divider 浅一档，只够勾出轮廓
        ["Bg.Bubble.Border"] = "#D6D6D8",
        ["Bg.SenderList"] = "#EEEFF0",
        // 选中行是比列表底深一档的灰（微信 PC 那档），整行染绿太吵；accent 只留在 3px 竖条上
        ["Bg.Selected"] = "#E2E2E2",
        ["Bg.AccentTint"] = "#E6F9EF",
        // 正文拖选的底：独立一支偏蓝的浅底，不再复用上面那支淡绿
        ["Bg.TextSelection"] = "#BFE0FF",
        ["Fg.Primary"] = "#19191A",
        // 13px 次要字要落在页面/卡片/列表三种底上，4.5:1 的门槛由这个深度兜住
        ["Fg.Secondary"] = "#5F5F5F",
        // 主色同时当按钮底（白字 5.4:1）和正文色（页面底 5.1:1）
        ["Accent"] = "#0F7A4F",
        ["OnAccent"] = "#FFFFFF",
        ["Badge.Unread"] = "#F5222D",
        ["Divider"] = "#CFCFCF",
        ["State.Offline"] = "#FF8C00",
        // 连接中与离线分开：橙留给"断了要人管"，蓝留给"正在自己好"（#155FBD 在 #FAFAFA 上 5.9:1）
        ["State.Connecting"] = "#155FBD",
        ["Level.Error"] = "#F5222D",
        ["Level.Warning"] = "#FF8C00",
        ["Level.Info"] = "#757575",
        ["Level.Highlight"] = "#1A73E8",
        // 滚动条三态：静止最浅、悬停加深、拖动最深。轨道与箭头都不画，所以只有这三个值
        ["ScrollBar.Thumb"] = "#A1A1A2",
        ["ScrollBar.ThumbHover"] = "#888889",
        ["ScrollBar.ThumbDrag"] = "#6E6E6F",
        // 按钮三态：Primary 越按越深，Ghost（中性/图标钮）铺灰，Accent 描边钮只补 pressed（hover 复用 Bg.AccentTint），Danger 铺浅红
        ["Btn.Primary.Hover"] = "#0C6742",
        ["Btn.Primary.Pressed"] = "#0A5536",
        ["Btn.Ghost.Hover"] = "#EFEFF0",
        ["Btn.Ghost.Pressed"] = "#E2E2E4",
        ["Btn.Accent.Pressed"] = "#CFF3E1",
        ["Btn.Danger.Hover"] = "#FDECEC",
        ["Btn.Danger.Pressed"] = "#FAD9D9",
    };

    /// <summary>§9.1 的深色那一列：整站反色且不碰纯黑。
    /// 抬升方向与浅色一致（卡片比页面亮），主色则要反过来变亮，压在它上面的字改用深墨绿。
    /// 滚动条的深浅顺序在这里反过来（静止最深、拖动最浅），因为深底上要靠"变亮"来表示被抓住。</summary>
    private static readonly Dictionary<string, string> DocumentedDark = new()
    {
        ["Bg.Page"] = "#1A1A1A",
        ["Bg.Elevated"] = "#2A2A2A",
        ["Bg.Bubble.In"] = "#2A2A2C",
        // 深色下代码底反而比气泡亮一档：抬升方向与浅色相反
        ["Bg.Code"] = "#3A3A3E",
        // 气泡描边同理走"变亮"这一侧
        ["Bg.Bubble.Border"] = "#3C3C3E",
        ["Bg.SenderList"] = "#1E1E1E",
        ["Bg.Selected"] = "#2E2E2E",
        ["Bg.AccentTint"] = "#223B2C",
        // 深色那一边同理偏蓝，而且要压得住 #E0E0E0 的正文
        ["Bg.TextSelection"] = "#2F5C8E",
        ["Fg.Primary"] = "#E0E0E0",
        ["Fg.Secondary"] = "#9E9E9E",
        // 深底上 #0A8F4A 只有 3.7:1，得提亮到 #3FBF7A（6.6:1）才能当正文色
        ["Accent"] = "#3FBF7A",
        ["OnAccent"] = "#08170E",
        ["Badge.Unread"] = "#FF4D4F",
        ["Divider"] = "#333333",
        ["State.Offline"] = "#FFA726",
        // 深底上连接中的蓝要变亮：原始 #1A73E8 在 #1A1A1A 上只有 3.9:1，#5B9BEF 是 6.1:1
        ["State.Connecting"] = "#5B9BEF",
        ["Level.Error"] = "#FF5252",
        ["Level.Warning"] = "#FFB74D",
        ["Level.Info"] = "#9E9E9E",
        ["Level.Highlight"] = "#1A73E8",
        ["ScrollBar.Thumb"] = "#6E6E6F",
        ["ScrollBar.ThumbHover"] = "#888889",
        ["ScrollBar.ThumbDrag"] = "#A1A1A2",
        // 深色下抬升方向反过来：Primary 越按越亮，Ghost 抬灰，Danger 铺暗红
        ["Btn.Primary.Hover"] = "#55C98B",
        ["Btn.Primary.Pressed"] = "#6BD39B",
        ["Btn.Ghost.Hover"] = "#333334",
        ["Btn.Ghost.Pressed"] = "#3D3D3E",
        ["Btn.Accent.Pressed"] = "#2A4A36",
        ["Btn.Danger.Hover"] = "#3B2325",
        ["Btn.Danger.Pressed"] = "#4C2A2C",
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

    /// <summary>
    /// 行内码与代码块的底必须与气泡底分开一档——两端同色时"码片"根本没有边界，
    /// 屏幕上看起来就是"Markdown 没上样式"（2026-10-26 之前 PC 的实际症状）。
    /// </summary>
    [Fact]
    public void CodeHasItsOwnBackgroundAPartFromTheBubble()
    {
        var light = TokenFiles.Read(Light);
        var dark = TokenFiles.Read(Dark);
        Assert.NotEqual(light["Bg.Bubble.In"], light["Bg.Code"]);
        Assert.NotEqual(dark["Bg.Bubble.In"], dark["Bg.Code"]);
        // 浅色向下压（码片比气泡深）、深色向上抬（更"浮起来"就更亮）；方向反了同样等于没画
        Assert.True(Luminance(light["Bg.Code"]) < Luminance(light["Bg.Bubble.In"]));
        Assert.True(Luminance(dark["Bg.Code"]) > Luminance(dark["Bg.Bubble.In"]));
    }

    private static double Luminance(string hex)
    {
        var r = Convert.ToInt32(hex.Substring(1, 2), 16);
        var g = Convert.ToInt32(hex.Substring(3, 2), 16);
        var b = Convert.ToInt32(hex.Substring(5, 2), 16);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /// <summary>
    /// 正文拖选的高亮底要同时满足两件事，缺一件就是"选了看不出选了哪里"：
    /// ① 正文色压上去够得着 4.5:1（WCAG AA，选中期间字还要能读）；
    /// ② 与底色拉开色相——判据取"蓝减红 ≥ 0x20"，因为它专门拦住淡绿/淡灰那一族：
    /// 原来复用 <c>Bg.AccentTint</c>（浅 #E6F9EF、深 #223B2C）时这个差只有 9 和 10，
    /// 和气泡底只差一档色相偏移，肉眼分不出边界（2026-10-27 的实测抱怨）。
    /// </summary>
    [Fact]
    public void TheDragSelectionBackgroundStaysReadableOverBodyText()
    {
        foreach (var file in new[] { Light, Dark })
        {
            var tokens = TokenFiles.Read(file);
            var selection = tokens["Bg.TextSelection"];
            var ratio = Contrast(selection, tokens["Fg.Primary"]);
            Assert.True(ratio >= 4.5, $"{file}: 正文压在选择底上只有 {ratio:0.0}:1");
            var blueMinusRed = Convert.ToInt32(selection.Substring(5, 2), 16)
                             - Convert.ToInt32(selection.Substring(1, 2), 16);
            Assert.True(blueMinusRed >= 0x20, $"{file}: 选择底没跟底色拉开色相，蓝减红只有 {blueMinusRed}");
        }
    }

    /// <summary>WCAG 的相对亮度：三通道先线性化再加权。上面的 <see cref="Luminance"/> 直接按 0-255 加权，
    /// 只够比"谁更深谁更浅"，够不着对比度门槛。</summary>
    private static double Contrast(string a, string b)
    {
        var la = RelativeLuminance(a);
        var lb = RelativeLuminance(b);
        return (Math.Max(la, lb) + 0.05) / (Math.Min(la, lb) + 0.05);
    }

    private static double RelativeLuminance(string hex)
    {
        static double Channel(string hex, int at)
        {
            var c = Convert.ToInt32(hex.Substring(at, 2), 16) / 255.0;
            return c <= 0.03928 ? c / 12.92 : Math.Pow((c + 0.055) / 1.055, 2.4);
        }

        return 0.2126 * Channel(hex, 1) + 0.7152 * Channel(hex, 3) + 0.0722 * Channel(hex, 5);
    }

    [Fact]
    public void EveryTokenTheWindowsReachForExists()
    {
        // 键名写错时 DynamicResource 不报错，只是那一处悄悄留着上一份值（或退成默认字号）——所以这条必须当场拦下。
        // 样式与字体族收在 Common.xaml，色值在浅色字典里：两边合起来才是窗口能拿到的全部键。
        var keys = new HashSet<string>(TokenFiles.AllKeys(Light));
        keys.UnionWith(TokenFiles.AllKeys(Common));
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
                var tag = e.Name.LocalName;
                if (tag == target) continue;
                // 派生类照样套得上基类的样式（TargetType 只要求"是它或它的子类"），标签名不同不算漂移
                if (DerivedFrom.TryGetValue(tag, out var baseTag) && baseTag == target) continue;
                offenders.Add($"{file}: <{tag}> 拿了 TargetType={target} 的 {key}");
            }
        }

        Assert.Empty(offenders);
    }

    /// <summary>本项目自己写的控件 → 它的 WPF 基类。只有列在这里的配对才被上面那条放行，
    /// 所以它不是"标签名不认识就跳过"的口子。</summary>
    private static readonly Dictionary<string, string> DerivedFrom = new()
    {
        // 会话摘要那一格：Emoji.Wpf 的 TextBlock.Text 不是依赖属性、绑不上，所以自己写了一个带 Source DP 的
        ["EmojiTextBlock"] = "TextBlock"
    };

    /// <summary>每一个样式键 → 它的 TargetType，来源是共享样式层、两份 token 字典加各窗口自己那一页资源。</summary>
    private static Dictionary<string, string> StyleTargets()
    {
        var x = XNamespace.Get("http://schemas.microsoft.com/winfx/2006/xaml");
        var map = new Dictionary<string, string>();
        foreach (var file in new[] { Common, Light, Dark, "MainWindow.xaml", "Views/SettingsWindow.xaml",
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

    /// <summary>
    /// 三档的换算是纯函数，"跟随系统"那一档才去看注册表。固定浅色遇到深色系统必须仍是浅色——
    /// 这里钉住的是这件事，免得以后把 <see cref="ThemeManager.Apply"/> 的入参写回 <c>SystemPrefersDark()</c>。
    /// </summary>
    [Fact]
    public void AChosenLightOrDarkIgnoresWhatTheSystemSays()
    {
        Assert.False(ThemeManager.DarkFor(ThemeChoice.Light, systemPrefersDark: true));
        Assert.False(ThemeManager.DarkFor(ThemeChoice.Light, systemPrefersDark: false));
        Assert.True(ThemeManager.DarkFor(ThemeChoice.Dark, systemPrefersDark: false));
        Assert.True(ThemeManager.DarkFor(ThemeChoice.Dark, systemPrefersDark: true));
        Assert.True(ThemeManager.DarkFor(ThemeChoice.System, systemPrefersDark: true));
        Assert.False(ThemeManager.DarkFor(ThemeChoice.System, systemPrefersDark: false));
    }

    /// <summary>
    /// 设置页新加的两处：主题下拉与关于页的日志入口。下拉不许自带背景色（那会把深色下的弹层钉成浅色），
    /// 日志路径要能换行（路径很长），按钮与提示各自走已有的样式档，不另开一套。
    /// </summary>
    [Fact]
    public void TheThemePickerAndTheLogEntryUseExistingTokens()
    {
        var doc = XDocument.Parse(TokenFiles.Text("Views/SettingsWindow.xaml"));
        XElement ByName(string name) => doc.Descendants()
            .Single(e => (string?)e.Attribute(X + "Name") == name);

        var theme = ByName("ThemeBox");
        Assert.Equal("ComboBox", theme.Name.LocalName);
        Assert.Null(theme.Attribute("Background"));
        Assert.Null(theme.Attribute("Foreground"));
        Assert.Null(theme.Attribute("Style"));

        var path = ByName("LogPathText");
        Assert.Equal("{DynamicResource Text.Code}", (string?)path.Attribute("Style"));
        Assert.Equal("Wrap", (string?)path.Attribute("TextWrapping"));
        Assert.Equal("{DynamicResource Fg.Secondary}", (string?)path.Attribute("Foreground"));

        Assert.Equal("{DynamicResource Button.Secondary}", (string?)ByName("OpenLogButton").Attribute("Style"));
        // 打不开目录时要说一句，默认收起（与静默时段那行同一个口径）
        var hint = ByName("LogHint");
        Assert.Equal("Collapsed", (string?)hint.Attribute("Visibility"));
        Assert.Equal("{DynamicResource Level.Error}", (string?)hint.Attribute("Foreground"));
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

    /// <summary>
    /// §9.2 的字号档位只有六档。设置页原来每个 TextBlock 自带 FontSize（还夹着 14/11），
    /// 换字典时字号不跟着走，也没法一眼看出哪一档是哪一档；这里钉住"再也别写回字面量"。
    /// </summary>
    [Fact]
    public void EveryTextBlockInSettingsUsesATypeScaleStyle()
    {
        var doc = XDocument.Parse(TokenFiles.Text("Views/SettingsWindow.xaml"));
        var offenders = doc.Descendants()
            .Where(e => e.Name.LocalName == "TextBlock")
            .Where(e => e.Attribute("Style") is null || e.Attribute("FontSize") is not null)
            .Select(e => (string?)e.Attribute("Text") ?? (string?)e.Attribute(X + "Name") ?? "(无文本)")
            .ToList();

        Assert.Empty(offenders);
    }

    /// <summary>
    /// 静默时段填歪了会被回填成原值——没有这行提示，用户看到的只是"我敲的 25:00 自己变回去了"。
    /// 钉住它确实在页面里、默认收起、走 error 那一档颜色（§9.3 的行内口径：说错话用红，不弹框）。
    /// </summary>
    [Fact]
    public void ABadQuietTimeHasSomewhereToSaySo()
    {
        var doc = XDocument.Parse(TokenFiles.Text("Views/SettingsWindow.xaml"));
        var hint = doc.Descendants().Single(e =>
            e.Name.LocalName == "TextBlock" && (string?)e.Attribute(X + "Name") == "QuietHint");

        Assert.Equal("Collapsed", (string?)hint.Attribute("Visibility"));
        Assert.Equal("{DynamicResource Text.Label}", (string?)hint.Attribute("Style"));
        Assert.Equal("{DynamicResource Level.Error}", (string?)hint.Attribute("Foreground"));
        Assert.Equal("Wrap", (string?)hint.Attribute("TextWrapping"));
    }

    /// <summary>
    /// 输入框与下拉框要自带模板：默认模板的 hover/焦点描边是系统浅蓝，下拉弹层在深色下是系统白底，
    /// 三态与弹层都没法靠 token 换。这里钉的是"模板里真写了"而不是"看起来差不多"。
    /// </summary>
    [Fact]
    public void TheSettingsInputsCarryTheirOwnThemedTemplate()
    {
        // 输入框那三份现在是设置页与更新确认框共用的一本（Themes/Inputs.xaml）：两处各自养一份模板，
        // 深色下必定一半对一半错。列表仍只有设置页在用，还留在它自己那一页
        var inputs = XDocument.Parse(TokenFiles.Text("Themes/Inputs.xaml"));
        var settings = XDocument.Parse(TokenFiles.Text("Views/SettingsWindow.xaml"));
        string Template(XDocument doc, string target) => doc.Descendants()
            .Single(e => e.Name.LocalName == "Style"
                         && (string?)e.Attribute("TargetType") == target
                         && e.Attribute(X + "Key") is null)
            .ToString();

        var box = Template(inputs, "TextBox");
        Assert.Contains("IsMouseOver", box);
        Assert.Contains("IsKeyboardFocusWithin", box);
        Assert.Contains("IsEnabled", box);
        Assert.Contains("PART_ContentHost", box);

        // 弹层底色、选项高亮也在模板里，不能让下拉那一层落回系统白
        var combo = Template(inputs, "ComboBox");
        Assert.Contains("PART_Popup", combo);
        Assert.Contains("Bg.Elevated", combo);
        Assert.Contains("IsKeyboardFocusWithin", combo);
        var item = Template(inputs, "ComboBoxItem");
        Assert.Contains("IsHighlighted", item);

        // 两处列表（频道/设备）共用这一份：条目是卡片，悬停与选中都走 token，不再是系统蓝条
        var list = Template(settings, "ListBox");
        Assert.Contains("ItemsPresenter", list);
        var row = Template(settings, "ListBoxItem");
        Assert.Contains("IsMouseOver", row);
        Assert.Contains("IsSelected", row);
        Assert.Contains("Bg.AccentTint", row);
        Assert.Contains("Bg.Elevated", row);
    }

    /// <summary>
    /// 复选框与滚动条一样是整个控制台共用的隐式样式：默认模板在深色下是系统白底 + 系统蓝勾。
    /// 首启原来那份 <c>AccentCheck</c> 已收进 App.xaml，这里钉"它确实在 App 级、且带悬停与勾选两态"。
    /// </summary>
    [Fact]
    public void TheCheckboxIsThemedOnceForTheWholeApp()
    {
        var doc = XDocument.Parse(TokenFiles.Text("App.xaml"));
        var style = doc.Descendants().Single(e => e.Name.LocalName == "Style"
            && (string?)e.Attribute("TargetType") == "CheckBox" && e.Attribute(X + "Key") is null);
        var text = style.ToString();
        Assert.Contains("IsMouseOver", text);
        Assert.Contains("IsChecked", text);
        Assert.Contains("OnAccent", text);

        // 各窗不再自养第二份勾选框模板
        Assert.DoesNotContain("AccentCheck", TokenFiles.Text("Views/FirstLaunchWindow.xaml"));
        Assert.DoesNotContain("TargetType=\"CheckBox\"", TokenFiles.Text("Views/SettingsWindow.xaml"));
    }

    /// <summary>
    /// §9.3 的长文本规则：名称与标题一行省略、摘要最多两行且末行省略。
    /// 名称原来塞在横向 StackPanel 里，按内容取宽——TextTrimming 永远不触发，长名字会把右边的时间挤出去。
    /// </summary>
    [Fact]
    public void LongConversationTextTrimsInsteadOfPushingTheRowWide()
    {
        var doc = XDocument.Parse(TokenFiles.Text("MainWindow.xaml"));
        XElement ByBinding(string tag, string property, string binding) => doc.Descendants()
            .Single(e => e.Name.LocalName == tag && (string?)e.Attribute(property) == binding);

        var name = ByBinding("TextBlock", "Text", "{Binding DisplayName}");
        Assert.Equal("CharacterEllipsis", (string?)name.Attribute("TextTrimming"));
        Assert.Equal("{Binding DisplayName}", (string?)name.Attribute("ToolTip"));
        // 横排 StackPanel 里 TextTrimming 是死代码：那条路必须已经改成星号列
        Assert.DoesNotContain(name.Ancestors(), a =>
            a.Name.LocalName == "StackPanel" && (string?)a.Attribute("Orientation") == "Horizontal");

        // 摘要走的是自己那个 EmojiTextBlock：文本挂在 Source（真依赖属性）上，不是 Text
        var summary = ByBinding("EmojiTextBlock", "Source", "{Binding Summary}");
        Assert.Equal("Wrap", (string?)summary.Attribute("TextWrapping"));
        Assert.Equal("CharacterEllipsis", (string?)summary.Attribute("TextTrimming"));
        // Text.Small 行高 18：36 就是两行
        Assert.Equal("36", (string?)summary.Attribute("MaxHeight"));

        var title = doc.Descendants().Single(e =>
            e.Name.LocalName == "TextBlock" && (string?)e.Attribute(X + "Name") == "ConversationTitle");
        Assert.Equal("CharacterEllipsis", (string?)title.Attribute("TextTrimming"));
        Assert.NotNull(title.Attribute("ToolTip"));
    }

    /// <summary>会话行原来把 FocusVisualStyle 置成 null，键盘走过去看不出焦点停在哪。</summary>
    [Fact]
    public void TheConversationRowKeepsAKeyboardFocusRing()
    {
        var doc = XDocument.Parse(TokenFiles.Text("MainWindow.xaml"));
        var row = doc.Descendants().Single(e =>
            e.Name.LocalName == "Style" && (string?)e.Attribute(X + "Key") == "ConversationRowStyle");
        var focus = row.Elements().Single(e =>
            e.Name.LocalName == "Setter" && (string?)e.Attribute("Property") == "FocusVisualStyle");

        Assert.Equal("{DynamicResource Focus.Visual}", (string?)focus.Attribute("Value"));
        // 焦点框本身也得在字典里（DynamicResource 拿不到键时不会报错，只是又变回"看不见"）。
        // 样式层只有 Common.xaml 一份，两份 token 字典 merge 它，键在 Common 里就等于两边都在
        Assert.Contains("Focus.Visual", TokenFiles.AllKeys("Themes/Common.xaml"));
    }

    /// <summary>
    /// §9.4 的按钮体系：六个键都收在 Common.xaml（两份 token 字典 merge 同一份，样式不分深浅色），
    /// 且每一个都带齐 hover/pressed/disabled 三个触发器与焦点框。
    /// 少一个键那一面就悄悄退回 WPF 默认灰钮，少一个触发器那只按钮就没有按压反馈——两者编译期都不报错，
    /// 要到用户面前才看得出来。
    /// </summary>
    [Fact]
    public void EveryButtonStyleCarriesAllThreeStatesOnBothSides()
    {
        var keys = new[] { "Button.Primary", "Button.Secondary", "Button.Accent", "Button.Danger", "Button.Icon", "Button.Pill" };
        var doc = XDocument.Parse(TokenFiles.Text(Common));
        foreach (var key in keys)
        {
            var style = doc.Root!.Elements()
                .SingleOrDefault(e => e.Name.LocalName == "Style" && (string?)e.Attribute(X + "Key") == key);
            Assert.True(style is not null, $"Common.xaml: 缺 {key}");
            var markup = style!.ToString();
            Assert.Contains("IsMouseOver", markup);
            Assert.Contains("IsPressed", markup);
            Assert.Contains("IsEnabled", markup);
            Assert.Contains("Focus.Visual", markup);
        }

        // 图标字体键也在：⚙/←/✅/⚠️ 已换成 MDL2 字形，键丢了那几处就退回方块字
        Assert.Contains("Font.Icon", TokenFiles.AllKeys(Common));
    }
}
