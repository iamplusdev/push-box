using System;
using System.Linq;
using System.Windows;
using Microsoft.Win32;

namespace PushBox.Windows.Themes;

/// <summary>
/// §9.1 的"深浅色跟随系统"。WPF 没有 Material3 那种自动反色，所以这里做的事只有两件：
/// 读系统那个 <c>AppsUseLightTheme</c>，以及把它变化时把两份 token 字典的顺序换一下。
/// 控件侧一律 <c>{DynamicResource Bg.Page}</c>，换字典不需要重建任何可视元素。
/// </summary>
public static class ThemeManager
{
    private const string PersonalizePath = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";
    private const string LightValue = "AppsUseLightTheme";

    private static readonly Uri Light =
        new("pack://application:,,,/PushBox.Windows;component/Themes/Tokens.xaml");

    private static readonly Uri Dark =
        new("pack://application:,,,/PushBox.Windows;component/Themes/Tokens.Dark.xaml");

    private static UserPreferenceChangedEventHandler? _watching;

    /// <summary>
    /// 换完字典后广播一次。<c>DynamicResource</c> 自己会跟着变，但两处不会：
    /// <see cref="Views.TokenBrushConverter"/> 这类**绑定时算一次**的画刷，和
    /// <see cref="Controls.MarkdownStyle.FromResources"/> 抓下来的那组画刷。
    /// 窗口订阅它并把视觉重建一遍，深浅色切换才不会只染半个界面。
    /// </summary>
    public static event Action? Changed;

    /// <summary>值缺失或非 0 都算浅色：只有明确的 0 才是深色（与 Windows 自己的读法一致）。</summary>
    public static bool IsDark(object? appsUseLightTheme) =>
        appsUseLightTheme is int or long or short && Convert.ToInt64(appsUseLightTheme) == 0L;

    /// <summary>注册表读不到（比如被组策略删了那行）就退回浅色，不让首启因为一个色值炸掉。</summary>
    public static bool SystemPrefersDark()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(PersonalizePath, false);
            return IsDark(key?.GetValue(LightValue));
        }
        catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException)
        {
            return false;
        }
    }

    /// <summary>把深色/浅色那份挪到最后（WPF 里后加入的字典覆盖先加入的同名键）。</summary>
    public static void Apply(bool dark)
    {
        var owner = Application.Current?.Resources;
        if (owner is null) return;
        var previous = dark ? Light : Dark;
        var current = dark ? Dark : Light;
        owner.MergedDictionaries.Clear();
        // 两份都留在里面、只换顺序：这样切换是幂等的，切回浅色也不用重新解析 XAML
        owner.MergedDictionaries.Add(new ResourceDictionary { Source = previous });
        owner.MergedDictionaries.Add(new ResourceDictionary { Source = current });
        // 首启那一次也广播：窗口先 Show 后应用主题的话，它会错过一次重建，而它需要的正是"按现在这套色重画"
        Changed?.Invoke();
    }

    /// <summary>
    /// 启动时应用一次，并在系统换主题时再应用一次。<see cref="SystemEvents.UserPreferenceChanged"/>
    /// 是这套机器上唯一能收到"用户在设置里点了深色"的入口（WPF 没有 theme changed 事件）。
    /// </summary>
    public static void FollowSystem()
    {
        Apply(SystemPrefersDark());
        if (_watching is not null) return;
        _watching = (_, _) => Apply(SystemPrefersDark());
        SystemEvents.UserPreferenceChanged += _watching;
    }

    /// <summary>进程退出前解绑：SystemEvents 是静态事件，挂着不放会让窗口对象回收不掉。</summary>
    public static void StopFollowing()
    {
        if (_watching is null) return;
        SystemEvents.UserPreferenceChanged -= _watching;
        _watching = null;
    }
}
