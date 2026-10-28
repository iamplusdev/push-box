using System;
using System.Collections.Generic;
using System.Globalization;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media;

namespace PushBox.Windows.Views;

/// <summary>
/// token 键名 → 画刷。模型那边只交出 <c>"Level.Warning"</c> 这样的**键名**而不是颜色，
/// 深色/浅色切换时才不用重建任何可视元素（<see cref="Themes.ThemeManager"/> 换的是字典顺序）。
/// 键为 null 就是"这一档不着色"（§9.1 的 level.info），交出去是透明而不是猜一个颜色。
/// </summary>
public sealed class TokenBrushConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is string key && Application.Current?.TryFindResource(key) is Brush brush
            ? brush
            : Brushes.Transparent;

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}

/// <summary>null 或空串 = 不占位（Collapsed），有值才出现——未读数字、筛选提示条、状态点都走这一条。</summary>
public sealed class NullOrEmptyToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var shown = value switch
        {
            null => false,
            string s => s.Length > 0,
            _ => true
        };
        return shown ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        throw new NotSupportedException();
}

/// <summary>
/// 占位头像的那一件东西（§7.3/§7.4）：显示名首字大写，底色按名字定色。
/// 头像字节由 <see cref="PushBox.Windows.Controls.AvatarBox"/> 自己异步盖上来，
/// 这里只留"没有字节时那一格长什么样"的那三个判定——它们不是 WPF 类型，所以能被普通用例直接断言。
/// </summary>
public static class AvatarVisual
{
    /// <summary>Android 的 AVATAR_PALETTE，六个色一模一样，两端同名会话在两个屏上同色。
    /// 第一格从 #07C160 收进品牌绿 #0F7A4F：首字是白字，#07C160 上只有 2.4:1，#0F7A4F 是 5.4:1。</summary>
    public static readonly IReadOnlyList<Color> Palette = new[]
    {
        Color.FromRgb(0x0F, 0x7A, 0x4F), Color.FromRgb(0x19, 0x8C, 0xFF), Color.FromRgb(0xFA, 0x51, 0x51),
        Color.FromRgb(0xB0, 0x69, 0x00), Color.FromRgb(0x7B, 0x61, 0xFF), Color.FromRgb(0x00, 0xB3, 0xA7),
    };

    public static string Initial(string? name)
    {
        var trimmed = name?.Trim();
        return trimmed is { Length: > 0 } ? char.ToUpperInvariant(trimmed[0]).ToString() : "?";
    }

    public static Color ColorOf(string? name) =>
        string.IsNullOrEmpty(name)
            ? Palette[0]
            // 取模前把符号位抹掉：C# 的 % 跟着被除数的符号走，负数会算出 Palette[-1]
            : Palette[(StableHash(name!) & int.MaxValue) % Palette.Count];

    /// <summary>
    /// 自己算而不是用 <c>string.GetHashCode()</c>：.NET Core 默认每进程换一个随机种子，
    /// 同一台机器上重启一次颜色就变了。这里是 Java/Kotlin 那套 31 进制多项式，跨进程、跨端都稳定。
    /// </summary>
    public static int StableHash(string value)
    {
        var hash = 0;
        foreach (var c in value) hash = unchecked(hash * 31) + c;
        return hash;
    }
}

/// <summary>显示名 → 头像圈里那一个字母与底色，见 <see cref="AvatarVisual"/>：
/// 头像现在由 <see cref="PushBox.Windows.Controls.AvatarBox"/> 自己画，不再走绑定转换器，
/// 所以这里只剩"没有字节时那一格长什么样"的那三个判定。</summary>
