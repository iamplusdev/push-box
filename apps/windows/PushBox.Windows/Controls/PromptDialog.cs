using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace PushBox.Windows.Controls;

/// <summary>
/// 要一个字进来的那一句（§7.3 重命名、§14 白名单加 sender_id、频道改名）。
/// 主窗口和设置窗共用这一个：两处问的是同一件事，各写一份只会让两处渐渐长得不一样。
///
/// 它不是 XAML 里的一个 Window：三行控件、进来就能写完，为它建一对 xaml 文件反而要多一份资源引用。
/// </summary>
public static class PromptDialog
{
    /// <summary>返回 null 是"取消"（含按右上角的 ×），空串是有意义的答复（"把这一项清掉"）。</summary>
    public static string? Show(Window? owner, string title, string current)
    {
        var box = new TextBox
        {
            Text = current, FontSize = 13, MinWidth = 320, Margin = new Thickness(0, 8, 0, 12)
        };
        var ok = new Button { Content = "确定", Width = 72, IsDefault = true, Margin = new Thickness(0, 0, 8, 0) };
        var cancel = new Button { Content = "取消", Width = 72, IsCancel = true };
        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right
        };
        buttons.Children.Add(ok);
        buttons.Children.Add(cancel);

        var content = new StackPanel { Margin = new Thickness(16) };
        content.Children.Add(box);
        content.Children.Add(buttons);

        var dialog = new Window
        {
            Title = title,
            Owner = owner,
            SizeToContent = SizeToContent.WidthAndHeight,
            ResizeMode = ResizeMode.NoResize,
            WindowStartupLocation = owner == null
                ? WindowStartupLocation.CenterScreen
                : WindowStartupLocation.CenterOwner,
            Background = BrushOf(owner, "Bg.Elevated"),
            Foreground = BrushOf(owner, "Fg.Primary"),
            Content = content
        };
        box.Focus();
        box.SelectAll();
        ok.Click += (_, _) => { dialog.DialogResult = true; };
        return dialog.ShowDialog() == true ? box.Text : null;
    }

    /// <summary>token 字典挂在应用级资源上（§9.1 的深浅色由 ThemeManager 换），对话框没有自己的父级可视树。</summary>
    private static Brush? BrushOf(Window? owner, string key) =>
        (owner?.TryFindResource(key) ?? Application.Current?.TryFindResource(key)) as Brush;
}
