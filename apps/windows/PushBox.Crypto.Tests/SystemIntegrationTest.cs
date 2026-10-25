using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows;
using PushBox.Windows.Data;
using PushBox.Windows.Integration;
using PushBox.Windows.Notify;
using PushBox.Windows.Settings;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W13 剩下的那几层薄皮的判定部分：开机自启该写什么值、单实例的名字怎么派生、头像往哪儿落、
/// 托盘那一行文案怎么拼，以及"通知这条路整个不可用时不许把异常送回同步循环"。
///
/// 真正只能在真机上验的三件事（注册表写进去了、托盘图标出来了、toast 弹出来了）走的是
/// <c>PushBox.Windows.exe --notify-smoke</c>，实测结果记在 §8.2。
/// </summary>
public sealed class SystemIntegrationTest : IDisposable
{
    private static readonly byte[] Png =
    {
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01
    };

    private static readonly byte[] Jpeg =
        { 0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };

    private readonly string _dir = Path.Combine(Path.GetTempPath(),
        "pushbox-notify-test-" + Guid.NewGuid().ToString("N"));

    // ---- §8.1 开机自启：只有判定这一半能进 xunit ------------------------------------------------

    [Fact]
    public void AutoStartQuotesTheWholePathAndTurnsOffMeansNoValue()
    {
        var exe = @"C:\Program Files\PushBox\PushBox.Windows.exe";

        // 不加引号，Run 会把带空格的路径拆成"可执行文件 + 参数"，开机时静默启动失败
        Assert.Equal("\"" + exe + "\" --tray", AutoStart.CommandFor(exe));
        Assert.Equal("\"" + exe + "\" --tray", AutoStart.DesiredValue(true, exe));
        // 关掉是"这一项不该存在"，而不是"写一个空值"：任务管理器启动页里会留着一条空条目
        Assert.Null(AutoStart.DesiredValue(false, exe));
    }

    [Fact]
    public void AutoStartStartsInTheTrayInsteadOfOpeningTheWindow()
    {
        var exe = @"D:\Apps\PushBox\PushBox.Windows.exe";

        // 自启是"帮我收消息"，不是"开机把一扇窗摆到脸上"：那一笔注册表必须带开关
        Assert.EndsWith(" " + AutoStart.BackgroundArgument, AutoStart.CommandFor(exe));
        Assert.True(App.StartInBackground(new[] { exe, AutoStart.BackgroundArgument }));
        // 值对不上就是"该重写"：老版本写的是不带开关的光路径，用户从 1.1.0 升上来不能继续开会话窗
        Assert.Equal(AutoStartDrift.MovedInstall,
            AutoStart.DriftOf("\"D:\\Apps\\PushBox\\PushBox.Windows.exe\"", AutoStart.CommandFor(exe)));
        // 大小写不敏感是因为那一笔可能是手写的
        Assert.True(App.StartInBackground(new[] { AutoStart.BackgroundArgument.ToUpperInvariant() }));
        // 用户自己双击 exe、点通知、体检开关都不该被这个开关误判
        Assert.False(App.StartInBackground(new[] { exe }));
        Assert.False(App.StartInBackground(new[] { App.SmokeSwitch, "out.txt" }));
        Assert.False(App.StartInBackground(null));
    }

    [Fact]
    public void AutoStartLeavesTheRegistryAloneWhenTheValueAlreadyMatches()
    {
        var exe = @"D:\Apps\PushBox\PushBox.Windows.exe";
        var wanted = AutoStart.CommandFor(exe);

        Assert.False(AutoStart.NeedsWrite(wanted, wanted));
        Assert.True(AutoStart.NeedsWrite(null, wanted)); // 从没开过
        Assert.True(AutoStart.NeedsWrite(wanted, null)); // 从开变成关，要删
        // 换了一份安装（旧值指着已经不存在的 bin\Debug），必须重写而不是"有值就算开"
        Assert.True(AutoStart.NeedsWrite("\"D:\\Old\\PushBox.Windows.exe\"", wanted));
    }

    [Fact]
    public void TheStartupEntryIsNamedForTheUserNotTheAssembly()
    {
        Assert.Equal("PushBox", AutoStart.ValueName);
        Assert.Equal(@"Software\Microsoft\Windows\CurrentVersion\Run", AutoStart.RunKeyPath);
        // HKCU 而不是 HKLM：不需要管理员，且用户自己就能在任务管理器里看到并关掉
        Assert.False(AutoStart.RunKeyPath.StartsWith("HKEY"));
    }

    [Fact]
    public void ADriftedAutoStartEntryIsToldApartFromADeletedOne()
    {
        var exe = @"D:\Apps\PushBox\PushBox.Windows.exe";
        var current = AutoStart.CommandFor(exe);

        // 值一模一样就别动注册表：重写同值会让启动页里亮一次"程序已更新"
        Assert.Equal(AutoStartDrift.AlreadyRight, AutoStart.DriftOf(current, current));
        // 有条目但指着别的那一份构建（bin\Debug 装到 Program Files 之后留下的）：跟着搬，
        // 否则开机时启动页里挂的是一条永远起不来的旧路径
        Assert.Equal(AutoStartDrift.MovedInstall,
            AutoStart.DriftOf("\"D:\\Old\\PushBox.Windows.exe\"", current));
        // 条目不见了就是用户在任务管理器里自己关的：顺着他把设置改成关，别再塞回去
        Assert.Equal(AutoStartDrift.UserCleared, AutoStart.DriftOf(null, current));
        // 空字符串是"有条目但内容是空的"（被别的清理工具写坏过），按换了地方处理而不是按关了处理
        Assert.Equal(AutoStartDrift.MovedInstall, AutoStart.DriftOf(string.Empty, current));
    }

    // ---- §8.1 注册：两处 HKCU 的形状 -----------------------------------------------------------

    [Fact]
    public void TheRegistrationKeysAreTheOnesAnUnpackagedAppNeeds()
    {
        Assert.Equal("PushBox.Windows", ToastRegistration.Aumid);
        Assert.Equal(@"Software\Classes\AppUserModelId\PushBox.Windows", ToastRegistration.AumidKeyPath);
        Assert.Equal(@"Software\Classes\pushbox", ToastRegistration.SchemeKeyPath);
        Assert.Equal(@"Software\Classes\pushbox\shell\open\command", ToastRegistration.CommandKeyPath);
    }

    // ---- 单实例：名字派生与真管道往返 ----------------------------------------------------------

    /// <summary>
    /// 用另一个线程去抢那把锁，代表"另一个进程"。
    ///
    /// Win32 的命名互斥体是<b>按线程</b>递归的：主实例在测试线程上持有它，同一个线程再抢一次一定成功，
    /// 于是同线程写出来的"第二个实例"永远抢不到东西——这条断言会假绿，而不是假红。
    /// 生产上点通知起来的确实是另一个进程、另一条线程，所以测试必须换线程来演。
    /// </summary>
    private static bool TryBecomePrimaryElsewhere(InstanceHandoff handoff)
    {
        var acquired = false;
        var thread = new Thread(() => acquired = handoff.TryBecomePrimary()) { IsBackground = true };
        thread.Start();
        Assert.True(thread.Join(TimeSpan.FromSeconds(10)), "抢实例锁没有按时返回");
        return acquired;
    }

    [Fact]
    public void TheInstanceNamesComeFromTheInstallPath()
    {
        // 同一份 exe 的两种写法（大小写、斜杠方向）算同一个实例；换目录安装算另一个实例
        Assert.Equal(InstanceHandoff.MutexNameFor(@"C:\Apps\PushBox\a.exe"),
            InstanceHandoff.MutexNameFor(@"c:\apps\pushbox\A.EXE"));
        Assert.Equal(InstanceHandoff.PipeNameFor(@"C:\Apps\PushBox\a.exe"),
            InstanceHandoff.PipeNameFor(@"C:/Apps/PushBox/a.exe"));
        Assert.NotEqual(InstanceHandoff.MutexNameFor(@"C:\Apps\PushBox\a.exe"),
            InstanceHandoff.MutexNameFor(@"D:\Build\Debug\a.exe"));
        // 这两个名字会出现在任务管理器和管道列表里，长到看不清就等于没有
        Assert.True(InstanceHandoff.PipeNameFor(@"C:\Apps\PushBox\a.exe").Length <= 20);
    }

    [Fact]
    public void ASecondInstanceHandsTheClickedTargetToTheFirstOne()
    {
        // 这条就是"点通知不该多出一个托盘图标、一条长连接和一个开同一份库的写者"的实现证据
        var exe = Path.Combine(_dir, "PushBox.Windows.exe");
        using var primary = new InstanceHandoff(exe);
        Assert.True(primary.TryBecomePrimary());
        Assert.True(primary.IsPrimary);

        var received = new ConcurrentQueue<string>();
        primary.StartListening(received.Enqueue);

        using var second = new InstanceHandoff(exe);
        Assert.False(TryBecomePrimaryElsewhere(second)); // 抢不到锁：这次是"点通知"那一下
        Assert.True(second.Send(ToastProtocol.OpenUri("83271504", "grafana")));

        // 等的时候别把队列抽干：SpinUntil 的条件用 TryDequeue 会把那条转发读走，后面再 Single() 就是空的
        Assert.True(SpinWait.SpinUntil(() => received.Count > 0, 5000), "5 秒内主实例没收到转发");
        var target = ToastProtocol.Parse(received.Single())!;
        Assert.Equal("83271504", target.ChannelNo);
    }

    [Fact]
    public async Task HandoffBeforeThePrimaryIsListeningStillLands()
    {
        // 真实顺序恰恰是这样：shell 起第二个进程时，主实例的管道服务可能还没就位。
        // Send 里那一次重试等待就是点通知到打开会话之间唯一的延迟，不能丢参数。
        var exe = Path.Combine(_dir, "late.exe");
        using var primary = new InstanceHandoff(exe);
        primary.TryBecomePrimary();

        var received = new ConcurrentQueue<string>();
        using var second = new InstanceHandoff(exe);
        Assert.False(TryBecomePrimaryElsewhere(second));

        var send = Task.Run(() => second.Send(ToastProtocol.ReadUri("c", "s")));
        Thread.Sleep(120); // 让 Send 先进那次重试
        primary.StartListening(received.Enqueue);

        Assert.True(await send);
        Assert.True(SpinWait.SpinUntil(() => received.Count > 0, 5000), "主实例起晚了这一次转发不该丢");
        Assert.Equal(ToastTargetKind.MarkRead, ToastProtocol.Parse(received.Single())!.Kind);
    }

    [Fact]
    public void OnlyThePrimaryMayListenAndAShutDownHandoffStillDisposes()
    {
        var exe = Path.Combine(_dir, "solo.exe");
        using var follower = new InstanceHandoff(exe);
        Assert.False(follower.IsPrimary);
        Assert.Throws<InvalidOperationException>(() => follower.StartListening(_ => { }));

        // 没抢到锁的那个实例的 Dispose 不该去 release 别人持有的 mutex
        var primary = new InstanceHandoff(exe);
        primary.TryBecomePrimary();
        primary.Dispose();
        primary.Dispose(); // 收工路径可能被走两遍（OnExit + 显式 Close）

        // 主实例退出后，下一个进程重新成为主实例
        using var next = new InstanceHandoff(exe);
        Assert.True(next.TryBecomePrimary());
    }

    [Fact]
    public void ACallbackThatThrowsDoesNotKillThePipeline()
    {
        // 回调里炸了（那一瞬间窗口正在关）之后，下一条通知还要能进来
        var exe = Path.Combine(_dir, "throwing.exe");
        using var primary = new InstanceHandoff(exe);
        primary.TryBecomePrimary();

        var secondCall = new ManualResetEventSlim();
        var calls = 0;
        primary.StartListening(_ =>
        {
            if (Interlocked.Increment(ref calls) == 1) throw new InvalidOperationException("窗口正在关");
            secondCall.Set();
        });

        using var follower = new InstanceHandoff(exe);
        Assert.False(TryBecomePrimaryElsewhere(follower));
        Assert.True(follower.Send(ToastProtocol.OpenUri("a", "b")));
        // 第一条把回调炸了；等它真的进过回调，才能证明"活下来的那条服务循环"接住了第二条
        Assert.True(SpinWait.SpinUntil(() => Volatile.Read(ref calls) >= 1, 6000), "第一条没进到回调");
        Assert.True(follower.Send(ToastProtocol.OpenUri("a", "c")));
        Assert.True(secondCall.Wait(6000), "第二条通知没能进到回调");
    }

    // ---- 托盘文案 ---------------------------------------------------------------------------

    [Theory]
    [InlineData(null, 0, "PushBox")]
    [InlineData("", 0, "PushBox")]
    [InlineData("已连接", 0, "PushBox · 已连接")]
    [InlineData(null, 3, "PushBox · 3 条未读")]
    [InlineData("重连中", 12, "PushBox · 12 条未读 · 重连中")]
    public void TooltipPutsTheUnreadCountFirst(string? state, int unread, string expected)
    {
        Assert.Equal(expected, TrayIcon.TooltipFor(state, unread));
    }

    // ---- §8.1 头像落盘：W10 那条"只进内存"的例外 ----------------------------------------------

    [Fact]
    public void TheAvatarFolderSitsNextToTheDatabase()
    {
        Assert.Equal(Path.Combine(IdentityStore.DefaultDirectory, "toast-avatar"),
            ToastAvatar.DirectoryOf());
    }

    [Fact]
    public void StagedAvatarsAreNamedForTheSenderAndSniffedByMagic()
    {
        var first = ToastAvatar.Stage(_dir, "ab12cd34", Png);
        Assert.NotNull(first);
        Assert.Equal(Path.Combine(_dir, "toast-avatar", "avatar-ab12cd34.png"), first);

        // 缓存里没有 Content-Type（§4.5 的 media_cache 只存字节），所以按魔数认
        var byMagic = ToastAvatar.Stage(_dir, "jpeg-token", Jpeg);
        Assert.EndsWith("avatar-jpeg-token.jpg", byMagic);
    }

    [Theory]
    [InlineData("image/webp")]
    [InlineData("image/svg+xml")]
    [InlineData("application/pdf")]
    public void AnythingTheToastRendererCannotDrawIsNoAvatarRatherThanAFailure(string mime)
    {
        // 认不出格式就当没头像：一条没图的通知照样发得出去，而一张渲染器拒绝的图会让整条通知消失。
        // webp 在 §4.5 的抓取白名单里是允许的，所以它一定会走到这一条而不是服务端拒掉
        Assert.Null(ToastAvatar.Stage(_dir, "some-token", Png, mime));
        AssertNoStagedFiles();
    }

    [Fact]
    public void BytesThatAreNotAnImageAtAllAreDropped()
    {
        // mime 缺失时靠魔数，魔数也不认就是"这字节不是图"，落盘没有意义
        var notAnImage = Enumerable.Repeat((byte)0x42, 24).ToArray();
        Assert.Null(ToastAvatar.Stage(_dir, "t", notAnImage));
        Assert.Null(ToastAvatar.Stage(_dir, "t", notAnImage, null));
        AssertNoStagedFiles();
    }

    [Fact]
    public void EmptyBytesNeverBecomeAnEmptyFile()
    {
        Assert.Null(ToastAvatar.Stage(_dir, "t", Array.Empty<byte>()));
        Assert.Null(ToastAvatar.Stage(_dir, "t", null));
        AssertNoStagedFiles();
    }

    [Fact]
    public void AChangedAvatarOverwritesTheSameSlotInsteadOfAccumulating()
    {
        // 一个人固定一个文件名 ⇒ 换头像不会在磁盘上攒历史，这正是 W10 不想落盘的那件事，
        // 落盘既然不可避免，就把代价收在这一个文件上
        var before = ToastAvatar.Stage(_dir, "same-token", Png)!;
        var after = ToastAvatar.Stage(_dir, "same-token", Png)!;
        Assert.Equal(before, after);
        Assert.Single(Directory.EnumerateFiles(Path.Combine(_dir, "toast-avatar")));
    }

    [Fact]
    public void AFormatSwitchLeavesOnlyTheNewOne()
    {
        ToastAvatar.Stage(_dir, "switching", Png, "image/png");
        var now = ToastAvatar.Stage(_dir, "switching", Jpeg, "image/jpeg")!;

        var files = Staged();
        Assert.Equal(now, Assert.Single(files));
    }

    [Fact]
    public void TheNinthAvatarPushesOutTheOldestAndNeverTheCurrentOne()
    {
        // 每张落盘后把它的最后写入时间往前挪一分钟：LRU 读的就是这个时间，
        // 靠 Thread.Sleep 去撞文件系统的时间戳粒度只会得到一个偶绿偶红的用例
        var clock = new DateTime(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);
        for (var i = 1; i <= 9; i++)
        {
            var path = ToastAvatar.Stage(_dir, $"token-{i}", Png)!;
            File.SetLastWriteTimeUtc(path, clock.AddMinutes(i));

            var all = Staged();
            Assert.True(all.Count <= ToastAvatar.FileCap, $"第 {i} 张之后有 {all.Count} 个文件");
            Assert.Contains(path, all); // 刚用的这张不许被自己挤掉
        }

        Assert.Equal(ToastAvatar.FileCap, Staged().Count);
        Assert.DoesNotContain(Staged(), f => Path.GetFileName(f) == "avatar-token-1.png");
        Assert.Contains(Staged(), f => Path.GetFileName(f) == "avatar-token-9.png");
    }

    [Fact]
    public void FreeTextTokensCannotEscapeTheFolder()
    {
        // token 今天是 sha256 的十六进制，但它是自由文本：万一换成带 / + = 的 base64，
        // 直接拼就会写出目录外或读不回的文件
        var path = ToastAvatar.Stage(_dir, "../../evil\\name", Png)!;
        Assert.Equal(Path.Combine(_dir, "toast-avatar"), Path.GetDirectoryName(path));
        Assert.StartsWith("avatar-", Path.GetFileName(path));
        Assert.DoesNotContain("..", Path.GetFileName(path));
        Assert.DoesNotContain('\\', Path.GetFileName(path));

        Assert.Equal(64 + ToastAvatar.FilePrefix.Length,
            ToastAvatar.FileName(new string('x', 200)).Length);
    }

    [Fact]
    public void ClearEmptiesTheFolderAndToleratesAnAbsentOne()
    {
        ToastAvatar.Clear(Path.Combine(_dir, "never-written")); // 目录不存在不该抛
        ToastAvatar.Stage(_dir, "token-a", Png);
        ToastAvatar.Stage(_dir, "token-b", Png);

        ToastAvatar.Clear(_dir);
        Assert.Empty(Directory.EnumerateFiles(Path.Combine(_dir, "toast-avatar")));
    }

    // ---- §14 的契约：通知这条路不许把异常送回同步循环 ------------------------------------------

    [Fact]
    public void ASuppressedDecisionNeverReachesTheSystem()
    {
        var shown = new ConcurrentQueue<ToastPlan>();
        var notifier = Notifier(new AlertPolicy(() => ClientSettings.Defaults with { NotificationsEnabled = false },
            () => DateTimeOffset.Now), shown);

        notifier.Notify(Support.Message(level: "error"));
        Assert.Equal(1, notifier.Suppressed);
        Assert.Equal(0, notifier.Delivered);
        Assert.Empty(shown);
    }

    [Fact]
    public void AFrameIsDeliveredOrFallBackToTheBalloonButNeverBoth()
    {
        // 这台机器上 toast 可能因为 AUMID 没注册、用户在系统设置里关了本应用而发不出去。
        // 两种结果都算通过，唯一不许发生的是"两条路都没走"或"两条都走了一遍"
        var shown = new ConcurrentQueue<ToastPlan>();
        var notifier = Notifier(new AlertPolicy(() => ClientSettings.Defaults, () => DateTimeOffset.Now), shown);

        var e = Record.Exception(() => notifier.Notify(Support.Message(level: "warning")));
        Assert.Null(e);
        Assert.Equal(1, notifier.Delivered + shown.Count);
    }

    [Fact]
    public void FailuresAreRecordedAsATypeNameAndNeverAsAMessage()
    {
        // 异常的 Message 里常有请求 URL、参数乃至正文片段，§14 不许它进任何留痕；这里只存类型名
        var shown = new ConcurrentQueue<ToastPlan>();
        var notifier = Notifier(new AlertPolicy(() => ClientSettings.Defaults, () => DateTimeOffset.Now), shown);
        notifier.Notify(Support.Message(level: "warning", content: "正文 seq=18481 磁盘告警"));

        if (notifier.LastFailure != null)
        {
            Assert.Equal(notifier.LastFailure.Trim(), notifier.LastFailure);
            Assert.DoesNotContain(" ", notifier.LastFailure);
            Assert.DoesNotContain(":", notifier.LastFailure);
            Assert.DoesNotContain("磁盘", notifier.LastFailure);
        }
        else Assert.NotEqual(0, notifier.Delivered);
    }

    [Fact]
    public void ForgettingAConversationRestartsItsCount()
    {
        var tally = new ConversationTally();
        var notifier = new ToastNotifier(
            new AlertPolicy(() => ClientSettings.Defaults, () => DateTimeOffset.Now), tally);

        tally.Bump("83271504/grafana");
        notifier.Forget("83271504/grafana");
        Assert.Equal(0, tally.Count("83271504/grafana"));
        // 通知中心那一组收不收得掉看系统，但 Forget 不许因此把异常送出来
        Assert.Null(Record.Exception(() => notifier.Forget("从没通知过的会话")));
    }

    [Fact]
    public void TheSettingProbeReadsWhatTheSystemThinksOfThisApplication()
    {
        var notifier = new ToastNotifier(
            new AlertPolicy(() => ClientSettings.Defaults, () => DateTimeOffset.Now), new ConversationTally());

        // 体检输出读的是这一句，而不是"我刚才那句 Show 没抛异常"：Show 不抛但被系统丢掉是另一种故障
        var setting = notifier.SettingProbe();
        Assert.False(string.IsNullOrWhiteSpace(setting));
        Assert.DoesNotContain("磁盘", setting);
    }

    private ToastNotifier Notifier(AlertPolicy policy, ConcurrentQueue<ToastPlan> fallback) =>
        new(policy, new ConversationTally(), fallback: fallback.Enqueue);

    private List<string> Staged() =>
        Directory.EnumerateFiles(Path.Combine(_dir, "toast-avatar")).ToList();

    private void AssertNoStagedFiles()
    {
        var dir = Path.Combine(_dir, "toast-avatar");
        Assert.False(Directory.Exists(dir) && Directory.EnumerateFiles(dir).Any());
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_dir)) Directory.Delete(_dir, true);
        }
        catch (IOException)
        {
            // 系统进程还在读那张图时目录删不掉，留个临时目录不影响结论
        }
    }
}
