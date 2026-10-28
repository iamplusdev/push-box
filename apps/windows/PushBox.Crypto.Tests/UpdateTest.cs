using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Controls;
using PushBox.Windows.Settings;
using PushBox.Windows.Update;
using PushBox.Windows.Views;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// 步骤 6/7 的下载面：掐过频的读数（<see cref="DownloadMeter"/>）、进度那行字的拼法、
/// 代理三档落到 handler 的结果，以及"下完才改名、下断就报错"这条落盘口径。
/// 界面那一格只建一次：断的是"存进设置的那一档会摆回控件上、改了会立刻写回去"。
/// </summary>
public sealed class UpdateTest : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(),
        "pushbox-update-test-" + Guid.NewGuid().ToString("N"));

    public UpdateTest() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        try
        {
            Directory.Delete(_dir, true);
        }
        catch (IOException)
        {
            // 用例跑完删不掉临时目录不该反过来把一次通过弄成失败
        }
    }

    // ---- 进度读数 -------------------------------------------------------------------------------------

    [Fact]
    public void TheMeterReportsOncePerIntervalInsteadOfOncePerChunk()
    {
        var meter = new DownloadMeter();

        // 第一格总发：那一下让界面从"正在连接"换成"下载中…"，但速度还没有时间差可比
        var first = meter.Sample(81920, 819200, TimeSpan.Zero);
        Assert.NotNull(first);
        Assert.Equal(0, first!.Value.BytesPerSecond);

        // 80KB 一格的回调在 200ms 内来三次，只发第一格
        Assert.Null(meter.Sample(163840, 819200, TimeSpan.FromMilliseconds(50)));
        Assert.Null(meter.Sample(245760, 819200, TimeSpan.FromMilliseconds(199)));

        var due = meter.Sample(327680, 819200, TimeSpan.FromMilliseconds(400))!.Value;
        Assert.Equal(40, due.Percent);
        // 从第一格算起 245760 字节 / 0.4 秒
        Assert.Equal(614400, due.BytesPerSecond, 0);
    }

    [Fact]
    public void ASuddenStallOnlyKnocksTheSpeedDownByFortyPercent()
    {
        var meter = new DownloadMeter();
        meter.Sample(0, 400000, TimeSpan.Zero);
        var fast = meter.Sample(400000, 400000, TimeSpan.FromSeconds(0.2))!.Value;
        Assert.Equal(2_000_000, fast.BytesPerSecond, 0);

        // 下一格一个字节都没到：瞬时是 0，读数只掉到上一格的六成——不会当场变成"剩余 12 小时"
        var stall = meter.Sample(400000, 400000, TimeSpan.FromSeconds(0.4))!.Value;
        Assert.Equal(1_200_000, stall.BytesPerSecond, 0);
    }

    [Fact]
    public void TheLastTickIgnoresTheInterval()
    {
        var meter = new DownloadMeter();
        meter.Sample(1024, 4096, TimeSpan.Zero);

        // 收尾那一格不看间隔：不补这一下，界面会停在 98% 而文件其实已经下完
        var last = meter.Finish(4096, 4096);
        Assert.Equal(4096, last.Done);
        Assert.Equal(100, last.Percent);
    }

    // ---- 那行字 ---------------------------------------------------------------------------------------

    [Fact]
    public void TheProgressLineCarriesPercentBytesSpeedAndTimeLeft()
    {
        var line = UpdateModel.ProgressLine(new DownloadTick(5_000_000, 20_000_000, 2_000_000));

        Assert.Contains("25%", line);
        Assert.Contains("4.8 MB / 19.1 MB", line);
        Assert.Contains("MB/s", line);
        Assert.Contains("剩余约 8 秒", line);
    }

    [Fact]
    public void WithoutAContentLengthTheLineClaimsNoPercentAndNoEta()
    {
        // chunked 响应没有总长：不编百分比，也不猜还有多久
        var line = UpdateModel.ProgressLine(new DownloadTick(2048, 0, 0));

        Assert.StartsWith("下载中…", line);
        Assert.DoesNotContain("%", line);
        Assert.DoesNotContain("剩余", line);
    }

    [Fact]
    public void TheTimeLeftIsRoundedToSomethingAHumanWouldSay()
    {
        Assert.Equal("剩余约 8 秒", UpdateModel.RemainingLabel(TimeSpan.FromSeconds(7.5)));
        Assert.Equal("剩余约 16 分 40 秒", UpdateModel.RemainingLabel(TimeSpan.FromSeconds(1000)));
        Assert.Equal("剩余约 1 小时 0 分", UpdateModel.RemainingLabel(TimeSpan.FromSeconds(3600)));
    }

    // ---- 代理 -----------------------------------------------------------------------------------------

    [Fact]
    public void AProxyAddressNeedsASchemeItUnderstandsAndAnExplicitPort()
    {
        Assert.Equal(new Uri("http://127.0.0.1:7890"), ProxyChoice.AddressOf("127.0.0.1:7890"));
        Assert.Equal(new Uri("http://127.0.0.1:7890"), ProxyChoice.AddressOf("  http://127.0.0.1:7890 "));
        Assert.Equal(new Uri("http://localhost:7890"), ProxyChoice.AddressOf("localhost:7890"));

        Assert.Null(ProxyChoice.AddressOf(null));
        Assert.Null(ProxyChoice.AddressOf("  "));
        // "7890" 会被 Uri 当成主机名 + 80 端口：那不是用户想填的那台代理
        Assert.Null(ProxyChoice.AddressOf("7890"));
        Assert.Null(ProxyChoice.AddressOf("127.0.0.1"));
        Assert.Null(ProxyChoice.AddressOf("socks5://127.0.0.1:7890"));
    }

    [Fact]
    public void TheProxyModeDecidesWhatTheDownloadHandlerDoes()
    {
        var system = UpdateClient.HandlerFor(null);
        Assert.True(system.UseProxy);
        Assert.Null(system.Proxy);

        var direct = UpdateClient.HandlerFor(new ProxyChoice(UpdateProxyMode.Direct));
        Assert.False(direct.UseProxy);

        var custom = UpdateClient.HandlerFor(new ProxyChoice(UpdateProxyMode.Custom, "127.0.0.1:7890"));
        Assert.True(custom.UseProxy);
        Assert.Equal(new Uri("http://127.0.0.1:7890"), Assert.IsType<WebProxy>(custom.Proxy).Address);

        // 自定义写歪了不拦下载：退回系统那一条，至少还有下完的可能
        var broken = UpdateClient.HandlerFor(new ProxyChoice(UpdateProxyMode.Custom, "7890"));
        Assert.True(broken.UseProxy);
        Assert.Null(broken.Proxy);
    }

    [Fact]
    public void TheProxyDropdownIndexAndTheModeMapBothWays()
    {
        foreach (var mode in new[] { UpdateProxyMode.System, UpdateProxyMode.Direct, UpdateProxyMode.Custom })
            Assert.Equal(mode, SettingsModel.ProxyModeOf(SettingsModel.ProxyModeIndexOf(mode)));

        Assert.Equal(UpdateProxyMode.System, SettingsModel.ProxyModeOf(-1));
        Assert.Equal(3, SettingsModel.ProxyLabels.Count);
    }

    // ---- 落盘 -----------------------------------------------------------------------------------------

    [Fact]
    public async Task ADownloadLandsInTheTempFileAndIsRenamedOnlyWhenComplete()
    {
        var bytes = new byte[300_000];
        new Random(7).NextBytes(bytes);
        var client = Client(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new ByteArrayContent(bytes)
        });
        var dest = Path.Combine(_dir, "PushBox-Setup-9.9.9-win-x64.exe");
        var sink = new Sink();

        await client.DownloadAsync("https://example.invalid/setup.exe", dest, sink);

        Assert.True(File.Exists(dest));
        Assert.False(File.Exists(dest + ".tmp"));
        Assert.Equal(bytes, await File.ReadAllBytesAsync(dest));

        // 300KB 按 80KB 一格本该报 4 次；掐频之后只剩"第一格 + 收尾那一格"
        Assert.InRange(sink.Ticks.Count, 2, 3);
        Assert.Equal(0, sink.Ticks[0].BytesPerSecond);
        Assert.Equal(300_000, sink.Ticks[^1].Done);
        Assert.Equal(300_000, sink.Ticks[^1].Total);
        Assert.Equal(100, sink.Ticks[^1].Percent);
    }

    [Fact]
    public async Task ATruncatedDownloadFailsInsteadOfLeavingAHalfPackage()
    {
        // 声明 400KB、只吐 100KB：连接在下到一半时被切断就长这样
        var client = Client(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new ShortContent(new byte[100_000], 400_000)
        });
        var dest = Path.Combine(_dir, "PushBox-Setup-9.9.9-win-x64.exe");

        var error = await Assert.ThrowsAsync<IOException>(() =>
            client.DownloadAsync("https://example.invalid/setup.exe", dest, null));

        Assert.Contains("下载不完整", error.Message);
        Assert.False(File.Exists(dest));
    }

    [Fact]
    public async Task ANonSuccessStatusIsOneSentence()
    {
        var client = Client(_ => new HttpResponseMessage(HttpStatusCode.NotFound));

        var error = await Assert.ThrowsAsync<HttpRequestException>(() =>
            client.DownloadAsync("https://example.invalid/setup.exe", Path.Combine(_dir, "x.exe"), null));

        Assert.Contains("HTTP 404", error.Message);
    }

    // ---- 确认框上的那一格 ------------------------------------------------------------------------------

    /// <summary>存着的那一档要摆回控件上；换档立刻写回设置（不等人再点一次"下载"）。
    /// 用可视树取控件而不是给窗口开测试口子：那样窗口的公开面就只为用例多长一个成员。</summary>
    [Fact]
    public void TheProxyPickerStartsFromTheSavedChoiceAndWritesBackOnChange()
    {
        Sta.Run(() =>
        {
            var saved = new List<ClientSettings>();
            var release = new GitHubRelease("windows-v9.9.9", "9.9.9", "",
                "https://example.invalid/PushBox-Setup-9.9.9-win-x64.exe", "PushBox-Setup-9.9.9-win-x64.exe", 1024);
            var window = new UpdatePromptWindow(new UpdateModel(), release, null,
                new ClientSettings { UpdateProxy = UpdateProxyMode.Direct }, saved.Add);

            Assert.Equal(1, window.ProbeProxyIndex);
            // 只有"自定义"才露地址格：不选它却摆一个空输入框，用户会以为那里必须填点什么
            Assert.False(window.ProbeProxyAddressVisible);

            // 这棵树里只有这一个下拉（地址格是 TextBox），Take(1) 是给 Descendants 的重复计数留的余量
            var box = Tree.Descendants<ComboBox>(window).First();
            box.SelectedIndex = 2;

            Assert.Equal(UpdateProxyMode.Custom, saved.Single().UpdateProxy);
            Assert.True(window.ProbeProxyAddressVisible);
            window.Close();
        });
    }

    /// <summary>取消回调用例里那个同步收集器：<see cref="Progress{T}"/> 在没有同步上下文的线程上
    /// 会排到线程池，断言拿到的可能还是空的。</summary>
    private sealed class Sink : IProgress<DownloadTick>
    {
        public List<DownloadTick> Ticks { get; } = new();

        public void Report(DownloadTick value) => Ticks.Add(value);
    }

    /// <summary>真下载路径直连一个假的传输层：这里要断的是落盘与读数，不是 GitHub 的响应格式。</summary>
    private static UpdateClient Client(Func<RecordedRequest, HttpResponseMessage> respond) =>
        new(downloadClient: _ => new HttpClient(new FakeHandler(respond))
        {
            Timeout = Timeout.InfiniteTimeSpan
        });

    /// <summary>声明一个长度、只吐另一个长度的内容：模拟"连接在下到一半时被切断"。</summary>
    private sealed class ShortContent : HttpContent
    {
        private readonly byte[] _bytes;
        private readonly long _declared;

        public ShortContent(byte[] bytes, long declared)
        {
            _bytes = bytes;
            _declared = declared;
        }

        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) =>
            stream.WriteAsync(_bytes, 0, _bytes.Length);

        protected override bool TryComputeLength(out long length)
        {
            length = _declared;
            return true;
        }
    }
}
