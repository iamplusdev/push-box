using System;
using System.Collections.Generic;
using System.Threading.Tasks;

namespace PushBox.Windows.Views;

/// <summary>
/// §7.3 退出空间的四步，<b>顺序就是这个类存在的理由</b>：停同步 → 关窗 → 关库 → 删文件。
/// 旧实现是窗口里点完就 <c>File.Delete</c>：库还被引擎和窗口攥着，抛出的那一下又被 catch 吞掉，
/// 于是机器上留下"身份没了、库还在"的半退出（2026-10-22 那台开发机就是这么来的）。
/// 关窗也必须排在关库之前：设置窗的 Closed 里会顺手重画主窗，库先关了那一下就是 ObjectDisposedException。
/// 四个步骤都从装配层交进来，装配起来的是真引擎/真库，用例交四个记次序的委托就能断到"谁先谁后"。
/// </summary>
public sealed class SpaceReset
{
    private readonly Func<Task> _stopSync;
    private readonly Action _closeWindows;
    private readonly Action _closeDatabase;
    private readonly Func<IReadOnlyList<string>> _purge;

    public SpaceReset(Func<Task> stopSync, Action closeWindows, Action closeDatabase,
        Func<IReadOnlyList<string>> purge)
    {
        _stopSync = stopSync;
        _closeWindows = closeWindows;
        _closeDatabase = closeDatabase;
        _purge = purge;
    }

    /// <summary>返回没删掉的路径（空 = 清干净了）。删不掉的必须回到界面上说，不能当成功。</summary>
    public async Task<IReadOnlyList<string>> RunAsync()
    {
        await _stopSync();
        _closeWindows();
        _closeDatabase();
        return _purge();
    }
}
