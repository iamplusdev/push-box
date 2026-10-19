using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Update;

namespace PushBox.Windows.Views;

/// <summary>检查之后界面上要说的那句话的全部材料：当前版本、有没有新版、新版本号、更新说明。</summary>
public sealed record UpdateInfo(string CurrentVersion, bool HasUpdate, bool HasInstaller,
    string LatestVersion, string Notes)
{
    /// <summary>列表行/状态格那句人话。判定在这里算，好让用例钉得住，而不是等人去点。</summary>
    public string StatusLine
    {
        get
        {
            if (!HasUpdate) return $"已是最新版本（{CurrentVersion}）";
            return HasInstaller ? $"发现新版本 {LatestVersion}（当前 {CurrentVersion}）"
                : $"最新版本 {LatestVersion}，但这次没有可下载的 Windows 安装包";
        }
    }

    /// <summary>只有在"有新版且有对应安装包"时才给下载按钮。</summary>
    public bool CanInstall => HasUpdate && HasInstaller;
}

/// <summary>
/// 更新这一页的全部判定（§8.4 之后追加，对齐 Android 的 UpdateControls）。它把
/// <see cref="Update.UpdateClient"/> 那条 GitHub 公开接口包在 <see cref="Outcome{T}"/> 里，
/// 网络异常一律翻成人话；下载的落盘位置、装完怎么退进程都由这里定，code-behind 只交控件上的字和拿回的状态。
/// </summary>
public sealed class UpdateModel
{
    private readonly Update.UpdateClient _client;

    /// <summary>上一次 <see cref="CheckAsync"/> 成功拿到的那一版 release（含下载 URL）。
    /// 界面下载时直接用它，不再为取一个 URL 重复请求 GitHub——未认证限额很紧。</summary>
    public GitHubRelease? LastRelease { get; private set; }

    public UpdateModel(Update.UpdateClient? client = null) => _client = client ?? new Update.UpdateClient();

    /// <summary>安装包落哪儿：与库、媒体缓存同根（<c>%LOCALAPPDATA%\PushBox\updates</c>），私有、不进漫游配置。
    /// 用真实文件名而不改名——Inno 的卸载信息里带着它，装完这份留着还能重装。</summary>
    public static string InstallerPathOf(string assetName)
    {
        var safe = string.IsNullOrWhiteSpace(assetName) ? "PushBox-Setup-win-x64.exe" : Path.GetFileName(assetName);
        return Path.Combine(IdentityStore.DefaultDirectory, "updates", safe);
    }

    /// <summary>拉最新 release，比对本机版本，产出界面要显示的那一句。检查失败翻成 Outcome.Bad。</summary>
    public async Task<Outcome<UpdateInfo>> CheckAsync(CancellationToken ct = default)
    {
        try
        {
            LastRelease = null;
            var current = Update.UpdateClient.CurrentVersion();
            var latest = await _client.CheckLatestAsync(ct).ConfigureAwait(false);
            var newer = Update.UpdateClient.IsNewer(latest, current);
            if (newer && latest.HasInstaller) LastRelease = latest;
            return Outcome<UpdateInfo>.Good(new UpdateInfo(current, newer, latest.HasInstaller,
                latest.Version, latest.Notes));
        }
        catch (Exception e)
        {
            return Outcome<UpdateInfo>.Bad(e);
        }
    }

    /// <summary>
    /// 只负责把 setup.exe 下载到本地并回它的落盘路径，<b>不</b>在这里拉起安装：拉起要调
    /// <see cref="UpdateClient.LaunchInstallAndExit"/>，而那一句里的 <c>App.ExitApp()</c> 会在 UI 线程上
    /// <c>Close()/Shutdown()</c>，绝不能被 <c>ConfigureAwait(false)</c> 续延挪到线程池上执行。
    /// 所以下载（可在线程池）与拉起（回 UI 线程）分开，由调用方在拿到路径后于 UI 线程完成。
    /// </summary>
    public async Task<Outcome<string>> DownloadAsync(GitHubRelease latest,
        IProgress<(long done, long total)>? progress, CancellationToken ct = default)
    {
        if (!latest.HasInstaller)
            return Outcome<string>.Bad(new InvalidOperationException("这次没有可下载的安装包"));
        try
        {
            var dest = InstallerPathOf(latest.InstallerName);
            await _client.DownloadAsync(latest.InstallerUrl!, dest, progress, ct).ConfigureAwait(false);
            return Outcome<string>.Good(dest);
        }
        catch (Exception e)
        {
            return Outcome<string>.Bad(e);
        }
    }

    /// <summary>供托盘/静默检查复用：只判断"这台有没有可装的新版"，不弹任何界面。</summary>
    public async Task<GitHubRelease?> NewInstallerOrNullAsync(CancellationToken ct = default)
    {
        try
        {
            var current = Update.UpdateClient.CurrentVersion();
            var latest = await _client.CheckLatestAsync(ct).ConfigureAwait(false);
            return latest.HasInstaller && Update.UpdateClient.IsNewer(latest, current) ? latest : null;
        }
        catch (Exception)
        {
            // 静默检查连不上 GitHub 是常态（离线、限流），不值得冒出来打扰——留痕由调用方决定，这里只回"没有新版"
            return null;
        }
    }
}
