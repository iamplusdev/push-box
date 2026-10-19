using System;
using System.Collections.Generic;
using System.Linq;
using System.Xml.Linq;
using PushBox.Windows.Notify;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// <see cref="ToastPlan"/> → 那一小段 toast XML 的转录层。<c>Windows.Data.Xml.Dom</c> 是进程内组件，
/// 所以在 xunit 里就能造出文档并把 <c>GetXml()</c> 读回来——这里用 <see cref="XDocument"/> 再解析一遍，
/// 于是"文本是数据还是标签""静音是省略还是显式声明"这两类问题都是断言而不是走查。
/// 真正只能在真机上验的只剩 <c>Show()</c> 那一句（见 <c>--notify-smoke</c>）。
/// </summary>
public sealed class ToastXmlTest
{
    private const string MarkupBody = "<b>危险</b> & \"引号\" '单引号'";

    [Fact]
    public void TheRootCarriesTheLaunchAndSaysTheShellMustRunIt()
    {
        var toast = Root(Plan());

        Assert.Equal(ToastProtocol.OpenUri("83271504", "grafana"), (string?)toast.Attribute("launch"));
        // activationType 不是 protocol 的话，launch 只会被当成应用内参数交给已经活着的进程，点击定位会话就没了
        Assert.Equal("protocol", (string?)toast.Attribute("activationType"));
    }

    [Fact]
    public void TextComesBackByteForByteEvenWhenItLooksLikeMarkup()
    {
        // 这一条断的是"用 CreateTextNode 而不是拼字符串"：拼进去的 <b> 会被解析成真正的元素，
        // 通知渲染器就此被第三方推进来的自由文本控制
        var plan = Plan(summary: MarkupBody);
        var texts = Texts(plan);

        Assert.Equal(2, texts.Count);
        Assert.Equal("Grafana", texts[0]);
        Assert.Equal(MarkupBody, texts[1]);
        Assert.DoesNotContain("<b>危险", ToastXml.ToXmlString(plan));
    }

    [Fact]
    public void BindingUsesTheAdaptiveTemplate()
    {
        var binding = Root(Plan()).Element("visual")!.Element("binding")!;
        Assert.Equal(ToastXml.Template, (string?)binding.Attribute("template"));
    }

    [Fact]
    public void ACountLineIsAThirdTextRowAndNothingIsEmittedWhenThereIsNone()
    {
        // 标题 + 正文是固定的两行，计数行只在同会话第二条及以后才多出来
        Assert.Equal(new[] { "Grafana", "正文" }, Texts(Plan(groupCount: 1)));
        Assert.Equal(new[] { "Grafana", "正文", "2 条新消息" }, Texts(Plan(groupCount: 2)));
    }

    [Fact]
    public void SilentStillShipsAnAudioElementBecauseOmittingItMeansPlayTheDefault()
    {
        // 这是 W13 踩过的那个反面：<audio> 整段省掉 = 放默认提示音，静音必须显式写 displayOnly
        var silent = Root(Plan(sound: false)).Element("audio")!;
        Assert.Equal("ms-winsoundevent:Notification.Default", (string?)silent.Attribute("src"));
        Assert.Equal("true", (string?)silent.Attribute("displayOnly"));

        var sounding = Root(Plan(sound: true)).Element("audio")!;
        Assert.NotNull(sounding.Attribute("src"));
        Assert.Null(sounding.Attribute("displayOnly"));
    }

    [Fact]
    public void AnAvatarBecomesAFileUriInTheLogoSlotAndCircleCropped()
    {
        var avatar = @"C:\Users\dev\AppData\Local\PushBox\toast-avatar\avatar-ab12.png";
        var node = Root(Plan(avatar: avatar)).Element("visual")!.Element("binding")!.Element("image")!;

        Assert.Equal(ToastXml.FileUri(avatar), (string?)node.Attribute("src"));
        // 三种斜杠自己拼容易漏一位，这里钉的是"确实是 file URI 且盘符还在"
        Assert.StartsWith("file:///c:/", ((string?)node.Attribute("src"))!.ToLowerInvariant());
        Assert.Equal(ToastXml.AppLogoOverride, (string?)node.Attribute("placement"));
        Assert.Equal("circle", (string?)node.Attribute("hint-crop"));
        // 头像不进文本行：它只能是 image 节点，路径出现在正文里等于把本机目录结构印在通知上
        Assert.DoesNotContain(avatar, Texts(Plan(avatar: avatar)));
    }

    [Fact]
    public void NoAvatarMeansNoImageElementAtAll()
    {
        Assert.Null(Root(Plan()).Element("visual")!.Element("binding")!.Element("image"));
    }

    [Fact]
    public void BothButtonsTravelAsProtocolActions()
    {
        var actions = Root(Plan()).Element("actions")!.Elements("action").ToList();

        Assert.Equal(new[] { ToastPlan.OpenLabel, ToastPlan.MarkReadLabel },
            actions.Select(a => (string?)a.Attribute("content")));
        Assert.Equal(new[] { ToastProtocol.OpenUri("83271504", "grafana"),
            ToastProtocol.ReadUri("83271504", "grafana") }, actions.Select(a => (string?)a.Attribute("arguments")));
        Assert.All(actions, a => Assert.Equal("protocol", (string?)a.Attribute("activationType")));
        // 第三颗按钮会开始挤掉正文，所以设计里只有两颗：第一颗 inline，第二颗收进悬停位
        Assert.Equal(new[] { "inline", "ambient" }, actions.Select(a => (string?)a.Attribute("placement")));
    }

    [Fact]
    public void APlanWithoutButtonsStillRenders()
    {
        var plan = Plan() with { Buttons = Array.Empty<ToastButton>() };
        Assert.Null(Root(plan).Element("actions"));
        Assert.NotNull(Root(plan).Element("visual"));
    }

    [Fact]
    public void EmptyTextNeverBecomesAnEmptyRow()
    {
        // 只有附件、摘要/标题/正文三档全空的一条：通知只剩标题一行，不能挂一个空 <text/> 让人以为渲染坏了
        var frame = Support.Message(summary: "  ", title: null, content: null);
        var plan = ToastPlan.Of(frame, new AlertDecision(AlertUrgency.Sound), null, 1)!;

        Assert.Equal(string.Empty, plan.Body);
        Assert.Equal(new[] { "Grafana" }, Texts(plan));
    }

    private static XElement Root(ToastPlan plan) => XDocument.Parse(ToastXml.ToXmlString(plan)).Root!;

    private static List<string> Texts(ToastPlan plan) =>
        Root(plan).Element("visual")!.Element("binding")!.Elements("text").Select(t => t.Value).ToList();

    /// <summary>造一条最小的计划：正文用 <paramref name="summary"/>，其余字段固定。</summary>
    private static ToastPlan Plan(string? summary = "正文", int groupCount = 1, bool sound = true,
        string? avatar = null)
    {
        var frame = Support.Message(summary: summary);
        return ToastPlan.Of(frame, new AlertDecision(sound ? AlertUrgency.Sound : AlertUrgency.Silent),
            avatar, groupCount)!;
    }
}
