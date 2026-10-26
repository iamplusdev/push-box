using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using PushBox.Windows.Controls;
using PushBox.Windows.Data;
using PushBox.Windows.Media;
using PushBox.Windows.Sync;
using PushBox.Windows.Themes;

namespace PushBox.Windows.Views;

/// <summary>
/// §8.1/§8.4 W12 的主窗口。这里只做三件事：把 <see cref="MainModel"/> 的判定搬上控件、
/// 把控件上的动作交回模型、把模型和引擎的"变了"通知转成一次重画。
/// 任何"该显示什么"的判定都不写在这一页——它们都在 <see cref="MainModel"/>，能被普通用例断言。
/// </summary>
public partial class MainWindow : Window
{
    private readonly MainModel _model;
    private readonly MediaLoader _media;
    private readonly SyncEngine? _engine;

    private DeviceIdentity? _identity;
    private MarkdownStyle? _style;

    /// <summary>重画会话列表时会把选中项设回去，那一下不是用户点的，不能再触发一次"打开会话"。</summary>
    private bool _applyingSelection;

    /// <summary>时间线是不是停在底部：只有停在底部才有"来新消息就跟到底"的语义，正在翻历史时不该被拽走。</summary>
    private bool _stuckToBottom = true;

    /// <summary>刚往上翻出一页历史：下一次布局要把多出来的那段高度补回滚动位置。</summary>
    private bool _restoringScroll;

    /// <summary>没贴在底部时落进来的新消息数（§9.3 的胶囊），回到底部就清零。</summary>
    private int _newBelow;

    /// <summary>会话列表那一栏的宽度。默认 260，用户拖过手柄之后记住拖到的值——
    /// <see cref="ApplyPaneWidth"/> 每次窗口尺寸变化都会重摆列宽，不回记就会把用户拖的宽度抹掉。</summary>
    private double _listWidth = MainModel.ListDefaultWidth;

    public MainWindow(DeviceStore db, IdentityStore identities, ReadReporter reporter,
        SearchService search, DeleteReporter deletions, MediaLoader media, SyncEngine? engine = null)
    {
        InitializeComponent();
        // 写死的 1080×680 在 1366×768@150% 上比工作区还大（可见区约 910×512），开窗就把内容推到屏幕外。
        // 只在启动时夹一次：之后用户自己拖大/最大化都按他的来
        Width = MainModel.FitToWorkArea(Width, SystemParameters.WorkArea.Width);
        Height = MainModel.FitToWorkArea(Height, SystemParameters.WorkArea.Height);
        _model = new MainModel(db, identities, reporter, search, deletions, new FulltextService(db, identities));
        Prompt = AskForText;
        PickImageFile = PickImageFromDisk;
        _media = media;
        _engine = engine;
        // 头像这一格自己取字节，所以取字节的这条路要在窗口装配时接上（只有窗口有 MediaLoader 和身份）
        AvatarBox.Loader = token => _media.AvatarAsync(Identity(), token);
        // 服务端覆盖写了某个头像之后，画着同一个 token 的格子要重取一次：缓存已经作废，重画就会取到新的
        _media.AvatarInvalidated += AvatarBox.Invalidate;
        // 拖完才记账：拖动过程中列宽是连续变的，每一下都写会让 ApplyPaneWidth 和手柄互相抢
        ListSplitter.DragCompleted += (_, _) =>
        {
            if (Body.ColumnDefinitions[1].Width.IsAbsolute)
                _listWidth = Body.ColumnDefinitions[1].Width.Value;
        };
        // 订阅在**构造时**接上，而不是等 OnContentRendered：开机自启那一轮主窗一次都不 Show，
        // 而托盘要读的未读数、连接状态，以及"引擎每落一条就重画"都挂在这里。
        // 放在渲染回调里等于把"后台启动能不能收消息"寄托在"装配层别忘了补一次"上
        Wire();
    }

    /// <summary>
    /// 二次确认的落点（§9.3）。做成可替换的委托而不是直接调 <see cref="MessageBox"/>：用例要能钉住
    /// "问的是哪两行话、取消的时候一条都不许动"，而 MessageBox 会把测试线程挂在等人点上。
    /// </summary>
    public Func<string, string, bool> Confirm { get; set; } = (question, detail) =>
        MessageBox.Show(question + "\n\n" + detail, "PushBox", MessageBoxButton.YesNo, MessageBoxImage.Warning)
        == MessageBoxResult.Yes;

    /// <summary>
    /// 要一个字进来的那类动作（§7.3 的重命名）问的那一句。返回 null 就是"取消"，空串是有意义的答复
    /// （"把自定义名清掉，落回服务端下发的那个名字"），所以这两件事不能合并成一个返回值。
    /// 与 <see cref="Confirm"/> 同一个理由做成可替换的委托：用例钉的是"问了什么、取消时动不动库"。
    /// </summary>
    public Func<string, string, string?> Prompt { get; set; }

    /// <summary>§7.3「设头像」的文件选择。与上面两条同一个理由做成委托：用例不给它一个真的文件就不会动库。</summary>
    public Func<string?> PickImageFile { get; set; }

    private string? AskForText(string title, string current) => PromptDialog.Show(this, title, current);

    private string? PickImageFromDisk()
    {
        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = "选一张头像",
            Filter = "图片|*.png;*.jpg;*.jpeg;*.gif|所有文件|*.*"
        };
        return dialog.ShowDialog(this) == true ? dialog.FileName : null;
    }

    /// <summary>取字节要签名，签名要身份；身份到第一次真的取图时才读盘（没图的会话不该碰 DPAPI）。</summary>
    private DeviceIdentity Identity() => _identity ??= _model.Identity();

    // 给测试用的只读视图（与 FirstLaunchWindow 同一手法）：XAML 写坏——x:Name 对不上、
    // 面板初始状态不对、模板没挂上——要能在 dotnet test 里被抓住，而不是等人去点。
    public Visibility ConversationListProbe => ConversationList.Visibility;
    public Visibility NoConversationProbe => NoConversationBar.Visibility;
    public Visibility BackButtonProbe => BackButton.Visibility;
    public Visibility StatusDotProbe => StatusDot.Visibility;
    public bool HasRowTemplate => ConversationList.ItemTemplate is not null;

    /// <summary>选中态那套样式没挂上时，列表会退成系统蓝高亮——深色 token 下它把会话名压得看不见。</summary>
    public bool HasRowContainerStyle => ConversationList.ItemContainerStyle is not null;

    /// <summary>§9.3 那颗"新消息"胶囊：只有用户没贴在底部时才可能出现，所以它和滚动位置是一对。</summary>
    public Visibility NewBelowProbe => NewBelow.Visibility;
    public string NewBelowTextProbe => NewBelowText.Text;

    /// <summary>点通知之后到底落在了哪条会话上——这条只有把窗口对象建出来才看得见。</summary>
    public Views.ConversationItem? SelectedConversationProbe => _model.Selected;

    /// <summary>W14 的回滚提示条：删除没成才说话，平时收起（§9.3 的行内口径，不是 Toast）。</summary>
    public Visibility NoticeProbe => NoticeBar.Visibility;

    public string NoticeTextProbe => NoticeText.Text;

    /// <summary>会话行那套右键菜单（§7.3 四项 + W14 的清空）是模板里的东西，只有把 DataTemplate 真加载一次才看得见。
    /// 认的是 <c>Tag</c> 而不是 Header：置顶/免打扰那两行的字会随这一行的现状态改写，Tag 才是稳定的。</summary>
    public IReadOnlyList<string> RowMenuTags => (ConversationList.ItemTemplate?.LoadContent() as FrameworkElement)?
        .ContextMenu?.Items.OfType<MenuItem>().Select(entry => entry.Tag as string ?? string.Empty).ToList()
        ?? new List<string>();

    // ---- 删除与清空（W14）---------------------------------------------------------------------------

    /// <summary>
    /// 气泡右键「删除这条消息」走这一条：确认 → 模型的乐观删除 + 上报 → 只有回滚才说话。
    /// 这里<b>不</b>套 <c>ConfigureAwait(false)</c>：续延要回 UI 线程动提示条，而模型那次回调
    /// （乐观那一刻的重画）是在当前线程上被同步调起的，两端都得留在有 Dispatcher 的那一侧。
    /// </summary>
    public async Task DeleteMessageFromUiAsync(string msgId)
    {
        if (!Confirm(MainModel.DeleteMessageQuestion, MainModel.DeleteMessageDetail)) return;
        var result = await _model.DeleteMessageAsync(msgId);
        ShowNotice(result.Notice);
    }

    /// <summary>
    /// 会话行右键「清空聊天记录」。会话名从那条行上拿，而不是回库裡查——点了哪一行就清空哪一条，
    /// 中间就算被别的帧改过名字，确认框里说的也还是用户眼前看到的那个。
    /// </summary>
    public async Task ClearConversationFromUiAsync(string channelNo, string senderId, string displayName)
    {
        if (!Confirm(MainModel.ClearConversationQuestion(displayName), MainModel.ClearConversationDetail)) return;
        var result = await _model.ClearConversationAsync(channelNo, senderId);
        ShowNotice(result.Notice);
    }

    /// <summary>成功的两种结局都不说话：屏幕上那条没了就是交代。空串就是"把这行收起来"。</summary>
    private void ShowNotice(string? message)
    {
        NoticeBar.Visibility = string.IsNullOrEmpty(message) ? Visibility.Collapsed : Visibility.Visible;
        NoticeText.Text = message ?? string.Empty;
    }

    private void OnNoticeDismissed(object sender, MouseButtonEventArgs e) => ShowNotice(null);

    /// <summary>
    /// 右键菜单挂在模板里，所以点击回调收到的只是 <see cref="MenuItem"/>：先把那一行的数据对象找回来。
    /// <c>DataContext</c> 和 <c>PlacementTarget.DataContext</c> 两条都试——ContextMenu 住在弹出树上，
    /// 它的数据上下文是从放置目标继承来的，两处都认一次就不必赌 WPF 的版本行为。
    /// </summary>
    private void OnClearConversation(object sender, RoutedEventArgs e)
    {
        var row = RowOf(sender);
        if (row is null) return;
        _ = ClearConversationFromUiAsync(row.ChannelNo, row.SenderId, row.DisplayName);
    }

    /// <summary>
    /// §7.3 的三项本机设置走的是同一个"找到我右键点的那一行"的前奏，所以菜单项只把那一行交出来：
    /// 置顶/免打扰是翻一下现状态，重命名要先问一句新名字。
    /// </summary>
    private void OnTogglePin(object sender, RoutedEventArgs e)
    {
        var row = RowOf(sender);
        if (row is null) return;
        _model.TogglePinned(row.ChannelNo, row.SenderId);
    }

    private void OnToggleMute(object sender, RoutedEventArgs e)
    {
        var row = RowOf(sender);
        if (row is null) return;
        _model.ToggleMuted(row.ChannelNo, row.SenderId);
    }

    private void OnRenameConversation(object sender, RoutedEventArgs e)
    {
        var row = RowOf(sender);
        if (row is null) return;
        var next = Prompt("重命名这条会话", row.DisplayName);
        if (next == null) return;
        _model.Rename(row.ChannelNo, row.SenderId, next);
    }

    /// <summary>§7.3「设头像」：选了图就复制进应用目录，没选（取消）就什么都不动。</summary>
    private void OnSetAvatar(object sender, RoutedEventArgs e)
    {
        var row = RowOf(sender);
        if (row is null) return;
        var file = PickImageFile();
        if (file is not { Length: > 0 }) return;
        _model.SetAvatar(row.ChannelNo, row.SenderId, file);
    }

    /// <summary>置顶/免打扰那两行的字要跟着这一行的现状态走（Android 的 conv_action_pin / _unpin 同一张表）。</summary>
    private void OnConversationMenuOpening(object sender, RoutedEventArgs e)
    {
        if (sender is not ContextMenu menu) return;
        var row = menu.PlacementTarget is FrameworkElement target
            ? target.DataContext as ConversationItem
            : null;
        foreach (var entry in menu.Items.OfType<MenuItem>())
        {
            if (row is null) continue;
            entry.Header = (entry.Tag as string) switch
            {
                "pin" => row.Pinned ? "取消置顶" : "置顶",
                "mute" => row.Muted ? "取消免打扰" : "免打扰",
                _ => entry.Header
            };
        }
    }

    /// <summary>顶栏那颗齿轮：设置窗由装配层开（主窗不认识设置窗，正如它不认识托盘）。</summary>
    private void OnOpenSettings(object sender, RoutedEventArgs e) => SettingsRequested?.Invoke();

    /// <summary>空会话列表时引导用户打开设置创建频道。</summary>
    private void OnOpenSettingsFromEmpty(object sender, RoutedEventArgs e) => SettingsRequested?.Invoke();

    /// <summary>
    /// 齿轮与箭头是字形，没法像带文字的按钮那样挂 AccessKey，所以用窗口级组合键补上键盘入口：
    /// Alt+S 开设置、Alt+← 退回"没选中"。<c>Alt</c> 按住时实际键码在 <see cref="KeyEventArgs.SystemKey"/> 上。
    /// </summary>
    private void OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (Keyboard.Modifiers != ModifierKeys.Alt) return;
        if (e.SystemKey == Key.S)
        {
            SettingsRequested?.Invoke();
            e.Handled = true;
        }
        else if (e.SystemKey == Key.Left && _model.Selected is not null)
        {
            _model.CloseConversation();
            e.Handled = true;
        }
    }

    /// <summary>菜单项收到的只有 <see cref="MenuItem"/>，那一行的数据要从弹出树反查回来。
    /// <c>DataContext</c> 与 <c>PlacementTarget.DataContext</c> 两条都试：ContextMenu 住在弹出树上，
    /// 它的数据上下文是从放置目标继承来的，两处都认一次就不必赌 WPF 的版本行为。</summary>
    private static ConversationItem? RowOf(object sender) => sender switch
    {
        MenuItem item when item.DataContext is ConversationItem own => own,
        MenuItem item => (item.Parent as ContextMenu)?.PlacementTarget is FrameworkElement target
            ? target.DataContext as ConversationItem
            : null,
        _ => null
    };

    /// <summary>气泡上的右键菜单在这里现建（不写进 XAML）：删除项点的是<b>这一条</b>消息的 id，
    /// 由调用方用闭包带进来，就不必再从弹出树反查气泡是哪一个。</summary>
    private static ContextMenu MessageMenu(Action delete)
    {
        var entry = new MenuItem { Header = "删除这条消息…" };
        entry.Click += (_, _) => delete();
        return new ContextMenu { Items = { entry } };
    }

    // ---- 托盘（W13）-------------------------------------------------------------------------------

    /// <summary>窗口这边每次重画都通知装配层来取一次 tooltip 的内容：未读数和连接状态都是这边的账，
    /// 托盘不该自己再算一遍。</summary>
    public event Action? TrayRefresh;

    /// <summary>点了顶栏的"设置"。开哪扇窗、给它哪些服务，都是装配那一层（App）的事。</summary>
    public event Action? SettingsRequested;

    /// <summary>托盘那一行的两样输入：状态文案与未读总数。
    /// 这里给的是带原因的那半句（<see cref="MainModel.StateDetail"/>）而不是顶栏那两字——
    /// 托盘气泡有的是宽度，而它常常是用户唯一还看着的地方（主窗已经收到托盘里去了）。</summary>
    public string TrayState => _engine is null ? string.Empty : MainModel.StateDetail(_engine.State);

    public int TrayUnread => _model.TotalUnread();

    /// <summary>关窗退到托盘：只是藏起来，引擎、库、订阅都留在原处（§8.1）。</summary>
    public void HideToTray() => Hide();

    /// <summary>从托盘或点通知回到前台：最小化过的要一并恢复正常态，否则 Activate bring 不出内容。</summary>
    public void ShowFromTray()
    {
        if (!IsVisible) Show();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        Activate();
    }

    /// <summary>别的来源改了库（通知上的"全部已读"、另一台设备的 read 帧到了窗口却藏着）：重画一次。
    /// 会话列表每次都是现从库里取的，所以这里不需要模型配合做任何失效。</summary>
    public void Repaint() => Refresh();

    /// <summary>
    /// §8.4 W13 的验收点：通知点进来要落在<b>那一条</b>会话上。和从搜索结果点进去同一条路
    /// （先退出搜索态，否则会话列表被结果盖住），差别只在搜索那条是"清掉框"、这一条是"根本没用过框"。
    /// </summary>
    public void OpenConversation(string channelNo, string senderId)
    {
        _model.ExitSearch();
        _model.Open(channelNo, senderId);
        _stuckToBottom = true;
        _newBelow = 0;
        Refresh();
        _ = ScrollToBottomAsync();
    }

    /// <summary>画刷是 <c>Application.Resources</c> 那两份 token 字典的快照，所以换主题时要作废重建。</summary>
    private MarkdownStyle PaintStyle() => _style ??= MarkdownStyle.FromResources();

    protected override void OnContentRendered(EventArgs e)
    {
        base.OnContentRendered(e);
        // 后台启动（自启）那条路在 Show 之前就跑过一次 Refresh，但它被 IsLoaded 挡在绘制之前：
        // Show 之后这一下才是第一屏
        Refresh();
    }

    private bool _wired;

    /// <summary>
    /// 把窗口上的订阅接起来。构造函数会调，所以后台启动（自启）那种"对象在、但一次都不 Show"
    /// 的形态也照样能收消息、刷托盘。方法自身幂等。
    /// </summary>
    private void Wire()
    {
        if (_wired) return;
        _wired = true;
        ConversationList.SelectionChanged += OnConversationSelected;
        BackButton.Click += (_, _) => _model.CloseConversation();
        TimelineScroll.ScrollChanged += OnTimelineScrollChanged;
        SizeChanged += (_, _) => ApplyPaneWidth();
        PreviewKeyDown += OnPreviewKeyDown;

        // 模型的"变了"可能来自线程池（搜索的服务端那半段就是 ConfigureAwait(false) 回来的），
        // 所以订阅的这一层负责把重画送回 UI 线程
        _model.Changed += OnModelChanged;
        ThemeManager.Changed += OnThemeChanged;
        if (_engine is not null)
        {
            _engine.StateChanged += OnEngineState;
            ApplyEngineState(_engine.State);
        }
    }

    protected override void OnClosed(EventArgs e)
    {
        _model.Changed -= OnModelChanged;
        ThemeManager.Changed -= OnThemeChanged;
        _media.AvatarInvalidated -= AvatarBox.Invalidate;
        if (_engine is not null) _engine.StateChanged -= OnEngineState;
        // 停引擎与释放库归装配它们的那一层（App）：窗口只管自己这一屏的订阅
        base.OnClosed(e);
    }

    // ---- 重画 -------------------------------------------------------------------------------------

    private void OnModelChanged()
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(Refresh);
            return;
        }

        Refresh();
    }

    /// <summary>换主题不动模型，只作废画刷快照再重画一棵树：会话行圆点和气泡左竖条都是绑定时算一次的。</summary>
    private void OnThemeChanged()
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(OnThemeChanged);
            return;
        }

        _style = null;
        Refresh();
    }

    private void Refresh()
    {
        // 托盘那一行先报：它读的是纯模型的账（未读数、连接状态），和这一屏画没画出来没有关系。
        // 放在 IsLoaded 之后会漏掉两种常态——"启动就最小化到托盘"根本没渲染过内容，
        // 而关闭后再打开会话（HideToTray 的那条路）窗口也没机会自己重画
        TrayRefresh?.Invoke();
        if (!IsLoaded) return;
        var style = PaintStyle();

        RenderConversations();
        RenderTimeline(style);
        PaintNewBelow();
        ApplyPaneWidth();
    }

    /// <summary>§9.3 的"新消息"胶囊：数的是"用户没贴在底部时落进来的那几条"，一回到栈底就清零。</summary>
    private void PaintNewBelow()
    {
        NewBelow.Visibility = _newBelow > 0 ? Visibility.Visible : Visibility.Collapsed;
        NewBelowText.Text = _newBelow > 0 ? MainModel.NewBelowLabel(_newBelow) : string.Empty;
    }

    private void RenderConversations()
    {
        var conversations = _model.Conversations();
        var selected = _model.Selected;
        _applyingSelection = true;
        ConversationList.ItemsSource = conversations;
        ConversationList.SelectedItem = selected is null
            ? null
            : Find(conversations, selected.ChannelNo, selected.SenderId);
        _applyingSelection = false;
        // 空列表在 PC 上不会像手机那样一眼看出"是没消息还是没加载出来"，所以这句话得自己说
        ConversationEmpty.Visibility = conversations.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void RenderTimeline(MarkdownStyle style)
    {
        var items = _model.Timeline();
        Timeline.Items.Clear();
        foreach (var item in items)
        {
            var node = item switch
            {
                DateDivider divider => BubbleView.Divider(divider.Label, style),
                MessageItem message => BubbleView.Build(message, style, MediaOf(message, style), OpenLink,
                    id => _ = _model.ExpandAsync(id)),
                _ => new TextBlock()
            };
            // §9.3 在 PC 上的落点：手机是左滑/长按，PC 是右键。删除是危险动作，所以只有菜单这一条入口，
            // 不加键盘快捷键——按错一下就把一条消息删掉的代价比少按一次鼠标大得多
            if (item is MessageItem target)
            {
                node.ContextMenu = MessageMenu(() => _ = DeleteMessageFromUiAsync(target.MsgId));
            }

            Timeline.Items.Add(node);
        }

        var opened = _model.Selected is not null;
        NoConversationBar.Visibility = opened ? Visibility.Collapsed : Visibility.Visible;
        ConversationTitle.Text = _model.Selected?.DisplayName ?? string.Empty;
        EmptyText.Text = opened && items.Count == 0 ? _model.EmptyText : string.Empty;
    }

    // ---- 交回模型 ---------------------------------------------------------------------------------

    private void OnConversationSelected(object sender, SelectionChangedEventArgs e)
    {
        if (_applyingSelection || ConversationList.SelectedItem is not ConversationItem item) return;
        _model.Open(item.ChannelNo, item.SenderId);
        // 换会话就是新的一屏，从底部看起：上一屏攒的"新消息"数带走没意义
        _stuckToBottom = true;
        _newBelow = 0;
        _ = ScrollToBottomAsync();
    }

    // ---- 引擎状态 ---------------------------------------------------------------------------------

    private void OnEngineState(SyncState state)
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.BeginInvoke(() => ApplyEngineState(state));
            return;
        }

        ApplyEngineState(state);
    }

    private void ApplyEngineState(SyncState state)
    {
        StatusText.Text = MainModel.DescribeState(state);
        // 正文只说"离线/异常"两字，原因挂在悬停上——菜单 60px 宽，一整句会把这一栏撑成字墙
        StatusArea.ToolTip = MainModel.StateDetail(state);
        // 点与字同色：在线绿 / 断链橙 / 要人处理的红 / 没启动灰，扫一眼就知道该不该管
        var token = MainModel.StateToken(state);
        StatusDot.Fill = BrushOf(token);
        StatusText.Foreground = BrushOf(token);
        StatusDot.Visibility = Visibility.Visible;
        // 帧落库后引擎会把 Online 的游标往前推，所以"有新消息"到这里就是"重取当前会话那一批 + 重画"。
        // 不重取的话新消息只进库不进屏——它正是 §9.3 那句"当前会话内来新消息要跟着滚到底"的前提
        var landed = _model.Reload();
        // 贴在底部就跟着滚到底、不数；正在往上翻历史才把这几种落进来（§9.3 的两条分支）
        if (_stuckToBottom) _newBelow = 0;
        else _newBelow += landed;
        Refresh();
        _ = ScrollToBottomAsync();
    }

    /// <summary>点胶囊：回到底部，顺带清掉攒下的计数。</summary>
    private void OnNewBelowClicked(object sender, MouseButtonEventArgs e)
    {
        _stuckToBottom = true;
        _newBelow = 0;
        PaintNewBelow();
        _ = ScrollToBottomAsync();
    }

    // ---- 滚动与栏宽 -------------------------------------------------------------------------------

    private void OnTimelineScrollChanged(object sender, ScrollChangedEventArgs e)
    {
        // 往上翻历史刚补了一页：把新长出来的那段高度补回偏移，眼睛盯着的那条消息才不会被推走
        if (_restoringScroll && e.ExtentHeightChange > 0)
        {
            _restoringScroll = false;
            TimelineScroll.ScrollToVerticalOffset(e.ExtentHeightChange);
            return;
        }

        _stuckToBottom = TimelineScroll.ScrollableHeight - TimelineScroll.VerticalOffset <= 1;
        // 用户自己滚回到底了：胶囊那句"3 条新消息"已经过时，屏幕上正看着的就是最新的一条
        if (_stuckToBottom && _newBelow > 0)
        {
            _newBelow = 0;
            PaintNewBelow();
        }

        if (e.VerticalOffset > 0 || e.ExtentHeight <= e.ViewportHeight) return;
        if (!_model.LoadEarlier()) return;
        _restoringScroll = true;
    }

    private Task ScrollToBottomAsync()
    {
        // 内容是一棵树，Measure 要等这一轮布局走完才有效，所以排进 Render 而不是当场滚
        return Dispatcher.BeginInvoke(new Action(() =>
        {
            if (_stuckToBottom) TimelineScroll.ScrollToEnd();
        }), DispatcherPriority.Render).Task;
    }

    /// <summary>§8.1 的窄窗口退化单栏：判定与数值都由模型算（<see cref="MainModel.PaneLayoutFor"/>），
    /// 这里只把结果抄到四列上。列表列的 Min/Max 也必须一起抄——只抄 Width，
    /// XAML 里的 <c>MinWidth=200</c> 会把"已经收成 0"的列表夹回 200 像素。</summary>
    private void ApplyPaneWidth()
    {
        // 还没布局过就没有"窗口宽度"可言，此时别把上限算成 0 把整条时间线挤没
        if (ActualWidth <= 0) return;

        var layout = MainModel.PaneLayoutFor(ActualWidth, _model.Selected is not null, _listWidth);
        BackButton.Visibility = layout.Narrow ? Visibility.Visible : Visibility.Collapsed;

        var columns = Body.ColumnDefinitions;
        columns[0].Width = WidthOf(layout.Menu);
        columns[1].Width = WidthOf(layout.List);
        columns[1].MinWidth = layout.ListMinWidth;
        columns[1].MaxWidth = layout.ListMaxWidth;
        columns[2].Width = WidthOf(layout.Splitter);
        columns[3].Width = WidthOf(layout.Conversation);
        Timeline.MaxWidth = MainModel.BubbleMaxWidth(layout.RightPaneWidth, layout.Narrow);
    }

    private static GridLength WidthOf(MainModel.ColumnPlan plan) =>
        plan.Star ? new GridLength(1, GridUnitType.Star) : new GridLength(plan.Width);

    // ---- 渲染用的小工具 ---------------------------------------------------------------------------

    /// <summary>这一条消息的图位：没图就不占位，有图才挂 <see cref="MediaStrip"/>（取字节是异步的）。
    /// 缩略图与大图是两次请求：§4.5 的 <c>size=thumb</c> 是服务端降过级的产物，点开时不能拿它放大充数。</summary>
    private UIElement? MediaOf(MessageItem item, MarkdownStyle style) => item.MediaCount <= 0
        ? null
        : MediaStrip.Build(item.MediaCount,
            idx => _media.ImageAsync(Identity(), item.MsgId, idx, true), style,
            idx => _media.ImageAsync(Identity(), item.MsgId, idx, false));

    /// <summary>只放行 http/https：正文是第三方推进来的，不能让它递一个 <c>file:</c> 或 <c>\\share</c> 过来。</summary>
    private void OpenLink(string url)
    {
        if (!url.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
            && !url.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) return;
        try
        {
            // UseShellExecute 才能把链接交给"系统默认浏览器"：自己拼浏览器可执行文件是给自己找麻烦
            Process.Start(new ProcessStartInfo { FileName = url, UseShellExecute = true });
        }
        catch (Exception)
        {
            // 没有默认浏览器、或那个协议本机没人认领：这条链接就在这儿停住，不弹一个吓人的错
        }
    }

    private Brush BrushOf(string key) => TryFindResource(key) as Brush ?? Brushes.Transparent;

    private static ConversationItem? Find(IReadOnlyList<ConversationItem> items, string channelNo, string senderId)
    {
        foreach (var item in items)
        {
            if (item.ChannelNo == channelNo && item.SenderId == senderId) return item;
        }

        return null;
    }
}
