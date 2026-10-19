using System;
using System.Text;
using Org.BouncyCastle.Crypto.Digests;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Parameters;

namespace PushBox.Windows.Crypto;

/// <summary>
/// HKDF-SHA256 key derivation wrapper.
/// </summary>
public static class HkdfSha256
{
    /// <summary>
    /// Derive output key material using HKDF-SHA256 (RFC 5869).
    /// </summary>
    /// <param name="ikm">Input keying material</param>
    /// <param name="salt">Salt (can be empty)</param>
    /// <param name="info">Context/info string</param>
    /// <param name="outputLength">Desired output length in bytes</param>
    public static byte[] Derive(byte[] ikm, byte[] salt, byte[] info, int outputLength)
    {
        var hkdf = new HkdfBytesGenerator(new Sha256Digest());
        hkdf.Init(new HkdfParameters(ikm, salt, info));
        var output = new byte[outputLength];
        hkdf.GenerateBytes(output, 0, outputLength);
        return output;
    }

    /// <summary>
    /// Convenience overload: derive session key from shared secret with fixed info string.
    /// Per §4.3: info = "pushbox-frame-v1", salt = nonce.
    /// </summary>
    public static byte[] DeriveSessionKey(byte[] sharedSecret, byte[] nonce)
    {
        var info = Encoding.UTF8.GetBytes("pushbox-frame-v1");
        return Derive(sharedSecret, nonce, info, 32);
    }
}
