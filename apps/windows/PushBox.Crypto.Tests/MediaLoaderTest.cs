using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading;
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
    private const string OtherToken = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    /// <summary>节流的时间起点由用例给，断言才不受跑用例的那台机器时钟影响。</summary>
    private static readonly DateTimeOffset Stamp = new(2026, 10, 26, 9, 0, 0, TimeSpan.Zero);

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

    [Fact]
    public async Task AFrameCarryingTheAvatarDropsTheCachedBytesSoTheNextLookRefetches()
    {
        // 服务端换头像不换 token，本机能拿到的信号只有"又来了这个人一条带 sender_avatar 的帧"。
        // 那一帧到引擎时作废缓存，画着的格子重画才会真的去取新图，而不是又拿到旧字节
        var handler = Images();
        var loader = Loader(handler);
        var avatar = "/api/v1/avatars/" + AvatarToken;
        await loader.AvatarAsync(Device(), avatar);
        await loader.AvatarAsync(Device(), avatar);
        Assert.Single(handler.Requests);

        var invalidated = new List<string>();
        loader.AvatarInvalidated += invalidated.Add;
        Assert.True(loader.InvalidateAvatar(avatar, Stamp));

        Assert.Equal(new[] { AvatarToken }, invalidated);
        Assert.Null(loader.Cache.Get(MediaLoader.AvatarKey(AvatarToken)));
        await loader.AvatarAsync(Device(), avatar);
        Assert.Equal(2, handler.Requests.Count);
    }

    [Fact]
    public void TheSameSenderIsRevalidatedAtMostOncePerWindow()
    {
        // 每条消息都回源等于给每个活跃发送者加一次 RTT：窗口里再来几条就什么都不动，
        // 缓存里那份本来就是上一次作废后刚取回来的，不会因此变旧
        var loader = Loader(Images());
        var avatar = "/api/v1/avatars/" + AvatarToken;

        Assert.True(loader.InvalidateAvatar(avatar, Stamp));
        Assert.False(loader.InvalidateAvatar(avatar, Stamp.AddMinutes(9)));
        Assert.True(loader.InvalidateAvatar(avatar, Stamp.AddMinutes(11)));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("/api/v1/avatars/not-a-token")]
    public void AFrameWithoutAnAvatarInvalidatesNothing(string? senderAvatar)
    {
        var loader = Loader(Images());
        var invalidated = new List<string>();
        loader.AvatarInvalidated += invalidated.Add;

        Assert.False(loader.InvalidateAvatar(senderAvatar, Stamp));
        Assert.Empty(invalidated);
    }

    [Fact]
    public async Task AScreenfulOfTheSameMissingFaceIsAskedOnce()
    {
        // 实测：打开一条 200 条历史的会话，屏上生成 12 行、全是同一个人，却敲了 22 次取图，
        // 而这 22 次都排在同一个派发周期里、谁都没回话。404 不在正缓存里，所以以前就是 22 个真请求
        var handler = new Gated(() =>
            Support.Reply(HttpStatusCode.NotFound, Support.ErrorBody("not_found", "avatar")));
        var loader = new MediaLoader(new DeviceClient(new HttpClient(handler)), new MediaCache(_dir));
        var avatar = "/api/v1/avatars/" + AvatarToken;

        var cells = Enumerable.Range(0, 12).Select(_ => loader.AvatarAsync(Device(), avatar)).ToArray();
        handler.Release();
        var shown = await Task.WhenAll(cells);

        Assert.Equal(1, handler.Count);
        Assert.All(shown, asset => Assert.Equal(MediaState.Missing, asset.State));
    }

    [Fact]
    public async Task CellsThatLandTogetherShareTheBytesRatherThanEachDownloadingThem()
    {
        var handler = new Gated(() => Reply(Png(32), "image/png"));
        var loader = new MediaLoader(new DeviceClient(new HttpClient(handler)), new MediaCache(_dir));
        var avatar = "/api/v1/avatars/" + AvatarToken;

        var cells = Enumerable.Range(0, 12).Select(_ => loader.AvatarAsync(Device(), avatar)).ToArray();
        handler.Release();
        var shown = await Task.WhenAll(cells);

        Assert.Equal(1, handler.Count);
        Assert.All(shown, asset =>
        {
            Assert.Equal(MediaState.Ready, asset.State);
            Assert.Equal(Png(32), asset.Bytes);
        });
    }

    [Fact]
    public async Task TwoSendersOnTheSameScreenDoNotShareOneFace()
    {
        // 合流按 token 走，不然左栏第二个那个人会显示第一个人的脸
        var handler = new Gated(() => Reply(Png(32), "image/png"));
        var loader = new MediaLoader(new DeviceClient(new HttpClient(handler)), new MediaCache(_dir));

        var one = loader.AvatarAsync(Device(), "/api/v1/avatars/" + AvatarToken);
        var two = loader.AvatarAsync(Device(), "/api/v1/avatars/" + OtherToken);
        handler.Release();
        await Task.WhenAll(one, two);

        Assert.Equal(2, handler.Count);
    }

    [Fact]
    public async Task ARefusedAvatarIsNotRememberedForTheNextCellThatAsks()
    {
        // 名册只管"同时在飞的"：滚出屏幕再滚回来、点那次重试，都还得能真的再问一次
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.NotFound,
            Support.ErrorBody("not_found", "avatar")));
        var loader = Loader(handler);
        var avatar = "/api/v1/avatars/" + AvatarToken;

        Assert.Equal(MediaState.Missing, (await loader.AvatarAsync(Device(), avatar)).State);
        Assert.Equal(MediaState.Missing, (await loader.AvatarAsync(Device(), avatar)).State);

        Assert.Equal(2, handler.Requests.Count);
    }

    // ---- 头像 TTL 复核：过期先画旧的，后台再核对一次 --------------------------------------------

    [Fact]
    public async Task AnExpiredCachedFacePaintsAtOnceAndChecksWithTheServer()
    {
        // 服务端覆盖写头像不换 token、也没有 ETag，本机只剩"手里这份字节放了多久"这一个信号。
        // 判过期就先把缓存作废等于让那一格空着等网络——用户看到的是满屏头像闪一下变成首字母再闪回来
        var clock = new FakeClock(Stamp);
        var handler = new Scripted(_ => Reply(Png(32), "image/png"));
        var loader = Loader(handler, clock);
        var avatar = "/api/v1/avatars/" + AvatarToken;

        await loader.AvatarAsync(Device(), avatar);
        Assert.Equal(1, handler.Count);

        clock.JumpTo(Stamp.AddMinutes(11));
        var shown = await loader.AvatarAsync(Device(), avatar);

        Assert.Equal(MediaState.Ready, shown.State);
        Assert.Equal(Png(32), shown.Bytes);
        await Until(() => handler.Count >= 2, "过期的头像没起后台复核");
    }

    [Fact]
    public async Task AScreenfulOfExpiredFacesStartsExactlyOneCheck()
    {
        // 一屏十二格是同一个人：先记账再起飞，所以那十二次只有第一次会真去核对，
        // 否则第 2 步省下来的那些 RTT 又在每一次切会话时加回来
        var clock = new FakeClock(Stamp);
        var handler = new Scripted(_ => Reply(Png(32), "image/png"));
        var loader = Loader(handler, clock);
        var avatar = "/api/v1/avatars/" + AvatarToken;
        await loader.AvatarAsync(Device(), avatar);

        clock.JumpTo(Stamp.AddMinutes(11));
        var cells = Enumerable.Range(0, 12).Select(_ => loader.AvatarAsync(Device(), avatar)).ToArray();
        var shown = await Task.WhenAll(cells);
        Assert.All(shown, asset => Assert.Equal(MediaState.Ready, asset.State));

        await Until(() => handler.Count >= 2, "过期后一次复核都没发出去");
        clock.JumpTo(Stamp.AddMinutes(12));
        await loader.AvatarAsync(Device(), avatar);
        Assert.Equal(2, handler.Count);
    }

    [Fact]
    public async Task ACheckThatFoundADifferentFaceTellsTheCellsThatPaintIt()
    {
        var clock = new FakeClock(Stamp);
        var handler = new Scripted(n => Reply(Png(n == 1 ? 32 : 64), "image/png"));
        var loader = Loader(handler, clock);
        var avatar = "/api/v1/avatars/" + AvatarToken;
        await loader.AvatarAsync(Device(), avatar);

        var invalidated = new List<string>();
        loader.AvatarInvalidated += invalidated.Add;

        clock.JumpTo(Stamp.AddMinutes(11));
        await loader.AvatarAsync(Device(), avatar);

        await Until(() => invalidated.Count > 0, "复核回来的新头像没通知画着它的格子");
        Assert.Equal(new[] { AvatarToken }, invalidated);
        // 新字节已经顶替旧的：重画那一次取到的是新脸，而不是又回一次源
        Assert.Equal(Png(64), (await loader.AvatarAsync(Device(), avatar)).Bytes);
        Assert.Equal(2, handler.Count);
    }

    [Fact]
    public async Task ACheckThatFoundTheSameFaceRepaintsNothing()
    {
        // 广播是有代价的：画着同一个 token 的格子会全部重取一遍。字节没变时那次重画纯属白干
        var clock = new FakeClock(Stamp);
        var handler = new Scripted(_ => Reply(Png(32), "image/png"));
        var loader = Loader(handler, clock);
        var avatar = "/api/v1/avatars/" + AvatarToken;
        await loader.AvatarAsync(Device(), avatar);
        var paintedBefore = loader.Cache.Get(MediaLoader.AvatarKey(AvatarToken));

        var invalidated = new List<string>();
        loader.AvatarInvalidated += invalidated.Add;

        clock.JumpTo(Stamp.AddMinutes(11));
        await loader.AvatarAsync(Device(), avatar);

        // "复核跑完了"的信号是缓存里那份换成了这一次读回来的那个数组（同样的内容、新的实例）。
        // 只断言"没广播"的话，一次都还没落地后台复核也能让它通过
        await Until(() => !ReferenceEquals(paintedBefore, loader.Cache.Get(MediaLoader.AvatarKey(AvatarToken))),
            "后台复核没把字节放回缓存");
        Assert.Empty(invalidated);
    }

    [Fact]
    public async Task AFailedCheckLeavesTheFaceOnScreenAndIsTriedAgainNextWindow()
    {
        // 回不了源不该让人掉回首字母：旧字节继续画、不广播、也不写进负缓存。
        // 下一个窗口还会再试——节流用的是同一把十分钟尺子，不是"这条永远不用再看"
        var clock = new FakeClock(Stamp);
        var handler = new Scripted(n => n == 1
            ? Reply(Png(32), "image/png")
            : Support.Reply((HttpStatusCode)503, Support.ErrorBody("server_error")));
        var loader = Loader(handler, clock);
        var avatar = "/api/v1/avatars/" + AvatarToken;
        await loader.AvatarAsync(Device(), avatar);

        var invalidated = new List<string>();
        loader.AvatarInvalidated += invalidated.Add;

        clock.JumpTo(Stamp.AddMinutes(11));
        var shown = await loader.AvatarAsync(Device(), avatar);
        Assert.Equal(Png(32), shown.Bytes);

        await Until(() => handler.Count >= 2, "过期后没起后台复核");
        Assert.Equal(MediaState.Ready, (await loader.AvatarAsync(Device(), avatar)).State);
        Assert.Empty(invalidated);

        clock.JumpTo(Stamp.AddMinutes(22));
        await loader.AvatarAsync(Device(), avatar);
        await Until(() => handler.Count >= 3, "下一个窗口没重试——失败被当成永久结果存下来了");
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
    public async Task EvictingOneKeyLeavesTheOthersAlone()
    {
        // 头像那条路要的是单键作废：两级都清，但别人那一格照旧命中，不能把整个缓存带走
        var handler = Images();
        var loader = Loader(handler);
        await loader.ImageAsync(Device(), "m_83", 0, thumb: true);
        await loader.ImageAsync(Device(), "m_83", 1, thumb: true);

        loader.Cache.Evict(MediaLoader.ImageKey("m_83", 0, true));

        Assert.Null(loader.Cache.Get(MediaLoader.ImageKey("m_83", 0, true)));
        Assert.NotNull(loader.Cache.Get(MediaLoader.ImageKey("m_83", 1, true)));
        await loader.ImageAsync(Device(), "m_83", 0, thumb: true);
        Assert.Equal(3, handler.Requests.Count);
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

    /// <summary>
    /// 回答由用例放行，这样"同一时刻的二十几格"才真的能重叠在一起——合流测的就是重叠。
    /// 每个请求各造一份回答（<see cref="HttpResponseMessage.Content"/> 读一次就Disposed），
    /// 计数用 Interlocked：这一格靠的正是几个线程同时进来，用 <see cref="FakeHandler"/> 那个 List 会打架。
    /// </summary>
    private sealed class Gated : HttpMessageHandler
    {
        private readonly Func<HttpResponseMessage> _reply;
        private readonly TaskCompletionSource<bool> _hold =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        private int _count;

        public Gated(Func<HttpResponseMessage> reply) => _reply = reply;

        public int Count => Volatile.Read(ref _count);

        public void Release() => _hold.SetResult(true);

        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Interlocked.Increment(ref _count);
            await _hold.Task.ConfigureAwait(false);
            return _reply();
        }
    }

    /// <summary>
    /// 按"这是第几次请求"给回答，并且能数出到底发了几次。TTL 那一档要判的是"过没过期"，
    /// 回答必须能变（第一次旧脸、第二次新脸），而 <see cref="FakeHandler"/> 那个 List 在
    /// 后台复核那条线程上会和测试线程打架，所以这里用 Interlocked。
    /// </summary>
    private sealed class Scripted : HttpMessageHandler
    {
        private readonly Func<int, HttpResponseMessage> _reply;
        private int _count;

        public Scripted(Func<int, HttpResponseMessage> reply) => _reply = reply;

        public int Count => Volatile.Read(ref _count);

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            var index = Interlocked.Increment(ref _count);
            return Task.FromResult(_reply(index));
        }
    }

    /// <summary>用例自己拨的钟：真等十分钟没法跑，而这一档的语义就只剩"过没过"。</summary>
    private sealed class FakeClock
    {
        private long _ticks;

        public FakeClock(DateTimeOffset start) => _ticks = start.UtcTicks;

        public void JumpTo(DateTimeOffset now) => Volatile.Write(ref _ticks, now.UtcTicks);

        public DateTimeOffset Now() => new(Volatile.Read(ref _ticks), TimeSpan.Zero);
    }

    private MediaLoader Loader(HttpMessageHandler handler, FakeClock clock) =>
        new(new DeviceClient(new HttpClient(handler)), new MediaCache(_dir), clock.Now);

    /// <summary>等后台那条复核跑到：它是不挡路的（旧字节已经交出去了），所以只能等它的副作用，
    /// 而不是等一个能被 await 的任务。超时就红，别把它睡成偶发绿。</summary>
    private static async Task Until(Func<bool> condition, string because, int timeoutMs = 4000)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (!condition() && DateTime.UtcNow < deadline) await Task.Delay(10);
        Assert.True(condition(), because);
    }

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
