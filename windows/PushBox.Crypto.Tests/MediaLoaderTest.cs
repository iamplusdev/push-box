using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Media;
using PushBox.Windows.Net;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W10 的验收面：图片先问本地缓存、问到就不碰网络（所以断网时看图照常），
/// 问不到才发签名请求；服务端的 404 按 hint 里的槽位状态分成"还在等 / 已经没了 / 可以重试"，
/// 而不是塌成一个破图图标。
/// </summary>
public sealed class MediaLoaderTest : IDisposable
{
    private const string BaseUrl = "http://127.0.0.1:8081";
    private const string AvatarToken = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static readonly byte[] DeviceEncPriv =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_enc_priv"));

    private static readonly byte[] DeviceSignSeed =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_sign_seed"));

    private readonly string _dir;

    public MediaLoaderTest()
    {
        _dir = Path.Combine(Path.GetTempPath(), "pushbox-w10-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
    }

    // ---- 验收①：断网时图片来自缓存 --------------------------------------------------------------

    [Fact]
    public async Task ACachedImageNeverTouchesTheNetworkAgain()
    {
        var handler = Images();
        var loader = Loader(handler);

        var first = await loader.ImageAsync(Device(), "m_83", 0, thumb: true);
        var second = await loader.ImageAsync(Device(), "m_83", 0, thumb: true);

        Assert.Equal(MediaState.Ready, first.State);
        Assert.Equal(Png(32), first.Bytes);
        // 一个图位在屏幕上被滚过去滚回来十次，也只该有一次签名请求
        Assert.Single(handler.Requests);
        Assert.Equal(second.Bytes, first.Bytes);
    }

    [Fact]
    public async Task ANewProcessReadsTheSameImageFromDiskWithTheServerDown()
    {
        // 这就是 W10 验收那句"断网时图片来自缓存"：缓存命中时连请求都不发，所以服务端是死是活无所谓
        var warm = Loader(Images(), _dir);
        await warm.ImageAsync(Device(), "m_83", 0, thumb: true);

        var offline = Loader(new ThrowingHandler(new HttpRequestException("no route to host")), _dir);
        var shown = await offline.ImageAsync(Device(), "m_83", 0, thumb: true);

        Assert.Equal(MediaState.Ready, shown.State);
        Assert.Equal(Png(32), shown.Bytes);
    }

    [Fact]
    public async Task ACacheMissWhileTheNetworkIsDownIsADiagnosisNotACrash()
    {
        var loader = Loader(new ThrowingHandler(new HttpRequestException("no route to host")), _dir);

        var shown = await loader.ImageAsync(Device(), "m_99", 0, thumb: true);

        Assert.Equal(MediaState.NetworkError, shown.State);
        Assert.Contains("连不上", shown.Reason);
    }

    [Fact]
    public async Task AThumbAndTheFullImageAreTwoSeparateEntries()
    {
        var handler = Images();
        var loader = Loader(handler);

        await loader.ImageAsync(Device(), "m_83", 0, thumb: true);
        await loader.ImageAsync(Device(), "m_83", 0, thumb: false);
        // 列表滚回这一行：缩略图不再发请求，点开大图仍然是另一次取字节
        await loader.ImageAsync(Device(), "m_83", 0, thumb: true);

        Assert.Equal(2, handler.Requests.Count);
        Assert.Equal(new[] { "?size=thumb", "?size=full" },
            handler.Requests.Select(r => r.Query).ToArray());
    }

    [Fact]
    public async Task ADegradedThumbNeedsNoClientSideSecondTry()
    {
        // §5.2：槽位没有缩略图时服务端直接降级发原图，也是 200。
        // 于是客户端根本分辨不出"这是降级来的"，也绝不该因此再要一次 full
        var handler = new FakeHandler(_ => Reply(Png(64), "image/jpeg"));
        var loader = Loader(handler);

        var shown = await loader.ImageAsync(Device(), "m_83", 0, thumb: true);

        Assert.Equal(MediaState.Ready, shown.State);
        Assert.Equal("image/jpeg", shown.ContentType);
        Assert.Single(handler.Requests);
    }

    // ---- 验收②：404 + 状态映射成对应占位 --------------------------------------------------------

    [Theory]
    [InlineData("pending", MediaState.Pending)]
    [InlineData("failed", MediaState.Failed)]
    [InlineData("deleted", MediaState.Deleted)]
    [InlineData("purged", MediaState.Purged)]
    [InlineData("media", MediaState.Missing)]
    public async Task EachSlotStatusKeepsItsOwnMeaning(string hint, MediaState expected)
    {
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.NotFound,
            Support.ErrorBody("not_found", hint)));
        var loader = Loader(handler);

        var shown = await loader.ImageAsync(Device(), "m_83", 0, thumb: true);

        Assert.Equal(expected, shown.State);
        Assert.False(shown.HasBytes);
        // pending 是"再等等"，别的都是"别再等了"：这一条区分不了，图位就会永远转圈或永远说失效
        Assert.Equal(expected == MediaState.Pending ? null : "图片已失效", shown.Placeholder);
    }

    [Fact]
    public async Task AFourOhOneIsNotReportedAsAnExpiredImage()
    {
        // 设备被吊销/时钟漂了是整片签名接口一起 401，图位这时候该给"重试"，说"图片已失效"是撒谎
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.Unauthorized,
            Support.ErrorBody("unauthorized", "clock_skew")));
        var loader = Loader(handler);

        var shown = await loader.ImageAsync(Device(), "m_83", 0, thumb: true);

        Assert.Equal(MediaState.NetworkError, shown.State);
        Assert.Contains("时间", shown.Placeholder);
    }

    [Fact]
    public async Task AFailureIsNeverWrittenIntoTheCache()
    {
        // 一次网络抖动不能把这个图位永久钉成失败：缓存里没落条目，下一次照样回源
        var handler = new FakeHandler(_ => Support.Reply((HttpStatusCode)503, Support.ErrorBody("server_error")));
        var loader = Loader(handler);

        Assert.Equal(MediaState.NetworkError, (await loader.ImageAsync(Device(), "m_83", 0, true)).State);
        Assert.Equal(MediaState.NetworkError, (await loader.ImageAsync(Device(), "m_83", 0, true)).State);
        Assert.Equal(2, handler.Requests.Count);
    }

    // ---- 头像 ----------------------------------------------------------------------------------

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("/api/v1/avatars/not-a-token")]
    public async Task AFrameWithoutAnAvatarSkipsTheNetwork(string? senderAvatar)
    {
        // §4.5 的转存是异步的，首帧 sender_avatar 通常是 NULL：这是正常时序，不该发请求也不该算失败
        var handler = Images();
        var loader = Loader(handler);

        var shown = await loader.AvatarAsync(Device(), senderAvatar);

        Assert.Equal(MediaState.NoSource, shown.State);
        Assert.Null(shown.Placeholder);
        Assert.Empty(handler.Requests);
    }

    [Fact]
    public async Task AvatarsStayInMemoryBecauseTheServerOverwritesThem()
    {
        // 头像对象按 (频道, 发送者) 覆盖写、token 不变，落盘等于把换掉的头像永久留在这台机器上
        var handler = Images();
        var loader = Loader(handler);

        var first = await loader.AvatarAsync(Device(), "/api/v1/avatars/" + AvatarToken);
        await loader.AvatarAsync(Device(), "/api/v1/avatars/" + AvatarToken);

        Assert.Equal(MediaState.Ready, first.State);
        Assert.Single(handler.Requests);
        // 目录里一个对象都没落下：头像只在内存里待着
        Assert.Empty(Directory.EnumerateFiles(_dir, "*.img"));

        // 换一个新进程（同目录、新内存）就该重新取一次，这样换掉的头像不会一直显示老的
        var restarted = Loader(Images(), _dir);
        Assert.Equal(MediaState.Ready, (await restarted.AvatarAsync(Device(), "/api/v1/avatars/" + AvatarToken)).State);
    }

    // ---- 清理 ----------------------------------------------------------------------------------

    [Fact]
    public async Task EvictingAMessageClearsBothCacheLevels()
    {
        var handler = Images();
        var loader = Loader(handler);
        await loader.ImageAsync(Device(), "m_83", 0, thumb: true);
        await loader.ImageAsync(Device(), "m_83", 0, thumb: true);
        Assert.Single(handler.Requests);
        Assert.NotEmpty(Directory.EnumerateFiles(_dir, "*.img"));

        // W14 的 tombstone 走到这里：消息都没了，本地图位不能还能翻出来看
        loader.EvictMessage("m_83");

        // 只删文件不算清完：内存那一份还在的话，这一格照样显示着已经删掉的那张图
        Assert.Empty(Directory.EnumerateFiles(_dir, "*.img"));
        await loader.ImageAsync(Device(), "m_83", 0, thumb: true);
        Assert.Equal(2, handler.Requests.Count);
    }

    [Fact]
    public async Task TheDiskLayerStopsAtItsCapacity()
    {
        var dir = Path.Combine(_dir, "capped");
        // 内存这一层关掉（容量 0），逼每一次读取都走磁盘，这样测的是磁盘的 LRU 而不是内存命中
        var cache = new MediaCache(dir, diskCapacityBytes: 120, memoryCapacityBytes: 0);
        foreach (var idx in new[] { 0, 1, 2, 3 })
        {
            await Loader(Images(), dir, cache).ImageAsync(Device(), "m_83", idx, thumb: true);
        }

        Assert.True(cache.DiskBytes <= 120, $"磁盘缓存没有守住上限：{cache.DiskBytes} 字节");
        Assert.NotNull(cache.Get(MediaLoader.ImageKey("m_83", 3, true)));
        Assert.Null(cache.Get(MediaLoader.ImageKey("m_83", 0, true)));
    }

    [Fact]
    public async Task BytesComingOffDiskStillKnowTheirMimeType()
    {
        // 缓存里只有字节，Content-Type 是靠魔数认回来的（§4.5 入库前已经白名单 + 魔数复验）
        var loader = Loader(Images(), _dir);
        var online = await loader.ImageAsync(Device(), "m_83", 0, thumb: true);

        var restarted = Loader(new ThrowingHandler(new HttpRequestException("offline")), _dir);
        var offline = await restarted.ImageAsync(Device(), "m_83", 0, thumb: true);

        Assert.Equal("image/png", online.ContentType);
        Assert.Equal("image/png", offline.ContentType);
    }

    // ---- fixtures -----------------------------------------------------------------------------

    public void Dispose()
    {
        try
        {
            Directory.Delete(_dir, true);
        }
        catch (IOException)
        {
            // 用例结论已经拿到了，留着这个临时目录比误删别人的文件好
        }
    }

    private static DeviceIdentity Device() => new(
        "dev_7f3a", DeviceEncPriv, DeviceSignSeed, "pbk_1a2b3c4d5e6f", "grp_001", BaseUrl, null, 0L);

    /// <summary>一条正常的图片回答：200 + PNG 魔数，MIME 与字节都对得上。</summary>
    private static FakeHandler Images() => new(_ => Reply(Png(32), "image/png"));

    private static HttpResponseMessage Reply(byte[] bytes, string contentType)
    {
        var message = new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new ByteArrayContent(bytes)
        };
        message.Content.Headers.ContentType = new MediaTypeHeaderValue(contentType);
        message.Headers.CacheControl = new CacheControlHeaderValue { NoStore = true };
        return message;
    }

    /// <summary>带 PNG 文件头的假图：测试只关心字节原样进出，不关心里面能不能解出像素。</summary>
    private static byte[] Png(int length) =>
        new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A }
            .Concat(Enumerable.Range(0, length).Select(i => (byte)i)).ToArray();

    private MediaLoader Loader(FakeHandler handler, string cacheDir) =>
        Loader(handler, cacheDir, new MediaCache(cacheDir));

    private MediaLoader Loader(ThrowingHandler handler, string cacheDir) =>
        new(new DeviceClient(new HttpClient(handler)), new MediaCache(cacheDir));

    private MediaLoader Loader(HttpMessageHandler handler, string cacheDir, MediaCache cache) =>
        new(new DeviceClient(new HttpClient(handler)), cache);

    /// <summary>用例开头那个默认的 loader：缓存目录用测试自己的临时目录。</summary>
    private MediaLoader Loader(FakeHandler handler) => Loader(handler, _dir);
}
