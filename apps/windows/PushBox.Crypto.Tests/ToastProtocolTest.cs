using System;
using PushBox.Windows.Notify;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// §8.1 那句"通知点击唤起对应会话"的载体：一条 <c>pushbox:</c> 协议 URI。
/// 它是"Toast 点击能定位到具体会话"（§8.4 W13 验收①）唯一能被自动化的一半——
/// 另一半（shell 真的按这条 URI 把进程拉起来）只能在真机上走查。
/// 编解码都必须在这里钉死，因为参数是自由文本：一条 <c>/</c> 就能让段数变化，解回来的会话就成了猜的。
/// </summary>
public sealed class ToastProtocolTest
{
    [Fact]
    public void TheOpenVerbCarriesTheConversationItMustShow()
    {
        var uri = ToastProtocol.OpenUri("83271504", "grafana");

        Assert.Equal("pushbox:conv/83271504/grafana", uri);
        var target = ToastProtocol.Parse(uri);
        Assert.Equal(ToastTargetKind.OpenConversation, target!.Kind);
        Assert.Equal("83271504", target.ChannelNo);
        Assert.Equal("grafana", target.SenderId);
    }

    [Fact]
    public void TheReadVerbIsADifferentActionOnTheSameConversation()
    {
        // 两颗按钮指向同一条会话，区别只在点完之后要不要顺手清红点（见 App.HandleTarget）
        Assert.Equal("pushbox:read/83271504/grafana", ToastProtocol.ReadUri("83271504", "grafana"));
        Assert.Equal(ToastTargetKind.MarkRead, ToastProtocol.Parse(ToastProtocol.ReadUri("c", "s"))!.Kind);
    }

    [Fact]
    public void RoundTripSurvivesWhateverTheIdsAre()
    {
        // channel_no / sender_id 是第三方自由文本：中文、空格、以及三个会破坏 URI 结构的字符
        foreach (var value in new[]
                 {
                     "频道 01", "a/b", "a%b", "a&b", "a?b#c", "a\\b", "100%", "emoji🙂"
                 })
        {
            var target = ToastProtocol.Parse(ToastProtocol.OpenUri(value, "sender"));
            Assert.Equal(value, target!.ChannelNo);
            Assert.Equal("sender", target.SenderId);
        }
    }

    [Fact]
    public void SeparatorsInTheIdsNeverChangeTheSegmentCount()
    {
        // 这条是上面那个循环真正在防的东西：不转义的话 "a/b" 会拼出四段，解出来是"段数不对"而整条通知失效
        var uri = ToastProtocol.OpenUri("a/b", "c&d");
        Assert.Equal(3, uri[ToastProtocol.Prefix.Length..].Split('/').Length);
        Assert.DoesNotContain("a/b", uri);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("https://example.com/pushbox:conv/c/s")]
    [InlineData("pushbox:conv/only-two")] // 段数少了
    [InlineData("pushbox:conv/a/b/c")] // 段数多了
    [InlineData("pushbox:jump/a/b")] // 动词不认识
    [InlineData("pushbox:conv//b")] // 频道为空
    [InlineData("pushbox:conv/a/")] // 发送者为空
    [InlineData("pushbox:conv/%20/b")] // 只剩空格，等于没有 id
    [InlineData("pushbox:conv/%20%20/b")]
    public void GarbageReadsAsNoTargetInsteadOfThrowing(string? raw)
    {
        // 调用方拿到 null 就是"这条通知我们读不懂"，于是什么都不做——把异常抛给一次点击是最差的结果
        Assert.Null(ToastProtocol.Parse(raw));
    }

    [Fact]
    public void SchemeAndVerbAreCaseInsensitive()
    {
        // shell 传参时协议名可能被规范化，不该因此把一次点击丢掉
        Assert.Equal(ToastTargetKind.OpenConversation, ToastProtocol.Parse("PUSHBOX:CONV/a/b")!.Kind);
    }

    [Fact]
    public void HalfWrittenEscapeIsReadLiterally()
    {
        // 手改过注册表/通知参数留下的半截 %xx：按字面读，别把整条通知判成坏数据
        var target = ToastProtocol.Parse("pushbox:conv/a%/b");
        Assert.Equal("a%", target!.ChannelNo);
    }

    [Fact]
    public void ArgumentsAreWalkedUntilOneOfThemIsATarget()
    {
        // shell 可能把 URI 整条传成一个参数，也可能被引号拆成几段，所以不能只看 args[1]
        var target = ToastProtocol.FromArgs(new[] { "C:\\Apps\\PushBox\\PushBox.Windows.exe", "--flag",
            ToastProtocol.ReadUri("83271504", "grafana") });

        Assert.Equal(ToastTargetKind.MarkRead, target!.Kind);
        Assert.Equal("grafana", target.SenderId);
    }

    [Fact]
    public void NoArgumentsAndAnOrdinaryLaunchBothMeanNobodyWasClicked()
    {
        Assert.Null(ToastProtocol.FromArgs(null));
        Assert.Null(ToastProtocol.FromArgs(Array.Empty<string>()));
        Assert.Null(ToastProtocol.FromArgs(new[] { "C:\\Apps\\PushBox\\PushBox.Windows.exe" }));
    }

    [Fact]
    public void TheHandlerQuotesBothTheExeAndTheArgument()
    {
        // 前半不带引号，Program Files 那条路径会被拆成"可执行文件 + 参数"；
        // 后半不带引号，URI 里的 & 会被 cmd 当成命令分隔符从那里截断
        Assert.Equal("\"C:\\Program Files\\PushBox\\PushBox.Windows.exe\" \"%1\"",
            ToastProtocol.HandlerCommand(@"C:\Program Files\PushBox\PushBox.Windows.exe"));
    }

    [Fact]
    public void ThePlanAndTheProtocolAgreeOnTheConversationKey()
    {
        // 分组键与 URI 的会话标识必须同源，否则"打开会话"清了红点、通知中心那一组还挂着
        var group = ToastPlan.GroupOf("83271504", "grafana");
        Assert.Equal("83271504/grafana", group);
        Assert.Equal("pushbox:conv/83271504/grafana", ToastProtocol.OpenUri("83271504", "grafana"));
    }

    [Fact]
    public void TheUriOfATargetIsTheSameStringThatBuiltIt()
    {
        var target = new ToastTarget(ToastTargetKind.MarkRead, "c", "s");
        Assert.Equal(ToastProtocol.ReadUri("c", "s"), ToastProtocol.UriOf(target));
        Assert.Equal(target, ToastProtocol.Parse(ToastProtocol.UriOf(target)));
    }
}
