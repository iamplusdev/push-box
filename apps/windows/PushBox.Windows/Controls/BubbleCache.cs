using System.Collections.Generic;
using System.Windows;
using PushBox.Windows.Views;

namespace PushBox.Windows.Controls;

/// <summary>
/// 切走的行把建好的那棵树留在这里，切回来时直接接上——省掉整条气泡的重建
/// （实测一条三行正文"建树 + 量"约 22 ms，一屏 6–7 条就是 150 ms 上下，而切会话每次要建两屏）。
///
/// 只活在一份 <see cref="TimelineConfig"/> 里：配置跟主题走，换主题整袋丢掉，
/// 所以缓存里不会混进上一套画刷的树。容量按"三屏左右"给，超出按放入顺序淘汰。
/// 条目键是 <see cref="MessageItem"/> 这个 record：正文被编辑/撤回后各字段变了，键也就对不上，
/// 旧的那条留在缓存里等淘汰，不会把过期内容接上去。
/// </summary>
public sealed class BubbleCache
{
    private const int Capacity = 48;

    private readonly Dictionary<MessageItem, FrameworkElement> _kept = new();
    private readonly Queue<MessageItem> _order = new();

    public int Count => _kept.Count;

    /// <summary>从缓存里接上过几次。用例拿它断"切回来确实复用了"。 </summary>
    public int Hits { get; private set; }

    /// <summary>要建新树几次。第一次打开一条会话时这一整屏都走这里。</summary>
    public int Misses { get; private set; }

    public FrameworkElement? Take(MessageItem item)
    {
        if (!_kept.Remove(item, out var element))
        {
            Misses++;
            return null;
        }

        Hits++;
        return element;
    }

    public void Put(MessageItem item, FrameworkElement element)
    {
        if (_kept.ContainsKey(item)) return;
        _kept[item] = element;
        _order.Enqueue(item);
        // 被 Take 走的那条在队列里还剩一个死键，这里顺手跳过
        while (_kept.Count > Capacity && _order.Count > 0) _kept.Remove(_order.Dequeue());
    }
}
