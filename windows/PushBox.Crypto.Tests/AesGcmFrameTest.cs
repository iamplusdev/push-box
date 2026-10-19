using System;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Xunit;

namespace PushBox.Crypto.Tests;

public class AesGcmFrameTest
{
    [Fact]
    public void AesGcm_EncryptDecrypt_MatchesVector()
    {
        // Read vectors.json
        var vectorsPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "testvectors", "vectors.json");
        var json = File.ReadAllText(vectorsPath);
        using var doc = JsonDocument.Parse(json);
        var outputs = doc.RootElement.GetProperty("outputs");
        var frame = doc.RootElement.GetProperty("frame");

        // Parse session key (from HKDF output)
        var sessionKeyHex = outputs.GetProperty("session_key_hex").GetString()!;
        var sessionKey = HexToBytes(sessionKeyHex);

        // Frame index and nonce/AAD construction per §4.3
        var frameIndex = frame.GetProperty("frame_index").GetInt64();
        var nonce = BuildNonce(frameIndex);
        var aad = BuildAad(frameIndex);

        // Plaintext JSON to encrypt
        var plaintextJson = frame.GetProperty("plaintext_json").GetString()!;
        var plaintextBytes = Encoding.UTF8.GetBytes(plaintextJson);

        // Expected ciphertext from ciphertext_b64 field (URL-safe base64, includes 16-byte GCM tag)
        var expectedCiphertextB64Url = frame.GetProperty("ciphertext_b64").GetString()!;
        var expectedCiphertextWithTag = FromBase64Url(expectedCiphertextB64Url);

        // Encrypt using .NET built-in AES-GCM
        // .NET AesGcm.Encrypt: ciphertext buffer must be same length as plaintext, tag is separate output
        var ciphertext = new byte[plaintextBytes.Length];
        var tag = new byte[16];
        using (var aesGcm = new AesGcm(sessionKey, 16))
        {
            aesGcm.Encrypt(nonce, plaintextBytes, ciphertext, tag, aad);
        }

        // Combine ciphertext + tag to match expected format
        var combined = new byte[ciphertext.Length + tag.Length];
        Array.Copy(ciphertext, 0, combined, 0, ciphertext.Length);
        Array.Copy(tag, 0, combined, ciphertext.Length, tag.Length);

        Assert.Equal(expectedCiphertextWithTag, combined);

        // Decrypt and verify
        var decryptedPlaintext = new byte[plaintextBytes.Length];
        var receivedTag = new byte[16];
        Array.Copy(combined, ciphertext.Length, receivedTag, 0, 16);
        using (var aesGcm = new AesGcm(sessionKey, 16))
        {
            aesGcm.Decrypt(nonce, combined.AsSpan(0, ciphertext.Length), receivedTag, decryptedPlaintext, aad);
        }

        Assert.Equal(plaintextJson, Encoding.UTF8.GetString(decryptedPlaintext));
    }

    [Fact]
    public void AesGcm_TamperedCiphertext_ShouldFail()
    {
        var vectorsPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "testvectors", "vectors.json");
        var json = File.ReadAllText(vectorsPath);
        using var doc = JsonDocument.Parse(json);
        var outputs = doc.RootElement.GetProperty("outputs");
        var frame = doc.RootElement.GetProperty("frame");

        var sessionKeyHex = outputs.GetProperty("session_key_hex").GetString()!;
        var sessionKey = HexToBytes(sessionKeyHex);
        var frameIndex = frame.GetProperty("frame_index").GetInt64();
        var nonce = BuildNonce(frameIndex);
        var aad = BuildAad(frameIndex);

        var plaintextJson = frame.GetProperty("plaintext_json").GetString()!;
        var plaintextBytes = Encoding.UTF8.GetBytes(plaintextJson);

        // Encrypt first
        var ciphertext = new byte[plaintextBytes.Length];
        var tag = new byte[16];
        using (var aesGcm = new AesGcm(sessionKey, 16))
        {
            aesGcm.Encrypt(nonce, plaintextBytes, ciphertext, tag, aad);
        }

        // Combine and tamper
        var combined = new byte[ciphertext.Length + tag.Length];
        Array.Copy(ciphertext, 0, combined, 0, ciphertext.Length);
        Array.Copy(tag, 0, combined, ciphertext.Length, tag.Length);
        combined[0] ^= 0x01;

        // Decrypt should throw CryptographicException (tag verification fails)
        var decryptedPlaintext = new byte[plaintextBytes.Length];
        var receivedTag = new byte[16];
        Array.Copy(combined, ciphertext.Length, receivedTag, 0, 16);
        
        var exception = Record.Exception(() =>
        {
            using var aesGcm = new AesGcm(sessionKey, 16);
            aesGcm.Decrypt(nonce, combined.AsSpan(0, ciphertext.Length), receivedTag, decryptedPlaintext, aad);
        });
        
        Assert.IsAssignableFrom<CryptographicException>(exception);
    }

    private static byte[] BuildNonce(long frameIndex)
    {
        // nonce = 8B BE(frame_index) + 4B zero
        var nonce = new byte[12];
        for (int i = 7; i >= 0; i--)
        {
            nonce[i] = (byte)(frameIndex & 0xFF);
            frameIndex >>= 8;
        }
        return nonce;
    }

    private static byte[] BuildAad(long frameIndex)
    {
        // AAD = 8B BE(frame_index)
        var aad = new byte[8];
        for (int i = 7; i >= 0; i--)
        {
            aad[i] = (byte)(frameIndex & 0xFF);
            frameIndex >>= 8;
        }
        return aad;
    }

    private static byte[] HexToBytes(string hex)
    {
        if (hex.Length % 2 != 0)
            throw new ArgumentException("Hex string must have even length");

        var bytes = new byte[hex.Length / 2];
        for (var i = 0; i < bytes.Length; i++)
            bytes[i] = Convert.ToByte(hex.Substring(i * 2, 2), 16);
        return bytes;
    }

    private static byte[] FromBase64Url(string base64Url)
    {
        // URL-safe base64 uses - instead of + and _ instead of /
        var base64 = base64Url.Replace('-', '+').Replace('_', '/');
        // Add padding if needed
        switch (base64.Length % 4)
        {
            case 2: base64 += "=="; break;
            case 3: base64 += "="; break;
        }
        return Convert.FromBase64String(base64);
    }
}
