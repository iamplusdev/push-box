using System;
using System.Windows;

namespace PushBox.Windows.Views;

/// <summary>
/// 主窗的几何：常态矩形 + 会话列表列宽 + 是否最大化。落盘在 <c>settings.json</c>，
/// 恢复时统一过 <see cref="WindowPlacement.Restore"/>；列宽不在这个纯函数里（它的合法区间是
/// <see cref="MainModel.ListMinWidth"/>/<see cref="MainModel.ListMaxWidth"/>，摆列时自己夹）。
/// </summary>
public readonly record struct WindowGeometry(double Left, double Top, double Width, double Height,
    double ListWidth, bool Maximized);

/// <summary>
/// 恢复窗口那一步。与 <see cref="MainModel.FitToWorkArea"/> 分工：那个只管"尺寸别超过工作区"，
/// 这里还要管"位置还在不在屏上"——上一块显示器被拔掉、远程桌面换了分辨率，保存的 Left/Top
/// 都会落到虚拟屏外，那样的窗口开出来看得见任务栏、找不着标题栏。
/// </summary>
public static class WindowPlacement
{
    /// <summary>至少要有这么宽、这么高露在虚拟屏里，否则当作"那块屏没了"，回到工作区正中。</summary>
    public const double MinVisible = 96;

    /// <param name="workArea">主屏工作区（DIP）。宽高 ≤ 0 表示读不到，原样返回 saved。</param>
    /// <param name="virtualScreen">所有显示器合成的矩形。读不到时退回 workArea（单屏就够）。</param>
    public static WindowGeometry Restore(WindowGeometry saved, Rect workArea, Rect virtualScreen,
        double minWidth, double minHeight)
    {
        if (workArea.Width <= 0 || workArea.Height <= 0) return saved;
        var screen = virtualScreen.Width > 0 && virtualScreen.Height > 0 ? virtualScreen : workArea;

        // 尺寸先按主屏封顶：上次在 4K 上拉到 1600 宽，换到 1366 的笔记本上不该比屏幕还宽
        var width = Math.Max(minWidth, MainModel.FitToWorkArea(saved.Width, workArea.Width));
        var height = Math.Max(minHeight, MainModel.FitToWorkArea(saved.Height, workArea.Height));

        var onScreen = saved.Left < screen.Right - MinVisible
                       && saved.Left + width > screen.Left + MinVisible
                       && saved.Top < screen.Bottom - MinVisible
                       && saved.Top + height > screen.Top + MinVisible;

        double left, top;
        if (onScreen)
        {
            // 还看得见就照用户的摆法来，只把整块矩形拉回虚拟屏内（比如贴着右边缘露了半个身位）
            left = Math.Clamp(saved.Left, screen.Left, Math.Max(screen.Left, screen.Right - width));
            top = Math.Clamp(saved.Top, screen.Top, Math.Max(screen.Top, screen.Bottom - height));
        }
        else
        {
            left = workArea.Left + Math.Round((workArea.Width - width) / 2);
            top = workArea.Top + Math.Round((workArea.Height - height) / 2);
        }

        return saved with { Left = left, Top = top, Width = width, Height = height };
    }
}
