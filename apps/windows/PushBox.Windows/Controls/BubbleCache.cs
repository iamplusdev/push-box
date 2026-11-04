using System.Collections.Generic;
using System.Windows;
using PushBox.Windows.Views;

namespace PushBox.Windows.Controls;

/// <summary>
/// 切走的行把建好的那棵树留在这里，切回来时直接接上——省掉整条气泡的重建
/// （实测一条三行正文"建树 + 量"约 22 ms，一屏 6–7 条就是 150 ms 上下，而切会话每次要建两屏）。
///
/// 为什么按会话分桶而不是一整袋 LRU：一屏真正建出来的是十几到二十几行（面板只量得出视口那一截），
/// 六个容量装不下两屏，于是"在五六条会话之间来回切"这条最常用的路每次都把要接上的那几条挤掉了——
/// 实测缓存命中 0 次、切一次会话 300–1200 ms；分桶之后同样六条会话来回切，命中铺满整屏、
/// 落到 25–45 ms。挤掉别人才会卡，所以淘汰的单位得是"一整条会话"，不是"最老的一棵树"。
///
/// 只活在一份 <see cref="TimelineConfig"/> 里：配置跟主题走，换主题整袋丢掉，
/// 所以缓存里不会混进上一套画刷的树。
/// 条目键是 <see cref="MessageItem"/> 这个 record：正文被编辑/撤回后各字段变了，键也就对不上，
/// 旧的那条留在缓存里等淘汰，不会把过期内容接上去。
/// </summary>
public sealed class BubbleCache
{
    /// <summary>一条会话留多少棵树：一整屏再加上翻出去的两三屏。</summary>
    public const int PerConversation = 64;

    /// <summary>同时记着几条会话。用户来回切的就是手头这几条，第 N+1 条进来才丢最久没碰的那一整桶。</summary>
    public const int RememberedConversations = 8;

    private sealed class Bucket
    {
        public readonly Dictionary<MessageItem, FrameworkElement> Kept = new();

        /// <summary>放入顺序，用来在桶内按最早的那几条淘汰（被 Take 走的会留下一个已不在 <see cref="Kept"/> 的死键）。</summary>
        public readonly Queue<MessageItem> Order = new();
    }

    private readonly Dictionary<(string ChannelNo, string SenderId), Bucket> _buckets = new();

    /// <summary>碰过的顺序，最近碰的那一路排在末尾；排在最前的就是下一条要整桶丢掉的。</summary>
    private readonly List<(string ChannelNo, string SenderId)> _recent = new();

    /// <summary>当下留着的树有几棵（淘汰与断言都认这个数）。</summary>
    public int Count { get; private set; }

    /// <summary>从缓存里接上过几次。用例拿它断"切回来确实复用了"。 </summary>
    public int Hits { get; private set; }

    /// <summary>要建新树几次。第一次打开一条会话时这一整屏都走这里。</summary>
    public int Misses { get; private set; }

    public FrameworkElement? Take(MessageItem item)
    {
        var bucket = Touch((item.ChannelNo, item.SenderId));
        if (!bucket.Kept.Remove(item, out var element))
        {
            Misses++;
            return null;
        }

        Count--;
        Hits++;
        return element;
    }

    public void Put(MessageItem item, FrameworkElement element)
    {
        var bucket = Touch((item.ChannelNo, item.SenderId));
        if (bucket.Kept.ContainsKey(item)) return;
        bucket.Kept[item] = element;
        bucket.Order.Enqueue(item);
        Count++;

        while (bucket.Kept.Count > PerConversation && bucket.Order.Count > 0)
            if (bucket.Kept.Remove(bucket.Order.Dequeue())) Count--;

        while (_buckets.Count > RememberedConversations) Drop(_recent[0]);
    }

    /// <summary>这一条会话此刻在被用：桶要留着，顺序要挪到末尾。</summary>
    private Bucket Touch((string ChannelNo, string SenderId) key)
    {
        if (!_buckets.TryGetValue(key, out var bucket)) _buckets[key] = bucket = new Bucket();
        _recent.Remove(key);
        _recent.Add(key);
        return bucket;
    }

    private void Drop((string ChannelNo, string SenderId) key)
    {
        if (_buckets.Remove(key, out var bucket)) Count -= bucket.Kept.Count;
        // 顺序表里这一路一定要摘掉：不摘的话上面那个 while 会拿同一个键转圈
        _recent.Remove(key);
    }
}
