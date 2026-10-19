using PushBox.Windows.Data;
using PushBox.Windows.Integration;
using PushBox.Windows.Logging;
using PushBox.Windows.Media;
using PushBox.Windows.Net;
using PushBox.Windows.Sync;
using Windows.UI.Notifications;
using WinToastNotifier = Windows.UI.Notifications.ToastNotifier;

namespace PushBox.Windows.Notify;

/// <summary>
/// §8.4 W13：<see cref="INotifier"/> 的真实现，替掉 W12 挂在引擎上的那个空实现。
/// 引擎那边一行都不用改——这正是当初把这条缝留在接口上的目的。
///
/// 这一层只剩三件事：问决策（<see cref="AlertPolicy"/>）、凑计划（<see cref="ToastPlan"/>）、
/// 把计划交给系统（<see cref="ToastXml"/> + <c>Show</c>）。它对外承诺只有一条：
/// <b>任何失败都不回抛给同步循环</b>。通知发不出去是"这条消息没打扰你"，不是"这条消息没收到"——
/// Android 那边用 <c>catch (SecurityException)</c> 守的就是这条，PC 上守的是同一个语义，
/// 只是要 catch 的东西更多（AUMID 没注册、系统把本应用的通知关了、渲染器拒绝了一段怪 XML、图片落盘失败）。
/// </summary>
public sealed class ToastNotifier : INotifier
{
    private readonly AlertPolicy _policy;
    private readonly ConversationTally _tally;
    private readonly MediaLoader? _media;
    private readonly Func<DeviceIdentity>? _identities;
    private readonly string? _avatarBaseDir;

    /// <summary>toast 彻底不可用时的那条退路（托盘气泡）。测试里传一个记账的委托就能验到"退路真的被走过"。</summary>
    private readonly Action<ToastPlan>? _fallback;

    private WinToastNotifier? _notifier;

    public ToastNotifier(AlertPolicy policy, ConversationTally tally, MediaLoader? media = null,
        Func<DeviceIdentity>? identities = null, string? avatarBaseDir = null,
        Action<ToastPlan>? fallback = null)
    {
        _policy = policy;
        _tally = tally;
        _media = media;
        _identities = identities;
        _avatarBaseDir = avatarBaseDir;
        _fallback = fallback;
    }

    /// <summary>成功交给系统的条数，只用于体检输出和"为什么我没收到通知"的自查。</summary>
    public int Delivered { get; private set; }

    /// <summary>最后一次失败的**异常类型名**。刻意不是 Message：那玩意儿可能把正文带进来（§14）。</summary>
    public string? LastFailure { get; private set; }

    /// <summary>通知被决定成"不发"的帧数（总开关关掉或静默时段里的那条低级别消息）。入库照样发生了。</summary>
    public int Suppressed { get; private set; }

    /// <summary>
    /// 免打扰（<c>muted</c>）不在这儿判：引擎在调这一句之前就把它过滤掉了（§7.3，与 Android 的
    /// <c>SyncEngine.kt</c> 同一分工），所以这里的 <c>muted</c> 恒为 false，只是把那条规则留在决策层里可测。
    /// </summary>
    public void Notify(MessageFrame message)
    {
        try
        {
            var decision = _policy.Decide(message.Level, false);
            if (!decision.Shows)
            {
                Suppressed++;
                return;
            }

            var group = ToastPlan.GroupOf(message.ChannelNo, message.SenderId);
            var plan = ToastPlan.Of(message, decision, AvatarPathOf(message), _tally.Bump(group));
            if (plan == null)
            {
                Suppressed++;
                return;
            }

            Show(plan);
        }
        catch (Exception e)
        {
            // 上面那条 catch 只管"系统不肯显示"；这一条管的是决策/取字节/落文件这条准备链上任何一步炸了。
            // 契约只有一句：通知这一条路没有把异常送回同步循环的权限（消息已经落库了）
            LastFailure = e.GetType().Name;
        }
    }

    /// <summary>交出去，失败就转退路。单独成一个方法是为了让"退路被走到"这件事可测。</summary>
    public void Show(ToastPlan plan)
    {
        try
        {
            // 出声与否全在 XML 的 <audio> 上（见 ToastXml）：ToastNotification 没有 Silent 这个属性，
            // 而"省略 audio 等于放默认音"而不是静音，所以静音必须显式写成 displayOnly。
            Notifier().Show(new ToastNotification(ToastXml.Build(plan))
            {
                Tag = plan.Tag,
                Group = plan.Group
            });
            Delivered++;
            LastFailure = null;
        }
        catch (Exception e)
        {
            LastFailure = e.GetType().Name;
            // 退路被走到的那一刻才是这条留痕：气泡本身说不出为什么 toast 没成，而"为什么我没收到通知"
            // 是 §7.3 最需要回答的问题，答案只到异常类型名为止（Message 可能带着正文渲染的片段）
            AppLog.ToastFellBack(e.GetType().Name);
            _fallback?.Invoke(plan);
        }
    }

    /// <summary>
    /// 会话被打开或被"全部已读"收掉：通知中心里那一组还挂着的话就成了过期的红点。
    /// Android 那边是 <c>cancelAll()</c>（它没有按会话取消的粒度），PC 上 <c>RemoveGroup</c> 正好按会话收，
    /// 于是这一句比手机端更准：看过一条不该把另一条没看过的也抹掉。
    /// </summary>
    public void Forget(string group)
    {
        _tally.Reset(group);
        try
        {
            // 静态那一个 History 才有 RemoveGroup；实例上的 ToastNotifier 没有 History 成员
            ToastNotificationManager.History.RemoveGroup(group);
        }
        catch (Exception e)
        {
            LastFailure = e.GetType().Name;
        }
    }

    /// <summary>系统对这一个 AUMID 的态度（Enabled / 用户在设置里关掉本应用 / …）。
    /// 体检读的是它，而不是"我刚才那句 Show 没抛异常"——Show 不抛但被系统丢掉是两种完全不同的故障。</summary>
    public string SettingProbe()
    {
        try
        {
            return Notifier().Setting.ToString();
        }
        catch (Exception e)
        {
            return "unavailable:" + e.GetType().Name;
        }
    }

    /// <summary>建一次就留住：<c>CreateToastNotifier</c> 里带着一个和通知平台通信的通道，反复建不划算。</summary>
    private WinToastNotifier Notifier() =>
        _notifier ??= ToastNotificationManager.CreateToastNotifier(ToastRegistration.Aumid);

    /// <summary>
    /// 头像这条链是"有就用，没有就这次不用"：<see cref="MediaCache"/> 命中就直接落一张临时文件，
    /// 没命中则这发通知先不带图、后台补一发取字节——下一条同会话的通知就带上了。
    /// 取字节这条链本身已经在 <see cref="MediaLoader"/> 里定过序（先缓存后网络），这里只是不拿它去堵通知。
    /// </summary>
    private string? AvatarPathOf(MessageFrame message)
    {
        if (_media == null || _identities == null || _avatarBaseDir == null) return null;
        var token = DeviceClient.AvatarTokenOf(message.SenderAvatar);
        if (token == null) return null;

        var cached = _media.Cache.Get(MediaLoader.AvatarKey(token));
        if (cached != null) return ToastAvatar.Stage(_avatarBaseDir, token, cached);

        var loader = _media;
        var identities = _identities;
        var avatar = message.SenderAvatar;
        _ = Task.Run(async () =>
        {
            try
            {
                await loader.AvatarAsync(identities(), avatar).ConfigureAwait(false);
            }
            catch (Exception)
            {
                // 头像取不到和这条通知成不成没有任何关系
            }
        });
        return null;
    }
}
