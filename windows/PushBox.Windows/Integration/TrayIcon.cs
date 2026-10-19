using System.Windows.Controls;
using H.NotifyIcon;
using PushBox.Windows.Notify;
using PushBox.Windows.Settings;

namespace PushBox.Windows.Integration;

/// <summary>托盘要干的活，全部由装配那一层（App）注入：托盘自己不碰库、不碰引擎，只转达意图。</summary>
public sealed record TrayActions(
    Action Open,
    Action OpenSettings,
    Action Exit,
    Action<bool> SetNotifications,
    Action<bool> SetQuiet,
    Action<bool> SetAutoStart,
    Action<bool> SetCloseToTray);

/// <summary>
/// §8.1 的托盘常驻。<c>H.NotifyIcon</c> 的 <see cref="TaskbarIcon"/> 只出现在 <see cref="_icon"/> 这一个字段上，
/// 外面看到的是一组菜单项 + 一句 tooltip + 一条退路气泡，以后换库都不用动通知那半条链。
///
/// 四个开关在托盘菜单上有一份，在设置窗（W16）里也有一份：<b>它们是同一份 <see cref="ClientSettings"/>
/// 的两个入口</b>，不是两份状态——菜单是"顺手关一下"，设置窗是"要把静默时段改成几点讲清楚"。
/// </summary>
public sealed class TrayIcon : IDisposable
{
    public const string AppName = "PushBox";

    public const string OpenItemLabel = "打开 " + AppName;

    public const string SettingsItemLabel = "设置…";

    public const string ExitItemLabel = "退出";

    public const string NotificationsItemLabel = "新消息通知";

    public const string QuietItemLabel = "静默时段";

    public const string AutoStartItemLabel = "开机自启";

    public const string CloseToTrayItemLabel = "关闭窗口时最小化到托盘";

    private readonly TaskbarIcon _icon;
    private readonly MenuItem _notifications;
    private readonly MenuItem _quiet;
    private readonly MenuItem _autoStart;
    private readonly MenuItem _closeToTray;

    public TrayIcon(TrayActions actions, ClientSettings settings)
    {
        _icon = new TaskbarIcon { ToolTipText = AppName };
        try
        {
            // 取不到就退回系统的应用图标：托盘这件事不该因为一个图标资源而整个失败
            _icon.Icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!)
                         ?? System.Drawing.SystemIcons.Application;
        }
        catch (Exception)
        {
            _icon.Icon = System.Drawing.SystemIcons.Application;
        }

        _notifications = Checkable(NotificationsItemLabel, settings.NotificationsEnabled,
            actions.SetNotifications);
        _quiet = Checkable(QuietItemLabel, settings.QuietEnabled, actions.SetQuiet);
        _autoStart = Checkable(AutoStartItemLabel, settings.AutoStartEnabled, actions.SetAutoStart);
        _closeToTray = Checkable(CloseToTrayItemLabel, settings.CloseToTray, actions.SetCloseToTray);

        var menu = new ContextMenu();
        menu.Items.Add(Command(OpenItemLabel, actions.Open));
        menu.Items.Add(Command(SettingsItemLabel, actions.OpenSettings));
        menu.Items.Add(new Separator());
        menu.Items.Add(_notifications);
        menu.Items.Add(_quiet);
        menu.Items.Add(_autoStart);
        menu.Items.Add(_closeToTray);
        menu.Items.Add(new Separator());
        menu.Items.Add(Command(ExitItemLabel, actions.Exit));

        _icon.ContextMenu = menu;
        // 左键单击 = 唤起窗口，这是这类托盘应用的默认肌肉记忆；右键才出菜单
        _icon.TrayLeftMouseUp += (_, _) => actions.Open();
        // 代码里 new 出来的 TaskbarIcon 不在任何资源/模板的加载流程上，显式要求建一次；
        // exemptness = true 是"别因此把应用切成隐藏模式"，我们是有主窗口的
        _icon.ForceCreate(true);
        Refresh(settings);
    }

    /// <summary>托盘那一行：未读数在前、连接状态在后，两个都没有就只剩应用名。</summary>
    public static string TooltipFor(string? state, int unread) => unread switch
    {
        > 0 when state is { Length: > 0 } => $"{AppName} · {unread} 条未读 · {state}",
        > 0 => $"{AppName} · {unread} 条未读",
        _ => state is { Length: > 0 } ? $"{AppName} · {state}" : AppName
    };

    public void SetTooltip(string text) => _icon.ToolTipText = text;

    /// <summary>toast 那条路整个不可用时的退路：气泡只保证"你知道有消息来了"，点击带不回会话。</summary>
    public void Balloon(ToastPlan plan)
    {
        // 气泡只有一行正文，计数那句并进同一行；正文为空（只有附件的消息）时退回标题
        var body = plan.Body.Length > 0 ? plan.Body : plan.Title;
        var text = plan.CountLine is { Length: > 0 } count ? $"{body}（{count}）" : body;
        _icon.ShowNotification(plan.Title, text, H.NotifyIcon.Core.NotificationIcon.Info,
            IntPtr.Zero, false, plan.PlaysSound, false, false, TimeSpan.FromSeconds(8));
    }

    /// <summary>把四个开关的勾选态对齐到当前设置；由 App 在设置变化后调用。</summary>
    public void Refresh(ClientSettings settings)
    {
        _notifications.IsChecked = settings.NotificationsEnabled;
        _quiet.IsChecked = settings.QuietEnabled;
        _autoStart.IsChecked = settings.AutoStartEnabled;
        _closeToTray.IsChecked = settings.CloseToTray;
    }

    public bool IsCreated => _icon.IsCreated;

    public void Dispose() => _icon.Dispose();

    private static MenuItem Command(string label, Action onClick)
    {
        var item = new MenuItem { Header = label };
        item.Click += (_, _) => onClick();
        return item;
    }

    private static MenuItem Checkable(string label, bool isChecked, Action<bool> onChange)
    {
        var item = new MenuItem { Header = label, IsCheckable = true, IsChecked = isChecked };
        // IsChecked 会先自己翻再发 Click，所以取的是翻完之后的值——那正是用户想要的
        item.Click += (_, _) => onChange(item.IsChecked);
        return item;
    }
}
