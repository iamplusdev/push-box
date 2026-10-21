using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Channels;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Logging;
using PushBox.Windows.Net;

namespace PushBox.Windows.Sync;

/// <summary>
/// §7.2/§8.1 的连接状态：W12 顶栏那个灰点和 W13 托盘的提示文字只读它，不碰引擎内部。
/// 记录类型是故意的——两次的 Offline 只要原因和等待一样就是同一个状态，UI 可以按相等去重。
/// </summary>
public abstract record SyncState
{
    public sealed record Idle : SyncState;

    public sealed record Connecting : SyncState;

    public sealed record Online(long LastSeq) : SyncState;

    public sealed record Offline(string Reason, TimeSpan RetryIn) : SyncState;

    /// <summary>再多重连也不会变好的那类断开：设备被吊销（§5.1）或服务端身份对不上（§4.3）。
    /// Android 的引擎把这几种和网络断开混在同一个退避循环里，PC 端分开是因为它们要的是人去处理，
    /// 让用户盯着一只永远在转的"重连中"圆圈是误导。</summary>
    public sealed record Blocked(string Reason) : SyncState;
}

/// <summary>
/// 通知的出口，W13 的 WinRT Toast 实现它。引擎只在"这条消息首次落库"时调用，传的是帧而不是
/// 拼好的字符串——正文只有这一条路能到达 UI，绝不进日志（§14）。
/// </summary>
public interface INotifier
{
    void Notify(MessageFrame message);
}

/// <summary>
/// §6.2「接收 → 入库 → 通知」的唯一入口 + §7.2 的重连状态机，和 Android 的 SyncEngine.kt 同一分工：
/// <see cref="PushConnection"/> 只管一条连接开好、活好、死好，这里负责"死了怎么再起来、缺的怎么补"。
///
/// 两条不能含糊的线程边界：
/// - <see cref="OnFrameAsync"/> 跑在读循环上，只入队、绝不 await 慢东西，否则补拉一页就把 pong 拖到
///   心跳超时（§5.3），被服务端踢掉；
/// - 帧按 FIFO 单线程消费，所以消息、墓碑、媒体回填不会互相插队。
/// </summary>
public sealed class SyncEngine : IPushObserver
{
    /// <summary>§7.2 阶梯的两端：第一次等 1 秒，最多等 60 秒。</summary>
    public static readonly TimeSpan BackoffMin = TimeSpan.FromSeconds(1);

    public static readonly TimeSpan BackoffMax = TimeSpan.FromSeconds(60);

    /// <summary>媒体帧比消息先到时暂存的那点内存的上限：只兜竞态，不当历史用。</summary>
    private const int MediaAheadCap = 64;

    private readonly IdentityStore _identities;
    private readonly DeviceStore _db;
    private readonly DeviceClient _client;
    private readonly INotifier _notifier;
    private readonly Func<DeviceIdentity, IPushTransport> _transports;

    /// <summary>抽出来是因为退避节奏本身要在测试里被断言，而不是让测试真等 60 秒。</summary>
    private readonly Func<TimeSpan, CancellationToken, Task> _delay;

    private Channel<Frame> _inbox = NewInbox();

    /// <summary>每轮起跑都要一条新队列（见 <see cref="Start"/>），所以工厂方法而不是字段初始化器。</summary>
    private static Channel<Frame> NewInbox() => Channel.CreateUnbounded<Frame>(
        new UnboundedChannelOptions { SingleReader = true });

    private readonly Dictionary<string, MediaReadyFrame> _mediaAhead = new();
    private readonly object _gate = new();

    private Task? _loop;
    private CancellationTokenSource? _stop;

    /// <summary>只覆盖"正在等退避"这段时间，<see cref="RetryNow"/> 取消的就是它。</summary>
    private CancellationTokenSource? _sleeping;

    private DeviceIdentity? _current;
    private SyncState _state = new SyncState.Idle();

    /// <summary>第几次起跑：收工时只清理"还是当前这一轮"的状态，别让上一轮把新一轮的 Connecting 抹掉。</summary>
    private int _run;

    /// <summary>开机回放正在进行（<see cref="RestoreHistoryAsync"/>）：0/1 开关，见那边的说明。</summary>
    private int _restoring;

    public SyncEngine(IdentityStore identities, DeviceStore db, DeviceClient client, INotifier notifier,
        Func<DeviceIdentity, IPushTransport>? transports = null,
        Func<TimeSpan, CancellationToken, Task>? delay = null)
    {
        _identities = identities;
        _db = db;
        _client = client;
        _notifier = notifier;
        _transports = transports ?? (_ => new ClientWebSocketTransport());
        _delay = delay ?? Task.Delay;
    }

    /// <summary>状态变化可能在任意线程上触发：W12 订阅后要自己转回 Dispatcher。</summary>
    public event Action<SyncState>? StateChanged;

    public SyncState State => _state;

    /// <summary>
    /// 起后台同步，幂等：已经在跑就把同一个 Task 给你（Android 的 start 同一条）。
    /// 返回循环 Task，是为了让"处理帧时抛了非协议异常"这件事有人观察，而不是静默变成一个不再更新的游标。
    /// </summary>
    public Task Start(DeviceIdentity identity)
    {
        lock (_gate)
        {
            // _stop 为空说明刚 Stop 过：那次收工还在 finally 里，不能把它当成"正在跑"而不再起新的循环
            if (_loop is { IsCompleted: false } && _stop != null) return _loop;
            _current = identity;
            _stop = new CancellationTokenSource();
            // 每轮起跑换一条新队列和一个新代号：上一轮的 finally 已经把旧队列 TryComplete() 了，复用它
            // TryWrite 只会静默返回 false——连接照样握手成功、resume_ok 也照样到，但帧一个都进不了库
            // （真服务体检 ⑥ 抓到的）。代号则是防上一轮的收工把这一轮的 Connecting 抹回 Idle。
            _inbox = NewInbox();
            _run++;
            var generation = _run;
            return _loop = Task.Run(() => RunLoopAsync(identity, _inbox, generation, _stop.Token));
        }
    }

    /// <summary>§7.2：网络刚恢复（或用户点了重试）就别再等剩下的退避，立刻连一次。
    /// 已经连上时什么都不做——断开的那次连接本来也会带着最新游标重连。</summary>
    public void RetryNow()
    {
        if (_state is SyncState.Online) return;
        CancellationTokenSource? sleeping;
        lock (_gate) sleeping = _sleeping;
        try
        {
            sleeping?.Cancel();
        }
        catch (ObjectDisposedException)
        {
            // 退避刚好在这一瞬间自己走完并释放了：循环下一行就是重新建连，等于已经重试过
        }
    }

    /// <summary>停掉循环并断开当前连接。没落库的那几条不算丢：游标停在原地，下次连接照样补得回来。</summary>
    public void Stop()
    {
        CancellationTokenSource? stop;
        CancellationTokenSource? sleeping;
        lock (_gate)
        {
            stop = _stop;
            sleeping = _sleeping;
            _stop = null;
        }

        sleeping?.Cancel();
        stop?.Cancel();
    }

    /// <summary>
    /// 队列栅栏：排到已经入队的所有帧之后。补拉是在消费队列里跑的，所以"队列空了"不等于"这批处理完了"，
    /// W12 的「同步中」和测试的收敛点都用这一个方法。
    /// </summary>
    public Task FlushAsync()
    {
        var barrier = new Barrier();
        return _inbox.Writer.TryWrite(barrier) ? barrier.Done.Task : Task.CompletedTask;
    }

    /// <summary>§7.2 的阶梯：这一次等 waited，等完再定下一次。连接活得比两个周期还久就不算故障，回到 1 秒
    /// ——否则一次正常的服务端重启之后，客户端要按 60 秒的节奏慢慢恢复，看起来像坏了。</summary>
    public static TimeSpan NextBackoff(TimeSpan waited, TimeSpan livedFor) =>
        livedFor > waited * 2
            ? BackoffMin
            : TimeSpan.FromTicks(Math.Min(waited.Ticks * 2, BackoffMax.Ticks));

    /// <summary>读循环线程：只做一次入队。这里多等一毫秒，pong 就晚一毫秒（§5.3）。</summary>
    public Task OnFrameAsync(Frame frame, CancellationToken ct)
    {
        _inbox.Writer.TryWrite(frame);
        return Task.CompletedTask;
    }

    /// <summary>§4.3 首次接触：指纹落盘只发生一次，所以这次同步写文件（DPAPI + 改名）压在读循环上可以接受。</summary>
    public void OnPinServerFingerprint(string fingerprint) => _identities.SaveServerPubFp(fingerprint);

    private async Task RunLoopAsync(DeviceIdentity seed, Channel<Frame> inbox, int generation, CancellationToken ct)
    {
        var consumer = ConsumeAsync(inbox, ct);
        var backoff = BackoffMin;
        try
        {
            while (!ct.IsCancellationRequested)
            {
                // 每次重连重读身份文件：since_seq 要用最新的那份，否则断线期间的消息会被漏拉
                var identity = LoadIdentity(seed);
                lock (_gate) _current = identity;
                SetState(new SyncState.Connecting());

                var startedAt = Stopwatch.GetTimestamp();
                var result = await ConnectAsync(identity, ct);
                // 这条连接活了多久决定下次算不算"故障"：活得比两个退避周期还久就回到 1 秒
                var livedFor = Elapsed(startedAt);
                if (ct.IsCancellationRequested) return;

                // 游标以库为权威，断开时回写一份副本，供下次"还没开库就要建连"时使用
                _identities.SaveLastAppliedSeq(CursorFor(identity));

                if (result.AuthenticationRejected || result.Reason == "server_identity_mismatch")
                {
                    // 这句在顶栏是"需要处理"，在日志里是同一句：这种断开不会自己变好，留痕要能回答
                    // "这台机器什么时候停下来的、为什么停"（reason 是定死的词表，不含内容）
                    AppLog.SyncBlocked(result.Reason);
                    SetState(new SyncState.Blocked(result.Reason));
                    return;
                }

                // 先把唤醒句柄挂上再公布 Offline：晚一步，用户在这一瞬间点重试就会扑空，白等一整个退避
                using var nap = CancellationTokenSource.CreateLinkedTokenSource(ct);
                lock (_gate) _sleeping = nap;

                AppLog.SyncOffline(result.Reason, (int)backoff.TotalSeconds);
                SetState(new SyncState.Offline(result.Reason, backoff));
                await SleepAsync(backoff, nap);
                backoff = NextBackoff(backoff, livedFor);
            }
        }
        finally
        {
            inbox.Writer.TryComplete();
            lock (_gate)
            {
                // 已经被新一轮起跑接手：这一轮只关自己那条队列，状态归新一轮说
                if (generation == _run)
                {
                    // Blocked 是"这事要人来处理"：循环自己收工时别把它抹成 Idle，否则顶栏从"服务端身份对不上"
                    // 变成"还没启动"，用户就看不到自己该做什么了
                    if (_state is not SyncState.Blocked) SetState(new SyncState.Idle());
                }
            }
            // 队列里的残留要先处理完再收工；里面有非协议异常就让它带出来，别静默变成一个不再更新的游标
            await consumer;
        }
    }

    /// <summary>一条连接的生命周期：建 socket、跑握手与读循环、断开后释放 socket。</summary>
    private async Task<PushResult> ConnectAsync(DeviceIdentity identity, CancellationToken ct)
    {
        var transport = _transports(identity);
        var connection = new PushConnection(identity, transport, this)
        {
            SinceSeq = CursorFor(identity),
        };

        try
        {
            return await connection.RunAsync(ct);
        }
        finally
        {
            if (transport is IDisposable disposable) disposable.Dispose();
        }
    }

    /// <summary>建连游标取两份里大的那个：identity.json 那份是断开时回写的副本，sync_state 那行是
    /// 落库事务里的权威值（W7 定的口径）。DB 只会更大，所以 max 不会漏消息。</summary>
    private long CursorFor(DeviceIdentity identity) => Math.Max(identity.LastAppliedSeq, _db.GroupCursor());

    private DeviceIdentity LoadIdentity(DeviceIdentity fallback)
    {
        try
        {
            return _identities.Load();
        }
        catch (Exception e) when (e is FileNotFoundException or InvalidDataException)
        {
            // 刚注册完还没落盘、或文件被别的进程改坏：用手上这份继续跑，同步循环不该因此死掉。
            // 这条必须留痕，否则"为什么这台机器同步用的是旧游标"这件事查不到源头。
            AppLog.IdentityFileUnreadable(e.GetType().Name);
            return fallback;
        }
    }

    /// <summary>等一次退避。<paramref name="nap"/> 由调用方在公布 Offline 之前挂进 <see cref="_sleeping"/>，
    /// 所以 RetryNow 一定看得见它。取消不算失败：要么重试，要么收工，都由循环顶部的 ct 判断。</summary>
    private async Task SleepAsync(TimeSpan wait, CancellationTokenSource nap)
    {
        try
        {
            await _delay(wait, nap.Token);
        }
        catch (OperationCanceledException)
        {
        }
        finally
        {
            lock (_gate)
            {
                if (ReferenceEquals(_sleeping, nap)) _sleeping = null;
            }
        }
    }

    private async Task ConsumeAsync(Channel<Frame> inbox, CancellationToken ct)
    {
        try
        {
            await foreach (var frame in inbox.Reader.ReadAllAsync(ct)) await HandleFrameAsync(frame, ct);
        }
        catch (OperationCanceledException)
        {
            // 断开即收工：没入库的帧靠游标在下次连接补回来
        }
    }

    private async Task HandleFrameAsync(Frame frame, CancellationToken ct)
    {
        if (frame is Barrier barrier)
        {
            barrier.Done.TrySetResult();
            return;
        }

        switch (frame)
        {
            case MessageFrame message:
                // 只有实时帧弹通知：补拉与重放拿到的是历史，一次休眠回来弹 500 条 Toast 是灾难
                Ingest(message, true);
                break;
            case ResumeGapFrame gap:
                await BackfillAsync(gap.MissingFrom, ct);
                break;
            case ReadFrame read:
                ApplyRead(read);
                break;
            case TombstoneFrame tombstone:
                // §5.3 的两形态都在这里：id 定位单条，scope 定位整会话/整频道（DeviceStore 分派）
                _db.ApplyTombstone(tombstone);
                break;
            case MediaReadyFrame media:
                ApplyMediaReady(media);
                break;
            case ConfigFrame config:
                await ApplyConfigAsync(config, ct);
                break;
            case SimpleFrame { Kind: FrameTypes.ResumeOk }:
            {
                // §5.3：重放到这里就结束了，这条连接从此算"在线"。这句不能只在"有消息落库"时说——
                // 一个还没发过消息的 Space 照样有一条好连接，顶栏一直停在"连接中"是在骗人
                var cursor = _db.GroupCursor();
                SetState(new SyncState.Online(cursor));
                AppLog.SyncReady(cursor);
                break;
            }
            default:
                break; // §5.3 前向兼容：不认识的帧跳过。握手帧（hello/kx）在 W6 内部就消化了，到不了这里
        }
    }

    /// <summary>
    /// 开机把本机还没缓存过的历史全部拉回来。<see cref="BackfillAsync"/> 原本只在 resume_gap 帧上触发，
    /// 而一台刚注册完、服务端之后又没再发过消息的机器永远等不到那一帧——主界面于是开成一片空白
    /// （手机那边是 join 完立刻 restoreHistory，两端口径要对齐）。
    /// 同一时刻只跑一份：重复跑没有副作用（msg_id 去重），但两条并发分页会把同一批拉两遍。
    /// </summary>
    public async Task RestoreHistoryAsync()
    {
        if (Interlocked.Exchange(ref _restoring, 1) == 1) return;
        CancellationTokenSource? stop;
        lock (_gate) stop = _stop;
        try
        {
            // 跟着这一轮的取消令牌走：收工时不该还在一页一页往下拉
            await BackfillAsync(1L, stop?.Token ?? CancellationToken.None);
        }
        finally
        {
            Interlocked.Exchange(ref _restoring, 0);
        }
    }

    /// <summary>
    /// §5.3 config：只有换发频道号码需要动本地数据，其余变更（密钥/治理位/白名单/设备名单/恢复码）
    /// 不改本机已有消息，设置页每次进都会重拉，收到帧即返回即可。
    ///
    /// 号码重置的麻烦在于服务端换了 channel_no 却没换 msg_id：旧号下的行既不会被新号覆盖
    /// （msg_id 撞主键直接丢弃），也不会自己消失，界面上就成了"同一批消息两条会话线"。
    /// 帧里只带新号，所以按"本地有、服务端列表里没有"认定它已作废，整频道丢弃后从 0 重放。
    /// 代价是该频道下的本机会话设置（置顶/改名/头像）跟着没了——号码都换了，那些以旧号为键的设置
    /// 本来也对不上新的会话线（与 Android 的 applyConfig 同一条取舍）。
    /// </summary>
    private async Task ApplyConfigAsync(ConfigFrame config, CancellationToken ct)
    {
        if (config.Reason != ConfigReason.ChannelNoReset) return;
        var identity = _current;
        if (identity == null) return;

        IReadOnlyList<ChannelInfo> live;
        try
        {
            live = await _client.ChannelsAsync(identity, ct).ConfigureAwait(false);
        }
        catch (PushBoxApiException e)
        {
            AppLog.BackfillRefused(e.Code, (int)e.Status);
            return;
        }
        catch (PushBoxTransportException e)
        {
            AppLog.BackfillUnreachable(e.GetType().Name);
            return;
        }

        var kept = new HashSet<string>(live.Select(c => c.ChannelNo));
        foreach (var stale in _db.ChannelNumbers().Where(no => !kept.Contains(no)))
            _db.ApplyTombstone(new TombstoneFrame(null, stale, null,
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));

        await BackfillAsync(1L, ct).ConfigureAwait(false);
    }

    /// <summary>§6.2：入库 → 通知。msg_id 去重（<c>ApplyMessage</c> 返回 false）就是"重复投 5 次只落 1 行"
    /// 那条验收的实现位置，通知也跟着去重。免打扰（§7.3）也判在这里而不是判在通知器里——
    /// 和 Android 的 <c>SyncEngine.kt</c> 同一分工：会话行读库这件事属于引擎，通知器只管"要弹了怎么弹"。</summary>
    private void Ingest(MessageFrame message, bool allowNotify)
    {
        if (_db.ApplyMessage(message))
        {
            ApplyPendingMedia(message.Id);
            if (allowNotify && message.Notify && !_db.IsMuted(message.ChannelNo, message.SenderId))
                _notifier.Notify(message);
        }

        // Online 上显示的是"本机已落库到哪"，取库里的游标而不是这条的 seq：补拉与乱序时这条可能更老，
        // 直接用会让状态栏倒退一步
        SetState(new SyncState.Online(_db.GroupCursor()));
    }

    /// <summary>
    /// §5.3：落后太多（&gt;2000）时帧补拉会被服务端拒绝，只能走 REST 分页，一页一页拉到 has_more 为假。
    /// 同一页里的墓碑先应用再补正文，否则服务端已删的那条会被这页重新写回来。
    /// </summary>
    private async Task BackfillAsync(long missingFrom, CancellationToken ct)
    {
        var identity = _current;
        if (identity == null) return;

        var after = missingFrom - 1;
        var high = after;
        while (!ct.IsCancellationRequested)
        {
            HistoryPage page;
            try
            {
                page = await _client.HistoryAsync(identity, after, DeviceClient.HistoryPageSize, ct);
            }
            catch (PushBoxApiException e)
            {
                // 这一轮没补成：游标没动，下次连接还会从同一条路径再补一次，所以不断链也不重抛。
                // 留痕只到 error 码与状态码为止：补拉的失败原因里最有用的是码，正文永远不进日志（§14）。
                AppLog.BackfillRefused(e.Code, (int)e.Status);
                return;
            }
            catch (PushBoxTransportException e)
            {
                // 连不上与"服务端拒了"要分开留：前者在日志里只有异常类型名，那句人话里带着服务器地址
                AppLog.BackfillUnreachable(e.GetType().Name);
                return;
            }

            foreach (var tombstone in page.Tombstones) _db.ApplyTombstone(tombstone);
            foreach (var message in page.Messages) Ingest(message, false);

            var newest = page.Messages.Count == 0 ? 0L : page.Messages.Max(m => m.Seq);
            if (newest > high) high = newest;
            // 空页或游标不前进就停：服务端哪怕 has_more 说错也不把自己钉死在这里
            if (!page.HasMore || newest <= after)
            {
                // 有进展才留痕：空账号开机那次 RestoreHistory 不该每天写一行"补拉完成"
                if (high > missingFrom - 1) AppLog.BackfillCompleted(missingFrom, high);
                return;
            }
            after = newest;
        }
    }

    /// <summary>§5.3：他端推进了已读游标，本端红点要跟着降；自己上报的那次回环忽略，
    /// 免得把别的端刚推上去的游标又抵掉。</summary>
    private void ApplyRead(ReadFrame read)
    {
        if (read.ChannelNo.Length == 0 || read.SenderId.Length == 0) return;
        var mine = _current?.DeviceId;
        if (read.DeviceId != null && mine != null && read.DeviceId == mine) return;
        _db.ApplyRead(read.ChannelNo, read.SenderId, read.LastReadSeq);
    }

    /// <summary>
    /// §4.5：只回填媒体终态，不推进游标、不再发通知（那条消息落库时已经通知过一次）。
    /// 帧比消息先到（补拉与实时下发的竞态）时命中 0 行，就暂存到那条消息落库再补一次——
    /// 乱序与重复都走这一条路径，所以两种到达顺序的表现一致（§8.4 W8 的验收口径）。
    /// </summary>
    private void ApplyMediaReady(MediaReadyFrame frame)
    {
        if (frame.Id.Length == 0) return;
        if (_db.ApplyMediaStatus(frame.Id, frame.Status, frame.SlotSpan)) return;

        if (!_mediaAhead.ContainsKey(frame.Id) && _mediaAhead.Count >= MediaAheadCap)
            _mediaAhead.Remove(_mediaAhead.Keys.First());
        _mediaAhead[frame.Id] = frame;
    }

    private void ApplyPendingMedia(string msgId)
    {
        if (!_mediaAhead.TryGetValue(msgId, out var pending)) return;
        _mediaAhead.Remove(msgId);
        _db.ApplyMediaStatus(pending.Id, pending.Status, pending.Media.Count);
    }

    private void SetState(SyncState next)
    {
        // 记录类型自带相等：同一个 Online 游标重复到达不算变化，UI 不用自己去重
        if (Equals(_state, next)) return;
        _state = next;
        StateChanged?.Invoke(next);
    }

    private static TimeSpan Elapsed(long sinceTimestamp) =>
        TimeSpan.FromSeconds((Stopwatch.GetTimestamp() - sinceTimestamp) / (double)Stopwatch.Frequency);

    /// <summary>FlushAsync 用的队列标记：不是协议帧，只是"排到我之前的一切都处理完了"。</summary>
    private sealed record Barrier : Frame
    {
        public Barrier() : base("barrier") {}

        public TaskCompletionSource Done { get; } =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
    }
}
