using System;
using System.Diagnostics;
using System.Net.Http;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Crypto;
using PushBox.Windows.Data;
using PushBox.Windows.Net;

namespace PushBox.Windows.Sync;

/// <summary>
/// What a live connection hands up to W8's SyncEngine: decrypted, bound frames and one TOFU pin.
/// Keeping it to two members is deliberate — §14 says message bodies must never be logged, so the
/// engine is the only place that decides what to do with a frame, and the connection decides nothing.
/// </summary>
public interface IPushObserver
{
    Task OnFrameAsync(Frame frame, CancellationToken ct);

    /// <summary>First contact with this server (§4.3): the engine persists the fingerprint and every
    /// later connection refuses a different one.</summary>
    void OnPinServerFingerprint(string fingerprint);
}

/// <summary>Why a connection ended. The reason string is all §8.1 lets us keep (§14: no bodies).</summary>
public sealed record PushResult(string Reason)
{
    /// <summary>Revoked device or dead token: reconnecting is pointless, the user must re-enroll (§5.1).</summary>
    public bool AuthenticationRejected => Reason.StartsWith("auth_fail", StringComparison.Ordinal);
}

/// <summary>
/// §5.3 downlink client, one connection per RunAsync: hello → auth → kx → {t:"enc"} frames.
///
/// Reconnect and backoff are W8's job (Android splits it the same way: net/PushConnection.kt vs
/// sync/SyncEngine.kt); this class only has to get the handshake right and refuse a forged one —
/// a mismatched fingerprint or a bad kx signature ends the connection instead of falling back to
/// anything readable (§12 P3 acceptance ④).
/// </summary>
public sealed class PushConnection
{
    public const int DefaultHeartbeatSec = 25;

    /// <summary>Server closes us at heartbeat+15s of silence (§5.3); we do the same to it, because a
    /// pulled network cable never delivers the server's close.</summary>
    public static TimeSpan DeadAfterFor(int heartbeatSec) => TimeSpan.FromSeconds(heartbeatSec + 15);

    /// <summary>心跳任务打盹的粒度：只为让"服务端在 hello 里说的间隔"最迟一秒后生效，跟判死无关。</summary>
    private static readonly TimeSpan PingPoll = TimeSpan.FromSeconds(1);

    private readonly DeviceIdentity _identity;
    private readonly IPushTransport _transport;
    private readonly IPushObserver _observer;
    private readonly TimeSpan? _deadAfterOverride;

    private byte[] _helloNonce = Array.Empty<byte>();
    private FrameChannel? _channel;
    private int _heartbeatSec = DefaultHeartbeatSec;
    private long _lastInbound = Stopwatch.GetTimestamp();
    private bool _heartbeatTimedOut;

    /// <summary>一条 socket 同时只能有一个 SendAsync 在飞（ClientWebSocket 的硬限制，第二个当场抛
    /// InvalidOperationException）。心跳任务与"收到 ping 回 pong"分属两条线程，所以出站只有这一道门。
    /// 少了它，一次相位撞上的 ping/pong 会让整条同步循环带着异常收工，而不是断线重连。</summary>
    private readonly SemaphoreSlim _sendGate = new(1, 1);

    public PushConnection(DeviceIdentity identity, IPushTransport transport, IPushObserver observer,
        TimeSpan? deadAfter = null)
    {
        _identity = identity;
        _transport = transport;
        _observer = observer;
        _deadAfterOverride = deadAfter;
        SinceSeq = identity.LastAppliedSeq;
    }

    /// <summary>§5.3 会话游标：只有 message 帧推进它，media_ready 挂的是同一条消息（§4.5）。</summary>
    public long SinceSeq { get; set; }

    public int HeartbeatSeconds => _heartbeatSec;

    public TimeSpan DeadAfter => _deadAfterOverride ?? DeadAfterFor(_heartbeatSec);

    /// <summary>device_id/token 不进 URL，这里只带 since_seq（§5.3）。</summary>
    public static string WsUrl(string baseUrl, long sinceSeq)
    {
        // 先归一化：裸 host 会被补成 https://，Scheme 必须在补完之后再看，否则换出来的前缀会吃掉一个字符
        var origin = DeviceClient.NormalizeBaseUrl(baseUrl);
        var https = origin.StartsWith("https://", StringComparison.OrdinalIgnoreCase);
        origin = (https ? "wss://" + origin.Substring("https://".Length)
            : "ws://" + origin.Substring("http://".Length));
        return origin + "/ws?since_seq=" + sinceSeq;
    }

    public async Task<PushResult> RunAsync(CancellationToken ct)
    {
        var reason = await ConnectAndReadAsync(ct);
        if (reason != "cancelled")
        {
            try { await _transport.CloseAsync(reason, CancellationToken.None); }
            catch (WebSocketException) { }
            catch (ObjectDisposedException) { }
        }
        return new PushResult(reason);
    }

    private async Task<string> ConnectAndReadAsync(CancellationToken ct)
    {
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(ct);
        Uri url;
        try
        {
            url = new Uri(WsUrl(_identity.ServerBaseUrl, SinceSeq));
        }
        catch (UriFormatException e)
        {
            return "bad_server_url:" + e.Message;
        }

        try
        {
            await _transport.ConnectAsync(url, lifetime.Token);
        }
        catch (OperationCanceledException)
        {
            return "cancelled";
        }
        catch (Exception e) when (e is WebSocketException or HttpRequestException)
        {
            return "connect_failed:" + Describe(e);
        }

        MarkInbound();
        var watchdog = QuietWatchdogAsync(lifetime.Token);
        var heartbeat = HeartbeatSenderAsync(lifetime.Token);
        string reason;
        try
        {
            reason = await ReadLoopAsync(lifetime.Token);
        }
        finally
        {
            lifetime.Cancel();
        }
        await Task.WhenAll(watchdog, heartbeat);
        return reason;
    }

    private async Task<string> ReadLoopAsync(CancellationToken ct)
    {
        while (true)
        {
            string? text;
            try
            {
                text = await _transport.ReceiveAsync(ct);
            }
            catch (Exception e) when (IsReceiveFailure(e))
            {
                return _heartbeatTimedOut ? "heartbeat_timeout"
                    : e is OperationCanceledException ? "cancelled" : "socket_error";
            }

            if (text == null) return "server_closed";
            MarkInbound();

            string? stop;
            try
            {
                stop = await HandleAsync(text, ct);
            }
            catch (Exception e) when (IsReceiveFailure(e))
            {
                return _heartbeatTimedOut ? "heartbeat_timeout" : "socket_error";
            }
            if (stop != null) return stop;
        }
    }

    /// <summary>An aborted socket surfaces as ObjectDisposedException on ClientWebSocket, so callers
    /// need the same answer for it as for a socket error.</summary>
    private static bool IsReceiveFailure(Exception e) =>
        e is OperationCanceledException or WebSocketException or ObjectDisposedException;

    private async Task<string?> HandleAsync(string text, CancellationToken ct)
    {
        JsonDocument doc;
        try
        {
            doc = JsonDocument.Parse(text);
        }
        catch (JsonException)
        {
            return null;
        }

        using (doc)
        {
            var root = doc.RootElement;
            var type = root.TryGetProperty("t", out var t) && t.ValueKind == JsonValueKind.String
                ? t.GetString()
                : null;
            switch (type)
            {
                case FrameTypes.Hello: return await OnHelloAsync(root, ct);
                case FrameTypes.Kx: return await OnKxAsync(root);
                case FrameTypes.Enc: return await OnEncAsync(root, ct);
                case FrameTypes.AuthFail:
                    return "auth_fail:" + (Str(root, "error") ?? "");
                case FrameTypes.Ping:
                    await SendAsync(Wire.Pong(), ct);
                    return null;
                default:
                    return null; // §5.3：未知帧类型忽略而不是崩（前向兼容）
            }
        }
    }

    private async Task<string?> OnHelloAsync(JsonElement root, CancellationToken ct)
    {
        if (FrameParser.Parse(root) is not HelloFrame hello) return "bad_hello_frame";
        var nonce = TryDecode(hello.NonceB64);
        if (nonce is not { Length: 32 }) return "bad_hello_frame";

        _helloNonce = nonce;
        if (hello.HeartbeatSec > 0) _heartbeatSec = hello.HeartbeatSec;

        // hello 已经带指纹，所以凭据先不交出去：等 kx 才发现服务端身份换了，等于多送了一次 token
        if (_identity.PinnedServerFp != null && _identity.PinnedServerFp != hello.ServerPubFp)
            return "server_identity_mismatch";

        var ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var sig = B64Url.Encode(Ed25519Sign.Sign(_identity.SignPrivSeed,
            WsCanonical.Auth(_identity.DeviceId, _helloNonce, ts)));
        await SendAsync(Wire.Auth(_identity.DeviceId, _identity.Token, ts, sig), ct);
        return null;
    }

    private Task<string?> OnKxAsync(JsonElement root)
    {
        if (FrameParser.Parse(root) is not KxFrame kx) return Task.FromResult<string?>("bad_kx_frame");
        if (_helloNonce.Length != 32) return Task.FromResult<string?>("kx_before_hello");

        var serverPub = TryDecode(kx.ServerPubB64);
        var ephPub = TryDecode(kx.EphPubB64);
        var sig = TryDecode(kx.SigB64);
        if (serverPub is not { Length: 32 } || ephPub is not { Length: 32 } || sig is not { Length: 64 })
            return Task.FromResult<string?>("bad_kx_frame");

        var fp = Ed25519Sign.Fingerprint(serverPub);
        if (_identity.PinnedServerFp == null) _observer.OnPinServerFingerprint(fp);
        else if (_identity.PinnedServerFp != fp)
            return Task.FromResult<string?>("server_identity_mismatch");

        if (!Ed25519Sign.Verify(serverPub, WsCanonical.Kx(ephPub, _helloNonce), sig))
            return Task.FromResult<string?>("bad_kx_signature");

        // 每次 kx（含中途换钥的 epoch+1）都新建 channel：帧序号从 0 重新数，跨 epoch 比较会把真帧判成重放
        var shared = X25519Kex.Agree(_identity.EncPrivSeed, ephPub);
        _channel = new FrameChannel(FrameChannel.SessionKey(shared, _helloNonce));
        return Task.FromResult<string?>(null);
    }

    private async Task<string?> OnEncAsync(JsonElement root, CancellationToken ct)
    {
        var channel = _channel;
        if (channel == null) return null; // 握手没完成就来的密文：丢掉，绝不"降级明文继续连"

        if (!root.TryGetProperty("n", out var n) || n.ValueKind != JsonValueKind.Number) return null;
        var data = TryDecode(Str(root, "data") ?? "");
        if (data == null) return "bad_enc_frame";

        var index = n.GetInt64();
        if (!channel.Accepts(index)) return "frame_index_gap";
        var plaintext = channel.Open(index, data);
        if (plaintext == null) return null; // tag 校验失败：篡改帧，丢弃但不拆连接

        Frame? frame;
        try
        {
            using var inner = JsonDocument.Parse(Encoding.UTF8.GetString(plaintext));
            frame = FrameParser.Parse(inner.RootElement);
        }
        catch (JsonException)
        {
            return null;
        }

        if (frame == null) return null;
        if (frame is SimpleFrame simple)
        {
            // 只有心跳留在这里：加密的 ping 回一个明文 pong 就够了（§5.3），pong 是本端自己发的回声。
            // resume_ok 必须转发——它不带正文，只说"重放到此结束"，引擎靠这句把顶栏从"连接中"改成"在线"。
            // 全部 SimpleFrame 一起吞掉的后果是：一个还没发过消息的 Space 永远停在"连接中"。
            if (simple.Kind != FrameTypes.Ping)
            {
                await _observer.OnFrameAsync(frame, ct);
                return null;
            }

            await SendAsync(Wire.Pong(), ct);
            return null;
        }

        if (frame is MessageFrame message) SinceSeq = Math.Max(SinceSeq, message.Seq);
        await _observer.OnFrameAsync(frame, ct);
        return null;
    }

    private async Task QuietWatchdogAsync(CancellationToken ct)
    {
        // 1s 粒度对 40s 判死足够；测试把 DeadAfter 压到毫秒级时用同一段时长轮询
        var slice = DeadAfter < TimeSpan.FromSeconds(1) ? DeadAfter : TimeSpan.FromSeconds(1);
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await Task.Delay(slice, ct);
            }
            catch (OperationCanceledException)
            {
                return;
            }

            if (QuietFor() > DeadAfter)
            {
                _heartbeatTimedOut = true;
                _transport.Abort();
                return;
            }
        }
    }

    /// <summary>
    /// §5.3 客户端心跳：按 hello 给的间隔主动 ping。
    ///
    /// 判死这件事本来就归 <see cref="QuietWatchdogAsync"/>（heartbeat+15s 无息），这里不跟它抢第二个
    /// 截止时间。ping 要办的是另外两件：一是让中间那道 NAT/代理别把这条安静得太久的连接收走，
    /// 二是给对面一个非答不可的理由——链路还好的时候 pong 会把"多久没听到说话"重新归零，
    /// 而链路半开（拔网线那种断法，既不报 close 也没有任何帧进来）的时候它不会，
    /// 于是看护那一条在 40s 上判死、走带 since_seq 的重连。Android 的 keepAlive 同一分工，
    /// 只是那边 OkHttp 的 readTimeout=0 永远不会自己超时，所以它必须自己数心跳。
    /// </summary>
    private async Task HeartbeatSenderAsync(CancellationToken ct)
    {
        // 起跑那一刻 hello 还没到，_heartbeatSec 还是默认值。一次 Task.Delay(25s) 会把这一整条连接
        // 钉在默认节奏上（服务端说"我们这边 5 秒"也没用），所以睡着的时候按 1 秒一小段，每段醒来重读间隔。
        var since = TimeSpan.Zero;
        while (!ct.IsCancellationRequested)
        {
            var interval = TimeSpan.FromSeconds(Math.Max(1, _heartbeatSec));
            var slice = interval < PingPoll ? interval : PingPoll;
            try
            {
                await Task.Delay(slice, ct);
            }
            catch (OperationCanceledException)
            {
                return;
            }

            since += slice;
            if (since < interval) continue;
            since = TimeSpan.Zero;

            try
            {
                await SendAsync(Wire.Ping(), ct);
            }
            catch (Exception e) when (e is OperationCanceledException or WebSocketException
                                      or ObjectDisposedException or InvalidOperationException)
            {
                // 发不出去就是这条链路已经没了：原因由读循环那一条给，这里既不抢它的说法也不往外抛——
                // 从 Task.WhenAll 抛出去等于把整条同步循环钉死，而不是重连一次
                return;
            }
        }
    }

    /// <summary>出站唯一入口：见 <see cref="_sendGate"/>。锁本身不取消排队中的等待者以外的东西，
    /// 所以取消/断线一律照原样把异常交给调用方。</summary>
    private async Task SendAsync(string text, CancellationToken ct)
    {
        await _sendGate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            await _transport.SendAsync(text, ct).ConfigureAwait(false);
        }
        finally
        {
            _sendGate.Release();
        }
    }

    private TimeSpan QuietFor() =>
        TimeSpan.FromSeconds((Stopwatch.GetTimestamp() - _lastInbound) / (double)Stopwatch.Frequency);

    private void MarkInbound() => Interlocked.Exchange(ref _lastInbound, Stopwatch.GetTimestamp());

    private static string? Str(JsonElement root, string name) =>
        root.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.String ? el.GetString() : null;

    private static byte[]? TryDecode(string? b64)
    {
        if (string.IsNullOrEmpty(b64)) return null;
        try
        {
            return B64Url.Decode(b64);
        }
        catch (FormatException)
        {
            return null;
        }
    }

    /// <summary>§11.1：TLS 不受信任时 WebSocketException 的 InnerException 才带真原因。</summary>
    private static string Describe(Exception e) =>
        e.InnerException is { } inner && inner.Message.Length > 0 ? e.Message + "（" + inner.Message + "）" : e.Message;
}
