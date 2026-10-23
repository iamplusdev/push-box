using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Crypto;
using PushBox.Windows.Data;
using PushBox.Windows.Net;
using PushBox.Windows.Sync;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W9：已读上报。这是 §5.2 的签名第一次用在<b>写</b>请求上，所以前半部分逐字节核对面相
/// （正文按真正发出去的那串字节进签名），后半部分是"托盘常驻"这个形态特有的两条时序：
/// 断网期间攒着、联网一次批量补报，以及已经报过的游标绝不发第二次（服务端那一行是后写覆盖）。
/// </summary>
public sealed class ReadReporterTest : IDisposable
{
    private const string BaseUrl = "http://127.0.0.1:8081";
    private const string Channel = "83271504";

    private static readonly byte[] DeviceEncPriv =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_enc_priv"));

    private static readonly byte[] DeviceSignSeed =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_sign_seed"));

    private readonly string _dir;
    private readonly IdentityStore _identities;
    private readonly DeviceStore _db;

    public ReadReporterTest()
    {
        _dir = Path.Combine(Path.GetTempPath(), "pushbox-w9-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _identities = new IdentityStore(_dir);
        _identities.Save(Device());
        _db = new DeviceStore(Path.Combine(_dir, "pushbox.db"));
    }

    // ---- §5.2 的签名 PUT：面相 --------------------------------------------------------------------

    [Fact]
    public async Task ReadReportIsASignedPutOfTheCursorArray()
    {
        var handler = Reporting(2);
        var identity = Device();

        var updated = await Client(handler).ReportReadAsync(identity, new[]
        {
            new ReadEntry(Channel, "jenkins", 18483L),
            new ReadEntry("91002203", "grafana", 7L),
        });

        // 报出去几条是服务端确认的，队列要按这个数收工
        Assert.Equal(2, updated);
        var request = handler.Single();
        Assert.Equal("PUT", request.Method);
        Assert.Equal("/api/v1/state/read", request.Path);
        // §5.3：token 与设备号都不进 URL
        Assert.Empty(request.Query);

        var items = request.BodyJson().EnumerateArray().ToList();
        Assert.Equal(2, items.Count);
        // 字段名必须与 core/api 的 ReadItem 逐字一致，否则服务端整片读不出来
        Assert.Equal(Channel, Support.Str(items[0], "channel_no"));
        Assert.Equal("jenkins", Support.Str(items[0], "sender_id"));
        Assert.Equal(18483L, items[0].GetProperty("last_read_seq").GetInt64());
        Assert.Equal("91002203", Support.Str(items[1], "channel_no"));
        Assert.Equal(7L, items[1].GetProperty("last_read_seq").GetInt64());

        Assert.Equal("Bearer pbk_1a2b3c4d5e6f", request.Header("Authorization"));
        Assert.Equal("dev_7f3a", request.Header("X-PB-Device"));
        Assert.Equal(16, B64Url.Decode(request.Header("X-PB-Nonce")).Length);
        AssertSigned(request, identity);
    }

    [Fact]
    public async Task TheSignatureCoversTheExactBodyBytesThatLeft()
    {
        // canonical 的第六行是 body 的 sha256：正文改一个字节（少报一位游标就是一次不同的写）就验不过
        var handler = Reporting();
        var identity = Device();
        await Client(handler).ReportReadAsync(identity, new[] { new ReadEntry(Channel, "jenkins", 18483L) });

        var request = handler.Single();
        var tampered = EncodingOf(
            "[{\"channel_no\":\"" + Channel + "\",\"sender_id\":\"jenkins\",\"last_read_seq\":1}]");
        Assert.False(Verifies(request, identity, tampered));
        Assert.True(Verifies(request, identity, request.Body));
    }

    [Fact]
    public async Task AnEmptyBatchNeverTouchesTheNetwork()
    {
        var handler = Reporting();

        Assert.Equal(0, await Client(handler).ReportReadAsync(Device(), Array.Empty<ReadEntry>()));
        Assert.Empty(handler.Requests);
    }

    [Fact]
    public async Task APlainHtmlAnswerSaysTheReplyIsNotASealedBox()
    {
        // §5.2：/api/v1 的成功回答一律是密封盒；不是它就说明地址指错了服务或被代理拦下
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.OK, "<html>login</html>", "text/html"));

        var e = await Assert.ThrowsAsync<PushBoxTransportException>(
            () => Client(handler).ReportReadAsync(Device(), new[] { new ReadEntry(Channel, "jenkins", 3L) }));

        Assert.Contains("密封盒", e.Message);
    }

    [Fact]
    public async Task ARejectedDeviceComesBackAsOneReadableSentence()
    {
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.Unauthorized,
            Support.ErrorBody("unauthorized")));

        var e = await Assert.ThrowsAsync<PushBoxApiException>(
            () => Client(handler).ReportReadAsync(Device(), new[] { new ReadEntry(Channel, "jenkins", 3L) }));

        Assert.Equal("unauthorized", e.Code);
    }

    // ---- 上报的时序：攒批、只增不减、不重报 --------------------------------------------------------

    [Fact]
    public async Task OpeningAConversationClearsTheBadgeWhileTheNetworkIsDown()
    {
        Seed(Channel, "jenkins", 1, 3);
        Assert.Equal(3, Assert.Single(_db.Conversations()).UnreadCount);

        var reporter = Reporter(new DeviceClient(new HttpClient(
            new ThrowingHandler(new HttpRequestException("no route to host")))));
        reporter.SetOnline(true);
        reporter.MarkRead(Channel, "jenkins");
        await Until(() => reporter.LastError != null, "上报失败要留下原因，而不是消失在没人等的 Task 里");

        // 本端红点不等网络：先把本地游标推到会话最后一条，报不报得出去是他端的事
        var conversation = Assert.Single(_db.Conversations());
        Assert.Equal(0, conversation.UnreadCount);
        Assert.Equal(3L, conversation.LastReadSeq);
        // 没报出去的那一条留在队列里，联网时补
        Assert.Equal(1, reporter.PendingCount);
    }

    [Fact]
    public async Task OfflineReadsGoOutAsOnePutWhenTheLinkReturns()
    {
        Seed(Channel, "jenkins", 1, 3);
        Seed(Channel, "grafana", 4, 9);
        Seed("91002203", "deploy", 1, 1);
        var handler = Reporting();
        var reporter = Reporter(Client(handler));

        // 合上盖子出门这段时间读过的三个会话：一次网络都不该碰，碰了也是白发
        reporter.MarkRead(Channel, "jenkins");
        reporter.MarkRead(Channel, "grafana");
        reporter.MarkRead("91002203", "deploy");
        Assert.Equal(3, reporter.PendingCount);
        Assert.Empty(handler.Requests);

        // §7.2 重连成功那一刻，三个游标一次 PUT 补报
        reporter.SetOnline(true);
        await Until(() => reporter.PendingCount == 0, "联网之后攒着的已读要补报出去");

        var request = handler.Single();
        var cursors = request.BodyJson().EnumerateArray()
            .ToDictionary(i => Support.Str(i, "sender_id"), i => i.GetProperty("last_read_seq").GetInt64());
        Assert.Equal(3, cursors.Count);
        Assert.Equal(3L, cursors["jenkins"]);
        Assert.Equal(9L, cursors["grafana"]);
        Assert.Equal(1L, cursors["deploy"]);
        AssertSigned(request, Device());
    }

    [Fact]
    public async Task AConversationReadTwiceReportsTheLargestCursorOnce()
    {
        Seed(Channel, "jenkins", 1, 3);
        var handler = Reporting();
        var reporter = Reporter(Client(handler));

        reporter.MarkRead(Channel, "jenkins");
        // 攒批期间又来了两条（长连接刚好在这一秒恢复下发）：队列里是同一个会话的两条游标，
        // 留着小的那条会把别端的红点点回来，所以只留最大的
        Seed(Channel, "jenkins", 4, 6);
        reporter.MarkRead(Channel, "jenkins");
        Assert.Equal(1, reporter.PendingCount);

        await reporter.FlushAsync();

        var items = handler.Single().BodyJson().EnumerateArray().ToList();
        Assert.Single(items);
        Assert.Equal(6L, items[0].GetProperty("last_read_seq").GetInt64());
    }

    [Fact]
    public async Task ACursorThatHasBeenReportedIsNotSentAgain()
    {
        Seed(Channel, "jenkins", 1, 3);
        var handler = Reporting();
        var reporter = Reporter(Client(handler));
        reporter.SetOnline(true);

        reporter.MarkRead(Channel, "jenkins");
        await Until(() => handler.Requests.Count >= 1, "联网时打开会话就该立刻上报");

        // 同一个会话再翻一遍：本地游标没动，就不该再发一次（服务端是后写覆盖，重复发只会白跑一趟）
        reporter.MarkRead(Channel, "jenkins");
        await reporter.FlushAsync();
        Assert.Equal(0, reporter.PendingCount);
        Assert.Single(handler.Requests);
    }

    [Fact]
    public async Task ARefusedReportKeepsTheCursorForTheNextAttempt()
    {
        Seed(Channel, "jenkins", 1, 3);
        var fail = true;
        var handler = new FakeHandler(_ => fail
            ? Support.Reply((HttpStatusCode)500, Support.ErrorBody("internal_error"))
            : Support.Reply(HttpStatusCode.OK, Support.SealRestResponse("{\"ok\":true,\"updated\":1}")));
        var reporter = Reporter(Client(handler));
        reporter.MarkRead(Channel, "jenkins");

        Assert.Equal(0, await reporter.FlushAsync());
        Assert.Equal(1, reporter.PendingCount);
        Assert.Contains("internal_error", reporter.LastError);

        // 已读不是"没到就算了"的事：这一轮的代价只该是红点晚点消失
        fail = false;
        Assert.Equal(1, await reporter.FlushAsync());
        Assert.Equal(0, reporter.PendingCount);
        Assert.Null(reporter.LastError);
        Assert.Equal(2, handler.Requests.Count);
    }

    [Fact]
    public async Task ACursorThatMovedWhileThePutWasInFlightOutlivesTheRetry()
    {
        Seed(Channel, "jenkins", 1, 5);
        var gate = new TaskCompletionSource<HttpResponseMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        var calls = 0;
        var handler = new FakeHandler(async (request, ct) =>
        {
            // 第一条 PUT 卡在服务端那里，直到测试决定它是死是活
            if (Interlocked.Increment(ref calls) == 1)
            {
                using var registration = ct.Register(() => gate.TrySetCanceled(ct));
                return await gate.Task;
            }

            return Support.Reply(HttpStatusCode.OK, Support.SealRestResponse("{\"ok\":true,\"updated\":1}"));
        });
        var reporter = Reporter(Client(handler));
        reporter.SetOnline(true);

        reporter.MarkRead(Channel, "jenkins");
        await Until(() => handler.Requests.Count == 1, "第一次上报该已经上路");

        // 在途期间用户又读了四条：这份更大的游标不能跟着失败的那一批一起退回去
        Seed(Channel, "jenkins", 6, 9);
        reporter.MarkRead(Channel, "jenkins");
        await Until(() => reporter.PendingCount == 1, "在途期间的新已读先进队列等下一轮");

        gate.SetException(new HttpRequestException("connection reset"));
        await Until(() => handler.Requests.Count == 2, "上一轮失败之后要自己再试一次");

        var items = handler.Requests[1].BodyJson().EnumerateArray().ToList();
        Assert.Single(items);
        Assert.Equal(9L, items[0].GetProperty("last_read_seq").GetInt64());
        await Until(() => reporter.PendingCount == 0, "重试成功后队列要清空");
    }

    [Fact]
    public async Task ARemoteCursorAheadOfOurOwnLastMessageIsNotDraggedBack()
    {
        Seed(Channel, "jenkins", 1, 8);
        // 本端离线时手机把这条会话读到了 10，重连后 read 帧先落库（W7 的 upsert）
        _db.ApplyRead(Channel, "jenkins", 10L);
        var handler = Reporting();
        var reporter = Reporter(Client(handler));

        // 本地"推到最后一条"是 MAX，所以这里报出去的是 10 而不是 8——发 8 会把手机刚清掉的红点又点亮
        reporter.MarkRead(Channel, "jenkins");
        Assert.Equal(1, await reporter.FlushAsync());

        var items = handler.Single().BodyJson().EnumerateArray().ToList();
        Assert.Equal(10L, items[0].GetProperty("last_read_seq").GetInt64());
        Assert.Equal(10L, Assert.Single(_db.Conversations()).LastReadSeq);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    public void Dispose()
    {
        _db.Dispose();
        try
        {
            Directory.Delete(_dir, true);
        }
        catch (IOException)
        {
            // SQLite 句柄还挂在 OS 上：结论早在用例里拿到了，留着临时目录比误删别人的文件好
        }
    }

    private static DeviceIdentity Device() => new(
        "dev_7f3a", DeviceEncPriv, DeviceSignSeed, "pbk_1a2b3c4d5e6f", "grp_001", BaseUrl, null, 0L);

    private static DeviceClient Client(FakeHandler handler) => new(new HttpClient(handler));

    private ReadReporter Reporter(DeviceClient client) => new(_db, _identities, client);

    /// <summary>一条正常回答：服务端确认收到几条就回几条，正文照 §5.2 封成密封盒。</summary>
    private static FakeHandler Reporting(int updated = 1) => new(_ =>
        Support.Reply(HttpStatusCode.OK, Support.SealRestResponse(
            "{\"ok\":true,\"updated\":" + updated + "}")));

    /// <summary>往库里按 seq 铺一段消息，模拟"这个会话读到哪了"。</summary>
    private void Seed(string channelNo, string senderId, long fromSeq, long toSeq)
    {
        for (var seq = fromSeq; seq <= toSeq; seq++)
        {
            _db.ApplyMessage(new MessageFrame(seq, $"{senderId}-{seq}", channelNo, senderId, senderId,
                null, "text", "标题", "摘要", "正文", "info", null, null,
                Array.Empty<MediaSlot>(), true, 1_765_500_000_000L + seq, false));
        }
    }

    /// <summary>§4.4 的核对方式：拿发出去的原文（method/path/query/body/ts/nonce）重拼 canonical 再验签。</summary>
    private static void AssertSigned(RecordedRequest request, DeviceIdentity identity) =>
        Assert.True(Verifies(request, identity, request.Body));

    private static bool Verifies(RecordedRequest request, DeviceIdentity identity, byte[] body)
    {
        var canonical = RestCanonical.Build(identity.DeviceId, request.Method, request.Path,
            request.Query.Length > 1 ? request.Query[1..] : null, body,
            long.Parse(request.Header("X-PB-Ts")), B64Url.Decode(request.Header("X-PB-Nonce")));
        return Ed25519Sign.Verify(Ed25519Sign.DerivePublic(identity.SignPrivSeed), canonical,
            B64Url.Decode(request.Header("X-PB-Sig")));
    }

    private static byte[] EncodingOf(string text) => System.Text.Encoding.UTF8.GetBytes(text);

    private static async Task Until(Func<bool> condition, string because, int timeoutMs = 4000)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (!condition() && DateTime.UtcNow < deadline) await Task.Delay(10);
        Assert.True(condition(), because);
    }
}
