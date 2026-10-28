using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
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

    /// <summary>刚往上翻出一页历史、这一屏还在重新摆位：这时候的偏移和"贴不贴底"都作不得数。</summary>
    private bool _holding;

    /// <summary>这一屏的首次布局已经落到底了。落底之前不许按"到顶了"翻页——
    /// 虚拟化面板刚开始排的时候偏移还在 0，那不是用户在翻历史，是这一屏还没摆好。</summary>
    private bool _timelineSettled;

    /// <summary>没贴在底部时落进来的新消息数（§9.3 的胶囊），回到底部就清零。</summary>
    private int _newBelow;

    /// <summary>时间线那一份行数据（<see cref="SyncTimeline"/> 往后补的就是它）。</summary>
    private ObservableCollection<TimelineItem>? _rows;

    /// <summary>行模板那一份配置。换主题才会换（<see cref="ConfigFor"/>）。</summary>
    private TimelineConfig? _rowConfig;

    /// <summary>会话列表那一栏的宽度。默认 260，用户拖过手柄之后记住拖到的值——
    /// <see cref="ApplyPaneWidth"/> 每次窗口尺寸变化都会重摆列宽，不回记就会把用户拖的宽度抹掉。</summary>
    private double _listWidth = MainModel.ListDefaultWidth;

    public MainWindow(DeviceStore db, IdentityStore identities, ReadReporter reporter,
        SearchService search, DeleteReporter deletions, MediaLoader media, SyncEngine? engine = null,
        WindowGeometry? restore = null)
    {
        InitializeComponent();
        ApplyGeometry(restore);
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

    /// <summary>关窗（含"收到托盘"那次被取消的关窗）前把当前几何交出去，由装配层落盘。</summary>
    public event Action<WindowGeometry>? GeometryChanged;

    /// <summary>
    /// 尺寸与位置：记过就恢复，没记过就把默认值按工作区夹一次。恢复值也要再夹一遍——
    /// 上次那块显示器可能已经拔了（见 <see cref="WindowPlacement.Restore"/>）。
    /// </summary>
    private void ApplyGeometry(WindowGeometry? restore)
    {
        var work = SystemParameters.WorkArea;
        var screen = new Rect(SystemParameters.VirtualScreenLeft, SystemParameters.VirtualScreenTop,
            SystemParameters.VirtualScreenWidth, SystemParameters.VirtualScreenHeight);
        if (restore is { } saved)
        {
            var placed = WindowPlacement.Restore(saved, work, screen, MinWidth, MinHeight);
            Width = placed.Width;
            Height = placed.Height;
            Left = placed.Left;
            Top = placed.Top;
            _listWidth = saved.ListWidth;
            if (saved.Maximized) WindowState = WindowState.Maximized;
            return;
        }

        // 写死的 1080×680 在 1366×768@150% 上比工作区还大（可见区约 910×512），开窗就把内容推到屏幕外。
        // 只在启动时夹一次：之后用户自己拖大/最大化都按他的来
        Width = MainModel.FitToWorkArea(Width, work.Width);
        Height = MainModel.FitToWorkArea(Height, work.Height);
    }

    /// <summary>
    /// 交出去的那一份。最大化时 <c>Width</c>/<c>Height</c> 报的是最大化之后的尺寸，
    /// 那要按 <see cref="Window.RestoreBounds"/> 记，否则下次还原会还原成一屏铺满的大小。
    /// </summary>
    private WindowGeometry CurrentGeometry()
    {
        var bounds = RestoreBounds;
        if (bounds.IsEmpty || bounds.Width <= 0 || bounds.Height <= 0)
            bounds = new Rect(Left, Top, Width, Height);
        return new WindowGeometry(bounds.Left, bounds.Top, bounds.Width, bounds.Height, _listWidth,
            WindowState == WindowState.Maximized);
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        base.OnClosing(e);
        // 先让装配层决定这次是"收到托盘"还是真退出，再记账：两种走法都该记住用户拖过的位置
        GeometryChanged?.Invoke(CurrentGeometry());
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

    /// <summary>空态那一块现在说的是哪一句、底下那颗按钮指向哪——三态最容易出错的就是"抄错了模型"。</summary>
    public string ConversationEmptyTitleProbe => ConversationEmptyTitle.Text;

    public MainModel.EmptyAction ConversationEmptyActionProbe =>
        ConversationEmptyButton.Tag is MainModel.EmptyAction action ? action : MainModel.EmptyAction.None;

    public Visibility ConversationEmptyButtonProbe => ConversationEmptyButton.Visibility;

    public Visibility TimelineEmptyProbe => TimelineEmpty.Visibility;

    public string TimelineEmptyTitleProbe => TimelineEmptyTitle.Text;

    public Visibility TimelineRetryProbe => TimelineRetryButton.Visibility;

    /// <summary>这一屏时间线里一共多少条（消息 + 日期分隔）。</summary>
    public int TimelineItemCountProbe => Timeline.Items.Count;

    /// <summary>
    /// 面板真正生成出来的行数：虚拟化生效时它只跟视口有关、不跟总数走。
    /// 未生成的那一头 <see cref="ItemContainerGenerator.ContainerFromIndex"/> 给 null，是外面唯一看得见的判据。
    /// </summary>
    public int TimelineRealizedProbe
    {
        get
        {
            var realized = 0;
            for (var i = 0; i < Timeline.Items.Count; i++)
            {
                if (Timeline.ItemContainerGenerator.ContainerFromIndex(i) is not null) realized++;
            }

            return realized;
        }
    }

    /// <summary>离底还有多远（像素）：0 表示正贴着最新一条。</summary>
    public double TimelineFromBottomProbe => TimelineScroll is { } scroller
        ? scroller.ScrollableHeight - scroller.VerticalOffset
        : 0;

    /// <summary>
    /// 可视树里一共几行（<see cref="TimelineRow"/>）。行是模板按需建的，所以这个数只跟视口走；
    /// 它要是跟着条目总数走，说明"建控件"又回到了换数据的那一趟里。
    /// </summary>
    public int TimelineBuiltProbe => CountRows(Timeline);

    private static int CountRows(DependencyObject root)
    {
        var count = root is TimelineRow ? 1 : 0;
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
            count += CountRows(VisualTreeHelper.GetChild(root, i));
        return count;
    }

    /// <summary>时间线背后那一份行数据。重画时整份换掉的话这个实例会换一个——
    /// 面板收到的是 Reset，滚动位置、拖选、已经取到的图跟着一起没。</summary>
    public object? TimelineSourceProbe => Timeline.ItemsSource;

    public Visibility NoConversationProbe => NoConversationBar.Visibility;
    public Visibility BackButtonProbe => BackButton.Visibility;
    public Visibility StatusDotProbe => StatusDot.Visibility;
    public bool HasRowTemplate => ConversationList.ItemTemplate is not null;

    /// <summary>选中态那套样式没挂上时，列表会退成系统蓝高亮——深色 token 下它把会话名压得看不见。</summary>
    public bool HasRowContainerStyle => ConversationList.ItemContainerStyle is not null;

    /// <summary>会话列表真正生成出来的行数：视口外的行在虚拟化下没有容器，行菜单也就无从谈起。</summary>
    public int RowRealizedProbe
    {
        get
        {
            var realized = 0;
            for (var i = 0; i < ConversationList.Items.Count; i++)
            {
                if (ConversationList.ItemContainerGenerator.ContainerFromIndex(i) is not null) realized++;
            }

            return realized;
        }
    }

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

    /// <summary>提示条右边那颗 ×：整条也能点，但键盘用户只有这一颗按得到。</summary>
    private void OnNoticeClosed(object sender, RoutedEventArgs e) => ShowNotice(null);

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

    /// <summary>
    /// 列表空态底下那一颗：三态里有话可做的只有两颗——"没取回来"重试回放，"确实没有"去建频道。
    /// 走哪条看模型这一轮给的 <see cref="MainModel.EmptyAction"/>，窗口不自己猜。
    /// </summary>
    private void OnConversationEmptyAction(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: MainModel.EmptyAction.Retry })
        {
            RestoreRequested?.Invoke();
            return;
        }

        SettingsRequested?.Invoke();
    }

    /// <summary>对话区那半的重试：引擎归装配层，这里只把话交出去。</summary>
    private void OnTimelineRetry(object sender, RoutedEventArgs e) => RestoreRequested?.Invoke();

    /// <summary>
    /// 齿轮与箭头是字形，没法像带文字的按钮那样挂 AccessKey，所以用窗口级组合键补上键盘入口：
    /// Alt+S 开设置、Alt+← 退回"没选中"。<c>Alt</c> 按住时实际键码在 <see cref="KeyEventArgs.SystemKey"/> 上。
    /// 行的右键菜单也在这里补：菜单挂在行模板里的 Grid 上，而 WPF 只认"焦点元素自己的 ContextMenu"，
    /// 那个 Grid 永远拿不到焦点，所以 Shift+F10 / 菜单键本来什么都不会发生。
    /// </summary>
    private void OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Apps || (e.Key == Key.F10 && Keyboard.Modifiers == ModifierKeys.Shift))
        {
            if (OpenRowMenuForKeyboard() is not null) e.Handled = true;
            return;
        }

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

    private ContextMenu? _rowMenu;

    /// <summary>键盘刚开出来的行菜单（关掉就没有了）：测试拿不到模板里那颗菜单实例，只能从窗口问。</summary>
    public ContextMenu? RowMenuProbe => _rowMenu is { IsOpen: true } ? _rowMenu : null;

    /// <summary>
    /// 键盘开"这一行"的菜单。挑的是<b>列表里选中的那一条</b>而不是"最后一个拿焦点的元素"：
    /// 上下键在列表里走的时候，用户眼里的"这一行"就是选中行。
    /// 没选中、或者那一行还没生成出来（虚拟化下视口外的行没有容器）时什么都不做。
    /// </summary>
    public ContextMenu? OpenRowMenuForKeyboard()
    {
        if (ConversationList.ItemContainerGenerator.ContainerFromItem(ConversationList.SelectedItem)
            is not ListBoxItem container) return null;
        if (MenuIn(container) is not { } menu) return null;

        // 模板里那份菜单的落点本来是 MousePoint（右键在哪就在哪开）。键盘开的时候没有鼠标可依，
        // 贴着行下面落——这也是 WPF 自己给键盘开菜单时的落法。关掉要还回去，
        // 否则这一行以后再被右键，也会跑到行下面去。
        menu.Closed += RestoreMousePlacement;
        menu.PlacementTarget = container;
        menu.Placement = PlacementMode.Bottom;
        menu.IsOpen = true;
        return _rowMenu = menu;
    }

    /// <summary>键盘开过的那颗菜单关掉时把落点还给鼠标。</summary>
    private static readonly RoutedEventHandler RestoreMousePlacement = (sender, _) =>
    {
        if (sender is ContextMenu menu) menu.Placement = PlacementMode.MousePoint;
    };

    /// <summary>行模板的根节点上挂的就是那颗菜单：容器 → 内容宿主 → 模板根，逐层往下找。</summary>
    private static ContextMenu? MenuIn(DependencyObject root)
    {
        if (root is FrameworkElement { ContextMenu: { } own }) return own;
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            if (MenuIn(VisualTreeHelper.GetChild(root, i)) is { } found) return found;
        }

        return null;
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

    /// <summary>复制这一条的文字。剪贴板被别的进程攥着时 <c>SetText</c> 会抛，而这行字本来就在屏幕上，
    /// 抄得走就不值得为它弹一个框（与设置页那份 <c>CopyToClipboard</c> 同一个口径）。</summary>
    private static void CopyText(string text)
    {
        if (string.IsNullOrEmpty(text)) return;
        try
        {
            Clipboard.SetText(text);
        }
        catch (System.Runtime.InteropServices.ExternalException)
        {
            // 远程桌面/密码管理器锁着剪贴板：这一次没复制上，屏幕上那份还在
        }
    }

    /// <summary>
    /// 右键"复制图片"：取 <c>size=full</c> 那一份，不是把缩略图放大充数（同看图窗的口径）。
    /// 取不到要说一句人话——剪贴板里悄悄躺着一张上一次复制的图，比什么都没发生更难查。
    /// </summary>
    private async Task CopyImageAsync(string msgId)
    {
        try
        {
            var asset = await _media.ImageAsync(Identity(), msgId, 0, false);
            if (ImageSourceOf.FromBytes(asset.Bytes) is { } image)
            {
                Clipboard.SetImage(image);
                return;
            }

            ShowNotice(asset.Placeholder is { Length: > 0 } text ? text : "这张图片暂时取不到");
        }
        catch (Exception)
        {
            ShowNotice("这张图片暂时取不到");
        }
    }

    // ---- 托盘（W13）-------------------------------------------------------------------------------

    /// <summary>窗口这边每次重画都通知装配层来取一次 tooltip 的内容：未读数和连接状态都是这边的账，
    /// 托盘不该自己再算一遍。</summary>
    public event Action? TrayRefresh;

    /// <summary>点了顶栏的"设置"。开哪扇窗、给它哪些服务，都是装配那一层（App）的事。</summary>
    public event Action? SettingsRequested;

    /// <summary>空态上那颗"重试"：再跑一次开机回放。引擎在装配层手里，这里只把话交出去。</summary>
    public event Action? RestoreRequested;

    /// <summary>回放开始（开机或重试）。三态判定在模型里，这一页只是把装配层的两次调用递进去。</summary>
    public void BeginRestore() => _model.BeginRestore();

    /// <summary>回放收工。结局由引擎给：成 / 被取消 / 没取回来，后两种不能混成一句"还没有消息"。</summary>
    public void EndRestore(ReplayOutcome outcome) => _model.EndRestore(outcome);

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
        Refresh();
        SnapToBottom();
    }

    /// <summary>画刷是 <c>Application.Resources</c> 那两份 token 字典的快照，所以换主题时要作废重建。</summary>
    private MarkdownStyle PaintStyle() => _style ??= MarkdownStyle.FromResources() with { Copy = CopyText };

    protected override void OnContentRendered(EventArgs e)
    {
        base.OnContentRendered(e);
        // 后台启动（自启）那条路在 Show 之前就跑过一次 Refresh，但它被 IsLoaded 挡在绘制之前：
        // Show 之后这一下才是第一屏
        Refresh();

        // 第一屏的焦点给会话列表：不指定的话它落在可视树第一颗按钮上（设置齿轮），
        // 键盘用户一进来按上下键什么都不会动。只放一次——之后从托盘回来不抢用户当下的焦点
        if (_focusPlaced) return;
        _focusPlaced = true;
        ConversationList.Focus();
    }

    private bool _focusPlaced;

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
        // 滚动条在 ItemsControl 的模板里（虚拟化要求它自己就是滚动内容的宿主），所以用冒泡的事件接：
        // 直接订阅哪个实例都行，ScrollChanged 会从模板里那个 ScrollViewer 冒上来
        Timeline.AddHandler(ScrollViewer.ScrollChangedEvent,
            new ScrollChangedEventHandler(OnTimelineScrollChanged));
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
        // 空列表在 PC 上不会像手机那样一眼看出"是没消息还是没加载出来"，所以这句话得自己说；
        // 说哪一句由模型的三态定（正在取 / 确实没有 / 没取回来），窗口不自己拼
        var empty = conversations.Count == 0;
        ConversationEmpty.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
        if (empty) PaintConversationEmpty(_model.ConversationEmptyState);
    }

    /// <summary>列表空态的三样：标题、说明、底下那颗按钮（有去向时才出现）。</summary>
    private void PaintConversationEmpty(MainModel.EmptyState empty)
    {
        ConversationEmptyTitle.Text = empty.Title;
        ConversationEmptyDetail.Text = empty.Detail;
        ConversationEmptyDetail.Visibility = string.IsNullOrEmpty(empty.Detail)
            ? Visibility.Collapsed
            : Visibility.Visible;
        ConversationEmptyButton.Visibility =
            empty.Action == MainModel.EmptyAction.None ? Visibility.Collapsed : Visibility.Visible;
        ConversationEmptyButton.Content =
            empty.Action == MainModel.EmptyAction.Retry ? "重试" : "打开设置创建频道";
        ConversationEmptyButton.Tag = empty.Action;
    }

    /// <summary>
    /// 把时间线那一份行数据同步成 <paramref name="next"/>：头尾相同的都留着，只换中间那段。
    /// 来新消息于是只是尾部追加，往上补一页只是头部插入，两种都不动整条集合——
    /// 整份换掉的代价不只是重建所有行，连"正在读的是哪一条"也会一起丢掉。
    /// </summary>
    private void SyncTimeline(IReadOnlyList<TimelineItem> next)
    {
        if (_rows is not { } rows)
        {
            Timeline.ItemsSource = _rows = new ObservableCollection<TimelineItem>(next);
            return;
        }

        // 行都是 record：值相等就是"这一行没变"，不必拿引用去猜
        var head = 0;
        while (head < rows.Count && head < next.Count && Equals(rows[head], next[head])) head++;
        var tail = 0;
        while (tail < rows.Count - head && tail < next.Count - head
               && Equals(rows[rows.Count - 1 - tail], next[next.Count - 1 - tail])) tail++;

        var gone = rows.Count - head - tail;
        var added = next.Count - head - tail;
        for (var i = 0; i < gone; i++) rows.RemoveAt(head);
        for (var i = 0; i < added; i++) rows.Insert(head + i, next[head + i]);
    }

    private void RenderTimeline(MarkdownStyle style)
    {
        var items = _model.Timeline();
        // 配置先摆好再换数据：换上去的那一趟就按它建行，晚一步的话新行拿到的还是旧主题那份画刷
        RowConfig = ConfigFor(style);
        SyncTimeline(items);

        var opened = _model.Selected is not null;
        NoConversationBar.Visibility = opened ? Visibility.Collapsed : Visibility.Visible;
        ConversationTitle.Text = _model.Selected?.DisplayName ?? string.Empty;
        // 打开的会话一条都没有：正在取 / 确实没有 / 没取回来，三句话都由模型定
        var empty = opened && items.Count == 0;
        TimelineEmpty.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
        if (empty) PaintTimelineEmpty(_model.TimelineEmptyState);
    }

    /// <summary>
    /// 行模板要的那份配置（见 <see cref="TimelineConfig"/>）。它只跟主题走：会话、滚动、未读都不在里面，
    /// 所以一轮里同一份能一直用下去。做成窗口的属性而不是每次直接塞给模板，是因为行是靠绑定取的——
    /// 换主题那一刻得能通知到已经建好的那几行。
    /// </summary>
    public static readonly DependencyProperty RowConfigProperty = DependencyProperty.Register(
        nameof(RowConfig), typeof(TimelineConfig), typeof(MainWindow), new PropertyMetadata(null));

    public TimelineConfig? RowConfig
    {
        get => (TimelineConfig?)GetValue(RowConfigProperty);
        set => SetValue(RowConfigProperty, value);
    }

    private TimelineConfig ConfigFor(MarkdownStyle style) =>
        _rowConfig is { } cached && ReferenceEquals(cached.Style, style)
            ? cached
            : _rowConfig = new TimelineConfig(style, item => MediaOf(item, style), OpenLink,
                id => _ = _model.ExpandAsync(id),
                new MessageCommands(
                    CopyText: CopyText,
                    CopyImage: id => _ = CopyImageAsync(id),
                    CopyLink: CopyText,
                    Delete: id => _ = DeleteMessageFromUiAsync(id)));

    private void PaintTimelineEmpty(MainModel.EmptyState empty)
    {
        TimelineEmptyTitle.Text = empty.Title;
        TimelineRetryButton.Visibility =
            empty.Action == MainModel.EmptyAction.Retry ? Visibility.Visible : Visibility.Collapsed;
    }

    // ---- 交回模型 ---------------------------------------------------------------------------------

    private void OnConversationSelected(object sender, SelectionChangedEventArgs e)
    {
        if (_applyingSelection || ConversationList.SelectedItem is not ConversationItem item) return;
        _model.Open(item.ChannelNo, item.SenderId);
        // 换会话就是新的一屏，从底部看起：上一屏攒的"新消息"数带走没意义
        SnapToBottom();
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
        // 点与字同色：在线绿 / 连接中蓝 / 断链橙 / 要人处理的红 / 没启动灰，扫一眼就知道该不该管
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
    private void OnNewBelowClicked(object sender, RoutedEventArgs e) => SnapToBottom();

    // ---- 滚动与栏宽 -------------------------------------------------------------------------------

    private ScrollViewer? _scroller;

    /// <summary>
    /// 时间线那根滚动条。虚拟化要求 ScrollViewer 就是 <see cref="ItemsControl"/> 的宿主，
    /// 于是它被搬进了控件模板，只能等模板套上再按名字取；还没布局过时给 null，几处调用都只是"没得滚"。
    /// </summary>
    private ScrollViewer? TimelineScroll
    {
        get
        {
            if (_scroller is not null) return _scroller;
            Timeline.ApplyTemplate();
            return _scroller = Timeline.Template?.FindName("TimelineScroll", Timeline) as ScrollViewer;
        }
    }

    private void OnTimelineScrollChanged(object sender, ScrollChangedEventArgs e)
    {
        var scroller = TimelineScroll;
        if (scroller is null) return;
        // 冒泡上来的还有气泡正文里那几个自己的 ScrollViewer（每颗都报 ext==vh==46），
        // 照单全收就会把它们的"高度变化"一笔笔加到时间线的偏移上。只认时间线自己那根
        if (!ReferenceEquals(e.OriginalSource, scroller)) return;

        // 补页那几帧是在给这一屏重新找位置，不是用户在滚：每长高多少就把偏移推多少，
        // 盯着的那一条才留在原地。这一段里"到顶了"和"贴不贴底"都不作数
        if (_holding)
        {
            scroller.ScrollToVerticalOffset(scroller.VerticalOffset + e.ExtentHeightChange);
            return;
        }

        _stuckToBottom = scroller.ScrollableHeight - scroller.VerticalOffset <= 1;
        // 用户自己滚回到底了：胶囊那句"3 条新消息"已经过时，屏幕上正看着的就是最新的一条
        if (_stuckToBottom && _newBelow > 0)
        {
            _newBelow = 0;
            PaintNewBelow();
        }

        // 这一屏还没摆好就先别翻页：虚拟化面板在首次布局时偏移本来就是 0，那不是"用户翻到顶了"
        if (!_timelineSettled) return;
        if (e.VerticalOffset > 0 || e.ExtentHeight <= e.ViewportHeight) return;
        var first = FirstRealized(scroller);
        if (first is null) return;

        // 旗子要在补页**之前**降下来：插进去那一下随即就会长出高度并回抛 ScrollChanged，
        // 那几帧正是要靠它把偏移跟着推下去的
        _holding = true;
        if (!_model.LoadEarlier())
        {
            _holding = false;
            return;
        }

        // 已经开始往回翻历史了，怎么算都不在底部
        _stuckToBottom = false;
        _ = HoldAnchorAsync(first.Value.Item, first.Value.Top);
    }

    /// <summary>
    /// 屏上第一条真的排出来的消息，连同它在视口里的纵坐标——补页之后要把它放回这个高度。
    /// 只认消息：日期头的位置是"这一天从哪开始"算出来的，它本来就该跟着新的历史留在最上面。
    /// </summary>
    private (MessageItem Item, double Top)? FirstRealized(ScrollViewer scroller)
    {
        for (var i = 0; i < Timeline.Items.Count; i++)
        {
            if (Timeline.Items[i] is not MessageItem item) continue;
            if (Timeline.ItemContainerGenerator.ContainerFromIndex(i) is not FrameworkElement row) continue;
            return (item, row.TranslatePoint(new Point(0, 0), scroller).Y);
        }

        return null;
    }

    /// <summary>按消息号找回那一行的容器：这一行自己的数据会变（补进来的新消息可能把它那条时间戳吃掉），
    /// 所以记录本身认不出来，能认的只有 <see cref="MessageItem.MsgId"/>。</summary>
    private FrameworkElement? RowOf(string msgId)
    {
        for (var i = 0; i < Timeline.Items.Count; i++)
        {
            if (Timeline.Items[i] is MessageItem m && m.MsgId == msgId
                && Timeline.ItemContainerGenerator.ContainerFromIndex(i) is FrameworkElement row)
            {
                return row;
            }
        }

        return null;
    }

    /// <summary>
    /// 插在头部的那些行是分批量出来的：一次 <see cref="ScrollChangedEventArgs.ExtentHeightChange"/>
    /// 只报一小截（先报估出来的一行，隔几轮才报整页），所以补一次就收工的话，
    /// 真正的增高会落在旗子已经降下之后。这里在 <see cref="_holding"/> 期间等布局走稳，
    /// 再按锚点那一行在视口里的实际高度校掉残差——长得不是"要高出来那一段"的部分（比如某一行
    /// 的高度自己变了）只有靠它才量得准。
    /// 连着几轮"既没长高、锚点也没动"才收旗：面板把估值换成实测值时总高度可以不变，但位置会挪。
    /// </summary>
    private async Task HoldAnchorAsync(MessageItem anchor, double anchorTop)
    {
        try
        {
            var quiet = 0;
            for (var round = 0; round < 12 && quiet < 3; round++)
            {
                await Dispatcher.Yield(DispatcherPriority.Loaded);
                var scroller = TimelineScroll;
                if (scroller is null) return;
                if (RowOf(anchor.MsgId) is not { } row)
                {
                    quiet++;
                    continue;
                }

                var moved = row.TranslatePoint(new Point(0, 0), scroller).Y - anchorTop;
                if (Math.Abs(moved) < 0.5)
                {
                    quiet++;
                    continue;
                }

                quiet = 0;
                scroller.ScrollToVerticalOffset(scroller.VerticalOffset + moved);
            }
        }
        finally
        {
            _holding = false;
        }
    }

    /// <summary>换一屏（打开会话、切会话、点胶囊）：回到最新一条，上一屏的"新消息"数不带过来。</summary>
    private void SnapToBottom()
    {
        _stuckToBottom = true;
        _newBelow = 0;
        _timelineSettled = false;
        PaintNewBelow();
        _ = ScrollToBottomAsync(force: true);
    }

    /// <summary>
    /// 滚到底。<paramref name="force"/> 是"换了一屏、必须落底"；不带它时只在用户本来就贴在底部才跟。
    /// 必须等布局走完：虚拟化面板在排之前连 extent 都没有，当场滚只会滚到一个还不存在的底。
    /// 而它的 extent 又是估出来的——真把最后几行生成出来高度还会再变一次，所以落底之后要再补一次。
    /// </summary>
    private async Task ScrollToBottomAsync(bool force = false)
    {
        await Dispatcher.Yield(DispatcherPriority.Loaded);
        var scroller = TimelineScroll;
        if (scroller is null) return;
        if (!force && !_stuckToBottom) return;
        for (var attempt = 0; attempt < 2; attempt++)
        {
            scroller.ScrollToEnd();
            await Dispatcher.Yield(DispatcherPriority.Loaded);
        }

        _timelineSettled = true;
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

    /// <summary>这一条消息的图位：没图就不占位，有图才挂 <see cref="MediaStrip"/>。
    /// 这里只<b>摆好格子</b>，取字节要等那一格被装载（<see cref="MediaStrip"/> 的 <c>MediaSlot</c>）——
    /// 切会话重建整条时间线，一次刷新里发几百个请求正是卡顿的一半来源。
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
