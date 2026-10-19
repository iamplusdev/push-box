using System;
using System.Linq;
using System.Threading.Tasks;
using PushBox.Windows.Net;
using PushBox.Windows.Notify;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W13 的"说什么"那一层：一条通知的完整文案与两颗按钮。<see cref="ToastPlan"/> 是纯数据，
/// 所以 §8.1 列的"头像 / 摘要 / 打开会话 / 全部已读"四项在这里就能逐项断言，
/// 不必等到真机上用眼睛看弹窗。文案优先级照 Android 的 <c>summary ?: title ?: content</c>。
/// </summary>
public sealed class ToastPlanTest
{
    private static readonly AlertDecision Sound = new(AlertUrgency.Sound);

    [Fact]
    public void SummaryWinsThenTitleThenContent()
    {
        // 第三方常常只给 title（"备份失败"）而 content 是一整段 Markdown；反过来只给 content 时它是唯一可读来源
        Assert.Equal("摘要在此", Body(summary: "摘要在此", title: "标题", content: "正文"));
        Assert.Equal("标题", Body(summary: null, title: "标题", content: "正文"));
        Assert.Equal("正文", Body(summary: null, title: null, content: "正文"));
        // 全空（只有附件的消息）时是一句空话而不是 null 或 "content"
        Assert.Equal(string.Empty, Body(null, null, null));
    }

    [Theory]
    [InlineData("   ")] // 空白也算没有：通知上会出现一行看起来像 bug 的空格
    [InlineData("")]
    public void BlankFieldsAreSkippedRatherThanShown(string blank)
    {
        Assert.Equal("正文", Body(blank, blank, "正文"));
    }

    [Fact]
    public void TitleIsTheSenderNotTheMessageTitle()
    {
        // §7.3：通知头部显示的是会话名（displayName），Android 的 setContentTitle 用的也是它
        var plan = Plan(Support.Message(name: "构建机器人", title: "流水线 #1287 失败"));
        Assert.Equal("构建机器人", plan!.Title);
        Assert.Equal("流水线 #1287 失败", plan.Body);

        // 发送者显示名缺失时退回 sender_id（Frames 的 DisplayName 那条口径）
        Assert.Equal("grafana", Plan(Support.Message(name: null))!.Title);
    }

    [Fact]
    public void TagIsTheMessageIdAndGroupIsTheConversation()
    {
        // tag = msg_id ⇒ 同一条重复投递是覆盖而不是第二条通知；group = 会话 ⇒ 通知中心按会话折叠
        var plan = Plan(Support.Message(id: "01JMESSAGE", channel: "83271504", sender: "grafana"))!;
        Assert.Equal("01JMESSAGE", plan.Tag);
        Assert.Equal("83271504/grafana", plan.Group);
    }

    [Fact]
    public void ClickingTheToastItselfOpensThatConversation()
    {
        // 验收①"Toast 点击能定位到具体会话"在计划这一层的形状：launch 必须解回同一条会话
        var plan = Plan(Support.Message(channel: "c 01", sender: "谁"))!;
        Assert.Equal(ToastProtocol.OpenUri("c 01", "谁"), plan.Launch);

        var target = ToastProtocol.Parse(plan.Launch)!;
        Assert.Equal(ToastTargetKind.OpenConversation, target.Kind);
        Assert.Equal("c 01", target.ChannelNo);
        Assert.Equal("谁", target.SenderId);
    }

    [Fact]
    public void TheTwoButtonsAreTheTwoActionsFromTheDesign()
    {
        var plan = Plan(Support.Message(channel: "chan", sender: "send"))!;

        Assert.Equal(new[] { "打开会话", "全部已读" }, plan.Buttons.Select(b => b.Label));
        Assert.Equal(ToastProtocol.OpenUri("chan", "send"), plan.Buttons[0].Uri);
        Assert.Equal(ToastProtocol.ReadUri("chan", "send"), plan.Buttons[1].Uri);
        Assert.Equal("打开会话", ToastPlan.OpenLabel);
        Assert.Equal("全部已读", ToastPlan.MarkReadLabel);
    }

    [Fact]
    public void TheCountLineAppearsFromTheSecondMessageOn()
    {
        // 第一条不该写"1 条新消息"——那是 Android 摘要通知的形态，单条时它只会挡住正文
        Assert.Null(Plan(Support.Message(), groupCount: 1)!.CountLine);
        Assert.Null(Plan(Support.Message(), groupCount: 0)!.CountLine);
        Assert.Equal("3 条新消息", Plan(Support.Message(), groupCount: 3)!.CountLine);
        Assert.Equal("99 条新消息", ToastPlan.CountLabel(99));
    }

    [Fact]
    public void AVetoFromTheDecisionMeansNoPlanAtAll()
    {
        // 调用方拿"有没有计划"当分支，而不是自己再判一次决策——两处各判一遍迟早会不一致
        Assert.Null(ToastPlan.Of(Support.Message(), new AlertDecision(AlertUrgency.None, "静默"), null, 1));
        Assert.NotNull(ToastPlan.Of(Support.Message(), new AlertDecision(AlertUrgency.Silent), null, 1));
    }

    [Fact]
    public void UrgencyAndSoundComeStraightFromTheDecision()
    {
        Assert.Equal(AlertUrgency.Sound, Plan(Support.Message(level: "warning"), sound: true)!.Urgency);
        Assert.True(Plan(Support.Message(level: "warning"), sound: true)!.PlaysSound);
        Assert.False(Plan(Support.Message(level: "info"), sound: false)!.PlaysSound);
    }

    [Fact]
    public void AnAvatarPathIsCarriedThroughOrSimplyAbsent()
    {
        Assert.Null(Plan(Support.Message())!.AvatarPath);
        Assert.Equal("C:\\tmp\\avatar-1.png", Plan(Support.Message(), avatar: @"C:\tmp\avatar-1.png")!.AvatarPath);
    }

    [Fact]
    public void TheFrameThatNeverNotifiesStillHasAPlanIfTheCallerAsks()
    {
        // notify=false 的门在引擎那侧（§7.3 与 Android 同一分工），计划层不重复判，否则两处规则会漂
        var plan = ToastPlan.Of(Support.Message(notify: false), Sound, null, 1);
        Assert.NotNull(plan);
    }

    [Fact]
    public void TallyCountsEachConversationApart()
    {
        var tally = new ConversationTally();
        Assert.Equal(0, tally.Count("83271504/grafana"));

        Assert.Equal(1, tally.Bump("83271504/grafana"));
        Assert.Equal(2, tally.Bump("83271504/grafana"));
        Assert.Equal(1, tally.Bump("70000001/jenkins"));
        Assert.Equal(2, tally.Count("83271504/grafana"));
    }

    [Fact]
    public void OpeningAConversationRestartsItsCountButNotAnyonesElse()
    {
        // 看过一条不该把另一条没看过的也抹掉，所以按会话 Reset 而不是 Clear
        var tally = new ConversationTally();
        tally.Bump("a/x");
        tally.Bump("a/x");
        tally.Bump("b/y");

        tally.Reset("a/x");
        Assert.Equal(0, tally.Count("a/x"));
        Assert.Equal(1, tally.Count("b/y"));
        Assert.Equal(1, tally.Bump("a/x"));

        tally.ResetAll();
        Assert.Equal(0, tally.Count("b/y"));
        // 没见过的会话回 0，不是抛
        Assert.Equal(0, tally.Count("从没出现过"));
    }

    [Fact]
    public void TallyIsSafeToBumpFromTwoThreads()
    {
        // 通知可能来自 §5.3 的读循环，也可能来自 §5.2 的补拉，两处不在同一个线程上
        var tally = new ConversationTally();
        Parallel.ForEach(Enumerable.Range(0, 8), _ =>
        {
            for (var i = 0; i < 50; i++) tally.Bump("shared/group");
        });

        Assert.Equal(400, tally.Count("shared/group"));
    }

    private static string Body(string? summary, string? title, string? content) =>
        ToastPlan.SummaryOf(Support.Message(summary: summary, title: title, content: content));

    private static ToastPlan? Plan(MessageFrame frame, int groupCount = 1, bool sound = true,
        string? avatar = null) =>
        ToastPlan.Of(frame, new AlertDecision(sound ? AlertUrgency.Sound : AlertUrgency.Silent, null),
            avatar, groupCount);
}
