using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using PushBox.Windows.Logging;
using PushBox.Windows.Net;
using Serilog.Core;
using Serilog.Events;
using Serilog.Parsing;

namespace PushBox.Crypto.Tests;

/// <summary>
/// §8.4 W15 的验收口径：<b>抓 log 目录 grep 不到任何正文片段</b>。所以这一页的用例全都要过真文件——
/// 富化器、格式化器、Serilog 的滚动 sink、临时目录，一条链跑下来再读回来。
/// 纯函数那五道网各自的分叉在 <c>PrivacyGateTest</c>，这里只管"合起来真的挡住了吗"。
/// 和 <see cref="LogWiringTest"/> 同一个 collection：<see cref="AppLog"/> 是全局的一本，
/// 两个类并行 Start/CloseAndFlush 会互相抢那一本。
/// </summary>
[Collection("AppLog")]
public sealed class AppLogTest : IDisposable
{
    private const string Body = "正文碎片ALPHA 磁盘告警 /mnt 已用 91%";
    private const string Summary = "摘要碎片BRAVO";
    private const string Title = "标题碎片CHARLIE";

    private readonly string _dir = Path.Combine(Path.GetTempPath(),
        "pushbox-log-test-" + Guid.NewGuid().ToString("N"));

    /// <summary>用例里另外开出来的临时留痕目录（"直接装配一本日志"那几条要用），收工时一起删。</summary>
    private readonly List<string> _extraDirs = new();

    private string FreshHome(string tag)
    {
        var home = Path.Combine(Path.GetTempPath(), $"{tag}-{Guid.NewGuid():N}");
        _extraDirs.Add(home);
        return home;
    }

    [Fact]
    public void NothingLandsOnDiskUntilTheAppTurnsTheLogOn()
    {
        // 其余那些跑真引擎、真库、真临时目录的用例都不该顺手在 %LOCALAPPDATA% 落下留痕：
        // Start 之前所有入口是空操作，这是"测试不产生留痕、真机才有留痕"这条分工的实现位置。
        AppLog.SyncOffline("server_identity_mismatch", 4);
        AppLog.DeleteAttempted("message", "Deleted", null, null);
        AppLog.Unhandled("用例", new InvalidOperationException(Body));

        Assert.False(Directory.Exists(AppLog.DirectoryFor(_dir)));
    }

    [Fact]
    public void AWhitelistedEventBecomesOneLineWithItsTimeItsLevelAndItsCode()
    {
        AppLog.Start(_dir);
        AppLog.BackfillRefused("rate_limited", 429);
        AppLog.CloseAndFlush();

        var line = Assert.Single(Lines(_dir));
        Assert.Contains("202", line);
        Assert.Contains("WRN", line);
        Assert.Contains("补拉被服务端拒 rate_limited (429)", line);
    }

    [Fact]
    public void TheTraceIsOneFilePerDayUnderTheSamePrivateFolderAsTheDatabase()
    {
        AppLog.Start(_dir);
        AppLog.Stopped();
        AppLog.CloseAndFlush();

        // §8.1：留痕和 pushbox.db / identity.json / settings.json 同一个私有目录下的一本，一天一本
        Assert.Equal(Path.Combine(_dir, "logs"), AppLog.DirectoryFor(_dir));
        var file = Assert.Single(Directory.GetFiles(AppLog.DirectoryFor(_dir), "pushbox-*.log"));
        // 认到文件名本身而不是"以日期结尾"：Serilog 是把日期插在扩展名之前，写成 pushbox-{Date}.log
        // 会落下一个叫 pushbox-{Date}20261015.log 的文件，而"以日期结尾"这种断言正好放得过它
        Assert.Equal($"pushbox-{DateTime.Now:yyyyMMdd}.log", Path.GetFileName(file));
    }

    [Fact]
    public void GrepOverTheWholeLogDirectoryFindsNoBodyFragment()
    {
        // 这一条就是 W15 的验收本身。写法上刻意把"正常的、带正文的、崩溃的"三类留痕混在一起跑，
        // 然后把两个目录（AppLog 的全局那本 + 直接装配那本）整本读回来逐碎片扫。
        AppLog.Start(_dir);
        AppLog.Started("1.0.0", true);
        AppLog.SyncOffline("心跳判死", 8);
        AppLog.DeleteAttempted("conversation", "RolledBack", "unauthorized", "PushBoxApiException");
        AppLog.MediaFetchFailed("thumb", "PushBoxTransportException");
        AppLog.ReadReported(3, null);
        AppLog.ToastFellBack("COMException");
        AppLog.SearchRefused("unauthorized", "PushBoxApiException");
        AppLog.SettingsSaveFailed("UnauthorizedAccessException");

        var other = FreshHome("pushbox-log-dump");
        var raw = AppLog.CreateLogger(other);
        Assert.NotNull(raw);
        try
        {
            // 三条"有人不守规矩"的路径：整帧当属性传、把回包原文当字符串传、把正文拼进模板
            raw!.Write(LogEventLevel.Information, (Exception?)null, "收到帧 {Frame}", Support.Message(content: Body, summary: Summary, title: Title));
            raw.Write(LogEventLevel.Warning, (Exception?)null, "回包原文 {Raw}", $"{{\"summary\":\"{Summary}\",\"seq\":18481}}");
            raw.Write(LogEventLevel.Error, new InvalidOperationException($"处理 {Title} 失败：{Body}"), "处理消息失败");
        }
        finally
        {
            raw.Dispose();
        }

        AppLog.CloseAndFlush();

        var all = Read(_dir) + Read(other);
        Assert.DoesNotContain("ALPHA", all);
        Assert.DoesNotContain("BRAVO", all);
        Assert.DoesNotContain("CHARLIE", all);
        Assert.DoesNotContain("磁盘告警", all);

        // 反向的那一半：如果整本日志是空的，上面四条断言也只是在骗人。这里要求"该留的确实留下了"
        Assert.Contains("心跳判死", all);
        Assert.Contains("RolledBack", all);
        Assert.Contains("unauthorized", all);
        Assert.Contains("InvalidOperationException", all);
        Assert.Contains("18481", all);
    }

    [Fact]
    public void TheCrashTraceKeepsTheThrowingLineButNotTheSentenceAroundIt()
    {
        AppLog.Start(_dir);
        try
        {
            throw new InvalidDataException($"密封盒解不开，原文是 {Body}");
        }
        catch (Exception e)
        {
            AppLog.Unhandled("同步循环", e);
        }

        AppLog.CloseAndFlush();

        var all = Read(_dir);
        Assert.Contains("InvalidDataException", all);
        Assert.Contains("at PushBox", all);
        Assert.DoesNotContain("ALPHA", all);
        Assert.DoesNotContain("密封盒解不开", all);
    }

    [Fact]
    public void EveryPublicEntryTakesOnlyFieldsThatCannotHoldContent()
    {
        // §14 的正解不是"记得别打正文"，而是"没有那个形参"：公开入口的参数只允许码、计数、游标、
        // 类型名这类小值。将来谁给 AppLog 加一个收 MessageFrame、收 string content、收 byte[] 的入口，
        // 这条用例当场判红——这是整个模块里唯一一条会随新入口变严的断言。
        var allowed = new[] { typeof(string), typeof(int), typeof(long), typeof(bool), typeof(Exception) };
        var offenders = typeof(AppLog)
            .GetMethods(BindingFlags.Public | BindingFlags.Static | BindingFlags.DeclaredOnly)
            .SelectMany(m => m.GetParameters()
                .Select(p => new { m.Name, Type = Nullable.GetUnderlyingType(p.ParameterType) ?? p.ParameterType })
                .Where(p => !allowed.Contains(p.Type))
                .Select(p => $"{p.Name}({p.Type.Name})"))
            .ToList();

        Assert.Empty(offenders);
    }

    [Fact]
    public void ADirectoryThatCannotBeWrittenMeansNoTraceNotADownApp()
    {
        // 盘满、ACL 不让写、目录被用户删掉——留痕起不来只是"这台机器没有留痕"，消息照收照存
        Directory.CreateDirectory(_dir);
        var blocker = Path.Combine(_dir, "not-a-folder");
        File.WriteAllText(blocker, "占位");

        Assert.False(AppLog.Start(blocker));
        Assert.Null(AppLog.CreateLogger(blocker));
        AppLog.SyncBlocked("server_identity_mismatch");
        Assert.False(Directory.Exists(Path.Combine(blocker, "logs")));
    }

    [Fact]
    public void AChunkTooLongToBeAFixedPhraseIsScrubbedEvenWithoutAKeyName()
    {
        // 运行时拼出来的模板（Log.Information($"处理 {body} 失败")）里没有 content= 这种键名，形状网与键值网都抓不到它。
        // 所以字面量那一支走的是 ScrubValue：长度闸先把它拦下——固定措辞不可能有 120 字以上，
        // 超了就只有"这是内容"这一种解释。
        var home = FreshHome("pushbox-log-long");
        var raw = AppLog.CreateLogger(home);
        Assert.NotNull(raw);
        try
        {
            raw!.Write(LogEventLevel.Warning, (Exception?)null,
                "处理 " + string.Concat(Enumerable.Repeat(Body + " ", 6)) + "失败");
        }
        finally
        {
            raw!.Dispose();
        }

        // 整段一起没掉，而不是留下前半——这是长度闸与"截断"的区别
        var all = Read(home);
        Assert.DoesNotContain("ALPHA", all);
        Assert.Contains(PrivacyGate.RedactedTooLong, all);
    }

    [Fact]
    public void AnObjectPassedAsAPropertyContributesOnlyItsTypeName()
    {
        // 最自然的一句 Log.Information("帧 {Frame}", message)：record 的 ToString() 会把 Content 一个字不落吐出来，
        // 所以富化器对"不是基元的对象"只留类型名。这一条是整层里唯一不看模板写法的网。
        var frame = Support.Message(content: Body, summary: Summary);
        var logEvent = Event("{Frame}", new LogEventProperty("Frame", new ScalarValue(frame)));
        new PrivacyEnricher().Enrich(logEvent, new OnlyNames());

        var written = Render(logEvent);
        Assert.Contains("MessageFrame", written);
        Assert.DoesNotContain("ALPHA", written);
    }

    [Fact]
    public void APropertyNamedLikeContentLosesItsValueEvenWhenItIsShort()
    {
        // 长度闸抓不到一句 20 字的摘要，所以名字这一道不能省：{Summary} 这种写法当场洗成 <已脱敏>
        var logEvent = Event("会话 {Summary} 已同步", new LogEventProperty("Summary", new ScalarValue(Summary)));
        new PrivacyEnricher().Enrich(logEvent, new OnlyNames());

        var written = Render(logEvent);
        Assert.DoesNotContain("BRAVO", written);
        Assert.Contains("已同步", written);
    }

    [Fact]
    public void OneEventIsOneLineEvenWhenAValueSpansLines()
    {
        var logEvent = Event("异常堆栈 {Type}", new LogEventProperty("Type", new ScalarValue("第一行\r\n第二行")));
        new PrivacyEnricher().Enrich(logEvent, new OnlyNames());

        Assert.DoesNotContain("\n", Render(logEvent).TrimEnd('\r', '\n'));
    }

    /// <summary>组装一条事件（不经 AppLog，因为这里要传 AppLog 压根不收的参数）。</summary>
    private static LogEvent Event(string template, params LogEventProperty[] properties) =>
        new(DateTimeOffset.Now, LogEventLevel.Information, null,
            new MessageTemplateParser().Parse(template), properties);

    /// <summary>富化 + 格式化走一遍，拿到的就是真正要落盘的那一行。</summary>
    private static string Render(LogEvent logEvent)
    {
        var buffer = new StringWriter();
        new PrivacyFormatter().Format(logEvent, buffer);
        return buffer.ToString();
    }

    private static string Read(string home) =>
        Directory.Exists(AppLog.DirectoryFor(home))
            ? string.Join("\n", Directory.GetFiles(AppLog.DirectoryFor(home), "pushbox-*.log")
                .SelectMany(File.ReadAllLines))
            : string.Empty;

    private static IEnumerable<string> Lines(string home) =>
        Directory.GetFiles(AppLog.DirectoryFor(home), "pushbox-*.log").SelectMany(File.ReadAllLines);

    /// <summary>富化器不用这个工厂（它只改写已有属性），但签名要一个实例，所以给一个最小的真实现而不是 null。</summary>
    private sealed class OnlyNames : ILogEventPropertyFactory
    {
        public LogEventProperty CreateProperty(string name, object? value, bool destructureObjects = false) =>
            new(name, new ScalarValue(value));
    }

    public void Dispose()
    {
        // 全局那本必须收掉句柄，否则下一条用例 Start 的是同一本、上一本的缓冲还没落盘
        AppLog.CloseAndFlush();
        foreach (var root in new[] { _dir }.Concat(_extraDirs))
        {
            try
            {
                if (Directory.Exists(root)) Directory.Delete(root, true);
            }
            catch (IOException)
            {
                // 临时目录删不掉不影响结论
            }
        }
    }
}
