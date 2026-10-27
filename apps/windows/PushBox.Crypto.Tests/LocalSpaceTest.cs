using System;
using System.Collections.Generic;
using System.IO;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Views;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// §7.3 退出空间：哪些文件归"这个空间"、删的顺序、以及删不掉时说什么。
/// 2026-10-22 那台开发机上留下了"身份没了、库还在"——旧实现把 File.Delete 留在窗口里，
/// 库还被引擎攥着，抛出的那一下被吞掉。这两条用例钉的正是那两个漏洞。
/// </summary>
public sealed class LocalSpaceTest : IDisposable
{
    private readonly string _dir;

    public LocalSpaceTest()
    {
        _dir = Path.Combine(Path.GetTempPath(), "pushbox-space-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_dir);
    }

    [Fact]
    public void PurgingTakesTheSpaceAndLeavesWhatBelongsToThisMachine()
    {
        var identity = Touch(Path.Combine(_dir, LocalSpace.IdentityFile));
        var database = Touch(Path.Combine(_dir, LocalSpace.DatabaseFile));
        var wal = Touch(Path.Combine(_dir, LocalSpace.DatabaseFile + "-wal"));
        var cached = Touch(Path.Combine(_dir, LocalSpace.MediaCacheDirectory, "ab", "cd.bin"));
        var avatar = Touch(Path.Combine(_dir, LocalSpace.AvatarDirectory, "x.png"));
        var toast = Touch(Path.Combine(_dir, LocalSpace.ToastAvatarDirectory, "y.png"));

        // 这三样描述的是"这台电脑"而不是"这个空间"：退出再回来，通知开关、几何、已下载的安装包都该还在
        var settings = Touch(Path.Combine(_dir, "settings.json"));
        var log = Touch(Path.Combine(_dir, "logs", "pushbox-20261027.log"));
        var installer = Touch(Path.Combine(_dir, "updates", "PushBox-Setup.exe"));

        var left = LocalSpace.Purge(_dir);

        Assert.Empty(left);
        foreach (var gone in new[] { identity, database, wal, cached, avatar, toast })
            Assert.False(File.Exists(gone), $"{gone} 该被删掉");
        foreach (var kept in new[] { settings, log, installer })
            Assert.True(File.Exists(kept), $"{kept} 不该被删掉");
        Assert.False(Directory.Exists(Path.Combine(_dir, LocalSpace.MediaCacheDirectory)));
    }

    [Fact]
    public void PurgingAnEmptyFolderSucceedsAndSaysNothingWasLeft()
    {
        Assert.Empty(LocalSpace.Purge(_dir));
        Assert.Empty(LocalSpace.Purge(_dir));
    }

    [Fact]
    public void AFileStillHeldBySomethingIsReportedInsteadOfSilentlyKept()
    {
        var database = Touch(Path.Combine(_dir, LocalSpace.DatabaseFile));
        var identity = Touch(Path.Combine(_dir, LocalSpace.IdentityFile));

        using (new FileStream(database, FileMode.Open, FileAccess.ReadWrite, FileShare.None))
        {
            var left = LocalSpace.Purge(_dir);

            // 删不掉的那一个要原样交回去（界面因此能说"还有文件被占用"），其余的照样清掉
            Assert.Equal(new[] { database }, left);
            Assert.True(File.Exists(database));
            Assert.False(File.Exists(identity));
        }

        Assert.Empty(LocalSpace.Purge(_dir));
        Assert.False(File.Exists(database));
    }

    /// <summary>
    /// 顺序是这一步的全部内容：停同步 → 关窗 → 关库 → 删文件。关窗也不能排在关库之后——
    /// 设置窗关掉时会顺手重画主窗，库先关了那一下就是 ObjectDisposedException。
    /// </summary>
    [Fact]
    public async Task StopCloseClosePurgeHappenInThatOrder()
    {
        var order = new List<string>();
        var paths = new List<string> { @"C:\Users\x\AppData\Local\PushBox\pushbox.db" };
        var reset = new SpaceReset(
            stopSync: () =>
            {
                order.Add("stop");
                return Task.CompletedTask;
            },
            closeWindows: () => order.Add("windows"),
            closeDatabase: () => order.Add("database"),
            purge: () =>
            {
                order.Add("purge");
                return paths;
            });

        var left = await reset.RunAsync();

        Assert.Equal(new[] { "stop", "windows", "database", "purge" }, order);
        Assert.Same(paths, left);
    }

    /// <summary>停同步是异步的（要等循环真的收工）：删文件必须等它回来之后才发生。</summary>
    [Fact]
    public async Task PurgeWaitsForTheSyncLoopToLetGo()
    {
        var gate = new TaskCompletionSource();
        var order = new List<string>();
        var reset = new SpaceReset(
            stopSync: async () =>
            {
                await gate.Task;
                order.Add("stop");
            },
            closeWindows: () => order.Add("windows"),
            closeDatabase: () => order.Add("database"),
            purge: () =>
            {
                order.Add("purge");
                return Array.Empty<string>();
            });

        var running = reset.RunAsync();
        await Task.Delay(20);
        Assert.Empty(order);

        gate.SetResult();
        await running;
        Assert.Equal(new[] { "stop", "windows", "database", "purge" }, order);
    }

    private static string Touch(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, "x");
        return path;
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_dir, true);
        }
        catch (Exception)
        {
            // 临时目录清不掉不影响结论
        }
    }
}
