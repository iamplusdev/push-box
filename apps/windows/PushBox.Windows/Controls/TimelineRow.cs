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
    /// <summary>切走的行把气泡留在这里，切回来直接接上（见 <see cref="BubbleCache"/>）。</summary>
    public BubbleCache Cache { get; init; } = new();

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

    /// <summary>
    /// 建行的那份配置从时间线上**继承**下来，不用绑定。绑 <c>RelativeSource AncestorType=Window</c>
    /// 要等这一行接到树上、绑定引擎下一拍才落地，而面板第一次量的时候就已经决定了要生成几行：
    /// 那一刻 <see cref="Child"/> 还是空的、行高是 0，"量够一屏了"永远不成立，于是 51 行全建一遍——
    /// 切一次会话卡 1 秒以上，成本全在这里。继承值在取值当下就能算出来，第一次量就有行高。
    /// </summary>
    public static readonly DependencyProperty ConfigProperty = DependencyProperty.RegisterAttached(
        "Config", typeof(TimelineConfig), typeof(TimelineRow),
        new FrameworkPropertyMetadata(null, FrameworkPropertyMetadataOptions.Inherits,
            static (d, _) =>
            {
                if (d is TimelineRow row) row.Rebuild();
            }));

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

    public static TimelineConfig? GetConfig(DependencyObject target) =>
        (TimelineConfig?)target.GetValue(ConfigProperty);

    public static void SetConfig(DependencyObject target, TimelineConfig? value) =>
        target.SetValue(ConfigProperty, value);

    private TimelineItem? _builtItem;

    private TimelineConfig? _builtWith;

    /// <summary>
    /// 两样缺一样就不建：绑定的落地顺序不定，谁后到谁触发这一次。
    /// 换下一条之前先把这一棵还给建它的那份配置的缓存——回收模式下同一个实例会被反复换数据，
    /// 上一次建出来的树正是下一次切回来时要接上的那一棵（旧配置建的树不进新配置的缓存：
    /// 换主题时画刷已经换了一套）。接缓存时元素必须先从这棵树上摘下来，一个元素不能挂两个父节点。
    /// </summary>
    private void Rebuild()
    {
        var previous = Child as FrameworkElement;
        var previousItem = _builtItem;
        var previousConfig = _builtWith;
        Child = null;
        _builtItem = null;
        _builtWith = null;
        if (previous is not null && previousItem is MessageItem message && previousConfig is not null)
            previousConfig.Cache.Put(message, previous);

        if (Item is not { } item || Config is not { } config) return;
        // 只有消息能进缓存：日期分隔条不值一次字典查找，也不该占容量
        Child = (item is MessageItem message2 ? config.Cache.Take(message2) : null) ?? config.Build(item);
        _builtItem = item;
        _builtWith = config;
    }
}
