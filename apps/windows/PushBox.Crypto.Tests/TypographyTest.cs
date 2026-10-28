using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text.RegularExpressions;
using System.Threading;
using System.Xml.Linq;
using PushBox.Windows.Controls;
using PushBox.Windows.Update;
using PushBox.Windows.Views;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// §9.2 的字号那一面。规范里只有六档字号，每一档有自己的行高；对齐 Android 与 PC 靠的不是
/// "两边都记得写 15"，而是把这六个数收在 <see cref="MarkdownStyle"/> 的常量里，XAML 一律
/// <c>{x:Static}</c> 引用。所以这里断的是"没有第二个数偷偷冒出来"，而不是"某个数字是否等于某个数字"。
/// </summary>
public class TypographyTest
{
    private static readonly XNamespace X = "http://schemas.microsoft.com/winfx/2006/xaml";

    private static readonly string Root = Path.Combine(AppContext.BaseDirectory,
        "..", "..", "..", "..", "PushBox.Windows");

    /// <summary>字号样式只有一份（浅深两本 token 字典各自 merge 它），不用再扫两份对比。</summary>
    private static readonly string[] Dictionaries = { "Themes/Common.xaml" };

    private static readonly string[] WindowFiles =
    {
        "MainWindow.xaml", "App.xaml", "Views/FirstLaunchWindow.xaml",
        "Views/SettingsWindow.xaml", "Views/UpdatePromptWindow.xaml",
    };

    /// <summary>§9.2 的字号 → 行高对照表。1.53× 缩放后取整：20/28、18/25、16/22、15/23、13/18、12/16。</summary>
    private static readonly Dictionary<double, double> Spec = new()
    {
        [20] = 28,
        [18] = 25,
        [16] = 22,
        [15] = 23,
        [13] = 18,
        [12] = 16,
    };

    private static readonly Regex StaticRef = new(
        @"\{x:Static\s+controls:MarkdownStyle\.(\w+)\}", RegexOptions.Compiled);

    /// <summary>XAML 里内联写的字号（<c>FontSize="13"</c>）。引 token 的写法不以数字开头，扫不到。</summary>
    private static readonly Regex InlineXamlSize = new(@"FontSize\s*=\s*""(\d)", RegexOptions.Compiled);

    /// <summary>C# 里内联写的字号（<c>FontSize = 13</c>）。</summary>
    private static readonly Regex InlineCodeSize = new(@"FontSize\s*=\s*\d", RegexOptions.Compiled);

    [Fact]
    public void EveryTextStyleIsOnTheSixStepScaleAndCarriesItsLineHeight()
    {
        var offenders = new List<string>();
        foreach (var file in Dictionaries)
        {
            foreach (var style in TextStyles(file))
            {
                var key = (string)style.Attribute(X + "Key")!;
                var size = Number(file, key, "FontSize");
                var line = Number(file, key, "LineHeight");
                if (!Spec.TryGetValue(size, out var documented))
                    offenders.Add($"{file}: {key} 用了规范外的字号 {size}");
                else if (line != documented)
                    offenders.Add($"{file}: {key} 是 {size} 号字，行高应为 {documented}，实际 {line}");
            }
        }

        Assert.Empty(offenders);
    }

    /// <summary>
    /// 六档之外不许再出现第七个数。设置页原来夹着 14 和 11，顶栏图标用 18、返回箭头用 16，
    /// 这些数散在四个文件里，换主题也不会跟着走——现在全部指向 <see cref="MarkdownStyle"/>。
    /// </summary>
    [Fact]
    public void NoWindowOrControlWritesANumericFontSize()
    {
        var offenders = new List<string>();

        foreach (var file in WindowFiles.Concat(new[] { "Themes/Common.xaml" }))
        {
            var lines = File.ReadAllLines(Path.Combine(Root, file));
            for (var i = 0; i < lines.Length; i++)
                if (InlineXamlSize.IsMatch(lines[i]))
                    offenders.Add($"{file}:{i + 1} {lines[i].Trim()}");
        }

        foreach (var file in SourceFiles())
        {
            var lines = File.ReadAllLines(file);
            for (var i = 0; i < lines.Length; i++)
                if (InlineCodeSize.IsMatch(lines[i]))
                    offenders.Add($"{Path.GetFileName(file)}:{i + 1} {lines[i].Trim()}");
        }

        Assert.Empty(offenders);
    }

    /// <summary>
    /// 聊天气泡里的标题走的是渲染层自己那两档（<see cref="MarkdownView.HeadingSize"/> /
    /// <see cref="MarkdownView.HeadingLine"/>），跟 XAML 的 token 样式是两条路，
    /// 所以同一张表要在这里再断一次：原来四个级别共用正文行高，20 号字的 H1 只剩 1.15×。
    /// </summary>
    [Fact]
    public void ChatHeadingsFollowTheSameTable()
    {
        for (var level = 1; level <= 6; level++)
        {
            var size = MarkdownView.HeadingSize(level);
            Assert.True(Spec.ContainsKey(size), $"{level} 级标题用了规范外的字号 {size}");
            Assert.Equal(Spec[size], MarkdownView.HeadingLine(level));
        }
    }

    /// <summary>窗口级的字体族也得走 token：四个窗口各写一遍 "Segoe UI, Microsoft YaHei UI"，
    /// 改字体就要改四处，漏一处那两个窗口的正文就换了字形。</summary>
    [Fact]
    public void EveryWindowTakesItsFontFamilyFromTheToken()
    {
        foreach (var file in WindowFiles.Where(f => f != "App.xaml"))
        {
            var text = File.ReadAllText(Path.Combine(Root, file));
            Assert.DoesNotContain("FontFamily=\"Segoe UI", text);
            Assert.Contains("FontFamily=\"{DynamicResource Font.UI}\"", text);
        }
    }

    [Fact]
    public void TheSixStepsCoverBothTheStyleSheetAndTheChatBody()
    {
        // 常量本身也要在这张表上——XAML 引的是它，不是反过来
        Assert.Equal(15, MarkdownStyle.BodySize);
        Assert.Equal(13, MarkdownStyle.SmallSize);
        Assert.Equal(12, MarkdownStyle.LabelSize);
        Assert.Equal(20, MarkdownStyle.H1Size);
        Assert.Equal(18, MarkdownStyle.H2Size);
        Assert.Equal(16, MarkdownStyle.H3Size);

        var painted = TextStyles("Themes/Common.xaml")
            .Select(style => Number("Themes/Common.xaml", (string)style.Attribute(X + "Key")!, "FontSize"))
            .Distinct()
            .OrderBy(size => size)
            .ToList();
        Assert.Equal(Spec.Keys.OrderBy(size => size), painted);
    }

    /// <summary>
    /// 更新确认框只在"有新版本"时才出现，四个窗口里只有它没有别的用例会去构造：
    /// 字号那几处 <c>{x:Static}</c> 若拼错，就变成到用户面前才炸。这里在 STA 线程上把它建出来一次。
    /// </summary>
    [Fact]
    public void TheUpdatePromptLoadsTheSizesItReferences()
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var release = new GitHubRelease("v9.9.9", "9.9.9", "修了几处显示问题",
                    "https://example.invalid/PushBox-Setup.exe", "PushBox-Setup.exe", 1024);
                var window = new UpdatePromptWindow(new UpdateModel(), release, null);
                Assert.True(window.ProbeNotesVisible);
                Assert.True(window.ProbeInstallEnabled);
                window.Close();
            }
            catch (Exception e)
            {
                failure = e;
            }
            finally
            {
                System.Windows.Threading.Dispatcher.CurrentDispatcher.InvokeShutdown();
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;
        thread.Start();
        thread.Join(TimeSpan.FromSeconds(20));

        Assert.Null(failure);
    }

    private static IEnumerable<XElement> TextStyles(string file) =>
        XDocument.Parse(TokenFiles.Text(file)).Root!.Elements()
            .Where(e => e.Name.LocalName == "Style")
            .Where(e => ((string?)e.Attribute(X + "Key") ?? string.Empty).StartsWith("Text.", StringComparison.Ordinal));

    /// <summary>取一个 Setter 的值。写成 <c>{x:Static}</c> 的按常量表解开，写成数字的就照 XAML 读。</summary>
    private static double Number(string file, string styleKey, string property)
    {
        var value = XDocument.Parse(TokenFiles.Text(file)).Root!.Elements()
            .Single(e => e.Name.LocalName == "Style" && (string?)e.Attribute(X + "Key") == styleKey)
            .Elements()
            .Where(e => e.Name.LocalName == "Setter" && (string?)e.Attribute("Property") == property)
            .Select(e => (string?)e.Attribute("Value"))
            .FirstOrDefault()
            ?? throw new Xunit.Sdk.XunitException($"{file}: {styleKey} 少了 {property}");

        var reference = StaticRef.Match(value);
        if (!reference.Success)
            return double.Parse(value, CultureInfo.InvariantCulture);

        var field = typeof(MarkdownStyle).GetField(reference.Groups[1].Value,
            BindingFlags.Public | BindingFlags.Static);
        Assert.NotNull(field);
        return (double)field!.GetValue(null)!;
    }

    /// <summary>产品代码里的 C#（跳过 bin/obj 里生成的那些）。</summary>
    private static IEnumerable<string> SourceFiles()
    {
        var separator = Path.DirectorySeparatorChar;
        return Directory.EnumerateFiles(Root, "*.cs", SearchOption.AllDirectories)
            .Where(file => !file.Contains($"{separator}bin{separator}")
                           && !file.Contains($"{separator}obj{separator}"));
    }
}
