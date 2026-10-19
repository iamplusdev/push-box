using System;
using System.Net;
using System.Net.Http;
using System.Threading.Tasks;
using PushBox.Windows.Crypto;
using PushBox.Windows.Data;
using PushBox.Windows.Net;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W11 的协议面：§5.2 的签名第一次用在<b>用户随手打字</b>当参数值的接口上。
/// 搜索词进 canonical 的第四行是原始 query 串，而这一串要先被百分号编码、再被 Uri 过一遍，
/// 两道规范化任何一道只发生在客户端或只发生在服务端，结果都是一片 401——所以这里
/// 一半的用例在核对"签的那串 == 上路的那串"，另一半在核对命中页的字段绑定。
/// </summary>
public class SearchClientTest
{
    private const string BaseUrl = "http://127.0.0.1:8081";
    private const string Channel = "83271504";

    /// <summary>vectors.json 里 rest_auth.get_search 的那串原始 query，参数顺序照它核对。</summary>
    private const string VectorQuery = "q=CPU%20usage&channel_no=83271504&sender_id=&since=0&limit=50";

    private static readonly byte[] DeviceEncPriv =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_enc_priv"));

    private static readonly byte[] DeviceSignSeed =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_sign_seed"));

    // ---- 签名的query ----------------------------------------------------------------------------

    [Fact]
    public async Task TheQueryThatGetsSignedIsTheOneThatLeaves()
    {
        var handler = Hits();
        var identity = Device();

        await Client(handler).SearchAsync(identity, "CPU usage", Channel);

        var request = handler.Single();
        Assert.Equal("GET", request.Method);
        Assert.Equal("/api/v1/search", request.Path);
        // §5.2 canonical 第四行：服务端拿 getQueryString() 复原的就是这一串
        Assert.Equal("?q=CPU%20usage&channel_no=" + Channel + "&since=0&limit=50", request.Query);
        AssertSigned(request, identity);
        // token 与设备号都不进 URL（§5.3）
        Assert.DoesNotContain("pbk_", request.Query);
    }

    [Theory]
    [InlineData("CPU usage", Channel, null, null, 0L, 50,
        "q=CPU%20usage&channel_no=83271504&since=0&limit=50")]
    [InlineData("磁盘 空间", null, "jenkins", "alert", 1765500000000L, 20,
        "q=%E7%A3%81%E7%9B%98%20%E7%A9%BA%E9%97%B4&sender_id=jenkins&type=alert&since=1765500000000&limit=20")]
    [InlineData("a&b=c", "", "   ", null, 0L, 50, "q=a%26b%3Dc&since=0&limit=50")]
    [InlineData("100% 使用率!", null, null, null, 0L, 50,
        "q=100%25%20%E4%BD%BF%E7%94%A8%E7%8E%87%21&since=0&limit=50")]
    public void ParametersAreEncodedThenJoinedInTheServersOrder(string q, string? channelNo,
        string? senderId, string? type, long since, int limit, string expected)
    {
        // 顺序固定为 q, channel_no, sender_id, type, since, limit，与 SearchController 的声明同序；
        // 没设的过滤条件整个省略而不是留 "key="，两边都只认这一种拼法
        Assert.Equal(expected, DeviceClient.SearchQuery(q, channelNo, senderId, type, since, limit));
    }

    [Fact]
    public async Task SpacesAndChineseSurviveTheRoundTripToTheWire()
    {
        // 空格必须是 %20 而不是 +（+ 在 query 里是加号本身），中文必须是 UTF-8 三字节转义：
        // 这两个值一旦在服务端被解错，搜到的就是另一批消息，而签名照样验得过
        var handler = Hits();
        await Client(handler).SearchAsync(Device(), "磁盘 空间!", "80000001", "jenkins");

        var query = handler.Single().Query;
        Assert.Equal("?q=%E7%A3%81%E7%9B%98%20%E7%A9%BA%E9%97%B4%21&channel_no=80000001"
            + "&sender_id=jenkins&since=0&limit=50", query);
    }

    [Theory]
    [InlineData("a!b'c(d)e*f~g")]
    [InlineData("100% 使用率")]
    [InlineData("a/b?c#d&e=f")]
    [InlineData("[]{}<>|\\;:\"',.`")]
    [InlineData("😀 emoji 告警")]
    public async Task WhateverTheUserTypesIsSignedAsTheBytesThatLeave(string term)
    {
        // 这一条是实测钉出来的：Uri 在 query 段上是原样透传的，EscapeDataString 的结果
        // 既不会被还原成原文（%21 → !），也不会被二次转义（% → %25）。搜索词是用户打的，
        // 这两件事任何一件发生，签名就覆盖在另一串字节上，服务端必然 401
        var handler = Hits();
        var identity = Device();
        await Client(handler).SearchAsync(identity, term, Channel, "jen kins");

        var request = handler.Single();
        Assert.Equal("?" + DeviceClient.SearchQuery(term, Channel, "jen kins", null, 0L, 50), request.Query);
        AssertSigned(request, identity);
    }

    [Fact]
    public async Task AReorderedQueryIsNotTheOneThatWasSigned()
    {
        // 与 §8.2 第 7 条同源：换序就是另一个值，服务端复原出来的串对不上签名
        var handler = Hits();
        var identity = Device();
        await Client(handler).SearchAsync(identity, "CPU usage", Channel);

        var request = handler.Single();
        Assert.True(Verifies(request, identity,
            "q=CPU%20usage&channel_no=" + Channel + "&since=0&limit=50"));
        Assert.False(Verifies(request, identity,
            "limit=50&since=0&channel_no=" + Channel + "&q=CPU%20usage"));
    }

    [Fact]
    public void TheVectorsQueryShapeIsTheOneThisClientProducesForTheSameInput()
    {
        // 向量里那条带的是空 sender_id（"传了就占一位"），本客户端的口径是"没传就整段省略"，
        // 所以这里核对的是"去掉那一段之后逐字节相同"——顺序、编码、since/limit 都在这一串里比
        var ours = DeviceClient.SearchQuery("CPU usage", Channel, null, null, 0L, 50);
        Assert.Equal(VectorQuery.Replace("sender_id=&", ""), ours);
    }

    [Fact]
    public async Task ABlankTermAsksTheServerForNothing()
    {
        // 服务端对空 q 回 400 invalid_param，而搜索框在用户还没打完字时就该什么都不发
        var handler = Hits();

        var page = await Client(handler).SearchAsync(Device(), "   ");

        Assert.Empty(page.Hits);
        Assert.False(page.HasMore);
        Assert.Empty(handler.Requests);
    }

    // ---- 命中页 ----------------------------------------------------------------------------------

    [Fact]
    public async Task TheHitsComeOutOfTheSealedBoxWithTheServerFieldNames()
    {
        var handler = Hits(SearchBody());
        var identity = Device();

        var page = await Client(handler).SearchAsync(identity, "CPU", Channel, "jenkins",
            since: 1765500000000L, limit: 2);

        Assert.True(page.HasMore);
        Assert.Equal(2, page.Hits.Count);
        var first = page.Hits[0];
        Assert.Equal("01J8M4ZCQW", first.Id);
        Assert.Equal(18483L, first.Seq);
        Assert.Equal(Channel, first.ChannelNo);
        Assert.Equal("jenkins", first.SenderId);
        Assert.Equal("Jenkins CI", first.SenderName);
        Assert.Equal("alert", first.MsgType);
        Assert.Equal("构建失败", first.Title);
        // 片段是服务端裁好的 120 字窗口，客户端不再二次裁
        Assert.Equal("…节点 disk01 的 CPU 使用率连续五分钟超阈值", first.Snippet);
        Assert.Equal("warn", first.Level);
        Assert.Equal("ready", first.MediaStatus);
        Assert.Equal(1765500000000L, first.CreatedAt);
        // 第二条没有 title，缺列一律按缺省走而不是整页失败
        Assert.Null(page.Hits[1].Title);
        Assert.Equal("", page.Hits[1].Snippet);
        Assert.Null(page.Hits[1].MediaStatus);
        AssertSigned(handler.Single(), identity);
    }

    [Fact]
    public async Task AHitWithoutAnIdIsNotListedBecauseNothingCanDedupeIt()
    {
        // 去重与"点结果跳转会话"都以 id 为锚；一条没有 id 的命中只会变成屏幕上重复的两行
        var handler = Hits("{\"results\":[{\"seq\":7,\"channel_no\":\"83271504\","
            + "\"sender_id\":\"jenkins\",\"snippet\":\"有片段没 id\",\"created_at\":1},"
            + "{\"id\":\"01J8M4ZCR0\",\"seq\":8,\"channel_no\":\"83271504\","
            + "\"sender_id\":\"jenkins\",\"created_at\":2}],\"has_more\":false}");

        var page = await Client(handler).SearchAsync(Device(), "CPU");

        var hit = Assert.Single(page.Hits);
        Assert.Equal("01J8M4ZCR0", hit.Id);
        Assert.Equal("text", hit.MsgType);
    }

    [Fact]
    public async Task AnEmptyResultsArrayIsANoMatchNotAnError()
    {
        var handler = Hits("{\"results\":[],\"has_more\":false}");

        var page = await Client(handler).SearchAsync(Device(), "没有这个词");

        Assert.Empty(page.Hits);
        Assert.False(page.HasMore);
    }

    [Fact]
    public async Task APlainHtmlAnswerSaysTheReplyIsNotASealedBox()
    {
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.OK, "<html>login</html>", "text/html"));

        var e = await Assert.ThrowsAsync<PushBoxTransportException>(
            () => Client(handler).SearchAsync(Device(), "CPU"));

        Assert.Contains("密封盒", e.Message);
    }

    [Fact]
    public async Task ATooLongTermComesBackAsTheServersOwnSentence()
    {
        // §5.1：q 超 100 字符是 400 invalid_param，这句要原样进搜索结果的状态条
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.BadRequest,
            Support.ErrorBody("invalid_param", "q 最长 100 字符")));

        var e = await Assert.ThrowsAsync<PushBoxApiException>(
            () => Client(handler).SearchAsync(Device(), new string('a', 101)));

        Assert.Equal("invalid_param", e.Code);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private static DeviceIdentity Device() => new(
        "dev_7f3a", DeviceEncPriv, DeviceSignSeed, "pbk_1a2b3c4d5e6f", "grp_001", BaseUrl, null, 0L);

    private static DeviceClient Client(FakeHandler handler) => new(new HttpClient(handler));

    /// <summary>一条正常的搜索回答：正文照 §5.2 封成密封盒，用的密钥是 vectors.json 那台设备。</summary>
    private static FakeHandler Hits(string body = "{\"results\":[],\"has_more\":false}") =>
        new(_ => Support.Reply(HttpStatusCode.OK, Support.SealRestResponse(body)));

    private static string SearchBody() =>
        """
        {"results":[{"seq":18483,"id":"01J8M4ZCQW","channel_no":"83271504","sender_id":"jenkins",
        "sender_name":"Jenkins CI","type":"alert","title":"构建失败",
        "snippet":"…节点 disk01 的 CPU 使用率连续五分钟超阈值","level":"warn","media_status":"ready",
        "created_at":1765500000000},
        {"seq":18482,"id":"01J8M4ZCR0","channel_no":"83271504","sender_id":"jenkins",
        "sender_name":"Jenkins CI","type":"text","snippet":"","level":"info","created_at":1765499999000}],
        "has_more":true}
        """;

    /// <summary>§4.4 的核对方式：拿发出去的原文重拼 canonical 再验签，query 用真正上路的那串。</summary>
    private static void AssertSigned(RecordedRequest request, DeviceIdentity identity) =>
        Assert.True(Verifies(request, identity, request.Query.Length > 1 ? request.Query[1..] : null));

    private static bool Verifies(RecordedRequest request, DeviceIdentity identity, string? query)
    {
        var canonical = RestCanonical.Build(identity.DeviceId, request.Method, request.Path, query,
            request.Body, long.Parse(request.Header("X-PB-Ts")), B64Url.Decode(request.Header("X-PB-Nonce")));
        return Ed25519Sign.Verify(Ed25519Sign.DerivePublic(identity.SignPrivSeed), canonical,
            B64Url.Decode(request.Header("X-PB-Sig")));
    }
}
