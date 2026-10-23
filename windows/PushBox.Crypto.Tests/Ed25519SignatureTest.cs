using System;
using System.Text;
using System.Text.Json;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;
using Xunit;

namespace PushBox.Crypto.Tests;

public class Ed25519SignatureTest
{
    [Fact]
    public void Ed25519_WsAuthSignature_MatchesVector()
    {
        var vectorsPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "contracts", "testvectors", "vectors.json");
        var json = File.ReadAllText(vectorsPath);
        using var doc = JsonDocument.Parse(json);
        var inputs = doc.RootElement.GetProperty("inputs");
        var signatures = doc.RootElement.GetProperty("signatures");

        var deviceSignSeedHex = inputs.GetProperty("device_sign_seed").GetString()!;
        var deviceSignSeed = HexToBytes(deviceSignSeedHex);

        var wsAuthMessage = signatures.GetProperty("ws_auth_message").GetString()!;
        var messageBytes = Encoding.UTF8.GetBytes(wsAuthMessage);

        var expectedSigB64Url = signatures.GetProperty("ws_auth_sig_b64").GetString()!;
        var expectedSig = FromBase64Url(expectedSigB64Url);

        // BouncyCastle Ed25519
        var signer = new Ed25519Signer();
        var privateKey = new Ed25519PrivateKeyParameters(deviceSignSeed, 0);
        signer.Init(true, privateKey);
        signer.BlockUpdate(messageBytes, 0, messageBytes.Length);
        var signature = signer.GenerateSignature();

        Assert.Equal(expectedSig, signature);

        // Verify with public key
        var publicKey = privateKey.GeneratePublicKey();
        var verifier = new Ed25519Signer();
        verifier.Init(false, publicKey);
        verifier.BlockUpdate(messageBytes, 0, messageBytes.Length);
        var isValid = verifier.VerifySignature(signature);
        Assert.True(isValid);
    }

    [Fact]
    public void Ed25519_KxSignature_MatchesVector()
    {
        var vectorsPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "contracts", "testvectors", "vectors.json");
        var json = File.ReadAllText(vectorsPath);
        using var doc = JsonDocument.Parse(json);
        var signatures = doc.RootElement.GetProperty("signatures");

        // kx_sig is signed by server_id key (fixed test seed: 00112233...ff)
        // not by device_sign_seed
        var serverIdSeedHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        var serverIdSeed = HexToBytes(serverIdSeedHex);

        var kxMessageHex = signatures.GetProperty("kx_message_hex").GetString()!;
        var kxMessage = HexToBytes(kxMessageHex);

        var expectedSigB64Url = signatures.GetProperty("kx_sig_b64").GetString()!;
        var expectedSig = FromBase64Url(expectedSigB64Url);

        var signer = new Ed25519Signer();
        var privateKey = new Ed25519PrivateKeyParameters(serverIdSeed, 0);
        signer.Init(true, privateKey);
        signer.BlockUpdate(kxMessage, 0, kxMessage.Length);
        var signature = signer.GenerateSignature();

        Assert.Equal(expectedSig, signature);
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
        var base64 = base64Url.Replace('-', '+').Replace('_', '/');
        switch (base64.Length % 4)
        {
            case 2: base64 += "=="; break;
            case 3: base64 += "="; break;
        }
        return Convert.FromBase64String(base64);
    }
}
