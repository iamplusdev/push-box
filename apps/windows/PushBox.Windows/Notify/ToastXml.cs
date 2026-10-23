using Windows.Data.Xml.Dom;

namespace PushBox.Windows.Notify;

/// <summary>
/// <see cref="ToastPlan"/> → 那一小段 toast XML。它是机械转录，没有判断，所以判断全在能测的地方；
/// 而这一层本身也能测：<c>Windows.Data.Xml.Dom</c> 是进程内组件，xunit 里就能造出文档并把
/// <c>GetXml()</c> 拿来断言——真正只能在真机上验的只剩"发出去"那一句。
///
/// 文本一律走 <see cref="XmlDocument.CreateTextNode"/> 而不是拼字符串：标题和正文都是第三方
/// 推进来的自由文本，拼进去等于给通知渲染器递一个可能带 <c>&lt;</c> 的串。
/// </summary>
public static class ToastXml
{
    /// <summary>§9 的自适应模板名，Win10/11 都认。</summary>
    public const string Template = "ToastGeneric";

    /// <summary>点通知本体时 shell 执行的正是这一条，所以它必须是 protocol 而不是默认的 application。</summary>
    public const string ActivationProtocol = "protocol";

    /// <summary>头像在 toast 里的那个位置：应用角标位，圆裁。</summary>
    public const string AppLogoOverride = "appLogoOverride";

    public static XmlDocument Build(ToastPlan plan)
    {
        var doc = new XmlDocument();
        var toast = doc.CreateElement("toast");
        toast.SetAttribute("launch", plan.Launch);
        toast.SetAttribute("activationType", ActivationProtocol);
        doc.AppendChild(toast);

        var visual = doc.CreateElement("visual");
        var binding = doc.CreateElement("binding");
        binding.SetAttribute("template", Template);
        Text(doc, binding, plan.Title);
        Text(doc, binding, plan.Body);
        Text(doc, binding, plan.CountLine);
        if (plan.AvatarPath is { Length: > 0 } avatar)
        {
            var image = doc.CreateElement("image");
            image.SetAttribute("src", FileUri(avatar));
            image.SetAttribute("placement", AppLogoOverride);
            image.SetAttribute("hint-crop", "circle");
            binding.AppendChild(image);
        }

        visual.AppendChild(binding);
        toast.AppendChild(visual);

        // <c>audio</c> 这一句在不在是有讲究的：**省略它等于"放默认提示音"**，不是"不出声"。
        // 所以静默那一档要显式写一个 displayOnly 的空音，而不是干脆不写。
        var audio = doc.CreateElement("audio");
        audio.SetAttribute("src", "ms-winsoundevent:Notification.Default");
        if (!plan.PlaysSound) audio.SetAttribute("displayOnly", "true");
        toast.AppendChild(audio);

        if (plan.Buttons.Count > 0)
        {
            var actions = doc.CreateElement("actions");
            foreach (var button in plan.Buttons)
            {
                var action = doc.CreateElement("action");
                action.SetAttribute("content", button.Label);
                action.SetAttribute("activationType", ActivationProtocol);
                action.SetAttribute("arguments", button.Uri);
                // 第一颗排进正文旁边那行，第二颗收进悬停才显示的位置：Windows 上超过两颗按钮会挤掉文本
                action.SetAttribute("placement", ReferenceEquals(button, plan.Buttons[0]) ? "inline" : "ambient");
                actions.AppendChild(action);
            }

            toast.AppendChild(actions);
        }

        return doc;
    }

    public static string ToXmlString(ToastPlan plan) => Build(plan).GetXml();

    /// <summary>toast 的图片只能给 URI，而 <c>file:///</c> 那三种斜杠自己拼容易漏一位。</summary>
    public static string FileUri(string absolutePath) => new Uri(absolutePath).AbsoluteUri;

    private static void Text(XmlDocument doc, XmlElement parent, string? value)
    {
        if (string.IsNullOrEmpty(value)) return;
        var text = doc.CreateElement("text");
        text.AppendChild(doc.CreateTextNode(value));
        parent.AppendChild(text);
    }
}
