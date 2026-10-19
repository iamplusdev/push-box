using System;
using PushBox.Windows.Notify;
using PushBox.Windows.Settings;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W13 的判定层：一条消息该以什么强度打扰用户。这里断言的是"决策"，不是"屏幕上有没有弹出东西"——
/// 三档强度逐条对齐 Android 的 <c>Notifier.ensureChannel</c>（error=HIGH / warning=DEFAULT / 其余=LOW），
/// §7.4 的静默时段逐条对齐 Android 的 <c>inQuietHours()</c>。D25 的 <c>max_level</c> 截断发生在服务端，
/// 所以"PC 与手机端强度一致"这条验收在客户端侧就等于"这张映射表一致"，它现在是 xunit 用例而不是人眼走查。
/// </summary>
public sealed class AlertPolicyTest
{
    private static readonly ClientSettings AlwaysOn = new()
    {
        NotificationsEnabled = true,
        QuietEnabled = false
    };

    [Theory]
    [InlineData("error", AlertUrgency.HeadsUp)]
    [InlineData("warning", AlertUrgency.Sound)]
    [InlineData("info", AlertUrgency.Silent)]
    // 服务端截断之外的取值（第三方乱填、将来新增的档）都落到低的那一端，与 Android 的 else 同
    [InlineData("critical", AlertUrgency.Silent)]
    [InlineData("", AlertUrgency.Silent)]
    [InlineData(null, AlertUrgency.Silent)]
    public void LevelMapsToTheSameThreeTiersAsAndroid(string? level, AlertUrgency expected)
    {
        Assert.Equal(expected, AlertPolicy.UrgencyFor(level));
    }

    [Theory]
    [InlineData(AlertUrgency.None, false)]
    [InlineData(AlertUrgency.Silent, false)]
    [InlineData(AlertUrgency.Sound, true)]
    [InlineData(AlertUrgency.HeadsUp, true)]
    public void OnlyWarningAndErrorMakeANoise(AlertUrgency urgency, bool playsSound)
    {
        Assert.Equal(playsSound, new AlertDecision(urgency).PlaysSound);
    }

    [Fact]
    public void MasterSwitchOffSuppressesEvenAnError()
    {
        // §7.4：总开关关掉是"照常入库、只是不发通知"，所以这里回 None 而不是抛，也不看 level
        var decision = AlertPolicy.Evaluate(AlwaysOn with { NotificationsEnabled = false },
            "error", false, LocalAt(3, 0));

        Assert.Equal(AlertUrgency.None, decision.Urgency);
        Assert.False(decision.Shows);
        Assert.Equal("通知总开关已关闭", decision.Reason);
    }

    [Fact]
    public void AMutedConversationIsSuppressedBeforeLevelIsRead()
    {
        // muted 由引擎读库后传进来（§7.3 与 Android 的 SyncEngine 同一分工），决策层只认这个 bool
        var decision = AlertPolicy.Evaluate(AlwaysOn, "error", true, LocalAt(3, 0));

        Assert.False(decision.Shows);
        Assert.Equal("该会话免打扰", decision.Reason);
    }

    [Fact]
    public void QuietHoursDowngradeInsteadOfCancelling()
    {
        var settings = QuietOnly();
        // 静默时段命中：warning 从"响一声"降到"只留痕"，但通知仍然发得出去——Android 那边是
        // PRIORITY_MIN + onlyAlertOnce，同样是降级而不是取消
        var warning = AlertPolicy.Evaluate(settings, "warning", false, LocalAt(23, 30));
        Assert.Equal(AlertUrgency.Silent, warning.Urgency);
        Assert.True(warning.Shows);
        Assert.False(warning.PlaysSound);
        Assert.Equal("静默时段", warning.Reason);

        Assert.Equal(AlertUrgency.Silent, AlertPolicy.Evaluate(settings, "info", false, LocalAt(1, 0)).Urgency);
    }

    [Fact]
    public void ErrorStillInterferesInsideQuietHours()
    {
        // §7.3 的豁免：静默时段挡得住告警，挡不住"着火了"。Android 的判定就是 message.level != "error"
        var decision = AlertPolicy.Evaluate(QuietOnly(), "error", false, LocalAt(2, 0));

        Assert.Equal(AlertUrgency.HeadsUp, decision.Urgency);
        Assert.True(decision.PlaysSound);
        Assert.Null(decision.Reason);
    }

    [Theory]
    [InlineData(21, 59, false)]
    [InlineData(22, 0, true)]
    [InlineData(23, 30, true)]
    [InlineData(0, 0, true)]
    [InlineData(7, 59, true)]
    [InlineData(8, 0, false)] // 闭开区间 [22:00, 08:00)：到点那一刻就出窗
    [InlineData(12, 0, false)]
    public void QuietWindowCrossesMidnight(int hour, int minute, bool expected)
    {
        Assert.Equal(expected, AlertPolicy.InQuietWindow(QuietOnly(), LocalAt(hour, minute)));
    }

    [Theory]
    [InlineData(8, 59, false)]
    [InlineData(9, 0, true)]
    [InlineData(16, 59, true)]
    [InlineData(17, 0, false)]
    [InlineData(23, 0, false)]
    public void QuietWindowInsideOneDay(int hour, int minute, bool expected)
    {
        var settings = QuietOnly().WithQuietWindow(true, 9 * 60, 17 * 60);
        Assert.Equal(expected, AlertPolicy.InQuietWindow(settings, LocalAt(hour, minute)));
    }

    [Fact]
    public void EqualStartAndEndMeansNoWindowNotAllDay()
    {
        // Android 那行 `if (s <= e) now in s until e` 读出来就是这个意思：[s,s) 长度为零。
        // 反过来当成"整天静默"会让一次误操作（起止拖到同一格）把通知全哑掉。
        var settings = QuietOnly().WithQuietWindow(true, 22 * 60, 22 * 60);
        Assert.False(AlertPolicy.InQuietWindow(settings, LocalAt(22, 0)));
    }

    [Fact]
    public void AWholeDayWindowIsTheSmokeTestShape()
    {
        // 体检 (--notify-smoke) 用 0..1439 表达"现在一定在窗内"，靠的就是 start<end 那条分支
        var settings = QuietOnly().WithQuietWindow(true, 0, ClientSettings.MinutesPerDay - 1);
        Assert.True(AlertPolicy.InQuietWindow(settings, LocalAt(12, 0)));
        Assert.False(AlertPolicy.InQuietWindow(settings, LocalAt(23, 59)));
    }

    [Fact]
    public void QuietSwitchOffNeverMatchesWhateverTheWindowIs()
    {
        var settings = QuietOnly() with { QuietEnabled = false };
        Assert.False(AlertPolicy.InQuietWindow(settings, LocalAt(23, 30)));
        // 关掉静默之后 warning 就该恢复成"响一声"
        Assert.Equal(AlertUrgency.Sound, AlertPolicy.Evaluate(settings, "warning", false, LocalAt(23, 30)).Urgency);
    }

    [Fact]
    public void DefaultQuietWindowIsTheAndroidNineHours()
    {
        // §7.4 默认 22:00–08:00，但默认**关**：装完就发现自己半夜被静音是最差的第一印象
        Assert.False(ClientSettings.Defaults.QuietEnabled);
        Assert.Equal(22 * 60, ClientSettings.Defaults.QuietStartMinute);
        Assert.Equal(8 * 60, ClientSettings.Defaults.QuietEndMinute);
    }

    [Fact]
    public void PolicyReadsTheSwitchItWasHandedEveryTime()
    {
        // settings 与 clock 都是取值的函数：托盘菜单勾一下不该重建决策器，下一条消息就得按新的走
        var settings = AlwaysOn;
        var policy = new AlertPolicy(() => settings, () => LocalAt(23, 30));

        Assert.Equal(AlertUrgency.Sound, policy.Decide("warning", false).Urgency);
        Assert.True(policy.Decide(null, false).Shows); // level 缺失也不炸

        settings = settings with { NotificationsEnabled = false };
        Assert.Equal("通知总开关已关闭", policy.Decide("error", false).Reason);
    }

    /// <summary>默认的 22:00–08:00 窗口、开着。</summary>
    private static ClientSettings QuietOnly() => new()
    {
        NotificationsEnabled = true,
        QuietEnabled = true,
        QuietStartMinute = ClientSettings.DefaultQuietStart,
        QuietEndMinute = ClientSettings.DefaultQuietEnd
    };

    /// <summary>
    /// 把"本地几点几分"钉成一个 DateTimeOffset。<see cref="AlertPolicy.InQuietWindow"/> 读的是
    /// <c>ToLocalTime()</c> 的时分，所以按本机当前偏移回推墙钟，测试才不必改系统时钟、也不必跑在特定时区。
    /// </summary>
    private static DateTimeOffset LocalAt(int hour, int minute)
    {
        var wall = new DateTime(2026, 3, 7, hour, minute, 0);
        return new DateTimeOffset(wall, TimeZoneInfo.Local.GetUtcOffset(wall));
    }
}
