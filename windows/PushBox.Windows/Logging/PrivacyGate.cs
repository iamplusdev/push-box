using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;

namespace PushBox.Windows.Logging;

/// <summary>
/// §14 那条底线唯一真正干活的地方：**一条留痕里除了"哪件事 + 哪个码 + 几个数"，什么都进不去**。
///
/// 它不靠"记得别打正文"这种习惯，而是把正文可能进来的每一扇门各自堵死一层，所以顺序无所谓、
/// 漏了一层也还有下一层：
/// 1. <see cref="IsSensitiveKey"/>：属性名本身是正文类的（content/summary/title/q…），整值不要了；
/// 2. <see cref="ScrubValue"/> 的长度闸：单个值超过 <see cref="MaxValueLength"/> 就整值替换。
///    这里是<b>替换而不是截断</b>——留前 120 字仍然是在留正文，而 §8.4 的验收写的是"grep 不到任何片段"；
/// 3. <see cref="ScrubLine"/> 的键值网：`content=…`、`"summary":"…"`、`?q=…` 这种"整包转储"里的敏感键，
///    连 JSON 一起洗；
/// 4. 凭据形状网：≥40 个 base64 字符、≥32 个十六进制字符的连续串一律当凭据（恢复码、API Secret、
///    密封盒的 data/salt、签名都是这个形状），而 msg_id（26 位 ULID）、时间戳、方法名都够不上这个长度；
/// 5. <see cref="ExceptionShapes"/>：**异常只留类型名与堆栈，Message 一个字都不留**。
///    这是 <c>ToastNotifier.LastFailure</c> 从 W13 起就立的口径（§8.2 第 46 条），理由是同一条：
///    Message 里常有 URL、参数乃至正文片段，而类型名和"从哪一行抛出来"已经够定位了。
///
/// 纯函数、无状态、不碰文件，所以这五层每一条都能在 xunit 里单独钉住；真正落盘的装配在 <see cref="AppLog"/>。
/// </summary>
public static class PrivacyGate
{
    /// <summary>单个留痕值允许的最长字符数。超过它的一定不是"一个 id 或一个状态词"。</summary>
    public const int MaxValueLength = 120;

    /// <summary>多长算"凭据形状"：32 字节密钥的 base64 是 44 字符，X25519/Ed25519 公钥与签名是 43 字符。</summary>
    public const int CredentialLength = 40;

    /// <summary>十六进制串的那条下限：§15 的示例 API Secret 是 32 个十六进制字符，够不到 <see cref="CredentialLength"/>。</summary>
    public const int HexCredentialLength = 32;

    /// <summary>被洗掉时留在行里的记号。<b>不含正文、也不含长度以外的信息</b>。</summary>
    public const string Redacted = "<已脱敏>";

    /// <summary>长度闸替换时的记号：带上原长度，是为了让人看出"这里本来有一段正文被挡住了"。</summary>
    public const string RedactedTooLong = "<已脱敏:超长值>";

    /// <summary>形状网替换时的记号。</summary>
    public const string RedactedShape = "<已脱敏:凭据形状>";

    /// <summary>
    /// 属性名/键名黑名单：这些键的<b>值</b>一律不要。分成两组只是为了让注释说清理由——
    /// 前一组是消息内容本身，后一组是凭据与握手材料。
    /// </summary>
    private static readonly string[] SensitiveKeys =
    {
        // 内容类：正文、摘要、标题、snippet、Markdown 原文、用户敲进去的搜索词
        "body", "content", "summary", "title", "text", "snippet", "match", "matches", "markdown",
        "q", "query", "keyword", "search_term",
        // 凭据类：恢复码、API Secret、token、会话密钥、封装后的读图钥、签名与 nonce、信封密文
        "secret", "api_secret", "token", "access_token", "password", "passcode",
        "recovery_code", "recovery", "signature", "ws_auth_sig", "kx_sig", "sig",
        "session_key", "enc_pubkey", "server_pub", "private_key", "priv", "seed", "salt", "data",
        "wrapped_key", "sse_key",
    };

    /// <summary>
    /// 键值网：`key` 前后必须是词边界（否则会咬住 <c>seq</c> 里的 <c>q</c>、<c>title_case</c> 这类），
    /// 值取"引号里的一段"或"到分隔符为止的一段"。分隔符里带 <c>&amp;</c> 与 <c>%</c>：query 串是
    /// <c>?channel_no=…&amp;sender_id=…</c>，`&amp;` 是分隔符而 <c>%</c> 属于转义值本身。
    /// </summary>
    private static readonly Regex KeyValuePattern = BuildKeyValuePattern();

    private static readonly Regex OpaqueRunPattern =
        new("[A-Za-z0-9+/_=-]{" + CredentialLength + ",}", RegexOptions.Compiled);

    private static readonly Regex HexRunPattern =
        new(@"\b[0-9a-fA-F]{" + HexCredentialLength + @",}\b", RegexOptions.Compiled);

    /// <summary>键名判定，忽略大小写与包络用的引号。<see cref="ScrubValue"/> 之外也给富化器用。</summary>
    public static bool IsSensitiveKey(string? name) =>
        name != null && Array.IndexOf(SensitiveKeys, name.Trim().Trim('"', '\'').ToLowerInvariant()) >= 0;

    /// <summary>
    /// 单个值的洗法（富化器与 <see cref="AppLog"/> 的入参都走这里）：黑名单键、超长、形状、内嵌键值，
    /// 四道按这个顺序过一遍——先判长度是因为超长值根本没必要再逐键扫。
    /// </summary>
    public static string ScrubValue(string? value)
    {
        if (string.IsNullOrEmpty(value)) return string.Empty;
        if (value!.Length > MaxValueLength) return RedactedTooLong;
        return ScrubLine(value);
    }

    /// <summary>
    /// 整行/整段的洗法：只扫键值与形状，<b>没有长度规则</b>——一整条留痕本来就可能超过 120 字，
    /// 按长度换掉整行等于把日志洗成空白。换行在这里保留，跨行的堆栈由 <see cref="ExceptionShapes"/> 逐行洗。
    /// </summary>
    public static string ScrubLine(string? line)
    {
        if (string.IsNullOrEmpty(line)) return string.Empty;
        var scrubbed = KeyValuePattern.Replace(line!, match =>
            match.Groups[1].Value + match.Groups[2].Value + Redacted);
        scrubbed = OpaqueRunPattern.Replace(scrubbed, RedactedShape);
        return HexRunPattern.Replace(scrubbed, RedactedShape);
    }

    /// <summary>
    /// 一条异常在日志里的全部形态：<c>外层的类型名 ← 内层的类型名 ← …</c> 加逐行脱敏后的堆栈。
    /// 没有 Message。堆栈里只有方法名与偏移，不含实参，所以这一份是可以安全落盘的定位信息。
    /// </summary>
    public static IReadOnlyList<string> ExceptionShapes(Exception? error)
    {
        var lines = new List<string>();
        for (var e = error; e != null; e = e.InnerException)
        {
            lines.Add(e.GetType().FullName ?? e.GetType().Name);
            var trace = e.StackTrace;
            if (!string.IsNullOrEmpty(trace))
            {
                foreach (var frame in trace!.Split('\n'))
                {
                    var line = frame.TrimEnd('\r');
                    if (line.Trim().Length > 0) lines.Add("  " + ScrubLine(line.Trim()));
                }
            }
        }

        return lines;
    }

    /// <summary>把值里的换行压成一个记号：留痕一行一条，grep 才是逐条的而不是一片糊。</summary>
    public static string OneLine(string? value) =>
        (value ?? string.Empty).Replace("\r\n", "⏎").Replace('\r', '⏎').Replace('\n', '⏎');

    /// <summary>
    /// 键值网一次成型：<c>(^|边界)(键)(收尾引号+冒号/等号)(值)</c>，只留两个捕获组（键、分隔符），值整段消费掉。
    /// 值那三个分支必须包在<b>同一个</b> <c>(?:…)</c> 里：写成 <c>(?:A)|(?:B)|(?:C)</c> 的话 <c>|</c> 是顶层交替，
    /// 于是"<b>任何一行</b>都匹配得上第三支（一路吃到行尾）"，日志被洗成一片 <c>&lt;已脱敏&gt;</c>——
    /// 这条是 <c>AppLogTest</c> 那几条"正常字段要留下"的反向断言当场抓出来的。
    /// 细节：
    /// - 键后面先允许一个引号，否则 <c>"content":"正文"</c> 这种 JSON 转储压根匹配不上（键与冒号之间隔着一个 <c>"</c>）；
    /// - 值带引号时取到引号为止，后面的正常字段照留；
    /// - 值是裸的（不带引号）时<b>一路吃到行尾</b>：<c>content=磁盘告警 /mnt 已用 91%</c> 里空格不是分隔符，
    ///   按空格截断等于把正文的后半留在行里。代价是同一行里跟在它后面的字段会一起被吃掉——
    ///   而一行里既然出现了 <c>content=</c>，那一行本来就是转储，宁可少留信息。
    /// </summary>
    private static Regex BuildKeyValuePattern()
    {
        var keys = string.Join("|", SensitiveKeys);
        return new Regex(
            @"(?<![A-Za-z0-9_])(" + keys + @")(?![A-Za-z0-9_])([""']?\s*[:=]\s*)(?:""[^""\r\n]*""|'[^'\r\n]*'|[^\r\n]*)",
            RegexOptions.Compiled | RegexOptions.IgnoreCase);
    }
}
