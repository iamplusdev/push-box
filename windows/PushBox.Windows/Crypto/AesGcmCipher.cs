using System;
using System.Security.Cryptography;

namespace PushBox.Windows.Crypto;

/// <summary>
/// AES-256-GCM authenticated encryption wrapper.
/// Nonce construction per §4.3: 8B BE(frame_index) + 4B zero.
/// AAD = 8B BE(frame_index).
/// </summary>
public static class AesGcmCipher
{
    private const int TagSize = 16;

    /// <summary>
    /// Encrypt plaintext with AES-256-GCM.
    /// Returns ciphertext + 16-byte tag concatenated.
    /// </summary>
    public static byte[] Encrypt(byte[] key, long frameIndex, byte[] plaintext)
    {
        if (key.Length != 32)
            throw new ArgumentException("AES-256 key must be 32 bytes");

        var nonce = BuildNonce(frameIndex);
        var aad = BuildAad(frameIndex);

        var ciphertext = new byte[plaintext.Length];
        var tag = new byte[TagSize];

        using var aesGcm = new AesGcm(key, TagSize);
        aesGcm.Encrypt(nonce, plaintext, ciphertext, tag, aad);

        // Concatenate ciphertext + tag
        var result = new byte[ciphertext.Length + tag.Length];
        Array.Copy(ciphertext, 0, result, 0, ciphertext.Length);
        Array.Copy(tag, 0, result, ciphertext.Length, tag.Length);
        return result;
    }

    /// <summary>
    /// Decrypt ciphertext + tag with AES-256-GCM.
    /// Throws CryptographicException if tag verification fails.
    /// </summary>
    public static byte[] Decrypt(byte[] key, long frameIndex, byte[] ciphertextWithTag)
    {
        if (key.Length != 32)
            throw new ArgumentException("AES-256 key must be 32 bytes");
        if (ciphertextWithTag.Length < TagSize)
            throw new ArgumentException("Ciphertext too short");

        var nonce = BuildNonce(frameIndex);
        var aad = BuildAad(frameIndex);

        var ctLen = ciphertextWithTag.Length - TagSize;
        var ciphertext = new byte[ctLen];
        var tag = new byte[TagSize];

        Array.Copy(ciphertextWithTag, 0, ciphertext, 0, ctLen);
        Array.Copy(ciphertextWithTag, ctLen, tag, 0, TagSize);

        var plaintext = new byte[ctLen];
        using var aesGcm = new AesGcm(key, TagSize);
        aesGcm.Decrypt(nonce, ciphertext, tag, plaintext, aad);

        return plaintext;
    }

    private static byte[] BuildNonce(long frameIndex)
    {
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
        var aad = new byte[8];
        for (int i = 7; i >= 0; i--)
        {
            aad[i] = (byte)(frameIndex & 0xFF);
            frameIndex >>= 8;
        }
        return aad;
    }
}
