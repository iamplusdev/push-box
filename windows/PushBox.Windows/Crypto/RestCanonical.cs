using System;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace PushBox.Windows.Crypto;

/// <summary>
/// §5.2 restAuth canonical: 8 LF-joined lines, byte-identical to
/// core/crypto/SignatureTargets.restAuth. No trailing newline.
/// </summary>
public static class RestCanonical
{
    public const string RestAuthV1 = "pushbox-rest-auth-v1";

    /// <summary>
    /// Build the bytes covered by the X-PB-Sig Ed25519 signature.
    /// </summary>
    /// <param name="query">Raw query string exactly as it will be sent; null means an empty line</param>
    /// <param name="requestBody">Raw body bytes; null or empty hashes sha256("")</param>
    /// <param name="tsMillis">epoch millis, must be inside the server's ±300s window</param>
    /// <param name="clientNonce">16 random bytes generated per request</param>
    public static byte[] Build(string deviceId, string method, string path, string? query,
        byte[]? requestBody, long tsMillis, byte[] clientNonce)
    {
        var canonical = string.Join('\n',
            RestAuthV1,
            deviceId,
            method.ToUpperInvariant(),
            path,
            query ?? string.Empty,
            Sha256Hex(requestBody ?? Array.Empty<byte>()),
            tsMillis.ToString(CultureInfo.InvariantCulture),
            B64Url.Encode(clientNonce));
        return Encoding.UTF8.GetBytes(canonical);
    }

    /// <summary>Lowercase hex, the same form the Java side uses.</summary>
    public static string Sha256Hex(byte[] data)
    {
        return Convert.ToHexString(SHA256.HashData(data)).ToLowerInvariant();
    }
}
