namespace PushBox.Windows.Notify;

/// <summary>点通知要点开哪件事。两种都定位到具体会话（§8.4 W13 的验收），区别只在要不要顺手清红点。</summary>
public enum ToastTargetKind
{
    /// <summary>唤起主窗口并打开这条会话。</summary>
    OpenConversation,

    /// <summary>把这条会话标已读（Toast 上那颗"全部已读"），不唤起窗口。</summary>
    MarkRead,
}

/// <summary>一条通知动作的目标会话。id 是解码后的原值，可以直接拿去查库。</summary>
public sealed record ToastTarget(ToastTargetKind Kind, string ChannelNo, string SenderId);

/// <summary>
/// §8.1 那句"通知点击唤起对应会话"在 PC 上的载体：一条自定义协议 URI。
///
/// 为什么是协议而不是 <c>activationType="foreground"</c>：后者只在应用已经活着时把窗口带到前台，
/// <b>launch 字符串不会交给进程</b>，于是"点这一条开这一条会话"就没了；协议那一路由 shell 执行，
/// 应用没开也能起来，起来时 URI 就在 argv 里，两种情况走同一条路。
///
/// 分段一律先转义再拼：<c>channel_no</c> / <c>sender_id</c> 是可以带 <c>/</c> 和空格的自由文本，
/// 不转义的话段数都不固定，解回来只能靠猜。
/// </summary>
public static class ToastProtocol
{
    /// <summary>注册进 <c>HKCU\Software\Classes</c> 的那个协议名。</summary>
    public const string Scheme = "pushbox";

    public const string OpenVerb = "conv";

    public const string ReadVerb = "read";

    /// <summary>前缀，注册表里那个 `URL:xxx` 默认值和 argv 匹配都用它。</summary>
    public static string Prefix => Scheme + ":";

    public static string UriOf(ToastTarget target)
    {
        var verb = target.Kind == ToastTargetKind.MarkRead ? ReadVerb : OpenVerb;
        return $"{Prefix}{verb}/{Seg(target.ChannelNo)}/{Seg(target.SenderId)}";
    }

    public static string OpenUri(string channelNo, string senderId) =>
        UriOf(new ToastTarget(ToastTargetKind.OpenConversation, channelNo, senderId));

    public static string ReadUri(string channelNo, string senderId) =>
        UriOf(new ToastTarget(ToastTargetKind.MarkRead, channelNo, senderId));

    /// <summary>解一条 URI。段数不对、动词不认识、解码后有一边是空白，都回 null 而不是抛——
    /// 调用方拿到的是"用户点了一条我们读不懂的通知"，那就什么都不做。</summary>
    public static ToastTarget? Parse(string? raw)
    {
        if (raw == null) return null;
        var text = raw.Trim();
        if (!text.StartsWith(Prefix, StringComparison.OrdinalIgnoreCase)) return null;

        var parts = text[Prefix.Length..].Split('/', StringSplitOptions.None);
        if (parts.Length != 3) return null;
        var channel = Unseg(parts[1]);
        var sender = Unseg(parts[2]);
        if (string.IsNullOrWhiteSpace(channel) || string.IsNullOrWhiteSpace(sender)) return null;

        var kind = parts[0].ToLowerInvariant() switch
        {
            OpenVerb => ToastTargetKind.OpenConversation,
            ReadVerb => ToastTargetKind.MarkRead,
            _ => (ToastTargetKind?)null
        };
        return kind is null ? null : new ToastTarget(kind.Value, channel, sender);
    }

    /// <summary>从 argv 里挑出那一条通知带来的目标：shell 可能把 URI 整条传成一个参数，
    /// 也可能被引号拆成几段，所以逐个试而不是只看 args[1]。</summary>
    public static ToastTarget? FromArgs(IEnumerable<string>? args)
    {
        if (args == null) return null;
        foreach (var arg in args)
        {
            var target = Parse(arg);
            if (target != null) return target;
        }

        return null;
    }

    /// <summary>协议处理器那条 <c>shell\open\command</c> 的默认值。exe 路径和 <c>%1</c> 都要各自加引号：
    /// 前者可能带空格（`C:\Program Files\...`），后者里的 URI 可能带 <code>&amp;</code>，
    /// 不加引号 shell 会在 <code>&amp;</code> 处把参数截断。</summary>
    public static string HandlerCommand(string exePath) => $"\"{exePath}\" \"%1\"";

    private static string Seg(string value) => Uri.EscapeDataString(value);

    private static string Unseg(string value)
    {
        try
        {
            return Uri.UnescapeDataString(value);
        }
        catch (UriFormatException)
        {
            // 半截的 %xx 转义（用户手改过通知参数）：按字面读，别把整条通知判成坏数据
            return value;
        }
    }
}
