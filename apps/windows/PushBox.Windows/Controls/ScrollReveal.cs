using System;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;

namespace PushBox.Windows.Controls;

/// <summary>
/// 微信 PC 那根滚动条：闲置时整个淡出，滚动起来或把指针搭上去时才浮现；
/// 滑块长度钉成固定 <see cref="ThumbPixels"/> 像素，不跟"可见/全部"的比例走——
/// 内容只比一屏多一点时按比例算会几乎占满整条轨道，观感上就是"太长了"。
/// 做法是反解 <see cref="ScrollBar.ViewportSize"/>：Track 用它除以 (它 + Maximum) 得滑块占比，
/// 而滑块的位置与拖动映射走的是 Value/Maximum，不受 ViewportSize 影响，所以拖动仍然跟手。
/// 淡出只动 <see cref="UIElement.Opacity"/>，命中测试留着：指针移到那条 6px 上它自己就出来了。
/// </summary>
public static class ScrollReveal
{
    private const double ThumbPixels = 56.0;

    public static readonly DependencyProperty AttachProperty = DependencyProperty.RegisterAttached(
        "Attach", typeof(bool), typeof(ScrollReveal), new PropertyMetadata(false, OnAttachChanged));

    public static void SetAttach(DependencyObject target, bool value) => target.SetValue(AttachProperty, value);

    public static bool GetAttach(DependencyObject target) => (bool)target.GetValue(AttachProperty);

    private static void OnAttachChanged(DependencyObject d, DependencyPropertyChangedEventArgs e)
    {
        if (d is not FrameworkElement element) return;
        if ((bool)e.NewValue) element.Loaded += OnLoaded;
        else element.Loaded -= OnLoaded;
    }

    private static void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement element) return;
        element.Loaded -= OnLoaded;

        var viewer = element as ScrollViewer ?? First<ScrollViewer>(element);
        if (viewer is null) return;
        var bars = new List<ScrollBar>();
        Collect(viewer, bars);
        if (bars.Count == 0) return;

        foreach (var bar in bars) bar.Opacity = 0;

        var lastActive = DateTime.UtcNow;
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
        timer.Tick += (_, _) =>
        {
            // 指针搭在滚动条上（含正在拖滑块）就不收：收了一半用户抓空了
            foreach (var bar in bars)
                if (bar.IsMouseOver)
                {
                    lastActive = DateTime.UtcNow;
                    return;
                }
            if (DateTime.UtcNow - lastActive < TimeSpan.FromSeconds(1.0)) return;
            foreach (var bar in bars) Fade(bar, 0);
            timer.Stop();
        };

        void Reveal()
        {
            lastActive = DateTime.UtcNow;
            foreach (var bar in bars)
                if (bar.IsVisible) Fade(bar, 1);
            if (!timer.IsEnabled) timer.Start();
        }

        viewer.ScrollChanged += (_, _) =>
        {
            foreach (var bar in bars) FixThumb(bar);
            Reveal();
        };
        viewer.SizeChanged += (_, _) =>
        {
            foreach (var bar in bars) FixThumb(bar);
        };
        foreach (var bar in bars)
        {
            bar.MouseEnter += (_, _) => Reveal();
            // 内容从"不溢出"长到"溢出"时滚动条才刚被 Auto 放出来，先让它露一次面
            bar.IsVisibleChanged += (_, _) =>
            {
                FixThumb(bar);
                Reveal();
            };
            bar.SizeChanged += (_, _) => FixThumb(bar);
        }

        // 首帧布局完才有 ActualHeight/Maximum，补一次定长
        element.Dispatcher.BeginInvoke(new Action(() =>
        {
            foreach (var bar in bars) FixThumb(bar);
        }));
    }

    private static void FixThumb(ScrollBar bar)
    {
        if (bar.Maximum <= 0) return;
        var track = bar.Orientation == Orientation.Vertical ? bar.ActualHeight : bar.ActualWidth;
        if (track <= 0) return;
        var fraction = ThumbPixels / track;
        if (fraction <= 0 || fraction >= 1) return;
        bar.ViewportSize = fraction * bar.Maximum / (1 - fraction);
    }

    private static void Fade(UIElement element, double to) =>
        element.BeginAnimation(UIElement.OpacityProperty,
            new DoubleAnimation(to, TimeSpan.FromMilliseconds(160)));

    private static T? First<T>(DependencyObject root) where T : DependencyObject
    {
        if (root is T hit) return hit;
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
        {
            var found = First<T>(VisualTreeHelper.GetChild(root, i));
            if (found is not null) return found;
        }

        return null;
    }

    private static void Collect(DependencyObject root, List<ScrollBar> into)
    {
        if (root is ScrollBar bar) into.Add(bar);
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(root); i++)
            Collect(VisualTreeHelper.GetChild(root, i), into);
    }
}
