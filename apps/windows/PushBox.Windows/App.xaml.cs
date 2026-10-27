using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Windows;
using System.Windows.Threading;
using PushBox.Windows.Controls;
using PushBox.Windows.Data;
using PushBox.Windows.Integration;
using PushBox.Windows.Logging;
using PushBox.Windows.Media;
using PushBox.Windows.Net;
using PushBox.Windows.Notify;
using PushBox.Windows.Settings;
using PushBox.Windows.Sync;
using PushBox.Windows.Themes;
using PushBox.Windows.Update;
using PushBox.Windows.Views;

namespace PushBox.Windows;

/// <summary>
/// Interaction logic for App.xaml
///
/// W13 之后这一页多了一件事：它是唯一"装配"系统集成的地方——单实例、托盘、注册表、通知器都在这里接上，
/// 而引擎、库、窗口各自都不知道对方存在。W12 留在 <see cref="INotifier"/> 那条缝上的空实现，
/// 在这里换成 <see cref="ToastNotifier"/>，同步循环一行都没动。
/// </summary>
public partial class App : Application
{
    /// <summary>体检开关：<c>PushBox.Windows.exe --notify-smoke 输出文件</c>。量的是"通知这条路通不通"，
    /// 需要交互桌面会话，所以它不是 xunit 用例而是这个自成一格的入口（同 <c>PushBox.LiveCheck</c> 的理由）。</summary>
    public const string SmokeSwitch = "--notify-smoke";

    private readonly DeviceClient _client = new();

    private DeviceStore? _db;
    private IdentityStore? _identities;
    private ReadReporter? _reporter;
    private SyncEngine? _engine;
    private MediaLoader? _media;
    private ConversationTally? _tally;
    private ToastNotifier? _notifier;

    private ClientSettings _settings = ClientSettings.Load();
    private InstanceHandoff? _handoff;
    private TrayIcon? _tray;
    private SettingsWindow? _settingsWindow;
    private FirstLaunchWindow? _firstLaunch;

    /// <summary>托盘"退出"和真退出之间的区分句柄：关窗默认只是藏起来，唯独这一次要真关。</summary>
    private bool _exiting;

    /// <summary>退出空间也在关主窗：那一次不是"最小化到托盘"，是把这个空间收掉。</summary>
    private bool _loggingOut;

    /// <summary>引擎那条 Task 的观察者只挂一次：<see cref="OpenMainWindow"/> 会被"点通知时窗口还没开"
    /// 再走一遍，而 <c>Start</c> 是幂等的，重复挂就成了同一次崩溃写两行。</summary>
    private bool _loopWatched;

    /// <summary>
    /// §7.5: no identity on disk means first launch, so the enroll screen owns startup instead of a
    /// StartupUri that would open an empty main window and then need closing.
    /// </summary>
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // W15 的第一件事是开留痕，排在单实例判断之前：第二个实例"为什么自己退了"和主实例"崩在哪"
        // 都要查得到。挂钩也不等业务——装配阶段（注册表、托盘、窗口）恰恰是最容易起不来的那一段。
        AppLog.Start();
        AttachCrashHandlers();

        var exe = ExePath;
        _settings = ClientSettings.Load();

        if (SmokeTarget(e.Args) is { Length: > 0 } outFile)
        {
            RunNotifySmoke(exe, outFile);
            Shutdown();
            return;
        }

        // 单实例在前，其它什么都不做：点一条通知不该多出一个托盘图标、一条长连接和一个开同一份库的写者
        var handoff = new InstanceHandoff(exe);
        var target = ToastProtocol.FromArgs(e.Args);
        if (!handoff.TryBecomePrimary())
        {
            // 已有实例在跑：把 URI 递过去，自己就此退出（递不上也只能这样——对方可能正在收工）
            var sent = target != null && handoff.Send(ToastProtocol.UriOf(target));
            AppLog.SecondInstanceLeft(sent);
            handoff.Dispose();
            Shutdown();
            return;
        }

        _handoff = handoff;
        handoff.StartListening(arg =>
        {
            // 管道线程 → UI 线程：HandleTarget 要动窗口，只能在 dispatcher 上做
            Dispatcher.BeginInvoke(() => HandleTarget(ToastProtocol.Parse(arg)));
        });

        // 开机自启那一笔注册表会带上 --tray：托盘与同步照常起来，主窗先不 Show
        var background = StartInBackground(e.Args);

        // §9.1 的深浅色：在任何一个窗口被 Show 之前换上 token 字典，否则首帧会闪一下另一个主题。
        // 档位来自设置（默认跟随系统），固定浅色/深色时系统事件不再改色
        ThemeManager.Follow(_settings.Theme);
        // 裸 WinRT 不给未打包应用注册入口，这两处 HKCU 就是那个入口（AUMID + 协议处理器）
        ToastRegistration.Ensure(exe);
        ReconcileAutoStart(exe);
        _tray = new TrayIcon(BuildTrayActions(), _settings);

        var model = new OnboardingModel();
        var enrolled = model.IsEnrolled();
        // 这一行是"这台机器从什么时候开始算装了 PushBox"的锚，也是查任何一条留痕时的第一处上下文
        AppLog.Started(VersionOf(), enrolled);
        // 彩色 emoji 的字形表初始化是一次性的 ~500 ms，落在哪个线程哪个线程就卡。
        // 赶在任何窗口 Show 之前在后台 STA 线程上把它付掉，UI 线程首次替换因此降到 7–12 ms。
        EmojiText.Prewarm();
        if (enrolled) OpenMainWindow(show: !background);
        else if (!background) OpenFirstLaunch();
        else
        {
            // 自启但本机没有身份（退出过空间、身份文件又丢了）：没有可收的消息，也没有可画的界面。
            // 开机那一次到此为止，绝不因为一个过期的注册表条目把首启窗摆到用户脸上
            Shutdown();
            return;
        }

        // 冷启动是"应用没在跑时点通知"那条路：窗口刚建好就该落在那条会话上
        if (target != null) HandleTarget(target);

        // §8.4 之后：注册过的机器冷启动静默查一次 GitHub 更新；没注册的先把空间建好再说。
        // 这条只在进程第一次走（见 _updateCheckedThisProcess），绝不在 15 分钟轮询里重复——未认证限额会烧光。
        // 后台启动（自启）不查：查到新版也只能弹一扇窗，那一下正好等于"开机就把界面打开"。
        if (enrolled && !background) CheckUpdateOnStartup();
    }

    /// <summary>
    /// argv 里有没有自启开关。与 <see cref="AutoStart.BackgroundArgument"/> 是同一个字面量，
    /// 判大小写不敏感是因为注册表里的那一笔可能有手写/旧版本留下的变体。
    /// </summary>
    public static bool StartInBackground(string[]? args)
    {
        if (args == null) return false;
        foreach (var arg in args)
        {
            if (string.Equals(arg, AutoStart.BackgroundArgument, StringComparison.OrdinalIgnoreCase)) return true;
        }

        return false;
    }

    /// <summary>本进程只静默查一次更新：窗口可能被"点通知时还没开"重开，但那不该再发一次 GitHub 请求。</summary>
    private bool _updateCheckedThisProcess;

    /// <summary>
    /// 启动静默查更新：只在「启动时自动检查更新」开着时跑（D32），后台查、绝不阻塞启动。
    /// 查到可装的新版就弹一个确认框——下载这一步仍要用户点「下载并安装」，装不装由用户当场决定；
    /// 连不上 GitHub（离线/限流）是常态，只留一行 Debug，不打扰用户。
    /// </summary>
    private void CheckUpdateOnStartup()
    {
        if (_updateCheckedThisProcess || !_settings.AutoCheckUpdate) return;
        _updateCheckedThisProcess = true;
        _ = Task.Run(async () =>
        {
            try
            {
                var update = new UpdateModel();
                var latest = await update.NewInstallerOrNullAsync().ConfigureAwait(false);
                if (latest == null) return;
                AppLog.UpdateFound(latest.Version, UpdateClient.CurrentVersion());
                _ = Dispatcher.BeginInvoke(() => OpenUpdatePrompt(update, latest));
            }
            catch (Exception e)
            {
                AppLog.UpdateCheckFailed(e.GetType().Name);
            }
        });
    }

    /// <summary>
    /// 在 UI 线程弹更新确认框：复用 <see cref="UpdatePromptWindow"/>，和「设置→关于」里手动检查走同一条
    /// 「先确认、后下载、安装再点一次」的路径，两处体验一致。窗口已开着就别叠第二层。
    /// </summary>
    private void OpenUpdatePrompt(UpdateModel update, GitHubRelease latest)
    {
        if (_updatePrompt is { } open)
        {
            open.Activate();
            return;
        }
        var window = new UpdatePromptWindow(update, latest, ExitApp)
        {
            Owner = VisibleMainWindow
        };
        _updatePrompt = window;
        window.Closed += (_, _) => _updatePrompt = null;
        window.Show();
    }

    private UpdatePromptWindow? _updatePrompt;

    /// <summary>
    /// 三个崩溃挂钩（§14 的"崩溃上报关闭"在这里是结构性的：<see cref="AppLog"/> 里没有 HttpClient、
    /// 没有 URL，崩溃只往 <c>%LOCALAPPDATA%\PushBox\logs</c> 写文件）：
    /// - <c>DispatcherUnhandledException</c>：UI 线程那条路，WPF 只在它有这一个事件；
    /// - <c>AppDomain.CurrentDomain.UnhandledException</c>：非 UI 线程（托盘的消息泵、WinRT 回调）；
    /// - <c>TaskScheduler.UnobservedTaskException</c>：没人管的 Task —— 它要等一次 GC 才浮上来，
    ///   所以引擎那条 Task 另外挂了即时观察者（见 <see cref="OpenMainWindow"/>）。
    /// 一个都不"吞"：<c>Handled</c> 一律不设，崩了就是崩了，悄悄继续跑只会留下一屏看起来正常的假数据。
    /// </summary>
    private void AttachCrashHandlers()
    {
        DispatcherUnhandledException += (_, args) => AppLog.Unhandled("dispatcher", args.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
            AppLog.Unhandled("appdomain", args.ExceptionObject as Exception);
        TaskScheduler.UnobservedTaskException += (_, args) => AppLog.Unhandled("task", args.Exception);
    }

    /// <summary>日志与安装包说的是同一个版本号，来源只有 csproj 里那一个 <c>&lt;Version&gt;</c>。</summary>
    private static string VersionOf() => typeof(App).Assembly.GetName().Version?.ToString(3) ?? "unknown";

    /// <summary>
    /// 设置说"开机自启是开的"，注册表那一笔却不一定还这么想：安装换了目录要把条目跟过来，
    /// 条目被用户在任务管理器的"启动"页里删掉则顺着他把设置改成关——下次开机偷偷再塞回去是不可接受的。
    /// 没开过自启的人一行注册表都不碰。
    /// </summary>
    private void ReconcileAutoStart(string exe)
    {
        if (!_settings.AutoStartEnabled) return;
        var desired = AutoStart.CommandFor(exe);
        switch (AutoStart.DriftOf(AutoStart.Read(), desired))
        {
            case AutoStartDrift.AlreadyRight:
                return;
            case AutoStartDrift.MovedInstall:
                try
                {
                    AutoStart.Apply(true, exe);
                }
                catch (Exception e)
                {
                    // 写不进去（组策略锁了 Run）就把菜单上的勾摘掉，别让设置里留着一个做不到的开关
                    AppLog.AutoStartWriteFailed(e.GetType().Name);
                    Update(_settings with { AutoStartEnabled = false });
                }

                return;
            default:
                Update(_settings with { AutoStartEnabled = false });
                return;
        }
    }

    /// <summary>
    /// 装配主窗与它要的服务。<paramref name="show"/> 为假是开机自启那条路：窗口对象照建一扇，
    /// 但一次都不 Show——托盘要读的未读数、连接状态，以及引擎状态驱动的重画都挂在窗口上，
    /// 而它们在窗口构造时就接好了（见 <see cref="MainWindow"/> 构造里的 Wire）。
    /// </summary>
    public void OpenMainWindow(bool show = true)
    {
        var db = _db ??= DeviceStore.OpenDefault();
        var identities = _identities ??= new IdentityStore();
        var reporter = _reporter ??= new ReadReporter(db, identities, _client);
        var media = _media ??= new MediaLoader(_client);
        var tally = _tally ??= new ConversationTally();
        var notifier = _notifier ??= new ToastNotifier(
            new AlertPolicy(() => _settings), tally, media, identities.Load,
            avatarBaseDir: ToastAvatar.DirectoryOf(), fallback: plan => _tray?.Balloon(plan));
        // 头像按 (频道, 发送者) 覆盖写、token 不变：帧里带着 sender_avatar 是"这人现在有头像"的唯一信号，
        // 作废本地那份（内部按 token 节流），再让画着这一格的控件重取
        var engine = _engine ??= new SyncEngine(identities, db, _client, notifier,
            onIngest: frame => media.InvalidateAvatar(frame.SenderAvatar));
        engine.StateChanged += state => reporter.SetOnline(state is SyncState.Online);

        var window = new MainWindow(db, identities, reporter,
            new SearchService(db, identities, _client), new DeleteReporter(db, identities, _client, media),
            media, engine, GeometryOf(_settings));
        window.GeometryChanged += geometry => Update(_settings with
        {
            WindowLeft = geometry.Left,
            WindowTop = geometry.Top,
            WindowWidth = geometry.Width,
            WindowHeight = geometry.Height,
            ListWidth = geometry.ListWidth,
            WindowMaximized = geometry.Maximized
        });
        window.Closing += OnWindowClosing;
        window.TrayRefresh += () => RefreshTray(window);
        window.SettingsRequested += OpenSettings;
        // 空态上的"重试"：再跑一次回放。引擎归这里，窗口只负责问
        window.RestoreRequested += () => _ = RestoreHistoryAsync(window);
        MainWindow = window;
        // 后台启动这里就是全部差别：托盘与同步照常，主窗留给用户从托盘点开
        if (show) window.Show();

        RefreshTray(window);
        reporter.SetOnline(engine.State is SyncState.Online);
        // 循环里的非协议异常由引擎自己带出来；这里不等它，首帧不该被一次握手拖住。
        // 但那条 Task 也不能真没人管：只靠 UnobservedTaskException 要等一次 GC 才看得见，
        // 而"同步循环崩了"是顶栏卡在'连接中'唯一的解释，必须当场落一行。观察者只重抛不了什么，
        // 它只读异常类型名（Message 一律不进日志），所以这里也不用怕它把正文带出去。
        var loop = engine.Start(identities.Load());
        // 开屏不能只等着"下一条消息"把历史带进来：一台刚注册完、服务端还没再发过消息的机器
        // 永远收不到 resume_gap 帧，于是主界面开出来是一片空白。手机那边 join 完立刻回放，PC 对齐。
        _ = RestoreHistoryAsync(window);
        if (!_loopWatched)
        {
            _loopWatched = true;
            loop.ContinueWith(faulted => AppLog.SyncLoopCrashed(
                    faulted.Exception == null ? "unknown" : faulted.Exception.GetBaseException().GetType().Name),
                TaskContinuationOptions.OnlyOnFaulted);
        }
    }

    /// <summary>
    /// 开机回放 / 空态上的"重试"。结局要交回窗口：一次没取回来如果被吞掉，界面就一直停在"正在同步"，
    /// 而用户手里唯一能做的动作就是重试——所以这三态（取着 / 没有 / 没取回来）必须由这里说清楚。
    /// 引擎内部把网络错误翻成了结局（<see cref="ReplayOutcome.Failed"/>），这里只兜住解码之类的意外。
    /// </summary>
    private async Task RestoreHistoryAsync(MainWindow window)
    {
        var engine = _engine;
        if (engine is null) return;

        window.BeginRestore();
        ReplayOutcome outcome;
        try
        {
            outcome = await engine.RestoreHistoryAsync();
        }
        catch (OperationCanceledException)
        {
            // 退出/登出把这一轮取消了：窗口正在关，三态没有可画的地方
            return;
        }
        catch (Exception e)
        {
            AppLog.HistoryRestoreFailed(e.GetType().Name);
            outcome = ReplayOutcome.Failed;
        }

        window.EndRestore(outcome);
    }

    /// <summary>
    /// settings.json 里那五个数只有凑齐了才算"记过"：缺一个就整体按没记过处理，
    /// 由主窗走默认尺寸再夹工作区。分开判会让半份几何拼出一个不存在的窗口。
    /// </summary>
    private static WindowGeometry? GeometryOf(ClientSettings settings) =>
        settings is
        {
            WindowLeft: { } left, WindowTop: { } top, WindowWidth: { } width,
            WindowHeight: { } height, ListWidth: { } list
        }
            ? new WindowGeometry(left, top, width, height, list, settings.WindowMaximized)
            : null;

    /// <summary>§8.1：关闭按钮 = 最小化到托盘，可设为退出。这一句就是那个"可设为退出"的分支。</summary>
    private void OnWindowClosing(object? sender, CancelEventArgs e)
    {
        // 退出空间正在收这一扇：那一次必须真的关掉，而不是又藏回托盘
        if (_exiting || _loggingOut || !_settings.CloseToTray || sender is not MainWindow view) return;
        e.Cancel = true;
        view.HideToTray();
    }

    /// <summary>点通知/按"全部已读"之后落到系统那一侧的收尾：会话被看过，那一组通知就该收掉了。</summary>
    private void HandleTarget(ToastTarget? target)
    {
        if (target == null) return;
        var group = ToastPlan.GroupOf(target.ChannelNo, target.SenderId);

        if (target.Kind == ToastTargetKind.MarkRead)
        {
            // 不唤窗口：这句就是"我看过了，别再挂着"，把用户从别的工作里拽出来是反的
            _reporter?.MarkRead(target.ChannelNo, target.SenderId);
            _notifier?.Forget(group);
            (MainWindow as MainWindow)?.Repaint();
            return;
        }

        if (MainWindow is not MainWindow view)
        {
            // 首启还没注册就点进来了：这一条会话在这台机器上还不存在，除了把注册界面等着，无事可做。
            // "等着"必须是主动的一步而不是顺路：没有身份文件就没有设备密钥，OpenMainWindow 里那句
            // identities.Load() 当场抛 FileNotFoundException（干净机器 + 一个 pushbox: 冷启动参数，
            // W15-c 的安装包实测就是这样倒在 dispatcher 上——留痕里那行 FTL 是它唯一的遗言）。
            var identities = _identities ??= new IdentityStore();
            if (!identities.Exists()) return;
            OpenMainWindow();
            if (MainWindow is not MainWindow opened) return;
            view = opened;
        }

        view.ShowFromTray();
        view.OpenConversation(target.ChannelNo, target.SenderId);
        _notifier?.Forget(group);
    }

    private TrayActions BuildTrayActions() => new(
        Open: () =>
        {
            if (MainWindow is MainWindow view) view.ShowFromTray();
            else if (Enrolled) OpenMainWindow();
            // 退出空间之后托盘还在：那一下该落到首启窗，而不是拿一份不存在的身份去开主窗
            else OpenFirstLaunch();
        },
        OpenSettings: () =>
        {
            if (Enrolled) OpenSettings();
            else OpenFirstLaunch();
        },
        Exit: ExitApp,
        SetNotifications: on => Update(_settings with { NotificationsEnabled = on }),
        SetQuiet: on => Update(_settings with { QuietEnabled = on }),
        SetAutoStart: on => ApplySettings(_settings with { AutoStartEnabled = on }),
        SetCloseToTray: on => Update(_settings with { CloseToTray = on }));

    /// <summary>本机有没有身份文件。托盘那两条与"退出空间之后该摆哪扇窗"都读它，
    /// 判的是磁盘上的事实而不是内存里的某个标志——身份文件也可能被别的进程删掉。</summary>
    private bool Enrolled => (_identities ??= new IdentityStore()).Exists();

    /// <summary>首启窗只留一扇：退出空间之后从托盘再点"打开"、或者注册到一半又点一下，都不该叠出第二扇。</summary>
    private void OpenFirstLaunch()
    {
        if (_firstLaunch is { } open)
        {
            open.Activate();
            return;
        }

        var window = new FirstLaunchWindow(new OnboardingModel());
        window.Closed += (_, _) =>
        {
            if (ReferenceEquals(_firstLaunch, window)) _firstLaunch = null;
        };
        _firstLaunch = window;
        window.Show();
    }

    /// <summary>
    /// 子窗挂 owner 只认"真的露着的主窗"。后台启动（自启）时主窗对象在、但一次都没 Show，
    /// 挂一个看不见的 owner 会把设置窗一起压住；这时候让它独立出现更合理。
    /// </summary>
    private Window? VisibleMainWindow =>
        MainWindow is MainWindow { IsVisible: true } view ? view : null;

    /// <summary>
    /// W16 的设置窗。它由装配层开而不是主窗自己 new：主窗不认识频道、设备这些服务，
    /// 正如同它不认识托盘。同一份 <see cref="ClientSettings"/> 从这儿交回去，两个入口不会各存一份状态。
    /// </summary>
    private void OpenSettings()
    {
        if (_settingsWindow is { } open)
        {
            open.Activate();
            return;
        }

        var identities = _identities ??= new IdentityStore();
        var window = new SettingsWindow(new SettingsModel(_client, identities), _settings, ApplySettings,
            new UpdateModel(), ExitApp, logout: () => _ = LogoutSpaceAsync())
        {
            Owner = VisibleMainWindow
        };
        // 频道换发号码、归档这些改动会让服务端广播 config 帧，也可能在帧到之前就想看一眼结果；
        // 关窗时补一次重画，主界面就不会停留在设置之前的那一份会话名与角标上
        window.Closed += (_, _) =>
        {
            _settingsWindow = null;
            (MainWindow as MainWindow)?.Repaint();
        };
        _settingsWindow = window;
        window.Show();
    }

    /// <summary>设置窗交回来的那一份。开机自启不只是落盘，还要真的动那一笔注册表——写不进去就把勾摘掉，
    /// 与托盘菜单那条路同一个处理（留着一个做不到的开关比开关失灵更糟）。</summary>
    private void ApplySettings(ClientSettings next)
    {
        if (next.AutoStartEnabled != _settings.AutoStartEnabled)
        {
            try
            {
                AutoStart.Apply(next.AutoStartEnabled, ExePath);
            }
            catch (Exception e)
            {
                AppLog.AutoStartWriteFailed(e.GetType().Name);
                Update(next with { AutoStartEnabled = false });
                return;
            }
        }

        Update(next);
    }

    private void Update(ClientSettings next)
    {
        // 主题先铺色再落盘：设置页选完的那一下就该看见变化，而不是等下一次系统事件
        ThemeManager.SetChoice(next.Theme);
        _settings = next;
        try
        {
            next.Save();
        }
        catch (Exception e)
        {
            // 落不了盘也要让这次的开关生效：判定读的是内存里那一份，重启回到旧值总比开关失灵好解释。
            // 但"重启怎么又变回去了"这个问题只有这一行能回答
            AppLog.SettingsSaveFailed(e.GetType().Name);
        }

        _tray?.Refresh(_settings);
    }

    private void RefreshTray(MainWindow view) =>
        _tray?.SetTooltip(TrayIcon.TooltipFor(view.TrayState, view.TrayUnread));

    /// <summary>
    /// §7.3 退出空间（设置页那一枚危险按钮的落点）。由装配层做，是因为要停的引擎、要关的库、要换的窗都归它；
    /// 设置窗只负责问一句——旧实现把 <c>File.Delete</c> 留在窗口里，库还被引擎攥着，删失败又被吞掉，
    /// 于是机器上剩下一份"身份没了、库还在"的半退出。四步的顺序由 <see cref="SpaceReset"/> 钉住。
    /// 多空间切换（同时留几份身份与库）这一轮不做：身份、库、两份图片缓存都按固定路径放，
    /// 要能切换得先把它们搬进"每空间一个目录"，那是独立的一步；现在"换一个空间"就是退出再从首启加入。
    /// </summary>
    private async Task LogoutSpaceAsync()
    {
        _loggingOut = true;
        try
        {
            var reset = new SpaceReset(
                stopSync: async () =>
                {
                    if (_engine is not { } engine) return;
                    _engine = null;
                    await engine.StopAsync();
                },
                closeWindows: () =>
                {
                    // 设置窗的 Closed 里会顺手重画主窗，所以两扇窗都必须排在关库之前
                    _settingsWindow?.Close();
                    // 先把下一扇窗摆上：默认的 OnLastWindowClose 在主窗一关就会把整个应用收掉，
                    // 而这时候同步刚停、库还没删——收在这里等于退出空间只做了半截
                    OpenFirstLaunch();
                    if (MainWindow is MainWindow view)
                    {
                        MainWindow = null;
                        view.Close();
                    }
                },
                closeDatabase: () =>
                {
                    _db?.Dispose();
                    _db = null;
                    // 这些服务都攥着刚关掉的那份库：留着它们等下一次注册时被 ??= 复用，就是拿旧库去查新空间
                    _reporter = null;
                    _media = null;
                    _tally = null;
                    _notifier = null;
                },
                purge: () => LocalSpace.Purge(IdentityStore.DefaultDirectory));

            var left = await reset.RunAsync();
            AppLog.SpaceCleared(left.Count);
            // 托盘还在：它得先退回"没连任何空间"的样子，别继续挂着上一个空间的未读数
            _tray?.SetTooltip(TrayIcon.TooltipFor(null, 0));
            OpenFirstLaunch();
            if (left.Count > 0) TellAboutLeftovers(left);
        }
        catch (Exception e)
        {
            // 到这一步窗口已经关掉了：不把这一句摆出来，用户看到的就只是"点了没反应"。
            // 仍然是首启（而不是回到那扇主窗）：退出走到一半的机器上继续收消息，比重新接入更不该发生
            AppLog.SpaceLogoutFailed(e.GetType().Name);
            MessageBox.Show("退出空间没走完：" + Outcome<string>.Describe(e), "PushBox",
                MessageBoxButton.OK, MessageBoxImage.Error);
            OpenFirstLaunch();
        }
        finally
        {
            _loggingOut = false;
        }
    }

    /// <summary>清不掉的必须自己说出来：一句"已退出"底下藏着一份旧库，比退出失败更糟。</summary>
    private static void TellAboutLeftovers(IReadOnlyList<string> left)
    {
        MessageBox.Show(
            "退出没有完全成功：下面这些文件还被占用，删不掉。\n\n" + string.Join("\n", left)
            + "\n\n关掉占用它们的程序后重试，或手动删除。",
            "PushBox", MessageBoxButton.OK, MessageBoxImage.Warning);
    }

    private void ExitApp()
    {
        _exiting = true;
        MainWindow?.Close();
        Shutdown();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        // SystemEvents 是静态事件，不解绑窗口对象就回收不掉；引擎和库也都在收工时才关，
        // 因为窗口 Closed 之后还可能再开一扇。
        // 顺序是有意的：先停引擎，再拆托盘——反过来就有一段"通知器还活着但托盘已经没了"的空档，
        // 那期间一条通知会走到一条已经 dispose 的气泡退路上。
        ThemeManager.StopFollowing();
        _engine?.Stop();
        _tray?.Dispose();
        _handoff?.Dispose();
        _db?.Dispose();
        // 收工那一行也得留下：它给前面所有行划定"这台机器上的这段运行到哪结束"。
        // CloseAndFlush 排在最后一句，因为它是把缓冲冲干净并关掉句柄——在它之后写的一切都会丢。
        AppLog.Stopped();
        AppLog.CloseAndFlush();
        base.OnExit(e);
    }

    /// <summary>体检那一段的 argv 读法：开关后面跟的那个参数就是输出文件。</summary>
    public static string? SmokeTarget(string[]? args)
    {
        if (args == null) return null;
        for (var i = 0; i < args.Length; i++)
        {
            if (args[i] == SmokeSwitch) return i + 1 < args.Length ? args[i + 1] : "notify-smoke.txt";
        }

        return null;
    }

    /// <summary>
    /// 通知这条路的真机体检：注册 → 决策 → 计划 → 交给系统，每一步的结果写进文件。
    /// 文案是固定的"体检"两个字，不含任何真实消息（§14），也不需要设备身份和服务端。
    /// </summary>
    private void RunNotifySmoke(string exe, string outFile)
    {
        var lines = new List<string>
        {
            $"exe={exe}",
            $"aumid={ToastRegistration.Aumid} registered={ToastRegistration.Ensure(exe)}",
            $"scheme={ToastRegistration.IsSchemePointedAt(exe)}"
        };

        var settings = ClientSettings.Load();
        lines.Add($"settings notifications={settings.NotificationsEnabled} quiet={settings.QuietEnabled} " +
                  $"auto_start={settings.AutoStartEnabled}");

        var tally = new ConversationTally();
        var notifier = new ToastNotifier(new AlertPolicy(() => settings with { QuietEnabled = false }), tally,
            fallback: plan => lines.Add($"fallback_used tag={plan.Tag}"));
        lines.Add($"setting={notifier.SettingProbe()}");

        notifier.Notify(ProbeFrame("warning"));
        notifier.Notify(ProbeFrame("error"));
        lines.Add($"delivered={notifier.Delivered} suppressed={notifier.Suppressed} " +
                  $"failure={notifier.LastFailure ?? "-"}");

        // 静默时段命中：把窗口开成"整天"，低级别那条必须降级、error 那条必须还能出声。
        // 这一步不再真发通知，只看决策——它已经有 xunit 用例，这里只是让体检文件里也留一行
        var quiet = settings.WithQuietWindow(true, 0, ClientSettings.MinutesPerDay - 1);
        lines.Add($"quiet_info={AlertPolicy.Evaluate(quiet, "info", false, DateTimeOffset.Now).Urgency} " +
                  $"quiet_error={AlertPolicy.Evaluate(quiet, "error", false, DateTimeOffset.Now).Urgency}");

        try
        {
            File.WriteAllText(outFile, string.Join(Environment.NewLine, lines) + Environment.NewLine);
        }
        catch (Exception e)
        {
            System.Diagnostics.Debug.WriteLine($"体检结果写不出去：{e.GetType().Name}");
        }
    }

    /// <summary>一条用于体检的帧：正文和发送者都是固定值，绝不从库里取。</summary>
    private static MessageFrame ProbeFrame(string level) => new(
        Seq: 1L,
        Id: "SMOKETEST0000000000000000000",
        ChannelNo: "smoke-channel",
        SenderId: "smoke-sender",
        SenderName: "通知体检",
        SenderAvatar: null,
        Type: "text",
        Title: "体检",
        Summary: "这条来自 --notify-smoke，不含真实消息",
        Content: null,
        Level: level,
        Url: null,
        MediaStatus: null,
        Media: Array.Empty<MediaSlot>(),
        Notify: true,
        CreatedAt: DateTimeOffset.Now.ToUnixTimeMilliseconds(),
        Truncated: false);

    private static string ExePath => Environment.ProcessPath ?? string.Empty;
}
