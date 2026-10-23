using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Crypto;
using PushBox.Windows.Data;
using PushBox.Windows.Media;
using PushBox.Windows.Net;
using PushBox.Windows.Sync;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W14：删除与清空。§8.4 那行验收（"PC 删一条 → 手机红点与内容同步消失；反向亦然；重启后不复活"）
/// 在客户端这边拆成三件事：这一发要发成服务端认得的样子（面相 + §5.2 签名）、本机当场生效（乐观），
/// 以及服务端没答应时把这条原样放回（回滚兜底）。
/// 第三件是 PC 比 Android 多出来的一段：本机的组游标已经越过这条消息，服务端又不会再把它发回来，
/// 所以除了放回，没有第二条路让它在屏幕上重新出现。
/// </summary>
public sealed class DeleteReporterTest : IDisposable
{
    private const string BaseUrl = "http://127.0.0.1:8081";
    private const string Channel = "83271504";
    private const string Grafana = "grafana";

    private static readonly byte[] DeviceEncPriv =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_enc_priv"));

    private static readonly byte[] DeviceSignSeed =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_sign_seed"));

    private readonly string _dir;
    private readonly IdentityStore _identities;
    private readonly DeviceStore _db;
    private readonly MediaLoader _media;

    public DeleteReporterTest()
    {
        _dir = Path.Combine(Path.GetTempPath(), "pushbox-w14-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _identities = new IdentityStore(_dir);
        _identities.Save(Device());
        _db = new DeviceStore(Path.Combine(_dir, "pushbox.db"));
        _media = new MediaLoader(null, new MediaCache(Path.Combine(_dir, "media")));
    }

    // ---- §5.2 的那一发：面相与签名 ----------------------------------------------------------------

    [Fact]
    public async Task DeletingOneMessageIsASignedDeleteWithNoQueryAndNoBody()
    {
        Seed(1, 3);
        var handler = Deleted();

        Assert.Equal(DeleteOutcome.Deleted, (await Reporter(handler).DeleteMessageAsync("grafana-2")).Outcome);

        var request = handler.Single();
        Assert.Equal("DELETE", request.Method);
        Assert.Equal("/api/v1/messages/grafana-2", request.Path);
        // id 在路径里，所以 query 是空的：canonical 的第四行因此是空行
        Assert.Empty(request.Query);
        // DELETE 没有正文，第六行是 sha256("")——infra/probe 的 ⑪ 用真 HTTP 把这条口径钉过
        Assert.Empty(request.Body);
        Assert.Equal("Bearer pbk_1a2b3c4d5e6f", request.Header("Authorization"));
        Assert.Equal("dev_7f3a", request.Header("X-PB-Device"));
        Assert.True(Verifies(request, request.Body), "签名要对得上发出去的那串字节");

        // 本机这条当场就没了，会话行的聚合跟着重算（剩下两条，最后一条还是 seq 3）
        Assert.Equal(new[] { "grafana-1", "grafana-3" }, Ids());
        var conversation = Assert.Single(_db.Conversations());
        Assert.Equal(3L, conversation.LastSeq);
        Assert.Equal("摘要", conversation.LastSummary);
    }

    [Fact]
    public async Task ClearingAConversationIsOneScopedDelete()
    {
        Seed(1, 3);
        Seed(1, 2, "deploy");
        var handler = Deleted();

        Assert.Equal(DeleteOutcome.Deleted, (await Reporter(handler).ClearConversationAsync(Channel, Grafana)).Outcome);

        var request = handler.Single();
        // 整条会话是<b>一次</b>请求：服务端一条 UPDATE 标完 + 一条带 scope 的 tombstone 帧，
        // 逐条删在这里是错的（可能上千条，既打爆 SQL 往返也让别的端收到上千帧）
        Assert.Equal("DELETE", request.Method);
        Assert.Equal("/api/v1/messages", request.Path);
        // 参数次序与转义都照服务端的 @RequestParam：query 同时进签名和 URL
        Assert.Equal("?channel_no=83271504&sender_id=grafana", request.Query);
        Assert.Empty(request.Body);
        Assert.True(Verifies(request, request.Body));

        // 只有点名的那条会话没了，另一条一动不动
        var left = Assert.Single(_db.Conversations());
        Assert.Equal("deploy", left.SenderId);
        Assert.Equal(2, left.LastSeq);
    }

    // ---- 乐观：屏幕上消失不等网络 -------------------------------------------------------------------

    [Fact]
    public async Task TheOptimisticMomentComesBeforeTheRequest()
    {
        Seed(1, 3);
        var gate = new TaskCompletionSource<HttpResponseMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        var handler = new FakeHandler(async (_, ct) =>
        {
            // 这一发卡在"服务端"那里，直到测试决定它是死是活
            using var registration = ct.Register(() => gate.TrySetCanceled(ct));
            return await gate.Task;
        });
        var painted = false;

        var inFlight = Reporter(handler).DeleteMessageAsync("grafana-2", () => painted = true);
        await Until(() => handler.Requests.Count == 1, "请求该在乐观移除之后才上路");

        // 服务端还没答：回调已经走过一遍，库里也确实没有这条了
        Assert.True(painted, "乐观那一刻没有通知到窗口");
        Assert.Equal(new[] { "grafana-1", "grafana-3" }, Ids());

        gate.SetResult(Support.Reply(HttpStatusCode.OK,
            Support.SealRestResponse("{\"ok\":true,\"id\":\"grafana-2\",\"deleted_at\":1765500009000}")));
        Assert.Equal(DeleteOutcome.Deleted, (await inFlight).Outcome);
    }

    [Fact]
    public async Task AMessageTheStoreDoesNotHaveIsNotAskedOfTheServer()
    {
        Seed(1, 2);
        var handler = Deleted();

        var result = await Reporter(handler).DeleteMessageAsync("grafana-99");

        // 没有东西要藏，也没有东西要回滚，更不该白跑一趟网络
        Assert.Equal(DeleteOutcome.NothingLocal, result.Outcome);
        Assert.Null(result.Notice);
        Assert.Empty(handler.Requests);
        Assert.Equal(2, Ids().Length);
    }

    [Fact]
    public async Task ClearingAConversationThatIsNotHereSendsNothing()
    {
        Seed(1, 2, "deploy");
        var handler = Deleted();

        Assert.Equal(DeleteOutcome.NothingLocal,
            (await Reporter(handler).ClearConversationAsync(Channel, Grafana)).Outcome);
        Assert.Empty(handler.Requests);
    }

    // ---- 回滚兜底（§9.3）---------------------------------------------------------------------------

    [Fact]
    public async Task AServerRefusalPutsTheMessageBackWithItsReadCursor()
    {
        Seed(1, 3);
        // 已读游标是"只有本机知道"的那几列之一：放回时它必须原样回来，否则手机上刚灭的红点会凭空复现
        Assert.Equal(3L, _db.MarkConversationRead(Channel, Grafana));
        var handler = new FakeHandler(_ => Support.Reply((HttpStatusCode)500,
            Support.ErrorBody("server_error")));

        var result = await Reporter(handler).DeleteMessageAsync("grafana-2");

        Assert.Equal(DeleteOutcome.RolledBack, result.Outcome);
        Assert.Contains("500", result.Notice);
        Assert.Contains("放回", result.Notice);
        Assert.Equal(new[] { "grafana-1", "grafana-2", "grafana-3" }, Ids());
        var conversation = Assert.Single(_db.Conversations());
        Assert.Equal(3L, conversation.LastReadSeq);
        Assert.Equal(0, conversation.UnreadCount);
        // 聚合是从消息现算的，所以它跟着消息一起回来，不必由票带着
        Assert.Equal(3L, conversation.LastSeq);
    }

    [Fact]
    public async Task AWholeConversationComesBackInOrder()
    {
        Seed(1, 3);
        Seed(1, 2, "deploy");
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.Unauthorized,
            Support.ErrorBody("unauthorized")));

        var result = await Reporter(handler).ClearConversationAsync(Channel, Grafana);

        Assert.Equal(DeleteOutcome.RolledBack, result.Outcome);
        // 放回是按 seq 排的：MessagesPage 正序返回，所以这一条同时钉住"三条都回来了"和"顺序没乱"
        Assert.Equal(new[] { "grafana-1", "grafana-2", "grafana-3" }, Ids());
        // 另一条会话没被牵连
        Assert.Equal(2, _db.Conversations().Count);
        Assert.Equal(2L, _db.Conversations().First(c => c.SenderId == "deploy").LastSeq);
    }

    [Fact]
    public async Task ADeadNetworkRollsBackToo()
    {
        Seed(1, 2);
        var reporter = new DeleteReporter(_db, _identities,
            new DeviceClient(new HttpClient(new ThrowingHandler(new HttpRequestException("no route to host")))),
            _media);

        var result = await reporter.DeleteMessageAsync("grafana-1");

        // 断网时删除照样"看起来成功了"是 W14 最不能接受的一种失败：别的端根本没收到过这一发
        Assert.Equal(DeleteOutcome.RolledBack, result.Outcome);
        Assert.Contains("no route to host", result.Notice);
        Assert.Equal(new[] { "grafana-1", "grafana-2" }, Ids());
    }

    [Fact]
    public async Task TheGroupCursorNeverMovesForADelete()
    {
        Seed(1, 3);
        var cursor = _db.GroupCursor();
        var handler = new FakeHandler(_ => Support.Reply((HttpStatusCode)500, Support.ErrorBody("server_error")));

        await Reporter(handler).DeleteMessageAsync("grafana-2");

        // 回滚放回的是消息，不是游标：游标退回去会让补拉把这一段的旧消息整批重放一遍
        Assert.Equal(cursor, _db.GroupCursor());
    }

    // ---- 唯一不回滚的那一支：服务端本来就没有这条 ------------------------------------------------------

    [Fact]
    public async Task AMessageTheServerDoesNotHaveStaysGone()
    {
        Seed(1, 2);
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.NotFound,
            Support.ErrorBody("not_found", "message")));

        var result = await Reporter(handler).DeleteMessageAsync("grafana-1");

        // 服务端那张活表里已经没有它了：放回只会造出一条只有这台 PC 看得见的僵尸消息
        Assert.Equal(DeleteOutcome.Deleted, result.Outcome);
        Assert.Null(result.Notice);
        Assert.Equal(new[] { "grafana-2" }, Ids());
    }

    [Fact]
    public async Task ANotFoundFromSomethingOtherThanTheServerRollsBack()
    {
        Seed(1, 2);
        // 404 不一定是"这条没了"：地址指错服务、代理自己造了一个页面，都会回一个不带 hint=message 的 404。
        // 认的是 hint 而不是状态码，正是为了这种时候把消息放回而不是悄悄藏掉
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.NotFound, "<html>Not Found</html>", "text/html"));

        var result = await Reporter(handler).DeleteMessageAsync("grafana-1");

        Assert.Equal(DeleteOutcome.RolledBack, result.Outcome);
        Assert.Equal(new[] { "grafana-1", "grafana-2" }, Ids());
    }

    // ---- 媒体缓存的驱逐排在确认之后 -------------------------------------------------------------------

    [Fact]
    public async Task CacheBytesSurviveARollbackAndGoAwayWithAConfirmedDelete()
    {
        Seed(1, 2);
        var thumb = MediaLoader.ImageKey("grafana-1", 0, true);
        _media.Cache.Put(thumb, new byte[] { 1, 2, 3 });

        var failing = new FakeHandler(_ => Support.Reply((HttpStatusCode)500, Support.ErrorBody("server_error")));
        Assert.Equal(DeleteOutcome.RolledBack, (await Reporter(failing).DeleteMessageAsync("grafana-1")).Outcome);
        // 消息带着图一起回来了，这时候清缓存等于凭空多一次取图（还是取一张屏幕上正画着的）
        Assert.NotNull(_media.Cache.Get(thumb));

        Assert.Equal(DeleteOutcome.Deleted, (await Reporter(Deleted()).DeleteMessageAsync("grafana-1")).Outcome);
        // 服务端确认删过之后，对象已经被标成待删（§4.5 的宽限期），本机留着的副本是别处再也取不到的孤儿字节
        Assert.Null(_media.Cache.Get(thumb));
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
            // SQLite 句柄还挂在 OS 上：结论早在用例里拿到了
        }
    }

    private static DeviceIdentity Device() => new(
        "dev_7f3a", DeviceEncPriv, DeviceSignSeed, "pbk_1a2b3c4d5e6f", "grp_001", BaseUrl, null, 0L);

    private DeleteReporter Reporter(FakeHandler handler) =>
        new(_db, _identities, new DeviceClient(new HttpClient(handler)), _media);

    /// <summary>服务端那两种成功回答都封成密封盒；单条的正文没被读，清空的 <c>deleted</c> 由客户端自己算。</summary>
    private static FakeHandler Deleted() => new(_ => Support.Reply(HttpStatusCode.OK,
        Support.SealRestResponse("{\"ok\":true,\"channel_no\":\"83271504\",\"sender_id\":\"grafana\","
            + "\"deleted\":3,\"deleted_at\":1765500009000}")));

    private void Seed(long fromSeq, long toSeq, string sender = Grafana)
    {
        for (var seq = fromSeq; seq <= toSeq; seq++)
        {
            _db.ApplyMessage(new MessageFrame(seq, $"{sender}-{seq}", Channel, sender, "Grafana", null, "text",
                "标题", "摘要", "正文", "info", null, null, Array.Empty<MediaSlot>(), true,
                1_765_500_000_000L + seq * 60_000, false));
        }
    }

    private string[] Ids() => _db.MessagesPage(Channel, Grafana, long.MaxValue, 100)
        .Select(m => m.MsgId).ToArray();

    /// <summary>§4.4 的核对方式：拿发出去的原文（method/path/query/body/ts/nonce）重拼 canonical 再验签。</summary>
    private static bool Verifies(RecordedRequest request, byte[] body)
    {
        var canonical = RestCanonical.Build(Device().DeviceId, request.Method, request.Path,
            request.Query.Length > 1 ? request.Query[1..] : null, body,
            long.Parse(request.Header("X-PB-Ts")), B64Url.Decode(request.Header("X-PB-Nonce")));
        return Ed25519Sign.Verify(Ed25519Sign.DerivePublic(DeviceSignSeed), canonical,
            B64Url.Decode(request.Header("X-PB-Sig")));
    }

    private static async Task Until(Func<bool> condition, string because, int timeoutMs = 4000)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (!condition() && DateTime.UtcNow < deadline) await Task.Delay(10);
        Assert.True(condition(), because);
    }
}
