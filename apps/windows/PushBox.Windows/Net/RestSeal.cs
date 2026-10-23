using System;
using System.Text;
using System.Text.Json;
using PushBox.Windows.Crypto;

namespace PushBox.Windows.Net;

/// <summary>
/// §5.2 sealed REST response: {v, eph_pub, salt, data, n}.
/// It is a different box from the WS envelope — opened with the device's static enc private key,
/// because register/join responses carry the token and recovery code before any WS session key exists.
/// Here salt is the raw sha256 of eph_pub (the WS path salts with the hello nonce).
/// </summary>
public static class RestSeal
{
    /// <summary>
    /// Open a one-round sealed REST response and return the inner JSON.
    /// </summary>
    public static JsonDocument Open(string envelopeJson, byte[] deviceEncPrivKey)
    {
        if (deviceEncPrivKey.Length != 32)
            throw new ArgumentException("Device encryption key must be 32 bytes");

        using var envelope = JsonDocument.Parse(envelopeJson);
        var root = envelope.RootElement;

        var ephPub = B64Url.Decode(Required(root, "eph_pub"));
        var salt = B64Url.Decode(Required(root, "salt"));
        var ciphertextWithTag = B64Url.Decode(Required(root, "data"));
        // A one-round box never advances its counter, but read n instead of assuming it.
        var frameIndex = root.TryGetProperty("n", out var n) ? n.GetInt64() : 0L;

        var sharedSecret = X25519Kex.Agree(deviceEncPrivKey, ephPub);
        var sessionKey = HkdfSha256.DeriveSessionKey(sharedSecret, salt);
        var plaintext = AesGcmCipher.Decrypt(sessionKey, frameIndex, ciphertextWithTag);

        return JsonDocument.Parse(Encoding.UTF8.GetString(plaintext));
    }

    private static string Required(JsonElement root, string name)
    {
        var value = root.TryGetProperty(name, out var element) ? element.GetString() : null;
        return value ?? throw new InvalidOperationException($"Sealed box is missing '{name}'");
    }
}
