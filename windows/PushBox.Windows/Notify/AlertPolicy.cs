using PushBox.Windows.Settings;

namespace PushBox.Windows.Notify;

/// <summary>
/// 一条消息该以什么强度打扰用户。<b>三档是照 Android 抄的</b>（<c>Notifier.ensureChannel</c> 的
/// IMPORTANCE_HIGH / DEFAULT / LOW），<see cref="None"/> 是 PC 多加的一档：Android 那边"不弹"发生在
/// 引擎调 post 之前（总开关、免打扰），PC 把这四种判定收成同一个返回值，好让它们在 xunit 里都能被断言。
/// </summary>
public enum AlertUrgency
{
    /// <summary>一条通知都不发：消息照常入库、照常出现在时间线里。</summary>
    None,

    /// <summary>只留痕不响（Android 的 LOW，以及 §7.4 静默时段命中后的那条降级）。</summary>
    Silent,

    /// <summary>响一声（Android 的 DEFAULT）。</summary>
    Sound,

    /// <summary>横幅 + 响（Android 的 IMPORTANCE_HIGH）。</summary>
    HeadsUp,
}

/// <summary>
/// 一次提醒决策。理由是人话且是固定文案——它可能被显示在托盘菜单里，所以绝不含正文（§14）。
/// </summary>
public sealed record AlertDecision(AlertUrgency Urgency, string? Reason = null)
{
    public bool Shows => Urgency != AlertUrgency.None;

    /// <summary>要不要出声：静默时段命中和 info 级都不出声，只有 error/warning 出声。</summary>
    public bool PlaysSound => Urgency is AlertUrgency.Sound or AlertUrgency.HeadsUp;

    public static AlertDecision Suppressed(string reason) => new(AlertUrgency.None, reason);
}

/// <summary>
/// §7.3 的提醒强度 + §7.4 的静默时段，两端规则的 PC 落点。它只做判定、不碰 WinRT，
/// 所以"总开关关掉是否照常入库""静默时段里 error 是否仍可打扰"这两条验收都是普通用例而不是人眼走查。
///
/// <paramref name="settings"/> 和 <paramref name="clock"/> 都是取值的函数而不是值：
/// 托盘菜单改一下开关，下一条消息就得按新的设置走，不需要重建这个对象。
/// </summary>
public sealed class AlertPolicy
{
    /// <summary>§7.3：只有 error 在静默时段仍可打扰，与 Android 的 <c>message.level != "error"</c> 同一条。</summary>
    public const string LevelError = "error";

    public const string LevelWarning = "warning";

    private readonly Func<ClientSettings> _settings;
    private readonly Func<DateTimeOffset> _clock;

    public AlertPolicy(Func<ClientSettings> settings, Func<DateTimeOffset>? clock = null)
    {
        _settings = settings;
        _clock = clock ?? (() => DateTimeOffset.Now);
    }

    /// <summary>免不免打扰由调用方（引擎读库）决定，和 Android 把 isMuted 放在引擎里同一分工。</summary>
    public AlertDecision Decide(string? level, bool muted) =>
        Evaluate(_settings(), level ?? string.Empty, muted, _clock());

    /// <summary>
    /// 纯函数那一半：<paramref name="now"/> 用本地时区读时分，所以测试给一个带 +08:00 的 DateTimeOffset
    /// 就能钉死跨午夜的判定，不用改机器时钟。
    /// </summary>
    public static AlertDecision Evaluate(ClientSettings settings, string level, bool muted, DateTimeOffset now)
    {
        if (!settings.NotificationsEnabled) return AlertDecision.Suppressed("通知总开关已关闭");
        if (muted) return AlertDecision.Suppressed("该会话免打扰");

        var urgency = UrgencyFor(level);
        // 静默时段只降级不取消：Android 那边是 PRIORITY_MIN + onlyAlertOnce，通知仍然出现在栏里
        if (urgency != AlertUrgency.HeadsUp && InQuietWindow(settings, now)) return new AlertDecision(AlertUrgency.Silent, "静默时段");
        return new AlertDecision(urgency);
    }

    /// <summary>level → 强度。不认识的值（将来的新档、第三方乱填）落到低的那一端，和 Android 的 else 同。</summary>
    public static AlertUrgency UrgencyFor(string? level) => level switch
    {
        LevelError => AlertUrgency.HeadsUp,
        LevelWarning => AlertUrgency.Sound,
        _ => AlertUrgency.Silent
    };

    /// <summary>
    /// §7.4 的窗口判定，<c>if (s &lt;= e) now in s until e else now &gt;= s || now &lt; e</c> 的逐字翻译。
    /// 关掉就是 false；起止相等也是 false——那是"整段都不静默"而不是"整天都静默"，
    /// 按 Android 那条区间写法读出来只能是这个意思（闭开区间 [s,e) 长度为零）。
    /// </summary>
    public static bool InQuietWindow(ClientSettings settings, DateTimeOffset now)
    {
        if (!settings.QuietEnabled) return false;
        var local = now.ToLocalTime();
        var minute = local.Hour * 60 + local.Minute;
        var start = ClientSettings.ClampMinute(settings.QuietStartMinute);
        var end = ClientSettings.ClampMinute(settings.QuietEndMinute);
        if (start == end) return false;
        return start < end ? minute >= start && minute < end : minute >= start || minute < end;
    }
}
