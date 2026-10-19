using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Data.Sqlite;
using PushBox.Windows.Data;
using PushBox.Windows.Net;
using PushBox.Windows.Sync;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W8：重连状态机 + 补拉 + 去重 + tombstone + 媒体回填。socket 和 HTTP 都是脚本，所以这里断言的全是
/// "引擎做了什么"：落了几行、弹了几次通知、什么时候再连、游标写到哪。真服务在 8081 上的握手联调随 W9 跑。
/// </summary>
public sealed class SyncEngineTest : IDisposable
{
    private static readonly JsonElement Vectors = Support.Vectors;
    private static readonly string VectorFp = Support.Str(Vectors.GetProperty("outputs"), "server_pub_fp");

    private static readonly byte[] DeviceEncPriv =
        Support.Hex(Support.Str(Vectors.GetProperty("inputs"), "device_enc_priv"));

    private static readonly byte[] DeviceSignSeed =
        Support.Hex(Support.Str(Vectors.GetProperty("inputs"), "device_sign_seed"));

    private readonly string _dir;
    private readonly IdentityStore _identities;
    private readonly DeviceStore _db;
    private readonly List<Rig> _started = new();

    public SyncEngineTest()
    {
        _dir = Path.Combine(Path.GetTempPath(), "pushbox-w8-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _identities = new IdentityStore(_dir);
        _db = new DeviceStore(Path.Combine(_dir, "pushbox.db"));
    }

    // ---- §7.2 退避与断因分派 ---------------------------------------------------------------------

    [Fact]
    public async Task ReconnectWaitsOneTwoFourSecondsAndCapsAtSixty()
    {
        var harness = Harness(Device());
        harness.Start();
        await Until(() => harness.Waits.Count >= 8, "退避要一路爬到上限");
        harness.Engine.Stop();

        Assert.Equal(new[] { 1d, 2, 4, 8, 16, 32, 60, 60 },
            harness.Waits.Take(8).Select(w => w.TotalSeconds).ToArray());
    }

    [Theory]
    // [已等秒数, 这条连接活了秒数, 下一次该等]
    [InlineData(1, 5, 1)]
    [InlineData(1, 1, 2)]
    [InlineData(2, 4, 4)]
    [InlineData(32, 0, 60)]
    [InlineData(60, 0, 60)]
    public void BackoffLadderDoublesButResetsAfterAHealthyConnection(int waited, int livedFor, int expected)
    {
        // 活得比两个周期还久就不算故障：一次正常的服务端重启之后，客户端不该按 60 秒慢慢恢复
        Assert.Equal(TimeSpan.FromSeconds(expected),
            SyncEngine.NextBackoff(TimeSpan.FromSeconds(waited), TimeSpan.FromSeconds(livedFor)));
    }

    [Fact]
    public async Task ARevokedDeviceStopsRetryingBecauseNoAmountOfWaitingHelps()
    {
        var harness = Harness(Device());
        harness.Raw("""{"t":"auth_fail","error":"设备已吊销"}""");
        harness.Start();
        await harness.Running.WaitAsync(TimeSpan.FromSeconds(4));

        // §5.1：吊销之后重连没有意义，也不会自己变好——循环自己收工，而 Blocked 得留在状态里，
        // 否则 W12 的顶栏会从"设备已被吊销"变成"还没启动"，用户看不到自己该做什么
        var blocked = Assert.IsType<SyncState.Blocked>(harness.Engine.State);
        Assert.Contains("auth_fail", blocked.Reason);
        Assert.Empty(harness.Waits);
        Assert.True(harness.Running.IsCompleted);
    }

    [Fact]
    public async Task AForgedServerIdentityBlocksInsteadOfSpinningTheBackoff()
    {
        // §4.3：本机钉过指纹，服务端换了身份 → 这是要人确认的事，不是"网络不好"
        var harness = Harness(DevicePinnedTo("ffffffffffffffff"));
        harness.Raw(Support.WsHello());
        harness.Start();
        await Until(() => harness.Engine.State is SyncState.Blocked, "钉过的指纹对不上要停在 Blocked，不能去跑退避");

        Assert.Equal("server_identity_mismatch", ((SyncState.Blocked)harness.Engine.State).Reason);
        // token 还没交出去就已经拆链（§4.3）
        Assert.Empty(harness.Sockets.Single().Sent);
        Assert.Empty(harness.Waits);
    }

    [Fact]
    public async Task RetryNowSkipsTheRestOfTheBackoffWait()
    {
        var harness = Harness(Device(), sleepForReal: true);
        harness.Start();
        await WaitState(harness, s => s is SyncState.Offline);

        var since = Stopwatch.StartNew();
        harness.Engine.RetryNow();
        // §7.2 网络刚恢复要立刻试一次：真等的话这里会是 1 秒，跳过则只有毫秒级
        await Until(() => harness.Sockets.Count >= 2, "RetryNow 之后应该马上再连一次", 700);
        Assert.True(since.Elapsed < TimeSpan.FromMilliseconds(700), $"退避没被跳过：{since.Elapsed}");
    }

    // ---- §6.2 去重与通知 --------------------------------------------------------------------------

    [Fact]
    public async Task FiveDeliveriesOfOneMessageLandOneRowAndOneNotify()
    {
        var late = Item(18485, "m_85");
        var harness = Harness(Device(), Pages(
            Page(new[] { Item(18483, "m_83"), Item(18484, "m_84") }),
            Page(new[] { Item(18484, "m_84") }, hasMore: false)));

        // m_83/m_84 从补拉的两页里来（页边重叠一条），m_85 是实时帧：这条连接服务端重发三次，
        // 断线重连后又 replay 两次。去重看的是 msg_id，所以五次只落一行、只弹一次
        harness.Enqueue(Handshake(Gap(18483, 18484), Message(late), Message(late), Message(late)));
        harness.Enqueue(Handshake(Message(late), Message(late)));
        harness.Start();
        await Until(() => harness.Sockets.Count >= 2, "第二次连接要再来一遍");
        await harness.Engine.FlushAsync();
        harness.Engine.Stop();

        Assert.Equal(new[] { "m_83", "m_84", "m_85" }, Rows().Select(r => r.MsgId).ToArray());
        // 谁先落库谁通知：这里第一份是实时帧，所以弹一次；后面四份都是重复，一条都不许再弹
        Assert.Equal(new[] { "m_85" }, harness.Toasts.Select(m => m.Id).ToArray());
        Assert.Equal(18485L, _db.GroupCursor());
    }

    [Fact]
    public async Task AMutedConversationLineStillLandsButNeverNotifies()
    {
        // §7.3 的免打扰门立在引擎这一侧（Android 的 SyncEngine.kt:141 同一位置）：静音只决定"要不要弹"，
        // 落库、游标、红点、会话行统统照常。PC 侧还没有写这一列的入口（§9.1 的会话右键菜单排在 W13 之后），
        // 所以这里用第二条连接按 §3.3 的形状造数据——测的是读它的那道门，不是改它的那只手
        _db.ApplyRead("83271504", "jenkins", 18482L); // 先有会话行，才谈得上给它打旗标
        Mute("jenkins");

        var muted = Item(18483, "m_83");
        var sibling = new Dictionary<string, object>(muted)
        {
            ["seq"] = 18484L,
            ["id"] = "m_84",
            ["sender_id"] = "grafana",
            ["sender_name"] = "Grafana",
        };

        var harness = Harness(Device());
        harness.Enqueue(Handshake(Message(muted), Message(sibling)));
        harness.Start();
        await Until(() => harness.Toasts.Count == 1, "同频道没静音的那条要照弹");
        await harness.Engine.FlushAsync();
        harness.Engine.Stop();

        Assert.Equal("m_83", Assert.Single(Rows()).MsgId);
        Assert.Equal(new[] { "m_84" }, harness.Toasts.Select(m => m.Id).ToArray());
        Assert.Equal(18484L, _db.GroupCursor());
        Assert.True(_db.IsMuted("83271504", "jenkins"));
        Assert.False(_db.IsMuted("83271504", "grafana"));
    }

    [Fact]
    public async Task BackfillPagesUntilHasMoreStopsAndNeverNotifies()
    {
        var harness = Harness(Device(), Pages(
            Page(new[] { Item(18483, "m_83"), Item(18484, "m_84") }, hasMore: true),
            Page(new[] { Item(18485, "m_85") }, hasMore: false)));
        harness.Enqueue(Handshake(Gap(18483, 18485)));
        harness.Start();
        await Until(() => harness.Http.Requests.Count >= 2, "两页都要拉到");
        await harness.Engine.FlushAsync();
        harness.Engine.Stop();

        // 游标一格一格往前，不会重复问同一页，也不会在 has_more 为假之后继续敲门
        Assert.Equal(new[] { "?after_seq=18482&limit=200", "?after_seq=18484&limit=200" },
            harness.Http.Requests.Select(r => r.Query).ToArray());
        Assert.Equal(3, Rows().Count);
        // 补拉拿到的是历史：一条都不弹，否则睡一觉回来会被几百条 Toast 糊住脸
        Assert.Empty(harness.Toasts);
    }

    [Fact]
    public async Task ATombstoneInsideThePageWinsOverTheSameRowsMessage()
    {
        Apply(Item(18483, "m_83"));
        var harness = Harness(Device(), Pages(Page(
            new[] { Item(18484, "m_84") },
            tombstones: new[] { TombstoneItem("m_83") })));
        harness.Enqueue(Handshake(Gap(18483, 18484)));
        harness.Start();
        await Until(() => harness.Http.Requests.Count >= 1, "补拉要发出去");
        await harness.Engine.FlushAsync();
        harness.Engine.Stop();

        Assert.Equal(new[] { "m_84" }, Rows().Select(r => r.MsgId).ToArray());
    }

    [Fact]
    public async Task AScopeTombstoneClearsTheWholeConversationLine()
    {
        var harness = Harness(Device());
        harness.Enqueue(Handshake(
            Message(Item(18483, "m_83")),
            Message(Item(18484, "m_84")),
            """{"t":"tombstone","scope":{"channel_no":"83271504","sender_id":"jenkins"},"deleted_at":1765500200000}"""));
        harness.Start();
        await Until(() => harness.States.OfType<SyncState.Online>().Count() >= 2, "两条都要落库");
        await harness.Engine.FlushAsync();
        harness.Engine.Stop();

        Assert.Empty(Rows());
        Assert.Empty(_db.Conversations());
    }

    // ---- §5.3 其余帧型 ---------------------------------------------------------------------------

    [Fact]
    public async Task AnotherDevicesReadClearsTheBadgeWhileOurOwnLoopbackDoesNot()
    {
        Apply(Item(18483, "m_83"));
        Apply(Item(18484, "m_84"));
        Apply(Item(18485, "m_85"));
        var harness = Harness(Device());
        harness.EnqueueHanging(Handshake(
            Read(18484, "dev_other"),
            // 本机 W9 上报之后服务端会把它广播回来：这条不能把别的端刚推上去的游标抵掉
            Read(18485, "dev_7f3a")));
        harness.Start();
        await Until(() => _db.Conversations().Any(c => c.LastReadSeq == 18484), "他端的已读要落到红点上");
        harness.Engine.Stop();

        var conversation = Assert.Single(_db.Conversations());
        Assert.Equal(18484L, conversation.LastReadSeq);
        Assert.Equal(1, conversation.UnreadCount);
    }

    [Fact]
    public async Task MediaReadyArrivingEarlyOrTwiceEndsUpOnTheRowOnce()
    {
        var row = Item(18484, "m_84");
        var ready = """{"t":"media_ready","id":"m_84","seq":18484,"status":"ready","media":[{"idx":0,"status":"ready"},{"idx":1,"status":"ready"}]}""";
        var harness = Harness(Device());
        harness.EnqueueHanging(Handshake(ready, Message(row), ready));
        harness.Start();
        await Until(() => Rows().Any(r => r.MediaStatus == "ready" && r.MediaCount == 2),
            "暂存的媒体终态要在消息落库时补上");
        harness.Engine.Stop();

        // 帧比消息先到时命中 0 行 → 暂存到消息落库再补；重复帧只是把同样的终态写第二次
        var stored = Assert.Single(Rows());
        Assert.Equal("ready", stored.MediaStatus);
        Assert.Equal(2, stored.MediaCount);
        Assert.Equal(new[] { "m_84" }, harness.Toasts.Select(m => m.Id).ToArray());
        Assert.Equal(18484L, _db.GroupCursor());
    }

    // ---- 失败与线程边界 ---------------------------------------------------------------------------

    [Fact]
    public async Task ARefusedBackfillLeavesTheCursorAloneAndTheQueueAlive()
    {
        var harness = Harness(Device(), new FakeHandler(_ => Support.Reply(HttpStatusCode.Unauthorized,
            Support.ErrorBody("unauthorized", "clock_skew"))));
        var late = Item(18485, "m_85");
        // 连接一直活着：401 之后它该继续收实时帧，而不是把自己拆掉重连
        harness.EnqueueHanging(Handshake(Gap(18483, 18485), Message(late), Message(Item(18486, "m_86"))));
        harness.Start();
        await Until(() => harness.Http.Requests.Count >= 1, "补拉要发出去");
        await harness.Engine.FlushAsync();

        // 401 是服务端拒绝，不是本机故障：游标只停在真正落库的那条、连接还在，下一条实时帧照样落库
        Assert.Equal(new[] { "m_85", "m_86" }, Rows().Select(r => r.MsgId).ToArray());
        Assert.Equal(new SyncState.Online(18486L), harness.Engine.State);
        Assert.False(harness.Running.IsCompleted);
        Assert.Empty(harness.Waits);
        harness.Engine.Stop();
    }

    [Fact]
    public async Task APageThatNeverArrivesNeverStallsTheReadLoop()
    {
        var gate = new TaskCompletionSource<HttpResponseMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        var harness = Harness(Device(), new FakeHandler(async (_, ct) => await Gate(gate, ct)));
        harness.EnqueueHanging(Handshake(Gap(18483, 18485), """{"t":"ping"}"""));
        harness.Start();
        await Until(() => harness.Http.Requests.Count >= 1, "补拉要发出去");

        // 补拉挂在线上时，同一连接的 ping 必须照样回 pong：§5.3 的 40 秒判死等不起一页 REST
        await Until(() => harness.Sockets.Single().Sent.Contains("""{"t":"pong"}"""),
            "读循环被补拉拖住了，pong 没按时出去");
        gate.TrySetResult(Support.Reply(HttpStatusCode.OK,
            Support.SealRestResponse(Page(Array.Empty<object>()))));
        harness.Engine.Stop();
    }

    [Fact]
    public async Task ReconnectStartsFromTheLargestCursorAndWritesItBackOnDisconnect()
    {
        Apply(Item(18490, "m_90"));
        var harness = Harness(Device(sinceSeq: 0));
        harness.Enqueue(Handshake(Message(Item(18491, "m_91"))));
        harness.EnqueueHanging();
        harness.Start();
        await Until(() => harness.ConnectedCount >= 2, "断开之后要重连");
        harness.Engine.Stop();

        // 第一次：库里已经到 18490 而 identity.json 还是 0 —— 建连取大的那份，否则这 18490 条会被重新拉一遍
        Assert.Equal("?since_seq=18490", harness.Sockets[0].ConnectedTo!.Query);
        Assert.Equal("?since_seq=18491", harness.Sockets[1].ConnectedTo!.Query);
        // 副本回写只为"下次开库之前也要知道从哪续"，权威值始终是 sync_state 那行
        Assert.Equal(18491L, _identities.Load().LastAppliedSeq);
    }

    [Fact]
    public async Task StatesWalkFromConnectingToOnlineThenOfflineWithTheNextWait()
    {
        var harness = Harness(Device());
        harness.Enqueue(Handshake(Message(Item(18483, "m_83"))));
        harness.EnqueueHanging();
        harness.Start();
        await Until(() => harness.Sockets.Count >= 2, "断开之后要重连");
        harness.Engine.Stop();
        await Until(() => harness.Engine.State is SyncState.Idle, "收工要回到 Idle");

        var states = harness.States.ToArray();
        Assert.Equal(new SyncState.Connecting(), states[0]);
        // 落库在消费线程、断开在读循环，两者谁先 SetState 不作保证，所以这里只断言"这几种状态都出现过"
        // 而不是它们的下标顺序——顺序断言会变成一条靠毫秒差运气跑的测试
        Assert.Contains(new SyncState.Online(18483L), states);
        // 顶栏要能直接显示"离线，1 秒后重连"，所以 Offline 带的是下一次真正要等的时间
        Assert.Contains(new SyncState.Offline("server_closed", TimeSpan.FromSeconds(1)), states);
        Assert.Contains(new SyncState.Connecting(), states);
        Assert.IsType<SyncState.Idle>(states.Last());
    }

    [Fact]
    public async Task AConnectionWithNothingToDeliverIsStillOnline()
    {
        // 真服务体检（W9）抓到的：一个还没发过消息的 Space 只会回 resume_ok，
        // 那时顶栏若一直停在"连接中"就是在骗人——连接好得很，只是没东西可给
        var harness = Harness(Device());
        harness.EnqueueHanging(Handshake("""{"t":"resume_ok"}"""));
        harness.Start();
        var online = await WaitState(harness, s => s is SyncState.Online);
        harness.Engine.Stop();

        // 在线带的是本机落库游标：库里一条都没有就是 0，而不是建连时那份 identity.json 副本
        Assert.Equal(new SyncState.Online(0L), online);
        Assert.Empty(harness.Waits);
    }

    [Fact]
    public async Task ARestartedEngineKeepsTakingFramesIn()
    {
        // 真服务体检 ⑥ 抓到的第二个坑：上一轮收工时 finally 把帧队列 TryComplete 了，复用同一条队列
        // 让重启后的 TryWrite 静默返回 false——握手照样成功、库里一条都不进，顶栏永远停在"连接中"
        var harness = Harness(Device());
        harness.EnqueueHanging(Handshake(Message(Item(18483, "m_83"))));
        harness.Start();
        await Until(() => Rows().Any(r => r.MsgId == "m_83"), "第一次起跑要落库");
        harness.Engine.Stop();
        await Until(() => harness.Engine.State is SyncState.Idle, "收工要回到 Idle");

        harness.EnqueueHanging(Handshake(Message(Item(18484, "m_84"))));
        _ = harness.Engine.Start(Device());
        await Until(() => Rows().Any(r => r.MsgId == "m_84"), "重启后同一台引擎还要能继续收帧");
        harness.Engine.Stop();

        Assert.Equal(new[] { "m_83", "m_84" }, Rows().Select(r => r.MsgId).ToArray());
    }

    [Fact]
    public async Task FirstContactPinsTheFingerprintIntoTheIdentityFile()
    {
        var harness = Harness(DevicePinnedTo(null));
        harness.EnqueueHanging(Handshake());
        harness.Start();
        await Until(() => _identities.Load().PinnedServerFp != null, "TOFU 指纹要落盘");
        harness.Engine.Stop();

        Assert.Equal(VectorFp, _identities.Load().PinnedServerFp);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    public void Dispose()
    {
        // 先让每台引擎收工再关库：不然 Dispose 会和还在落库的线程抢同一个连接
        foreach (var harness in _started)
        {
            harness.Engine.Stop();
            try
            {
                harness.Running.Wait(TimeSpan.FromSeconds(5));
            }
            catch
            {
                // 断线时的取消与落库竞态都在这条路径上；结论早在每个用例里就拿到了
            }
        }
        _db.Dispose();
        try
        {
            Directory.Delete(_dir, true);
        }
        catch (IOException)
        {
            // SQLite 句柄还挂在 OS 上：测试已经跑完，留着临时目录比误删别人的文件好
        }
    }

    private Rig Harness(DeviceIdentity identity, FakeHandler? http = null, bool sleepForReal = false)
    {
        var harness = new Rig(this, identity, http, sleepForReal);
        _started.Add(harness);
        return harness;
    }

    /// <summary>已经钉过向量指纹的设备：每一次连接都该认得这个服务端。</summary>
    private DeviceIdentity Device(long sinceSeq = 18482) => DevicePinnedTo(VectorFp, sinceSeq);

    /// <summary>换指纹就是在换另一件事：null 是"本机第一次接触这个服务端"，别的值是"钉过别人"。</summary>
    private DeviceIdentity DevicePinnedTo(string? fingerprint, long sinceSeq = 18482) => new(
        "dev_7f3a", DeviceEncPriv, DeviceSignSeed, "pbk_1a2b3c4d5e6f", "grp_001",
        "http://127.0.0.1:8081", fingerprint, sinceSeq);

    /// <summary>直接写库：模拟"上次跑过、这次冷启动"的本机状态。</summary>
    private void Apply(Dictionary<string, object> item) => _db.ApplyMessage(FrameParser.ReadMessage(
        JsonDocument.Parse(JsonSerializer.Serialize(item)).RootElement));

    /// <summary>
    /// 用第二条连接把 §3.3 的 <c>muted</c> 造出来。这一列在 PC 上还没有写它的入口（会话右键菜单排在
    /// W13 之后），但读它的那道门现在就立在通知路径上，所以两者都得先被测到。
    /// </summary>
    private void Mute(string senderId)
    {
        using var conn = new SqliteConnection($"Data Source={_db.DatabasePath}");
        conn.Open();
        using var busy = conn.CreateCommand();
        busy.CommandText = "PRAGMA busy_timeout = 5000;";
        busy.ExecuteNonQuery();

        using var cmd = conn.CreateCommand();
        cmd.CommandText =
            "UPDATE conversations SET muted = 1 WHERE channel_no = :channel AND sender_id = :sender;";
        cmd.Parameters.AddWithValue(":channel", "83271504");
        cmd.Parameters.AddWithValue(":sender", senderId);
        Assert.Equal(1, cmd.ExecuteNonQuery());
    }

    private List<MessageRow> Rows() => _db.MessagesPage("83271504", "jenkins").ToList();

    /// <summary>一条连接的脚本：明文 hello/kx，之后的帧按 §5.3 的帧序号封好。</summary>
    private static string[] Handshake(params string[] plaintextFrames)
    {
        var script = new List<string> { Support.WsHello(), Support.WsVectorKx() };
        for (var i = 0; i < plaintextFrames.Length; i++) script.Add(Support.SealWsFrame(i, plaintextFrames[i]));
        return script.ToArray();
    }

    private static Dictionary<string, object> Item(long seq, string id) => new()
    {
        ["seq"] = seq,
        ["id"] = id,
        ["channel_no"] = "83271504",
        ["sender_id"] = "jenkins",
        ["sender_name"] = "Jenkins",
        ["type"] = "text",
        ["title"] = "构建 #" + seq,
        ["summary"] = "部署完成",
        ["content"] = "正文",
        ["level"] = "error",
        ["created_at"] = 1_765_500_100_000L + seq,
    };

    private static string Message(Dictionary<string, object> item)
    {
        var withType = new Dictionary<string, object>(item) { ["t"] = FrameTypes.Message };
        return JsonSerializer.Serialize(withType);
    }

    private static string Gap(long missingFrom, long latestSeq) => JsonSerializer.Serialize(
        new Dictionary<string, object>
        {
            ["t"] = FrameTypes.ResumeGap,
            ["missing_from"] = missingFrom,
            ["latest_seq"] = latestSeq,
        });

    private static string Read(long lastReadSeq, string deviceId) => JsonSerializer.Serialize(
        new Dictionary<string, object>
        {
            ["t"] = FrameTypes.Read,
            ["channel_no"] = "83271504",
            ["sender_id"] = "jenkins",
            ["last_read_seq"] = lastReadSeq,
            ["device_id"] = deviceId,
        });

    private static string Page(object[] messages, bool hasMore = true, object[]? tombstones = null) =>
        JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["messages"] = messages,
            ["tombstones"] = tombstones ?? Array.Empty<object>(),
            ["latest_seq"] = 18485L,
            ["has_more"] = hasMore,
        });

    private static object TombstoneItem(string id) => new Dictionary<string, object>
    {
        ["id"] = id,
        ["deleted_at"] = 1_765_500_200_000L,
    };

    /// <summary>一页一个密封盒回答，问完就回空页：补拉的分页测试只看它有没有走到 has_more 为假。</summary>
    private static FakeHandler Pages(params string[] pageJson)
    {
        var index = 0;
        return new FakeHandler(_ => Support.Reply(HttpStatusCode.OK, Support.SealRestResponse(
            index < pageJson.Length ? pageJson[index++] : Page(Array.Empty<object>(), hasMore: false))));
    }

    private static async Task<HttpResponseMessage> Gate(TaskCompletionSource<HttpResponseMessage> gate,
        CancellationToken ct)
    {
        using var linked = ct.Register(() => gate.TrySetCanceled(ct));
        return await gate.Task;
    }

    private static async Task<SyncState> WaitState(Rig harness, Func<SyncState, bool> matches,
        int timeoutMs = 4000)
    {
        await Until(() => matches(harness.Engine.State), "状态没走到预期的那一步", timeoutMs);
        return harness.Engine.State;
    }

    private static async Task Until(Func<bool> condition, string because, int timeoutMs = 4000)
    {
        var deadline = Stopwatch.GetTimestamp() + (long)(timeoutMs * Stopwatch.Frequency / 1000);
        while (!condition() && Stopwatch.GetTimestamp() < deadline) await Task.Delay(5);
        Assert.True(condition(), because);
    }

    /// <summary>一台按脚本活的 PC：每次连接领一条 socket 脚本，HTTP 由 responder 决定，退避只记不睡。</summary>
    private sealed class Rig
    {
        private readonly Queue<(string[] Frames, bool Hang)> _scripts = new();
        private readonly IdentityStore _identities;

        public Rig(SyncEngineTest test, DeviceIdentity identity, FakeHandler? http, bool sleepForReal)
        {
            _identities = test._identities;
            _identities.Save(identity);
            Http = http ?? Pages();
            Sockets = new List<FakePushTransport>();
            Waits = new ConcurrentQueue<TimeSpan>();
            Toasts = new ConcurrentQueue<MessageFrame>();
            States = new ConcurrentQueue<SyncState>();

            Engine = new SyncEngine(test._identities, test._db, new DeviceClient(new HttpClient(Http)),
                new ToastRecorder(Toasts), Next, sleepForReal ? null : StubSleepAsync);
            Engine.StateChanged += state => States.Enqueue(state);
        }

        public SyncEngine Engine { get; }
        public FakeHandler Http { get; }
        public List<FakePushTransport> Sockets { get; }
        public ConcurrentQueue<TimeSpan> Waits { get; }
        public ConcurrentQueue<MessageFrame> Toasts { get; }
        public ConcurrentQueue<SyncState> States { get; }
        public Task Running { get; private set; } = Task.CompletedTask;

        /// <summary>已经真正连上的 socket 数：Sockets[1] 存在不等于它的 ConnectedTo 已经写好。</summary>
        public int ConnectedCount => Sockets.Count(s => s.ConnectedTo != null);

        public void Enqueue(params string[] frames) => _scripts.Enqueue((frames, false));

        /// <summary>脚本放完后不返回 EOF，让这条连接一直活着——重连节奏与"连接还活着"要能分开测。</summary>
        public void EnqueueHanging(params string[] frames) => _scripts.Enqueue((frames, true));

        /// <summary>不套 hello/kx 的原始帧：auth_fail 那种握手都没走完的回答。</summary>
        public void Raw(params string[] frames) => _scripts.Enqueue((frames, false));

        public void Start() => Running = Engine.Start(_identities.Load());

        private IPushTransport Next(DeviceIdentity identity)
        {
            var (frames, hang) = _scripts.Count > 0 ? _scripts.Dequeue() : (Array.Empty<string>(), false);
            var socket = new FakePushTransport(hang, frames);
            lock (Sockets) Sockets.Add(socket);
            return socket;
        }

        private async Task StubSleepAsync(TimeSpan wait, CancellationToken ct)
        {
            Waits.Enqueue(wait);
            await Task.Delay(1, ct);
        }

        private sealed class ToastRecorder : INotifier
        {
            private readonly ConcurrentQueue<MessageFrame> _toasts;

            public ToastRecorder(ConcurrentQueue<MessageFrame> toasts) => _toasts = toasts;

            public void Notify(MessageFrame message) => _toasts.Enqueue(message);
        }
    }
}
