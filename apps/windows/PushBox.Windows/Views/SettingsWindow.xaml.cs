using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using PushBox.Windows.Data;
using PushBox.Windows.Logging;
using PushBox.Windows.Net;
using PushBox.Windows.Settings;
using PushBox.Windows.Update;

namespace PushBox.Windows.Views;

/// <summary>
/// §8.4 W16 的设置窗：频道（含 §14 治理位、白名单、容量、换发号码）、设备（吊销、恢复码）、通知（本机四个开关）。
/// 它是 Android 的 ChannelsTab / DevicesTab / SettingsTab 搬到 PC 的那一份，文案逐条对齐。
///
/// code-behind 只做三件事：把控件里的字符串交出去、等异步结果、把结果摆回屏幕上。
/// 判定全在 <see cref="SettingsModel"/>（留空算不算提交、按钮该不该出现、这段话怎么说），
/// 所以这一页的验收不需要人真去点。
/// </summary>
public partial class SettingsWindow : Window
{
    private readonly SettingsModel _model;
    private ClientSettings _settings;
    private readonly Action<ClientSettings> _apply;

    /// <summary>更新这一页的判定与下载。<see cref="_beforeExit"/> 由 App 交进来（就是 <c>App.ExitApp</c>）：
    /// 安装前必须先让开单实例 mutex 和正在运行的 exe，这一句只有 App 能给，所以从装配层注入而不是窗口自己造。</summary>
    private readonly UpdateModel _update;
    private readonly Action? _beforeExit;

    /// <summary>退出空间的落点（<c>App.LogoutSpaceAsync</c>）。这一页只问一句：删文件要等引擎停了、库关了，
    /// 那两样都不归窗口管，拿着库的第二份引用在窗口里删，就是 2026-10-22 那次"身份没了、库还在"。</summary>
    private readonly Action? _logout;

    /// <summary>退出空间那一问的正文。恢复码必须说出来：本机唯一的恢复码就在身份文件里，
    /// 一起清掉之后，没有第二台设备的人就再也进不回这个空间。</summary>
    public const string LogoutDetail =
        "这台电脑上的会话、消息、图片与设备身份会被清空，然后回到首启注册页。\n"
        + "服务器上的消息不受影响，但本机保存的恢复码会一起清掉——重新加入之前请确认已经抄下。\n"
        + "此操作不可撤销。";

    /// <summary>刚回显过的那一份接入信息。密钥不落盘，窗口一关就没有第二份了。</summary>
    private Issued? _issued;

    /// <summary>轮换出来的新恢复码同样只活在内存里这一会儿。</summary>
    private string? _recovery;

    private IReadOnlyList<ChannelRow> _channels = Array.Empty<ChannelRow>();
    private IReadOnlyList<SenderEntry> _senders = Array.Empty<SenderEntry>();

    /// <summary>服务端回显的那一列。回填勾选框时用它，而不是拿界面上的勾当事实。</summary>
    private bool _subscribedOnly;

    /// <summary>回填 <see cref="FilterOnly"/> 的勾时压掉一次事件：那是刷新的结果，不是用户的一次改动。</summary>
    private bool _filterLoading;

    /// <summary>回填主题下拉时同样压掉一次 SelectionChanged——刷新的结果不该被当成换档去落盘。</summary>
    private bool _themeLoading;

    /// <summary>一次只让一个写操作在飞：治理页那堆按钮点两下就是两次 PUT，服务端会按后到那条落库。</summary>
    private bool _busy;

    public SettingsWindow(SettingsModel model, ClientSettings settings, Action<ClientSettings> apply,
        UpdateModel? update = null, Action? beforeExit = null, Action? logout = null)
    {
        _model = model;
        _settings = settings;
        _apply = apply;
        _update = update ?? new UpdateModel();
        _beforeExit = beforeExit;
        _logout = logout;
        InitializeComponent();
        // 写死的 900×640 在 1366×768@150% 的可见区（约 910×512）里放不下，开窗就把底下那排按钮推到屏幕外。
        // MinHeight 460 会把夹出来的值再顶回来一点，所以只保证"不比工作区更大"，不保证"刚好塞得下 460"
        WindowPlacement.FitToWorkArea(this);
        PreviewKeyDown += OnPreviewKeyDown;
        // 下拉的项先于回填挂上：SelectedIndex 落在没有 ItemsSource 的空列表上会被压回 -1
        ThemeBox.ItemsSource = SettingsModel.ThemeLabels;
        LogPathText.Text = AppLog.DirectoryFor();
        ApplyToControls();
        AboutVersion.Text = "版本 " + UpdateClient.CurrentVersion();
        // 一开扇就去服务端取一次，而不是等人再点一下"刷新"：这一页的全部价值都在"看得见现在的治理位"，
        // 打开时先给一片空白就等于把验收交给人手。取不到也把话说在这一格里。
        _ = RefreshAllAsync();
    }

    // 给用例用的只读视图（与主窗同一手法）：面板切换、按钮该不该出现、留空的那一格怎么处理，
    // 都要能在 dotnet test 里被读到，而不是等人去点。
    public string ProbePanel => ChannelsPanel.Visibility == Visibility.Visible
        ? "channels"
        : DevicesPanel.Visibility == Visibility.Visible
            ? "devices"
            : NotifyPanel.Visibility == Visibility.Visible ? "notify" : "about";

    public int ProbeChannelCount => _channels.Count;
    public bool ProbeGovernanceVisible => GovernanceCard.Visibility == Visibility.Visible;
    public bool ProbeSendersVisible => SendersCard.Visibility == Visibility.Visible;
    public string ProbeNotice => ChannelNotice.Text;
    public bool ProbeFilterOnlyChecked => FilterOnly.IsChecked == true;
    public string ProbeFilterLabel => FilterOnlyLabel.Text;
    public string ProbeFilterWarning => FilterWarning.Text;
    public bool ProbeLeaveButtonVisible => LeaveButton.Visibility == Visibility.Visible;
    public string ProbeSubscriptionLine => GovSubscriptionLine.Text;
    public string ProbeSendersHint => SendersHint.Text;
    public string ProbeQuietLine => QuietLabel.Text;
    public int ProbeThemeIndex => ThemeBox.SelectedIndex;
    public string ProbeLogPath => LogPathText.Text;
    public IReadOnlyList<string> ProbeLevelsNow => GovLevel.Items.Cast<string>().ToList();

    // ---- 导航 ----------------------------------------------------------------------------------------

    private void OnNavChanged(object sender, RoutedEventArgs e) => ShowPanel((sender as RadioButton)?.Tag as string);

    /// <summary>
    /// Alt+1..4 直接跳那一页、Esc 关窗。左侧导航是中文标签，挂 AccessKey 没有意义
    /// （Alt+汉字在标准键盘上按不出来），所以用数字组合键；快捷键只改 RadioButton 的选中，
    /// 换页仍然走 <see cref="OnNavChanged"/> 那一条，不另开一套状态。
    /// </summary>
    private void OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            Close();
            e.Handled = true;
            return;
        }

        if (Keyboard.Modifiers != ModifierKeys.Alt) return;
        RadioButton? target = e.SystemKey switch
        {
            Key.D1 or Key.NumPad1 => NavChannels,
            Key.D2 or Key.NumPad2 => NavDevices,
            Key.D3 or Key.NumPad3 => NavNotify,
            Key.D4 or Key.NumPad4 => NavAbout,
            _ => null
        };
        if (target is null) return;
        target.IsChecked = true;
        e.Handled = true;
    }

    /// <summary>切到哪一块面板。选中态由那一列的 RadioButton 拿着，这里只负责让对应那一屏出现。
    ///
    /// 那句 null 检查不是防外部乱调：<c>NavChannels</c> 的 <c>IsChecked="True"</c> 会在 <see cref="InitializeComponent"/>
    /// 解析 BAML 的半路上就把 <see cref="OnNavChanged"/> 叫起来，那时候三个面板的字段还没赋上。
    /// XAML 里已经把"频道那一屏可见、其余折叠"写死，所以这一声早到的通知直接忽略就是对的初值。</summary>
    public void ShowPanel(string? which)
    {
        if (ChannelsPanel is null) return;
        ChannelsPanel.Visibility = which == "channels" ? Visibility.Visible : Visibility.Collapsed;
        DevicesPanel.Visibility = which == "devices" ? Visibility.Visible : Visibility.Collapsed;
        NotifyPanel.Visibility = which == "notify" ? Visibility.Visible : Visibility.Collapsed;
        AboutPanel.Visibility = which == "about" ? Visibility.Visible : Visibility.Collapsed;
    }

    // ---- 装载 ----------------------------------------------------------------------------------------

    private async Task RefreshAllAsync()
    {
        await RefreshChannelsAsync();
        await RefreshDevicesAsync();
    }

    private async Task RefreshChannelsAsync()
    {
        var result = await _model.ChannelsAsync();
        if (!result.Ok)
        {
            ChannelNotice.Text = result.Error;
            return;
        }

        var page = result.Value!;
        _channels = page.Rows;
        _subscribedOnly = page.SubscribedOnly;
        ChannelList.ItemsSource = _channels;
        ChannelEmpty.Visibility = _channels.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        // 列表换了一批就丢掉选中：治理卡上那几格写的还是上一条频道的话，留着等于让人改错频道
        if (ChannelList.SelectedItem is not ChannelRow) GovernanceCard.Visibility = Visibility.Collapsed;
        // 勾、说明、警告三格都由服务端那一列驱动：本机自记一份"我刚才开过"迟早和服务器说不一样的话
        _filterLoading = true;
        FilterOnly.IsChecked = page.SubscribedOnly;
        _filterLoading = false;
        FilterOnlyLabel.Text = page.SubscribedOnly ? "已开启：只收已接入的频道" : "未开启：全部照收";
        FilterWarning.Text = page.FilterWarning;
    }

    private async Task RefreshDevicesAsync()
    {
        var result = await _model.DevicesAsync();
        if (!result.Ok)
        {
            DeviceNotice.Text = result.Error;
            return;
        }

        DeviceList.ItemsSource = result.Value;
        DeviceEmpty.Visibility = result.Value!.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        DeviceNotice.Text = string.Empty;
    }

    private void OnReloadChannels(object sender, RoutedEventArgs e) => _ = Run(async () =>
    {
        await RefreshChannelsAsync();
    });

    private void OnReloadDevices(object sender, RoutedEventArgs e) => _ = Run(RefreshDevicesAsync);

    // ---- 频道 ----------------------------------------------------------------------------------------

    private void OnChannelSelected(object sender, SelectionChangedEventArgs e)
    {
        if (ChannelList.SelectedItem is not ChannelRow row)
        {
            GovernanceCard.Visibility = Visibility.Collapsed;
            SendersCard.Visibility = Visibility.Collapsed;
            return;
        }

        GovernanceCard.Visibility = Visibility.Visible;
        FillGovernance(row);
    }

    /// <summary>把这一条频道的现值填进治理那一格。数字按 Android 的口径原样写出来，不替用户"优化"。</summary>
    private void FillGovernance(ChannelRow row)
    {
        GovName.Text = row.Name;
        GovLevel.ItemsSource = SettingsModel.Levels;
        GovLevel.SelectedItem = row.MaxLevel;
        GovMaxImages.Text = row.MaxImages.ToString();
        GovRateLimit.Text = row.RateLimit.ToString();
        GovBodyBytes.Text = row.MaxBodyBytes.ToString();
        GovDailyMsg.Text = row.DailyMsgLimit.ToString();
        GovDailyImage.Text = row.DailyImageLimit.ToString();
        GovDailyBytes.Text = row.DailyBytesLimit.ToString();
        GovStrict.IsChecked = row.StrictAuth;
        GovMuted.IsChecked = row.Muted;
        GovWhitelist.IsChecked = row.SenderWhitelistMode;
        GovSubscriptionLine.Text = row.SubscriptionText;
        // "取消接入"只出现在确实接入过的那一行：给没接入的行摆一个取消按钮，点了什么也不会变。
        // 副标题跟按钮同进退——按钮藏了说明还留着，那一行就成了没有按钮的孤句
        LeaveButton.Visibility = row.Subscribed ? Visibility.Visible : Visibility.Collapsed;
        LeaveHint.Visibility = LeaveButton.Visibility;
        SendersCard.Visibility = Visibility.Collapsed;
        SenderList.ItemsSource = Array.Empty<SenderEntry>();
        SendersHint.Text = string.Empty;
    }

    private void OnCreateChannel(object sender, RoutedEventArgs e) => _ = Run(async () =>
    {
        var created = await _model.CreateChannelAsync(NewChannelName.Text);
        if (!created.Ok)
        {
            ChannelNotice.Text = created.Error;
            return;
        }

        ChannelNotice.Text = string.Empty;
        NewChannelName.Text = string.Empty;
        ShowIssued(created.Value!);
        await RefreshChannelsAsync();
    });

    /// <summary>§5.2 加入频道：号码与密钥都手输，校验在 <see cref="SettingsModel"/> 里，这里只交出控件上的字符串。</summary>
    private void OnJoinChannel(object sender, RoutedEventArgs e) => _ = Run(async () =>
    {
        var joined = await _model.JoinChannelAsync(JoinNo.Text, JoinSecret.Text);
        ChannelNotice.Text = joined.Ok ? joined.Value! : joined.Error;
        if (!joined.Ok) return;
        JoinNo.Text = string.Empty;
        JoinSecret.Text = string.Empty;
        await RefreshChannelsAsync();
    });

    /// <summary>把选中的频道号抄进上面那一格：同一张列表里的频道只差密钥，再让人手抄一遍号码只会多出一种打错的方式。</summary>
    private void OnUseSelectedNo(object sender, RoutedEventArgs e)
    {
        if (ChannelList.SelectedItem is not ChannelRow row)
        {
            ChannelNotice.Text = "先在下面列表里选中一条频道";
            return;
        }

        JoinNo.Text = row.ChannelNo;
        ChannelNotice.Text = string.Empty;
    }

    private void OnLeaveChannel(object sender, RoutedEventArgs e)
    {
        if (ChannelList.SelectedItem is not ChannelRow row) return;
        _ = Run(async () =>
        {
            var done = await _model.LeaveChannelAsync(row.ChannelNo);
            ChannelNotice.Text = done.Ok ? done.Value! : done.Error;
            if (done.Ok) await RefreshChannelsAsync();
        });
    }

    private void OnToggleFilterOnly(object sender, RoutedEventArgs e)
    {
        // 刷新回填勾时也会走到这里，那不是用户的一次改动——放过去就是每次刷新多发一个 POST
        if (_filterLoading) return;
        var want = FilterOnly.IsChecked == true;
        _ = Run(async () =>
        {
            var done = await _model.SetSubscriptionModeAsync(want);
            if (!done.Ok)
            {
                ChannelNotice.Text = done.Error;
                // 服务端没认下这一档就把勾放回原样：屏幕上的勾必须等于服务器里的那一列
                _filterLoading = true;
                FilterOnly.IsChecked = _subscribedOnly;
                _filterLoading = false;
                return;
            }

            _subscribedOnly = done.Value;
            await RefreshChannelsAsync();
        });
    }

    private void OnSaveGovernance(object sender, RoutedEventArgs e)    {
        if (ChannelList.SelectedItem is not ChannelRow row) return;
        var patch = SettingsModel.GovernancePatch(GovName.Text, GovLevel.SelectedItem as string,
            GovMaxImages.Text, GovRateLimit.Text, GovBodyBytes.Text, GovStrict.IsChecked == true,
            GovMuted.IsChecked == true, GovWhitelist.IsChecked == true, GovDailyMsg.Text,
            GovDailyImage.Text, GovDailyBytes.Text);
        _ = Run(async () =>
        {
            var saved = await _model.SaveGovernanceAsync(row.ChannelNo, patch);
            ChannelNotice.Text = saved.Ok ? string.Empty : saved.Error;
            if (saved.Ok) await RefreshChannelsAsync();
        });
    }

    private void OnRotateSecret(object sender, RoutedEventArgs e)
    {
        if (ChannelList.SelectedItem is not ChannelRow row) return;
        if (!Confirm("重置密钥？", "旧密钥即刻失效，用它的第三方必须换成新密钥。此操作不可撤销。")) return;
        _ = Run(async () =>
        {
            var rotated = await _model.RotateSecretAsync(row.ChannelNo);
            if (!rotated.Ok)
            {
                ChannelNotice.Text = rotated.Error;
                return;
            }

            ShowIssued(rotated.Value!);
        });
    }

    private void OnResetChannelNo(object sender, RoutedEventArgs e)
    {
        if (ChannelList.SelectedItem is not ChannelRow row) return;
        if (!Confirm("换发新的频道号码？", SettingsModel.ResetNoDetail)) return;
        _ = Run(async () =>
        {
            var swapped = await _model.ResetChannelNoAsync(row.ChannelNo);
            if (!swapped.Ok)
            {
                ChannelNotice.Text = swapped.Error;
                return;
            }

            // 号码换了、密钥没变：这一条频道只能按新号重新抄一遍接入行
            ShowIssued(new Issued(swapped.Value!, string.Empty));
            await RefreshChannelsAsync();
        });
    }

    private void OnArchiveChannel(object sender, RoutedEventArgs e)
    {
        if (ChannelList.SelectedItem is not ChannelRow row) return;
        if (!Confirm("归档此频道？",
                "归档后该频道停止接收新消息（第三方投递会收到 403），历史消息保留。此操作不可撤销。")) return;
        _ = Run(async () =>
        {
            var done = await _model.ArchiveChannelAsync(row.ChannelNo);
            ChannelNotice.Text = done.Ok ? string.Empty : done.Error;
            if (done.Ok) await RefreshChannelsAsync();
        });
    }

    private void OnClearChannel(object sender, RoutedEventArgs e)
    {
        if (ChannelList.SelectedItem is not ChannelRow row) return;
        if (!Confirm("清空此频道？",
                "删除该频道下的全部历史消息与图片（各端同步移除），频道本身与接入地址保留。此操作不可撤销。")) return;
        _ = Run(async () =>
        {
            var done = await _model.ClearChannelAsync(row.ChannelNo);
            ChannelNotice.Text = done.Ok ? string.Empty : done.Error;
        });
    }

    private void OnCapacity(object sender, RoutedEventArgs e)
    {
        if (ChannelList.SelectedItem is not ChannelRow row) return;
        _ = Run(async () =>
        {
            var report = await _model.CapacityAsync(row.ChannelNo);
            ChannelNotice.Text = report.Ok ? report.Value! : report.Error;
        });
    }

    private void ShowIssued(Issued issued)
    {
        _issued = issued;
        IssuedNo.Text = "频道号：" + issued.ChannelNo;
        // 换发号码这一支没有新密钥（密钥不变），那一格就该说"沿用原来那一个"而不是留一行空的密码位
        IssuedSecret.Text = issued.Secret.Length == 0 ? "密钥：沿用原来那一个" : "密钥：" + issued.Secret;
        IssuedCurl.Text = issued.Secret.Length == 0
            ? "把频道号填进第三方软件即可，密钥不变。"
            : issued.CurlLine(_model.BaseUrl);
        IssuedCard.Visibility = Visibility.Visible;
    }

    private void OnDismissIssued(object sender, RoutedEventArgs e)
    {
        _issued = null;
        IssuedCard.Visibility = Visibility.Collapsed;
    }

    private void OnCopyChannelNo(object sender, RoutedEventArgs e) => CopyToClipboard(_issued?.ChannelNo);

    private void OnCopySecret(object sender, RoutedEventArgs e) => CopyToClipboard(_issued?.Secret);

    private void OnCopyCurl(object sender, RoutedEventArgs e) =>
        CopyToClipboard(_issued is { } i && i.Secret.Length > 0 ? i.CurlLine(_model.BaseUrl) : null);

    // ---- 白名单 --------------------------------------------------------------------------------------

    private void OnOpenSenders(object sender, RoutedEventArgs e)
    {
        if (ChannelList.SelectedItem is not ChannelRow row) return;
        SendersCard.Visibility = Visibility.Visible;
        _ = Run(() => RefreshSendersAsync(row));
    }

    private async Task RefreshSendersAsync(ChannelRow row)
    {
        var result = await _model.SendersAsync(row.ChannelNo);
        if (!result.Ok)
        {
            ChannelNotice.Text = result.Error;
            return;
        }

        _senders = result.Value!;
        SenderList.ItemsSource = _senders;
        // 这一格的话由列表和模式两件事决定，所以每次刷完都重算（SettingsModel.WhitelistWarning 那条判定）
        SendersHint.Text = SettingsModel.WhitelistWarning(row.SenderWhitelistMode,
            _senders.Count(s => s.Enabled));
    }

    private void OnAddSender(object sender, RoutedEventArgs e)
    {
        if (ChannelList.SelectedItem is not ChannelRow row) return;
        _ = Run(async () =>
        {
            var done = await _model.PutSenderAsync(row.ChannelNo, SenderIdInput.Text, true);
            if (!done.Ok)
            {
                ChannelNotice.Text = done.Error;
                return;
            }

            ChannelNotice.Text = string.Empty;
            SenderIdInput.Text = string.Empty;
            await RefreshSendersAsync(row);
        });
    }

    private void OnSenderToggle(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: SenderEntry entry } || ChannelList.SelectedItem is not ChannelRow row) return;
        _ = Run(async () =>
        {
            var done = await _model.PutSenderAsync(row.ChannelNo, entry.SenderId, !entry.Enabled);
            if (!done.Ok)
            {
                ChannelNotice.Text = done.Error;
                return;
            }

            await RefreshSendersAsync(row);
        });
    }

    // ---- 设备 ----------------------------------------------------------------------------------------

    private void OnRevokeDevice(object sender, RoutedEventArgs e)
    {
        if (DeviceList.SelectedItem is not DeviceRow row) return;
        if (!row.CanRevoke)
        {
            DeviceNotice.Text = row.Revoked ? "这台已经吊销过了" : "不能吊销本机";
            return;
        }

        if (!Confirm("吊销此设备？",
                "吊销后该设备的令牌立即失效并强制下线，需凭恢复码重新加入。此操作不可撤销。")) return;
        _ = Run(async () =>
        {
            var done = await _model.RevokeDeviceAsync(row.DeviceId);
            DeviceNotice.Text = done.Ok ? string.Empty : done.Error;
            if (done.Ok) await RefreshDevicesAsync();
        });
    }

    private void OnRotateRecovery(object sender, RoutedEventArgs e)
    {
        if (!Confirm("轮换恢复码？", SettingsModel.RecoveryOnceHint)) return;
        _ = Run(async () =>
        {
            var rotated = await _model.RotateRecoveryCodeAsync();
            if (!rotated.Ok)
            {
                DeviceNotice.Text = rotated.Error;
                return;
            }

            DeviceNotice.Text = string.Empty;
            _recovery = rotated.Value;
            RecoveryText.Text = rotated.Value!;
            RecoveryHint.Text = SettingsModel.RecoveryOnceHint;
            RecoveryHint.Visibility = Visibility.Visible;
        });
    }

    private void OnCopyRecovery(object sender, RoutedEventArgs e) => CopyToClipboard(_recovery);

    // ---- 通知（本机开关）------------------------------------------------------------------------------

    private void ApplyToControls()
    {
        NotifyOn.IsChecked = _settings.NotificationsEnabled;
        QuietOn.IsChecked = _settings.QuietEnabled;
        AutoStartOn.IsChecked = _settings.AutoStartEnabled;
        CloseToTrayOn.IsChecked = _settings.CloseToTray;
        AutoCheckOn.IsChecked = _settings.AutoCheckUpdate;
        QuietStart.Text = SettingsModel.ClockOf(_settings.QuietStartMinute);
        QuietEnd.Text = SettingsModel.ClockOf(_settings.QuietEndMinute);
        QuietLabel.Text = SettingsModel.QuietLine(_settings.QuietEnabled, _settings.QuietStartMinute,
            _settings.QuietEndMinute);
        // 每次按落盘值回填都比上一次更可信：上一轮那句"已改回…"留着只会和现在这两格对不上
        QuietHint.Text = string.Empty;
        QuietHint.Visibility = Visibility.Collapsed;
        _themeLoading = true;
        ThemeBox.SelectedIndex = SettingsModel.ThemeIndexOf(_settings.Theme);
        _themeLoading = false;
    }

    private void OnToggleNotifications(object sender, RoutedEventArgs e) =>
        Update(_settings with { NotificationsEnabled = NotifyOn.IsChecked == true });

    private void OnToggleQuiet(object sender, RoutedEventArgs e) =>
        Update(_settings with { QuietEnabled = QuietOn.IsChecked == true });

    /// <summary>换档即时生效：<see cref="Update"/> 把同一份设置交回装配层，那边先铺色再落盘。</summary>
    private void OnThemeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_themeLoading) return;
        Update(_settings with { Theme = SettingsModel.ThemeOf(ThemeBox.SelectedIndex) });
    }

    private void OnToggleCloseToTray(object sender, RoutedEventArgs e) =>
        Update(_settings with { CloseToTray = CloseToTrayOn.IsChecked == true });

    /// <summary>开机自启这一项落不了盘（组策略锁了 Run）时不能把勾留在屏幕上骗人：回读一次现状。</summary>
    private void OnToggleAutoStart(object sender, RoutedEventArgs e) =>
        Update(_settings with { AutoStartEnabled = AutoStartOn.IsChecked == true });

    /// <summary>失焦即提交。填歪的那一格会被 <see cref="Update"/> 回填成原值，所以这里补一句"为什么改了"，
    /// 否则用户看到的只是自己刚敲的 25:00 莫名变回了 22:00。</summary>
    private void OnQuietWindowEdited(object sender, RoutedEventArgs e)
    {
        var edit = SettingsModel.QuietEditOf(_settings, QuietStart.Text, QuietEnd.Text);
        Update(edit.Settings);
        QuietHint.Text = SettingsModel.QuietHintOf(edit);
        QuietHint.Visibility = QuietHint.Text.Length > 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void Update(ClientSettings next)
    {
        _settings = next;
        _apply(next);
        ApplyToControls();
    }

    // ---- 关于（版本更新）------------------------------------------------------------------------------

    /// <summary>把日志目录交给资源管理器。目录可能还没建（全新安装、第一条日志都没写），先建再开；
    /// 打不开就把原因写在这一行，不要只留一个按下去没反应的按钮。</summary>
    private void OnOpenLogs(object sender, RoutedEventArgs e)
    {
        try
        {
            var dir = AppLog.DirectoryFor();
            System.IO.Directory.CreateDirectory(dir);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(dir)
            {
                UseShellExecute = true
            });
            LogHint.Text = string.Empty;
            LogHint.Visibility = Visibility.Collapsed;
        }
        catch (Exception ex)
        {
            LogHint.Text = "打不开日志目录：" + Outcome<string>.Describe(ex);
            LogHint.Visibility = Visibility.Visible;
        }
    }

    /// <summary>点"检查更新"：拉一次最新 release，比对版本，把结论显示在这一页。
    /// 查到确有可装的新版就当场弹确认框（和启动自动检查同一只窗），下载这一步仍要用户在框里点——
    /// 未认证限额很紧，所以 <see cref="UpdateModel.CheckAsync"/> 已把那一版 release 记在 LastRelease 里，
    /// 交给确认框复用，不再为取一个 URL 多发一次 GitHub 请求。</summary>
    private void OnCheckUpdate(object sender, RoutedEventArgs e) => _ = Run(async () =>
    {
        CheckUpdateButton.IsEnabled = false;
        UpdateStatus.Text = "正在检查更新…";
        UpdateHint.Text = string.Empty;
        try
        {
            var result = await _update.CheckAsync();
            if (!result.Ok)
            {
                UpdateStatus.Text = string.Empty;
                UpdateHint.Text = result.Error;
                return;
            }

            var info = result.Value!;
            UpdateStatus.Text = info.StatusLine;
            if (_update.LastRelease is { } latest) OpenPrompt(latest);
        }
        finally
        {
            CheckUpdateButton.IsEnabled = true;
        }
    });

    /// <summary>弹出确认框：把这一页持有的 <see cref="_update"/> 与 <see cref="_beforeExit"/> 交进去，
    /// 下载/拉起安装都在框里完成，本页只负责"发现→请用户确认"。同一刻不叠第二层。</summary>
    private void OpenPrompt(GitHubRelease latest)
    {
        if (_prompt is { } open)
        {
            open.Activate();
            return;
        }
        // 代理那一档存在设置里：确认框改完就交回这一页，同一次会话里下次升级还是这一档
        var window = new UpdatePromptWindow(_update, latest, _beforeExit, _settings, Update) { Owner = this };
        _prompt = window;
        window.Closed += (_, _) => _prompt = null;
        window.Show();
    }

    private UpdatePromptWindow? _prompt;

    /// <summary>启动自动检查开关：关掉后 App 冷启动那一趟 GitHub 请求就不发了（只留这里的手动按钮）。</summary>
    private void OnToggleAutoCheck(object sender, RoutedEventArgs e) =>
        Update(_settings with { AutoCheckUpdate = AutoCheckOn.IsChecked == true });

    /// <summary>这一页的检查态：状态那句结论 + 出错那句话。下载进度与结果都在确认框里，不在这里。</summary>
    public string ProbeUpdateStatus => UpdateStatus.Text;
    public string ProbeUpdateHint => UpdateHint.Text;

    // ---- 公共设施 ------------------------------------------------------------------------------------

    /// <summary>
    /// 一次写操作的骨架：挡住并发点击、把异常收成人话、结束后无论成败都把按钮放回可用。
    /// 这里<b>不</b>套 ConfigureAwait(false)：续延要回 UI 线程改控件，而这条链上没有任何等待点需要让线程走开。
    /// </summary>
    private async Task Run(Func<Task> work)
    {
        if (_busy) return;
        _busy = true;
        IsEnabled = false;
        try
        {
            await work();
        }
        catch (Exception e)
        {
            // 到这里只剩没被 SettingsModel 包住的那一类（比如取身份时 DPAPI 打不开）。
            // 屏幕上说的是这一句人话，日志里只留类型名（§13/§14）
            ChannelNotice.Text = Outcome<string>.Describe(e);
        }
        finally
        {
            _busy = false;
            IsEnabled = true;
        }
    }

    private static void CopyToClipboard(string? text)
    {
        if (string.IsNullOrEmpty(text)) return;
        try
        {
            Clipboard.SetText(text!);
        }
        catch (System.Runtime.InteropServices.ExternalException)
        {
            // 剪贴板被别的进程攥着（远程桌面、密码管理器锁定）：这一格本来就在屏幕上，看得见就抄得走
        }
    }

    private static bool Confirm(string question, string detail) =>
        MessageBox.Show(question + "\n\n" + detail, "PushBox 设置", MessageBoxButton.YesNo,
            MessageBoxImage.Warning) == MessageBoxResult.Yes;

    /// <summary>§7.3 退出空间：这一页只负责问一句，停引擎、关库、删文件、换窗口都由装配层做。</summary>
    private void OnLogout(object sender, RoutedEventArgs e)
    {
        if (_logout is null) return;
        if (!Confirm("退出空间？", LogoutDetail)) return;
        _logout();
    }
}
