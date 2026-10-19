using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Net;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W11 的合并层：§7.4 那两段结果在屏幕上是同一个列表，所以去重、缺名、失败这三件事都发生在这里，
/// 而不是留到 XAML 里补。两条验收口径（§8.4 W11）都在下面的用例里：
/// 未缓存的历史消息能被搜到、同一条结果不出现两次。
/// </summary>
public sealed class SearchServiceTest : IDisposable
{
    private const string BaseUrl = "http://127.0.0.1:8081";
    private const string Channel = "83271504";
    private const string Jenkins = "jenkins";

    private static readonly byte[] DeviceEncPriv =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_enc_priv"));

    private static readonly byte[] DeviceSignSeed =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_sign_seed"));

    private readonly string _dir;
    private readonly IdentityStore _identities;
    private readonly DeviceStore _db;

    public SearchServiceTest()
    {
        _dir = Path.Combine(Path.GetTempPath(), "pushbox-w11-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _identities = new IdentityStore(_dir);
        _identities.Save(Device());
        _db = new DeviceStore(Path.Combine(_dir, "pushbox.db"));
    }

    // ---- 两段合并 ----------------------------------------------------------------------------------

    [Fact]
    public async Task AMessageThatWasNeverCachedIsStillSearchable()
    {
        // §8.4 W11 验收①：本机库里没有的那条，靠服务端明文库召回
        var service = Service(Server(Hit(18490, "01J8M4ZD00", "构建失败", "…CPU 使用率超阈值")));

        var result = await service.SearchAsync("CPU");

        Assert.Empty(result.Local);
        var remote = Assert.Single(result.Server);
        Assert.Equal("01J8M4ZD00", remote.Id);
        Assert.Equal("…CPU 使用率超阈值", remote.Snippet);
        Assert.Equal("Jenkins CI", remote.SenderName);
        Assert.Null(result.Error);
    }

    [Fact]
    public async Task TheSameMessageIsNotListedTwice()
    {
        // §8.4 W11 验收②：同一条既在本地又被服务端召回，留本地那份——它带正文，点进去能直接定位
        Seed(Channel, Jenkins, "01LOCAL1", 5L, "夜间备份任务", "CPU 占用高", "正文里也提到 CPU");
        var service = Service(Server(Hit(18491, "01LOCAL1", "夜间备份任务", "…CPU 占用高"),
            Hit(18492, "01LOCAL2", "白天的告警", "…CPU 恢复正常")));

        var result = await service.SearchAsync("CPU");

        Assert.Equal("01LOCAL1", Assert.Single(result.Local).Id);
        Assert.Equal("01LOCAL2", Assert.Single(result.Server).Id);
    }

    [Fact]
    public void ALocalHitCarriesTheConversationNameAndItsOwnSnippet()
    {
        Seed(Channel, Jenkins, "01LOCAL1", 5L, "夜间备份任务", "备份摘要",
            new string('满', 300) + "夜间备份在节点 disk01 上因为磁盘空间不足而回滚");

        var hit = Assert.Single(Service(Server()).LocalHits("磁盘空间"));

        Assert.Equal("01LOCAL1", hit.Id);
        // messages 表里没有发送者名（§3.3 它在 conversations 上），而服务端那段是带的——
        // 两段拼在一个列表里时不能一边有名一边没名
        Assert.Equal(Jenkins, hit.SenderName);
        Assert.Contains("磁盘空间", hit.Snippet);
        Assert.StartsWith("…", hit.Snippet);
        Assert.True(hit.Snippet.Length <= SearchText.SnippetChars + 2,
            $"片段该裁成一个窗口，实际 {hit.Snippet.Length} 字");
    }

    [Fact]
    public async Task OnlyThisConversationIsSearchedWhenAScopeIsGiven()
    {
        // §7.4 对话页那个搜索框只查这一条会话线：本地段和服务端段用同一个 scope，
        // 否则两段说的不是同一件事——本地只剩这一条，历史却把别的会话也捞进来
        Seed(Channel, Jenkins, "01LOCAL1", 5L, "本会话的词", null, "只有这里提到 阈值");
        Seed("91002203", "deploy", "01LOCAL2", 6L, "另一个会话", null, "另一个会话也提到 阈值");
        var handler = Server();
        var service = Service(handler);

        var scoped = await service.SearchAsync("阈值", Channel, Jenkins);
        var everything = await service.SearchAsync("阈值");

        Assert.Equal("01LOCAL1", Assert.Single(scoped.Local).Id);
        Assert.Equal(2, everything.Local.Count);
        // 同一段会话线要同时管两段：本地只剩这一条、历史却把别的会话也捞进来，两段说的就不是同一件事
        Assert.Contains("channel_no=83271504", handler.Requests[0].Query);
        Assert.Contains("sender_id=jenkins", handler.Requests[0].Query);
        Assert.DoesNotContain("channel_no", handler.Requests[1].Query);
    }

    [Fact]
    public async Task TheServerHalfKeepsItsOwnHasMore()
    {
        // 服务端一次最多回 200 条，has_more 说的是"更早历史还有"，跟本地那半查没查完无关
        var service = Service(ServerMore(Hit(1, "01A", "t", "s"), Hit(2, "01B", "t", "s")));

        var result = await service.SearchAsync("词");

        Assert.True(result.HasMore);
        Assert.Equal(2, result.Server.Count);
        Assert.Empty(result.Local);
    }

    [Fact]
    public async Task AnEmptyTermAsksNeitherTheDiskNorTheNetwork()
    {
        Seed(Channel, Jenkins, "01LOCAL1", 5L, "夜间备份任务", null, "正文");
        var handler = Server();

        var result = await Service(handler).SearchAsync("  ");

        Assert.True(result.IsEmpty);
        Assert.Empty(handler.Requests);
    }

    // ---- 服务端那段没回来 ---------------------------------------------------------------------------

    [Fact]
    public async Task AFailedServerSearchStillHandsBackTheLocalHalf()
    {
        // 断网时搜索框照样要能查到自己缓存过的内容，只是"更早历史"这一半要说明没拿到
        Seed(Channel, Jenkins, "01LOCAL1", 5L, "夜间备份任务", null, "正文里有 阈值 这个词");
        var service = new SearchService(_db, _identities, new DeviceClient(new HttpClient(
            new ThrowingHandler(new HttpRequestException("no route to host")))));

        var result = await service.SearchAsync("阈值");

        Assert.Equal("01LOCAL1", Assert.Single(result.Local).Id);
        Assert.Empty(result.Server);
        Assert.False(result.HasMore);
        Assert.Contains("no route to host", result.Error);
    }

    [Fact]
    public async Task ARejectedSearchIsOneSentenceRatherThanAnException()
    {
        // 结果列表不该因为一次 401 就整片崩掉：本地那半是真的，服务端那半只是没拿回来，
        // 而要说给用户的话术仍然是 §5.1 那张表里的同一句
        Seed(Channel, Jenkins, "01LOCAL1", 5L, "夜间备份任务", null, "正文里有 阈值 这个词");
        var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.Unauthorized,
            Support.ErrorBody("unauthorized")));
        var expected = new PushBoxApiException(HttpStatusCode.Unauthorized, "unauthorized", "").Message;

        var result = await Service(handler).SearchAsync("阈值");

        Assert.Single(result.Local);
        Assert.Equal(expected, result.Error);
        Assert.DoesNotContain("{", result.Error);
    }

    [Fact]
    public async Task ACancelledSearchIsNotReportedAsAFailure()
    {
        // 用户又敲了一个词，上一发请求该安静地退场，而不是变成状态条上的一句"更早历史没查到"
        var gate = new TaskCompletionSource<HttpResponseMessage>(TaskCreationOptions.RunContinuationsAsynchronously);
        var handler = new FakeHandler(async (_, ct) =>
        {
            using var registration = ct.Register(() => gate.TrySetCanceled(ct));
            return await gate.Task;
        });
        using var ct = new CancellationTokenSource();

        var pending = Service(handler).SearchAsync("阈值", ct: ct.Token);
        ct.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => pending);
    }

    // ---- 片段与高亮 ---------------------------------------------------------------------------------

    [Fact]
    public void TheSnippetOpensAWindowAroundTheFirstMatch()
    {
        var body = new string('前', 100) + "使用率超标" + new string('后', 100);

        var snippet = SearchText.Snippet("使用率", "标题", body, "摘要");

        Assert.Contains("使用率", snippet);
        // 命中处前面留 1/3 窗口，两端各一个省略号——与服务端 snippet() 同形
        Assert.StartsWith("…", snippet);
        Assert.EndsWith("…", snippet);
        Assert.Equal(SearchText.SnippetChars + 2, snippet.Length);
    }

    [Fact]
    public void AWindowEdgeLandingInsideAnEmojiTakesTheWholeCharacter()
    {
        // 正文里一个 emoji 是两个 char，窗口边界正好落在中间就会在屏上显成一个问号：
        // 命中在第 141 字 → 窗口起点算下来是 101，那正是 emoji 的后半截
        var body = new string('前', 100) + "😀" + new string('前', 39) + "b" + new string('后', 100);

        var snippet = SearchText.Snippet("b", null, body, null);

        Assert.Contains("b", snippet);
        Assert.DoesNotContain("😀", snippet);
        for (var i = 0; i < snippet.Length; i++)
        {
            if (char.IsLowSurrogate(snippet[i]))
            {
                Assert.True(i > 0 && char.IsHighSurrogate(snippet[i - 1]), $"片段里第 {i} 个字符是半个 emoji");
            }
        }
    }

    [Fact]
    public void WhenNothingMatchesLiterallyTheSnippetOpensAtTheStartOfTheFirstColumn()
    {
        // 全文索引允许命中在分词后的变形上（大小写、标点），这时服务端给的是第一列的开头而不是空串，
        // 本地这一路同形：宁可得一个不像片段的片段，也不让那一行只剩标题
        Assert.Equal("标题", SearchText.Snippet("使用率", "标题", "正文里没有那个词", null));
        // 三列都空才真的没得可显示
        Assert.Equal("", SearchText.Snippet("词", null, null, null));
    }

    [Fact]
    public void HighlightRangesCoverThePhraseFirstAndNeverOverlap()
    {
        var text = "CPU 使用率：CPU 占用高，使用率 90%";

        var ranges = SearchText.HighlightRanges(text, "CPU 使用率");

        // 整词先占位，空格切出来的各个词只能补在它没盖住的地方——否则同一段文字被标两次，
        // XAML 拼 Run 时就会把字符重复或丢掉
        Assert.Equal(new[] { (0, 7), (8, 3), (16, 3) }, ranges);
        Assert.Equal(new[] { "CPU 使用率", "CPU", "使用率" },
            ranges.Select(r => text.Substring(r.Start, r.Length)).ToArray());
    }

    [Fact]
    public void AnEmptyTermHighlightsNothingRatherThanEverything()
    {
        Assert.Empty(SearchText.HighlightRanges("任意正文", "   "));
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

    private SearchService Service(FakeHandler handler) =>
        new(_db, _identities, new DeviceClient(new HttpClient(handler)));

    /// <summary>往库里铺一条本机已缓存的消息（§3.3 的三列都进 FTS 触发器）。</summary>
    private void Seed(string channelNo, string senderId, string msgId, long seq, string title,
        string? summary, string body) =>
        _db.ApplyMessage(new MessageFrame(seq, msgId, channelNo, senderId, senderId, null, "text",
            title, summary, body, "info", null, null, Array.Empty<MediaSlot>(), true,
            1_765_500_000_000L + seq, false));

    /// <summary>服务端那段命中的一条 JSON，字段名与 core/api/SearchPage.SearchHit 逐字一致。</summary>
    private static string Hit(long seq, string id, string title, string snippet) =>
        "{\"seq\":" + seq + ",\"id\":\"" + id + "\",\"channel_no\":\"" + Channel + "\","
        + "\"sender_id\":\"" + Jenkins + "\",\"sender_name\":\"Jenkins CI\",\"type\":\"alert\","
        + "\"title\":\"" + title + "\",\"snippet\":\"" + snippet + "\",\"level\":\"warn\","
        + "\"created_at\":1765500000000}";

    /// <summary>把命中装进 results[] 后封成 §5.2 的密封盒（密钥是 vectors.json 那台设备的，
    /// 所以 fixture 的身份也是它）。</summary>
    private static FakeHandler Server(params string[] hits) => Sealed(hits, false);

    private static FakeHandler ServerMore(params string[] hits) => Sealed(hits, true);

    private static FakeHandler Sealed(string[] hits, bool hasMore) => new(_ =>
        Support.Reply(HttpStatusCode.OK, Support.SealRestResponse(
            "{\"results\":[" + string.Join(",", hits) + "],\"has_more\":"
            + (hasMore ? "true" : "false") + "}")));
}
