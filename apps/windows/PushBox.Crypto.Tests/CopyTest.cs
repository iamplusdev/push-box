using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using PushBox.Windows.Controls;
using PushBox.Windows.Data;
using PushBox.Windows.Views;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// §9.3 的"把这条东西拿走"：气泡右键菜单与代码块那颗复制按钮。
/// 剪贴板本身不在这里断——那是系统的事，写一次就会污染跑测的机器；
/// 断的是"点下去交给谁、交的是哪一段"，也就是真正会写错的那一层。
/// </summary>
public class CopyTest
{
    private static readonly MarkdownStyle Style = Paint.Style();

    private static MessageItem Item(string text = "正文", int mediaCount = 0, string? link = null) =>
        new("01HXYZ", 7, "ch", "sender", text, "info", null, 1_700_000_000_000, mediaCount, null, link);

    /// <summary>四条动作全部记下参数，谁被误接到谁身上一眼看得出来。</summary>
    private sealed class Recorder
    {
        public List<string> Calls { get; } = new();

        public MessageCommands Commands => new(
            text => Calls.Add("text:" + text),
            id => Calls.Add("image:" + id),
            url => Calls.Add("link:" + url),
            id => Calls.Add("delete:" + id));
    }

    private static List<string> Tags(ContextMenu menu) => menu.Items.OfType<MenuItem>()
        .Select(item => item.Tag as string ?? string.Empty)
        .ToList();

    private static void Click(ContextMenu menu, string tag) =>
        menu.Items.OfType<MenuItem>().Single(item => (item.Tag as string) == tag)
            .RaiseEvent(new RoutedEventArgs(MenuItem.ClickEvent));

    [Fact]
    public void OnlyTheThingsTheMessageActuallyHasGetAMenuEntry()
    {
        Sta.Run(() =>
        {
            // 一句纯文本：能复制正文，也能删；没有图、没有链接
            Assert.Equal(new[] { BubbleView.CopyTextTag, BubbleView.DeleteTag },
                Tags(BubbleView.ContextMenuFor(Item(), new Recorder().Commands)));

            // 只有图的：第一项就是复制图片，前面不该挂一条孤零零的分隔线
            var mediaOnly = BubbleView.ContextMenuFor(Item(text: string.Empty, mediaCount: 1),
                new Recorder().Commands);
            Assert.Equal(new[] { BubbleView.CopyImageTag, BubbleView.DeleteTag }, Tags(mediaOnly));
            Assert.IsType<MenuItem>(mediaOnly.Items[0]);

            Assert.Contains(BubbleView.CopyLinkTag,
                Tags(BubbleView.ContextMenuFor(Item(link: "https://x.cn/a"), new Recorder().Commands)));
        });
    }

    /// <summary>多图消息不给"复制图片"：复制哪一张说不清，给一个猜的答案比不给更糟。</summary>
    [Fact]
    public void AStackOfImagesDoesNotPretendToKnowWhichOneToCopy()
    {
        Sta.Run(() =>
        {
            Assert.DoesNotContain(BubbleView.CopyImageTag,
                Tags(BubbleView.ContextMenuFor(Item(mediaCount: 3), new Recorder().Commands)));
            Assert.Contains(BubbleView.CopyImageTag,
                Tags(BubbleView.ContextMenuFor(Item(mediaCount: 1), new Recorder().Commands)));
        });
    }

    /// <summary>
    /// 拖选之后"复制正文"要复制<b>选中那段</b>，而且菜单项的字也得跟着改——
    /// 点下去拿到半句、菜单上还写着"复制正文"，用户会以为整条都进去了。
    /// </summary>
    [Fact]
    public void CopyingTextPrefersWhatTheUserSelected()
    {
        Sta.Run(() =>
        {
            var recorder = new Recorder();
            string? picked = null;
            var menu = BubbleView.ContextMenuFor(Item("整条正文"), recorder.Commands, () => picked);
            var copy = menu.Items.OfType<MenuItem>().Single(item => (item.Tag as string) == BubbleView.CopyTextTag);

            // 没选中：写着"复制正文"，交整条
            menu.RaiseEvent(new RoutedEventArgs(ContextMenu.OpenedEvent));
            Assert.Equal(BubbleView.CopyTextLabel, copy.Header);
            Click(menu, BubbleView.CopyTextTag);
            Assert.Equal(new[] { "text:整条正文" }, recorder.Calls);

            // 选中了：菜单当场改字，交的是那半句
            picked = "选中这半句";
            menu.RaiseEvent(new RoutedEventArgs(ContextMenu.OpenedEvent));
            Assert.Equal(BubbleView.CopyPickedLabel, copy.Header);
            Click(menu, BubbleView.CopyTextTag);
            Assert.Equal(new[] { "text:整条正文", "text:选中这半句" }, recorder.Calls);
        });
    }

    /// <summary>窗口那一侧要靠它把"选中了什么"绑到菜单上：有正文的气泡找得到，纯图气泡找不到。</summary>
    [Fact]
    public void TheBubbleKnowsWhichPartOfItCanBeSelected()
    {
        Sta.Run(() =>
        {
            Assert.NotNull(BubbleView.SelectableOf(BubbleView.Build(Item(), Style)));
            Assert.Null(BubbleView.SelectableOf(BubbleView.Build(Item(text: string.Empty, mediaCount: 1), Style)));
        });
    }

    [Fact]
    public void EachEntryHandsOverItsOwnPieceOfTheMessage()
    {
        Sta.Run(() =>
        {
            var recorder = new Recorder();
            var menu = BubbleView.ContextMenuFor(Item("构建通过", 1, "https://x.cn/alert"), recorder.Commands);

            Click(menu, BubbleView.CopyTextTag);
            Click(menu, BubbleView.CopyImageTag);
            Click(menu, BubbleView.CopyLinkTag);
            Click(menu, BubbleView.DeleteTag);

            // 顺序也是判据：复制正文交正文、复制图片交的是这条消息的 id、复制链接交链接，
            // 四者串一位就等于用户按"复制图片"拿到的是一段文字
            Assert.Equal(new[] { "text:构建通过", "image:01HXYZ", "link:https://x.cn/alert", "delete:01HXYZ" },
                recorder.Calls);
        });
    }

    /// <summary>用例里的渲染是纯函数，不该顺手写系统剪贴板：没人交回调，那颗按钮连字都不出现。</summary>
    [Fact]
    public void TheCodeBlockOnlyGrowsACopyButtonWhenSomebodyIsListening()
    {
        Sta.Run(() =>
        {
            var quiet = (Border)MarkdownView.Build(new MarkdownCode("curl -X POST"), Style);
            Assert.IsType<ScrollViewer>(quiet.Child);

            string? copied = null;
            var border = (Border)MarkdownView.Build(new MarkdownCode("curl -X POST"),
                Style with { Copy = text => copied = text });
            var layer = Assert.IsType<Grid>(border.Child);
            // 代码本体在下、按钮在上，按钮盖住的那块正是横滚时长行经过的地方
            Assert.IsType<ScrollViewer>(layer.Children[0]);
            var chip = Assert.IsType<TextBlock>(layer.Children[1]);
            Assert.Equal("复制", chip.Text);
            Assert.Null(copied);
        });
    }

    [Fact]
    public void TheCopyButtonHandsOverTheCodeVerbatimAndThenSaysSo()
    {
        Sta.Run(() =>
        {
            const string source = "curl -X POST https://x.cn/message \\\n  -d '{\"text\":\"**不加粗**\"}'";
            string? copied = null;
            var border = (Border)MarkdownView.Build(new MarkdownCode(source),
                Style with { Copy = text => copied = text });
            var chip = (TextBlock)((Grid)border.Child).Children[1];

            chip.RaiseEvent(new MouseButtonEventArgs(Mouse.PrimaryDevice, 0, MouseButton.Left)
            {
                RoutedEvent = UIElement.MouseLeftButtonUpEvent
            });

            // 交出去的是原文而不是排版后的样子：markdown 记号照抄，粘回去还能再渲染
            Assert.Equal(source, copied);
            Assert.Equal("已复制", chip.Text);
            // 按钮自己跟主题走：点完不变色、也不掉回气泡底色
            Assert.Same(Style.Code, chip.Background);
            Assert.Same(Style.Link, chip.Foreground);
        });
    }
}
