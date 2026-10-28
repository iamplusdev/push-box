using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Reflection;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Net;

namespace PushBox.Windows.Update;

/// <summary>
/// GitHub Releases 上"<b>选中那一个 release</b>"在本机眼里的样子：够决定要不要升、升下来是哪个文件，
/// 别的字段（作者、时间、下载计数）一概不取——更新检查只关心这三件事。
/// </summary>
public sealed record GitHubRelease(string Tag, string Version, string Notes,
    string? InstallerUrl, string InstallerName, long InstallerSize)
{
    /// <summary>没有可下载的 windows 安装包时，界面上要能说"有新版本但这台升不了"，而不是假装没更新。</summary>
    public bool HasInstaller => InstallerUrl is { Length: > 0 };
}

/// <summary>
/// 下载到某一刻的读数。<see cref="Total"/> 为 0 表示服务端没给 Content-Length（走 chunked 时可能），
/// 此时 <see cref="Percent"/> 是 -1——界面只报已下多少，不编一个假百分比。
/// </summary>
public readonly record struct DownloadTick(long Done, long Total, double BytesPerSecond)
{
    public bool HasTotal => Total > 0;

    public int Percent => HasTotal ? (int)Math.Min(100L, Done * 100L / Total) : -1;
}

/// <summary>
/// 把每 80KB 一次的字节回调压成"人看得清"的频率。一个 40MB 的包按 80KB 一格要回调 500 次，
/// 每次都 <c>IProgress.Report</c> 到 UI 线程——进度那行字一秒最多看得清两三次，多出来的只是排队。
/// 顺手把字节差算成速度：界面上"还有多久"就是拿它除出来的。
/// </summary>
public sealed class DownloadMeter
{
    /// <summary>两格之间最短的间隔。再短就只是在刷同一行字。</summary>
    public static readonly TimeSpan MinInterval = TimeSpan.FromMilliseconds(200);

    private bool _started;
    private long _lastDone;
    private TimeSpan _lastAt;
    private double _speed;

    /// <summary>
    /// 算这一格的读数；没到间隔就返回 null。第一格<b>总</b>发：那一下让界面从"正在连接"换成
    /// "下载中 0%"，用户才知道请求真发出去了。速度要到第二格才有意义（之前没有时间差可比）。
    /// </summary>
    public DownloadTick? Sample(long done, long total, TimeSpan elapsed)
    {
        if (!_started)
        {
            _started = true;
            _lastDone = done;
            _lastAt = elapsed;
            return new DownloadTick(done, total, 0);
        }

        var span = elapsed - _lastAt;
        if (span < MinInterval) return null;

        // 瞬时速度丢一次包就会从 3 MB/s 掉到 20 KB/s，直接写上去就是一行自己在跳的字。
        // 平滑一次：新读数只占四成，方向还是对的，抖动却看不出来了
        var instant = (done - _lastDone) / span.TotalSeconds;
        _speed = _speed > 0 ? _speed * 0.6 + instant * 0.4 : instant;
        _lastDone = done;
        _lastAt = elapsed;
        return new DownloadTick(done, total, _speed);
    }

    /// <summary>收尾那一格不看间隔：不补这一下，进度会停在 98% 而文件其实已经下完。</summary>
    public DownloadTick Finish(long done, long total) => new(done, total, _speed);
}

/// <summary>下载包走哪条网络。系统代理是默认——机器上本来就配了代理的人不该再填一遍。</summary>
public enum UpdateProxyMode
{
    System,
    Direct,
    Custom
}

/// <summary>一次下载的代理口径（设置里存的就是这一份）。</summary>
public sealed record ProxyChoice(UpdateProxyMode Mode = UpdateProxyMode.System, string Address = "")
{
    /// <summary>
    /// "127.0.0.1:7890" 与 "http://127.0.0.1:7890" 都认，其余一律 null：
    /// 只收 http(s) 代理，且必须显式写端口——<c>Uri</c> 会把 "7890" 当成主机名 + 80 端口，
    /// 那样一个手滑的地址会变成一条指向陌生主机的代理。
    /// </summary>
    public static Uri? AddressOf(string? text)
    {
        var value = (text ?? "").Trim();
        if (value.Length == 0) return null;
        if (!value.Contains("://", StringComparison.Ordinal)) value = "http://" + value;
        return Uri.TryCreate(value, UriKind.Absolute, out var uri)
               && (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps)
               && !uri.IsDefaultPort
            ? uri
            : null;
    }

    public Uri? Endpoint => AddressOf(Address);
}

/// <summary>
/// 更新检查的独立走法：<b>公开 https 的 GitHub API</b>，与用户在设置里填的那台业务服务器毫无关系，
/// 因此它不进密封盒、不带 Authorization、也不复用 <see cref="DeviceClient"/> 的签名链——
/// 那条链上的每一层（token、Ed25519 签名、X-PB-* 头）对 api.github.com 都只会换来一个 401。
/// 手写的 <see cref="JsonDocument"/> 读法与 <see cref="ChannelInfo.From"/> 同一条口径：缺字段按缺省兜，
/// 绝不用 <c>JsonSerializer.Deserialize</c>（本仓库没有一处用它，见各 record 的 From 助手）。
///
/// 未认证 GitHub API 的限额是 60 次/小时/IP，所以这条路只在"启动静默查一次"和"手动点检查"两处走，
/// 绝不进 15 分钟兜底轮询——那会把限额在一两天内烧光，之后所有更新检查都吃 403。
/// </summary>
public sealed class UpdateClient
{
    public const string Owner = "iamplusdev";
    public const string Repo = "push-box";

    // 分端发布后每个平台各占一个 Release，/releases/latest 只会返回"最后发的那个"：
    // Windows 可能读到 Android 的 Release（没有 exe → 永远不提示更新），反过来也一样。
    // 所以改读列表，按资产名挑出属于本平台的那一条；标签也因此不能假定是 vX.Y.Z。
    public const string ReleasesUrl = $"https://api.github.com/repos/{Owner}/{Repo}/releases?per_page=30";

    /// <summary>GitHub 无 UA 直接 403，这一句是硬性要求而不是礼貌。</summary>
    private const string UserAgent = "PushBox-Windows";

    /// <summary>安装包名的前后缀：CI 产物是 <c>PushBox-Setup-{version}-win-x64.exe</c>（见 installer/PushBox.iss 的 OutputBaseFilename）。</summary>
    private const string InstallerPrefix = "PushBox-Setup-";
    private const string InstallerArch = "win-x64";

    private readonly HttpClient _http;
    private readonly TimeSpan _checkTimeout;

    /// <summary>下载用的客户端按代理口径现造：同一个 <see cref="HttpClientHandler"/> 不能改代理再复用
    /// （改了也不会对已建的连接生效），而用户完全可能"这一把直连、下一把挂代理"地重试。
    /// 交给构造参数是为了用例能塞一个假的传输层。</summary>
    private readonly Func<ProxyChoice?, HttpClient> _downloadClient;

    public UpdateClient(HttpClient? http = null, TimeSpan? checkTimeout = null,
        Func<ProxyChoice?, HttpClient>? downloadClient = null)
    {
        _checkTimeout = checkTimeout ?? TimeSpan.FromSeconds(20);
        _http = http ?? new HttpClient();
        // 检查这一趟要设超时；真正的 setup.exe 下载走另一个客户端（见 DownloadAsync），那里必须不限时
        if (http == null) _http.Timeout = _checkTimeout;
        _downloadClient = downloadClient ?? CreateDownloadClient;
    }

    /// <summary>
    /// 本机显示版本：<b>三段</b>语义与 <c>App.VersionOf()</c> 一致（"0.1.0"），但这里要能带上预发布后缀，
    /// 所以优先读 <see cref="AssemblyInformationalVersionAttribute"/>。SourceLink 会把 informational 版本
    /// 追加成 "0.1.0+abc123"，'+' 之后是提交哈希、不参与比较，取到第一个 '+' 为止。
    /// 拿不到 attributes 时退回 <c>GetName().Version.ToString(3)</c>，与日志里那行同一个号。
    /// </summary>
    public static string CurrentVersion()
    {
        var assembly = typeof(UpdateClient).Assembly;
        var info = assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        if (!string.IsNullOrWhiteSpace(info))
        {
            var plus = info.IndexOf('+');
            var trimmed = (plus >= 0 ? info[..plus] : info).Trim();
            if (trimmed.Length > 0) return trimmed;
        }

        return assembly.GetName().Version?.ToString(3) ?? "0.0.0";
    }

    /// <summary>拉最近的 releases，挑出第一条带 windows 安装包的；任何非 2xx 都翻成一句人话，交给界面直接显示。</summary>
    public async Task<GitHubRelease> CheckLatestAsync(CancellationToken ct = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, ReleasesUrl);
        request.Headers.UserAgent.ParseAdd(UserAgent);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(_checkTimeout);
        HttpResponseMessage response;
        try
        {
            response = await _http.SendAsync(request, timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            throw new HttpRequestException($"更新检查在 {_checkTimeout.TotalSeconds:0} 秒内没有响应");
        }

        using (response)
        {
            var text = await response.Content.ReadAsStringAsync(CancellationToken.None).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                var reason = response.StatusCode switch
                {
                    System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized
                        => "GitHub 接口限流或未授权（未登录限额 60 次/小时），稍后再试",
                    System.Net.HttpStatusCode.NotFound => "还没有发布过任何版本",
                    _ => $"HTTP {(int)response.StatusCode}"
                };
                throw new HttpRequestException($"更新检查失败：{reason}");
            }

            using var doc = JsonDocument.Parse(text);
            if (doc.RootElement.ValueKind != JsonValueKind.Array)
                throw new HttpRequestException("更新检查失败：GitHub 返回的不是 releases 列表");

            // 列表按创建时间倒序，逐条找第一条带 Windows 安装包的 release；
            // 服务端/Android 的 Release 不会被误当成"最新版"。
            foreach (var release in doc.RootElement.EnumerateArray())
            {
                var parsed = FromJson(release);
                if (parsed.HasInstaller) return parsed;
            }

            // 一条带安装包的 Release 都没有：等同"没有可装的版本"。
            return new GitHubRelease("", "0.0.0", "", null, "", 0L);
        }
    }

    /// <summary>解析一个 release 对象。字段读法借用 <see cref="DeviceClient"/> 那三个 internal 助手，保持全仓库同一种兜底口径。</summary>
    internal static GitHubRelease FromJson(JsonElement root)
    {
        var tag = DeviceClient.Str(root, "tag_name") ?? "";
        var notes = DeviceClient.Str(root, "body") ?? "";

        string? url = null;
        var name = "";
        long size = 0L;
        foreach (var asset in DeviceClient.Objects(root, "assets"))
        {
            var assetName = DeviceClient.Str(asset, "name") ?? "";
            if (!IsWindowsInstaller(assetName)) continue;
            // 同名有多个（比如顺手传了带 debug 的）时取第一个匹配的即可；真正的下载凭据是 browser_download_url
            var download = DeviceClient.Str(asset, "browser_download_url");
            if (string.IsNullOrEmpty(download)) continue;
            url = download;
            name = assetName;
            size = DeviceClient.Long(asset, "size");
            break;
        }

        return new GitHubRelease(tag, VersionOf(tag), notes, url, name, size);
    }

    /// <summary>
    /// 从 <c>tag_name</c> 取可比对的版本：<c>windows-v1.2.3</c> 与旧的 <c>v1.2.3</c> 都取 <c>1.2.3</c>，
    /// 其余原样交给 <see cref="IsNewer"/> 的分段解析。
    /// </summary>
    public static string VersionOf(string tag)
    {
        var value = (tag ?? "").Trim();
        const string componentPrefix = "windows-v";
        if (value.StartsWith(componentPrefix, StringComparison.OrdinalIgnoreCase))
            return value[componentPrefix.Length..];
        return value.Length > 0 && (value[0] == 'v' || value[0] == 'V') ? value[1..] : value;
    }

    private static bool IsWindowsInstaller(string name) =>
        name.StartsWith(InstallerPrefix, StringComparison.OrdinalIgnoreCase)
        && name.Contains(InstallerArch, StringComparison.OrdinalIgnoreCase)
        && name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// "该不该升"：逐段比整数，与 Android 的 <c>UpdateApi.isNewer</c> 同一条算法。
    /// 缺的段当 0，非数字尾巴（"0.1.1-beta"里的 "-beta"）在取段时只留前导数字，所以预发布串不会比崩。
    /// </summary>
    public static bool IsNewer(GitHubRelease latest, string current) =>
        Compare(latest.Version, current) > 0;

    /// <summary>正数=left 更新，0=相同，负数=right 更新。按 '.' 分段各取前导整数。</summary>
    public static int Compare(string left, string right)
    {
        var a = Split(left);
        var b = Split(right);
        var n = Math.Max(a.Length, b.Length);
        for (var i = 0; i < n; i++)
        {
            var x = i < a.Length ? a[i] : 0;
            var y = i < b.Length ? b[i] : 0;
            if (x != y) return x > y ? 1 : -1;
        }

        return 0;
    }

    private static int[] Split(string version)
    {
        var parts = (version ?? "").Split('.');
        var nums = new int[parts.Length];
        for (var i = 0; i < parts.Length; i++) nums[i] = LeadingInt(parts[i]);
        return nums;
    }

    /// <summary>取一段里的前导数字；非数字开头（含空）记 0。"3-beta"→3，"beta"→0。</summary>
    private static int LeadingInt(string segment)
    {
        var digits = "";
        foreach (var c in segment ?? "")
        {
            if (c is >= '0' and <= '9') digits += c;
            else break;
        }

        return digits.Length > 0
               && int.TryParse(digits, NumberStyles.None, CultureInfo.InvariantCulture, out var value)
            ? value
            : 0;
    }

    /// <summary>
    /// 流式下载 setup.exe 到 <paramref name="dest"/>：先写 <c>dest+".tmp"</c> 再整文件改名，
    /// 与 <see cref="Media.MediaCache.Put"/> 同一条"中途被杀不留半个包"的口径（那半个包下次是装不上的）。
    /// 这里必须换一个不限总超时的客户端：一次检查 20 秒足够，但一个几十 MB 的安装包在慢网上不该被 20 秒掐断。
    /// <paramref name="progress"/> 是掐过频的读数（见 <see cref="DownloadMeter"/>），
    /// <paramref name="proxy"/> 决定这一把走哪条网络——失败重试时可以换一档再试。
    /// </summary>
    public async Task DownloadAsync(string url, string dest, IProgress<DownloadTick>? progress,
        ProxyChoice? proxy = null, CancellationToken ct = default)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
        var temp = dest + ".tmp";
        try
        {
            File.Delete(temp);
        }
        catch (IOException)
        {
            // 上一次的半截临时文件删不掉（被占用）也没关系，下面 Create 会覆盖
        }

        using var client = _downloadClient(proxy);
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.UserAgent.ParseAdd(UserAgent);
        // Accept: octet-stream 让 GitHub 直发二进制而非又包一层 JSON
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/octet-stream"));

        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct)
            .ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            var code = (int)response.StatusCode;
            throw new HttpRequestException($"下载失败：HTTP {code}");
        }

        // 0 = 服务端没给长度（chunked）：界面只报已下多少，不编假百分比
        var total = response.Content.Headers.ContentLength ?? 0L;
        await using var src = await response.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        await using var dst = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None,
            81920, useAsync: true);

        var buffer = new byte[81920];
        var meter = new DownloadMeter();
        var clock = Stopwatch.StartNew();
        long done = 0L;
        int read;
        while ((read = await src.ReadAsync(buffer, ct).ConfigureAwait(false)) > 0)
        {
            await dst.WriteAsync(buffer.AsMemory(0, read), ct).ConfigureAwait(false);
            done += read;
            // meter 自己按 200ms 掐：每 80KB 报一次，界面一秒要刷几十下，而人只看得清两三下
            if (meter.Sample(done, total, clock.Elapsed) is { } tick) progress?.Report(tick);
        }

        await dst.FlushAsync(ct).ConfigureAwait(false);
        dst.Close();

        // 有声明长度就核对：字节少了多半是连接被中途切断，留着这个包只会安装时报一个看不懂的错
        if (total > 0 && done != total)
            throw new IOException($"下载不完整：期望 {total.ToString(CultureInfo.InvariantCulture)} 字节，实际 {done.ToString(CultureInfo.InvariantCulture)}");

        // 最后一格不看间隔：不然界面停在上一次的百分比上，看起来像卡住了
        progress?.Report(meter.Finish(done, total));
        File.Move(temp, dest, true);
    }

    /// <summary>
    /// 下载专用客户端：不设总超时（大文件），也不复用检查那一只——检查 20 秒就该有结论，
    /// 而一个几十 MB 的安装包在慢网上不该被 20 秒掐断。
    /// 代理按用户选的那一档现配：默认跟随系统，直连就把 <c>UseProxy</c> 关掉，
    /// 自定义则填一支 <see cref="WebProxy"/>——设置里那个代理开着但下载走不通时，重试前换这一档。
    /// </summary>
    private static HttpClient CreateDownloadClient(ProxyChoice? proxy) => new(HandlerFor(proxy))
    {
        Timeout = System.Threading.Timeout.InfiniteTimeSpan
    };

    /// <summary>
    /// 代理三档落到 handler 上。自定义写歪了（<see cref="ProxyChoice.AddressOf"/> 给 null）时
    /// <b>不</b>报错、也不退回直连：原样留成系统默认那一条，至少还有一个能下完的可能，
    /// 而错误可以在界面上说清——为了一个填歪的端口把下载拦下来没有第二个好处。
    /// </summary>
    public static HttpClientHandler HandlerFor(ProxyChoice? proxy)
    {
        var handler = new HttpClientHandler { AllowAutoRedirect = true };
        switch (proxy?.Mode ?? UpdateProxyMode.System)
        {
            case UpdateProxyMode.Direct:
                handler.UseProxy = false;
                break;
            case UpdateProxyMode.Custom when proxy?.Endpoint is { } endpoint:
                handler.Proxy = new WebProxy(endpoint);
                break;
        }

        return handler;
    }

    /// <summary>
    /// 拉起 Inno 安装并交出退出权：<see cref="Process.Start"/> 之后立刻调用 <paramref name="beforeExit"/>
    /// （即 <c>App.ExitApp()</c>——它释放单实例 mutex、关库、冲日志），安装器才能覆盖正在运行的这份文件。
    /// <c>/VERYSILENT</c> 对应"用户只在升级前确认那一次"，装完由设置页提示手动重启，
    /// 不选 <c>/RESTARTAPPLICATIONS</c>：那会赶在旧进程还没让开 mutex 时就重开一份。
    /// </summary>
    public static void LaunchInstallAndExit(string installerPath, Action beforeExit)
    {
        var start = new ProcessStartInfo(installerPath)
        {
            Arguments = "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART",
            UseShellExecute = true,
            WorkingDirectory = Path.GetDirectoryName(installerPath) ?? ""
        };
        System.Diagnostics.Process.Start(start);
        beforeExit();
    }
}
