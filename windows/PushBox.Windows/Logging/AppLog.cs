using System;
using System.IO;
using System.Text;
using Serilog;
using Serilog.Core;
using Serilog.Events;

namespace PushBox.Windows.Logging;

/// <summary>
/// §8.4 W15：PC 端的留痕。<b>它是"能写日志"的唯一入口</b>——外面（引擎、删除、媒体、通知、App 装配）
/// 只能调下面这些方法，每个方法的参数都是类型化的小字段（码、计数、游标、毫秒、异常类型名），
/// 所以"把正文写进日志"这件事在签名层面就没有路：不是"记得别传"，而是<b>没有那个形参</b>。
///
/// 三层职责分得很清：
/// - 这一页：白名单事件入口 + 装配（滚动文件、崩溃挂钩、启动/收工）；
/// - <see cref="PrivacyEnricher"/>：属性级净化（非基元只留类型名、黑名单名整值替换）；
/// - <see cref="PrivacyFormatter"/> + <see cref="PrivacyGate"/>：文本级净化，落盘前的最后一道网。
///
/// 另外两条刻意的口径：
/// - <b>没 <c>Start()</c> 就一个字都不写</b>（<see cref="_log"/> 为空 ⇒ 所有入口静默返回）。
///   这样测试里那些跑真引擎、真删除、真取图的路径不会往 %LOCALAPPDATA% 里落文件，而要看留痕的进程
///   必须显式开一次；测试用临时目录调 <see cref="Start"/>，验收口径与真机同一条路。
/// - <b>日志起不来绝不能带走应用</b>：目录建不出来、盘满、ACL 不让写，都只是"这台机器没有留痕"，
///   消息照收照存。<see cref="CreateLogger"/> 也照这个原则：它自己吞异常，永远返回可用实例。
///
/// 崩溃那条路只写文件，<b>没有任何上报</b>（§14"崩溃上报关闭"）：这一页没有 HttpClient、没有 URL，
/// 所以"上报开着"这件事在代码里就不成立，不是靠文档承诺。
/// </summary>
public static class AppLog
{
    /// <summary>留痕目录的名字，与 pushbox.db / identity.json / settings.json 同父目录（§8.1）。</summary>
    public const string FolderName = "logs";

    /// <summary>
    /// 文件名的模板。<b>Serilog 的滚动是把日期插在扩展名之前</b>，所以这里写 <c>pushbox-.log</c> 得到的就是
    /// <c>pushbox-20261015.log</c>；曾经照着"文件名里有个 {Date} 位"的想法写成 <c>pushbox-{Date}.log</c>，
    /// 结果落盘的是 <c>pushbox-{Date}20261015.log</c>——那个短横线本身就是留给日期的位子。
    /// </summary>
    public const string FileNamePattern = "pushbox-.log";

    /// <summary>§8.1 自用机器的留痕成本：一天一本、最多留一星期、单本超 4MB 再切。写满不是留着不删的理由。</summary>
    public const int RetainedFiles = 7;

    public const long FileSizeLimitBytes = 4L * 1024 * 1024;

    private static readonly object Gate = new();

    private static Logger? _log;

    /// <summary>留痕有没有开。只给体检/测试看，业务分支不许读它。</summary>
    public static bool IsStarted
    {
        get
        {
            lock (Gate) return _log != null;
        }
    }

    /// <summary>真开一次留痕（幂等）。返回是否真的在写——调用方不需要据此改变行为，只是给体检一个可断言的值。</summary>
    public static bool Start(string? directory = null)
    {
        lock (Gate)
        {
            if (_log != null) return true;
            _log = CreateLogger(directory);
            return _log != null;
        }
    }

    /// <summary>收工：Serilog 的 dispose 会把缓冲写完并关闭句柄。<see cref="CloseAndFlush"/> 之后可以再 Start。</summary>
    public static void CloseAndFlush()
    {
        Logger? closing;
        lock (Gate)
        {
            closing = _log;
            _log = null;
        }

        try
        {
            closing?.Dispose();
        }
        catch (Exception)
        {
            // 收工阶段的写不进去（比如临时目录已被删）不值得让进程难看
        }
    }

    /// <summary>当前留痕目录（<c>%LOCALAPPDATA%\PushBox\logs</c>，测试注入临时目录）。纯算路径，不建目录。</summary>
    public static string DirectoryFor(string? home = null) =>
        Path.Combine(home ?? Data.IdentityStore.DefaultDirectory, FolderName);

    /// <summary>
    /// 装配一条"文件 + 三层脱敏"的日志。<b>公开是为了让验收用例能把整条链（富化器 + 格式化器 + 真文件）
    /// 跑在自己的临时目录里</b>，而不是只测纯函数；<see cref="Start"/> 用的就是这一个方法。
    /// 出任何事都返回 null：调用方按"这台机器没留痕"继续跑。
    /// </summary>
    public static Logger? CreateLogger(string? home = null)
    {
        try
        {
            var dir = DirectoryFor(home);
            Directory.CreateDirectory(dir);
            return new LoggerConfiguration()
                .MinimumLevel.Information()
                .Enrich.With(new PrivacyEnricher())
                .WriteTo.File(new PrivacyFormatter(), Path.Combine(dir, FileNamePattern),
                    rollingInterval: RollingInterval.Day,
                    retainedFileCountLimit: RetainedFiles,
                    fileSizeLimitBytes: FileSizeLimitBytes,
                    rollOnFileSizeLimit: true,
                    encoding: Encoding.UTF8)
                .CreateLogger();
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>下面这些入口的顺序就是它们被调用的顺序，一条一个用途；新增入口的唯一要求是参数必须类型化。</summary>
    public static void Started(string version, bool enrolled) =>
        Write(LogEventLevel.Information, "启动 {Version} 身份={Enrolled}", version, enrolled ? "已注册" : "待注册");

    public static void Stopped() => Write(LogEventLevel.Information, "收工");

    /// <summary>§8.1 单实例：这一次启动发现自己不是主实例，递了 URI（或压根没有 URI）就退场。
    /// 不写 exe 路径：那一串里带着安装目录，而"这台机器上装了几份"不该由日志来回答。</summary>
    public static void SecondInstanceLeft(bool targetSent) =>
        Write(LogEventLevel.Information, "第二个实例退出 递送={Sent}", targetSent ? "已递" : "无目标");

    /// <summary>§7.2：一次断开与它的下一次重试。reason 是 <c>PushResult.Reason</c>，服务端/本端定死的词表，不含内容。</summary>
    public static void SyncOffline(string reason, int retryInSeconds) =>
        Write(LogEventLevel.Warning, "长连接断开 {Reason}，{Seconds} 秒后重连", reason, retryInSeconds);

    /// <summary>重连不会自己变好的那一类（设备被吊销、服务端身份对不上）：顶栏挂"需要处理"，日志留同一句。</summary>
    public static void SyncBlocked(string reason) =>
        Write(LogEventLevel.Error, "同步已停：{Reason}", reason);

    /// <summary>SyncEngine.cs:385 那条预留：补拉这一轮没补成（游标没动，下次连接再走同一条路径）。</summary>
    public static void BackfillRefused(string code, int status) =>
        Write(LogEventLevel.Warning, "补拉被服务端拒 {Code} ({Status})", code, status);

    public static void BackfillUnreachable(string exceptionType) =>
        Write(LogEventLevel.Warning, "补拉连不上 {Type}", exceptionType);

    /// <summary>身份文件读不出来时用手上那一份继续跑——这条不留痕的话，"为什么同步用的是旧游标"就查不到。</summary>
    public static void IdentityFileUnreadable(string exceptionType) =>
        Write(LogEventLevel.Warning, "身份文件读不出，沿用手上那份 {Type}", exceptionType);

    /// <summary>同步循环带出来的非协议异常：<c>Task</c> 没人 await 就会静默，所以 <see cref="App"/> 观察它并留这一句。</summary>
    public static void SyncLoopCrashed(string exceptionType) =>
        Write(LogEventLevel.Error, "同步循环崩了 {Type}", exceptionType);

    /// <summary>
    /// §9.3 删除的结局。<c>outcome</c> 取 <see cref="Sync.DeleteOutcome"/> 的名字，
    /// 内容类字段一个都不带：msg_id 是可日志的（它不含正文），但正文与摘要不在这个签名里。
    /// </summary>
    public static void DeleteAttempted(string scope, string outcome, string? code, string? exceptionType) =>
        Write(LogEventLevel.Information, "删除 {Scope} 结局={Outcome} {Code} {Type}",
            scope, outcome, code ?? "-", exceptionType ?? "-");

    /// <summary>§4.5 取字节失败：kind 只区分 thumb/avatar，槽位状态由 UI 说，日志只留异常类型名。</summary>
    public static void MediaFetchFailed(string kind, string exceptionType) =>
        Write(LogEventLevel.Warning, "取图失败 {Kind} {Type}", kind, exceptionType);

    /// <summary>图片磁盘缓存写不进去（盘满、目录被删）：内存那份还在，图照样看得见，所以只是留一行。</summary>
    public static void MediaCacheWriteFailed(string exceptionType) =>
        Write(LogEventLevel.Warning, "图片落盘失败 {Type}", exceptionType);

    /// <summary>W9 的已读上报：一次批量发送的结局，只有条数和错误码。攒批丢了不该让人猜。</summary>
    public static void ReadReported(int count, string? code) =>
        Write(LogEventLevel.Information, "已读上报 {Count} 条 {Code}", count, code ?? "ok");

    /// <summary>§7.3 通知降级到托盘气泡的那一刻。WinRT 拒绝了 toast，这是唯一能看出"为什么没弹"的留痕。</summary>
    public static void ToastFellBack(string exceptionType) =>
        Write(LogEventLevel.Warning, "通知走退路 {Type}", exceptionType);

    /// <summary>§5.2 搜索的服务端那半被拒：本机那半照常出结果，所以只在日志里说明"另一半为什么没有"。</summary>
    public static void SearchRefused(string code, string exceptionType) =>
        Write(LogEventLevel.Warning, "服务端搜索被拒 {Code} {Type}", code, exceptionType);

    /// <summary>§5.2 展开全文没取回来：气泡那半截正文还在，那行按钮也还在，所以只留类型名（msg_id 都不必带）。</summary>
    public static void FulltextRefused(string exceptionType) =>
        Write(LogEventLevel.Warning, "全文回捞没成 {Type}", exceptionType);

    /// <summary>settings.json 落不了盘（§8.1 那次开关仍然生效，重启回旧值）：这条不留在日志里就永远没人知道。</summary>
    public static void SettingsSaveFailed(string exceptionType) =>
        Write(LogEventLevel.Warning, "设置写不进盘 {Type}", exceptionType);

    public static void AutoStartWriteFailed(string exceptionType) =>
        Write(LogEventLevel.Warning, "开机自启写不进注册表 {Type}", exceptionType);

    /// <summary>§8.4 之后的更新检查：只记"发现新版"这件事和版本号，GitHub 的 URL、release 正文都不进日志。</summary>
    public static void UpdateFound(string latest, string current) =>
        Write(LogEventLevel.Information, "发现新版本 {Latest}（当前 {Current}）", latest, current);

    /// <summary>启动静默查更新连不上 GitHub 是常态（离线、限流），只留异常类型名，不打扰用户。</summary>
    public static void UpdateCheckFailed(string exceptionType) =>
        Write(LogEventLevel.Debug, "更新检查没成 {Type}", exceptionType);

    /// <summary>
    /// 崩溃留痕：只把 <see cref="PrivacyGate.ExceptionShapes"/> 那份（类型名 + 堆栈，无 Message）交给日志。
    /// <paramref name="where"/> 是三个挂钩各自的名字，写死在本机代码里，不含任何用户数据。
    /// </summary>
    public static void Unhandled(string where, Exception? error) =>
        Write(LogEventLevel.Fatal, "未处理异常来自 {Where}", where, error);

    /// <summary>
    /// 唯一的 Serilog 出口。<paramref name="error"/> 走 <c>Exception</c> 参数（于是格式化器只取类型名与堆栈），
    /// 其余参数一律是值 —— 这就是为什么加新入口时不需要再想"这条会不会带出正文"。
    /// </summary>
    private static void Write(LogEventLevel level, string template, params object?[] argv)
    {
        Logger? log;
        lock (Gate) log = _log;
        if (log == null) return;

        var error = argv.Length > 0 && argv[^1] is Exception exception ? exception : null;
        var values = error == null ? argv : argv[..^1];
        try
        {
            log.Write(level, error, template, values);
        }
        catch (Exception)
        {
            // 日志自己出问题是日志的事，不能把调用它的那条业务一起带走
        }
    }
}
