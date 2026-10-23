using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Logging;
using PushBox.Windows.Media;
using PushBox.Windows.Net;
using PushBox.Windows.Sync;

namespace PushBox.Crypto.Tests;

/// <summary>
/// §8.4 W15 的后半条：<b>接入点真的被走到了吗</b>。<see cref="AppLogTest"/> 量的是那五道网合起来挡不挡得住，
/// 这一页量的是另一件事——把真组件（引擎、删除、已读、搜索、取图、缓存）跑到失败那一支上，
/// 然后读回真文件：该留的一行不少，不该留的一个字不多。
///
/// 为什么这些用例值得单独一页：一个没人调用的日志入口和没有日志是同一件事，
/// 而"漏了一个接入点"恰好是那种 grep 十万行留痕才能发现的故障。
/// 和 <see cref="AppLogTest"/> 同一个 collection 是因为 <see cref="AppLog"/> 是全局的一本：
/// 两个类并行 Start/CloseAndFlush 会互相抢那本日志，用例就成了抛硬币。
/// </summary>
[Collection("AppLog")]
public sealed class LogWiringTest : IDisposable
{
    private const string BaseUrl = "http://127.0.0.1:8081";
    private const string Channel = "83271504";
    private const string Grafana = "grafana";

    /// <summary>带进错误回包里的碎片：hint 会被拼进 <c>e.Message</c>，所以这一页的每条断言都在盯"那句人话有没有漏进日志"。</summary>
    private const string Marker = "正文碎片ALPHA";

    private static readonly byte[] DeviceEncPriv =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_enc_priv"));

    private static readonly byte[] DeviceSignSeed =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_sign_seed"));

    private static readonly string VectorFp = Support.Str(Support.Vectors.GetProperty("outputs"), "server_pub_fp");

    private readonly string _home;
    private readonly IdentityStore _identities;
    private readonly DeviceStore _db;

    public LogWiringTest()
    {
        _home = Path.Combine(Path.GetTempPath(), "pushbox-w15-wiring-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_home);
        _identities = new IdentityStore(_home);
        // 组件那几条用例都真发一次签名请求，所以身份默认就在盘上；"读不出身份"那条另用一个空目录
        _identities.Save(Device());
        _db = new DeviceStore(Path.Combine(_home, "pushbox.db"));
    }

    // ---- §9.3 删除 --------------------------------------------------------------------------------

    [Fact]
    public async Task ADeleteTheServerRefusesLeavesTheCodeButNotTheSentenceAroundIt()
    {
        AppLog.Start(_home);
        Seed(1, 2);
        // 400 + hint：ApiErrorText 会把 hint 原样拼进那句人话（UI 该看见），日志只认 error 码
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.BadRequest,
            Support.ErrorBody("invalid_param", Marker)));

        var result = await Reporter(handler).DeleteMessageAsync($"{Grafana}-1");

        Assert.Equal(DeleteOutcome.RolledBack, result.Outcome);
        var line = Assert.Single(WiringLines());
        Assert.Contains("删除 message 结局=RolledBack invalid_param PushBoxApiException", line);
        AssertNothingLikeContent();
    }

    [Fact]
    public async Task ADeleteThatTheServerAlreadyForgotIsStillOneLineWithNotFoundOnIt()
    {
        AppLog.Start(_home);
        Seed(1, 2);
        // 404 hint=message 是不回滚的那一支：它同样是"结局"，日志里不该只有失败才有痕迹
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.NotFound,
            Support.ErrorBody("not_found", "message")));

        Assert.Equal(DeleteOutcome.Deleted, (await Reporter(handler).DeleteMessageAsync($"{Grafana}-1")).Outcome);

        var line = Assert.Single(WiringLines());
        Assert.Contains("删除 message 结局=Deleted not_found", line);
        AssertNothingLikeContent();
    }

    [Fact]
    public async Task AClearedConversationIsLoggedOnceByScopeNotByWhatWasInIt()
    {
        AppLog.Start(_home);
        Seed(1, 3);
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.Unauthorized,
            Support.ErrorBody("unauthorized", Marker)));

        Assert.Equal(DeleteOutcome.RolledBack,
            (await Reporter(handler).ClearConversationAsync(Channel, Grafana)).Outcome);

        // scope 只到"整会话"这一级：清空了多少条、都是什么，日志一条都不答
        var line = Assert.Single(WiringLines());
        Assert.Contains("删除 conversation 结局=RolledBack unauthorized", line);
        AssertNothingLikeContent();
    }

    // ---- §5.2 已读上报 -------------------------------------------------------------------------------

    [Fact]
    public async Task AFailedReadReportLeavesTheBatchSizeAndTheCode()
    {
        AppLog.Start(_home);
        Seed(1, 2);
        var reporter = ReadReporter(new FakeHandler(_ => Support.Reply(HttpStatusCode.BadRequest,
            Support.ErrorBody("invalid_param", Marker))));
        reporter.MarkRead(Channel, Grafana);

        Assert.Equal(0, await reporter.FlushAsync());

        var line = Assert.Single(WiringLines());
        Assert.Contains("已读上报 0 条 invalid_param", line);
        AssertNothingLikeContent();
    }

    [Fact]
    public async Task ASuccessfulReadReportSaysOkAndHowMany()
    {
        AppLog.Start(_home);
        Seed(1, 2);
        var reporter = ReadReporter(new FakeHandler(_ => Support.Reply(HttpStatusCode.OK,
            Support.SealRestResponse("{\"ok\":true,\"updated\":1}"))));
        reporter.MarkRead(Channel, Grafana);

        Assert.Equal(1, await reporter.FlushAsync());

        // "0 条"和"1 条"要分得开，否则红点为什么还在这件事仍然查不到
        Assert.Contains("已读上报 1 条 ok", Assert.Single(WiringLines()));
        AssertNothingLikeContent();
    }

    // ---- §7.4 搜索 --------------------------------------------------------------------------------

    [Fact]
    public async Task ARefusedServerSearchNeverRevealsWhatWasTyped()
    {
        AppLog.Start(_home);
        Seed(1, 2);
        var service = new SearchService(_db, _identities,
            new DeviceClient(new HttpClient(new FakeHandler(_ => Support.Reply(HttpStatusCode.BadRequest,
                Support.ErrorBody("invalid_param", Marker))))));

        // 用户敲进去的词在 §14 的黑名单里（query/q/keyword），它进 URL、进状态条，但不进日志
        var result = await service.SearchAsync("告警碎片DELTA");

        Assert.NotNull(result.Error);
        var line = Assert.Single(WiringLines());
        Assert.Contains("服务端搜索被拒 invalid_param PushBoxApiException", line);
        AssertNothingLikeContent();
        Assert.DoesNotContain("DELTA", Read());
    }

    // ---- §4.5 媒体 --------------------------------------------------------------------------------

    [Fact]
    public async Task AnUnreachableImageLeavesTheSlotKindNotTheMessageId()
    {
        AppLog.Start(_home);
        var msgId = "01JTESTMESSAGE000000000000";
        var loader = new MediaLoader(
            new DeviceClient(new HttpClient(new ThrowingHandler(new HttpRequestException($"连不上 {BaseUrl}：{Marker}")))),
            new MediaCache(Path.Combine(_home, "media")));

        Assert.Equal(MediaState.NetworkError, (await loader.ImageAsync(Device(), msgId, 0, true)).State);

        // 日志只回答"缩略图还是原图还是头像"，msg_id 是能反查内容的定位符，不占这一行
        var line = Assert.Single(WiringLines());
        Assert.Contains("取图失败 thumb PushBoxTransportException", line);
        Assert.DoesNotContain(msgId, Read());
        AssertNothingLikeContent();
    }

    [Fact]
    public async Task ARejectedImageRequestThatIsNotASlotStateIsTheOneThatSpeaks()
    {
        AppLog.Start(_home);
        // pending 是服务端明说的槽位状态，UI 那一格会转圈，不需要日志重复一遍
        var pending = new MediaLoader(new DeviceClient(new HttpClient(new FakeHandler(_ =>
                Support.Reply(HttpStatusCode.NotFound, Support.ErrorBody("not_found", "pending"))))),
            new MediaCache(Path.Combine(_home, "pending-media")));
        Assert.Equal(MediaState.Pending, (await pending.ImageAsync(Device(), "01JPENDING0000000000000000", 0, true)).State);
        Assert.Empty(WiringLines());

        // 401 那种"这条链路不对"才留痕：图位空着却没一点痕迹，是最难查的一种坏
        var refused = new MediaLoader(new DeviceClient(new HttpClient(new FakeHandler(_ =>
                Support.Reply(HttpStatusCode.Unauthorized, Support.ErrorBody("unauthorized", Marker))))),
            new MediaCache(Path.Combine(_home, "refused-media")));
        Assert.Equal(MediaState.NetworkError, (await refused.ImageAsync(Device(), "01JREFUSED00000000000000000", 0, false)).State);

        Assert.Contains("取图失败 full", Assert.Single(WiringLines()));
        AssertNothingLikeContent();
    }

    [Fact]
    public void ACacheThatCannotWriteToDiskSaysSoOncePerMiss()
    {
        AppLog.Start(_home);
        // 拿一个"同名占位文件"当缓存目录：CreateDirectory 当场 IOException，内存那份照旧
        var blocked = Path.Combine(_home, "blocked-dir");
        File.WriteAllText(blocked, "占位");
        var cache = new MediaCache(blocked);

        cache.Put(MediaLoader.ImageKey("01JTESTMESSAGE000000000000", 0, true), new byte[] { 1, 2, 3 });

        Assert.Contains("图片落盘失败 IOException", Assert.Single(WiringLines()));
        AssertNothingLikeContent();
    }

    // ---- §7.2 同步循环 -------------------------------------------------------------------------------

    [Fact]
    public async Task ABackfillTheServerRefusesLeavesTheCodeAndTheNextReconnectLeavesTheWait()
    {
        AppLog.Start(_home);
        var engine = Engine(new FakeHandler(_ => Support.Reply(HttpStatusCode.BadRequest,
            Support.ErrorBody("invalid_param", Marker))));
        engine.Enqueue(Handshake(Gap(18483, 18485)));
        engine.Start();

        // 补拉失败不断链：游标没动、连接照旧走完退避再重连，所以这两行是一起出现的。
        // 等的是"两行都到齐"而不是只等第一行：补拉跑在消费队列上，可能比那次断开更晚落地
        await Until(() =>
        {
            var written = Read();
            return written.Contains("补拉被服务端拒") && written.Contains("秒后重连");
        }, "补拉被拒与重连的留痕没一起出现");
        engine.Stop();

        var all = Read();
        Assert.Contains("补拉被服务端拒 invalid_param (400)", all);
        Assert.Contains("秒后重连", all);
        AssertNothingLikeContent();
    }

    [Fact]
    public async Task ARevokedDeviceStopsTheLoopAndLeavesWhy()
    {
        AppLog.Start(_home);
        var engine = Engine(new FakeHandler(_ => Support.Reply(HttpStatusCode.OK, "{}")));
        // 握手都没走完就回来的那一句：服务端给的话术里不含任何消息内容
        engine.Raw("""{"t":"auth_fail","error":"设备凭据不对"}""");
        engine.Start();

        await Until(() => engine.Engine.State is SyncState.Blocked, "吊销要停在 Blocked，而不是转圈重连");
        await engine.Running;

        Assert.Contains("同步已停：auth_fail", Assert.Single(WiringLines()));
    }

    [Fact]
    public async Task AnUnreadableIdentityFileIsTheOneThingThatExplainsAnOldCursor()
    {
        AppLog.Start(_home);
        // 刻意不写身份文件：循环每轮起跑都现读一次，读不到就沿用手上那份——这条不留在日志里就永远没人知道
        var engine = EngineWithoutIdentityFile(new FakeHandler(_ => Support.Reply(HttpStatusCode.OK, "{}")));
        engine.Enqueue(Handshake());
        engine.Start();

        await Until(() => WiringLines().Any(l => l.Contains("身份文件读不出")), "身份文件读不出没留下痕迹");
        engine.Stop();

        Assert.Contains("身份文件读不出，沿用手上那份 FileNotFoundException", Read());
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private WiringRig Engine(FakeHandler http) => RigOf(_identities, http, saveIdentity: true);

    /// <summary>换一个空目录当身份库：循环每轮起跑都要现读一次，读不到就沿用手上那一份——那正是 W15
    /// 要留痕的那一支，而它没有别的触发方式（真机上就是"文件被别的进程改坏/还没落盘"）。</summary>
    private WiringRig EngineWithoutIdentityFile(FakeHandler http) =>
        RigOf(new IdentityStore(Path.Combine(_home, "no-identity")), http, saveIdentity: false);

    private WiringRig RigOf(IdentityStore identities, FakeHandler http, bool saveIdentity)
    {
        var identity = Device(VectorFp);
        if (saveIdentity) identities.Save(identity);
        return new WiringRig(identities, _db, http, identity);
    }

    private DeleteReporter Reporter(FakeHandler handler) =>
        new(_db, _identities, new DeviceClient(new HttpClient(handler)),
            new MediaLoader(null, new MediaCache(Path.Combine(_home, "media"))));

    private ReadReporter ReadReporter(FakeHandler handler) =>
        new(_db, _identities, new DeviceClient(new HttpClient(handler)));

    private static DeviceIdentity Device(string? fingerprint = null) => new(
        "dev_7f3a", DeviceEncPriv, DeviceSignSeed, "pbk_1a2b3c4d5e6f", "grp_001", BaseUrl, fingerprint, 18482L);

    private void Seed(long fromSeq, long toSeq)
    {
        for (var seq = fromSeq; seq <= toSeq; seq++)
        {
            _db.ApplyMessage(new MessageFrame(seq, $"{Grafana}-{seq}", Channel, Grafana, "Grafana", null, "text",
                "标题碎片CHARLIE", "摘要碎片BRAVO", Marker, "info", null, null,
                Array.Empty<MediaSlot>(), true, 1_765_500_000_000L + seq * 60_000, false));
        }
    }

    /// <summary>一条连接的脚本：明文 hello/kx，之后的帧按 §5.3 的帧序号封好。</summary>
    private static string[] Handshake(params string[] plaintextFrames)
    {
        var script = new List<string> { Support.WsHello(), Support.WsVectorKx() };
        for (var i = 0; i < plaintextFrames.Length; i++) script.Add(Support.SealWsFrame(i, plaintextFrames[i]));
        return script.ToArray();
    }

    private static string Gap(long missingFrom, long latestSeq) => JsonSerializer.Serialize(
        new Dictionary<string, object>
        {
            ["t"] = FrameTypes.ResumeGap,
            ["missing_from"] = missingFrom,
            ["latest_seq"] = latestSeq,
        });

    /// <summary>整本留痕（这一页只开过一本，都在 <paramref name="_home"/> 下）。</summary>
    private string Read() => string.Join("\n", WiringLines());

    /// <summary>只取"用例里真的写进去"的那些行：空文件与标题行不算证据。</summary>
    private string[] WiringLines()
    {
        var dir = AppLog.DirectoryFor(_home);
        return Directory.Exists(dir)
            ? Directory.GetFiles(dir, "pushbox-*.log").SelectMany(ReadWhileOpen).ToArray()
            : Array.Empty<string>();
    }

    /// <summary>边写边读：sink 自己还攥着写句柄，<c>File.ReadAllLines</c> 那套 FileShare 跟它谈不拢，
    /// 必须按 ReadWrite 打开才读得回来（引擎那两条用例等的就是"还在跑的那一轮"留下的行）。</summary>
    private static IEnumerable<string> ReadWhileOpen(string path)
    {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        using var reader = new StreamReader(stream, Encoding.UTF8);
        var lines = new List<string>();
        string? line;
        while ((line = reader.ReadLine()) != null) lines.Add(line);
        return lines;
    }

    /// <summary>这一页每条用例的收尾断言：留痕里既不能有正文/摘要/标题的碎片，也不能有那条消息的 id。</summary>
    private void AssertNothingLikeContent()
    {
        var all = Read();
        Assert.DoesNotContain("ALPHA", all);
        Assert.DoesNotContain("BRAVO", all);
        Assert.DoesNotContain("CHARLIE", all);
        Assert.DoesNotContain("正文", all);
        Assert.DoesNotContain("摘要", all);
        Assert.DoesNotContain("01JTEST", all);
    }

    private static async Task Until(Func<bool> condition, string because, int timeoutMs = 4000)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (!condition() && DateTime.UtcNow < deadline) await Task.Delay(10);
        Assert.True(condition(), because);
    }

    public void Dispose()
    {
        // 全局那本必须收掉：下一条用例 Start 的是同一本，句柄没关就会把上一本的缓冲算进这一本
        AppLog.CloseAndFlush();
        _db.Dispose();
        try
        {
            Directory.Delete(_home, true);
        }
        catch (IOException)
        {
            // SQLite 句柄还挂在 OS 上：结论早在用例里拿到了
        }
    }

    /// <summary>socket 队列 + 不真睡的退避。<see cref="SyncEngineTest"/> 里那个 Rig 是私有的，这里只要三样东西。</summary>
    private sealed class WiringRig
    {
        private readonly Queue<string[]> _scripts = new();

        public WiringRig(IdentityStore identities, DeviceStore db, FakeHandler http,
            DeviceIdentity identity)
        {
            Identity = identity;
            Engine = new SyncEngine(identities, db, new DeviceClient(new HttpClient(http)),
                new NoToast(), Next, StubSleepAsync);
        }

        public SyncEngine Engine { get; }
        public DeviceIdentity Identity { get; }
        public Task Running { get; private set; } = Task.CompletedTask;

        public void Enqueue(params string[] frames) => _scripts.Enqueue(frames);

        /// <summary>不套 hello/kx 的原始帧：auth_fail 那种握手都没走完的回答。</summary>
        public void Raw(params string[] frames) => _scripts.Enqueue(frames);

        public void Start() => Running = Engine.Start(Identity);

        public void Stop() => Engine.Stop();

        private IPushTransport Next(DeviceIdentity identity) =>
            new FakePushTransport(false, _scripts.Count > 0 ? _scripts.Dequeue() : Array.Empty<string>());

        private static async Task StubSleepAsync(TimeSpan wait, CancellationToken ct) =>
            await Task.Delay(1, ct);

        private sealed class NoToast : INotifier
        {
            public void Notify(MessageFrame message) {}
        }
    }
}
