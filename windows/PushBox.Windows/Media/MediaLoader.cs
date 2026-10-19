using System;
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
    private readonly DeviceClient _client;
    private readonly MediaCache _cache;

    public MediaLoader(DeviceClient? client = null, MediaCache? cache = null)
    {
        _client = client ?? new DeviceClient();
        _cache = cache ?? new MediaCache();
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
    /// </summary>
    public Task<MediaAsset> AvatarAsync(DeviceIdentity identity, string? senderAvatar,
        CancellationToken ct = default)
    {
        var token = DeviceClient.AvatarTokenOf(senderAvatar);
        if (token == null) return Task.FromResult(new MediaAsset(MediaState.NoSource));

        // 头像对象是覆盖写的（§4.5），只进内存：落盘等于把换掉的头像永久留在本机
        return LoadAsync(AvatarKey(token), false,
            inner => _client.AvatarAsync(identity, token, inner), ct);
    }

    /// <summary>消息被删（W14 的 tombstone / 清空）时连带清掉它的图。</summary>
    public void EvictMessage(string msgId) => _cache.EvictMessage(msgId);

    private async Task<MediaAsset> LoadAsync(string key, bool persist,
        Func<CancellationToken, Task<MediaBytes>> fetch, CancellationToken ct)
    {
        var cached = _cache.Get(key);
        if (cached != null) return MediaAsset.Ready(cached, null);

        try
        {
            var fresh = await fetch(ct).ConfigureAwait(false);
            _cache.Put(key, fresh.Bytes, persist);
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
