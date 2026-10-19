using System;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Authentication;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using PushBox.Windows.Crypto;
using PushBox.Windows.Data;
using PushBox.Windows.Net;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W5: the two open device endpoints. Every §5.1 error body has to come out as one readable sentence,
/// and the success path has to open the same sealed box the Java server writes.
/// </summary>
public class DeviceClientTest
{
    private const string BaseUrl = "https://push.test";

    private static DeviceIdentity VectorDevice(string baseUrl = BaseUrl) => new(
        "",
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_enc_priv")),
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_sign_seed")),
        "", "", baseUrl, null, 0L);

    private static DeviceClient Client(Func<RecordedRequest, HttpResponseMessage> responder) =>
        new(new HttpClient(new FakeHandler(responder)));

    /// <summary>用例自己要数请求条数时先建好 handler，再套这一层：省掉一处 <c>new DeviceClient(new HttpClient(...))</c>。</summary>
    private static DeviceClient Client(FakeHandler handler) => new(new HttpClient(handler));

    [Fact]
    public async Task RegisterRequestMatchesTheSpecFields()
    {
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.OK, Support.SealRestResponse(
            Support.Str(Support.Vectors.GetProperty("sealed_box"), "plaintext_json"))));
        var pending = VectorDevice();

        await new DeviceClient(new HttpClient(handler)).RegisterAsync(pending, "Office PC");

        var request = handler.Single();
        Assert.Equal(HttpMethod.Post.Method, request.Method);
        Assert.Equal("/api/v1/devices/register", request.Path);
        var body = request.BodyJson();
        Assert.Equal(pending.EncPubB64, Support.Str(body, "enc_pubkey"));
        Assert.Equal(pending.SignPubB64, Support.Str(body, "sign_pubkey"));
        Assert.Equal("Office PC", Support.Str(body, "name"));
        Assert.Equal("windows", Support.Str(body, "platform"));
        Assert.False(request.HasBodyField("group_secret"));
        Assert.Equal("application/json", request.Message.Content!.Headers.ContentType!.MediaType);
        // 32 字节的真实公钥，不是占位串
        Assert.Equal(32, B64Url.Decode(Support.Str(body, "enc_pubkey")).Length);
        Assert.Equal(32, B64Url.Decode(Support.Str(body, "sign_pubkey")).Length);
    }

    [Fact]
    public async Task RegisterSendsNoIdentityHeaders()
    {
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.OK, Support.SealRestResponse(
            Support.Str(Support.Vectors.GetProperty("sealed_box"), "plaintext_json"))));

        await new DeviceClient(new HttpClient(handler)).RegisterAsync(VectorDevice(), "Office PC");

        // 此刻服务端还不认识这台设备，任何签名头只会换来 401（PushBoxApi.kt 同样只发 body）
        var request = handler.Single();
        Assert.False(request.HasHeader("Authorization"));
        Assert.False(request.HasHeader("X-PB-Device"));
        Assert.False(request.HasHeader("X-PB-Ts"));
        Assert.False(request.HasHeader("X-PB-Nonce"));
        Assert.False(request.HasHeader("X-PB-Sig"));
        // §5.3：凭据绝不进 URL
        Assert.DoesNotContain("token", request.Message.RequestUri!.ToString());
    }

    [Fact]
    public async Task RegisterOpensTheJavaSealedVectorResponse()
    {
        var envelope = Support.Str(Support.Vectors.GetProperty("sealed_box"), "envelope_json");
        var client = Client(_ => Support.Reply(HttpStatusCode.OK, envelope));

        var enrolled = await client.RegisterAsync(VectorDevice(), "Office PC");

        Assert.Equal("dev_7f3a", enrolled.DeviceId);
        Assert.Equal("grp_001", enrolled.GroupId);
        Assert.Equal("pbk_1a2b3c4d5e6f", enrolled.Token);
        Assert.Equal("9f3c77ad1e58b0c2Xk7mQw", enrolled.RecoveryCode);
        // §5.3 TOFU 的锚点：W6 建连时拿 hello.server_pub_fp 跟它比
        Assert.Equal(Support.Str(Support.Vectors.GetProperty("outputs"), "server_pub_fp"), enrolled.ServerPubFp);
    }

    [Fact]
    public void SealedTestHelperReproducesTheJavaEnvelope()
    {
        // 测试自建信封的实现必须和 Java 侧逐字节一致，否则下面所有 join 用例都在自证
        var box = Support.Vectors.GetProperty("sealed_box");
        var java = JsonDocument.Parse(Support.Str(box, "envelope_json")).RootElement;
        var mine = JsonDocument.Parse(Support.SealRestResponse(Support.Str(box, "plaintext_json"))).RootElement;
        Assert.Equal(Support.Str(java, "eph_pub"), Support.Str(mine, "eph_pub"));
        Assert.Equal(Support.Str(java, "salt"), Support.Str(mine, "salt"));
        Assert.Equal(Support.Str(java, "data"), Support.Str(mine, "data"));
        Assert.Equal(java.GetProperty("n").GetInt64(), mine.GetProperty("n").GetInt64());
    }

    [Fact]
    public async Task JoinUsesJoinPathAndAnswersWithoutRecoveryCode()
    {
        var payload = "{\"device_id\":\"dev_9\",\"group_id\":\"grp_001\",\"token\":\"tk_9\","
            + "\"recovery_code\":null,\"server_pub_fp\":\"e76604b23bcf585d\"}";
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.OK, Support.SealRestResponse(payload)));
        var pending = VectorDevice();

        var enrolled = await new DeviceClient(new HttpClient(handler))
            .JoinAsync(pending, "9f3c77ad1e58b0c2Xk7mQw", "Office PC");

        Assert.Equal("/api/v1/devices/join", handler.Single().Path);
        Assert.Equal("9f3c77ad1e58b0c2Xk7mQw", Support.Str(handler.Single().BodyJson(), "group_secret"));
        // 加入者拿不到恢复码明文（§4.2）
        Assert.Null(enrolled.RecoveryCode);
        Assert.Equal("dev_9", enrolled.DeviceId);
    }

    [Fact]
    public async Task SixthDeviceIsRejectedWithReadableText()
    {
        var client = Client(_ => Support.Reply(HttpStatusCode.Conflict, Support.ErrorBody("device_limit_reached")));

        var e = await Assert.ThrowsAsync<PushBoxApiException>(
            () => client.RegisterAsync(VectorDevice(), "Office PC"));

        Assert.Equal("device_limit_reached", e.Code);
        Assert.Contains("5 台", e.Message);
        Assert.Contains("吊销", e.Message);
    }

    [Fact]
    public async Task WrongRecoveryCodeTellsYouToCopyAgain()
    {
        var client = Client(_ => Support.Reply(HttpStatusCode.Unauthorized, Support.ErrorBody("invalid_credential")));

        var e = await Assert.ThrowsAsync<PushBoxApiException>(
            () => client.JoinAsync(VectorDevice(), "9f3c77ad1e58b0c2Xk7mQw", "Office PC"));

        Assert.Contains("恢复码", e.Message);
    }

    [Fact]
    public async Task RateLimitedReportsRetryAfterSeconds()
    {
        var client = Client(_ => Support.Reply(
            (HttpStatusCode)429, Support.ErrorBody("rate_limited", "60"), retryAfter: "60"));

        var e = await Assert.ThrowsAsync<PushBoxApiException>(
            () => client.RegisterAsync(VectorDevice(), "Office PC"));

        Assert.Equal(60L, e.RetryAfterSeconds);
        Assert.Contains("60 秒", e.Message);
    }

    [Fact]
    public async Task ClockSkewIsFlaggedAsSystemTimeProblem()
    {
        // W3b 验收④：ts 出 ±300s 窗口是整片签名接口一起 401，提示必须指向系统时间
        var client = Client(_ => Support.Reply(HttpStatusCode.Unauthorized,
            Support.ErrorBody("unauthorized", "clock_skew")));

        var e = await Assert.ThrowsAsync<PushBoxApiException>(
            () => client.RegisterAsync(VectorDevice(), "Office PC"));

        Assert.True(e.IsClockSkew);
        Assert.Contains("时间", e.Message);
    }

    [Fact]
    public async Task ProxyHtmlPageStillNamesTheStatus()
    {
        var client = Client(_ => new HttpResponseMessage(HttpStatusCode.BadGateway)
        {
            Content = new StringContent("<html>502 Bad Gateway</html>", Encoding.UTF8, "text/html")
        });

        var e = await Assert.ThrowsAsync<PushBoxApiException>(
            () => client.RegisterAsync(VectorDevice(), "Office PC"));

        Assert.Equal("server_error", e.Code);
        Assert.DoesNotContain("502 Bad Gateway</html>", e.Message);
        Assert.Contains("服务端", e.Message);
    }

    [Fact]
    public async Task PlainTextSuccessSaysResponseIsNotASealedBox()
    {
        var client = Client(_ => Support.Reply(HttpStatusCode.OK, "<html>login</html>", "text/html"));

        var e = await Assert.ThrowsAsync<PushBoxTransportException>(
            () => client.RegisterAsync(VectorDevice(), "Office PC"));

        Assert.Contains("密封盒", e.Message);
    }

    [Fact]
    public async Task MissingFieldInSealedPayloadIsReadable()
    {
        var client = Client(_ => Support.Reply(HttpStatusCode.OK, Support.SealRestResponse(
            "{\"device_id\":\"dev_9\",\"group_id\":\"grp_001\",\"server_pub_fp\":\"fp\"}")));

        var e = await Assert.ThrowsAsync<PushBoxApiException>(
            () => client.RegisterAsync(VectorDevice(), "Office PC"));

        Assert.Equal("invalid_response", e.Code);
        Assert.Contains("token", e.Message);
    }

    [Fact]
    public async Task UnreachableHostIsReadable()
    {
        var client = new DeviceClient(new HttpClient(
            new ThrowingHandler(new HttpRequestException("name could not be resolved"))));

        var e = await Assert.ThrowsAsync<PushBoxTransportException>(
            () => client.RegisterAsync(VectorDevice(), "Office PC"));

        Assert.Contains("连不上", e.Message);
        Assert.Contains("/api/v1/devices/register", e.Message);
    }

    [Fact]
    public async Task TlsFailureMentionsTheTrustedRootStore()
    {
        var client = new DeviceClient(new HttpClient(new ThrowingHandler(new HttpRequestException("TLS error",
            new AuthenticationException("The cert issuer is not trusted")))));

        var e = await Assert.ThrowsAsync<PushBoxTransportException>(
            () => client.RegisterAsync(VectorDevice(), "Office PC"));

        Assert.Contains("证书", e.Message);
    }

    [Fact]
    public async Task TimeoutIsReportedAsNoAnswer()
    {
        var client = new DeviceClient(new HttpClient(new HangingHandler()), TimeSpan.FromMilliseconds(80));

        var e = await Assert.ThrowsAsync<PushBoxTransportException>(
            () => client.RegisterAsync(VectorDevice(), "Office PC"));

        Assert.Contains("毫秒内没有响应", e.Message);
    }

    [Theory]
    [InlineData("push.test", "https://push.test/api/v1/devices/register")]
    [InlineData("https://push.test/", "https://push.test/api/v1/devices/register")]
    [InlineData("  http://100.100.100.100:8081  ", "http://100.100.100.100:8081/api/v1/devices/register")]
    [InlineData("https://push.test/pushbox/", "https://push.test/pushbox/api/v1/devices/register")]
    public void BaseUrlIsNormalised(string input, string expected)
    {
        Assert.Equal(expected, DeviceClient.BuildUrl(input, DeviceClient.RegisterPath));
    }

    [Theory]
    [InlineData("")]
    [InlineData("not a url")]
    [InlineData("ftp://push.test")]
    [InlineData("https://push.test?token=secret")]
    [InlineData("https://user:pass@push.test")]
    public void BadBaseUrlIsRefusedBeforeAnyNetworkCall(string input)
    {
        Assert.Throws<ArgumentException>(() => DeviceClient.NormalizeBaseUrl(input));
    }

    [Fact]
    public void IdentityUrlKeepsTheSameRootAsTheCall()
    {
        // §5.2 canonical 把 path 算进签名，所以身份文件里的根地址必须和这里发出去的一字不差
        var normalized = DeviceClient.NormalizeBaseUrl("https://push.test/");
        Assert.Equal("https://push.test", normalized);
        Assert.Equal("https://push.test/api/v1/devices/join", DeviceClient.BuildUrl("https://push.test/", DeviceClient.JoinPath));
    }

    // ---- §5.2/D13 的两发 DELETE ----------------------------------------------------------------------

    [Fact]
    public async Task DeletingOneMessageIsADeleteOfItsPathWithNoQueryAndNoBody()
    {
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.OK,
            Support.SealRestResponse("{\"ok\":true,\"id\":\"01JTESTMESSAGE000000000000\",\"deleted_at\":1}")));

        await Client(handler).DeleteMessageAsync(VectorDevice(), "01JTESTMESSAGE000000000000");

        var request = handler.Single();
        Assert.Equal(HttpMethod.Delete.Method, request.Method);
        Assert.Equal("/api/v1/messages/01JTESTMESSAGE000000000000", request.Path);
        // 单条删除把 id 放在路径里，所以 canonical 的第四行是空行、第六行是 sha256("")：
        // deploy/probe 的 ⑪ 已经拿真 HTTP 把这两个空位核过，向量文件里没有 DELETE 这一支
        Assert.Empty(request.Query);
        Assert.Empty(request.Body);
    }

    [Theory]
    [InlineData("83271504", "grafana", "channel_no=83271504&sender_id=grafana")]
    [InlineData("83271504", null, "channel_no=83271504")]
    [InlineData("83271504", "  ", "channel_no=83271504")]
    // 省略 sender_id 在服务端那一侧就是"清整个频道"（scope 帧的 sender_id 为 null），所以这里不能替它编一个空串
    [InlineData("83 27", "jen kins", "channel_no=83%2027&sender_id=jen%20kins")]
    public void TheScopedClearOrdersAndEscapesItsQuery(string channel, string? sender, string expected)
    {
        Assert.Equal(expected, DeviceClient.ClearQuery(channel, sender));
    }

    [Fact]
    public void ClearingWithoutAChannelIsRefusedBeforeTheNetwork()
    {
        // 服务端对空 channel_no 回 400；在这里先拦住，省下一趟只会换来一句没人看得懂的报错
        Assert.Throws<ArgumentException>(() => DeviceClient.ClearQuery("  ", "grafana"));
    }

    [Fact]
    public async Task AScopedClearIsOneRequestAndHandsBackTheDeletedCount()
    {
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.OK, Support.SealRestResponse(
            "{\"ok\":true,\"channel_no\":\"83271504\",\"sender_id\":\"grafana\",\"deleted\":412,"
            + "\"deleted_at\":1765500009000}")));

        Assert.Equal(412, await Client(handler).ClearMessagesAsync(VectorDevice(), "83271504", "grafana"));

        var request = handler.Single();
        Assert.Equal(HttpMethod.Delete.Method, request.Method);
        Assert.Equal("/api/v1/messages", request.Path);
        // 逐条删在这里是错的：可能上千条，既打爆 SQL 往返也让别的端收到上千帧
        Assert.Equal("?channel_no=83271504&sender_id=grafana", request.Query);
        Assert.Empty(request.Body);
    }

    [Fact]
    public async Task AnIdempotentAnswerOfZeroIsStillASuccess()
    {
        // 会话本来就空或早被清空：服务端回 deleted=0 而不是 404，客户端不该把它读成"没删成"
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.OK,
            Support.SealRestResponse("{\"ok\":true,\"deleted\":0}")));

        Assert.Equal(0, await Client(handler).ClearMessagesAsync(VectorDevice(), "83271504", "grafana"));
    }

    [Fact]
    public async Task AClearThatNamesNoSenderClearsTheWholeChannel()
    {
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.OK,
            Support.SealRestResponse("{\"ok\":true,\"deleted\":9}")));

        Assert.Equal(9, await Client(handler).ClearMessagesAsync(VectorDevice(), "83271504"));
        // 只有 channel_no：服务端据此广播一条 sender_id 为 null 的 scope 帧，别的端把那个频道下所有会话一起清掉
        Assert.Equal("?channel_no=83271504", handler.Single().Query);
    }
}
