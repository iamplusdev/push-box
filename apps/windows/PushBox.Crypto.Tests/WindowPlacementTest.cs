using System.Windows;
using PushBox.Windows.Views;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// 恢复窗口几何的三条判据。这一页只断纯函数 <see cref="WindowPlacement.Restore"/>：
/// "关窗时交出什么"那半截在 <see cref="MainWindowTest"/> 里建真窗口断。
///
/// 用的是一台 1366×768@150% 的笔记本（工作区约 910×512）配一块 1920×1040 的虚拟屏——
/// 这正是当初把主窗推到屏幕外的那台机器，写在用例里比读当前机器的 <c>SystemParameters</c> 稳。
/// </summary>
public sealed class WindowPlacementTest
{
    private static readonly Rect Work = new(0, 0, 910, 512);
    private static readonly Rect Screen = new(0, 0, 1920, 1040);

    private static WindowGeometry Saved(double left, double top, double width, double height) =>
        new(left, top, width, height, 320, false);

    [Fact]
    public void ARectThatStillFitsComesBackUntouched()
    {
        var saved = Saved(100, 40, 800, 440);

        var placed = WindowPlacement.Restore(saved, Work, Screen, 440, 420);

        // 用户怎么摆的还是怎么摆：恢复不是"重新居中"
        Assert.Equal(saved, placed);
    }

    [Fact]
    public void ASizeLargerThanTheWorkAreaIsCappedTheSameWayTheFirstLaunchIs()
    {
        // 上次在 4K 屏上拉到 1600×900，这次换到 910×512 的可见区：按同一套 0.9 封顶
        var placed = WindowPlacement.Restore(Saved(0, 0, 1600, 900), Work, Screen, 440, 420);

        Assert.Equal(MainModel.FitToWorkArea(1600, Work.Width), placed.Width);
        Assert.Equal(MainModel.FitToWorkArea(900, Work.Height), placed.Height);
    }

    [Fact]
    public void ARectOnAMonitorThatIsGoneComesBackToTheMiddleOfTheWorkArea()
    {
        // 那块屏被拔了：保存的 (4000,3000) 落在虚拟屏外，窗口开出来会只看得见任务栏
        var placed = WindowPlacement.Restore(Saved(4000, 3000, 900, 600), Work, Screen, 440, 420);

        var centerX = placed.Left + placed.Width / 2;
        var centerY = placed.Top + placed.Height / 2;
        Assert.InRange(centerX, Work.Left + Work.Width * 0.4, Work.Left + Work.Width * 0.6);
        Assert.InRange(centerY, Work.Top + Work.Height * 0.4, Work.Top + Work.Height * 0.6);
    }

    [Fact]
    public void ARectHangingOffTheEdgeIsPulledBackOnScreen()
    {
        // 还看得见就不居中，但整块矩形要回到虚拟屏里（左边露 96 像素以上就算"看得见"）
        var placed = WindowPlacement.Restore(Saved(-300, 100, 800, 400), Work, Screen, 440, 420);

        Assert.Equal(Screen.Left, placed.Left);
        Assert.Equal(100, placed.Top);
    }

    [Fact]
    public void BelowTheMinimumSizeTheWindowGrowsToTheMinimum()
    {
        // 上一版允许拖到很小：恢复时不能让标题栏被压成一条线
        var placed = WindowPlacement.Restore(Saved(100, 100, 200, 100), Work, Screen, 440, 420);

        Assert.Equal(440, placed.Width);
        Assert.Equal(420, placed.Height);
    }

    [Fact]
    public void AnUnreadableDesktopLeavesTheSavedRectAlone()
    {
        // 工作区读不到（宽高 ≤ 0）时不要硬算：原样交回去，由窗口那层自己兜底
        var saved = Saved(4000, 3000, 900, 600);

        Assert.Equal(saved, WindowPlacement.Restore(saved, new Rect(0, 0, 0, 0), Screen, 440, 420));
    }

    [Fact]
    public void TheListWidthAndMaximizedFlagSurviveTheRestore()
    {
        // 这两个不是几何计算的对象，但要是被 with 表达式顺手抹掉，恢复出来的窗就是默认列宽
        var saved = new WindowGeometry(2000, 2000, 1200, 800, 360, true);

        var placed = WindowPlacement.Restore(saved, Work, Screen, 440, 420);

        Assert.Equal(360, placed.ListWidth);
        Assert.True(placed.Maximized);
    }
}
