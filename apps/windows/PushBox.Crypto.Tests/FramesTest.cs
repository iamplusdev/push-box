using System.Text;
using System.Text.Json;
using PushBox.Windows.Crypto;
using PushBox.Windows.Net;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W3c: every §5.3 frame type binds to non-empty fields, both tombstone shapes parse,
/// and an unknown type is skipped rather than crashing the read loop.
/// </summary>
public class FramesTest
{
    private static readonly JsonElement Vectors = Support.Vectors;

    [Fact]
    public void DecryptedVectorFrameBindsEveryField()
    {
        // W6 的 FrameChannel 解出明文帧，W3c 的绑定必须接得住
        var frame = Vectors.GetProperty("frame");
        var channel = new FrameChannel(Support.VectorSessionKey());
        using var envelope = JsonDocument.Parse(frame.GetProperty("envelope_json").GetString()!);
        var envelopeRoot = envelope.RootElement;
        var plaintext = channel.Open(envelopeRoot.GetProperty("n").GetInt64(),
            B64Url.Decode(envelopeRoot.GetProperty("data").GetString()!));
        var msg = Assert.IsType<MessageFrame>(FrameParser.Parse(Encoding.UTF8.GetString(plaintext!)));

        Assert.Equal(1L, msg.Seq);
        Assert.Equal("83271504", msg.ChannelNo);
        Assert.Equal("01JZKTEST000000000000000", msg.Id);
        Assert.Equal("grafana", msg.SenderId);
        Assert.Equal("markdown", msg.Type);
        Assert.Equal("[严重] CPU 使用率 > 90%", msg.Title);
        Assert.Equal("摘要", msg.Summary);
        Assert.Equal("正文 **粗体**", msg.Content);
        Assert.Equal("error", msg.Level);
        Assert.Equal("https://example.com/d/abc", msg.Url);
        Assert.Equal("Grafana 告警", msg.DisplayName);
        Assert.Equal(1_765_500_000_000L, msg.CreatedAt);
        Assert.True(msg.Notify);
        Assert.False(msg.Truncated);
        // 实时帧不带消息级媒体状态，终态由 media_ready 回填（§4.5）
        Assert.Null(msg.MediaStatus);

        var slot = Assert.Single(msg.Media);
        Assert.Equal(0, slot.Idx);
        Assert.Equal("ready", slot.Status);
        Assert.True(slot.Ready);
        Assert.Equal("media/01JZK/0", slot.Key);
        Assert.Equal("image/png", slot.Mime);
        Assert.Equal(1024L, slot.Size);
        Assert.Equal(800, slot.W!.Value);
        Assert.Equal(600, slot.H!.Value);
        Assert.Equal("media/01JZK/0.thumb", slot.Thumb);
    }

    [Fact]
    public void HistoryItemShapeCarriesMediaStatus()
    {
        // §5.2 的历史项与 §5.3 的实时帧字段同名，只有 media_status 是历史项独有的（W7 入库要用）
        Assert.Null(((MessageFrame)FrameParser.Parse(Json(
            """{"t":"message","seq":1,"id":"m1","channel_no":"83271504","sender_id":"grafana"}"""))!).MediaStatus);

        // 历史项没有 t 字段，补拉时逐项走 ReadMessage（§5.2 的 messages[] 就是这个形状）
        var history = FrameParser.ReadMessage(Json("""
            {"seq":18482,"id":"m18482","channel_no":"83271504","sender_id":"grafana",
             "sender_name":"Grafana 告警","type":"markdown","content":"正文",
             "media_status":"partial","media":[{"idx":0,"status":"ready","key":"media/m18482/0"}],
             "created_at":1765500000000,"deleted_at":null}
            """));
        Assert.Equal("partial", history.MediaStatus);
        Assert.Equal("正文", history.Content);
        Assert.Equal(18482L, history.Seq);
        Assert.True(history.Notify);
    }

    [Fact]
    public void HandshakeFramesBind()
    {
        var outputs = Vectors.GetProperty("outputs");
        var fp = outputs.GetProperty("server_pub_fp").GetString()!;
        // hello.nonce 与 §4.4 ws_auth 的挑战值是同一份 32 字节
        var nonceB64 = Vectors.GetProperty("signatures").GetProperty("ws_auth_message")
            .GetString()!.Split('\n')[2];

        var hello = Assert.IsType<HelloFrame>(FrameParser.Parse(
            Json($$"""{"t":"hello","server_time":1765500000000,"heartbeat":25,"server_pub_fp":"{{fp}}","nonce":"{{nonceB64}}"}""")));
        Assert.Equal(25, hello.HeartbeatSec);
        Assert.Equal(fp, hello.ServerPubFp);
        Assert.Equal(nonceB64, hello.NonceB64);
        Assert.Equal(32, B64Url.Decode(hello.NonceB64).Length);

        var kx = Assert.IsType<KxFrame>(FrameParser.Parse(Json(
            $$"""{"t":"kx","server_pub":"{{outputs.GetProperty("server_id_pub_b64").GetString()}}","eph_pub":"{{outputs.GetProperty("server_eph_pub_b64").GetString()}}","sig":"i5g","epoch":1}""")));
        Assert.Equal(outputs.GetProperty("server_eph_pub_b64").GetString(), kx.EphPubB64);
        Assert.Equal(1, kx.Epoch);
        // kx 带的身份公钥必须能算回 hello 的指纹，否则 TOFU 无从固定（这一条同时钉住 C# 的 Fingerprint）
        Assert.Equal(fp, Ed25519Sign.Fingerprint(B64Url.Decode(kx.ServerPubB64)));
    }

    [Fact]
    public void DefaultsMatchTheSpec()
    {
        // §5.3：notify 缺省为 true、type 缺省 text、level 缺省 info
        var msg = (MessageFrame)FrameParser.Parse(Json(
            """{"t":"message","seq":7,"id":"m7","channel_no":"1","sender_id":"ops","sender_name":"  "}"""))!;
        Assert.True(msg.Notify);
        Assert.Equal("text", msg.Type);
        Assert.Equal("info", msg.Level);
        Assert.Equal("ops", msg.DisplayName);
        Assert.Empty(msg.Media);
    }

    [Fact]
    public void MediaFramesKeepTheirType()
    {
        var ready = Assert.IsType<MediaReadyFrame>(FrameParser.Parse(Json(
            """{"t":"media_ready","id":"01JZK","seq":9,"status":"ready","media":[{"idx":1,"status":"failed"}]}""")));
        Assert.Equal("01JZK", ready.Id);
        Assert.Equal(9L, ready.Seq);
        Assert.False(ready.Failed);
        Assert.Equal("failed", Assert.Single(ready.Media).Status);

        var failed = Assert.IsType<MediaReadyFrame>(FrameParser.Parse(Json(
            """{"t":"media_failed","id":"01JZK","seq":9,"status":"failed"}""")));
        Assert.True(failed.Failed);
        Assert.Equal(FrameTypes.MediaFailed, failed.Kind);
    }

    /// <summary>服务端合并窗口到期后按<b>增量</b>补发 media_ready：三张图的消息可能先来一帧两槽、
    /// 再来一帧只带第三槽。落库的槽数必须按最大槽号算跨度，取 <c>Media.Count</c> 会把九宫格缩成一格。</summary>
    [Fact]
    public void AReissuedMediaFrameCountsSlotsBySpanNotByItsOwnSize()
    {
        var first = Assert.IsType<MediaReadyFrame>(FrameParser.Parse(Json(
            """{"t":"media_ready","id":"01JZK","seq":9,"status":"pending","media":[{"idx":0,"status":"ready"},{"idx":1,"status":"ready"}]}""")));
        Assert.Equal(2, first.SlotSpan);

        var later = Assert.IsType<MediaReadyFrame>(FrameParser.Parse(Json(
            """{"t":"media_ready","id":"01JZK","seq":9,"status":"ready","media":[{"idx":2,"status":"ready"}]}""")));
        Assert.Equal(3, later.SlotSpan);

        // 只有低槽位的补发不能把已知的跨度缩回去（这一半由 DeviceStore 的 MAX 兜住，这里只保证帧自己说得清）
        var lowOnly = Assert.IsType<MediaReadyFrame>(FrameParser.Parse(Json(
            """{"t":"media_ready","id":"01JZK","seq":9,"status":"ready","media":[{"idx":1,"status":"failed"}]}""")));
        Assert.Equal(2, lowOnly.SlotSpan);

        var empty = Assert.IsType<MediaReadyFrame>(FrameParser.Parse(Json(
            """{"t":"media_failed","id":"01JZK","seq":9,"status":"failed"}""")));
        Assert.Equal(0, empty.SlotSpan);
    }

    [Fact]
    public void ReadAndResumeFramesBind()
    {
        var read = Assert.IsType<ReadFrame>(FrameParser.Parse(Json(
            """{"t":"read","channel_no":"83271504","sender_id":"grafana","last_read_seq":18482,"device_id":"dev_pc"}""")));
        Assert.Equal(18482L, read.LastReadSeq);
        Assert.Equal("dev_pc", read.DeviceId);

        var gap = Assert.IsType<ResumeGapFrame>(FrameParser.Parse(Json(
            """{"t":"resume_gap","missing_from":18483,"latest_seq":19000}""")));
        Assert.Equal(18483L, gap.MissingFrom);
        Assert.Equal(19000L, gap.LatestSeq);

        Assert.Equal(FrameTypes.ResumeOk, Assert.IsType<SimpleFrame>(
            FrameParser.Parse(Json("""{"t":"resume_ok"}"""))).Kind);
        Assert.Equal(FrameTypes.Ping, Assert.IsType<SimpleFrame>(
            FrameParser.Parse(Json("""{"t":"ping"}"""))).Kind);
        Assert.Equal(FrameTypes.Pong, Assert.IsType<SimpleFrame>(
            FrameParser.Parse(Json("""{"t":"pong"}"""))).Kind);
        Assert.Equal(25, Assert.IsType<AuthOkFrame>(
            FrameParser.Parse(Json("""{"t":"auth_ok","heartbeat":25}"""))).HeartbeatSec);
        Assert.Equal("bad_signature", Assert.IsType<AuthFailFrame>(
            FrameParser.Parse(Json("""{"t":"auth_fail","error":"bad_signature"}"""))).Error);
    }

    [Fact]
    public void TombstoneBothShapesAreMutuallyExclusive()
    {
        var single = Assert.IsType<TombstoneFrame>(FrameParser.Parse(Json(
            """{"t":"tombstone","id":"01JZK","deleted_at":1765500000000}""")));
        Assert.Equal("01JZK", single.Id);
        Assert.Equal(1765500000000L, single.DeletedAt);
        Assert.False(single.ClearsConversation);

        var scope = Assert.IsType<TombstoneFrame>(FrameParser.Parse(Json(
            """{"t":"tombstone","scope":{"channel_no":"83271504","sender_id":"grafana"},"deleted_at":1}""")));
        Assert.Null(scope.Id);
        Assert.True(scope.ClearsConversation);
        Assert.Equal("83271504", scope.ChannelNo);
        Assert.Equal("grafana", scope.SenderId);
    }

    [Fact]
    public void TombstoneWithoutIdOrScopeClearsNothing()
    {
        var bare = Assert.IsType<TombstoneFrame>(FrameParser.Parse(Json(
            """{"t":"tombstone","deleted_at":1}""")));
        Assert.Null(bare.Id);
        Assert.False(bare.ClearsConversation);
    }

    [Fact]
    public void UnknownFrameTypeIsSkippedNotThrown()
    {
        Assert.Null(FrameParser.Parse(Json("""{"t":"something_new","x":1}""")));
    }

    private static JsonElement Json(string text) => JsonDocument.Parse(text).RootElement;
}
