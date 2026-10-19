using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Logging;
using PushBox.Windows.Net;

namespace PushBox.Windows.Data;

/// <summary>
/// 一次搜索的两段结果（§7.4/D19）：本地缓存命中的那段先出、即时出，服务端历史命中的那段后出。
/// 两段分开装着而不是合成一串，因为空结果的文案要分清是"本地无匹配"还是"哪都没有"，
/// 而列表里服务端那一段的标题就是"更早历史（服务端）"。
/// </summary>
/// <param name="Local">本机 FTS5 命中，按 seq 倒序。</param>
/// <param name="Server">服务端命中，已去掉本地那条同 id 的重复项。</param>
/// <param name="HasMore">服务端还有更多（本地那一段一次查完，没有分页）。</param>
/// <param name="Error">服务端那一段没拿回来的原因，人话；网络好时是 null。<b>不为 null 不代表整次搜索失败</b>
/// ——Local 照样有内容，UI 该显示本地结果加一句"服务端历史没查到"。</param>
public sealed record SearchResultSet(string Term, IReadOnlyList<SearchHit> Local,
    IReadOnlyList<SearchHit> Server, bool HasMore, string? Error)
{
    public bool IsEmpty => Local.Count == 0 && Server.Count == 0;
}

/// <summary>
/// §7.4 搜索这一条链：输入即查本机（<see cref="LocalHits"/>，绝不打网络），回车再要服务端全量历史
/// （<see cref="SearchAsync"/>）。两段结果按 <c>msg_id</c> 去重——同一条消息既在本地缓存又被服务端召回时，
/// 屏幕上只该有一行，留哪一份？留本地那份：它带正文，点进去能直接定位，服务端那份只有一句片段。
///
/// 这个类不持有"当前这次搜索"的状态，所以连续敲两次回车不会有后一次被前一次盖住的问题：
/// 前一次的取消由调用方（W12 的搜索框）拿 CancellationToken 做，防抖也一样。
/// </summary>
public sealed class SearchService
{
    private readonly DeviceStore _db;
    private readonly IdentityStore _identities;
    private readonly DeviceClient _client;

    public SearchService(DeviceStore db, IdentityStore identities, DeviceClient? client = null)
    {
        _db = db;
        _identities = identities;
        _client = client ?? new DeviceClient();
    }

    /// <summary>输入即查：只碰本机库。channelNo/senderId 同时给定时就是"仅本会话"。</summary>
    public IReadOnlyList<SearchHit> LocalHits(string term, string? channelNo = null,
        string? senderId = null, int limit = 100)
    {
        // messages 表里没有发送者名（§3.3 它在 conversations 上），而服务端那段是带的——
        // 两段拼在一个列表里时不能一边有名一边没名
        var names = _db.Conversations().ToDictionary(c => (c.ChannelNo, c.SenderId), c => c.DisplayName);
        return _db.Search(term, channelNo, senderId, limit)
            .Select(row => AsHit(term, row,
                names.GetValueOrDefault((row.ChannelNo, row.SenderId))))
            .ToList();
    }

    /// <summary>
    /// 回车：本地一段立刻有，服务端一段等它回来。服务端失败（断网、限流、地址指错）只影响第二段，
    /// 因为本地那半是这次搜索的主要价值——离线时照样要能查到自己缓存过的内容。
    /// </summary>
    public async Task<SearchResultSet> SearchAsync(string term, string? channelNo = null,
        string? senderId = null, long since = 0L, int limit = DeviceClient.SearchPageSize,
        CancellationToken ct = default)
    {
        var query = term?.Trim() ?? string.Empty;
        if (query.Length == 0) return new SearchResultSet(query, Array.Empty<SearchHit>(),
            Array.Empty<SearchHit>(), false, null);

        var local = LocalHits(query, channelNo, senderId);
        try
        {
            var page = await _client.SearchAsync(_identities.Load(), query, channelNo, senderId,
                since: since, limit: limit, ct: ct).ConfigureAwait(false);

            // 同一条只在本地出现过就不必再列一遍：id 是服务端分配的 ULID，本机的 msg_id 就是它
            var already = new HashSet<string>(local.Select(h => h.Id), StringComparer.Ordinal);
            var remote = page.Hits.Where(h => !already.Contains(h.Id)).ToList();
            return new SearchResultSet(query, local, remote, page.HasMore, null);
        }
        catch (OperationCanceledException)
        {
            throw; // 调用方自己取消的（用户又敲了一个词），不是"服务端没答上"
        }
        catch (Exception e) when (e is PushBoxApiException or PushBoxTransportException)
        {
            // §5.1 的错误到这里已经是一句人话了，原样带给状态条
            // 留痕只到码/类型名为止：搜索词本身是 §14 黑名单里的 query，一次没查成的搜索不该在日志里留下用户找了什么
            AppLog.SearchRefused((e as PushBoxApiException)?.Code ?? "-", e.GetType().Name);
            return new SearchResultSet(query, local, Array.Empty<SearchHit>(), false, e.Message);
        }
    }

    /// <summary>本机那条消息映射成同一个 SearchHit：正文在这里，片段当场裁出来。</summary>
    private static SearchHit AsHit(string term, MessageRow row, string? senderName) => new(row.MsgId,
        row.Seq, row.ChannelNo, row.SenderId, senderName, row.MsgType, row.Title,
        SearchText.Snippet(term, row.Title, row.Body, row.Summary), row.Level, row.MediaStatus,
        row.CreatedAt);
}
