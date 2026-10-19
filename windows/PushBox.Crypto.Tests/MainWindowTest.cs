using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Threading;
using PushBox.Windows.Data;
using PushBox.Windows.Media;
using PushBox.Windows.Net;
using PushBox.Windows.Sync;
using PushBox.Windows.Views;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// XAML 那一层的闸口：<c>x:Name</c> 对不上字段、面板初始态不对、模板/转换器没挂上，
/// 这些都只有真把窗口对象建出来才看得见。建完就丢，不 Show——与 <see cref="FirstLaunchWindowTest"/> 同一手法，
/// "该显示什么"的判定全在 <see cref="MainModelTest"/> 那一页断，这里只断"骨架立没立起来"。
/// </summary>
public sealed class MainWindowTest : IDisposable
{
    private static readonly byte[] DeviceEncPriv =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_enc_priv"));

    private static readonly byte[] DeviceSignSeed =
        Support.Hex(Support.Str(Support.Vectors.GetProperty("inputs"), "device_sign_seed"));

    private readonly string _dir;
    private readonly IdentityStore _identities;
    private readonly DeviceStore _db;

    public MainWindowTest()
    {
        _dir = Path.Combine(Path.GetTempPath(), "pushbox-w12-window-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
        _identities = new IdentityStore(_dir);
        _identities.Save(new DeviceIdentity("dev_7f3a", DeviceEncPriv, DeviceSignSeed,
            "pbk_1a2b3c4d5e6f", "grp_001", "http://127.0.0.1:8081", null, 0L));
        _db = new DeviceStore(Path.Combine(_dir, "pushbox.db"));
    }

    public void Dispose()
    {
        _db.Dispose();
        try
        {
            Directory.Delete(_dir, true);
        }
        catch (IOException)
        {
            // 临时目录清不干净不影响这一条的结论
        }
    }

    [Fact]
    public void TheMainWindowBuildsFromXamlInTheIdleState()
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var reporter = new ReadReporter(_db, _identities, Offline());
                var window = new MainWindow(_db, _identities, reporter,
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader());

                // 一条会话都没打开：右栏是那句"从左侧选一条会话线"，筛选条与胶囊都还没话可说
                Assert.Equal(Visibility.Visible, window.NoConversationProbe);
                Assert.Equal(Visibility.Collapsed, window.FilterBarProbe);
                Assert.Equal(Visibility.Collapsed, window.NewBelowProbe);
                // W14 的回滚提示条同样是"没话可说"的那一个：它只在删除失败时才出现
                Assert.Equal(Visibility.Collapsed, window.NoticeProbe);
                // 列表是默认视图（搜索输入框已在 #45 移除）
                Assert.Equal(Visibility.Visible, window.ConversationListProbe);
                // 顶栏在"没状态"时不留一个孤零零的点
                Assert.Equal(Visibility.Collapsed, window.StatusDotProbe);
                // 会话行的模板与选中态样式都得挂上，否则列表会退成一行 ToString()
                Assert.True(window.HasRowTemplate);
                Assert.True(window.HasRowContainerStyle);
                window.Close();
            }
            catch (Exception e)
            {
                failure = e;
            }
            finally
            {
                Dispatcher.CurrentDispatcher.InvokeShutdown();
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;

        thread.Start();
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "构造主窗口没有按时完成");
        Assert.True(failure is null, failure?.ToString());
    }

    /// <summary>网络这条支路一律当场失败：这里测的是骨架，不是任何一次请求。</summary>
    private static DeviceClient Offline() => new(new HttpClient(new ThrowingHandler(
        new HttpRequestException("骨架用例不该发请求"))));

    /// <summary>W14 的删除上报：构造窗口时要把它交进去，而骨架用例从不点菜单，所以它一次也不会发请求。</summary>
    private DeleteReporter Deletions() => new(_db, _identities, Offline(), new MediaLoader());

    [Fact]
    public void ATargetFromAToastSelectsThatConversationAndRepaintsTheTray()
    {
        // §8.4 W13 验收①在窗口这一侧的落点：URI 解出来的那一条要真的被选出来，
        // 而 tooltip 上的未读数归窗口算——托盘自己再数一遍就会有两套口径
        _db.ApplyMessage(Msg(1, "m1", Grafana));
        _db.ApplyMessage(Msg(2, "m2", "deploy"));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader());
                var refreshes = 0;
                window.TrayRefresh += () => refreshes++;

                Assert.Equal(2, window.TrayUnread);
                // 没有引擎就没有连接状态可报：托盘那一行此时只剩应用名 + 未读
                Assert.Equal(string.Empty, window.TrayState);

                // 通知上那颗"打开会话"落地的就是这一句
                window.OpenConversation(Channel, Grafana);
                Assert.Equal(Grafana, window.SelectedConversationProbe!.SenderId);
                // 打开即清红点（W9 那条），总数跟着掉，而另一条会话的未读不受影响
                Assert.Equal(1, window.TrayUnread);
                Assert.True(refreshes >= 1, "重画没有通知到托盘");

                // 别的来源改了库（"全部已读"、他端的 read 帧）之后只需要一次 Repaint
                window.Repaint();
                Assert.True(refreshes >= 2, "Repaint 没有通知到托盘");

                window.Close();
            }
            catch (Exception e)
            {
                failure = e;
            }
            finally
            {
                Dispatcher.CurrentDispatcher.InvokeShutdown();
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;

        thread.Start();
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "走一遍通知落点没有按时完成");
        Assert.True(failure is null, failure?.ToString());
    }

    private const string Channel = "83271504";
    private const string Grafana = "grafana";

    // ---- W14：确认、提示条与"取消就一条都不动" --------------------------------------------------------

    [Fact]
    public void ACancellationAtTheConfirmationSendsNothing()
    {
        _db.ApplyMessage(Msg(1, "m1", Grafana));
        _db.ApplyMessage(Msg(2, "m2", Grafana));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var handler = new FakeHandler(_ => Support.Reply(HttpStatusCode.OK,
                    Support.SealRestResponse("{\"ok\":true,\"id\":\"m1\"}")));
                var window = Window(handler);
                window.Confirm = (_, _) => false;
                window.OpenConversation(Channel, Grafana);

                Await(window.Dispatcher, window.DeleteMessageFromUiAsync("m1"));

                // §9.3 的二次确认不是形式：说"不"的那一次连请求都不该发出去
                Assert.Empty(handler.Requests);
                Assert.Equal(2, _db.MessagesPage(Channel, Grafana, long.MaxValue, 10).Count);
                Assert.Equal(Visibility.Collapsed, window.NoticeProbe);
                window.Close();
            }
            catch (Exception e)
            {
                failure = e;
            }
            finally
            {
                Dispatcher.CurrentDispatcher.InvokeShutdown();
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;

        thread.Start();
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "取消确认没有按时完成");
        Assert.True(failure is null, failure?.ToString());
    }

    [Fact]
    public void ARefusedDeleteSpeaksThroughTheNoticeBarAndASuccessTakesItAway()
    {
        _db.ApplyMessage(Msg(1, "m1", Grafana));
        _db.ApplyMessage(Msg(2, "m2", Grafana));
        var refused = true;
        var handler = new FakeHandler(_ => refused
            ? Support.Reply(HttpStatusCode.InternalServerError, Support.ErrorBody("server_error"))
            : Support.Reply(HttpStatusCode.OK, Support.SealRestResponse("{\"ok\":true,\"id\":\"m1\"}")));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = Window(handler);
                window.Confirm = (_, _) => true;
                window.OpenConversation(Channel, Grafana);

                Await(window.Dispatcher, window.DeleteMessageFromUiAsync("m1"));

                // 回滚不是"悄悄把消息放回去"：屏幕上那条重新出现之外，还得说一句为什么
                Assert.Equal(Visibility.Visible, window.NoticeProbe);
                Assert.Contains("放回", window.NoticeTextProbe);
                Assert.Contains("500", window.NoticeTextProbe);
                Assert.Equal(2, _db.MessagesPage(Channel, Grafana, long.MaxValue, 10).Count);

                // 第二次成了：提示条不能留着那句已经过时的话
                refused = false;
                Await(window.Dispatcher, window.DeleteMessageFromUiAsync("m1"));

                Assert.Equal(Visibility.Collapsed, window.NoticeProbe);
                Assert.Equal(2, handler.Requests.Count);
                Assert.All(handler.Requests, request => Assert.Equal("DELETE", request.Method));
                Assert.Single(_db.MessagesPage(Channel, Grafana, long.MaxValue, 10));
                window.Close();
            }
            catch (Exception e)
            {
                failure = e;
            }
            finally
            {
                Dispatcher.CurrentDispatcher.InvokeShutdown();
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;

        thread.Start();
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "删除的两种结局没有按时走完");
        Assert.True(failure is null, failure?.ToString());
    }

    [Fact]
    public void ClearingTheWholeChannelIsReachableFromTheConversationRow()
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader());
                // 清空与 §7.3 那几项都挂在会话行模板上（不是右上角那个 ⋮），所以它们得真的被建出来
                Assert.Equal(["pin", "mute", "rename", "avatar", "clear"], window.RowMenuTags.ToArray());
                window.Close();
            }
            catch (Exception e)
            {
                failure = e;
            }
            finally
            {
                Dispatcher.CurrentDispatcher.InvokeShutdown();
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;

        thread.Start();
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "读会话行模板没有按时完成");
        Assert.True(failure is null, failure?.ToString());
    }

    /// <summary>把窗口建在给定"服务端"上：三个上报者共用同一个 handler，用例数请求时不必区分是谁发的。</summary>
    private MainWindow Window(FakeHandler handler)
    {
        var client = new DeviceClient(new HttpClient(handler));
        var media = new MediaLoader();
        return new MainWindow(_db, _identities, new ReadReporter(_db, _identities, client),
            new SearchService(_db, _identities, client),
            new DeleteReporter(_db, _identities, client, media), media);
    }

    /// <summary>
    /// 把窗口那一发走完。UI 线程既是请求的发源地又是续延的落点，所以等它只能泵 Dispatcher——
    /// <c>Wait()</c>／<c>Result</c> 在这里就是当场死锁（用例挂三十秒比挂一条断言更难查）。
    /// </summary>
    private static void Await(Dispatcher dispatcher, Task work)
    {
        var frame = new DispatcherFrame();
        work.ContinueWith(_ => dispatcher.BeginInvoke(new Action(() => frame.Continue = false)));
        Dispatcher.PushFrame(frame);
        work.GetAwaiter().GetResult();
    }

    private static MessageFrame Msg(long seq, string id, string sender) => new(
        seq, id, Channel, sender, "Grafana", null, "text", null, "摘要", "正文", "info", null, null,
        Array.Empty<MediaSlot>(), true, 1_765_500_000_000L + seq, false);
}
