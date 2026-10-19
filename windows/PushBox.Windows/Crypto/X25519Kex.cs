using System;
using Org.BouncyCastle.Crypto.Agreement;
using Org.BouncyCastle.Crypto.Parameters;

namespace PushBox.Windows.Crypto;

/// <summary>
/// X25519 key exchange wrapper using BouncyCastle.
/// </summary>
public static class X25519Kex
{
    /// <summary>
    /// Compute shared secret from local private key and remote public key.
    /// Both keys are 32-byte seeds/raw keys.
    /// </summary>
    public static byte[] Agree(byte[] localPrivKey, byte[] remotePubKey)
    {
        if (localPrivKey.Length != 32 || remotePubKey.Length != 32)
            throw new ArgumentException("X25519 keys must be 32 bytes");

        var localPriv = new X25519PrivateKeyParameters(localPrivKey, 0);
        var remotePub = new X25519PublicKeyParameters(remotePubKey, 0);

        var agreement = new X25519Agreement();
        agreement.Init(localPriv);
        var sharedSecret = new byte[32];
        agreement.CalculateAgreement(remotePub, sharedSecret, 0);
        return sharedSecret;
    }

    /// <summary>
    /// Derive public key from 32-byte private seed.
    /// </summary>
    public static byte[] DerivePublic(byte[] privateKey)
    {
        if (privateKey.Length != 32)
            throw new ArgumentException("X25519 private key must be 32 bytes");

        var priv = new X25519PrivateKeyParameters(privateKey, 0);
        return priv.GeneratePublicKey().GetEncoded();
    }
}
