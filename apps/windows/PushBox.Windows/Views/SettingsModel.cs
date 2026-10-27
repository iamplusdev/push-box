using System;
using System.Collections.Generic;
using System.Globalization;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Net;
using PushBox.Windows.Settings;

namespace PushBox.Windows.Views;

/// <summary>一次操作的结果：成功带一个值，失败带一句人话。<b>值与话都不含消息正文</b>（§13/§14）。</summary>
public sealed record Outcome<T>(bool Ok, T? Value, string Error)
{
    public static Outcome<T> Good(T value) => new(true, value, string.Empty);
    public static Outcome<T> Bad(Exception e) => new(false, default, Describe(e));

    /// <summary>把异常说成用户能行动的一句话。服务端的 hint 本来就是为了这个才存在的，直接转达；
    /// 网络那一类（连不上、超时）没有 hint，退成类型名 + 一句"检查接入地址"。</summary>
    public static string Describe(Exception e) => e switch
    {
        PushBoxApiException api => api.Message,
        TaskCanceledException => "请求超时，请检查网络或服务端是否在跑",
        HttpRequestException http => $"连不上服务端：{http.Message}",
        _ => e.Message
    };
}

/// <summary>创建/轮换之后回显的那一份接入信息。<see cref="Secret"/> 只在这一次回答里有，永不落盘、永不进日志。</summary>
public sealed record Issued(string ChannelNo, string Secret)
{
    /// <summary>第三方软件要抄的那一行。地址、号码、密钥三个都由服务端这一次给，本机不猜。
    ///
    /// 凭据写在<b>请求体</b>里而不是请求头：入站接口（<c>POST {base}/message</c>，注意它不在
    /// <c>/api/v1</c> 下）只认 body 里的 channel/secret，写成 header 的那一行看着能抄，实际一律 401。</summary>
    public string CurlLine(string baseUrl) =>
        $"curl -X POST {baseUrl.TrimEnd('/')}/message -H 'content-type: application/json' " +
        $"-d '{{\"channel\":\"{ChannelNo}\",\"secret\":\"{Secret}\"," +
        "\"type\":\"text\",\"content\":\"hello pushbox\"}'";
}

/// <summary>频道治理页的一行。字段照 <see cref="ChannelInfo"/> 抄，文案在这里算，好让用例钉得住。</summary>
public sealed record ChannelRow(string ChannelNo, string Name, string MaxLevel, int MaxImages, int RateLimit,
    int MaxBodyBytes, bool StrictAuth, bool Muted, int DailyMsgLimit, int DailyImageLimit, long DailyBytesLimit,
    bool SenderWhitelistMode, bool Subscribed)
{
    public static ChannelRow Of(ChannelInfo c) => new(c.ChannelNo, c.Name, c.MaxLevel, c.MaxImages, c.RateLimit,
        c.MaxBodyBytes, c.StrictAuth, c.Muted, c.DailyMsgLimit, c.DailyImageLimit, c.DailyBytesLimit,
        c.SenderWhitelistMode, c.Subscribed);

    /// <summary>Android 的 channel_unnamed：名字是可选的，空名也要有一个能读出来的标题。</summary>
    public string TitleText => string.IsNullOrWhiteSpace(Name) ? "未命名频道" : Name;

    /// <summary>Android 的 channel_max_level 那一档下拉的三项文案，同一张表。</summary>
    public string LevelText => SettingsModel.LevelName(MaxLevel);

    /// <summary>列表行那枚小徽章：一行塞不下整段解释，长短语留给治理区那一格。</summary>
    public string SubscriptionBadge => Subscribed ? "已接入" : "未接入";

    /// <summary>接入是设备级的：同一张频道列表在手机上可以全是"已接入"、在这台 PC 上全是"未接入"，
    /// 所以这一格必须自己说清本机是哪一种，不能让人拿另一台设备的状态来推断。</summary>
    public string SubscriptionText => Subscribed
        ? "这台电脑已接入该频道，会收到它的消息"
        : "这台电脑未接入该频道：默认照样收得到，只有开启「只接收已接入的频道」之后才不弹";

    /// <summary>列表行下面那行灰字：把最常被人改的三项放前面，剩下的看治理区。</summary>
    public string SummaryText
    {
        get
        {
            var flags = new List<string> { $"上限 {LevelText}", $"{RateLimit} 条/分", $"单次 {MaxImages} 图" };
            if (StrictAuth) flags.Add("强制校验 sender");
            if (SenderWhitelistMode) flags.Add("只允许白名单");
            if (Muted) flags.Add("频道静默");
            return string.Join(" · ", flags);
        }
    }
}

/// <summary>
/// 一次 GET /channels 的界面视图：行 + 本机那个"只接收已接入频道"的开关。
/// 开关的值来自服务端而不是本机记录——它写在 devices 表那一行上，另一台设备改了对这台也生效。
/// </summary>
public sealed record ChannelPage(IReadOnlyList<ChannelRow> Rows, bool SubscribedOnly)
{
    /// <summary>已接入的条数。用循环数而不是 LINQ：这一页的用例要能在没有 System.Linq 的语境里被读。</summary>
    public int JoinedCount
    {
        get
        {
            var n = 0;
            foreach (var row in Rows) if (row.Subscribed) n++;
            return n;
        }
    }

    /// <summary>Android 的 channel_filter_only_empty：开了过滤却一条都没接入＝这台设备从此静默。
    /// 这是一个合法但极易误会的状态，界面要主动说出来而不是等人发现通知不来了。</summary>
    public string FilterWarning => SubscribedOnly && JoinedCount == 0
        ? "已开启「只接收已接入的频道」，但一条都没接入——这台电脑现在收不到任何消息。"
        : string.Empty;
}

/// <summary>设备列表的一行（含已吊销的那几行——界面上要看得见"谁被吊销过"）。</summary>
public sealed record DeviceRow(string DeviceId, string Name, string Platform, long CreatedAt, long LastSeenAt,
    bool Current, bool Revoked)
{
    public static DeviceRow Of(DeviceInfo d) =>
        new(d.DeviceId, d.Name, d.Platform, d.CreatedAt, d.LastSeenAt, d.Current, d.Revoked);

    /// <summary>Android 的 device_current / device_revoked：两个都命中时"已吊销"说话，因为它是更能改变结论的那个事实。</summary>
    public string StatusText => Revoked ? "已吊销" : Current ? "当前设备" : string.Empty;

    /// <summary>已吊销的设备不再上线，"最近在线"这一格就写它停止的时刻而不是留一个旧时间。</summary>
    public string LastSeenText => TimeFormat.Stamp(LastSeenAt);

    /// <summary>吊销按钮只在"不是本机、也还没吊销"的那一行出现——吊销自己等于把自己踢下线，吊销已经吊销的那台无事可做。</summary>
    public bool CanRevoke => !Revoked && !Current;
}

/// <summary>
/// 设置窗的全部判定（§8.4 W16，对齐 Android 的 ChannelsTab / DevicesTab / SettingsTab）。
/// 它不引用任何 WPF 类型：接入信息怎么排、留空的那个输入框算不算"不提交"、按钮该不该出现在这一行，
/// 这些都要能被普通用例断言，而不是等人去点。网络只走 <see cref="DeviceClient"/>，本机开关只走
/// <see cref="ClientSettings"/>——窗口的 code-behind 因此只剩"把控件里的字符串交出去"这一件事。
/// </summary>
public sealed class SettingsModel
{
    /// <summary>Android 的 channel_g_hint_zero 说的那件事：0 不是"上限 0 条"，是"沿用服务端默认值"。</summary>
    public const string GovernanceHintZero = "填 0 表示沿用服务端默认值";

    /// <summary>密钥只显示这一次（Android 的 secret_once_hint）。</summary>
    public const string SecretOnceHint = "密钥只显示这一次，请立即复制保存。";

    public const string RecoveryOnceHint =
        "旧恢复码即刻失效，已经用它登录的设备不受影响；新码只显示这一次。";

    /// <summary>Android 的 channel_reset_no_desc：换号之后第三方必须改地址，这一句必须在点之前读到。</summary>
    public const string ResetNoDetail =
        "旧的对外号码即刻作废，第三方必须改用新号码投递（往旧号发会收到 401/404）；"
        + "历史消息随迁、密钥不变。本机该频道下的会话设置会丢失并重新拉取历史。";

    /// <summary>提醒强度上限的三档，顺序与 Android 的下拉一致（等级从低到高）。</summary>
    public static readonly IReadOnlyList<string> Levels = new[] { "info", "warning", "error" };

    private readonly DeviceClient _client;
    private readonly IdentityStore _identities;

    public SettingsModel(DeviceClient client, IdentityStore identities)
    {
        _client = client;
        _identities = identities;
    }

    public DeviceIdentity Identity() => _identities.Load();

    public string BaseUrl => Identity().ServerBaseUrl;

    /// <summary>Android 的 channel_g_* 三项标签对应的文案；未知值原样回显，不替服务端编一个。</summary>
    public static string LevelName(string level) => level switch
    {
        "info" => "普通",
        "warning" => "警告",
        "error" => "错误",
        _ => level
    };

    // ---- 频道 ----------------------------------------------------------------------------------------

    public async Task<Outcome<ChannelPage>> ChannelsAsync(CancellationToken ct = default)
    {
        try
        {
            var page = await _client.ChannelsPageAsync(Identity(), ct).ConfigureAwait(false);
            return Outcome<ChannelPage>.Good(new ChannelPage(Mapped(page.Channels, ChannelRow.Of),
                page.SubscribedOnly));
        }
        catch (Exception e)
        {
            return Outcome<ChannelPage>.Bad(e);
        }
    }

    // ---- 接入（订阅）----------------------------------------------------------------------------------

    /// <summary>频道号在服务端是 CHAR(8)，而且超长会被静默截到 8 位再查——多抄一位就变成"去查了另一个
    /// 频道"，回一个 404 让人以为号码写错了。所以这一格先按 8 位收口，错在这儿就说在这儿。</summary>
    public static string JoinNumberError(string? channelNo)
    {
        var trimmed = (channelNo ?? string.Empty).Trim();
        if (trimmed.Length == 0) return "先填频道号";
        if (trimmed.Length != 8) return "频道号是 8 位数字，请检查有没有多抄或少抄";
        return string.Empty;
    }

    /// <summary>密钥只有创建/轮换那一次显示过；留空接入是一次注定失败的请求，值得先拦下来。</summary>
    public static string JoinSecretError(string? secret) => string.IsNullOrWhiteSpace(secret)
        ? "还要填密钥（创建频道那一次显示的 32 位）"
        : string.Empty;

    /// <summary>Android 的 channel_join_title：用频道号 + 密钥把这台设备登记进该频道的接收集。</summary>
    public async Task<Outcome<string>> JoinChannelAsync(string channelNo, string secret,
        CancellationToken ct = default)
    {
        var problem = JoinNumberError(channelNo);
        if (problem.Length > 0) return Outcome<string>.Bad(new ArgumentException(problem));
        problem = JoinSecretError(secret);
        if (problem.Length > 0) return Outcome<string>.Bad(new ArgumentException(problem));
        try
        {
            await _client.JoinChannelAsync(Identity(), channelNo.Trim(), secret.Trim(), ct)
                .ConfigureAwait(false);
            return Outcome<string>.Good("已接入，稍后该频道的消息会出现在会话列表");
        }
        catch (Exception e)
        {
            return Outcome<string>.Bad(e);
        }
    }

    /// <summary>取消接入是幂等的：服务端本来就没接入时也回成功，界面按"已经不带这个频道"报。</summary>
    public async Task<Outcome<string>> LeaveChannelAsync(string channelNo, CancellationToken ct = default)
    {
        try
        {
            await _client.LeaveChannelAsync(Identity(), channelNo, ct).ConfigureAwait(false);
            return Outcome<string>.Good("已取消接入");
        }
        catch (Exception e)
        {
            return Outcome<string>.Bad(e);
        }
    }

    /// <summary>切换"只接收已接入频道"。成功时 Value 是<b>服务端回显</b>的那一档，不是界面刚才勾上的那一档。</summary>
    public async Task<Outcome<bool>> SetSubscriptionModeAsync(bool subscribedOnly,
        CancellationToken ct = default)
    {
        try
        {
            return Outcome<bool>.Good(
                await _client.SetSubscriptionModeAsync(Identity(), subscribedOnly, ct)
                    .ConfigureAwait(false));
        }
        catch (Exception e)
        {
            return Outcome<bool>.Bad(e);
        }
    }

    /// <summary>过滤模式那一行的说明，与 Android 的 channel_filter_only_desc 同一条口径：
    /// 接入关系不会因为关这个开关而丢，开关也不会因为接入第一个频道而自动打开。</summary>
    public const string FilterOnlyHint =
        "开启后这台电脑只收已接入频道的消息；其余频道仍在服务器上好着，只是这台不弹。"
        + "关掉即恢复全收，接入关系不会丢。";

    /// <summary>Android 的 <c>name.trim().ifEmpty { Build.MODEL }.take(24)</c>：留空就用本机设备名，上限 24 字。</summary>
    public static string ChannelNameFor(string? typed, string? deviceName)
    {
        var trimmed = (typed ?? string.Empty).Trim();
        if (trimmed.Length == 0) trimmed = (deviceName ?? string.Empty).Trim();
        if (trimmed.Length == 0) trimmed = "channel";
        return trimmed.Length <= 24 ? trimmed : trimmed.Substring(0, 24);
    }

    public async Task<Outcome<Issued>> CreateChannelAsync(string name, CancellationToken ct = default)
    {
        try
        {
            var identity = Identity();
            var created = await _client.CreateChannelAsync(identity,
                ChannelNameFor(name, identity.DeviceId), ct).ConfigureAwait(false);
            return Outcome<Issued>.Good(new Issued(created.ChannelNo, created.Secret));
        }
        catch (Exception e)
        {
            return Outcome<Issued>.Bad(e);
        }
    }

    public async Task<Outcome<Issued>> RotateSecretAsync(string channelNo, CancellationToken ct = default)
    {
        try
        {
            var rotated = await _client.RotateSecretAsync(Identity(), channelNo, ct).ConfigureAwait(false);
            return Outcome<Issued>.Good(new Issued(rotated.ChannelNo, rotated.Secret));
        }
        catch (Exception e)
        {
            return Outcome<Issued>.Bad(e);
        }
    }

    public async Task<Outcome<string>> ResetChannelNoAsync(string channelNo, CancellationToken ct = default)
    {
        try
        {
            return Outcome<string>.Good(await _client.ResetChannelNoAsync(Identity(), channelNo, ct)
                .ConfigureAwait(false));
        }
        catch (Exception e)
        {
            return Outcome<string>.Bad(e);
        }
    }

    public async Task<Outcome<string>> ArchiveChannelAsync(string channelNo, CancellationToken ct = default)
    {
        try
        {
            await _client.ArchiveChannelAsync(Identity(), channelNo, ct).ConfigureAwait(false);
            return Outcome<string>.Good("已归档");
        }
        catch (Exception e)
        {
            return Outcome<string>.Bad(e);
        }
    }

    /// <summary>Android 的 channel_clear：整个频道的历史与图片，sender 传 null 就是这个作用域。</summary>
    public async Task<Outcome<string>> ClearChannelAsync(string channelNo, CancellationToken ct = default)
    {
        try
        {
            var deleted = await _client.ClearMessagesAsync(Identity(), channelNo, null, ct).ConfigureAwait(false);
            return Outcome<string>.Good($"已清空 {deleted} 条");
        }
        catch (Exception e)
        {
            return Outcome<string>.Bad(e);
        }
    }

    /// <summary>字节数说成人话。服务端报表给的是精确到字节的整数，没人能在 41943040 上看出是 40MB。</summary>
    public static string BytesLabel(long bytes) => bytes switch
    {
        < 1024 => $"{bytes} B",
        < 1024 * 1024 => $"{bytes / 1024.0:0.#} KB",
        < 1024L * 1024 * 1024 => $"{bytes / (1024.0 * 1024):0.#} MB",
        _ => $"{bytes / (1024.0 * 1024 * 1024):0.#} GB"
    };

    /// <summary>容量报表那一句，抄 Android 的 channel_capacity_body 的字段顺序。</summary>
    public static string CapacityLine(Capacity c) =>
        $"消息 {c.TotalMessages} 条 / 正文 {BytesLabel(c.TotalTextBytes)}；" +
        $"图片 {c.TotalImages} 张 / {BytesLabel(c.TotalImageBytes)}；" +
        $"最早一条 {TimeFormat.DateLabel(c.EarliestMsgAt)}";

    public async Task<Outcome<string>> CapacityAsync(string channelNo, CancellationToken ct = default)
    {
        try
        {
            return Outcome<string>.Good(CapacityLine(
                await _client.CapacityAsync(Identity(), channelNo, ct).ConfigureAwait(false)));
        }
        catch (Exception e)
        {
            return Outcome<string>.Bad(e);
        }
    }

    /// <summary>
    /// 治理 PUT 的补丁（Android 的 ChannelGovernanceDialog 同一条口径）：
    /// <b>留空的输入框是"不提交这一项"</b>，把空框当 0 发过去会静默清掉用户没打算动的限额；
    /// 三个开关则总是提交——它们没有"没碰过"这种状态，屏幕上是什么就是什么。
    /// </summary>
    public static ChannelPatch GovernancePatch(string name, string? maxLevel, string maxImages, string rateLimit,
        string bodyBytes, bool strictAuth, bool muted, bool whitelist, string dailyMsg, string dailyImage,
        string dailyBytes)
    {
        var trimmed = (name ?? string.Empty).Trim();
        return new ChannelPatch(
            trimmed.Length == 0 ? null : trimmed,
            string.IsNullOrWhiteSpace(maxLevel) ? null : maxLevel,
            PositiveInt(maxImages), PositiveInt(rateLimit), PositiveInt(bodyBytes),
            strictAuth, muted, PositiveInt(dailyMsg), PositiveInt(dailyImage), NonNegativeInt(dailyBytes),
            whitelist);
    }

    /// <summary>非负整数才进补丁；空、非数字、负数都算"这一项不动"。</summary>
    public static int? PositiveInt(string? text) =>
        int.TryParse((text ?? string.Empty).Trim(), NumberStyles.None, CultureInfo.InvariantCulture, out var n)
            && n >= 0
            ? n
            : null;

    public static long? NonNegativeInt(string? text) =>
        long.TryParse((text ?? string.Empty).Trim(), NumberStyles.None, CultureInfo.InvariantCulture, out var n)
            && n >= 0
            ? n
            : null;

    /// <summary>一个字段都没填时补丁是空的，这一趟不必发（服务端会把空正文当成"整列清空"来读）。</summary>
    public async Task<Outcome<string>> SaveGovernanceAsync(string channelNo, ChannelPatch patch,
        CancellationToken ct = default)
    {
        if (patch.BuildBody() == null) return Outcome<string>.Bad(new ArgumentException("没有要保存的改动"));
        try
        {
            await _client.UpdateChannelAsync(Identity(), channelNo, patch, ct).ConfigureAwait(false);
            return Outcome<string>.Good("已保存");
        }
        catch (Exception e)
        {
            return Outcome<string>.Bad(e);
        }
    }

    // ---- 白名单 --------------------------------------------------------------------------------------

    public async Task<Outcome<IReadOnlyList<SenderEntry>>> SendersAsync(string channelNo,
        CancellationToken ct = default)
    {
        try
        {
            return Outcome<IReadOnlyList<SenderEntry>>.Good(
                await _client.SendersAsync(Identity(), channelNo, ct).ConfigureAwait(false));
        }
        catch (Exception e)
        {
            return Outcome<IReadOnlyList<SenderEntry>>.Bad(e);
        }
    }

    /// <summary>加一个或改一个。<see cref="SenderEntry.Enabled"/> 为 false 是停用而不是删除，保留是为了审计。</summary>
    public async Task<Outcome<string>> PutSenderAsync(string channelNo, string senderId, bool enabled,
        CancellationToken ct = default)
    {
        var id = (senderId ?? string.Empty).Trim();
        if (id.Length == 0) return Outcome<string>.Bad(new ArgumentException("先填 sender_id"));
        try
        {
            await _client.PutSenderAsync(Identity(), channelNo, id, enabled, ct).ConfigureAwait(false);
            return Outcome<string>.Good(enabled ? "已允许" : "已停用");
        }
        catch (Exception e)
        {
            return Outcome<string>.Bad(e);
        }
    }

    /// <summary>Android 的 channel_senders_empty：开启白名单模式却一个人都没加，等于把自己锁在门外。</summary>
    public static string WhitelistWarning(bool mode, int allowed) =>
        mode && allowed == 0 ? "已开启\"只允许白名单\"，但名单为空——这个频道现在收不到任何消息。" : string.Empty;

    // ---- 设备 ----------------------------------------------------------------------------------------

    public async Task<Outcome<IReadOnlyList<DeviceRow>>> DevicesAsync(CancellationToken ct = default)
    {
        try
        {
            var list = await _client.DevicesAsync(Identity(), ct).ConfigureAwait(false);
            return Outcome<IReadOnlyList<DeviceRow>>.Good(Mapped(list, DeviceRow.Of));
        }
        catch (Exception e)
        {
            return Outcome<IReadOnlyList<DeviceRow>>.Bad(e);
        }
    }

    public async Task<Outcome<string>> RevokeDeviceAsync(string deviceId, CancellationToken ct = default)
    {
        try
        {
            await _client.RevokeDeviceAsync(Identity(), deviceId, ct).ConfigureAwait(false);
            return Outcome<string>.Good("已吊销");
        }
        catch (Exception e)
        {
            return Outcome<string>.Bad(e);
        }
    }

    public async Task<Outcome<string>> RotateRecoveryCodeAsync(CancellationToken ct = default)
    {
        try
        {
            return Outcome<string>.Good(await _client.RotateRecoveryCodeAsync(Identity(), ct)
                .ConfigureAwait(false));
        }
        catch (Exception e)
        {
            return Outcome<string>.Bad(e);
        }
    }

    /// <summary>恢复码只在注册/join 那一次回答里给过；本机没存过就要先轮换一次才有码可抄。</summary>
    public static string RecoveryText(string? saved) => string.IsNullOrWhiteSpace(saved)
        ? "本机未保存恢复码"
        : saved;

    // ---- 通知（本机开关）------------------------------------------------------------------------------

    /// <summary>分钟数 → "HH:mm"，界面上起止两格读的就是这个形状（与 <see cref="MinuteOf"/> 互为逆）。</summary>
    public static string ClockOf(int minute)
    {
        var clamped = ClientSettings.ClampMinute(minute);
        return $"{clamped / 60:00}:{clamped % 60:00}";
    }

    /// <summary>空与格式不对都回 null——"这一格没填"和"填成 0 点"是两件事，不能替用户猜。</summary>
    public static int? MinuteOf(string? text)
    {
        var parts = (text ?? string.Empty).Trim().Split(':');
        if (parts.Length != 2) return null;
        if (!int.TryParse(parts[0], NumberStyles.None, CultureInfo.InvariantCulture, out var h)
            || !int.TryParse(parts[1], NumberStyles.None, CultureInfo.InvariantCulture, out var m)) return null;
        if (h < 0 || h > 23 || m < 0 || m > 59) return null;
        return h * 60 + m;
    }

    /// <summary>Android 的静默时段那一行：跨午夜要说清是"今天到明天"，否则 22:00–08:00 看着像写反了。</summary>
    public static string QuietLine(bool enabled, int start, int end)
    {
        if (!enabled) return "未开启";
        var crosses = end <= start;
        return $"{ClockOf(start)}–{ClockOf(end)}{(crosses ? "（次日）" : string.Empty)}";
    }

    /// <summary>把界面上的两个格子变成设置本身。填歪了的那一格保持原值，不为一个 25:00 把整晚的窗口改掉。</summary>
    public static ClientSettings QuietWindowOf(ClientSettings current, string start, string end)
    {
        var from = MinuteOf(start) ?? current.QuietStartMinute;
        var to = MinuteOf(end) ?? current.QuietEndMinute;
        return current.WithQuietWindow(current.QuietEnabled, from, to);
    }

    private static IReadOnlyList<TOut> Mapped<TIn, TOut>(IReadOnlyList<TIn> source, Func<TIn, TOut> map)
    {
        // 复制一份：设置窗拿到的是快照，服务端在那之后的改动不该让已经画出来的那格跟着变
        var copy = new List<TOut>(source.Count);
        foreach (var item in source) copy.Add(map(item));
        return copy;
    }
}
