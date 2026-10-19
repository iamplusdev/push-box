using System;
using System.IO;
using System.Net;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Logging;
using PushBox.Windows.Media;
using PushBox.Windows.Net;

namespace PushBox.Windows.Sync;

/// <summary>§8.4 W14 删除的三种结局。<see cref="NothingLocal"/> 是"库里本来就没有这条"，不是失败。</summary>
public enum DeleteOutcome
{
    NothingLocal,
    Deleted,
    RolledBack,
}

/// <summary>
/// 一次删除或清空的结果。<see cref="Error"/> 只在回滚时有值，而且它已经是给人看的那句话——
/// §9.3 的兜底不是"悄悄把消息放回去"，用户得知道自己那一下没成。
/// </summary>
public sealed record DeleteResult(DeleteOutcome Outcome, string? Error = null)
{
    public static readonly DeleteResult NothingLocal = new(DeleteOutcome.NothingLocal);

    public static DeleteResult Deleted() => new(DeleteOutcome.Deleted);

    public static DeleteResult RolledBack(string reason) =>
        new(DeleteOutcome.RolledBack, $"删除没有生效：{reason}（本机这条已经放回）");

    /// <summary>窗口只在这一句非空时提示；成功的两种结局都不需要说话——屏幕上那条没了就是交代。</summary>
    public string? Notice => Outcome == DeleteOutcome.RolledBack ? Error : null;
}

/// <summary>
/// §5.2/D13 的删除上报，与 <see cref="ReadReporter"/> 同一分工：本机先立刻生效，服务端那一发只管别的端
/// （写 tombstone → 标删它的图片 → 向同组广播 tombstone 帧，他端就此隐藏、重装也不再回放）。
///
/// 与已读那条链路不同的是这里<b>要</b>回滚（§9.3"本地乐观删除 + 回滚兜底"）：服务端没删成，意味着这条消息
/// 还活在服务器上也还活在别的端上，本机偷偷藏起来就是两端各看一套，而且本机游标已经越过它、
/// 补拉再也不会把它带回来——除了放回去，没有第二条路让它重新出现。
/// 唯一不回滚的情况是 404 <c>hint=message</c>，见 <see cref="IsAlreadyGone"/>。
///
/// 媒体缓存的驱逐排在服务端确认<b>之后</b>：回滚时消息会带着图一起回来，这时候清缓存等于凭空多一次取图；
/// 而服务端确认删过之后，对象已经被标成待删（§4.5 的宽限期），本机留着的副本就成了别处再也取不到的孤儿字节。
/// </summary>
public sealed class DeleteReporter
{
    private readonly DeviceStore _db;
    private readonly IdentityStore _identities;
    private readonly DeviceClient _client;
    private readonly MediaLoader _media;

    public DeleteReporter(DeviceStore db, IdentityStore identities, DeviceClient client, MediaLoader media)
    {
        _db = db;
        _identities = identities;
        _client = client;
        _media = media;
    }

    /// <summary>
    /// 删一条消息。<paramref name="locallyRemoved"/> 就是"本机已经没有这条"的那一刻，窗口靠它重画——
    /// 拿它而不是拿返回值当画的机会，删除才不用等一次网络往返（十几秒）才在屏幕上生效。
    /// </summary>
    public async Task<DeleteResult> DeleteMessageAsync(string msgId, Action? locallyRemoved = null)
    {
        var undo = _db.DeleteMessageLocal(msgId);
        if (undo == null)
        {
            Log("message", DeleteOutcome.NothingLocal);
            return DeleteResult.NothingLocal;
        }

        locallyRemoved?.Invoke();

        // 服务端那一句"这条本来就已经没了"（404 hint=message）不改结局，但要留在日志里：
        // 它意味着本机删对了、只是消息在服务器上早就不存在，那种行查起来最需要这个码
        PushBoxApiException? alreadyGone = null;
        try
        {
            // 身份每次现读：服务器地址与 token 可能在乐观删除那段时间里被重新注册换掉
            await _client.DeleteMessageAsync(_identities.Load(), msgId).ConfigureAwait(false);
        }
        catch (Exception e) when (e is PushBoxApiException or PushBoxTransportException
                                    or FileNotFoundException or InvalidDataException)
        {
            if (!IsAlreadyGone(e))
            {
                _db.RestoreMessage(undo);
                Log("message", DeleteOutcome.RolledBack, e);
                return DeleteResult.RolledBack(e.Message);
            }

            alreadyGone = (PushBoxApiException)e;
        }

        _media.EvictMessage(msgId);
        Log("message", DeleteOutcome.Deleted, alreadyGone);
        return DeleteResult.Deleted();
    }

    /// <summary>
    /// 清空一条会话：一次 DELETE 标完 + 服务端只广播一条带 scope 的 tombstone 帧。
    /// 这条接口是幂等的（重复调用回 <c>deleted=0</c>，不报 404），所以它没有"已经没了"那个分支——
    /// 任何失败都放回本地，包括 401 那种"整片签名接口一起不对"。
    /// </summary>
    public async Task<DeleteResult> ClearConversationAsync(string channelNo, string senderId,
        Action? locallyRemoved = null)
    {
        var undo = _db.ClearConversationLocal(channelNo, senderId);
        if (undo == null)
        {
            Log("conversation", DeleteOutcome.NothingLocal);
            return DeleteResult.NothingLocal;
        }

        locallyRemoved?.Invoke();

        try
        {
            await _client.ClearMessagesAsync(_identities.Load(), channelNo, senderId)
                .ConfigureAwait(false);
        }
        catch (Exception e) when (e is PushBoxApiException or PushBoxTransportException
                                    or FileNotFoundException or InvalidDataException)
        {
            _db.RestoreConversation(undo);
            Log("conversation", DeleteOutcome.RolledBack, e);
            return DeleteResult.RolledBack(e.Message);
        }

        foreach (var message in undo.Messages) _media.EvictMessage(message.MsgId);
        Log("conversation", DeleteOutcome.Deleted);
        return DeleteResult.Deleted();
    }

    /// <summary>
    /// W15 的留痕：一条删除只留 scope、结局枚举名、error 码与异常类型名。
    /// msg_id 本身不含正文、本可以留，但"到底删了哪一条"这件事本地库答得比日志准，所以不必占这一行。
    /// </summary>
    private static void Log(string scope, DeleteOutcome outcome, Exception? e = null) =>
        AppLog.DeleteAttempted(scope, outcome.ToString(), (e as PushBoxApiException)?.Code, e?.GetType().Name);

    /// <summary>
    /// "服务端本来就没有这条"——这时候本机移除正是要的终态，放回本地反而会造出一条僵尸消息：
    /// 它在服务端的活表里已经不存在，之后的补拉和 tombstone 广播都不会再提它，只有这台 PC 上还留着。
    /// 认的是 <c>hint=message</c> 而不是状态码：404 也可能是地址指错了服务（那时装进 hint 的是代理的
    /// 一句话或干脆是空），那种情况必须回滚。hint 是服务端自己写的字段，永远不含消息正文（§14）。
    /// </summary>
    public static bool IsAlreadyGone(Exception e) =>
        e is PushBoxApiException { Status: HttpStatusCode.NotFound, Hint: "message" };
}
