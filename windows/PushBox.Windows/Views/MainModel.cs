using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Logging;
using PushBox.Windows.Net;
using PushBox.Windows.Sync;

namespace PushBox.Windows.Views;

/// <summary>§8.1 对话页 <c>⋮</c> 里的三档（D17：不做常驻 Tab）。判定用会话的 last_read_seq，与 Android 的 read_state 同效。</summary>
public enum ConversationFilter
{
    All,
    Unread,
    Read
}

public sealed record ConversationItem(string ChannelNo, string SenderId, string DisplayName,
    string? Summary, long LastMsgAt, int UnreadCount, bool Pinned, bool Muted, string? LastLevel,
    long LastReadSeq, string? AvatarPath = null)
{
    public string ClockText => TimeFormat.Clock(LastMsgAt);

    /// <summary>§4.5 的头像对象标识：sender_id 的 sha256，本机能算出来，不等 <c>sender_avatar</c> 那一列。</summary>
    public string AvatarToken => DeviceClient.AvatarTokenFor(SenderId);

    /// <summary>Android 那边未读不是红点而是纯数字，&gt;99 收敛成 "99+"（§9.1 的 badge.unread 只上颜色）。</summary>
    public string? UnreadText => UnreadCount <= 0 ? null : UnreadCount > 99 ? "99+" : UnreadCount.ToString();

    /// <summary>置顶/静音在 Android 是名称后面追加的灰字，不是图标；照抄。</summary>
    public string FlagsText
    {
        get
        {
            var flags = new List<string>();
            if (Pinned) flags.Add("置顶");
            if (Muted) flags.Add("静音");
            return flags.Count == 0 ? string.Empty : " · " + string.Join(" · ", flags);
        }
    }

    /// <summary>§9.1 的克制口径：info 不着色，所以只有 warning/error 才有会话行小圆点。</summary>
    public string? LevelDotKey => TimeFormat.LevelAccent(LastLevel);
}

public abstract record TimelineItem;

public sealed record DateDivider(string Label) : TimelineItem;

public sealed record MessageItem(string MsgId, long Seq, string ChannelNo, string SenderId,
    string Text, string Level, string? MsgTypeLabel, long CreatedAt, int MediaCount, string? MediaStatus,
    string? LinkUrl, bool Truncated = false, bool Expanding = false,
    string? SenderName = null, string? SenderAvatarToken = null, bool ShowTimestamp = true)
    : TimelineItem
{
    public string StampText => TimeFormat.Stamp(CreatedAt);
    public string DayKey => TimeFormat.DayKey(CreatedAt);

    /// <summary>气泡左侧 3dp 竖条：info 没有（与 Android 的 levelAccent 同一张表）。</summary>
    public string? AccentKey => TimeFormat.LevelAccent(Level);

    /// <summary>
    /// §5.2 那句"展开全文"（Android 的 message_fulltext / message_fulltext_loading 两行文案）。
    /// 取回成功后库里 <c>truncated</c> 被清掉，这一行跟着消失，所以它只在"还没展开"时出现。
    /// </summary>
    public string? ExpandLabel => Truncated ? Expanding ? "全文取回中…" : "展开全文" : null;
}

/// <summary>
/// 一条搜索命中：本地与服务端统一成这个形状才能合并、去重、并排渲染（Android 的 SearchEntry 同理）。
/// <see cref="FromServer"/> 不进文案，由 <see cref="SourceLabel"/> 落成行尾那句"本机/服务端"。
/// </summary>
public sealed record SearchItem(string Id, string ChannelNo, string SenderId, string SenderName,
    string Text, long CreatedAt, bool FromServer)
{
    public string SourceLabel => FromServer ? "服务端" : "本机";
    public string DateText => TimeFormat.DateLabel(CreatedAt);
}

/// <summary>时间格式与日期分节，逐条对齐 Android 的 clock/stamp/dayKey/dateLabel。</summary>
public static class TimeFormat
{
    private static readonly string[] AccentKeys = { "Level.Error", "Level.Warning" };

    public static string Clock(long millis) =>
        millis <= 0 ? string.Empty : Local(millis).ToString("HH:mm", CultureInfo.InvariantCulture);

    public static string Stamp(long millis) =>
        millis <= 0 ? string.Empty : Local(millis).ToString("MM-dd HH:mm", CultureInfo.InvariantCulture);

    /// <summary>天键用 yyyyMMdd：跨年时前四位天然把两年分开，分节判定就是它跟前一条比。</summary>
    public static string DayKey(long millis) =>
        Local(millis).ToString("yyyyMMdd", CultureInfo.InvariantCulture);

    public static string DateLabel(long millis)
    {
        if (millis <= 0) return string.Empty;
        var now = DateTimeOffset.Now.ToUnixTimeMilliseconds();
        var today = DayKey(now);
        var day = DayKey(millis);
        if (day == today) return "今天";
        // "昨天"按 24 小时前那一格的天键算，与 Android 完全一致（夏令时边界的口径也因此一致）
        if (day == DayKey(now - 24L * 60 * 60 * 1000)) return "昨天";
        return day.Substring(0, 4) == today.Substring(0, 4)
            ? Local(millis).ToString("M月d日", CultureInfo.InvariantCulture)
            : Local(millis).ToString("yyyy年M月d日", CultureInfo.InvariantCulture);
    }

    /// <summary>资源字典里颜色 token 的键名；返回 null 就是"不着色"，比返回一个透明色更好读。</summary>
    public static string? LevelAccent(string? level) => level switch
    {
        "error" => AccentKeys[0],
        "warning" => AccentKeys[1],
        _ => null
    };

    /// <summary>§7.4 的类型标签：非纯文本才给一个可见类型，text 返回 null（Android 的 typeLabel 同表）。</summary>
    public static string? TypeLabel(string msgType) => msgType switch
    {
        "markdown" => "Markdown",
        "image" => "图片",
        "file" => "文件",
        "audio" => "音频",
        "video" => "视频",
        "link" => "链接",
        _ => null
    };

    private static DateTime Local(long millis) =>
        DateTimeOffset.FromUnixTimeMilliseconds(millis).LocalDateTime;
}

/// <summary>
/// 主窗口的全部判定都在这儿（W5 定下的口径：XAML 只放控件）。它不引用任何 WPF 类型，
/// 所以会话排序、筛选、日期分节、两段搜索合并这些能被普通用例直接断言。
/// </summary>
public sealed class MainModel
{
    /// <summary>窄窗口退化单栏的分界（§8.1）。设备无关单位，与 XAML 里的宽度同一基准。</summary>
    public const double NarrowPaneBelow = 720;

    public const int PageLimit = 50;

    /// <summary>§9.2 的气泡最大宽占比：宽栏 72%，窄屏（单栏）78%。</summary>
    public const double WideBubbleFraction = 0.72;

    public const double NarrowBubbleFraction = 0.78;

    /// <summary>再封一道绝对上限：1080p 全屏时 72% 有 1300 多像素，一行正文长到眼睛跟不上。</summary>
    public const double TimelineMaxWidth = 900;

    private readonly DeviceStore _db;
    private readonly IdentityStore _identities;
    private readonly ReadReporter _reporter;
    private readonly SearchService _search;
    private readonly DeleteReporter _deletions;
    private readonly FulltextService? _fulltexts;

    private ConversationItem? _selected;
    private ConversationFilter _filter = ConversationFilter.All;

    /// <summary>正在等全文回捞的那几条（只影响这一行的文案，不改库里的任何状态）。</summary>
    private readonly HashSet<string> _expanding = new(StringComparer.Ordinal);

    /// <summary>当前时间线窗口的**上界**（取这一批时用的 from_seq）：打开会话时是"最新"，之后不变。</summary>
    private long _windowBelow = long.MaxValue;

    /// <summary>下一页的边界，只由 <see cref="LoadEarlier"/> 往前推（见该方法）。</summary>
    private long _olderThanSeq = long.MaxValue;

    private List<MessageRow> _window = new();
    private List<SearchItem> _results = new();

    public MainModel(DeviceStore db, IdentityStore identities, ReadReporter reporter, SearchService search,
        DeleteReporter deletions, FulltextService? fulltexts = null)
    {
        _db = db;
        _identities = identities;
        _reporter = reporter;
        _search = search;
        _deletions = deletions;
        _fulltexts = fulltexts;
    }

    /// <summary>状态变了就该重画：列表、时间线、搜索结果都从这里现取，所以不需要自己维护集合。</summary>
    public event Action? Changed;

    public ConversationItem? Selected => _selected;
    public ConversationFilter Filter => _filter;

    /// <summary>搜索框里的词，只为回显高亮范围；真正的查询参数在 <see cref="SearchAsync"/> 那次调用里。</summary>
    public string SearchTerm { get; private set; } = string.Empty;
    public bool IsSearching { get; private set; }
    public IReadOnlyList<SearchItem> Results => _results;
    public string? SearchError { get; private set; }

    /// <summary>§7.4 的"仅本会话"开关：给了作用域就只查当前这条会话线（对话页那个框）。</summary>
    public bool SearchInsideConversation { get; set; }

    public IReadOnlyList<ConversationItem> Conversations() =>
        _db.Conversations().Select(AsItem).ToList();

    /// <summary>托盘那一行的未读总数（§7.3 的角标在 PC 上没有 launcher 可挂，落点是 tooltip）。</summary>
    public int TotalUnread() => Conversations().Sum(c => c.UnreadCount);

    public IReadOnlyList<TimelineItem> Timeline() =>
        BuildTimeline(_window, _selected?.LastReadSeq ?? 0L, _filter);

    /// <summary>筛选提示条那句人话（Android 的 filter_hint_*，点击回"全部"）。</summary>
    public string? FilterHint => _filter switch
    {
        ConversationFilter.Unread => "当前只看未读 · 点此处显示全部",
        ConversationFilter.Read => "当前只看已读 · 点此处显示全部",
        _ => null
    };

    public string EmptyText => _filter == ConversationFilter.All ? "还没有消息" : "该筛选下没有消息";

    public bool IsNarrow(double width) => width < NarrowPaneBelow;

    /// <summary>
    /// 气泡那一竖列的宽度上限：§9.2 的 72%（窄屏 78%），再封在 900 之内。
    /// 传进来的是**右栏**宽度而不是窗口宽度——左栏那 320 像素里没有气泡，把它算进百分比会越算越小。
    /// </summary>
    public static double BubbleMaxWidth(double rightPaneWidth, bool narrow) =>
        Math.Round(Math.Min(900, Math.Max(0, rightPaneWidth)
            * (narrow ? NarrowBubbleFraction : WideBubbleFraction)));

    public void SetFilter(ConversationFilter filter)
    {
        if (_filter == filter) return;
        _filter = filter;
        Raise();
    }

    /// <summary>
    /// 打开一条会话：先出内容、再把红点清掉。已读上报是"本地立刻清 + 攒批上报"两件事（W9 的口径），
    /// 所以这里只调 MarkRead，不等网络。
    /// </summary>
    public void Open(string channelNo, string senderId)
    {
        var row = _db.Conversations().FirstOrDefault(c => c.ChannelNo == channelNo && c.SenderId == senderId);
        if (row is null) return;

        _windowBelow = long.MaxValue;
        _olderThanSeq = long.MaxValue;
        _window = _db.MessagesPage(channelNo, senderId, _windowBelow, PageLimit).ToList();
        _reporter.MarkRead(channelNo, senderId);
        // 刚清完红点，会话行的未读数与 last_read_seq 都变了，选中项按库里的新值重取一次：
        // 时间线的"未读/已读"筛选读的就是这个游标，用旧值会把刚看过的那几条算成未读
        _selected = ReadSelected(channelNo, senderId);
        if (_selected is null)
        {
            // 就在这中间被别的端清空了：这一屏没有可展示的东西，别留一个空标题的会话
            CloseConversation();
            return;
        }

        Raise();
    }

    public void CloseConversation()
    {
        _selected = null;
        _window = new List<MessageRow>();
        _windowBelow = long.MaxValue;
        _olderThanSeq = long.MaxValue;
        Raise();
    }

    /// <summary>
    /// 帧刚落库时由窗口调用：把当前会话线的那一批重取一遍。它不发 <see cref="Changed"/>——
    /// 调用方紧接着就要重画，多一次广播只会多一次重画。
    /// 重取的范围是"从打开时那个上界起的 <c>已加载数 + 一页</c>"，所以往回翻过的历史不会被这次刷新削掉。
    /// 返回**这一批里新出现的条数**：§9.3 的"1 条新消息"胶囊读的就是它（用户正往上翻历史时，
    /// 窗口那半会拿着这个数堆胶囊而不是把滚动位置拽走）。
    /// </summary>
    public int Reload()
    {
        if (_selected is null) return 0;
        var known = new HashSet<string>(_window.ConvertAll(m => m.MsgId));
        _window = _db.MessagesPage(_selected.ChannelNo, _selected.SenderId, _windowBelow,
            Math.Max(PageLimit, _window.Count + PageLimit)).ToList();
        // 游标要跟着库走：他端报的已读（§5.3 的 read 帧）和 tombstone 都改的是库里的值
        _selected = ReadSelected(_selected.ChannelNo, _selected.SenderId) ?? _selected;
        var landed = 0;
        foreach (var row in _window)
        {
            if (!known.Contains(row.MsgId)) landed++;
        }

        return landed;
    }

    /// <summary>会话可能在这中间被清空（tombstone 到只剩 0 条），那就退回"没有选中"而不是拿旧值撑着一个空窗口。</summary>
    private ConversationItem? ReadSelected(string channelNo, string senderId)
    {
        var row = _db.Conversations().FirstOrDefault(c => c.ChannelNo == channelNo && c.SenderId == senderId);
        return row is null ? null : AsItem(row);
    }

    /// <summary>
    /// 向上加载更早的一页：下一页把本页最小 seq 减 1 传进去，所以既不会重复也不会漏（DeviceStore 的口径）。
    /// </summary>
    public bool LoadEarlier()
    {
        if (_selected is null || _window.Count == 0) return false;
        var from = Math.Min(_olderThanSeq, _window.Min(m => m.Seq) - 1);
        if (from < 1) return false;
        var page = _db.MessagesPage(_selected.ChannelNo, _selected.SenderId, from, PageLimit);
        if (page.Count == 0) return false;
        _olderThanSeq = from;
        _window = page.Concat(_window).ToList();
        Raise();
        return true;
    }

    /// <summary>
    /// §7.3 会话行的那几项本机设置。它们和"置顶/静音"在服务端没有对应接口，所以只落本机库这一行——
    /// 与 Android 同一条口径（<c>conv_action_pin</c> / <c>conv_action_mute</c> 走的也是 DAO）。
    /// 会话行不在库里时（刚被清空）这三句都是废操作，直接返回。
    /// </summary>
    public void TogglePinned(string channelNo, string senderId) => Flip(channelNo, senderId, true, false);

    public void ToggleMuted(string channelNo, string senderId) => Flip(channelNo, senderId, false, true);

    private void Flip(string channelNo, string senderId, bool pinned, bool muted)
    {
        var row = _db.Conversations().FirstOrDefault(c => c.ChannelNo == channelNo && c.SenderId == senderId);
        if (row is null) return;
        _db.UpdateConversation(channelNo, senderId, pinned ? !row.Pinned : null,
            muted ? !row.Muted : null, null, null);
        if (_selected is { ChannelNo: var ch, SenderId: var sid } && ch == channelNo && sid == senderId)
            _selected = ReadSelected(channelNo, senderId);
        Raise();
    }

    /// <summary>
    /// §7.3「设头像」的本机那一半：选中的那张图要复制进应用目录而不是把资源管理器的路径存进库——
    /// 那个路径随时会变成" Downloads 里那张已经删掉的图"。文件名用 sender_id 的 sha256，
    /// 所以同一个人换头像就是覆盖写，不会在本机攒一抽屉历史。传 null 取消，会话行回到远端头像或首字占位。
    /// </summary>
    public void SetAvatar(string channelNo, string senderId, string? sourceFile)
    {
        string? stored = null;
        if (sourceFile is { Length: > 0 })
        {
            Directory.CreateDirectory(AvatarDirectory);
            var target = Path.Combine(AvatarDirectory, DeviceClient.AvatarTokenFor(senderId)
                + Path.GetExtension(sourceFile).ToLowerInvariant());
            File.Copy(sourceFile, target, true);
            stored = target;
        }

        _db.UpdateConversation(channelNo, senderId, null, null, null, stored ?? string.Empty);
        if (_selected is { ChannelNo: var ch, SenderId: var sid } && ch == channelNo && sid == senderId)
            _selected = ReadSelected(channelNo, senderId);
        Raise();
    }

    /// <summary>自定义头像的落点，与 pushbox.db 同层（<c>%LOCALAPPDATA%\PushBox\avatars</c>）。</summary>
    public static string AvatarDirectory => Path.Combine(IdentityStore.DefaultDirectory, "avatars");

    /// <summary>§7.3 的重命名：空串就是"取消自定义名"，落回服务端下发的那个发送者名。</summary>
    public void Rename(string channelNo, string senderId, string customName)
    {
        _db.UpdateConversation(channelNo, senderId, null, null, customName.Trim(), null);
        _selected = ReadSelected(channelNo, senderId) ?? _selected;
        Raise();
    }

    /// <summary>§9.3 那颗胶囊的文案，单复数在中文里只差一个数量。</summary>
    public static string NewBelowLabel(int count) => $"{count} 条新消息";

    /// <summary>
    /// §5.2 展开全文（Android 的 <c>message_fulltext</c> 那个按钮）。同一 id 在取回期间再点一次是废操作，
    /// 所以这里挡住；取回成功后库里那条换了正文、标记也清了，<see cref="RepullCurrent"/> 把新那一份读回来。
    /// 没有 <see cref="FulltextService"/>（用例里的窗口）时直接返回 false，那一行按钮就此不再响应。
    /// </summary>
    public async Task<bool> ExpandAsync(string msgId)
    {
        if (_fulltexts is null || !_expanding.Add(msgId)) return false;
        Raise();
        var fetched = false;
        try
        {
            fetched = await _fulltexts.FetchAsync(msgId).ConfigureAwait(false);
        }
        catch (Exception e)
        {
            // 展开失败只是"这一条还是半截"，时间线照常能看：不弹错误条，只把这行按钮放回原样
            AppLog.FulltextRefused(e.GetType().Name);
        }
        finally
        {
            _expanding.Remove(msgId);
        }

        if (fetched) RepullCurrent();
        Raise();
        return fetched;
    }

    /// <summary>
    /// §9.3 的二次确认文案放在模型里（和 <see cref="NewBelowLabel"/>、<see cref="FilterHint"/> 同一个理由：
    /// 人话要能被用例钉住）。删一条那两行沿用 Android 的 delete_dialog_*；清空那两行必须改 Android 的措辞——
    /// 它写的是"服务端仍保留历史"，那是 Android 还没接 §5.2 清空接口的现状，PC 这一版是真会通知服务端的。
    /// </summary>
    public static string DeleteMessageQuestion => "删除这条消息？";

    public static string DeleteMessageDetail =>
        "本机立即隐藏该消息，并通知服务端落 tombstone 同步到其他设备；此操作不可撤销。";

    public static string ClearConversationQuestion(string displayName) => $"清空「{displayName}」？";

    public static string ClearConversationDetail =>
        "删除这条会话在本地与服务端的全部消息，手机与其他设备会一起消失，重新安装也不会再回放；"
        + "本条会话会从列表里移除，下一条消息到达时重新出现。此操作不可撤销。";

    /// <summary>
    /// 删一条消息（§8.4 W14）。两次于重画之间夹着一次网络往返，所以 <see cref="DeleteReporter"/> 的
    /// 回调里做乐观重画、返回后再做一次：前者让屏幕上那条立刻消失，后者把服务端失败之后的放回结果补上。
    /// 第二次 <see cref="RepullCurrent"/> 只在回滚时发，是因为成功那条路已经画过了，再广播一次只是多一次重绘。
    /// </summary>
    public async Task<DeleteResult> DeleteMessageAsync(string msgId)
    {
        var result = await _deletions.DeleteMessageAsync(msgId, RepullCurrent).ConfigureAwait(false);
        if (result.Outcome == DeleteOutcome.RolledBack) RepullCurrent();
        Raise();
        return result;
    }

    /// <summary>
    /// 清空一条会话。乐观那一刻右栏要退回"没有选中"（那条会话已经不在这台机器的列表里了），
    /// 所以传进去的回调按"是不是当前打开的那条"分流；回滚时会话行重新出现在左栏，右栏不用抢回来。
    /// </summary>
    public async Task<DeleteResult> ClearConversationAsync(string channelNo, string senderId)
    {
        var isOpen = _selected is not null && _selected.ChannelNo == channelNo && _selected.SenderId == senderId;
        var result = await _deletions.ClearConversationAsync(channelNo, senderId,
            () =>
            {
                // CloseConversation 自己会广播一次，这是乐观那一刻
                if (isOpen) CloseConversation();
            }).ConfigureAwait(false);
        // 回滚时放回的是整段历史，右栏不会自己找回来：会话重新出现在左栏，等用户再点一次。
        // 所以这里只需要 Raise 让列表重画，不需要 RepullCurrent——它读的那条会话本来就没打开着。
        Raise();
        return result;
    }

    /// <summary>
    /// 按库里的真值把当前会话那一批和那一行重取一遍。删除的两个时刻（乐观移除后、回滚后）共用它，
    /// 因为它们要的其实是同一件事——"屏幕照库里现在的样子画"。<see cref="Reload"/> 不能代替它：
    /// 那个方法是给"来了新消息"用的，会返回新出现条数并让胶囊计数，而这里少了几条是本机自己干的。
    /// 会话行不在库里（清空、或删掉了最后一条）就把右栏退回未选中，而不是留一个空标题的窗口。
    /// </summary>
    private void RepullCurrent()
    {
        if (_selected is null)
        {
            _window.Clear();
            return;
        }

        _window = _db.MessagesPage(_selected.ChannelNo, _selected.SenderId, _windowBelow,
            Math.Max(PageLimit, _window.Count + PageLimit)).ToList();
        _selected = ReadSelected(_selected.ChannelNo, _selected.SenderId);
        if (_selected is null)
        {
            _windowBelow = long.MaxValue;
            _olderThanSeq = long.MaxValue;
        }
    }

    /// <summary>输入即查：只碰本机库，一次网络都不发，所以离线也能查到自己缓存过的内容。</summary>
    public void SearchLocal(string term)
    {
        SearchTerm = term?.Trim() ?? string.Empty;
        if (SearchTerm.Length == 0)
        {
            IsSearching = false;
            _results = new List<SearchItem>();
            SearchError = null;
            Raise();
            return;
        }
        IsSearching = true;
        _results = _search.LocalHits(SearchTerm, ScopeChannel(), ScopeSender()).Select(h => AsItem(h, false)).ToList();
        Raise();
    }

    /// <summary>
    /// 回车（Android 那边是 300ms 防抖后自动发）：本地那段立刻有，服务端那段等它回来。
    /// </summary>
    public async Task SearchAsync(string term, CancellationToken ct = default)
    {
        SearchLocal(term);
        if (SearchTerm.Length == 0) return;
        var set = await _search.SearchAsync(SearchTerm, ScopeChannel(), ScopeSender(), ct: ct)
            .ConfigureAwait(false);
        _results = set.Local.Select(h => AsItem(h, false)).Concat(set.Server.Select(h => AsItem(h, true))).ToList();
        SearchError = set.Error;
        Raise();
    }

    public void ExitSearch()
    {
        SearchTerm = string.Empty;
        IsSearching = false;
        SearchError = null;
        _results = new List<SearchItem>();
        Raise();
    }

    /// <summary>同步引擎的状态翻译成顶栏那一行话（§8.1"离线，正在重连"）。</summary>
    public static string DescribeState(SyncState state) => state switch
    {
        SyncState.Online => "在线",
        SyncState.Connecting => "连接中",
        SyncState.Idle => "未连接",
        SyncState.Offline off => $"离线，正在重连（{off.Reason}）",
        SyncState.Blocked blocked => blocked.Reason,
        _ => "未连接"
    };

    public DeviceIdentity Identity() => _identities.Load();

    /// <summary>顶栏那个点的颜色：只有非在线才上色，在线时点本身不出现（§9.1 的克制）。</summary>
    public static bool NeedsAttention(SyncState state) => state is not SyncState.Online;

    private string? ScopeChannel() => SearchInsideConversation ? _selected?.ChannelNo : null;
    private string? ScopeSender() => SearchInsideConversation ? _selected?.SenderId : null;

    private static ConversationItem AsItem(ConversationRow row) =>
        new(row.ChannelNo, row.SenderId, row.DisplayName, row.LastSummary, row.LastMsgAt,
            row.UnreadCount, row.Pinned, row.Muted, row.LastLevel, row.LastReadSeq, row.AvatarPath);

    private static SearchItem AsItem(SearchHit hit, bool fromServer) =>
        new(hit.Id, hit.ChannelNo, hit.SenderId, hit.SenderName ?? hit.SenderId,
            string.IsNullOrWhiteSpace(hit.Title) ? hit.Snippet : hit.Title + " " + hit.Snippet,
            hit.CreatedAt, fromServer);

    /// <summary>
    /// 时间线 = 筛选后的消息 + 日期分节头。分节触发只看"与上一条是否同一天"，与 Android 一致；
    /// 筛选把整段滤空时返回空表，由 UI 落 <see cref="EmptyText"/>。
    /// </summary>
    public IReadOnlyList<TimelineItem> BuildTimeline(IReadOnlyList<MessageRow> rows,
        long lastReadSeq, ConversationFilter filter)
    {
        var kept = rows.Where(m => filter switch
        {
            ConversationFilter.Unread => m.Seq > lastReadSeq,
            ConversationFilter.Read => m.Seq <= lastReadSeq,
            _ => true
        }).ToList();

        var items = new List<TimelineItem>(kept.Count * 2);
        string? previousDay = null;
        long? previousTime = null;
        foreach (var m in kept)
        {
            var day = TimeFormat.DayKey(m.CreatedAt);
            if (day != previousDay) items.Add(new DateDivider(TimeFormat.DateLabel(m.CreatedAt)));
            previousDay = day;
            
            // 时间智能分组：5分钟内的连续消息只显示一次时间戳
            var showTimestamp = previousTime == null || 
                (m.CreatedAt - previousTime.Value) > 5 * 60 * 1000L ||
                day != TimeFormat.DayKey(previousTime.Value);
            previousTime = m.CreatedAt;
            
            // body ?: summary ?: title 三选一：Android 也是三选一，同一条消息两端才会显示同一段文字
            // §9.2 对齐 Android MessageBubble：带上发送者名称与头像 token（从当前会话取）
            var senderName = _selected?.DisplayName;
            var avatarToken = _selected is not null ? DeviceClient.AvatarTokenFor(_selected.SenderId) : null;
            items.Add(new MessageItem(m.MsgId, m.Seq, m.ChannelNo, m.SenderId,
                m.Body ?? m.Summary ?? m.Title ?? string.Empty, m.Level,
                TimeFormat.TypeLabel(m.MsgType), m.CreatedAt, m.MediaCount, m.MediaStatus, m.LinkUrl,
                m.Truncated, _expanding.Contains(m.MsgId), senderName, avatarToken, showTimestamp));
        }
        return items;
    }

    private void Raise() => Changed?.Invoke();
}
