using System;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Net;

namespace PushBox.Windows.Data;

/// <summary>
/// §5.2/§7.4 全文回捞（Android 那边是 <c>message_fulltext</c> 那个按钮，同一句"展开全文"）。
/// 服务端为了压一帧的体积会把长正文截断下发并标 <c>truncated</c>，完整那一份只在这一条请求里给。
/// 取回来的正文直接落库（<see cref="DeviceStore.ApplyFulltext"/> 顺手把标记清掉），
/// 所以"展开"是一次性的：同一条消息第二次点开不再发请求。
/// </summary>
public sealed class FulltextService
{
    private readonly DeviceStore _db;
    private readonly IdentityStore _identities;
    private readonly DeviceClient _client;

    public FulltextService(DeviceStore db, IdentityStore identities, DeviceClient? client = null)
    {
        _db = db;
        _identities = identities;
        _client = client ?? new DeviceClient();
    }

    /// <summary>
    /// 取那一条的完整正文并落库。返回 false 表示库里那条还是截断的（服务端 404、正文为空、
    /// 或这条根本是别的端删掉的），调用方据此把"展开全文"那行留着而不是假装已经展开。
    /// 网络与协议异常不在这里咽：它是"这一条没展开成"，由 UI 说话，日志只留类型名。
    /// </summary>
    public async Task<bool> FetchAsync(string msgId, CancellationToken ct = default)
    {
        if (msgId.Length == 0) return false;
        var frame = await _client.FulltextAsync(_identities.Load(), msgId, ct).ConfigureAwait(false);
        var body = frame?.Content;
        if (string.IsNullOrEmpty(body)) return false;
        _db.ApplyFulltext(msgId, body);
        return true;
    }
}
