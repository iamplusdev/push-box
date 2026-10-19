using PushBox.Windows.Net;

namespace PushBox.Windows.Notify;

/// <summary>通知上的一颗按钮：文字 + 点它时要交给 shell 的那条协议 URI。</summary>
public sealed record ToastButton(string Label, string Uri);

/// <summary>
/// 一条通知"要说什么"的完整决定，纯数据、不含 WinRT。<see cref="ToastNotifier"/> 只做一件事：
/// 把这份计划逐字段抄进 toast XML。这样"点通知能不能定位到会话""静默时段到底静不静"
/// 都是 xunit 断言，而不是"看机器上有没有弹出来"。
/// </summary>
public sealed record ToastPlan
{
    /// <summary>§8.1 那两颗按钮的文案，两端一致。</summary>
    public const string OpenLabel = "打开会话";

    public const string MarkReadLabel = "全部已读";

    /// <summary>同一会话的计数行，与 §9.3 那颗"新消息"胶囊同一句人话（Android 摘要通知也是这一句）。</summary>
    public static string CountLabel(int count) => $"{count} 条新消息";

    /// <summary>WinRT 的 tag：同一个 msg_id 再投一次是覆盖而不是新增第二条通知。§6.2 的去重在库里已经做了一道，
    /// 这里是"进程重启前那几条还没落库的重复"的第二道。</summary>
    public required string Tag { get; init; }

    /// <summary>WinRT 的 group：通知中心把同一会话折叠在一处。</summary>
    public required string Group { get; init; }

    /// <summary>点通知本体 = 唤起这条会话（§8.4 W13 的验收项）。</summary>
    public required string Launch { get; init; }

    public required AlertUrgency Urgency { get; init; }

    /// <summary>出声与否由 <see cref="AlertDecision.PlaysSound"/> 抄过来，计划里留着它是因为传输层只认这两个字。</summary>
    public required bool PlaysSound { get; init; }

    /// <summary>标题是发送者显示名（§7.3 与 Android 的 setContentTitle 同一个来源），不是消息标题。</summary>
    public required string Title { get; init; }

    /// <summary>正文那一行按 summary → title → content 的优先级取第一个非空的，见 <see cref="SummaryOf"/>。</summary>
    public required string Body { get; init; }

    /// <summary>"3 条新消息"那一行，只在同会话第二条及以后才出现。</summary>
    public string? CountLine { get; init; }

    /// <summary>圆形头像的文件路径，拿不到就是 null（通知照样发得出去）。</summary>
    public string? AvatarPath { get; init; }

    public required IReadOnlyList<ToastButton> Buttons { get; init; }

    /// <summary>
    /// §7.3 的摘要优先级：<c>summary ?: title ?: content</c>。这条不是随便挑的——第三方推送常常只给
    /// <c>title</c>（"备份失败"）而正文是一整段 Markdown，直接上通知会糊成一片；反过来只给 <c>content</c>
    /// 时它仍是唯一可读的来源，所以排在最后一档而不是丢掉。
    /// </summary>
    public static string SummaryOf(MessageFrame frame) =>
        Pick(frame.Summary) ?? Pick(frame.Title) ?? Pick(frame.Content) ?? string.Empty;

    /// <summary>会话分组键：与 <see cref="ToastProtocol"/> 同一套转义，两处必须长成同一个样。</summary>
    public static string GroupOf(string channelNo, string senderId) =>
        $"{channelNo}/{senderId}";

    /// <summary>这条决策不发通知时回 null——调用方拿"有没有计划"当分支，而不是自己再判一次决策。</summary>
    public static ToastPlan? Of(MessageFrame frame, AlertDecision decision, string? avatarPath, int groupCount)
    {
        if (!decision.Shows) return null;
        return new ToastPlan
        {
            Tag = frame.Id,
            Group = GroupOf(frame.ChannelNo, frame.SenderId),
            Launch = ToastProtocol.OpenUri(frame.ChannelNo, frame.SenderId),
            Urgency = decision.Urgency,
            PlaysSound = decision.PlaysSound,
            Title = frame.DisplayName,
            Body = SummaryOf(frame),
            CountLine = groupCount > 1 ? CountLabel(groupCount) : null,
            AvatarPath = avatarPath,
            Buttons = new[]
            {
                new ToastButton(OpenLabel, ToastProtocol.OpenUri(frame.ChannelNo, frame.SenderId)),
                new ToastButton(MarkReadLabel, ToastProtocol.ReadUri(frame.ChannelNo, frame.SenderId))
            }
        };
    }

    private static string? Pick(string? value) => string.IsNullOrWhiteSpace(value) ? null : value;
}

/// <summary>
/// 每条会话"这次是第几条"的计数，对应 Android 摘要通知里那个 <c>setNumber(count)</c>
/// （那边从 activeNotifications 读回来，PC 读不到别人渲染的通知，所以自己记）。
/// 只记条数、不记内容，内存里放正文是给自己找 §14 的麻烦。
/// </summary>
public sealed class ConversationTally
{
    private readonly Dictionary<string, int> _counts = new();
    private readonly object _gate = new();

    /// <summary>发一条之前调它，返回**含这一条在内**的计数（Android 的 existing.number + 1 同义）。</summary>
    public int Bump(string group)
    {
        lock (_gate)
        {
            var next = _counts.TryGetValue(group, out var seen) ? seen + 1 : 1;
            _counts[group] = next;
            return next;
        }
    }

    public int Count(string group)
    {
        lock (_gate) return _counts.TryGetValue(group, out var seen) ? seen : 0;
    }

    /// <summary>会话被打开或被标已读：计数清零，下一条新消息重新从"1 条"数起。</summary>
    public void Reset(string group)
    {
        lock (_gate) _counts.Remove(group);
    }

    public void ResetAll()
    {
        lock (_gate) _counts.Clear();
    }
}
