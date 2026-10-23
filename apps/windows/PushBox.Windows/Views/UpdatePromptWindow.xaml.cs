using System;
using System.Threading.Tasks;
using System.Windows;
using PushBox.Windows.Update;

namespace PushBox.Windows.Views;

/// <summary>
/// D32 的更新确认框。<b>只负责"问一次 + 下载 + 拉起安装"</b>：发现新版后由自动检查或手动检查叫起这一扇，
/// 用户点"下载并安装"才真去下载 <c>setup.exe</c> 并拉起 Inno 静默安装——绝不在这扇窗出现前自动下载。
/// 下载在后台线程跑（<see cref="UpdateModel.DownloadAsync"/>），装完的 <c>ExitApp</c> 回 UI 线程执行。
/// </summary>
public sealed partial class UpdatePromptWindow : Window
{
    private readonly UpdateModel _update;
    private readonly GitHubRelease _latest;
    private readonly Action? _beforeExit;
    private bool _downloading;

    /// <summary>用例读的三格：按钮态与那一行进度/结果，好让"确认前不下载"能被断言而不靠人点。</summary>
    public bool ProbeInstallEnabled => InstallButton.IsEnabled;
    public string ProbeStatus => Status.Text;
    public bool ProbeNotesVisible => NotesScroll.Visibility == Visibility.Visible;

    public UpdatePromptWindow(UpdateModel update, GitHubRelease latest, Action? beforeExit)
    {
        _update = update;
        _latest = latest;
        _beforeExit = beforeExit;
        InitializeComponent();
        Headline.Text = $"发现新版本 {latest.Version}（当前 {UpdateClient.CurrentVersion()}），是否下载并安装？";
        if (!string.IsNullOrWhiteSpace(latest.Notes))
        {
            Notes.Text = latest.Notes;
            NotesScroll.Visibility = Visibility.Visible;
        }
    }

    private void OnLater(object sender, RoutedEventArgs e) => Close();

    private void OnInstall(object sender, RoutedEventArgs e)
    {
        if (_downloading) return;
        _downloading = true;
        InstallButton.IsEnabled = false;
        _ = RunDownload();
    }

    private async Task RunDownload()
    {
        var progress = new Progress<(long done, long total)>(p =>
            StatusText(p.total > 0
                ? $"下载中… {p.done * 100 / p.total}%"
                : $"下载中… {SettingsModel.BytesLabel(p.done)}"));
        var done = await _update.DownloadAsync(_latest, progress);
        if (!done.Ok)
        {
            _downloading = false;
            InstallButton.IsEnabled = true;
            ErrorText(done.Error);
            return;
        }

        var target = done.Value!;
        // 没有 beforeExit（脱离 App 的用例）就不拉起安装，只报落盘位置——测试机上不该真去装
        if (_beforeExit == null)
        {
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
                _downloading = false;
                InstallButton.IsEnabled = true;
                ErrorText("无法启动安装程序：" + e.Message);
            }
        }));
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
