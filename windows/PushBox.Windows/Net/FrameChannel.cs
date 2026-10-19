using System;
using System.Security.Cryptography;
using PushBox.Windows.Crypto;

namespace PushBox.Windows.Net;

/// <summary>
/// §4.3 receive-side state for one connection epoch: the session key plus the next frame index.
///
/// The server builds a fresh channel per connection and per re-kx, counting from 0, so this side must
/// too — reusing a channel across a reconnect would mark every frame as a replay. That is also why the
/// stateful channel replaces W3's one-shot codec: the key is derived once at kx, never per envelope.
///
/// Java parity: core/crypto/FrameChannel.
/// </summary>
public sealed class FrameChannel
{
    private readonly byte[] _sessionKey;
    private long _recvExpected;

    /// <summary>K = HKDF-SHA256(shared, salt=hello nonce, info="pushbox-frame-v1", 32).</summary>
    public static byte[] SessionKey(byte[] sharedSecret, byte[] helloNonce) =>
        HkdfSha256.DeriveSessionKey(sharedSecret, helloNonce);

    public FrameChannel(byte[] sessionKey)
    {
        if (sessionKey.Length != 32) throw new ArgumentException("§4.3 会话密钥必须是 32 字节");
        _sessionKey = (byte[])sessionKey.Clone();
    }

    /// <summary>Next frame index this channel expects; 0 right after a kx.</summary>
    public long RecvExpected => _recvExpected;

    /// <summary>Frame indexes must arrive contiguous — a jump means the connection was reused or replayed (§4.3).</summary>
    public bool Accepts(long frameIndex) => frameIndex == _recvExpected;

    /// <summary>
    /// null = tag check failed (tampered frame, wrong index, or wrong key). The caller drops the
    /// frame instead of throwing, because Java's AesGcm.open does the same.
    /// </summary>
    public byte[]? Open(long frameIndex, byte[] ciphertextWithTag)
    {
        byte[] plaintext;
        try
        {
            plaintext = AesGcmCipher.Decrypt(_sessionKey, frameIndex, ciphertextWithTag);
        }
        catch (CryptographicException)
        {
            return null;
        }
        catch (ArgumentException)
        {
            return null;
        }

        if (frameIndex == _recvExpected) _recvExpected = frameIndex + 1;
        return plaintext;
    }
}
