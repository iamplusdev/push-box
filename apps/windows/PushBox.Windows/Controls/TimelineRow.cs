using System;
using System.Windows;
using System.Windows.Controls;
using PushBox.Windows.Data;
using PushBox.Windows.Views;

namespace PushBox.Windows.Controls;

/// <summary>
/// 建一行要的那几样东西：画刷快照、图位取字节的路、两个动作、右键菜单的四件事。
/// 它跟着主题变，不跟着会话变，所以窗口可以整轮留着同一份——配置一换，所有行都要重建一次。
/// </summary>
public sealed record TimelineConfig(MarkdownStyle Style, Func<MessageItem, UIElement?> Media,
    Action<string>? OpenLink, Action<string>? Expand, MessageCommands Commands)
{
    /// <summary>
    /// 一条数据变成一棵控件树。这<b>不是</b>虚拟化能省掉的那部分：面板只对生成出来的容器套模板，
    /// 屏外的条目就只剩一个数据对象，几百条历史也就不会被建一遍。切会话卡顿的另一半正来自这里。
    /// </summary>
    public FrameworkElement Build(TimelineItem item) => item switch
    {
        MessageItem message => Bubble(message),
        DateDivider divider => BubbleView.Divider(divider.Label, Style),
        _ => new TextBlock()
    };

    /// <summary>
    /// §9.3 在 PC 上的落点：右键菜单归气泡根节点。菜单里"复制正文"该复制哪一段只有正文自己知道——
    /// 拖选了一段就复制那段，没选才复制整条（文字跟着改，见 <see cref="BubbleView.ContextMenuFor"/>）。
    /// </summary>
    private FrameworkElement Bubble(MessageItem message)
    {
        var node = BubbleView.Build(message, Style, Media(message), OpenLink, Expand);
        node.ContextMenu = BubbleView.ContextMenuFor(message, Commands,
            () => BubbleView.SelectableOf(node)?.SelectedText);
        return node;
    }
}

/// <summary>
/// 时间线行模板的根。把"建气泡"这件事挪到模板里，屏外的行因此连控件都不存在；
/// 回收模式下同一个实例会被换上下一条数据（<see cref="Item"/> 变），所以不能建一次就完事。
/// </summary>
public sealed class TimelineRow : Decorator
{
    public static readonly DependencyProperty ItemProperty = DependencyProperty.Register(
        nameof(Item), typeof(TimelineItem), typeof(TimelineRow),
        new PropertyMetadata(null, static (d, _) => ((TimelineRow)d).Rebuild()));

    public static readonly DependencyProperty ConfigProperty = DependencyProperty.Register(
        nameof(Config), typeof(TimelineConfig), typeof(TimelineRow),
        new PropertyMetadata(null, static (d, _) => ((TimelineRow)d).Rebuild()));

    public TimelineItem? Item
    {
        get => (TimelineItem?)GetValue(ItemProperty);
        set => SetValue(ItemProperty, value);
    }

    public TimelineConfig? Config
    {
        get => (TimelineConfig?)GetValue(ConfigProperty);
        set => SetValue(ConfigProperty, value);
    }

    /// <summary>两样缺一样就不建：绑定的落地顺序不定，谁后到谁触发这一次。</summary>
    private void Rebuild() => Child = Item is { } item && Config is { } config ? config.Build(item) : null;
}
