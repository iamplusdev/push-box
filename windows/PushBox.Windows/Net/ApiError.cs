using System;
using System.Globalization;
using System.Net;
using System.Text.Json;

namespace PushBox.Windows.Net;

/// <summary>
/// §5.1 error body {ok,error,hint} turned into one readable sentence.
/// Android still shows the raw "HTTP 409: {json}"; the PC acceptance (W5) asks for a human refusal
/// instead — "第 6 台设备被拒并给出可读提示" — so the code→话术 table lives here and every
/// endpoint shares it. `hint` is server-authored wording and never echoes the request body (§4.4),
/// which is why it is safe to put into a message box.
/// </summary>
public sealed class PushBoxApiException : Exception
{
    public PushBoxApiException(HttpStatusCode status, string code, string hint, long? retryAfterSeconds = null)
        : base(ApiErrorText.Describe(status, code, hint, retryAfterSeconds))
    {
        Status = status;
        Code = code;
        Hint = hint;
        RetryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatusCode Status { get; }
    public string Code { get; }
    public string Hint { get; }
    public long? RetryAfterSeconds { get; }

    /// <summary>ts 出窗口是整片签名接口一起 401，不是偶发（§5.2 canonical 的两个翻车点之一）。</summary>
    public bool IsClockSkew => Code == "unauthorized" && Hint.Contains("clock_skew", StringComparison.Ordinal);

    /// <summary>Parse the error body; anything unrecognisable (a reverse proxy's HTML page) degrades
    /// to a status-derived code rather than throwing away the only clue we have.</summary>
    public static PushBoxApiException FromResponse(HttpStatusCode status, string body,
        string? retryAfterHeader)
    {
        var code = "";
        var hint = "";
        TryReadFields(body, ref code, ref hint);
        if (code.Length == 0) code = CodeForStatus(status);

        long? retryAfter = null;
        if (long.TryParse(retryAfterHeader, NumberStyles.None, CultureInfo.InvariantCulture, out var fromHeader))
        {
            retryAfter = fromHeader;
        }
        else if (long.TryParse(hint, NumberStyles.None, CultureInfo.InvariantCulture, out var fromHint))
        {
            retryAfter = fromHint;
        }

        return new PushBoxApiException(status, code, hint, retryAfter);
    }

    private static void TryReadFields(string body, ref string code, ref string hint)
    {
        if (body.Length == 0) return;
        try
        {
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return;
            if (root.TryGetProperty("error", out var e) && e.ValueKind == JsonValueKind.String) code = e.GetString() ?? "";
            if (root.TryGetProperty("hint", out var h) && h.ValueKind == JsonValueKind.String) hint = h.GetString() ?? "";
        }
        catch (JsonException)
        {
            // 不是 JSON：交给 CodeForStatus，正文原文不进消息也不进日志
        }
    }

    private static string CodeForStatus(HttpStatusCode status) => (int)status switch
    {
        400 => "invalid_param",
        401 => "unauthorized",
        403 => "forbidden",
        404 => "not_found",
        409 => "conflict",
        429 => "rate_limited",
        >= 500 => "server_error",
        _ => "unexpected_response"
    };
}

/// <summary>No HTTP status at all: DNS, TLS, timeout or connection reset.</summary>
public sealed class PushBoxTransportException : Exception
{
    public PushBoxTransportException(string serverUrl, string reason, Exception? inner = null)
        : base($"连不上 {serverUrl}：{reason}", inner)
    {
        ServerUrl = serverUrl;
    }

    public string ServerUrl { get; }
}

/// <summary>The code→话术 table itself, kept separate so a UI layer can render it without an exception.</summary>
public static class ApiErrorText
{
    public static string Describe(HttpStatusCode status, string code, string hint, long? retryAfterSeconds)
    {
        return code switch
        {
            "device_limit_reached" =>
                "这个个人空间已经绑满 5 台设备了（设计上限）。请先在手机的「设置 → 设备与同步」里吊销一台，"
                + "或者把这台电脑加入另一个空间。",

            "invalid_credential" =>
                "恢复码不正确。服务端不会区分「这个码不存在」和「这个码写错了」，所以请回到手机的"
                + "「设置 → 设备信息」里重新复制一次，注意别漏掉首尾字符。",

            "invalid_param" =>
                "服务端不接受这个请求：" + (string.IsNullOrWhiteSpace(hint) ? "请检查设备名称与恢复码。" : hint),

            "rate_limited" =>
                $"尝试太频繁，请 {retryAfterSeconds ?? 60} 秒后再试。",

            "unauthorized" => hint.Contains("clock_skew", StringComparison.Ordinal)
                ? "这台电脑的系统时间与服务器相差超过 5 分钟。签名带的是本机时间，所以所有需要签名的接口会一起失败——"
                  + "请先校准 Windows 时间（设置 → 时间和区域 → 自动同步）再重试。"
                : hint.Contains("bad_signature", StringComparison.Ordinal)
                    ? "本机签名与服务器校验不一致：这台设备可能已被吊销，或身份文件被改过。"
                      + "可以在手机上重新授权，或用恢复码重新加入一次。"
                    : "设备凭据无效或已被吊销。请在手机的「设备与同步」里确认这台电脑还在列表内。",

            "channel_locked" =>
                "该频道因连续鉴权失败被临时锁定，稍后再试或在 App 里重置频道密钥。",

            "too_large" =>
                "请求内容超过服务端允许的大小。",

            "not_found" => (int)status == 404
                ? "服务器上没有这个接口（404）。请确认地址填的是 PushBox 服务端本身，"
                  + "例如 https://push.iamplus.cn，而不是它的某个页面。"
                : "没有找到对应的" + (string.IsNullOrWhiteSpace(hint) ? "资源" : hint) + "。",

            "server_error" =>
                $"服务端内部错误（{(int)status}）。这是服务端的问题，不是这台电脑的问题，可以稍后重试。",

            "invalid_response" =>
                "服务器的回包不符合协议：" + (string.IsNullOrWhiteSpace(hint)
                    ? "请确认服务端版本与这台客户端匹配。"
                    : hint),

            "unexpected_response" =>
                $"服务器返回了没有预期的状态码 {(int)status}"
                + (string.IsNullOrWhiteSpace(hint) ? "。" : $"：{hint}"),

            _ => $"服务器返回 {(int)status} {code}" + (string.IsNullOrWhiteSpace(hint) ? "。" : $"：{hint}")
        };
    }
}
