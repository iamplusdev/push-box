using System;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using PushBox.Windows.Crypto;
using PushBox.Windows.Net;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// §4.3 receive-side state, pinned against the Java export: the same kx material must produce the
/// same session key, the frame index must be counted contiguously, and a bad tag must answer null
/// instead of throwing (core/crypto/AesGcm.open does the same).
/// </summary>
public class FrameChannelTest
{
    private static readonly JsonElement Vectors = Support.Vectors;

    [Fact]
    public void VectorKxMaterialDerivesTheVectorSessionKey()
    {
        var deviceEncPriv = Support.Hex(Vectors.GetProperty("inputs").GetProperty("device_enc_priv").GetString()!);
        var serverEphPub = B64Url.Decode(Vectors.GetProperty("outputs").GetProperty("server_eph_pub_b64").GetString()!);

        var key = FrameChannel.SessionKey(X25519Kex.Agree(deviceEncPriv, serverEphPub), Support.VectorNonce());

        Assert.Equal(Vectors.GetProperty("outputs").GetProperty("session_key_hex").GetString(),
            Convert.ToHexString(key).ToLowerInvariant());
    }

    [Fact]
    public void VectorEnvelopeOpensToTheByteExactJavaPlaintext()
    {
        var frame = Vectors.GetProperty("frame");
        var channel = new FrameChannel(Support.VectorSessionKey());
        using var envelope = JsonDocument.Parse(frame.GetProperty("envelope_json").GetString()!);
        var index = envelope.RootElement.GetProperty("n").GetInt64();

        Assert.True(channel.Accepts(index));
        var plaintext = channel.Open(index, B64Url.Decode(envelope.RootElement.GetProperty("data").GetString()!));

        Assert.NotNull(plaintext);
        Assert.Equal(frame.GetProperty("plaintext_json").GetString(), Encoding.UTF8.GetString(plaintext!));
        Assert.Equal(1, channel.RecvExpected);
        Assert.False(channel.Accepts(0));
        Assert.True(channel.Accepts(1));
    }

    [Fact]
    public void TamperedFrameOpensToNullAndDoesNotAdvanceTheCounter()
    {
        var channel = new FrameChannel(Support.VectorSessionKey());

        Assert.Null(Open(channel, Support.TamperWsFrame(Support.SealWsFrame(0, "{\"t\":\"resume_ok\"}"))));
        Assert.Equal(0, channel.RecvExpected);

        // 丢一帧不该把整条连接卡住：同一个序号的好帧照样能接
        Assert.NotNull(Open(channel, Support.SealWsFrame(0, "{\"t\":\"resume_ok\"}")));
        Assert.Equal(1, channel.RecvExpected);
    }

    [Fact]
    public void FrameSealedForAnotherSessionKeyOpensToNull()
    {
        var sealedByOther = Support.SealWsFrame(0, "{\"t\":\"resume_ok\"}", RandomNumberGenerator.GetBytes(32));

        Assert.Null(Open(new FrameChannel(Support.VectorSessionKey()), sealedByOther));
    }

    private static byte[]? Open(FrameChannel channel, string envelopeJson)
    {
        using var doc = JsonDocument.Parse(envelopeJson);
        var root = doc.RootElement;
        return channel.Open(root.GetProperty("n").GetInt64(), B64Url.Decode(root.GetProperty("data").GetString()!));
    }
}
