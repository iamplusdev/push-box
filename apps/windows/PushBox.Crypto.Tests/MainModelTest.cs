using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Media;
using PushBox.Windows.Net;
using PushBox.Windows.Sync;
using PushBox.Windows.Views;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W12 的主窗口判定。它们全在 <see cref="MainModel"/> 里而不是在 XAML 里，为的就是这一页能被普通用例断言：
/// §8.1 的双栏、§9.2 的行高与未读数字、§9.3 的筛选提示条、§12 P3 验收③ 的"两端渲染一致"，
/// 只要判据还是"某条消息该不该出现、该显示成什么文字"，就都落在这一层。
/// </summary>
public sealed class MainModelTest : IDisposable
{
    private const string Channel = "83271504";
    private const string Grafana = "grafana";

    /// <summary>
    /// 日期分节的用例要能算出"同一天/相邻两天"，所以基准点是**本地**那天早上八点，
    /// 而不是一个 UTC 串——机器的时区差不该决定某个用例今天开始失败。
    /// </summary>
    private static readonly long Day0 = At(2025, 12, 12, 8);

    private const long DayMs = 24L * 60 * 60 * 1000;

    private static long At(int year, int month, int day, int hour) =>
        new DateTimeOffset(new DateTime(year, month, day, hour, 0, 0, DateTimeKind.Local))
            .ToUnixTimeMilliseconds();

    private static readonly byte[] DeviceEncPriv =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_enc_priv"));

    private static readonly byte[] DeviceSignSeed =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_sign_seed"));

    private readonly string _dir;
    private readonly IdentityStore _identities;
    private readonly DeviceStore _db;
    private readonly ReadReporter _reporter;

    public MainModelTest()
    {
        _dir = Path.Combine(Path.GetTempPath(), "pushbox-w12-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _identities = new IdentityStore(_dir);
        _identities.Save(new DeviceIdentity("dev_7f3a", DeviceEncPriv, DeviceSignSeed,
            "pbk_1a2b3c4d5e6f", "grp_001", "http://127.0.0.1:8081", null, 0L));
        _db = new DeviceStore(Path.Combine(_dir, "pushbox.db"));
        // 这台"离线"：已读只攒着不报，网络那半永远走不到
        _reporter = new ReadReporter(_db, _identities,
            new DeviceClient(new HttpClient(new ThrowingHandler(new HttpRequestException("不该发请求")))));
    }

    // ---- 打开会话与红点 ---------------------------------------------------------------------------

    [Fact]
    public void OpeningAConversationClearsItsBadgeAndQueuesOneReadReport()
    {
        Seed(1, "m1");
        Seed(2, "m2");
        Seed(3, "m3");
        var model = Model();

        Assert.Equal("3", Assert.Single(model.Conversations()).UnreadText);
        Assert.Equal(0, _reporter.PendingCount);

        model.Open(Channel, Grafana);

        var conversation = Assert.Single(model.Conversations());
        // §9.2 的未读是纯数字，不是红点：清完就是没有这个文本，而不是"0"
        Assert.Null(conversation.UnreadText);
        Assert.Equal(3, model.Timeline().OfType<MessageItem>().Count());
        // 离线时 MarkRead 只攒一条，等 SetOnline 再报（W9 的口径）
        Assert.Equal(1, _reporter.PendingCount);
    }

    [Fact]
    public void TotalUnreadIsTheBadgeAcrossEveryConversation()
    {
        // §8.1 托盘那一行读的是这个总数，而不是当前选中会话的未读：W13 之前没有消费者，
        // 于是 "PushBox · 12 条未读" 这句只能靠人眼看，现在它是一条断言
        Seed(1, "m1");
        Seed(2, "m2");
        Seed(3, "m3", sender: "deploy");
        var model = Model();

        Assert.Equal(3, model.TotalUnread());
        model.Open(Channel, Grafana);
        Assert.Equal(1, model.TotalUnread());
        model.Open(Channel, "deploy");
        Assert.Equal(0, model.TotalUnread());
    }

    [Fact]
    public void ClosingAConversationKeepsTheListIntact()
    {
        Seed(1, "m1");
        var model = Model();
        model.Open(Channel, Grafana);

        model.CloseConversation();

        Assert.Null(model.Selected);
        Assert.Empty(model.Timeline());
        Assert.Single(model.Conversations());
    }

    [Fact]
    public void OpeningAConversationThatVanishedChangesNothing()
    {
        // 会话在点下去的那一瞬间被别的端清空了：库里查不到就整件事都不该发生，
        // 而不是切出一个空的时间线还把自己原来的选中态丢掉
        Seed(1, "m1");
        var model = Model();
        _db.ApplyTombstone(Tombstone(
            """{"t":"tombstone","scope":{"channel_no":"83271504","sender_id":"grafana"},"deleted_at":9}"""));

        model.Open(Channel, Grafana);

        Assert.Null(model.Selected);
    }

    // ---- 时间线 ------------------------------------------------------------------------------------

    [Fact]
    public void TheTimelineSplitsByDayWithOneDividerPerDay()
    {
        Seed(1, "m1", createdAt: At(2025, 12, 12, 8));
        Seed(2, "m2", createdAt: At(2025, 12, 12, 9));
        Seed(3, "m3", createdAt: At(2025, 12, 14, 8));
        var model = Model();
        model.Open(Channel, Grafana);

        var items = model.Timeline();

        Assert.Equal(5, items.Count);
        Assert.Equal(new[] { "DateDivider", "MessageItem", "MessageItem", "DateDivider", "MessageItem" },
            items.Select(i => i.GetType().Name));
        // 同一个自然日的两条之间不该再插一个分节头
        Assert.Equal(2, items.OfType<DateDivider>().Count());
    }

    [Fact]
    public void TheBodyWinsThenTheSummaryThenTheTitle()
    {
        // 与 Android 的 messageText = body ?: summary ?: title 同一张三选一（§12 验收③ 就判这个）
        Seed(1, "m1", title: "只有标题");
        Seed(2, "m2", title: "标题", summary: "摘要有优先");
        Seed(3, "m3", title: "标题", summary: "摘要", body: "正文最优先");
        var model = Model();
        model.Open(Channel, Grafana);

        Assert.Equal(new[] { "只有标题", "摘要有优先", "正文最优先" },
            model.Timeline().OfType<MessageItem>().Select(m => m.Text));
    }

    [Fact]
    public void ATombstoneMovesTheLevelDotBecauseTheDotIsNotAColumn()
    {
        // 会话行那个小圆点读的是"当前最后一条"的 level，不是某个落列的标志位：
        // 未读会算错，圆点也会——tombstone 之后那条不在了，点就该跟着回到上一条
        Seed(1, "m1", level: "info");
        Seed(2, "m2", level: "warning");
        var model = Model();
        Assert.Equal("Level.Warning", Assert.Single(model.Conversations()).LevelDotKey);

        _db.ApplyTombstone(Tombstone("{\"t\":\"tombstone\",\"id\":\"m2\",\"deleted_at\":9}"));

        // 剩下那条是 info：小圆点读的是"最后一条的 level"，所以它跟着消失
        Assert.Null(Assert.Single(model.Conversations()).LevelDotKey);
    }

    [Fact]
    public void InfoHasNoAccentButWarningAndErrorDo()
    {
        // §9.1 的克制：只有 warning/error 上色，info 连色条都不给
        Seed(1, "m1", level: "info");
        Seed(2, "m2", level: "warning");
        Seed(3, "m3", level: "error");
        var model = Model();
        model.Open(Channel, Grafana);

        var items = model.Timeline().OfType<MessageItem>().ToList();
        Assert.Null(items[0].AccentKey);
        Assert.Equal("Level.Warning", items[1].AccentKey);
        Assert.Equal("Level.Error", items[2].AccentKey);
        Assert.Equal("Level.Error", Assert.Single(model.Conversations()).LevelDotKey);
    }

    [Fact]
    public void ATypeLabelAppearsOnlyForNonTextMessages()
    {
        Seed(1, "m1");
        Seed(2, "m2", type: "markdown");
        Seed(3, "m3", type: "image", mediaCount: 2);
        var model = Model();
        model.Open(Channel, Grafana);

        var items = model.Timeline().OfType<MessageItem>().ToList();
        Assert.Null(items[0].MsgTypeLabel);
        Assert.Equal("Markdown", items[1].MsgTypeLabel);
        Assert.Equal("图片", items[2].MsgTypeLabel);
        // 图位数是渲染那一排 220×165 槽位的唯一依据，正文与它各走各的
        Assert.Equal(2, items[2].MediaCount);
    }

    [Fact]
    public void ALinkMessageCarriesItsUrlSoTheBubbleCanOfferToOpenIt()
    {
        Seed(1, "m1", type: "link", url: "https://ci.iamplus.cn/job/1287");
        var model = Model();
        model.Open(Channel, Grafana);

        Assert.Equal("https://ci.iamplus.cn/job/1287",
            Assert.Single(model.Timeline().OfType<MessageItem>()).LinkUrl);
    }

    [Fact]
    public void LoadingEarlierPrependsThePreviousPageWithoutGaps()
    {
        for (long seq = 1; seq <= 60; seq++) Seed(seq, $"m{seq}");
        var model = Model();
        model.Open(Channel, Grafana);

        // 首屏只取一页（PageLimit），屏上是最晚的那 50 条
        Assert.Equal(50, model.Timeline().OfType<MessageItem>().Count());
        Assert.Equal(11L, model.Timeline().OfType<MessageItem>().First().Seq);

        Assert.True(model.LoadEarlier());
        var seqs = model.Timeline().OfType<MessageItem>().Select(m => m.Seq).ToList();
        Assert.Equal(60, seqs.Count);
        Assert.Equal(Enumerable.Range(1, 60).Select(s => (long)s), seqs);

        // 到底了就说到底，不要留一个"点了没反应"的开关
        Assert.False(model.LoadEarlier());
    }

    [Fact]
    public void ReloadKeepsTheHistoryAlreadyPagedInWhenANewMessageLands()
    {
        // 往回翻过的历史不能被"来了一条新消息"削掉：重取的范围要按已加载的条数放宽，而不是固定一页
        for (long seq = 1; seq <= 60; seq++) Seed(seq, $"m{seq}");
        var model = Model();
        model.Open(Channel, Grafana);
        model.LoadEarlier();
        Assert.Equal(60, model.Timeline().OfType<MessageItem>().Count());

        Seed(61, "m61");
        model.Reload();

        var seqs = model.Timeline().OfType<MessageItem>().Select(m => m.Seq).ToList();
        Assert.Equal(61, seqs.Count);
        Assert.Equal(1L, seqs[0]);
        Assert.Equal(61L, seqs[^1]);
    }

    [Fact]
    public void TheNewBelowPillCountsWhatLandedWhileTheReaderWasUpInTheHistory()
    {
        Seed(1, "m1");
        var model = Model();
        model.Open(Channel, Grafana);
        // 贴在底部时新消息直接跟滚，计数一条都不留（§9.3 的另一分支在窗口那侧，靠 Reload 的返回值分岔）
        Assert.Equal(0, model.Reload());

        Seed(2, "m2");
        Seed(3, "m3");
        Assert.Equal(2, model.Reload());
        Assert.Equal("2 条新消息", MainModel.NewBelowLabel(2));
        Assert.Equal("1 条新消息", MainModel.NewBelowLabel(1));

        // 已经数过的两条不会被再数一遍，同时上一条也没被这次重取挤出窗口
        Assert.Equal(0, model.Reload());
        Assert.Equal(3, model.Timeline().OfType<MessageItem>().Count());
    }

    [Fact]
    public void ABubbleIsSeventyTwoPercentOfTheRightPaneAndNeverWiderThanNineHundred()
    {
        // §9.2 的 72%：左栏那 320 像素里没有气泡，所以百分比吃的是右栏宽度
        Assert.Equal(547, MainModel.BubbleMaxWidth(760, false));
        Assert.Equal(900, MainModel.BubbleMaxWidth(2000, false));
        // 窄屏（单栏）放宽到 78%，还是同一道 900 封顶
        Assert.Equal(546, MainModel.BubbleMaxWidth(700, true));
        Assert.Equal(0, MainModel.BubbleMaxWidth(-1, false));
    }

    // ---- 搜索 ---------------------------------------------------------------------------------------

    [Fact]
    public void TypingSearchesLocallyWithoutTouchingTheNetwork()
    {
        Seed(1, "m1", body: "磁盘阈值告警");
        var handler = Server();
        var model = Model(handler);

        model.SearchLocal("阈值");

        Assert.True(model.IsSearching);
        Assert.Empty(handler.Requests);
        Assert.Equal(new[] { "m1" }, model.Results.Select(r => r.Id));
        // 本机那一段永远标"本机"，用户才知道点进去一定能定位
        Assert.Equal("本机", Assert.Single(model.Results).SourceLabel);
    }

    [Fact]
    public async Task SearchMergesBothHalvesAndNeverListsTheSameIdTwice()
    {
        Seed(1, "m1", body: "CPU 使用率高");
        var handler = Server(Hit(50, "m1"), Hit(51, "01SERVER"));
        var model = Model(handler);

        await model.SearchAsync("CPU");

        Assert.Equal(new[] { "m1", "01SERVER" }, model.Results.Select(r => r.Id));
        Assert.Equal(new[] { "本机", "服务端" }, model.Results.Select(r => r.SourceLabel));
        Assert.Null(model.SearchError);
        Assert.Single(handler.Requests);
    }

    [Fact]
    public async Task AFailedServerHalfStillKeepsTheLocalHalfAndSaysSo()
    {
        Seed(1, "m1", body: "CPU 使用率高");
        var model = Model(new FakeHandler(_ => Support.Reply(HttpStatusCode.Forbidden,
            Support.ErrorBody("rate_limited"))));

        await model.SearchAsync("CPU");

        Assert.Equal("m1", Assert.Single(model.Results).Id);
        Assert.NotNull(model.SearchError);
    }

    [Fact]
    public async Task OnlyThisConversationIsSearchedWhenTheScopeIsOn()
    {
        Seed(1, "m1", body: "阈值 在本会话");
        Seed(2, "m2", sender: "jenkins", body: "阈值 在另一个发送者");
        var handler = Server();
        var model = Model(handler);
        model.SearchInsideConversation = true;
        // 作用域取的是"当前打开的那条会话线"，所以先要开一条
        model.Open(Channel, Grafana);

        await model.SearchAsync("阈值");

        Assert.Equal("m1", Assert.Single(model.Results).Id);
        Assert.Contains("channel_no=83271504", handler.Requests[0].Query);
        Assert.Contains("sender_id=grafana", handler.Requests[0].Query);
    }

    [Fact]
    public void ExitingSearchClearsTheTermAndTheResults()
    {
        Seed(1, "m1", body: "阈值");
        var model = Model();
        model.SearchLocal("阈值");

        model.ExitSearch();

        Assert.False(model.IsSearching);
        Assert.Empty(model.Results);
        Assert.Equal(string.Empty, model.SearchTerm);
        Assert.Null(model.SearchError);
    }

    // ---- 顶栏与栏宽 ---------------------------------------------------------------------------------

    [Fact]
    public void ThePaneBreakIsTheDocumentedPaneWidth()
    {
        // 分界两侧只差 0.1：719.9 还退单栏，720 就摆满四栏
        Assert.True(MainModel.PaneLayoutFor(MainModel.NarrowPaneBelow - 0.1, false, 260).Narrow);
        Assert.False(MainModel.PaneLayoutFor(MainModel.NarrowPaneBelow, false, 260).Narrow);
    }

    [Fact]
    public void TheWidePaneKeepsAllFourColumnsAndClampsTheListWidth()
    {
        var layout = MainModel.PaneLayoutFor(1080, false, 260);

        Assert.False(layout.Narrow);
        Assert.Equal(MainModel.ColumnPlan.Of(MainModel.MenuColumnWidth), layout.Menu);
        Assert.Equal(MainModel.ColumnPlan.Of(260), layout.List);
        Assert.Equal(MainModel.ColumnPlan.Of(MainModel.SplitterColumnWidth), layout.Splitter);
        Assert.True(layout.Conversation.Star);
        // 宽屏时上下限要装回去，否则拖拽手柄能把列表拖成一条缝或半屏
        Assert.Equal(MainModel.ListMinWidth, layout.ListMinWidth);
        Assert.Equal(MainModel.ListMaxWidth, layout.ListMaxWidth);
        // 1080 - 60 - 260 - 4：气泡百分比吃的是这个数
        Assert.Equal(756, layout.RightPaneWidth);

        // 拖过头或拖太小都在模型这层就被夹住，窗口拿到的永远是"合法值"
        Assert.Equal(400, MainModel.PaneLayoutFor(1080, false, 5000).List.Width);
        Assert.Equal(200, MainModel.PaneLayoutFor(1080, false, 10).List.Width);
    }

    [Fact]
    public void TheNarrowPaneReleasesTheListBoundsSoCollapsingIsReal()
    {
        // 打开会话：列表收成 0。只是 Width=0 不够——XAML 的 MinWidth=200 会把它夹回 200px，
        // 所以这里必须同时看到上下限被放开
        var collapsed = MainModel.PaneLayoutFor(440, true, 260);
        Assert.True(collapsed.Narrow);
        Assert.Equal(MainModel.ColumnPlan.Of(0), collapsed.List);
        Assert.Equal(0, collapsed.ListMinWidth);
        Assert.Equal(MainModel.PaneLayout.NoMaxWidth, collapsed.ListMaxWidth);
        Assert.True(collapsed.Conversation.Star);
        Assert.Equal(380, collapsed.RightPaneWidth);

        // 没选会话：列表吃满。窄屏最宽能到 700-60=640，MaxWidth=400 会把它截在半路
        var listOnly = MainModel.PaneLayoutFor(700, false, 260);
        Assert.True(listOnly.List.Star);
        Assert.Equal(0, listOnly.ListMinWidth);
        Assert.Equal(MainModel.PaneLayout.NoMaxWidth, listOnly.ListMaxWidth);
        Assert.Equal(0, listOnly.Conversation.Width);
        Assert.False(listOnly.Conversation.Star);
    }

    [Fact]
    public void TheStartupWindowIsClampedToTheWorkArea()
    {
        // 1366×768@150% 的可见区约 910×512：写死的 1080×680 开窗就超出屏幕
        Assert.Equal(819, MainModel.FitToWorkArea(1080, 910));
        Assert.Equal(461, MainModel.FitToWorkArea(680, 512));
        // 屏幕够大时不该被缩小
        Assert.Equal(1080, MainModel.FitToWorkArea(1080, 1920));
        Assert.Equal(680, MainModel.FitToWorkArea(680, 1040));
        // 读不到工作区（无头环境/系统没给）时原样返回，不能把窗口压成 0
        Assert.Equal(1080, MainModel.FitToWorkArea(1080, 0));
    }

    [Fact]
    public void ConnectionStateBecomesOneSentence()
    {
        // 左侧菜单只有 60px 宽，正文一律两字：原来的"离线，正在重连（socket 断了）"会把这一栏撑成字墙
        Assert.Equal("在线", MainModel.DescribeState(new SyncState.Online(12L)));
        Assert.Equal("连接中", MainModel.DescribeState(new SyncState.Connecting()));
        Assert.Equal("离线",
            MainModel.DescribeState(new SyncState.Offline("socket 断了", TimeSpan.FromSeconds(4))));
        Assert.Equal("异常", MainModel.DescribeState(new SyncState.Blocked("服务端身份对不上")));
        Assert.Equal("未连接", MainModel.DescribeState(new SyncState.Idle()));

        // 正文变短不等于原因被丢掉：排障时"离线"和"为什么离线"是两件事，后半句挪到悬停提示里。
        // RetryIn 也在这里出口——顶栏不显示它，它就没有别的地方可去了
        Assert.Equal("离线（socket 断了，4 秒后重连）",
            MainModel.StateDetail(new SyncState.Offline("socket 断了", TimeSpan.FromSeconds(4))));
        Assert.Equal("异常（服务端身份对不上）",
            MainModel.StateDetail(new SyncState.Blocked("服务端身份对不上")));
        // 没有额外可说的状态就退回正文，免得悬停弹出一个和正文一模一样的框
        Assert.Equal("在线", MainModel.StateDetail(new SyncState.Online(12L)));
        Assert.Equal("离线", MainModel.StateDetail(new SyncState.Offline(string.Empty, TimeSpan.Zero)));

        // 三种状态三种颜色：在线绿、断链橙、要人处理的红；没启动是灰，不假装自己在连
        Assert.Equal("Accent", MainModel.StateToken(new SyncState.Online(1L)));
        Assert.Equal("State.Offline", MainModel.StateToken(new SyncState.Offline("x", TimeSpan.Zero)));
        Assert.Equal("State.Offline", MainModel.StateToken(new SyncState.Connecting()));
        Assert.Equal("Level.Error", MainModel.StateToken(new SyncState.Blocked("x")));
        Assert.Equal("Fg.Secondary", MainModel.StateToken(new SyncState.Idle()));
    }

    [Fact]
    public void TimestampsFollowTheAndroidFormats()
    {
        Assert.Equal("08:00", TimeFormat.Clock(Day0));
        Assert.Equal("12-12 08:00", TimeFormat.Stamp(Day0));
        Assert.Equal("20251212", TimeFormat.DayKey(Day0));
        // 今天/昨天用相对现在的时间戳，绝对串在跨年边界上会漂
        var now = DateTimeOffset.Now.ToUnixTimeMilliseconds();
        Assert.Equal("今天", TimeFormat.DateLabel(now));
        Assert.Equal("昨天", TimeFormat.DateLabel(now - DayMs));
        Assert.Equal(string.Empty, TimeFormat.DateLabel(0L));
        // 去年的同一天要带年份，否则"12月12日"会被读成今年
        Assert.Equal("2025年12月12日", TimeFormat.DateLabel(Day0));
    }

    // ---- 删除与清空（W14）---------------------------------------------------------------------------

    [Fact]
    public async Task DeletingAMessageTakesItOutOfTheOpenTimeline()
    {
        Seed(1, "m1");
        Seed(2, "m2");
        Seed(3, "m3");
        var model = Model(Removed());
        model.Open(Channel, Grafana);

        var result = await model.DeleteMessageAsync("m2");

        Assert.Equal(DeleteOutcome.Deleted, result.Outcome);
        // 屏幕上那条没了就是交代，成功不该再多说一句
        Assert.Null(result.Notice);
        Assert.Equal(new[] { "m1", "m3" },
            model.Timeline().OfType<MessageItem>().Select(i => i.MsgId).ToArray());
        // 删一条不该把人从正在看的对话里踢出去
        Assert.Equal(Grafana, model.Selected!.SenderId);
    }

    [Fact]
    public async Task TheScreenIsAlreadyThinWhenTheRequestLeaves()
    {
        Seed(1, "m1");
        Seed(2, "m2");
        Seed(3, "m3");
        // responder 是在请求上路那一刻被同步调起的，所以它能看见"乐观之后、确认之前"的那个窗口
        MainModel? atRequest = null;
        var seenAtRequest = -1;
        var handler = new FakeHandler(_ =>
        {
            seenAtRequest = atRequest!.Timeline().OfType<MessageItem>().Count();
            return Support.Reply(HttpStatusCode.OK, Support.SealRestResponse("{\"ok\":true,\"id\":\"m2\"}"));
        });
        var model = Model(handler);
        atRequest = model;
        model.Open(Channel, Grafana);

        await model.DeleteMessageAsync("m2");

        // 请求上路时已经只剩两条，等回答回来还是两条：中间没有一帧把这条闪回去过
        Assert.Equal(2, seenAtRequest);
        Assert.Equal(new[] { "m1", "m3" }, model.Timeline().OfType<MessageItem>().Select(i => i.MsgId).ToArray());
    }

    [Fact]
    public async Task ARefusedDeleteComesBackOnScreenWithASentence()
    {
        Seed(1, "m1");
        Seed(2, "m2");
        var model = Model(Refused());
        model.Open(Channel, Grafana);

        var result = await model.DeleteMessageAsync("m2");

        Assert.Equal(DeleteOutcome.RolledBack, result.Outcome);
        Assert.Contains("放回", result.Notice);
        // 服务端没答应，这条就还在屏幕上——本机偷偷藏起来会让两端各看一套
        Assert.Equal(2, model.Timeline().OfType<MessageItem>().Count());
    }

    [Fact]
    public async Task ClearingTheOpenConversationClosesTheRightPane()
    {
        Seed(1, "m1");
        Seed(2, "m2");
        var model = Model(Removed());
        model.Open(Channel, Grafana);

        Assert.Equal(DeleteOutcome.Deleted, (await model.ClearConversationAsync(Channel, Grafana)).Outcome);

        // 那条会话已经不在这台机器的列表里了，右栏跟着退回"没选中"而不是留一个空标题的窗口
        Assert.Null(model.Selected);
        Assert.Empty(model.Conversations());
    }

    [Fact]
    public async Task ClearingAnotherConversationLeavesTheOpenOneAlone()
    {
        Seed(1, "m1");
        Seed(2, "m2", sender: "deploy");
        var model = Model(Removed());
        model.Open(Channel, "deploy");

        await model.ClearConversationAsync(Channel, Grafana);

        Assert.Equal("deploy", model.Selected!.SenderId);
        Assert.Equal("deploy", Assert.Single(model.Conversations()).SenderId);
    }

    [Fact]
    public void TheTwoConfirmationsSayWhatTheAcceptanceNeeds()
    {
        // §9.3 的二次确认在模型里，所以这两段话能被钉住：要讲清"别的端会怎样""能不能撤销"，
        // 而 §14 那条底线是——确认框里永远不出现消息正文
        Assert.Equal("删除这条消息？", MainModel.DeleteMessageQuestion);
        Assert.Contains("tombstone", MainModel.DeleteMessageDetail);
        Assert.Contains("不可撤销", MainModel.DeleteMessageDetail);
        Assert.DoesNotContain("正文", MainModel.DeleteMessageDetail);

        // 清空点名的是哪一条要写在标题里：这一行右键下去清的是"这一条会话"，不是整个软件
        Assert.Equal("清空「监控告警」？", MainModel.ClearConversationQuestion("监控告警"));
        Assert.Contains("其他设备", MainModel.ClearConversationDetail);
        Assert.Contains("不可撤销", MainModel.ClearConversationDetail);
    }

    // ---- §7.3 的四项本机设置与 §5.2 的展开全文 -----------------------------------------------------

    [Fact]
    public void PinAndMuteMoveOnlyTheRowTheyWereCalledOn()
    {
        Seed(1, "m1");
        Seed(2, "m2", sender: "deploy");
        var model = Model();

        model.TogglePinned(Channel, Grafana);

        Assert.Equal(" · 置顶", Row(model, Grafana).FlagsText);
        Assert.Equal(string.Empty, Row(model, "deploy").FlagsText);
        // 再点一次是取消，不是"再置顶一层"（Android 的 conv_action_unpin 同一个动作）
        model.TogglePinned(Channel, Grafana);
        Assert.Equal(string.Empty, Row(model, Grafana).FlagsText);

        model.ToggleMuted(Channel, Grafana);
        Assert.Equal(" · 静音", Row(model, Grafana).FlagsText);
    }

    [Fact]
    public void RenamingShowsTheCustomNameAndAnEmptyOneFallsBackToTheSenders()
    {
        Seed(1, "m1");
        var model = Model();

        model.Rename(Channel, Grafana, "  监控告警  ");
        Assert.Equal("监控告警", Row(model, Grafana).DisplayName);

        // 空串不是"改成一个空名字"，是取消自定义、落回服务端下发的那个发送者名
        model.Rename(Channel, Grafana, "");
        Assert.Equal("Grafana", Row(model, Grafana).DisplayName);
    }

    [Fact]
    public void TheRowNamesItsAvatarObjectWithoutAskingTheServer()
    {
        Seed(1, "m1");
        // §4.5：头像对象的 token 就是 sender_id 的 sha256 十六进制小写，两端各自现算，
        // 所以本机"设头像"和远端转存的那一张能落在同一个键上，不靠 sender_avatar 那一列
        Assert.Equal(Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes(Grafana))).ToLowerInvariant(),
            Row(Model(), Grafana).AvatarToken);
    }

    [Fact]
    public void CancellingTheLocalAvatarLeavesTheRowWithNothingToLocalShow()
    {
        Seed(1, "m1");
        var model = Model();
        // 只断"取消"这一支：设头像那半要往 %LOCALAPPDATA%\PushBox\avatars 复制文件，
        // 用例不该在真机的应用目录里留一张测试图
        model.SetAvatar(Channel, Grafana, null);
        Assert.Null(Row(model, Grafana).AvatarPath);
    }

    [Fact]
    public async Task ATruncatedRowOffersTheActionLineAndSilencesItOnceTheBodyIsBack()
    {
        Seed(1, "m1", body: "前半截", truncated: true);
        var model = Model(Fulltext("完整正文在这里"));
        model.Open(Channel, Grafana);

        Assert.Equal("展开全文", OnlyMessage(model).ExpandLabel);

        Assert.True(await model.ExpandAsync("m1"));

        var after = OnlyMessage(model);
        Assert.Contains("完整正文在这里", after.Text);
        // 落库那次顺手把标记清了，所以这一行按钮消失，而不是"展开成一个已经展开的样子"
        Assert.Null(after.ExpandLabel);
    }

    [Fact]
    public async Task TheActionLineSaysItIsFetchingAndADoubleClickSendsOneRequest()
    {
        Seed(1, "m1", body: "前半截", truncated: true);
        var gate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var server = new FakeHandler(async (_, ct) =>
        {
            await gate.Task.WaitAsync(ct);
            return Support.Reply(HttpStatusCode.OK, Support.SealRestResponse(FulltextBody("完整正文在这里")));
        });
        var model = Model(server);
        model.Open(Channel, Grafana);

        var flying = model.ExpandAsync("m1");
        Assert.Equal("全文取回中…", OnlyMessage(model).ExpandLabel);
        // 还在飞的那一次没落地前，第二下是废操作
        Assert.False(await model.ExpandAsync("m1"));

        gate.SetResult();
        Assert.True(await flying);
        Assert.Single(server.Requests);
    }

    [Fact]
    public async Task ARefusedExpansionLeavesTheHalfBodyAndSaysNothing()
    {
        Seed(1, "m1", body: "前半截", truncated: true);
        var model = Model(new FakeHandler(_ => Support.Reply(HttpStatusCode.NotFound,
            Support.ErrorBody("not_found"))));
        model.Open(Channel, Grafana);

        Assert.False(await model.ExpandAsync("m1"));
        // 服务端没有更多可看：按钮放回原样，屏幕上还是那半截，不弹任何错误
        Assert.Equal("展开全文", OnlyMessage(model).ExpandLabel);
    }

    // ---- 空态的三态（§9.3）------------------------------------------------------------------------

    /// <summary>
    /// 机器刚开、库还是空的：这时候不能说"还没有频道"。回放可能正把历史取回来，
    /// 而"还没有消息"会被当成服务端的结论——用户接着就去手机上又建一个频道。
    /// </summary>
    [Fact]
    public void AnEmptySpaceSaysItIsLoadingUntilTheReplayFinishes()
    {
        var model = Model();

        Assert.Equal(MainModel.EmptyPhase.Loading, model.Phase);
        Assert.Equal("正在同步会话…", model.ConversationEmptyState.Title);
        Assert.Equal(MainModel.EmptyAction.None, model.ConversationEmptyState.Action);
        Assert.Equal("正在同步历史消息…", model.TimelineEmptyState.Title);
        // 加载中不许摆出"去建频道"或"重试"：前者让人白建一个，后者让人以为已经失败了
        Assert.DoesNotContain("还没有", model.ConversationEmptyState.Title);
    }

    /// <summary>回放跑到服务端末尾、库里确实一条都没有，这时候"空"才算成立。</summary>
    [Fact]
    public void AnEmptySpaceOnlySaysItIsEmptyOnceTheReplayHasFinished()
    {
        var model = Model();
        model.EndRestore(ReplayOutcome.Completed);

        Assert.Equal(MainModel.EmptyPhase.Ready, model.Phase);
        Assert.Equal("还没有频道", model.ConversationEmptyState.Title);
        Assert.Equal("在手机上创建频道，或用 curl 发送第一条消息", model.ConversationEmptyState.Detail);
        Assert.Equal(MainModel.EmptyAction.CreateChannel, model.ConversationEmptyState.Action);
        Assert.Equal("还没有消息", model.TimelineEmptyState.Title);
        Assert.Equal(MainModel.EmptyAction.None, model.TimelineEmptyState.Action);
    }

    /// <summary>没取回来和"没有"是两件事：前者该给人一颗重试，后者才该让人去建频道。</summary>
    [Fact]
    public void AFailedReplayOffersRetryInsteadOfPretendingTheSpaceIsEmpty()
    {
        var model = Model();
        model.EndRestore(ReplayOutcome.Failed);

        Assert.Equal(MainModel.EmptyPhase.Failed, model.Phase);
        Assert.Equal("会话没取回来", model.ConversationEmptyState.Title);
        Assert.Equal(MainModel.EmptyAction.Retry, model.ConversationEmptyState.Action);
        Assert.Equal("历史消息没取回来", model.TimelineEmptyState.Title);
        Assert.Equal(MainModel.EmptyAction.Retry, model.TimelineEmptyState.Action);

        // 点重试：先回到"正在取"，而不是继续挂着上一次的失败
        var raised = 0;
        model.Changed += () => raised++;
        model.BeginRestore();
        Assert.Equal(MainModel.EmptyPhase.Loading, model.Phase);
        Assert.Equal(MainModel.EmptyAction.None, model.ConversationEmptyState.Action);
        Assert.Equal(1, raised);

        // 已经在取的时候再叫一次不重画：重试按两下不该把列表刷两遍
        model.BeginRestore();
        Assert.Equal(1, raised);
    }

    /// <summary>
    /// 取消不是失败：退出空间/登出把回放掐了，窗口这会儿正在关。
    /// 把它当失败会留下一次无处可看的"重试"。
    /// </summary>
    [Fact]
    public void ACancelledReplayChangesNothingBecauseTheWindowIsAlreadyClosing()
    {
        var model = Model();
        model.EndRestore(ReplayOutcome.Completed);
        var raised = 0;
        model.Changed += () => raised++;

        model.EndRestore(ReplayOutcome.Cancelled);

        Assert.Equal(MainModel.EmptyPhase.Ready, model.Phase);
        Assert.Equal(0, raised);
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private MainModel Model(HttpMessageHandler? handler = null)
    {
        // 一个 handler、一个 DeviceClient：搜索、删除、全文三条链路走的是同一台"服务端"，
        // 用例传进来的 responder 因此能同时接住几种请求，请求次序也在同一份记录里
        var client = new DeviceClient(new HttpClient(handler ?? Server()));
        return new MainModel(_db, _identities, _reporter,
            new SearchService(_db, _identities, client),
            new DeleteReporter(_db, _identities, client, new MediaLoader()),
            new FulltextService(_db, _identities, client));
    }

    private void Seed(long seq, string id, string? title = null, string? summary = null,
        string? body = null, string level = "info", string type = "text", string sender = Grafana,
        long? createdAt = null, int mediaCount = 0, string? url = null, bool truncated = false) =>
        _db.ApplyMessage(new MessageFrame(seq, id, Channel, sender, "Grafana", null, type,
            title, summary, body, level, url, null,
            Enumerable.Range(0, mediaCount)
                .Select(i => new MediaSlot(i, "ready", null, null, 0L, null, null, null)).ToList(),
            true, createdAt ?? (Day0 + seq * 60_000), truncated));

    private static TombstoneFrame Tombstone(string json) =>
        (TombstoneFrame)FrameParser.Parse(json)!;

    /// <summary>服务端那一段的脚本：默认一条都不回，这样"合并"用例只对自己塞进去的那几条做断言。</summary>
    private static FakeHandler Server(params string[] hits) => new(_ => Support.Reply(HttpStatusCode.OK,
        Support.SealRestResponse("{\"results\":[" + string.Join(",", hits) + "],\"has_more\":false}")));

    /// <summary>W14 里"服务端答应了"那一支：密封盒正文里给个 deleted 计数，单条与清空两种回答都能吃。</summary>
    private static FakeHandler Removed() => new(_ => Support.Reply(HttpStatusCode.OK,
        Support.SealRestResponse("{\"ok\":true,\"deleted\":3,\"deleted_at\":1765500009000}")));

    /// <summary>§5.2 全文回捞那一条的应答：GET /message/{id} 的密封盒里就是一个消息对象。</summary>
    private static string FulltextBody(string content) =>
        "{\"seq\":1,\"id\":\"m1\",\"channel_no\":\"" + Channel + "\",\"sender_id\":\"" + Grafana
        + "\",\"type\":\"text\",\"content\":\"" + content + "\",\"level\":\"warning\","
        + "\"notify\":true,\"truncated\":false}";

    private static FakeHandler Fulltext(string content) => new(_ => Support.Reply(HttpStatusCode.OK,
        Support.SealRestResponse(FulltextBody(content))));

    private static ConversationItem Row(MainModel model, string senderId) =>
        model.Conversations().Single(c => c.SenderId == senderId);

    private static MessageItem OnlyMessage(MainModel model) =>
        Assert.Single(model.Timeline().OfType<MessageItem>());

    /// <summary>W14 里"服务端没答应"那一支：500 会走回滚，屏幕上那条得闪回来。</summary>
    private static FakeHandler Refused() => new(_ => Support.Reply((HttpStatusCode)500,
        Support.ErrorBody("server_error")));

    private static string Hit(long seq, string id) =>
        "{\"seq\":" + seq + ",\"id\":\"" + id + "\",\"channel_no\":\"" + Channel + "\","
        + "\"sender_id\":\"" + Grafana + "\",\"sender_name\":\"Grafana\",\"type\":\"text\","
        + "\"title\":\"\",\"snippet\":\"CPU 使用率\",\"level\":\"info\",\"created_at\":" + Day0 + "}";

    public void Dispose()
    {
        _db.Dispose();
        try
        {
            Directory.Delete(_dir, true);
        }
        catch (IOException)
        {
            // 句柄还挂在 OS 上：结论早在用例里拿到了
        }
    }
}
