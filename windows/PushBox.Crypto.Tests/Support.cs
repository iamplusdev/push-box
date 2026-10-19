using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Crypto;
using PushBox.Windows.Net;
using PushBox.Windows.Sync;

namespace PushBox.Crypto.Tests;

/// <summary>Shared fixtures: the vectors file, a scripted HttpMessageHandler, and C#-side sealers.</summary>
internal static class Support
{
    public static readonly JsonElement Vectors = Load();

    public static JsonElement Load()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "testvectors", "vectors.json");
        return JsonDocument.Parse(File.ReadAllText(path)).RootElement;
    }

    public static byte[] Hex(string hex) =>
        Enumerable.Range(0, hex.Length / 2).Select(i => Convert.ToByte(hex.Substring(i * 2, 2), 16)).ToArray();

    public static string Str(JsonElement root, string name) => root.GetProperty(name).GetString()!;

    /// <summary>
    /// 按 §5.3 的字段名拼 JSON 再走 <see cref="FrameParser"/> 造帧（与 DeviceStoreTest 里那个私有 Msg 同一个理由）：
    /// 通知这一层消费的是解析后的记录，直接 new record 会把"字段名绑定正确"这半条保证让出去。
    /// </summary>
    public static MessageFrame Message(string id = "01JTESTMESSAGE000000000000",
        string channel = "83271504", string sender = "grafana", string? name = "Grafana",
        string? title = null, string? summary = null, string? content = "磁盘告警 /mnt 已用 91%",
        string level = "info", bool notify = true, long seq = 18481L, string? avatar = null)
    {
        var fields = new Dictionary<string, object>
        {
            ["t"] = "message",
            ["seq"] = seq,
            ["id"] = id,
            ["channel_no"] = channel,
            ["sender_id"] = sender,
            ["type"] = "text",
            ["level"] = level,
            ["notify"] = notify,
            ["created_at"] = 1_765_500_000_000L + seq,
        };
        if (name != null) fields["sender_name"] = name;
        if (title != null) fields["title"] = title;
        if (summary != null) fields["summary"] = summary;
        if (content != null) fields["content"] = content;
        if (avatar != null) fields["sender_avatar"] = avatar;
        return (MessageFrame)FrameParser.Parse(JsonSerializer.Serialize(fields))!;
    }

    /// <summary>Seal with the vector's fixed response key pair, so the byte-exact Java envelope is
    /// reproducible from C# too.</summary>
    public static string SealRestResponse(string payloadJson)
    {
        var box = Vectors.GetProperty("sealed_box");
        var ephPriv = Hex(Str(Vectors.GetProperty("inputs"), "response_eph_priv"));
        var ephPub = B64Url.Decode(Str(box, "eph_pub_b64"));
        var deviceEncPub = B64Url.Decode(Str(Vectors.GetProperty("outputs"), "device_enc_pub_b64"));
        return Envelope(payloadJson, ephPriv, ephPub, deviceEncPub);
    }

    /// <summary>Seal to whatever enc_pubkey the request carried — exactly what DeviceGuard.seal does,
    /// and the only way a test can answer a flow that minted its own key pair.</summary>
    public static string SealForRequest(string payloadJson, RecordedRequest request)
    {
        var ephPriv = RandomNumberGenerator.GetBytes(32);
        return Envelope(payloadJson, ephPriv, X25519Kex.DerivePublic(ephPriv),
            B64Url.Decode(Str(request.BodyJson(), "enc_pubkey")));
    }

    public static Func<RecordedRequest, HttpResponseMessage> EnrollResponder(string payloadJson) =>
        request => Reply(HttpStatusCode.OK, SealForRequest(payloadJson, request));

    private static string Envelope(string payloadJson, byte[] ephPriv, byte[] ephPub, byte[] deviceEncPub)
    {
        var salt = SHA256.HashData(ephPub);
        var key = HkdfSha256.DeriveSessionKey(X25519Kex.Agree(ephPriv, deviceEncPub), salt);
        var data = AesGcmCipher.Encrypt(key, 0L, Encoding.UTF8.GetBytes(payloadJson));
        return "{\"v\":1,\"eph_pub\":\"" + B64Url.Encode(ephPub) + "\",\"salt\":\"" + B64Url.Encode(salt)
            + "\",\"data\":\"" + B64Url.Encode(data) + "\",\"n\":0}";
    }

    public static HttpResponseMessage Reply(HttpStatusCode code, string body,
        string contentType = "application/json", string? retryAfter = null)
    {
        var message = new HttpResponseMessage(code)
        {
            Content = new StringContent(body, Encoding.UTF8, contentType)
        };
        if (retryAfter != null) message.Headers.Add("Retry-After", retryAfter);
        return message;
    }

    public static string ErrorBody(string code, string hint = "") =>
        "{\"ok\":false,\"error\":\"" + code + "\",\"hint\":\"" + hint + "\"}";

    // ---- §5.3 WebSocket fixtures ---------------------------------------------------------------
    // Everything below is keyed on the vector session key / exported signature, so a C# handshake
    // test replays the exact bytes the Java side produced.

    public static byte[] VectorNonce() => Hex(Str(Vectors.GetProperty("inputs"), "nonce"));

    public static byte[] VectorSessionKey() => Hex(Str(Vectors.GetProperty("outputs"), "session_key_hex"));

    public static string WsHello(string? serverPubFp = null, string? nonceB64 = null, int heartbeatSec = 25)
    {
        return "{\"t\":\"hello\",\"server_time\":1765500000000,\"heartbeat\":" + heartbeatSec
            + ",\"server_pub_fp\":\"" + (serverPubFp ?? Str(Vectors.GetProperty("outputs"), "server_pub_fp"))
            + "\",\"nonce\":\"" + (nonceB64 ?? B64Url.Encode(VectorNonce())) + "\"}";
    }

    public static string WsKx(string serverPubB64, string ephPubB64, string sigB64, int epoch = 0)
    {
        return "{\"t\":\"kx\",\"server_pub\":\"" + serverPubB64 + "\",\"eph_pub\":\"" + ephPubB64
            + "\",\"sig\":\"" + sigB64 + "\",\"epoch\":" + epoch + "}";
    }

    /// <summary>The exported kx frame: server static identity key + its signature over eph_pub‖nonce.</summary>
    public static string WsVectorKx() => WsKx(
        Str(Vectors.GetProperty("outputs"), "server_id_pub_b64"),
        Str(Vectors.GetProperty("outputs"), "server_eph_pub_b64"),
        Str(Vectors.GetProperty("signatures"), "kx_sig_b64"));

    /// <summary>A kx signed by a key the caller holds — how the tests stage both a forged handshake and a re-key.</summary>
    public static string WsKxSignedBy(byte[] serverIdSeed, byte[] ephPub, byte[] helloNonce, int epoch = 0)
    {
        return WsKx(B64Url.Encode(Ed25519Sign.DerivePublic(serverIdSeed)), B64Url.Encode(ephPub),
            B64Url.Encode(Ed25519Sign.Sign(serverIdSeed, WsCanonical.Kx(ephPub, helloNonce))), epoch);
    }

    /// <summary>Downlink envelope exactly as Envelope.seal writes it: {t:"enc", n, data}.</summary>
    public static string SealWsFrame(long frameIndex, string plaintextJson, byte[]? sessionKey = null)
    {
        var data = AesGcmCipher.Encrypt(sessionKey ?? VectorSessionKey(), frameIndex,
            Encoding.UTF8.GetBytes(plaintextJson));
        return "{\"t\":\"enc\",\"n\":" + frameIndex + ",\"data\":\"" + B64Url.Encode(data) + "\"}";
    }

    /// <summary>Damage the ciphertext of a sealed envelope so the GCM tag check fails.</summary>
    public static string TamperWsFrame(string envelopeJson)
    {
        using var doc = JsonDocument.Parse(envelopeJson);
        var data = B64Url.Decode(Str(doc.RootElement, "data"));
        var index = doc.RootElement.GetProperty("n").GetInt64();
        data[^1] ^= 0xFF;
        return "{\"t\":\"enc\",\"n\":" + index + ",\"data\":\"" + B64Url.Encode(data) + "\"}";
    }
}

/// <summary>A request as the code under test actually put on the wire.</summary>
internal sealed class RecordedRequest
{
    public RecordedRequest(HttpRequestMessage message, byte[] body)
    {
        Message = message;
        Body = body;
    }

    public HttpRequestMessage Message { get; }
    public byte[] Body { get; }
    public string BodyText => Encoding.UTF8.GetString(Body);
    public string Path => Message.RequestUri!.AbsolutePath;

    /// <summary>带前导 '?' 的原始查询串：§5.2 的 canonical 把它算进签名，签名用例要看的就是这一串。</summary>
    public string Query => Message.RequestUri!.Query;

    public string Method => Message.Method.Method;

    public JsonElement BodyJson() => JsonDocument.Parse(BodyText).RootElement;

    public bool HasBodyField(string name) => BodyJson().TryGetProperty(name, out _);

    public bool HasHeader(string name) =>
        Message.Headers.Any(h => string.Equals(h.Key, name, StringComparison.OrdinalIgnoreCase));

    /// <summary>§5.2 的五个签名头要按发出去的原文逐条核对，取不到就当空串。</summary>
    public string Header(string name) =>
        Message.Headers.TryGetValues(name, out var values) ? values.First() : "";
}

/// <summary>
/// Scripted §5.3 socket, no network: answers a fixed list of text frames and then either EOF or
/// silence. Shared by W6 (one connection's handshake) and W8 (the reconnect loop), which is why it
/// lives here rather than inside one test class.
/// </summary>
internal sealed class FakePushTransport : IPushTransport
{
    private readonly Queue<string> _inbound = new();
    private readonly bool _hangWhenDrained;
    private readonly TaskCompletionSource<string?> _hang = new(TaskCreationOptions.RunContinuationsAsynchronously);

    public FakePushTransport(bool hangWhenDrained = false, string[]? frames = null)
    {
        _hangWhenDrained = hangWhenDrained;
        foreach (var frame in frames ?? Array.Empty<string>()) _inbound.Enqueue(frame);
    }

    public List<string> Sent { get; } = new();
    public Uri? ConnectedTo { get; private set; }
    public string? ClosedWith { get; private set; }
    public bool Aborted { get; private set; }

    public Task ConnectAsync(Uri url, CancellationToken ct)
    {
        ConnectedTo = url;
        return Task.CompletedTask;
    }

    public Task<string?> ReceiveAsync(CancellationToken ct)
    {
        if (_inbound.Count > 0) return Task.FromResult<string?>(_inbound.Dequeue());
        if (!_hangWhenDrained) return Task.FromResult<string?>(null);
        ct.Register(() => _hang.TrySetCanceled(ct));
        return _hang.Task;
    }

    public Task SendAsync(string text, CancellationToken ct)
    {
        Sent.Add(text);
        return Task.CompletedTask;
    }

    public Task CloseAsync(string reason, CancellationToken ct)
    {
        ClosedWith = reason;
        return Task.CompletedTask;
    }

    public void Abort()
    {
        Aborted = true;
        _hang.TrySetException(new WebSocketException("心跳判死，socket 被本端撕掉"));
    }
}

/// <summary>Scripted transport: no socket, so the W5 tests can assert both the request bytes and how
/// each §5.1 error body turns into one readable sentence.</summary>
internal sealed class FakeHandler : HttpMessageHandler
{
    private readonly Func<RecordedRequest, CancellationToken, Task<HttpResponseMessage>> _responder;

    public FakeHandler(Func<RecordedRequest, HttpResponseMessage> responder)
        : this((request, _) => Task.FromResult(responder(request)))
    {
    }

    /// <summary>Async responder: the W8 cases where a page must not exist until the test hands it over,
    /// so the engine can be caught blocking (or not blocking) the §5.3 read loop.</summary>
    public FakeHandler(Func<RecordedRequest, CancellationToken, Task<HttpResponseMessage>> responder)
        => _responder = responder;

    public List<RecordedRequest> Requests { get; } = new();

    public RecordedRequest Single()
    {
        Assert.Single(Requests);
        return Requests[0];
    }

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        var body = request.Content?.ReadAsByteArrayAsync().GetAwaiter().GetResult() ?? Array.Empty<byte>();
        var recorded = new RecordedRequest(request, body);
        Requests.Add(recorded);
        return _responder(recorded, ct);
    }
}

/// <summary>Fails like a dead network: HttpRequestException is what HttpClient surfaces for DNS,
/// refused connections and TLS, with the real cause as InnerException.</summary>
internal sealed class ThrowingHandler : HttpMessageHandler
{
    private readonly Exception _error;

    public ThrowingHandler(Exception error) => _error = error;

    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        => Task.FromException<HttpResponseMessage>(_error);
}

/// <summary>Never answers, so HttpClient's own timeout fires.</summary>
internal sealed class HangingHandler : HttpMessageHandler
{
    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
    {
        await Task.Delay(TimeSpan.FromSeconds(30), ct).ConfigureAwait(false);
        return Support.Reply(HttpStatusCode.OK, "{}");
    }
}
