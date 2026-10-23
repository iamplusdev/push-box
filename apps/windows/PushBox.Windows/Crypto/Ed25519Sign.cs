using System;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;

namespace PushBox.Windows.Crypto;

/// <summary>
/// Ed25519 signature wrapper using BouncyCastle.
/// </summary>
public static class Ed25519Sign
{
    /// <summary>
    /// Sign a message with Ed25519 private key (32-byte seed).
    /// Returns 64-byte signature.
    /// </summary>
    public static byte[] Sign(byte[] privateKeySeed, byte[] message)
    {
        if (privateKeySeed.Length != 32)
            throw new ArgumentException("Ed25519 private key must be 32 bytes");

        var signer = new Ed25519Signer();
        var privateKey = new Ed25519PrivateKeyParameters(privateKeySeed, 0);
        signer.Init(true, privateKey);
        signer.BlockUpdate(message, 0, message.Length);
        return signer.GenerateSignature();
    }

    /// <summary>
    /// Verify an Ed25519 signature. Bad-sized inputs answer false rather than throwing, the same way
    /// core/crypto/Ed25519.verify does: a handshake frame arrives from the network, and a truncated
    /// key or signature is a rejection, not a crash.
    /// </summary>
    public static bool Verify(byte[] publicKey, byte[] message, byte[] signature)
    {
        if (publicKey.Length != 32 || signature.Length != 64) return false;

        var verifier = new Ed25519Signer();
        var pubKey = new Ed25519PublicKeyParameters(publicKey, 0);
        verifier.Init(false, pubKey);
        verifier.BlockUpdate(message, 0, message.Length);
        return verifier.VerifySignature(signature);
    }

    /// <summary>
    /// Derive public key from 32-byte private seed.
    /// </summary>
    public static byte[] DerivePublic(byte[] privateKeySeed)
    {
        if (privateKeySeed.Length != 32)
            throw new ArgumentException("Ed25519 private key must be 32 bytes");

        var priv = new Ed25519PrivateKeyParameters(privateKeySeed, 0);
        return priv.GeneratePublicKey().GetEncoded();
    }

    /// <summary>
    /// TOFU label (§4.3): first 16 hex chars of sha256(publicKey), the same string register/join
    /// answers as server_pub_fp. Comparing fingerprints is what stops a hand that swaps the kx key.
    /// </summary>
    public static string Fingerprint(byte[] publicKey)
    {
        if (publicKey.Length != 32)
            throw new ArgumentException("Ed25519 public key must be 32 bytes");

        return RestCanonical.Sha256Hex(publicKey)[..16];
    }
}
