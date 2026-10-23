using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Logging;
using PushBox.Windows.Net;

namespace PushBox.Windows.Sync;

/// <summary>
/// §5.2/§4.4 的已读上报：本端把某个会话读到哪了 PUT 回服务端，服务端写 read_state 后向同组他端广播
/// read 帧，另一台机器的红点就此消失（D9）。
///
/// 与 Android 的 fire-and-forget 相比，PC 端多管两件事，都是因为"托盘常驻"这个形态：
/// - <b>断网期间攒着，联网一次批量补报</b>：合上盖子出门、回到工位重连，这一期间读过的会话要一起补，
///   而不是每个会话撞开一次网络再各发一发；
/// - <b>同一个游标不重复上报</b>：服务端 read_state 那一行是后写覆盖（不是取最大值，见 PbStore.upsertReadState），
///   把已经报过的旧游标再发一次，会把别端刚清掉的红点点回来。所以本地两张表——待发队列和"已经报到哪"，
///   两边都只增不减。
///
/// 红点在 UI 上是立刻消失的（<see cref="MarkRead"/> 先把本地游标写进库，不等网络），上报只是把它同步给别端。
/// </summary>
public sealed class ReadReporter
{
    private readonly DeviceStore _db;
    private readonly IdentityStore _identities;
    private readonly DeviceClient _client;

    /// <summary>一次只放一个 PUT 出门：并发发两条等于把同一批游标报两遍，第二条还可能是已被第一条确认过的旧值。</summary>
    private readonly SemaphoreSlim _sending = new(1, 1);

    /// <summary>队列与"已报到哪"必须一起改，所以共用一把锁：漏了一半就会出现既不在队列、也没记成已报的游标，
    /// 那个红点就再也报不出去了。</summary>
    private readonly object _gate = new();

    private readonly Dictionary<string, ReadEntry> _pending = new();
    private readonly Dictionary<string, long> _reported = new();

    /// <summary>正在网络上的那一批。它和 <see cref="_reported"/> 一起挡住重复上报：在途期间同一个游标
    /// 又被 MarkRead 一次是常事（用户把刚看过的会话又点了一遍），那时它既不在队列也还没被确认，
    /// 只看 <see cref="_reported"/> 就会把它重新排进去，白多发一发。</summary>
    private List<ReadEntry>? _inFlight;

    private bool _online;

    public ReadReporter(DeviceStore db, IdentityStore identities, DeviceClient client)
    {
        _db = db;
        _identities = identities;
        _client = client;
    }

    /// <summary>攒着还没报出去的会话数：W12 的"已同步/待上报"和测试的收敛点都看它。</summary>
    public int PendingCount
    {
        get
        {
            lock (_gate) return _pending.Count;
        }
    }

    /// <summary>最近一次上报失败的原因，成功时清空。MarkRead 是即发即忘的（UI 不该被网络拖住），
    /// 所以失败在这里留一份给状态条与测试看，而不是让它消失在没人等的 Task 里。</summary>
    public string? LastError { get; private set; }

    /// <summary>
    /// §7.4 的两个触发点：打开会话、滚动到末尾。本地游标先推到该会话的最后一条（红点立刻没了），
    /// 联网中就顺手报一次，断网中只攒着等 <see cref="SetOnline"/>。
    /// </summary>
    public void MarkRead(string channelNo, string senderId)
    {
        if (string.IsNullOrEmpty(channelNo) || string.IsNullOrEmpty(senderId)) return;

        // 0 表示这条会话不在库里（或一条消息都没有）：没有已读可言，也没有东西要 PUT
        var lastReadSeq = _db.MarkConversationRead(channelNo, senderId);
        if (lastReadSeq <= 0) return;

        bool online;
        lock (_gate)
        {
            Track(new ReadEntry(channelNo, senderId, lastReadSeq));
            online = _online;
        }

        // 手机那边的红点不该等到下一次重连才消失
        if (online) ScheduleFlush();
    }

    /// <summary>由宿主把 <see cref="SyncEngine"/> 的状态接过来：<c>reads.SetOnline(s is SyncState.Online)</c>。
    /// 恢复联网那一刻正是批量补报的时机。</summary>
    public void SetOnline(bool online)
    {
        bool drain;
        lock (_gate)
        {
            _online = online;
            drain = online && _pending.Count > 0;
        }

        if (drain) ScheduleFlush();
    }

    /// <summary>
    /// 把攒下的游标一次 PUT 出去，返回报掉的会话数（0 = 这一轮什么都没成）。
    /// 网络与接口错误都不往外抛：批次原样回到队列，下一次联网再试——已读是"晚点到"的事，不是"没到就算了"的事，
    /// 但也不该因为一次 502 就把用户读过的记录丢掉。
    /// </summary>
    public async Task<int> FlushAsync(CancellationToken ct = default)
    {
        await _sending.WaitAsync(ct).ConfigureAwait(false);
        List<ReadEntry> batch;
        try
        {
            lock (_gate)
            {
                if (_pending.Count == 0) return 0;
                batch = _pending.Values.ToList();
                _pending.Clear();
                _inFlight = batch;
            }

            var sent = false;
            try
            {
                // 身份每次现读：token 与服务器地址可能刚被重新注册换掉，队列里攒的是游标不是凭据
                await _client.ReportReadAsync(_identities.Load(), batch, ct).ConfigureAwait(false);
                sent = true;
            }
            catch (Exception e) when (e is PushBoxApiException or PushBoxTransportException
                                         or FileNotFoundException or InvalidDataException)
            {
                LastError = e.Message;
                // 报不出去的那一批回到队列，红点还挂着：这一行就是"为什么它到这儿还没没掉"的唯一线索
                AppLog.ReadReported(0, e is PushBoxApiException api ? api.Code : e.GetType().Name);
            }

            lock (_gate)
            {
                _inFlight = null;
                if (!sent)
                {
                    foreach (var entry in batch) Track(entry);
                    return 0;
                }

                foreach (var entry in batch)
                {
                    var key = Key(entry);
                    _reported[key] = Math.Max(_reported.TryGetValue(key, out var upTo) ? upTo : 0L,
                        entry.LastReadSeq);
                }

                LastError = null;
            }

            AppLog.ReadReported(batch.Count, null);
            return batch.Count;
        }
        finally
        {
            _sending.Release();
        }
    }

    /// <summary>即发即忘的那条路：调用方不该为了一个红点等一次网络往返，所以这里自己把异常吃掉（已经存进
    /// <see cref="LastError"/> 并放回队列），只留下取消这一种真正不该继续的情形。</summary>
    private void ScheduleFlush()
    {
        _ = RunAsync();

        async Task RunAsync()
        {
            try
            {
                await FlushAsync().ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                // 应用正在退出：批次已经放回队列，下次启动接着攒
            }
        }
    }

    /// <summary>进队列的唯一入口：不比"已经报到的值"和"正在网络上的那一批"更新的都不要，
    /// 同一个会话后到的更大值覆盖前一个。在 <see cref="_gate"/> 里调用。</summary>
    private void Track(ReadEntry entry)
    {
        var key = Key(entry);
        var upTo = _reported.TryGetValue(key, out var sent) ? sent : 0L;
        if (_inFlight != null)
        {
            foreach (var flying in _inFlight)
                if (Key(flying) == key) upTo = Math.Max(upTo, flying.LastReadSeq);
        }

        if (upTo >= entry.LastReadSeq) return;
        _pending[key] = _pending.TryGetValue(key, out var waiting) && waiting.LastReadSeq >= entry.LastReadSeq
            ? waiting
            : entry;
    }

    private static string Key(ReadEntry entry) => Key(entry.ChannelNo, entry.SenderId);

    /// <summary>channel_no 是数字串、sender_id 是频道方给的短标识，两者都不会含竖线，所以拼起来当主键够用。</summary>
    private static string Key(string channelNo, string senderId) => channelNo + "|" + senderId;
}
