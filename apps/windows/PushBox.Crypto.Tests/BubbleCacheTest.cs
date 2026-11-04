using System.Collections.Generic;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using PushBox.Windows.Controls;
using PushBox.Windows.Views;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// 气泡缓存的淘汰单位。判据全是"切回来还要不要重建"：一屏气泡建出来实测 130–760 ms，
/// 而"在几条会话之间来回切"是用户点得最多的那件事，所以复用生不生效值得单独钉住。
/// 这里只喂字典，不建真的树——窗口那一侧的"确实接上了同一个实例"由
/// <see cref="MainWindowTest.BubblesSurviveASwitchAwayAndBack"/> 断。
/// </summary>
public class BubbleCacheTest
{
    private static MessageItem Item(string sender, int seq) =>
        new("m" + seq, seq, "ch", sender, "正文 " + seq, "info", null,
            1_700_000_000_000L + seq, 0, null, null);

    /// <summary>一棵假的树就够：缓存只管谁挂着谁，不认识内容。</summary>
    private static FrameworkElement Tree() => new Border { Width = 7 };

    [Fact]
    public void EachConversationKeepsItsOwnPageSoCyclingBetweenThemRebuildsNothing()
    {
        var senders = new[] { "grafana", "deploy", "alert", "ci", "backup" };
        Sta.Run(() =>
        {
            var cache = new BubbleCache();
            // 每条会话一屏（比旧的整袋容量还多），然后五来五回地切
            foreach (var sender in senders)
                for (var seq = 1; seq <= 30; seq++) cache.Put(Item(sender, seq), Tree());

            foreach (var sender in senders)
                for (var seq = 1; seq <= 30; seq++)
                    Assert.NotNull(cache.Take(Item(sender, seq)));

            Assert.Equal(senders.Length * 30, cache.Hits);
            Assert.Equal(0, cache.Misses);
            Assert.Equal(0, cache.Count);
        });
    }

    /// <summary>
    /// 一条会话翻得很深只该丢它自己最早的那几棵：整袋 LRU 的话，翻一条长的就把别的会话全挤掉，
    /// 下一次切回去又是一整屏重建。
    /// </summary>
    [Fact]
    public void ADeeplyScrolledConversationOnlyLosesItsOwnOldestTrees()
    {
        Sta.Run(() =>
        {
            var cache = new BubbleCache();
            for (var seq = 1; seq <= 30; seq++) cache.Put(Item("deploy", seq), Tree());
            for (var seq = 1; seq <= BubbleCache.PerConversation + 6; seq++)
                cache.Put(Item("grafana", seq), Tree());

            // 桶里超出容量的那几条被淘汰；被 Take 走在队列里留下的死键不重复算账
            Assert.Equal(BubbleCache.PerConversation + 30, cache.Count);
            Assert.True(cache.Take(Item("grafana", 1)) is null, "最早那几条本该先出局");
            Assert.NotNull(cache.Take(Item("grafana", BubbleCache.PerConversation + 6)));
            for (var seq = 1; seq <= 30; seq++)
                Assert.NotNull(cache.Take(Item("deploy", seq)));
        });
    }

    /// <summary>记住的会话数是有限的：超出之后走的是"最久没碰的那一整桶"，不是"最老的那一棵"。</summary>
    [Fact]
    public void TheLeastRecentlyTouchedConversationGoesWholeWhenOneMoreArrives()
    {
        Sta.Run(() =>
        {
            var cache = new BubbleCache();
            for (var sender = 0; sender <= BubbleCache.RememberedConversations; sender++)
                for (var seq = 1; seq <= 5; seq++) cache.Put(Item("s" + sender, seq), Tree());

            Assert.Equal(BubbleCache.RememberedConversations * 5, cache.Count);
            for (var seq = 1; seq <= 5; seq++)
            {
                Assert.True(cache.Take(Item("s0", seq)) is null, "第一条进来的会话该已经整桶出局");
                Assert.NotNull(cache.Take(Item("s" + BubbleCache.RememberedConversations, seq)));
            }
        });
    }

    /// <summary>
    /// 桶的"最近碰过"要跟着取走移动：正在看的这一条即使一棵都没剩下（全被取上屏了），
    /// 也不能被下一条进来的会话挤掉，否则滚一圈回来又是一整屏重建。
    /// </summary>
    [Fact]
    public void BeingOnScreenKeepsAConversationInTheList()
    {
        Sta.Run(() =>
        {
            var cache = new BubbleCache();
            for (var sender = 1; sender <= BubbleCache.RememberedConversations; sender++)
                for (var seq = 1; seq <= 4; seq++) cache.Put(Item("s" + sender, seq), Tree());

            // 第一条会话整屏取走：桶空了，但这一路刚刚才碰过
            var onScreen = new List<FrameworkElement>();
            for (var seq = 1; seq <= 4; seq++) onScreen.Add(cache.Take(Item("s1", seq))!);

            // 第 9 条进来：走人的该是"最久没碰"的那一路（s2），而不是刚被取空的 s1
            for (var seq = 1; seq <= 4; seq++) cache.Put(Item("s9", seq), Tree());
            Assert.True(cache.Take(Item("s2", 1)) is null, "该整桶出局的是最久没碰的那一路");

            // 切走这一条：还回去的四棵还是原来那四棵
            for (var seq = 1; seq <= 4; seq++) cache.Put(Item("s1", seq), onScreen[seq - 1]);
            for (var seq = 1; seq <= 4; seq++) Assert.Same(onScreen[seq - 1], cache.Take(Item("s1", seq)));
        });
    }

    /// <summary>放回去的是已经在袋里的那一条 → 不覆盖、不重复计数。</summary>
    [Fact]
    public void PuttingWhatIsAlreadyKeptChangesNothing()
    {
        Sta.Run(() =>
        {
            var cache = new BubbleCache();
            var item = Item("grafana", 1);
            var tree = Tree();
            cache.Put(item, tree);
            cache.Put(item, Tree());

            Assert.Equal(1, cache.Count);
            Assert.Same(tree, cache.Take(item));
        });
    }
}
