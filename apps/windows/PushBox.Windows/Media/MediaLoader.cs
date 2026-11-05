using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Logging;
using PushBox.Windows.Net;

namespace PushBox.Windows.Media;

/// <summary>
/// 一个图位现在的状态。<b>这四类"没有字节"是分开的</b>：§5.2 的服务端把槽位状态原样写在 404 的
/// hint 里，pending 是"再等等"，failed/deleted/purged 是"别再等了"，网络问题则是"可以重试"。
/// 把它们全渲染成同一个破图就是 W10 验收里那句"404+状态映射成占位而不是破图"要防的事。
/// </summary>
public enum MediaState
{
    /// <summary>拿到字节了（来自内存/磁盘缓存，或刚从服务端取回）。</summary>
    Ready,

    /// <summary>服务端还在抓这张外链（§4.5 的 media_objects.status='pending'），图位继续转圈。</summary>
    Pending,

    /// <summary>抓取失败或队列排不下（failed）：正文照常显示，这一格标"图片已失效"。</summary>
    Failed,

    /// <summary>消息已 tombstone，对象还在 7 天宽限期内（deleted）。</summary>
    Deleted,

    /// <summary>宽限期已过、对象真删了（purged），字节永远回不来。</summary>
    Purged,

    /// <summary>库里根本没有这一行/这个来源（404 hint=media 或 avatar）。</summary>
    Missing,

    /// <summary>这一帧压根没带图源（<c>sender_avatar</c> 是 NULL：§4.5 的转存是异步的，首帧常带不到）。
    /// 这不是错误，UI 该落回首字占位头像，所以它和 <see cref="Missing"/> 分开。</summary>
    NoSource,

    /// <summary>连不上、超时、TLS、鉴权失败：这一格留给用户点重试，不谎称图片已失效。</summary>
    NetworkError,
}

/// <summary>图位的一次取图结果。Bytes 只在 <see cref="MediaState.Ready"/> 时非空。</summary>
public sealed record MediaAsset(MediaState State, byte[]? Bytes = null, string? ContentType = null,
    string? Reason = null)
{
    public bool HasBytes => Bytes is { Length: > 0 };

    /// <summary>W12 的占位文案：只有"字节不会再来"才叫失效。NetworkError 的文案带原因，Pending 继续转圈。</summary>
    public string? Placeholder => State switch
    {
        MediaState.Failed or MediaState.Deleted or MediaState.Purged or MediaState.Missing => "图片已失效",
        MediaState.NetworkError => Reason,
        _ => null,
    };

    internal static MediaAsset Ready(byte[] bytes, string? contentType) =>
        new(MediaState.Ready, bytes, contentType ?? ImageMime.Sniff(bytes));
}

/// <summary>
/// W10：把"取一张图"这件事收成一条链——先问缓存，问到就用，问不到才发签名请求，
/// 取回来的字节按同一个键落缓存。<see cref="MediaCache"/> 命中时一次网络都不碰，
/// 所以"断网时图片来自缓存"是这条顺序的直接结果，而不需要一个"离线模式"开关。
/// </summary>
public sealed class MediaLoader
{
    /// <summary>
    /// 同一个发送者两次<b>真回源</b>的最小间隔，两条路共用这一把尺子：帧那边"又收到这个人一条带
    /// <c>sender_avatar</c> 的帧"的节流（<see cref="InvalidateAvatar"/>），以及手里那份旧字节什么时候
    /// 该复核（<see cref="AvatarAsync"/>）。头像按 (频道, 发送者) 覆盖写、token 不变，本机分辨
    /// "换没换"只有时间这一个信号；十分钟与 Android 的正缓存有效期同一条口径。
    /// </summary>
    public static readonly TimeSpan AvatarRevalidateInterval = TimeSpan.FromMinutes(10);

    private readonly DeviceClient _client;
    private readonly MediaCache _cache;

    /// <summary>用例要把时间拨过去：真等十分钟没法跑，而这一档的语义就是"过没过"。</summary>
    private readonly Func<DateTimeOffset> _now;

    /// <summary>每个 token 上一次作废的时刻（UTC ticks）。只用来节流，过期条目在下一次插入时清掉。</summary>
    private readonly Dictionary<string, long> _avatarSignals = new(StringComparer.Ordinal);

    /// <summary>每个 token 上一次<b>从服务端真拿到字节</b>的时刻（UTC ticks）。TTL 复核认这把表，
    /// 而命中缓存那一次不许记它——每画一次就把 deadline 往后推一格，那就永远过不了期。</summary>
    private readonly Dictionary<string, long> _avatarLanded = new(StringComparer.Ordinal);

    private readonly object _signalGate = new();

    /// <summary>
    /// 正在飞的头像：<c>token → 那一次取图</c>。头像一格一次重画就敲一次取图，而一屏十二格是同一个人
    /// （实测打开一条会话敲了 22 次，全打在同一个 token 上）：那二十几次挤在建树的几轮派发里，
    /// 每一轮开头谁都还没等到回话，合流之前它们就是 22 个真请求（合流后实测 6 个）。
    /// 只合"同时"的那几次，不缓存失败结果：点重试仍然会真的再发一次。
    /// </summary>
    private readonly Dictionary<string, Task<MediaAsset>> _avatarFlight = new(StringComparer.Ordinal);
    private readonly object _flightGate = new();

    /// <summary>某个 token 的头像被作废了：画着它的控件要重新取一次字节。可能在帧线程上触发。</summary>
    public event Action<string>? AvatarInvalidated;

    public MediaLoader(DeviceClient? client = null, MediaCache? cache = null,
        Func<DateTimeOffset>? clock = null)
    {
        _client = client ?? new DeviceClient();
        _cache = cache ?? new MediaCache();
        // 匿名函数不能直接放在 ?? 右边，得包一层括号
        _now = clock ?? (static () => DateTimeOffset.UtcNow);
    }

    public MediaCache Cache => _cache;

    /// <summary>图位键：<c>{msg_id}#{idx}#{t|f}</c>。缩略图和原图是两份字节，必须分开存
    /// （点开大图时不能把小图当大图显示，也不能因为看过大图就省掉一次缩略图请求）。</summary>
    public static string ImageKey(string msgId, int idx, bool thumb) =>
        msgId + "#" + idx.ToString() + (thumb ? "#t" : "#f");

    /// <summary>头像键：token 本身就是 sender_id 的 sha256，不另造标识。</summary>
    public static string AvatarKey(string token) => "avatar#" + token;

    /// <summary>取一条消息的第 idx 个图位。<paramref name="thumb"/> = true 要缩略图，false 要大图。</summary>
    public Task<MediaAsset> ImageAsync(DeviceIdentity identity, string msgId, int idx, bool thumb,
        CancellationToken ct = default)
        => LoadAsync(ImageKey(msgId, idx, thumb), true,
            token => _client.MediaAsync(identity, msgId, idx, thumb, token), ct);

    /// <summary>§4.5 头像：<c>sender_avatar</c> 可以是"这一帧没头像"（转存是异步的，首帧常为 null），
    /// 那是正常情况而不是错误，所以这里不发请求，直接回 <see cref="MediaState.NoSource"/>，
    /// 由 UI 落回 sender_id 首字占位头像。
    ///
    /// 同一个 token 在这一屏被敲十几次（一行在建树那几轮里会重绑，实测打开一条会话敲 22 次）：
    /// 这些调用都排在同一个派发周期里，所以按 token 合流成一次回源，见 <see cref="_avatarFlight"/>。
    ///
    /// 手里有字节但已经过期：<b>先交旧字节，后台再复核一次</b>（<see cref="ServeStaleAndRevalidate"/>）。
    /// 头像只有时间这一个"换没换"的信号，所以过期只能自己判；而判到过期就作废缓存的话，
    /// 那一格在网络回话之前是空的——用户会看到满屏头像闪一下变成首字母。
    /// </summary>
    public Task<MediaAsset> AvatarAsync(DeviceIdentity identity, string? senderAvatar,
        CancellationToken ct = default)
    {
        var token = DeviceClient.AvatarTokenOf(senderAvatar);
        if (token == null) return Task.FromResult(new MediaAsset(MediaState.NoSource));

        var key = AvatarKey(token);
        if (ServeStaleAndRevalidate(identity, token, key) is { } stale) return Task.FromResult(stale);
        TaskCompletionSource<MediaAsset> gate;
        lock (_flightGate)
        {
            if (_avatarFlight.TryGetValue(key, out var flying)) return flying;
            // 先挂上名册再开飞：取字节那一步可能在别的线程上立刻完成，晚一步就没人摘得掉这一格
            gate = new TaskCompletionSource<MediaAsset>(TaskCreationOptions.RunContinuationsAsynchronously);
            _avatarFlight[key] = gate.Task;
        }

        _ = Land(gate, key, identity, token, ct);
        return gate.Task;
    }

    /// <summary>这一枪落地：把结果交给所有等同一张脸的格子，再把这一格从名册上摘掉。
    /// 名册只管"同时在飞的"，不留失败结果——所以点重试、以及下一次打开会话都还会真的回源一次。
    /// <c>persist: false</c> 是第 27 条那条：头像对象按 (频道, 发送者) 覆盖写，落盘等于把换掉的头像永久留在本机。</summary>
    private async Task Land(TaskCompletionSource<MediaAsset> gate, string key, DeviceIdentity identity,
        string token, CancellationToken ct)
    {
        try
        {
            var asset = await LoadAsync(key, false,
                inner => _client.AvatarAsync(identity, token, inner), ct,
                landed: () => MarkLanded(token)).ConfigureAwait(false);
            gate.TrySetResult(asset);
        }
        catch (Exception e)
        {
            // 取消（图位滚出屏幕）也得给等它的格子一个说法，否则那一格永远转圈
            gate.TrySetException(e);
        }
        finally
        {
            lock (_flightGate) _avatarFlight.Remove(key);
        }
    }

    /// <summary>
    /// 缓存里有这个人的头像，但那份字节已经放超过 <see cref="AvatarRevalidateInterval"/>：
    /// 立刻把旧字节交出去（这一格不能空着等网络），同时后台回源一次。
    ///
    /// 先记账再起飞：<c>_avatarLanded</c> 一写，后面同一屏那十几次敲进来看到的都是"还没过期"，
    /// 于是一屏一次回源；如果反过来，回话之前每一格都会各自起一次复核，那就是把第 2 步省下来的
    /// RTT 又加回去。这一格也不走 <see cref="_avatarFlight"/>：调用方已经拿到字节走人了，
    /// 合流在这里没有意义，节流靠的是上面那笔记账。
    /// </summary>
    /// <returns>命中"旧字节可用"时返回要画的那一帧；没有缓存或还没过期时返回 null，交回正常的取链路。</returns>
    private MediaAsset? ServeStaleAndRevalidate(DeviceIdentity identity, string token, string key)
    {
        var bytes = _cache.Get(key);
        if (bytes is not { Length: > 0 }) return null;

        var stamp = _now().UtcTicks;
        lock (_signalGate)
        {
            if (_avatarLanded.TryGetValue(token, out var landed)
                && stamp - landed < AvatarRevalidateInterval.Ticks) return null;
            _avatarLanded[token] = stamp;
        }

        _ = RevalidateAsync(identity, token, key, bytes);
        return MediaAsset.Ready(bytes, null);
    }

    /// <summary>后台那一次复核：新字节顶替旧的，只有<b>真的换了一张脸</b>才广播重画。
    /// 广播是有代价的——画着同一个 token 的格子会全部重取一次，而字节没变时那次重画纯属白干。</summary>
    private async Task RevalidateAsync(DeviceIdentity identity, string token, string key, byte[] served)
    {
        MediaAsset fresh;
        try
        {
            fresh = await LoadAsync(key, false, inner => _client.AvatarAsync(identity, token, inner),
                CancellationToken.None, landed: () => MarkLanded(token), bypass: true).ConfigureAwait(false);
        }
        catch (Exception)
        {
            // 复核失败留着旧字节继续画：这一格有脸可看，比"因为回不了源而掉回首字母"好
            return;
        }

        switch (fresh.State)
        {
            case MediaState.Ready when fresh.HasBytes:
                _cache.Put(key, fresh.Bytes!, false);
                if (!fresh.Bytes!.SequenceEqual(served)) AvatarInvalidated?.Invoke(token);
                return;
            case MediaState.Pending or MediaState.NetworkError:
                return;
            default:
                // 服务端明说这张脸不会再有了（failed/deleted/purged/missing）：缓存里那份是过期的脸，
                // 留着只会让人一直看到换掉之前的头像
                _cache.Evict(key);
                AvatarInvalidated?.Invoke(token);
                return;
        }
    }

    /// <summary>这一刻真的从服务端拿到了这个人的头像字节——TTL 从这儿起算。
    /// 命中缓存那一次绝不许记它，否则每画一次就把 deadline 往后推一格，永远过不了期。</summary>
    private void MarkLanded(string token)
    {
        lock (_signalGate) _avatarLanded[token] = _now().UtcTicks;
    }

    /// <summary>
    /// 这一帧带了 <c>sender_avatar</c>：服务端现在有这个人的头像，而本地缓存里那份可能就是换掉之前那张。
    /// 作废它并通知画着这一格的控件重取一次。
    ///
    /// 只有"又收到这个人的一条消息"这一个信号可用（token 不变、也没有 ETag），所以按 token 节流：
    /// 窗口内返回 false 表示这次什么都没动，缓存里那份就是上一次作废后刚取回来的。
    /// 返回是否真的作废了，用例靠它判"这次有没有产生副作用"。
    /// </summary>
    public bool InvalidateAvatar(string? senderAvatar, DateTimeOffset? now = null)
    {
        var token = DeviceClient.AvatarTokenOf(senderAvatar);
        if (token == null) return false;

        var stamp = (now ?? _now()).UtcTicks;
        lock (_signalGate)
        {
            if (_avatarSignals.TryGetValue(token, out var last) && last <= stamp
                && stamp - last < AvatarRevalidateInterval.Ticks)
                return false;

            // 过了窗口的条目节流不了任何人，顺手清掉：这张表的大小就只跟"最近十分钟里说过话的发送者"走
            foreach (var stale in _avatarSignals
                         .Where(entry => stamp - entry.Value >= AvatarRevalidateInterval.Ticks)
                         .Select(entry => entry.Key).ToList())
                _avatarSignals.Remove(stale);
            _avatarSignals[token] = stamp;
        }

        _cache.Evict(AvatarKey(token));
        AvatarInvalidated?.Invoke(token);
        return true;
    }

    /// <summary>消息被删（W14 的 tombstone / 清空）时连带清掉它的图。</summary>
    public void EvictMessage(string msgId) => _cache.EvictMessage(msgId);

    /// <param name="bypass">true = 手里已经有一份旧字节正要画出来，这次是去核对它换没换，所以跳过缓存查询。
    /// 不跳过就永远查到自己刚放进去的那份，复核等于没发生。</param>
    /// <param name="landed">真的从服务端拿到字节时回调一次，给头像的 TTL 起表。传 null 的图位（消息里的图）
    /// 有自己的键（<c>{msg_id}#idx</c>），内容不可变，不需要这条。</param>
    private async Task<MediaAsset> LoadAsync(string key, bool persist,
        Func<CancellationToken, Task<MediaBytes>> fetch, CancellationToken ct,
        Action? landed = null, bool bypass = false)
    {
        if (!bypass)
        {
            var cached = _cache.Get(key);
            if (cached != null) return MediaAsset.Ready(cached, null);
        }

        try
        {
            var fresh = await fetch(ct).ConfigureAwait(false);
            _cache.Put(key, fresh.Bytes, persist);
            landed?.Invoke();
            return MediaAsset.Ready(fresh.Bytes, fresh.ContentType);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw; // 图位滚出屏幕了，这次取图本来就作废，不该被说成网络故障
        }
        catch (PushBoxApiException e)
        {
            var asset = FromApiError(e);
            // 槽位状态（pending/failed/deleted/purged/missing）是服务端明说的，UI 那一格会说话，不留痕；
            // 只有"这条链路本身不对"（400/401/5xx）才要留一行，否则图位空着却一点痕迹都没有
            if (asset.State == MediaState.NetworkError) AppLog.MediaFetchFailed(KindOf(key), e.GetType().Name);
            return asset;
        }
        catch (PushBoxTransportException e)
        {
            AppLog.MediaFetchFailed(KindOf(key), e.GetType().Name);
            return new MediaAsset(MediaState.NetworkError, Reason: e.Message);
        }
    }

    /// <summary>日志里只留图位种类，不留键的另一半：msg_id 虽然不含正文，但它是能反查内容的定位符，
    /// 而这一行要回答的只是"缩略图还是原图还是头像没取到"。</summary>
    private static string KindOf(string key) => key switch
    {
        _ when key.StartsWith("avatar#", StringComparison.Ordinal) => "avatar",
        _ when key.EndsWith("#t", StringComparison.Ordinal) => "thumb",
        _ when key.EndsWith("#f", StringComparison.Ordinal) => "full",
        _ => "image",
    };

    /// <summary>
    /// §5.2：这两个字节接口把槽位状态写在 404 的 hint 里（MediaController 直接回
    /// <c>not_found</c> + 该状态），所以映射表按 hint 走，而不是把所有 404 都当"图坏了"。
    /// 400 是 size 参数写错（只允许 thumb|full），401/5xx 是这条链路暂时不对——三类都不该骗用户"已失效"。
    /// </summary>
    private static MediaAsset FromApiError(PushBoxApiException e)
    {
        if (e.Status == HttpStatusCode.NotFound)
        {
            return e.Hint switch
            {
                "pending" => new MediaAsset(MediaState.Pending, Reason: e.Message),
                "failed" => new MediaAsset(MediaState.Failed, Reason: e.Message),
                "deleted" => new MediaAsset(MediaState.Deleted, Reason: e.Message),
                "purged" => new MediaAsset(MediaState.Purged, Reason: e.Message),
                _ => new MediaAsset(MediaState.Missing, Reason: e.Message),
            };
        }

        return new MediaAsset(MediaState.NetworkError, Reason: e.Message);
    }
}
