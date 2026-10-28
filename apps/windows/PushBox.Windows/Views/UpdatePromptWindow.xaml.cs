using System;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using PushBox.Windows.Settings;
using PushBox.Windows.Update;

namespace PushBox.Windows.Views;

/// <summary>
/// D32 的更新确认框。<b>只负责"问一次 + 下载 + 拉起安装"</b>：发现新版后由自动检查或手动检查叫起这一扇，
/// 用户点"下载并安装"才真去下载 <c>setup.exe</c> 并拉起 Inno 静默安装——绝不在这扇窗出现前自动下载。
/// 下载在后台线程跑（<see cref="UpdateModel.DownloadAsync"/>），装完的 <c>ExitApp</c> 回 UI 线程执行。
///
/// 下载期间这一扇显示的是"百分比 / 已下字节 / 速度 / 还剩多久"，失败之后按钮换成"重试下载"：
/// 那一趟的临时文件不会留下来，重试是从头再下一遍；代理下拉则在重试之前就能改，
/// 所以"系统代理开着但 GitHub 下不动"这件事不用关窗、进设置、再找回来。
/// </summary>
public sealed partial class UpdatePromptWindow : Window
{
    private readonly UpdateModel _update;
    private readonly GitHubRelease _latest;
    private readonly Action? _beforeExit;

    /// <summary>把代理这一档写回设置的那条路（App 是 <c>ApplySettings</c>，设置窗是它自己那一份）。
    /// 没交进来（用例）就只改本窗内存里的那一份。</summary>
    private readonly Action<ClientSettings>? _apply;

    private ClientSettings _settings;

    /// <summary>关窗 = 放弃这一次下载：请求还在飞的时候用户点了"稍后"，
    /// 续延回来就不该再把安装器拉起来——那会让一扇已经关掉的窗在后台把应用装掉。</summary>
    private readonly CancellationTokenSource _lifetime = new();

    private bool _downloading;

    /// <summary>回填代理控件时压掉一次 SelectionChanged：那是读出来的状态，不是用户的一次改动。</summary>
    private bool _proxyLoading;

    /// <summary>用例读的几格：按钮态与那一行进度/结果，好让"确认前不下载"能被断言而不靠人点。</summary>
    public bool ProbeInstallEnabled => InstallButton.IsEnabled;
    public string ProbeInstallLabel => (string)InstallButton.Content;
    public string ProbeStatus => Status.Text;
    public bool ProbeNotesVisible => NotesScroll.Visibility == Visibility.Visible;
    public int ProbeProxyIndex => ProxyBox.SelectedIndex;
    public bool ProbeProxyAddressVisible => ProxyAddress.Visibility == Visibility.Visible;

    public UpdatePromptWindow(UpdateModel update, GitHubRelease latest, Action? beforeExit,
        ClientSettings? settings = null, Action<ClientSettings>? apply = null)
    {
        _update = update;
        _latest = latest;
        _beforeExit = beforeExit;
        _settings = settings ?? ClientSettings.Defaults;
        _apply = apply;
        InitializeComponent();
        // 与首启同一理由：NoResize + SizeToContent 的窗在超高 DPI 小屏上夹 MaxHeight
        WindowPlacement.FitToWorkArea(this);
        Headline.Text = $"发现新版本 {latest.Version}（当前 {UpdateClient.CurrentVersion()}），是否下载并安装？";
        if (!string.IsNullOrWhiteSpace(latest.Notes))
        {
            Notes.Text = latest.Notes;
            NotesScroll.Visibility = Visibility.Visible;
        }

        ProxyBox.ItemsSource = SettingsModel.ProxyLabels;
        PaintProxy();
        Closed += (_, _) => _lifetime.Cancel();
    }

    private void OnLater(object sender, RoutedEventArgs e) => Close();

    private void OnInstall(object sender, RoutedEventArgs e)
    {
        if (_downloading) return;
        _downloading = true;
        InstallButton.IsEnabled = false;
        InstallButton.Content = "下载中…";
        LaterButton.Content = "取消下载";
        // 这一趟的代理在请求发出时就定了：改下拉要等下一次（失败后的"重试下载"）才生效
        ProxyBox.IsEnabled = false;
        ProxyAddress.IsEnabled = false;
        StatusText("正在连接 GitHub…");
        _ = RunDownload();
    }

    private async Task RunDownload()
    {
        // Progress<T> 把回调排回 UI 线程；meter 已经按 200ms 掐过，一秒最多几次，不会挤住消息泵
        var progress = new Progress<DownloadTick>(tick => StatusText(UpdateModel.ProgressLine(tick)));
        var proxy = new ProxyChoice(_settings.UpdateProxy, _settings.UpdateProxyAddress);
        var done = await _update.DownloadAsync(_latest, progress, proxy, _lifetime.Token);
        if (!done.Ok)
        {
            EndAttempt("重试下载");
            // 进度行与错误行是两格：进度停在断的那一处反而不用留，错误一句话说清为什么
            ErrorText(done.Error);
            return;
        }

        var target = done.Value!;
        // 没有 beforeExit（脱离 App 的用例）就不拉起安装，只报落盘位置——测试机上不该真去装
        if (_beforeExit == null)
        {
            EndAttempt("重新下载");
            StatusText("安装包已下载到 " + target);
            return;
        }

        StatusText("下载完成，正在安装并退出…");
        // 续延此刻在 UI 线程；ExitApp 里的 Close/Shutdown 必须跑在 UI 线程，BeginInvoke 排到队列末尾让进度先画出来
        _ = Dispatcher.BeginInvoke(new Action(() =>
        {
            try
            {
                UpdateClient.LaunchInstallAndExit(target, _beforeExit);
            }
            catch (Exception e)
            {
                EndAttempt("重试下载");
                ErrorText("无法启动安装程序：" + e.Message);
            }
        }));
    }

    /// <summary>一次尝试结束（失败或下完）：按钮与代理控件放回可用，字面说清下一步按了会做什么。</summary>
    private void EndAttempt(string label)
    {
        _downloading = false;
        InstallButton.Content = label;
        InstallButton.IsEnabled = true;
        LaterButton.Content = "稍后";
        ProxyBox.IsEnabled = true;
        ProxyAddress.IsEnabled = true;
    }

    // ---- 代理三档 -------------------------------------------------------------------------------------

    /// <summary>选中即存：这是"这台机器怎么上网"的偏好，不该等人再点一次"下载"才生效。</summary>
    private void OnProxyModeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_proxyLoading) return;
        SaveProxy(SettingsModel.ProxyModeOf(ProxyBox.SelectedIndex), _settings.UpdateProxyAddress);
    }

    private void OnProxyAddressEdited(object sender, RoutedEventArgs e)
    {
        if (_proxyLoading) return;
        var address = ProxyAddress.Text.Trim();
        if (address == _settings.UpdateProxyAddress) return;
        SaveProxy(_settings.UpdateProxy, address);
    }

    private void SaveProxy(UpdateProxyMode mode, string address)
    {
        _settings = _settings with { UpdateProxy = mode, UpdateProxyAddress = address };
        _apply?.Invoke(_settings);
        PaintProxy();
    }

    /// <summary>把设置里那一档摆到控件上。地址格只在"自定义"时露出来——不选它却摆一个空输入框，
    /// 用户会以为那里必须填点什么。</summary>
    private void PaintProxy()
    {
        _proxyLoading = true;
        ProxyBox.SelectedIndex = SettingsModel.ProxyModeIndexOf(_settings.UpdateProxy);
        ProxyAddress.Text = _settings.UpdateProxyAddress;
        var custom = _settings.UpdateProxy == UpdateProxyMode.Custom;
        ProxyAddress.Visibility = custom ? Visibility.Visible : Visibility.Collapsed;
        ProxyHint.Visibility = custom ? Visibility.Visible : Visibility.Collapsed;
        _proxyLoading = false;
    }

    private void StatusText(string text)
    {
        Error.Visibility = Visibility.Collapsed;
        Status.Text = text;
        Status.Visibility = Visibility.Visible;
    }

    private void ErrorText(string text)
    {
        Status.Visibility = Visibility.Collapsed;
        Error.Text = text;
        Error.Visibility = Visibility.Visible;
    }
}
