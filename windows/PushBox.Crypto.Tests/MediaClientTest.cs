using System;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Threading.Tasks;
using PushBox.Windows.Crypto;
using PushBox.Windows.Data;
using PushBox.Windows.Net;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W10 的协议面：§5.2 的签名第一次用在<b>回答不是 JSON</b>的接口上。
/// 这两条接口把密封盒换成了图片字节，所以两个方向都容易做错——请求侧少签一个 query 就整片 401，
/// 回答侧若还照着密封盒去解，一张 jpg 会被说成"这个地址指向了别的服务"。
/// </summary>
public class MediaClientTest
{
    private const string BaseUrl = "http://127.0.0.1:8081";
    private const string AvatarToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private const string AvatarTokenUpper = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

    private static readonly byte[] DeviceEncPriv =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_enc_priv"));

    private static readonly byte[] DeviceSignSeed =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_sign_seed"));

    /// <summary>8 字节 PNG 签名后面垫一点内容，测试关心的是字节原样回来，不是它能不能解码。</summary>
    private static readonly byte[] PngBytes =
        new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }.Concat(new byte[] { 1, 2, 3, 4 }).ToArray();

    [Fact]
    public async Task AThumbRequestSignsTheSizeQueryItActuallySent()
    {
        var handler = Image();
        var identity = Device();

        await Client(handler).MediaAsync(identity, "m_83", 0, thumb: true);

        var request = handler.Single();
        Assert.Equal("GET", request.Method);
        Assert.Equal("/api/v1/media/m_83/0", request.Path);
        // §5.2 canonical 第四行就是这串原始 query：服务端拿 getQueryString() 复原，多一个字符都是坏签名
        Assert.Equal("?size=thumb", request.Query);
        Assert.Equal("Bearer pbk_1a2b3c4d5e6f", request.Header("Authorization"));
        Assert.Equal("dev_7f3a", request.Header("X-PB-Device"));
        AssertSigned(request, identity);
    }

    [Fact]
    public async Task TheSignedQueryIsTheOnlySizeThatCanBeAskedFor()
    {
        // 把 size 改成别值再验一次：签名算的是发出去那串，改一个字就验不过（探针 ⑮ 的 400 也是这个口径）
        var handler = Image();
        var identity = Device();
        await Client(handler).MediaAsync(identity, "m_83", 1, thumb: false);

        var request = handler.Single();
        Assert.Equal("?size=full", request.Query);
        Assert.True(Verifies(request, identity, "size=full"));
        Assert.False(Verifies(request, identity, "size=medium"));
    }

    [Fact]
    public async Task ImageBytesComeBackVerbatimWithoutBeingTreatedAsASealedBox()
    {
        // 这是 W10 最容易误判的一条：非 JSON 的成功回答在别的接口上等于"地址指错了服务"，
        // 在这里是常态，所以 MediaAsync 不能走 OpenSealed
        var handler = Image();

        var fetched = await Client(handler).MediaAsync(Device(), "m_83", 0, thumb: true);

        Assert.Equal(PngBytes, fetched.Bytes);
        Assert.Equal("image/png", fetched.ContentType);
    }

    [Theory]
    [InlineData("pending")]
    [InlineData("failed")]
    [InlineData("deleted")]
    [InlineData("purged")]
    [InlineData("media")]
    public async Task ASlotStateSurvivesTheErrorBodySoTheUiCanTellThemApart(string hint)
    {
        // MediaController 对没到 ready 的槽位回 404 + hint=该状态，客户端必须原样拿到，
        // 否则"图还在抓"和"图已真删"会塌成同一个占位
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.NotFound,
            Support.ErrorBody("not_found", hint)));

        var e = await Assert.ThrowsAsync<PushBoxApiException>(
            () => Client(handler).MediaAsync(Device(), "m_83", 0, thumb: true));

        Assert.Equal(HttpStatusCode.NotFound, e.Status);
        Assert.Equal("not_found", e.Code);
        Assert.Equal(hint, e.Hint);
    }

    [Fact]
    public async Task AnAvatarGetSignsAnEmptyQueryAgainstTheDownlinkPath()
    {
        var handler = Image();
        var identity = Device();

        await Client(handler).AvatarAsync(identity, "/api/v1/avatars/" + AvatarToken);

        var request = handler.Single();
        Assert.Equal("/api/v1/avatars/" + AvatarToken, request.Path);
        // 头像接口没有查询参数：canonical 的第四行是空行，签名时必须传 null 而不是拼一个空串进去
        Assert.Equal("", request.Query);
        AssertSigned(request, identity);
    }

    [Theory]
    [InlineData("/api/v1/avatars/" + AvatarToken, AvatarToken)]
    [InlineData(AvatarToken, AvatarToken)]
    [InlineData("/api/v1/avatars/" + AvatarToken + "/", AvatarToken)]
    [InlineData(null, null)]
    [InlineData("", null)]
    [InlineData("/api/v1/avatars/dev_7f3a", null)]
    [InlineData("/api/v1/avatars/" + AvatarTokenUpper, null)]
    public void OnlyASixtyFourHexTokenIsAnAvatar(string? senderAvatar, string? expected)
    {
        // 下发的 sender_avatar 只有 NULL 和这条路径两种形态；服务端对不成形的 token 也是直接 404，
        // 所以宁可在这里判成"没有头像"，也不发一个注定失败的签名请求
        Assert.Equal(expected, DeviceClient.AvatarTokenOf(senderAvatar));
    }

    [Fact]
    public async Task AMissingServerAnswerStillNamesTheUrlInsteadOfADecryptFailure()
    {
        var handler = new FakeHandler(_ => new HttpResponseMessage(HttpStatusCode.BadGateway)
        {
            Content = new StringContent("<html>502 Bad Gateway</html>", Encoding.UTF8, "text/html")
        });

        var e = await Assert.ThrowsAsync<PushBoxApiException>(
            () => Client(handler).MediaAsync(Device(), "m_83", 0, thumb: true));

        Assert.Equal("server_error", e.Code);
        Assert.DoesNotContain("密封盒", e.Message);
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private static DeviceIdentity Device() => new(
        "dev_7f3a", DeviceEncPriv, DeviceSignSeed, "pbk_1a2b3c4d5e6f", "grp_001", BaseUrl, null, 0L);

    private static DeviceClient Client(FakeHandler handler) => new(new HttpClient(handler));

    /// <summary>一条正常的图片回答：200 + 原始字节 + 实测 MIME。</summary>
    private static FakeHandler Image() => new(_ => Reply(PngBytes, "image/png"));

    private static HttpResponseMessage Reply(byte[] bytes, string contentType)
    {
        var message = new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new ByteArrayContent(bytes)
        };
        // §4.5：服务端代理回字节时带的是入库实测的 MIME，另有一行 Cache-Control: no-store
        message.Content.Headers.ContentType = new MediaTypeHeaderValue(contentType);
        message.Headers.CacheControl = new CacheControlHeaderValue { NoStore = true };
        return message;
    }

    /// <summary>§4.4 的核对方式：拿发出去的原文（method/path/query/body/ts/nonce）重拼 canonical 再验签。</summary>
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
