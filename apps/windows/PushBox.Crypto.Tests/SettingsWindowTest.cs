using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using PushBox.Windows.Data;
using PushBox.Windows.Net;
using PushBox.Windows.Settings;
using PushBox.Windows.Views;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// §8.4 W16 的设置页。判定全在 <see cref="SettingsModel"/>（不进 WPF 类型），
/// 所以这一页大部分结论不需要把窗口立起来就能断到——只有"面板切换"和"取不到也要说话"两条要看窗口。
/// </summary>
public sealed class SettingsWindowTest : IDisposable
{
    private const string Channel = "83271504";

    private readonly string _dir;
    private readonly IdentityStore _identities;

    public SettingsWindowTest()
    {
        _dir = System.IO.Path.Combine(System.IO.Path.GetTempPath(),
            "pushbox-w16-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _identities = new IdentityStore(_dir);
        _identities.Save(new DeviceIdentity("dev_7f3a",
            Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_enc_priv")),
            Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_sign_seed")),
            "pbk_1a2b3c4d5e6f", "grp_001", "http://127.0.0.1:8081", null, 0L, "RC-AAAA-BBBB"));
    }

    // ---- 频道名与接入行 ------------------------------------------------------------------------------

    [Fact]
    public void AnEmptyChannelNameFallsBackToTheDeviceAndIsCapped()
    {
        Assert.Equal("dev_7f3a", SettingsModel.ChannelNameFor("   ", "dev_7f3a"));
        Assert.Equal("监控告警", SettingsModel.ChannelNameFor(" 监控告警 ", "dev_7f3a"));
        // Android 的 take(24)：名字是给人看的，不该有人贴一整段日志进来当频道名
        Assert.Equal(24, SettingsModel.ChannelNameFor(new string('字', 40), "dev_7f3a").Length);
        Assert.Equal("channel", SettingsModel.ChannelNameFor(null, null));
    }

    [Fact]
    public void TheIssuedLineCarriesTheBaseUrlTheDeviceAlreadyKnows()
    {
        var curl = new Issued("83271504", "sec_abc").CurlLine("http://127.0.0.1:8081/");
        // 入站接口是 POST {base}/message（不在 /api/v1 下），且 channel/secret 只认请求体：
        // 这两条任何一处写错，抄过去的那一行就是一句永远 401 的假接入命令。
        Assert.Contains("http://127.0.0.1:8081/message", curl);
        Assert.DoesNotContain("/api/v1/message", curl);
        Assert.Contains("\"channel\":\"83271504\"", curl);
        Assert.Contains("\"secret\":\"sec_abc\"", curl);
    }

    // ---- §14 治理补丁：留空与 0 是两件事 --------------------------------------------------------------

    [Fact]
    public void BlankGovernanceFieldsAreNotSubmitted()
    {
        var patch = SettingsModel.GovernancePatch("", null, "", "", "", false, false, false, "", "", "");
        // 名字留空 + 强度没选 + 六个数字格全空 + 三个开关关掉 → 只有那三个开关进正文
        var body = System.Text.Encoding.UTF8.GetString(patch.BuildBody()!);
        Assert.DoesNotContain("name", body);
        Assert.DoesNotContain("max_level", body);
        Assert.DoesNotContain("max_images", body);
        Assert.DoesNotContain("daily_bytes_limit", body);
        Assert.Contains("strict_auth", body);
        Assert.Contains("sender_whitelist_mode", body);
    }

    [Fact]
    public void ZeroIsASubmittedValueAndNegativeIsNot()
    {
        Assert.Equal(0, SettingsModel.PositiveInt("0"));
        Assert.Null(SettingsModel.PositiveInt("-1"));
        Assert.Equal(12, SettingsModel.PositiveInt(" 12 "));
        Assert.Null(SettingsModel.PositiveInt(""));
        Assert.Null(SettingsModel.PositiveInt("60秒"));
        Assert.Equal(0L, SettingsModel.NonNegativeInt("0"));
        Assert.Null(SettingsModel.NonNegativeInt(null));
    }

    [Fact]
    public void APatchWithNothingInItIsRefusedBeforeTheWire()
    {
        // 空正文的 PUT 在服务端会被当成"整列清空"来读，所以一个字段都没填时这一趟根本不该发出去
        var empty = SettingsModel.GovernancePatch("", null, "", "", "", false, false, false, "", "", "") with
        {
            StrictAuth = null, Muted = null, SenderWhitelistMode = null
        };
        Assert.Null(empty.BuildBody());
    }

    [Fact]
    public void TheChannelRowSaysWhatTheListShows()
    {
        var row = new ChannelRow(Channel, "", "warning", 4, 60, 32768, true, false, 0, 0, 0, true, false);
        Assert.Equal("未命名频道", row.TitleText);
        Assert.Equal("警告", row.LevelText);
        Assert.Contains("上限 警告", row.SummaryText);
        Assert.Contains("60 条/分", row.SummaryText);
        Assert.Contains("强制校验 sender", row.SummaryText);
        Assert.Contains("只允许白名单", row.SummaryText);
        Assert.DoesNotContain("频道静默", row.SummaryText);
        // §5.2 接入是设备级的：这一行的徽章说的是"这台电脑"，不是"这个频道"
        Assert.Equal("未接入", row.SubscriptionBadge);
        Assert.Contains("只接收已接入的频道", row.SubscriptionText);
        Assert.Equal("已接入", (row with { Subscribed = true }).SubscriptionBadge);
    }

    [Fact]
    public void FilteringWithNothingJoinedIsTheOneSilentStateWorthShoutingAbout()
    {
        var off = new ChannelPage(new[] { new ChannelRow(Channel, "监控", "info", 4, 60, 32768,
            false, false, 0, 0, 0, false, false) }, false);
        // 开关关着＝全收，这条频道没接入也不算异常，界面不该吓人
        Assert.Equal(string.Empty, off.FilterWarning);

        var onButEmpty = new ChannelPage(Array.Empty<ChannelRow>(), true);
        Assert.Contains("收不到任何消息", onButEmpty.FilterWarning);
        Assert.Equal(0, onButEmpty.JoinedCount);

        var onWithOne = new ChannelPage(new[] { off.Rows[0] with { Subscribed = true } }, true);
        Assert.Equal(1, onWithOne.JoinedCount);
        Assert.Equal(string.Empty, onWithOne.FilterWarning);
        // 接入关系与这个开关是两件事：关开关不清订阅，接频道也不开开关
        var onWithoutAny = new ChannelPage(new[] { off.Rows[0] }, true);
        Assert.Equal(0, onWithoutAny.JoinedCount);
    }

    [Fact]
    public void TheCapacityReportIsReadableNotJustNumbers()
    {
        var line = SettingsModel.CapacityLine(new Capacity(1234, 4096, 1765500009000, 12, 2 * 1024 * 1024));
        Assert.Contains("消息 1234 条", line);
        Assert.Contains("正文 4 KB", line);
        Assert.Contains("图片 12 张", line);
        Assert.Contains("2 MB", line);
        Assert.Equal("0 B", SettingsModel.BytesLabel(0));
        Assert.Equal("512 B", SettingsModel.BytesLabel(512));
        Assert.Equal("1.5 MB", SettingsModel.BytesLabel(1536 * 1024));
    }

    [Fact]
    public void AnEmptyWhitelistWithTheModeOnIsTheOneThingWorthShoutingAbout()
    {
        Assert.Equal(string.Empty, SettingsModel.WhitelistWarning(false, 0));
        Assert.Equal(string.Empty, SettingsModel.WhitelistWarning(true, 2));
        Assert.Contains("名单", SettingsModel.WhitelistWarning(true, 0));
    }

    // ---- 设备行 -------------------------------------------------------------------------------------

    [Fact]
    public void OnlyAnotherLiveDeviceCanBeRevoked()
    {
        Assert.False(new DeviceRow("dev_self", "这台", "windows", 0, 0, true, false).CanRevoke);
        Assert.False(new DeviceRow("dev_old", "那台", "android", 0, 0, false, true).CanRevoke);
        Assert.True(new DeviceRow("dev_phone", "手机", "android", 0, 0, false, false).CanRevoke);
        // 本机也被吊销过的时候，说话的是"已吊销"而不是"当前设备"
        Assert.Equal("已吊销", new DeviceRow("dev_self", "这台", "windows", 0, 0, true, true).StatusText);
        Assert.Equal("当前设备", new DeviceRow("dev_self", "这台", "windows", 0, 0, true, false).StatusText);
    }

    [Fact]
    public void AWhitelistRowSaysWhichKindOfAbsenceItIs()
    {
        Assert.Equal("已允许", new SenderEntry("grafana", true).StatusText);
        Assert.Equal("已停用", new SenderEntry("grafana", false).StatusText);
    }

    // ---- 静默时段 -----------------------------------------------------------------------------------

    [Fact]
    public void TheQuietWindowRoundTripsThroughItsTwoBoxes()
    {
        Assert.Equal("22:00", SettingsModel.ClockOf(ClientSettings.DefaultQuietStart));
        Assert.Equal(22 * 60, SettingsModel.MinuteOf("22:00"));
        Assert.Null(SettingsModel.MinuteOf("25:00"));
        Assert.Null(SettingsModel.MinuteOf("8"));
        Assert.Null(SettingsModel.MinuteOf(string.Empty));
        Assert.Equal(8 * 60 + 5, SettingsModel.MinuteOf("08:05"));
        // 跨午夜要说清是到次日，否则 22:00–08:00 看着像写反了
        Assert.Equal("22:00–08:00（次日）", SettingsModel.QuietLine(true, 22 * 60, 8 * 60));
        Assert.Equal("09:00–12:00", SettingsModel.QuietLine(true, 9 * 60, 12 * 60));
        Assert.Equal("未开启", SettingsModel.QuietLine(false, 22 * 60, 8 * 60));
    }

    [Fact]
    public void ABoxTypedWrongKeepsTheWindowItReplacedAndSaysSo()
    {
        var current = ClientSettings.Defaults.WithQuietWindow(true, 22 * 60, 8 * 60);
        var edited = SettingsModel.QuietEditOf(current, "09:30", "填歪了");
        Assert.Equal(9 * 60 + 30, edited.Settings.QuietStartMinute);
        Assert.Equal(8 * 60, edited.Settings.QuietEndMinute);
        // 开关不是这两格能动的：改起止不该顺手把静默打开或关掉
        Assert.True(edited.Settings.QuietEnabled);
        // 回退本身原来就在做，缺的是把"改回了什么"说出来
        Assert.False(edited.StartBad);
        Assert.True(edited.EndBad);
        Assert.Contains("HH:mm", SettingsModel.QuietHintOf(edited));
        Assert.Contains("08:00", SettingsModel.QuietHintOf(edited));

        var both = SettingsModel.QuietEditOf(current, "25:00", string.Empty);
        Assert.True(both.StartBad);
        Assert.True(both.EndBad);
        Assert.Equal(22 * 60, both.Settings.QuietStartMinute);
        Assert.Equal(8 * 60, both.Settings.QuietEndMinute);
        Assert.Contains("22:00", SettingsModel.QuietHintOf(both));
        Assert.Contains("08:00", SettingsModel.QuietHintOf(both));

        // 两格都好就一句话都不用说，提示要收起来
        var good = SettingsModel.QuietEditOf(current, "21:15", "07:45");
        Assert.False(good.StartBad || good.EndBad);
        Assert.Equal(string.Empty, SettingsModel.QuietHintOf(good));
        Assert.Equal(21 * 60 + 15, good.Settings.QuietStartMinute);
        Assert.Equal(7 * 60 + 45, good.Settings.QuietEndMinute);
    }

    [Fact]
    public void AFailureSaysSomethingTheUserCanActOn()
    {
        // 设置页的每一格失败都要落成人话，不能只留一个转圈的按钮
        Assert.Equal("已保存", Outcome<string>.Good("已保存").Value);
        Assert.Contains("8081", Outcome<string>.Describe(
            new HttpRequestException("连接被拒 http://127.0.0.1:8081")));
        Assert.Contains("超时", Outcome<string>.Describe(new TaskCanceledException()));
        Assert.NotEqual(string.Empty, Outcome<string>.Describe(new PushBoxApiException(
            HttpStatusCode.TooManyRequests, "rate_limited", "每分钟条数已达上限")));
    }

    // ---- 网络那几支：错误进得来、也出得去 ------------------------------------------------------------

    [Fact]
    public async Task ChannelsAreRefusedLoudlyWhenTheServerIsNotThere()
    {
        var model = Offline();
        var result = await model.ChannelsAsync();
        Assert.False(result.Ok);
        Assert.NotEqual(string.Empty, result.Error);
        Assert.Null(result.Value);
    }

    [Fact]
    public async Task GovernanceSavingAnEmptyPatchNeverReachesTheWire()
    {
        var model = Offline();
        var empty = SettingsModel.GovernancePatch("", null, "", "", "", false, false, false, "", "", "") with
        {
            StrictAuth = null, Muted = null, SenderWhitelistMode = null
        };
        var saved = await model.SaveGovernanceAsync(Channel, empty);
        Assert.False(saved.Ok);
    }

    [Fact]
    public async Task ABlankSenderIdIsRejectedBeforeTheRequest()
    {
        var saved = await Offline().PutSenderAsync(Channel, "   ", true);
        Assert.False(saved.Ok);
        Assert.Contains("sender_id", saved.Error);
    }

    [Fact]
    public async Task ListedChannelsBecomeRowsAndCarryTheirWhitelist()
    {
        // 两个端点各有各的应答：这一页的列表和白名单是同一次打开里先后发的两趟请求，
        // 用一个固定 responder 会让第二趟读到第一趟的正文
        var model = new SettingsModel(new DeviceClient(new HttpClient(new FakeHandler(request =>
            Support.Reply(HttpStatusCode.OK, Support.SealRestResponse(
                request.Path.EndsWith("/senders", StringComparison.Ordinal) ? SendersJson() : ChannelsJson()))))),
            _identities);

        var list = await model.ChannelsAsync();
        Assert.True(list.Ok, list.Error);
        var row = Assert.Single(list.Value!.Rows);
        Assert.Equal("监控告警", row.TitleText);
        Assert.Equal(60, row.RateLimit);
        Assert.True(row.StrictAuth);
        Assert.True(row.Subscribed);
        // 过滤模式取的是响应根上那一列：它写在 devices 表上，是这台设备的事实而不是这条频道的事实
        Assert.True(list.Value.SubscribedOnly);
        Assert.Equal(string.Empty, list.Value.FilterWarning);

        var senders = await model.SendersAsync(Channel);
        var sender = Assert.Single(senders.Value!);
        Assert.Equal("grafana", sender.SenderId);
        Assert.Equal("已停用", sender.StatusText);
    }

    [Fact]
    public async Task AJoinWithoutBothHalvesNeverReachesTheWire()
    {
        var missingSecret = await Offline().JoinChannelAsync(Channel, "   ");
        Assert.False(missingSecret.Ok);
        Assert.Contains("密钥", missingSecret.Error);

        // 服务端把过长的号码静默截到 8 位再查：多抄一位就变成"去查了另一个频道"，
        // 回一个 404 只会让人以为自己抄的号错了
        var shortNo = await Offline().JoinChannelAsync("8327150", "sec");
        Assert.False(shortNo.Ok);
        Assert.Contains("8 位", shortNo.Error);
        var longNo = await Offline().JoinChannelAsync(Channel + "9", "sec");
        Assert.False(longNo.Ok);
    }

    [Fact]
    public async Task JoiningAndLeavingReportTheServerTheirOwnWay()
    {
        var model = new SettingsModel(new DeviceClient(new HttpClient(new FakeHandler(_ => Support.Reply(
            HttpStatusCode.OK, Support.SealRestResponse("{\"ok\":true,\"channel_no\":\"" + Channel + "\"}"))))),
            _identities);

        var joined = await model.JoinChannelAsync(" " + Channel + " ", " sec_abc ");
        Assert.True(joined.Ok, joined.Error);
        Assert.Contains("已接入", joined.Value);
        // 取消接入在服务端是幂等的：本来没接入也回成功，界面按"这台不再带它"报，而不是报一次失败
        var left = await model.LeaveChannelAsync(Channel);
        Assert.True(left.Ok, left.Error);
        Assert.Equal("已取消接入", left.Value);
    }

    [Fact]
    public async Task TheFilterSwitchShowsTheServersAnswerNotOurOwnGuess()
    {
        // 我们要求开（true），服务端回的是 0：界面最终显示的必须是回显的那一档
        var model = new SettingsModel(new DeviceClient(new HttpClient(new FakeHandler(_ => Support.Reply(
            HttpStatusCode.OK, Support.SealRestResponse("{\"ok\":true,\"subscribed_only\":0}"))))), _identities);
        var done = await model.SetSubscriptionModeAsync(true);
        Assert.True(done.Ok, done.Error);
        Assert.False(done.Value);
    }

    [Fact]
    public async Task RecoveryCodeRotationEchoesTheNewCodeOnce()
    {
        var model = new SettingsModel(new DeviceClient(new HttpClient(new FakeHandler(_ => Support.Reply(
            HttpStatusCode.OK, Support.SealRestResponse("{\"recovery_code\":\"RC-NEW-0001\"}"))))), _identities);
        var rotated = await model.RotateRecoveryCodeAsync();
        Assert.Equal("RC-NEW-0001", rotated.Value);
        // 本机没存过恢复码的那一支要说清"这里没有可抄的东西"，而不是留一格空白让人以为已经抄完了
        Assert.Equal("本机未保存恢复码", SettingsModel.RecoveryText(null));
        Assert.Equal("RC-AAAA-BBBB", SettingsModel.RecoveryText("RC-AAAA-BBBB"));
    }

    // ---- 窗口本身 -----------------------------------------------------------------------------------

    [Fact]
    public void TheWindowOpensOnTheChannelsPageAndSwitchesByTheLeftRail()
    {
        Sta.Run(() =>
        {
            var window = new SettingsWindow(Offline(), ClientSettings.Defaults, _ => { });
            Assert.Equal("channels", window.ProbePanel);
            Assert.False(window.ProbeGovernanceVisible);
            Assert.False(window.ProbeSendersVisible);

            window.ShowPanel("devices");
            Assert.Equal("devices", window.ProbePanel);
            window.ShowPanel("notify");
            Assert.Equal("notify", window.ProbePanel);
            window.ShowPanel("channels");
            Assert.Equal("channels", window.ProbePanel);

            window.Close();
        });
    }

    [Fact]
    public void AWindowThatCannotReachTheServerSaysSoInsteadOfShowingNothing()
    {
        Sta.Run(() =>
        {
            var window = new SettingsWindow(Offline(), ClientSettings.Defaults, _ => { });
            // 装载是开扇就发生的（不等人去点"刷新"），所以取不到的那句人话也得自己浮上来
            Sta.PumpUntil(() => window.ProbeNotice.Length > 0);
            Assert.NotEqual(string.Empty, window.ProbeNotice);
            Assert.Equal(0, window.ProbeChannelCount);
            Assert.Equal("未开启", window.ProbeQuietLine);
            // 取不到列表时那两格停在"没开、没话说"：勾不是本机记的，没回显就不该自己亮起来
            Assert.False(window.ProbeFilterOnlyChecked);
            Assert.Equal(string.Empty, window.ProbeFilterWarning);
            Assert.False(window.ProbeLeaveButtonVisible);
            window.Close();
        });
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    /// <summary>一条永远连不上的服务端：这一页所有"网络没成"的分支都要能在没有 8081 的机器上被走到。</summary>
    private SettingsModel Offline() => new(new DeviceClient(
        new HttpClient(new ThrowingHandler(new HttpRequestException("不该发请求")))), _identities);

    private static string ChannelsJson() =>
        "{\"channels\":[{\"channel_no\":\"" + Channel + "\",\"name\":\"监控告警\",\"max_level\":\"warning\"," +
        "\"max_images\":4,\"rate_limit\":60,\"max_body_bytes\":32768,\"strict_auth\":1,\"muted\":0," +
        "\"daily_msg_limit\":0,\"daily_image_limit\":0,\"daily_bytes_limit\":0,\"sender_whitelist_mode\":0," +
        // 服务端给 subscribed 放的是布尔，不是 0/1 —— 与治理位那几列写法不同，解析要两种都认
        "\"subscribed\":true}],\"subscribed_only\":1}";

    private static string SendersJson() =>
        "{\"senders\":[{\"sender_id\":\"grafana\",\"enabled\":0}]}";

    public void Dispose()
    {
        try
        {
            Directory.Delete(_dir, true);
        }
        catch (Exception)
        {
            // 结论早在用例里拿到了
        }
    }
}
