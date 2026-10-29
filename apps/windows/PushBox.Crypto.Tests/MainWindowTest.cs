using System;
using System.Collections.Specialized;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using PushBox.Windows.Controls;
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

                // 一条会话都没打开：右栏是那句"从左侧选一条会话线"，胶囊也还没话可说
                Assert.Equal(Visibility.Visible, window.NoConversationProbe);
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

    /// <summary>
    /// 恢复几何这条路要真的落到窗口上：模型算得再对，忘了把结果抄给 <c>Width</c>/<c>Left</c> 就等于没做。
    /// 用的矩形刻意与默认值（1080×680 再夹工作区）不同，否则"根本没恢复"也能过。
    /// </summary>
    [Fact]
    public void ARestoredGeometryIsAppliedToTheWindow()
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var saved = new WindowGeometry(120, 80, 700, 500, 320, false);
                var reporter = new ReadReporter(_db, _identities, Offline());
                var window = new MainWindow(_db, _identities, reporter,
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader(),
                    engine: null, restore: saved);

                // 这一台的工作区装得下 700×500，所以恢复值应当原样落上去；被夹掉了说明抄错了地方
                var expected = WindowPlacement.Restore(saved, SystemParameters.WorkArea,
                    new Rect(SystemParameters.VirtualScreenLeft, SystemParameters.VirtualScreenTop,
                        SystemParameters.VirtualScreenWidth, SystemParameters.VirtualScreenHeight),
                    window.MinWidth, window.MinHeight);
                Assert.Equal(expected.Width, window.Width);
                Assert.Equal(expected.Height, window.Height);
                Assert.Equal(expected.Left, window.Left);
                Assert.Equal(expected.Top, window.Top);

                // 关窗（这里没有"收到托盘"那一层）要把它当前这一份几何交出去，否则下一版永远是默认尺寸
                WindowGeometry? reported = null;
                window.GeometryChanged += g => reported = g;
                window.Close();
                Assert.True(reported is { }, "关窗没有把几何交出去");
                Assert.Equal(320d, reported!.Value.ListWidth);
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
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "恢复几何没有按时完成");
        Assert.True(failure is null, failure?.ToString());
    }

    /// <summary>
    /// 窄屏收列表的根因在 WPF 这一侧：<c>ColumnDefinition.Width=0</c> 会被 <c>MinWidth=200</c> 夹回 200px，
    /// 于是"收掉列表"只是纸面上的，对话区白让 200px。这条用例把模型算出来的列宽与上下限真摆进 Grid，
    /// 断的是字面上的 0——只断模型数值的话，把 Min/Max 忘了抄上去照样能过。
    /// </summary>
    [Fact]
    public void TheModelPaneLayoutCollapsesTheListColumnForReal()
    {
        static GridLength AsLength(MainModel.ColumnPlan plan) =>
            plan.Star ? new GridLength(1, GridUnitType.Star) : new GridLength(plan.Width);

        static Grid Place(MainModel.PaneLayout plan, double width)
        {
            var grid = new Grid();
            foreach (var _ in new[] { plan.Menu, plan.List, plan.Splitter, plan.Conversation })
                grid.ColumnDefinitions.Add(new ColumnDefinition());
            var columns = grid.ColumnDefinitions;
            columns[0].Width = AsLength(plan.Menu);
            columns[1].Width = AsLength(plan.List);
            columns[1].MinWidth = plan.ListMinWidth;
            columns[1].MaxWidth = plan.ListMaxWidth;
            columns[2].Width = AsLength(plan.Splitter);
            columns[3].Width = AsLength(plan.Conversation);
            grid.Measure(new Size(width, 100));
            grid.Arrange(new Rect(0, 0, width, 100));
            return grid;
        }

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                // 窄屏 + 已打开会话：列表是 0 而不是被 MinWidth 夹住的 200
                var collapsed = Place(MainModel.PaneLayoutFor(440, true, 260), 440);
                Assert.Equal(0, collapsed.ColumnDefinitions[1].ActualWidth);
                Assert.Equal(380, collapsed.ColumnDefinitions[3].ActualWidth);

                // 窄屏 + 没选会话：列表吃满 640，不被 MaxWidth=400 截住
                var listOnly = Place(MainModel.PaneLayoutFor(700, false, 260), 700);
                Assert.Equal(640, listOnly.ColumnDefinitions[1].ActualWidth);

                // 回到宽屏：上下限装回去，列表还是用户拖过的那个绝对宽
                var wide = Place(MainModel.PaneLayoutFor(1080, false, 260), 1080);
                Assert.Equal(260, wide.ColumnDefinitions[1].ActualWidth);
                Assert.Equal(756, wide.ColumnDefinitions[3].ActualWidth);
            }
            catch (Exception e)
            {
                failure = e;
            }
        });
        thread.SetApartmentState(ApartmentState.STA);
        thread.IsBackground = true;

        thread.Start();
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "摆列没有按时完成");
        Assert.True(failure is null, failure?.ToString());
    }

    /// <summary>
    /// 三态在窗口这一侧的落点：模型给的句子与按钮去向要真的抄到控件上，换态之后要跟着重画，点"重试"要
    /// 把话交回装配层。这一条必须 Show——<c>Refresh</c> 在 <c>IsLoaded</c> 之前整段跳过（自启那轮靠的就是它）。
    /// </summary>
    [Fact]
    public void TheEmptyStateFollowsTheReplayPhaseOnScreen()
    {
        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader())
                {
                    // 摆到屏幕外、不抢焦点：这条用例要的是真的加载过一屏，不是给人看
                    WindowStartupLocation = WindowStartupLocation.Manual,
                    Left = -10000,
                    Top = -10000,
                    ShowActivated = false
                };
                var retries = 0;
                window.RestoreRequested += () => retries++;
                window.Show();
                Sta.PumpUntil(() => window.IsLoaded);
                Sta.PumpUntil(() => window.ConversationEmptyTitleProbe.Length > 0);

                // 库里空着、回放还没回话：不许说"还没有频道"，也没有可点的按钮
                Assert.Equal("正在同步会话…", window.ConversationEmptyTitleProbe);
                Assert.Equal(MainModel.EmptyAction.None, window.ConversationEmptyActionProbe);
                Assert.Equal(Visibility.Collapsed, window.ConversationEmptyButtonProbe);

                // 回放说"确实没有"：换成建频道那颗
                window.EndRestore(ReplayOutcome.Completed);
                Sta.PumpUntil(() => window.ConversationEmptyTitleProbe == "还没有频道");
                Assert.Equal(MainModel.EmptyAction.CreateChannel, window.ConversationEmptyActionProbe);
                Assert.Equal(Visibility.Visible, window.ConversationEmptyButtonProbe);

                // 回放没成：同一颗按钮改成"重试"，点它把话交回装配层
                window.EndRestore(ReplayOutcome.Failed);
                Sta.PumpUntil(() => window.ConversationEmptyTitleProbe == "会话没取回来");
                Assert.Equal(MainModel.EmptyAction.Retry, window.ConversationEmptyActionProbe);
                var retry = (Button)window.FindName("ConversationEmptyButton")!;
                Assert.Equal("重试", retry.Content);
                retry.RaiseEvent(new RoutedEventArgs(Button.ClickEvent));
                Assert.Equal(1, retries);

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
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "空态三态没有按时画完");
        Assert.True(failure is null, failure?.ToString());
    }

    /// <summary>
    /// 时间线是虚拟化的：这一屏 50 条历史只排看得见的那几行。
    /// 判据只能落在"生成出来的行数"上——<c>Timeline.Items</c> 是全量的（条目对象本来就在），
    /// 而且虚拟化这条路最容易被"外面再套一个 ScrollViewer"悄悄弄失效，那种写法下面板照样全量测量。
    /// </summary>
    [Fact]
    public void TheTimelineOnlyLaysOutWhatIsOnScreen()
    {
        for (var seq = 1; seq <= 200; seq++) _db.ApplyMessage(Msg(seq, "m" + seq, Grafana));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader())
                {
                    WindowStartupLocation = WindowStartupLocation.Manual,
                    Left = -10000,
                    Top = -10000,
                    ShowActivated = false
                };
                window.Show();
                Sta.PumpUntil(() => window.IsLoaded);
                window.OpenConversation(Channel, Grafana);
                // 落底要等布局那一轮走完：虚拟化面板的 extent 是滚到最后一行生成出来才定下的
                Sta.PumpUntil(() => window.TimelineRealizedProbe > 0
                                    && window.TimelineFromBottomProbe <= 1);

                var total = window.TimelineItemCountProbe;
                var realized = window.TimelineRealizedProbe;
                Assert.True(total > 40, $"这一屏至少有 40 条可断，实际 {total}");
                // 打开一屏只取一页：首屏布局时偏移还在 0，那不是"用户翻到顶了"，不该顺手再翻一页
                Assert.True(total <= 60, $"打开会话连着翻了两页，实际 {total} 条");
                Assert.True(realized < total,
                    $"时间线把 {total} 条全排了（虚拟化没生效）");
                Assert.True(realized <= 40, $"看得见的行数不该是 {realized}");
                // 光"屏外不排版"还不够：行本身是数据、由模板按需建，屏外的条目才连气泡都不存在。
                // 回到 Items.Add(建好的控件) 那种写法时这里会掉到 0——那时整条历史又被建了一遍
                var built = window.TimelineBuiltProbe;
                Assert.True(built > 0, "一行控件都没建出来：行模板没挂上");
                Assert.True(built <= realized, $"建出来的行 {built} 比生成出来的容器 {realized} 还多");
                // 打开会话仍然从最新一条看起
                Assert.True(window.TimelineFromBottomProbe <= 1,
                    $"没贴底：离底还有 {window.TimelineFromBottomProbe}");

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
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "虚拟化用例没有按时跑完");
        Assert.True(failure is null, failure?.ToString());
    }

    /// <summary>
    /// 重画一次不换集合、也不重建行。原来每次 Refresh 都 <c>Items.Clear()</c> 再逐条建控件，
    /// 面板收到的是 Reset：滚动位置、拖选、已经取到的图一起没，屏幕还会先弹回顶部。
    /// 现在行是数据、由模板按需建，所以"同一份集合 + 同一批容器"就是这一步真正的判据。
    /// </summary>
    [Fact]
    public void ARepaintKeepsTheTimelineCollectionAndItsRowsInPlace()
    {
        for (var seq = 1; seq <= 200; seq++) _db.ApplyMessage(Msg(seq, "m" + seq, Grafana));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader())
                {
                    WindowStartupLocation = WindowStartupLocation.Manual,
                    Left = -10000,
                    Top = -10000,
                    ShowActivated = false
                };
                window.Show();
                Sta.PumpUntil(() => window.IsLoaded);
                window.OpenConversation(Channel, Grafana);
                Sta.PumpUntil(() => window.TimelineRealizedProbe > 0
                                    && window.TimelineFromBottomProbe <= 1);
                Sta.PumpFor(100);

                var count = window.TimelineItemCountProbe;
                var source = window.TimelineSourceProbe;
                var container = FirstRealizedContainer(window);
                Assert.NotNull(container);

                window.Repaint();
                Sta.PumpFor(100);

                Assert.Same(source, window.TimelineSourceProbe);
                Assert.Equal(count, window.TimelineItemCountProbe);
                // 容器还是那一个：整份换掉时面板会把这批容器全部丢掉重生成
                Assert.Same(container, FirstRealizedContainer(window));
                Assert.True(window.TimelineFromBottomProbe <= 1,
                    $"重画一次就离底 {window.TimelineFromBottomProbe}——时间线被整条重建了");

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
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "重画一次没有按时跑完");
        Assert.True(failure is null, failure?.ToString());
    }

    /// <summary>
    /// 往上翻到顶会补进来一页历史。新的一页<b>插在前面</b>，上面长出来的那段高度必须补进偏移，
    /// 正在读的那条才不会被推到视野外——判据就是"补完之后屏上第一条还是刚才那条"。
    /// 顺带钉住补页不换集合：整份换掉的话滚动位置只能靠猜，而这正是它要靠容器去校的原因。
    /// </summary>
    [Fact]
    public void AnEarlierPageKeepsTheReaderInPlaceInsteadOfSnappingToTheTop()
    {
        for (var seq = 1; seq <= 200; seq++) _db.ApplyMessage(Msg(seq, "m" + seq, Grafana));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader())
                {
                    WindowStartupLocation = WindowStartupLocation.Manual,
                    Left = -10000,
                    Top = -10000,
                    ShowActivated = false
                };
                window.Show();
                Sta.PumpUntil(() => window.IsLoaded);
                window.OpenConversation(Channel, Grafana);
                Sta.PumpUntil(() => window.TimelineRealizedProbe > 0
                                    && window.TimelineFromBottomProbe <= 1);
                // 落底之后还有两轮布局才把"这一屏摆好了"标上，翻页判定要等那一下
                Sta.PumpFor(100);

                var scroller = Scroller(window);
                var before = window.TimelineItemCountProbe;
                var source = window.TimelineSourceProbe;

                // 先退一格（这时候还不该翻页），记下摆在最上面的是哪一条、离视口顶多远
                scroller.ScrollToVerticalOffset(1);
                Sta.PumpFor(100);
                Assert.Equal(before, window.TimelineItemCountProbe);
                var top = FirstRealizedId(window);
                Assert.NotNull(top);
                var anchorTop = TopOf(window, top!, scroller);
                Assert.NotNull(anchorTop);

                scroller.ScrollToVerticalOffset(0);
                Sta.PumpUntil(() => window.TimelineItemCountProbe > before);
                // 补页之后要几轮布局才摆稳，视角是那几轮里钉住的
                Sta.PumpFor(300);

                Assert.Same(source, window.TimelineSourceProbe);
                Assert.True(scroller.VerticalOffset > 0,
                    $"补了一页，偏移却是 {scroller.VerticalOffset}——正在读的那条被推到视野外了");
                var settled = TopOf(window, top!, scroller);
                Assert.True(settled is not null,
                    $"补页之后 {top} 连容器都没有了：off={scroller.VerticalOffset}");
                // 基准是在偏移 1 处量的，翻页发生在偏移 0，两者本来就差一个像素
                Assert.True(Math.Abs(settled!.Value - anchorTop!.Value) <= 2,
                    $"{top} 原来在 {anchorTop}，补完跑到 {settled}——那一页把它推走了");

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
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "往上补一页没有按时跑完");
        Assert.True(failure is null, failure?.ToString());
    }

    /// <summary>
    /// 对话区的两条边：消息列贴着左边（头像就在 8px 内边距之后），滚动条贴着对话区右缘。
    /// 原来整条时间线被 <c>MaxWidth</c> 掐住再居中，滚动条落在对话区中间，左边还空出一大截。
    /// 列宽钉成固定的那一档（<see cref="MainModel.BubbleMaxWidth"/>）而不是按内容收：
    /// 宽气泡被虚拟化回收时整列跟着缩，日期头就会在滚动中左右跳。
    /// </summary>
    [Fact]
    public void TheMessageColumnSitsLeftAndTheScrollBarHugsTheRightEdge()
    {
        // 12 条刚好超出一屏：滚动条显出来，日期头在底部时又不在视口里（滚回顶上才量得到）。
        // 条数不超过一页，滚到顶也不会触发"补一页"，测量不会被那一趟重新摆位搅乱
        for (var seq = 1; seq <= 12; seq++) _db.ApplyMessage(Msg(seq, "m" + seq, Grafana));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader())
                {
                    WindowStartupLocation = WindowStartupLocation.Manual,
                    Left = -10000,
                    Top = -10000,
                    Width = 1400,
                    Height = 500,
                    ShowActivated = false
                };
                window.Show();
                Sta.PumpUntil(() => window.IsLoaded);
                window.OpenConversation(Channel, Grafana);
                Sta.PumpUntil(() => window.TimelineRealizedProbe > 0
                                    && window.TimelineFromBottomProbe <= 1);
                Sta.PumpFor(100);

                var timeline = (ItemsControl)window.FindName("Timeline")!;
                var scroller = Scroller(window);
                var presenter = Find<ItemsPresenter>(timeline)
                    ?? throw new InvalidOperationException("时间线模板里没有消息列");
                var bar = VerticalBar(scroller);
                Assert.Equal(Visibility.Visible, bar.Visibility);

                // 时间线铺满对话区，滚动条就在这条控件的右缘上
                Assert.Equal(timeline.ActualWidth, scroller.ActualWidth, 1);
                var barRight = bar.TranslatePoint(new Point(bar.ActualWidth, 0), scroller).X;
                Assert.Equal(scroller.ActualWidth, barRight, 1);

                // 消息列靠左：ScrollViewer 的左内边距(8)之后就是它，右面留白给滚动条那一侧
                var presenterLeft = presenter.TranslatePoint(new Point(0, 0), scroller).X;
                Assert.Equal(8, presenterLeft, 1);
                // 列宽就是模型按"窗口宽度"算出来的那一档（ApplyPaneWidth 抄的就是它）。
                // 实际宽度会按布局取整到 1 物理像素（125% 缩放下就是 0.8 DIP 一格），所以留 1 DIP
                var layout = MainModel.PaneLayoutFor(window.ActualWidth, true, MainModel.ListDefaultWidth);
                var expected = MainModel.BubbleMaxWidth(layout.RightPaneWidth, layout.Narrow);
                Assert.Equal(expected, presenter.Width, 1);
                Assert.True(Math.Abs(expected - presenter.ActualWidth) <= 1,
                    $"消息列宽 {presenter.ActualWidth}（钉的是 {expected}）");
                Assert.True(presenter.ActualWidth < scroller.ActualWidth - 100,
                    $"消息列 {presenter.ActualWidth} 几乎铺满了 {scroller.ActualWidth}：右边没有留白，滚动条也不在边上");

                // 行都收在这一列里，头像的左边就是列的左缘
                var row = FirstRealizedMessage(window) ?? throw new InvalidOperationException("一条消息都没生成出来");
                Assert.Equal(presenterLeft, row.TranslatePoint(new Point(0, 0), scroller).X, 1);
                var avatar = Find<AvatarBox>(row) ?? throw new InvalidOperationException("消息行里没有头像");
                Assert.Equal(presenterLeft, avatar.TranslatePoint(new Point(0, 0), scroller).X, 1);

                // 日期头居中在这条列上（列宽固定，翻多久都不会左右跳）
                scroller.ScrollToTop();
                Sta.PumpUntil(() => timeline.ItemContainerGenerator.ContainerFromIndex(0) is FrameworkElement);
                Sta.PumpFor(100);
                var divider = (FrameworkElement)timeline.ItemContainerGenerator.ContainerFromIndex(0)!;
                Assert.IsType<DateDivider>(timeline.Items[0]);
                var label = Find<TextBlock>(divider) ?? throw new InvalidOperationException("日期头没有字");
                var center = label.TranslatePoint(new Point(label.ActualWidth / 2, 0), scroller).X;
                Assert.Equal(presenterLeft + presenter.ActualWidth / 2, center, 2);

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
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "消息列与滚动条的位置没有按时量完");
        Assert.True(failure is null, failure?.ToString());
    }

    /// <summary>
    /// 鼠标停在气泡正文上滚，时间线也要动。正文是 <c>FlowDocumentScrollViewer</c>，它内部那层
    /// ScrollViewer 即使滚动条关着也会把滚轮吃掉——测试里合成的是隧道事件，判据是窗口在隧道阶段
    /// 就把它接住（<c>Handled</c>）并且偏移真的动了。
    /// </summary>
    [Fact]
    public void AWheelOverTheBubbleBodyScrollsTheTimeline()
    {
        for (var seq = 1; seq <= 60; seq++) _db.ApplyMessage(Msg(seq, "m" + seq, Grafana));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader())
                {
                    WindowStartupLocation = WindowStartupLocation.Manual,
                    Left = -10000,
                    Top = -10000,
                    Width = 1200,
                    Height = 700,
                    ShowActivated = false
                };
                window.Show();
                Sta.PumpUntil(() => window.IsLoaded);
                window.OpenConversation(Channel, Grafana);
                Sta.PumpUntil(() => window.TimelineRealizedProbe > 0
                                    && window.TimelineFromBottomProbe <= 1);
                Sta.PumpFor(100);

                var scroller = Scroller(window);
                var body = Find<SelectableBody>((DependencyObject)window.FindName("Timeline")!)
                    ?? throw new InvalidOperationException("屏上没有气泡正文");
                var before = scroller.VerticalOffset;
                Assert.True(before > 0, "时间线没有滚到底，滚轮用例没有可滚的空间");

                var up = new MouseWheelEventArgs(Mouse.PrimaryDevice, Environment.TickCount, 120)
                    { RoutedEvent = UIElement.PreviewMouseWheelEvent };
                body.RaiseEvent(up);
                Sta.PumpFor(100);

                Assert.True(up.Handled, "气泡正文把滚轮吃掉了：窗口这一层没接到");
                Assert.True(scroller.VerticalOffset < before,
                    $"滚轮没有把时间线往上滚：{before} → {scroller.VerticalOffset}");

                // 一格就是一格：位移量跟系统"每次滚动行数"走，也不许被处理两遍（那才叫不跟手）
                var lines = SystemParameters.WheelScrollLines;
                var step = lines < 0 ? Math.Max(1, scroller.ViewportHeight - 40) : Math.Max(1, lines) * 40;
                Assert.InRange(before - scroller.VerticalOffset, step - 2, step + 2);

                var back = new MouseWheelEventArgs(Mouse.PrimaryDevice, Environment.TickCount, -120)
                    { RoutedEvent = UIElement.PreviewMouseWheelEvent };
                body.RaiseEvent(back);
                Sta.PumpFor(100);
                Assert.True(window.TimelineFromBottomProbe <= 1,
                    $"往下滚回不到底：离底还有 {window.TimelineFromBottomProbe}");

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
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "滚轮用例没有按时跑完");
        Assert.True(failure is null, failure?.ToString());
    }

    /// <summary>
    /// 换会话只建视口那几行。行高必须在面板第一次量的时候就建得出来：那一刻要是行是空的、高 0，
    /// "量够一屏了"永远不成立，面板会把整整一屏 50 条全建一遍——一次切换 1 秒以上。
    /// 判据两条：切过去当场就落在最新一条（整段同步做完，中间没有帧能被画出来），
    /// 以及这一趟里同时存在的行不超过一屏的量（旧样子是 51 行）。
    /// </summary>
    [Fact]
    public void SwitchingSendersBuildsOnlyWhatTheViewportShows()
    {
        for (var seq = 1; seq <= 50; seq++) _db.ApplyMessage(Msg(seq, "a" + seq, Grafana));
        for (var seq = 101; seq <= 150; seq++) _db.ApplyMessage(Msg(seq, "b" + seq, "ops"));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader())
                {
                    WindowStartupLocation = WindowStartupLocation.Manual,
                    Left = -10000,
                    Top = -10000,
                    ShowActivated = false
                };
                window.Show();
                Sta.PumpUntil(() => window.IsLoaded);
                window.OpenConversation(Channel, Grafana);
                Sta.PumpUntil(() => window.TimelineRealizedProbe > 0
                                    && window.TimelineFromBottomProbe <= 1);
                Sta.PumpFor(100);

                var most = 0;
                void OnLayout(object? _, EventArgs __) => most = Math.Max(most, window.TimelineBuiltProbe);
                window.LayoutUpdated += OnLayout;
                window.OpenConversation(Channel, "ops");
                window.LayoutUpdated -= OnLayout;

                Assert.True(window.TimelineFromBottomProbe <= 1,
                    $"切过去没有当场落在最新一条：离底还有 {window.TimelineFromBottomProbe}");
                Assert.InRange(most, 1, 20);

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
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "换会话的行数用例没有按时跑完");
        Assert.True(failure is null, failure?.ToString());
    }

    /// <summary>
    /// 换一个发送人不该逐条拆行。头尾对不上时旧代码会走 <c>RemoveAt(0)</c> 一整遍，
    /// 面板为每一对通知回收/重建一次容器，一次切换就是上百次——那正是"切发送人卡顿"的来源。
    /// 现在的判据：旧集合一条通知都没有，集合实例换了一个（面板只收到一次 Reset）。
    /// </summary>
    [Fact]
    public void SwitchingSendersSwapsTheTimelineInOnePiece()
    {
        for (var seq = 1; seq <= 50; seq++) _db.ApplyMessage(Msg(seq, "a" + seq, Grafana));
        for (var seq = 101; seq <= 150; seq++) _db.ApplyMessage(Msg(seq, "b" + seq, "ops"));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader())
                {
                    WindowStartupLocation = WindowStartupLocation.Manual,
                    Left = -10000,
                    Top = -10000,
                    ShowActivated = false
                };
                window.Show();
                Sta.PumpUntil(() => window.IsLoaded);
                window.OpenConversation(Channel, Grafana);
                Sta.PumpUntil(() => window.TimelineRealizedProbe > 0
                                    && window.TimelineFromBottomProbe <= 1);
                Sta.PumpFor(100);

                var before = window.TimelineSourceProbe;
                Assert.NotNull(before);
                var actions = new List<NotifyCollectionChangedAction>();
                ((INotifyCollectionChanged)before!).CollectionChanged += (_, e) => actions.Add(e.Action);

                window.OpenConversation(Channel, "ops");
                Sta.PumpUntil(() => window.TimelineRealizedProbe > 0
                                    && window.TimelineFromBottomProbe <= 1);
                Sta.PumpFor(100);

                Assert.Empty(actions);
                Assert.NotSame(before, window.TimelineSourceProbe);
                var last = ((ItemsControl)window.FindName("Timeline")!).Items[^1];
                Assert.Equal("b150", Assert.IsType<MessageItem>(last).MsgId);
                Assert.True(window.TimelineFromBottomProbe <= 1,
                    $"切过去没有落在最新一条：离底还有 {window.TimelineFromBottomProbe}");

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
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "切换发送人没有按时跑完");
        Assert.True(failure is null, failure?.ToString());
    }

    /// <summary>
    /// 行的右键菜单要能只用键盘开出来。菜单住在行模板里的 Grid 上，而那个 Grid 不是焦点元素——
    /// WPF 的 Shift+F10 只找"焦点元素自己的 ContextMenu"，所以这条键盘路径得窗口自己接。
    /// 顺带断落点：键盘开的那次贴在行下面，关掉之后要还给鼠标（否则这一行下次右键也跑到行下面去）。
    /// </summary>
    [Fact]
    public void TheRowMenuOpensFromTheKeyboardAndGivesPlacementBack()
    {
        _db.ApplyMessage(Msg(1, "m1", Grafana));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader())
                {
                    WindowStartupLocation = WindowStartupLocation.Manual,
                    Left = -10000,
                    Top = -10000,
                    ShowActivated = false
                };
                window.Show();
                Sta.PumpUntil(() => window.IsLoaded);
                Sta.PumpUntil(() => window.RowRealizedProbe > 0);

                // 没选中就没有"这一行"可言：这时按菜单键不该开出一颗指着别人的菜单
                Assert.Null(window.OpenRowMenuForKeyboard());

                window.OpenConversation(Channel, Grafana);
                Sta.PumpUntil(() => window.RowRealizedProbe > 0);

                var menu = window.OpenRowMenuForKeyboard();
                Assert.NotNull(menu);
                Sta.PumpUntil(() => menu!.IsOpen);
                Assert.Same(window.RowMenuProbe, menu);
                // 落点是被选中的那一行本身，不是模板里那个 Grid
                Assert.IsType<ListBoxItem>(menu!.PlacementTarget);
                Assert.Equal(PlacementMode.Bottom, menu.Placement);

                // 打开时那两行按这一行的现状态写字：与右键开出来的走的是同一个 Opened
                var items = menu.Items.OfType<MenuItem>().ToList();
                Assert.Equal("置顶", items.Single(i => (string?)i.Tag == "pin").Header);
                Assert.Equal("免打扰", items.Single(i => (string?)i.Tag == "mute").Header);

                menu.IsOpen = false;
                Sta.PumpUntil(() => !menu.IsOpen);
                Sta.PumpUntil(() => menu.Placement == PlacementMode.MousePoint);
                Assert.Null(window.RowMenuProbe);

                // 真正的那条路：Shift+F10 / 菜单键走窗口的 PreviewKeyDown。
                // 合成事件而不是真按键——测试窗没有激活，键盘输入根本到不了它
                var args = new KeyEventArgs(Keyboard.PrimaryDevice,
                    PresentationSource.FromVisual(window)!, 0, Key.Apps)
                {
                    RoutedEvent = Keyboard.PreviewKeyDownEvent
                };
                window.RaiseEvent(args);
                Assert.True(args.Handled, "菜单键到了窗口却没人接");
                Sta.PumpUntil(() => window.RowMenuProbe is not null);
                window.RowMenuProbe!.IsOpen = false;
                Sta.PumpUntil(() => window.RowMenuProbe is null);

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
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "键盘开行菜单没有按时跑完");
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

    // ---- 切会话：气泡复用 ----------------------------------------------------------------
    /// <summary>
    /// 切走的会话把建好的气泡留在 <see cref="BubbleCache"/> 里，切回来直接接上，不重建一遍。
    /// 判据落在元素实例本身：同一条消息两次看到的是同一棵树。只断计数器的话，
    /// "取到了但没接上"这种错法照样计数正确，屏幕上也照样卡。
    /// </summary>
    [Fact]
    public void BubblesSurviveASwitchAwayAndBack()
    {
        for (var seq = 1; seq <= 20; seq++) _db.ApplyMessage(Msg(seq, "a" + seq, Grafana));
        for (var seq = 101; seq <= 120; seq++) _db.ApplyMessage(Msg(seq, "b" + seq, "ops"));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader())
                {
                    WindowStartupLocation = WindowStartupLocation.Manual,
                    Left = -10000,
                    Top = -10000,
                    ShowActivated = false
                };
                window.Show();
                Sta.PumpUntil(() => window.IsLoaded);
                window.OpenConversation(Channel, Grafana);
                Sta.PumpUntil(() => window.TimelineRealizedProbe > 0
                                    && window.TimelineFromBottomProbe <= 1);
                Sta.PumpFor(100);

                var list = (ListBox)window.FindName("ConversationList")!;
                var mine = list.SelectedIndex;
                var other = mine == 0 ? 1 : 0;

                var (id, before) = LastRealizedBubble(window)
                                    ?? throw new InvalidOperationException("一条气泡都没建出来");

                list.SelectedIndex = other;
                Sta.PumpUntil(() => window.TimelineRealizedProbe > 0 && window.TimelineFromBottomProbe <= 1);
                Sta.PumpFor(100);

                list.SelectedIndex = mine;
                Sta.PumpUntil(() => window.TimelineRealizedProbe > 0 && window.TimelineFromBottomProbe <= 1);
                Sta.PumpFor(100);

                var cache = window.RowConfig?.Cache ?? throw new InvalidOperationException("行配置没挂上");
                Assert.True(cache.Hits > 0, "切回来一条气泡都没复用上");
                Assert.Same(before, BubbleOf(window, id));

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
        Assert.True(thread.Join(TimeSpan.FromSeconds(60)), "来回切一次没有按时跑完");
        Assert.True(failure is null, failure?.ToString());
    }

    private const string Channel = "83271504";
    private const string Grafana = "grafana";

    /// <summary>
    /// 开机自启那一轮主窗一次都不 Show（见 App.OpenMainWindow(show:false)）。托盘要读的未读数、
    /// 连接状态，以及"引擎落一条就重画"全靠窗口自己的订阅，所以它们必须在**构造时**就接上——
    /// 退回到"等 OnContentRendered 再挂"的话，自启那一路会安静地什么都收不到。
    /// </summary>
    [Fact]
    public void AWindowForABackgroundStartIsWiredOnConstructionAndNeverShown()
    {
        _db.ApplyMessage(Msg(1, "m1", Grafana));

        Exception? failure = null;
        var thread = new Thread(() =>
        {
            try
            {
                var engine = new SyncEngine(_identities, _db, Offline(), new Silent());
                var window = new MainWindow(_db, _identities,
                    new ReadReporter(_db, _identities, Offline()),
                    new SearchService(_db, _identities, Offline()), Deletions(), new MediaLoader(), engine);

                // 自启的形态：对象在、一次都没 Show
                Assert.False(window.IsVisible);
                Assert.False(window.IsLoaded);
                // 未读数是纯模型的账：没画过的窗口也得算得出来（托盘 tooltip 读的就是它）
                Assert.Equal(1, window.TrayUnread);
                // 状态点默认收起（"没状态时不留一个孤零零的点"），它亮着说明引擎初始状态已经应用过——
                // 那一步只发生在订阅接上时，也就是构造里
                Assert.Equal(Visibility.Visible, window.StatusDotProbe);

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
        Assert.True(thread.Join(TimeSpan.FromSeconds(30)), "后台启动那种窗口形态没有按时走完");
        Assert.True(failure is null, failure?.ToString());
    }

    private sealed class Silent : INotifier
    {
        public void Notify(MessageFrame message)
        {
        }
    }

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

    /// <summary>时间线那根滚动条住在 ItemsControl 的模板里（虚拟化要求它自己就是内容的宿主），只能顺可视树找。</summary>
    private static ScrollViewer Scroller(MainWindow window) =>
        Find<ScrollViewer>((DependencyObject)window.FindName("Timeline")!)
        ?? throw new InvalidOperationException("时间线模板里没有滚动条");

    /// <summary>竖着的那一根。ScrollViewer 模板按名字放它，问不到再退到"朝向为竖"的第一根。</summary>
    private static ScrollBar VerticalBar(ScrollViewer scroller)
    {
        if (scroller.Template?.FindName("PART_VerticalScrollBar", scroller) is ScrollBar named) return named;
        var bars = new List<ScrollBar>();
        Collect(scroller, bars);
        return bars.FirstOrDefault(bar => bar.Orientation == Orientation.Vertical)
               ?? throw new InvalidOperationException("滚动宿主里没有竖滚动条");
    }

    private static void Collect(DependencyObject root, List<ScrollBar> bars)
    {
        if (root is ScrollBar bar) bars.Add(bar);
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
            Collect(VisualTreeHelper.GetChild(root, i), bars);
    }

    private static T? Find<T>(DependencyObject root) where T : DependencyObject
    {
        if (root is T hit) return hit;
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            if (Find<T>(VisualTreeHelper.GetChild(root, i)) is { } found) return found;
        }

        return null;
    }

    /// <summary>屏上第一条真的排出来的那一条的容器（虚拟化下只有视口附近有）。</summary>
    private static FrameworkElement? FirstRealizedContainer(MainWindow window)
    {
        var timeline = (ItemsControl)window.FindName("Timeline")!;
        for (var i = 0; i < timeline.Items.Count; i++)
        {
            if (timeline.ItemContainerGenerator.ContainerFromIndex(i) is FrameworkElement row) return row;
        }

        return null;
    }

    /// <summary>第一条生成出来的消息行（跳过日期头——它没有头像）。</summary>
    private static FrameworkElement? FirstRealizedMessage(MainWindow window)
    {
        var timeline = (ItemsControl)window.FindName("Timeline")!;
        for (var i = 0; i < timeline.Items.Count; i++)
        {
            if (timeline.Items[i] is MessageItem
                && timeline.ItemContainerGenerator.ContainerFromIndex(i) is FrameworkElement row)
            {
                return row;
            }
        }

        return null;
    }

    /// <summary>屏上第一条消息是哪一条——补页前后它不变，才算视角没被推走。</summary>
    private static string? FirstRealizedId(MainWindow window)
    {
        var timeline = (ItemsControl)window.FindName("Timeline")!;
        for (var i = 0; i < timeline.Items.Count; i++)
        {
            if (timeline.ItemContainerGenerator.ContainerFromIndex(i) is FrameworkElement { DataContext: MessageItem m })
                return m.MsgId;
        }

        return null;
    }

    /// <summary>最靠下的那条"已经有气泡"的消息（切会话后仍落在底屏，适合拿它比实例）。</summary>
    private static (string Id, FrameworkElement Bubble)? LastRealizedBubble(MainWindow window)
    {
        var timeline = (ItemsControl)window.FindName("Timeline")!;
        (string, FrameworkElement)? last = null;
        for (var i = 0; i < timeline.Items.Count; i++)
        {
            if (BubbleAt(timeline, i) is { } current) last = current;
        }

        return last;
    }

    /// <summary>某一条消息此刻挂在行上的那棵气泡。行没生成出来就是 null。</summary>
    private static FrameworkElement? BubbleOf(MainWindow window, string msgId)
    {
        var timeline = (ItemsControl)window.FindName("Timeline")!;
        for (var i = 0; i < timeline.Items.Count; i++)
        {
            if (BubbleAt(timeline, i) is { } current && current.Id == msgId) return current.Bubble;
        }

        return null;
    }

    /// <summary>第 <paramref name="index"/> 行的消息 id 与它那棵气泡。行容器里套的是 <see cref="TimelineRow"/>。</summary>
    private static (string Id, FrameworkElement Bubble)? BubbleAt(ItemsControl timeline, int index)
    {
        if (timeline.ItemContainerGenerator.ContainerFromIndex(index) is not FrameworkElement container
            || container.DataContext is not MessageItem message
            || Find<TimelineRow>(container)?.Child is not FrameworkElement bubble)
        {
            return null;
        }

        return (message.MsgId, bubble);
    }

    /// <summary>某一条消息的容器相对视口顶纵坐标；它没被生成出来就是 null。</summary>
    private static double? TopOf(MainWindow window, string msgId, ScrollViewer scroller)
    {
        var timeline = (ItemsControl)window.FindName("Timeline")!;
        for (var i = 0; i < timeline.Items.Count; i++)
        {
            if (timeline.ItemContainerGenerator.ContainerFromIndex(i) is FrameworkElement { DataContext: MessageItem m } row
                && m.MsgId == msgId)
            {
                return row.TranslatePoint(new Point(0, 0), scroller).Y;
            }
        }

        return null;
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
