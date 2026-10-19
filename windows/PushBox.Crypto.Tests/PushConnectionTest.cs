using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Crypto;
using PushBox.Windows.Data;
using PushBox.Windows.Net;
using PushBox.Windows.Sync;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W6: the §5.3 handshake driven frame by frame against a scripted socket, keyed on the Java export.
/// The negative cases are the point — §12 P3 acceptance ④ says a forged handshake must be refused
/// with no fallback to anything readable, and "no fallback" is only provable by asserting that no
/// frame ever reaches the engine and no token ever leaves the process.
/// </summary>
public class PushConnectionTest
{
    private static readonly JsonElement Vectors = Support.Vectors;

    private static readonly byte[] DeviceSignPub = B64("device_sign_pub_b64");
    private static readonly byte[] ServerEphPub = B64("server_eph_pub_b64");
    private static readonly string ServerIdPub = Vec("outputs", "server_id_pub_b64");
    private static readonly string VectorFp = Vec("outputs", "server_pub_fp");

    [Fact]
    public async Task HelloIsAnsweredWithAnAuthFrameSignedByTheHelloNonce()
    {
        var attempt = await Dial(Support.WsHello(), Support.WsVectorKx());

        Assert.Equal("server_closed", attempt.Result.Reason);
        var auth = JsonDocument.Parse(Assert.Single(attempt.Socket.Sent)).RootElement;
        Assert.Equal(FrameTypes.Auth, auth.GetProperty("t").GetString());
        Assert.Equal("dev_7f3a", auth.GetProperty("device_id").GetString());
        Assert.Equal("pbk_1a2b3c4d5e6f", auth.GetProperty("token").GetString());

        var ts = auth.GetProperty("ts").GetInt64();
        var sig = B64Url.Decode(auth.GetProperty("sig").GetString()!);
        Assert.True(Ed25519Sign.Verify(DeviceSignPub, WsCanonical.Auth("dev_7f3a", Support.VectorNonce(), ts), sig));
    }

    [Fact]
    public void AuthCanonicalAndSignatureMatchTheJavaVector()
    {
        var message = WsCanonical.Auth("dev_7f3a", Support.VectorNonce(), 1_765_500_000_000L);

        Assert.Equal(Vec("signatures", "ws_auth_message"), Encoding.UTF8.GetString(message));
        Assert.True(Ed25519Sign.Verify(DeviceSignPub, message, B64Url.Decode(Vec("signatures", "ws_auth_sig_b64"))));
    }

    [Fact]
    public void KxSignedBytesAndSignatureMatchTheJavaVector()
    {
        var message = WsCanonical.Kx(ServerEphPub, Support.VectorNonce());

        Assert.Equal(Support.Hex(Vec("signatures", "kx_message_hex")), message);
        Assert.True(Ed25519Sign.Verify(B64Url.Decode(ServerIdPub), message,
            B64Url.Decode(Vec("signatures", "kx_sig_b64"))));
    }

    [Fact]
    public async Task ConnectUrlCarriesOnlySinceSeq()
    {
        var attempt = await Dial(Support.WsHello());

        Assert.Equal("ws://127.0.0.1:8081/ws?since_seq=18482", attempt.Socket.ConnectedTo!.ToString());
        Assert.DoesNotContain("pbk_1a2b3c4d5e6f", attempt.Socket.ConnectedTo!.ToString());
        Assert.DoesNotContain("dev_7f3a", attempt.Socket.ConnectedTo!.Query);
    }

    [Theory]
    [InlineData("http://192.168.1.55:8080", 18482, "ws://192.168.1.55:8080/ws?since_seq=18482")]
    [InlineData("https://push.example.cn/", 0, "wss://push.example.cn/ws?since_seq=0")]
    [InlineData("push.example.cn:8443", 7, "wss://push.example.cn:8443/ws?since_seq=7")]
    public void WsUrlSwitchesSchemeFromTheStoredBaseUrl(string baseUrl, long sinceSeq, string expected)
        => Assert.Equal(expected, PushConnection.WsUrl(baseUrl, sinceSeq));

    [Fact]
    public async Task KxOpensTheVectorDownlinkFrameIntoABoundMessageFrame()
    {
        var envelope = Vec("frame", "envelope_json");
        var attempt = await Dial(FreshIdentity(), Support.WsHello(), Support.WsVectorKx(), envelope);

        var msg = Assert.IsType<MessageFrame>(Assert.Single(attempt.Observer.Frames));
        Assert.Equal("01JZKTEST000000000000000", msg.Id);
        Assert.Equal(1L, msg.Seq);
        Assert.Equal(1L, attempt.Connection.SinceSeq);
    }

    [Fact]
    public async Task FirstContactPinsTheFingerprintOfTheKxIdentityKey()
    {
        var attempt = await Dial(FreshIdentity(), Support.WsHello(), Support.WsVectorKx());

        Assert.Equal(VectorFp, Assert.Single(attempt.Observer.Pinned));
        Assert.Empty(attempt.Observer.Frames);
    }

    [Fact]
    public async Task MismatchedHelloFingerprintIsRefusedBeforeTheTokenLeaves()
    {
        var attempt = await Dial(PinnedTo("ffffffffffffffff"), Support.WsHello(), Support.WsVectorKx());

        Assert.Equal("server_identity_mismatch", attempt.Result.Reason);
        Assert.Empty(attempt.Socket.Sent); // auth 帧里就是 token：没验过指纹之前一个字都不发
        Assert.Empty(attempt.Observer.Pinned);
        Assert.Empty(attempt.Observer.Frames);
    }

    [Fact]
    public async Task SwappedServerIdentityKeyIsRefusedAtKx()
    {
        // hello 的指纹是自签的假身份，过了第一道却过不了第二道：kx 里的公钥算不回固定值
        var (seed, _) = ServerIdentity();
        var hello = Support.WsHello(Ed25519Sign.Fingerprint(Ed25519Sign.DerivePublic(seed)));
        var kx = Support.WsKxSignedBy(seed, ServerEphPub, Support.VectorNonce());

        var attempt = await Dial(PinnedTo(VectorFp), hello, kx);

        Assert.Equal("server_identity_mismatch", attempt.Result.Reason);
        Assert.Empty(attempt.Observer.Pinned);
        Assert.Empty(attempt.Observer.Frames);
    }

    [Fact]
    public async Task ForgedKxSignatureIsRefused()
    {
        var (seed, _) = ServerIdentity();
        var forged = B64Url.Encode(Ed25519Sign.Sign(seed, WsCanonical.Kx(ServerEphPub, Support.VectorNonce())));
        var kx = Support.WsKx(ServerIdPub, Vec("outputs", "server_eph_pub_b64"), forged);

        var attempt = await Dial(FreshIdentity(), Support.WsHello(), kx);

        Assert.Equal("bad_kx_signature", attempt.Result.Reason);
        Assert.Empty(attempt.Observer.Frames);
    }

    [Fact]
    public async Task FrameIndexGapEndsTheConnection()
    {
        var attempt = await Dial(Support.WsHello(), Support.WsVectorKx(),
            Support.SealWsFrame(0, Vec("frame", "plaintext_json")),
            Support.SealWsFrame(2, """{"t":"resume_ok"}"""));

        Assert.Equal("frame_index_gap", attempt.Result.Reason);
        Assert.Single(attempt.Observer.Frames);
    }

    [Fact]
    public async Task TamperedFrameIsDroppedWithoutDisconnecting()
    {
        var sealed0 = Support.SealWsFrame(0, Vec("frame", "plaintext_json"));

        var attempt = await Dial(Support.WsHello(), Support.WsVectorKx(),
            Support.TamperWsFrame(sealed0), sealed0);

        Assert.Equal("server_closed", attempt.Result.Reason);
        Assert.IsType<MessageFrame>(Assert.Single(attempt.Observer.Frames));
    }

    [Fact]
    public async Task EncryptedPingIsAnsweredWithPongAndNotForwarded()
    {
        var attempt = await Dial(Support.WsHello(), Support.WsVectorKx(),
            Support.SealWsFrame(0, """{"t":"ping"}"""));

        Assert.Equal(2, attempt.Socket.Sent.Count);
        Assert.Equal("""{"t":"pong"}""", attempt.Socket.Sent[1]);
        Assert.Empty(attempt.Observer.Frames);
    }

    [Fact]
    public async Task PlaintextPingIsAnsweredWithPong()
    {
        var attempt = await Dial(Support.WsHello(), """{"t":"ping"}""");

        Assert.Equal("""{"t":"pong"}""", attempt.Socket.Sent[1]);
        Assert.Empty(attempt.Observer.Frames);
    }

    [Fact]
    public async Task CipherFrameBeforeKxIsNeverTouched()
    {
        // 握手还没走完就收到密文：丢掉。§12 P3 ④ 的"不降级明文"就是这个分支
        var attempt = await Dial(Support.WsHello(), Vec("frame", "envelope_json"));

        Assert.Empty(attempt.Observer.Frames);
        Assert.Equal("server_closed", attempt.Result.Reason);
    }

    [Fact]
    public async Task UnknownInnerFrameTypeIsSkippedNotThrown()
    {
        var attempt = await Dial(Support.WsHello(), Support.WsVectorKx(),
            Support.SealWsFrame(0, """{"t":"hologram","x":1}"""));

        Assert.Empty(attempt.Observer.Frames);
        Assert.Equal("server_closed", attempt.Result.Reason);
    }

    [Fact]
    public async Task ResumeGapIsForwardedForTheRestBackfill()
    {
        var attempt = await Dial(Support.WsHello(), Support.WsVectorKx(),
            Support.SealWsFrame(0, """{"t":"resume_gap","missing_from":18483,"latest_seq":19000}"""));

        var gap = Assert.IsType<ResumeGapFrame>(Assert.Single(attempt.Observer.Frames));
        Assert.Equal(18483L, gap.MissingFrom);
        Assert.Equal(19000L, gap.LatestSeq);
    }

    [Fact]
    public async Task MediaReadyMergesWithoutMovingTheCursor()
    {
        var attempt = await Dial(FreshIdentity(), Support.WsHello(), Support.WsVectorKx(),
            Support.SealWsFrame(0, """{"t":"message","seq":5,"id":"m5","channel_no":"1","sender_id":"ops"}"""),
            Support.SealWsFrame(1, """{"t":"media_ready","id":"m5","seq":5,"status":"ready","media":[{"idx":0,"status":"ready"}]}"""));

        Assert.Equal(2, attempt.Observer.Frames.Count);
        Assert.IsType<MediaReadyFrame>(attempt.Observer.Frames[1]);
        Assert.Equal(5L, attempt.Connection.SinceSeq);
    }

    [Fact]
    public async Task AuthFailIsTerminalBecauseTheDeviceIsGone()
    {
        var attempt = await Dial("""{"t":"auth_fail","error":"设备已吊销"}""");

        Assert.Equal("auth_fail:设备已吊销", attempt.Result.Reason);
        Assert.True(attempt.Result.AuthenticationRejected);
        Assert.Empty(attempt.Socket.Sent);
    }

    [Fact]
    public async Task ReKxWithANewEphemeralKeyRestartsTheFrameCounter()
    {
        var (seed, _) = ServerIdentity();
        var nonce = Support.VectorNonce();
        var hello = Support.WsHello(Ed25519Sign.Fingerprint(Ed25519Sign.DerivePublic(seed)));
        var eph1 = RandomNumberGenerator.GetBytes(32);
        var eph2 = RandomNumberGenerator.GetBytes(32);
        var key1 = SessionKey(eph1, nonce);
        var key2 = SessionKey(eph2, nonce);

        var attempt = await Dial(FreshIdentity(), hello,
            Support.WsKxSignedBy(seed, eph1, nonce),
            Support.SealWsFrame(0, """{"t":"message","seq":4,"id":"m4","channel_no":"1","sender_id":"ops"}""", key1),
            Support.WsKxSignedBy(seed, eph2, nonce, 1),
            // 换钥之后旧钥的帧：序号从 0 重数，旧钥过不了 tag，丢弃而不是断开
            Support.SealWsFrame(0, """{"t":"message","seq":9,"id":"m9","channel_no":"1","sender_id":"ops"}""", key1),
            Support.SealWsFrame(0, """{"t":"message","seq":6,"id":"m6","channel_no":"1","sender_id":"ops"}""", key2));

        Assert.Equal("server_closed", attempt.Result.Reason);
        Assert.Equal(new[] { "m4", "m6" }, attempt.Observer.Frames.Cast<MessageFrame>().Select(f => f.Id));
        Assert.Equal(6L, attempt.Connection.SinceSeq);
    }

    [Fact]
    public async Task QuietSocketDiesOnTheHeartbeatDeadline()
    {
        // 拔网线的形状：服务端再也发不出 ping，本端按 §5.3 的 40s 判死自己断
        var attempt = await Dial(PinnedIdentity(), deadAfter: TimeSpan.FromMilliseconds(150), hangWhenDrained: true,
            frames: new[] { Support.WsHello(), Support.WsVectorKx() });

        Assert.Equal("heartbeat_timeout", attempt.Result.Reason);
        Assert.True(attempt.Socket.Aborted);
        Assert.Equal("heartbeat_timeout", attempt.Socket.ClosedWith);
    }

    [Fact]
    public async Task CancellingTheConnectionStopsTheReadLoop()
    {
        using var cts = new CancellationTokenSource();
        var socket = new FakePushTransport(hangWhenDrained: true);

        var running = new PushConnection(PinnedIdentity(), socket, new CollectingObserver()).RunAsync(cts.Token);
        await Task.Delay(50);
        cts.Cancel();

        var result = await running;
        Assert.Equal("cancelled", result.Reason);
        Assert.False(socket.Aborted);
    }

    [Fact]
    public async Task TheClientSpeaksOnTheHeartbeatTheServerAskedFor()
    {
        // hello 说"我们这边 1 秒一跳"，本端就得真的把 ping 发出去，而不是只等对面开口：
        // 半开的链路（拔网线的形状）既不报 close 也没有任何帧进来，只有"我说了话却没听到回音"
        // 能让 heartbeat+15s 那一条看护有东西可判。 pong 回来会把静默重新归零，链路好着就不会被误判死。
        var attempt = await Dial(PinnedIdentity(), deadAfter: TimeSpan.FromSeconds(1.5), hangWhenDrained: true,
            frames: new[] { Support.WsHello(heartbeatSec: 1), Support.WsVectorKx() });

        Assert.Equal("heartbeat_timeout", attempt.Result.Reason);
        Assert.Contains("{\"t\":\"ping\"}", attempt.Socket.Sent);
    }

    [Fact]
    public void DeadAfterFollowsTheServerHeartbeatPlusFifteenSeconds()
    {
        Assert.Equal(TimeSpan.FromSeconds(40), PushConnection.DeadAfterFor(25));
        Assert.Equal(40, new PushConnection(PinnedIdentity(), new FakePushTransport(), new CollectingObserver()).DeadAfter.TotalSeconds);
    }

    // ---- fixtures --------------------------------------------------------------------------

    private sealed record Attempt(PushConnection Connection, FakePushTransport Socket,
        CollectingObserver Observer, PushResult Result);

    private static async Task<Attempt> Dial(params string[] frames) => await Dial(PinnedIdentity(), frames);

    private static async Task<Attempt> Dial(DeviceIdentity identity, params string[] frames) =>
        await Dial(identity, null, false, frames);

    private static async Task<Attempt> Dial(DeviceIdentity identity, TimeSpan? deadAfter, bool hangWhenDrained,
        string[] frames)
    {
        var socket = new FakePushTransport(hangWhenDrained, frames);
        var observer = new CollectingObserver();
        var connection = new PushConnection(identity, socket, observer, deadAfter);
        var result = await connection.RunAsync(CancellationToken.None);
        return new Attempt(connection, socket, observer, result);
    }

    /// <summary>一台已经固定过服务端指纹、游标落后在 18482 的设备（§5.3 的连接示例）。</summary>
    private static DeviceIdentity PinnedIdentity(long sinceSeq = 18482) =>
        BuildIdentity(VectorFp, sinceSeq);

    /// <summary>首连：本机还没有任何可信的服务端身份公钥，走 TOFU。</summary>
    private static DeviceIdentity FreshIdentity(long sinceSeq = 0) => BuildIdentity(null, sinceSeq);

    private static DeviceIdentity PinnedTo(string fingerprint, long sinceSeq = 0) =>
        BuildIdentity(fingerprint, sinceSeq);

    private static DeviceIdentity BuildIdentity(string? pinnedFp, long sinceSeq) => new(
        "dev_7f3a", InputBytes("device_enc_priv"), InputBytes("device_sign_seed"),
        "pbk_1a2b3c4d5e6f", "grp_001", "http://127.0.0.1:8081", pinnedFp, sinceSeq);

    private static (byte[] Seed, byte[] PublicKey) ServerIdentity()
    {
        var seed = RandomNumberGenerator.GetBytes(32);
        return (seed, Ed25519Sign.DerivePublic(seed));
    }

    private static byte[] SessionKey(byte[] serverEphPub, byte[] nonce) =>
        FrameChannel.SessionKey(X25519Kex.Agree(InputBytes("device_enc_priv"), serverEphPub), nonce);

    private static byte[] InputBytes(string name) => Support.Hex(Vec("inputs", name));

    private static byte[] B64(string name) => B64Url.Decode(Vec("outputs", name));

    private static string Vec(string section, string name) => Support.Str(Vectors.GetProperty(section), name);

    private sealed class CollectingObserver : IPushObserver
    {
        public List<Frame> Frames { get; } = new();
        public List<string> Pinned { get; } = new();

        public Task OnFrameAsync(Frame frame, CancellationToken ct)
        {
            Frames.Add(frame);
            return Task.CompletedTask;
        }

        public void OnPinServerFingerprint(string fingerprint) => Pinned.Add(fingerprint);
    }
}
