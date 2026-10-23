using System;
using System.Globalization;
using System.Text;

namespace PushBox.Windows.Crypto;

/// <summary>
/// The two §4.3/§5.3 Ed25519 signature targets, byte-identical to core/crypto/SignatureTargets.
/// Split apart from RestCanonical on purpose: restAuth has 8 lines and hashes a body, these two don't.
/// </summary>
public static class WsCanonical
{
    public const string WsAuthV1 = "pushbox-ws-auth-v1";

    /// <summary>kx: sig = Ed25519(server_identity_priv, eph_pub ‖ hello_nonce) — 64 raw bytes, no separator.</summary>
    public static byte[] Kx(byte[] ephPub, byte[] helloNonce)
    {
        var message = new byte[ephPub.Length + helloNonce.Length];
        Array.Copy(ephPub, 0, message, 0, ephPub.Length);
        Array.Copy(helloNonce, 0, message, ephPub.Length, helloNonce.Length);
        return message;
    }

    /// <summary>
    /// auth: 4 LF-joined lines, no trailing newline. The challenge is the server's hello nonce, so
    /// a captured auth frame cannot be replayed on another connection (§5.3).
    /// </summary>
    public static byte[] Auth(string deviceId, byte[] serverNonce, long tsMillis)
    {
        return Encoding.UTF8.GetBytes(string.Join('\n',
            WsAuthV1,
            deviceId,
            B64Url.Encode(serverNonce),
            tsMillis.ToString(CultureInfo.InvariantCulture)));
    }
}
