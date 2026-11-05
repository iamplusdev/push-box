using System;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Windows;
using Microsoft.Win32;

namespace PushBox.Windows.Themes;

/// <summary>主题三档。默认跟随系统；另外两档是"不想被系统带着变"的显式选择。</summary>
public enum ThemeChoice
{
    System,
    Light,
    Dark
}

/// <summary>
/// §9.1 的"深浅色"。WPF 没有 Material3 那种自动反色，所以这里做的事只有两件：
/// 读系统那个 <c>AppsUseLightTheme</c>（仅"跟随系统"这一档才读），以及把两份 token 字典的顺序换一下。
/// 控件侧一律 <c>{DynamicResource Bg.Page}</c>，换字典不需要重建任何可视元素。
/// </summary>
public static class ThemeManager
{
    private const string PersonalizePath = @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";
    private const string LightValue = "AppsUseLightTheme";

    private static readonly Uri Light = TokenDictionary("Tokens.xaml");

    private static readonly Uri Dark = TokenDictionary("Tokens.Dark.xaml");

    /// <summary>
    /// pack:// 这个 scheme 是 <see cref="Application"/> 的静态构造注册的，而这两个地址在
    /// ThemeManager 被第一次碰到的那一刻就要解析。以前不显式注册，于是"谁第一个碰 ThemeManager"
    /// 决定成败：2026-11-04 实测同一份代码有一跑 39 条用例红在 `UriFormatException: Invalid port`
    /// （静态构造抛过一次，这个类型就永久废了），重跑又全绿——这种靠顺序成立的绿不能用。
    /// <para>
    /// 注册必须写在<b>这里</b>、而不是 <c>static ThemeManager()</c> 里：C# 把字段初始化放在静态构造体
    /// <b>之前</b>跑，写在静态构造里等于在 `new Uri` 失败之后才补救——单跑 ThemeTest 那一组仍然全红，
    /// 只有放在这两个地址的取数路径上才是"解析之前先注册"。它不建实例，
    /// <see cref="Application.Current"/> 仍然是 null，<c>MarkdownStyle.FromResources</c> 那套兜底照旧。
    /// </para>
    /// </summary>
    private static Uri TokenDictionary(string file)
    {
        RuntimeHelpers.RunClassConstructor(typeof(Application).TypeHandle);
        return new Uri($"pack://application:,,,/PushBox.Windows;component/Themes/{file}");
    }

    private static UserPreferenceChangedEventHandler? _watching;

    /// <summary>
    /// 现在铺的是不是深色那一份。<b>只给"颜色不是纯令牌"的那几处看</b>（目前是头像占位：浅色是白底配
    /// 名字色首字，深色仍是定色底配白字），画刷能走 <c>DynamicResource</c> 的地方一律不许读它。
    /// 名字不叫 <c>IsDark</c>：那已经是<b>读注册表值</b>的那个判定（<see cref="IsDark(object?)</see>）。
    /// </summary>
    public static bool IsDarkTheme { get; private set; }

    /// <summary>当前档位。系统事件不带"现在是哪一档"，所以只能由这里记着。</summary>
    private static ThemeChoice _choice = ThemeChoice.System;

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

    /// <summary>把深色/浅色那份挪到最后（WPF 里后加入的字典覆盖先加入的同名键），并广播一次"换档了"。</summary>
    public static void Apply(bool dark)
    {
        IsDarkTheme = dark;
        var owner = Application.Current?.Resources;
        if (owner is not null)
        {
            var previous = dark ? Light : Dark;
            var current = dark ? Dark : Light;
            owner.MergedDictionaries.Clear();
            // 两份都留在里面、只换顺序：这样切换是幂等的，切回浅色也不用重新解析 XAML
            owner.MergedDictionaries.Add(new ResourceDictionary { Source = previous });
            owner.MergedDictionaries.Add(new ResourceDictionary { Source = current });
        }

        // 广播看的是"档位变了"，不是"字典换了"：没有 App 实例的那条路（用例）也要收到，
        // 否则那些按档自绘的控件在没有字典的世界里就永远画不出另一档。
        // 首启那一次同样广播：窗口先 Show 后应用主题的话，它会错过一次重建，而它需要的正是"按现在这套色重画"
        Changed?.Invoke();
    }

    /// <summary>
    /// 启动时按设置里的档位应用一次，并在系统换主题时再应用一次。<see cref="SystemEvents.UserPreferenceChanged"/>
    /// 是这套机器上唯一能收到"用户在设置里点了深色"的入口（WPF 没有 theme changed 事件）。
    /// 固定浅色/深色时系统事件仍会到，但那时不再改色——档位是用户明说的。
    /// </summary>
    public static void Follow(ThemeChoice choice)
    {
        _choice = choice;
        Apply(DarkFor(choice, SystemPrefersDark()));
        if (_watching is not null) return;
        _watching = (_, _) =>
        {
            if (_choice == ThemeChoice.System) Apply(SystemPrefersDark());
        };
        SystemEvents.UserPreferenceChanged += _watching;
    }

    /// <summary>设置页换档：立刻生效，不等下一次系统事件。同一档不重铺字典（铺一次就是整窗重绘）。</summary>
    public static void SetChoice(ThemeChoice choice)
    {
        if (choice == _choice) return;
        _choice = choice;
        Apply(DarkFor(choice, SystemPrefersDark()));
    }

    /// <summary>三档里实际该用哪一份字典：只有"跟随系统"才去看系统的值。</summary>
    public static bool DarkFor(ThemeChoice choice, bool systemPrefersDark) => choice switch
    {
        ThemeChoice.Dark => true,
        ThemeChoice.Light => false,
        _ => systemPrefersDark
    };

    /// <summary>进程退出前解绑：SystemEvents 是静态事件，挂着不放会让窗口对象回收不掉。</summary>
    public static void StopFollowing()
    {
        if (_watching is null) return;
        SystemEvents.UserPreferenceChanged -= _watching;
        _watching = null;
    }
}
