using System;
using System.Text.Json;
using Org.BouncyCastle.Crypto.Agreement;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Parameters;
using Xunit;

namespace PushBox.Crypto.Tests;

public class X25519KeyExchangeTest
{
    [Fact]
    public void X25519_SharedSecret_MatchesVector()
    {
        // Read vectors.json from repo root
        var vectorsPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "contracts", "testvectors", "vectors.json");
        var json = File.ReadAllText(vectorsPath);
        using var doc = JsonDocument.Parse(json);
        var inputs = doc.RootElement.GetProperty("inputs");
        var outputs = doc.RootElement.GetProperty("outputs");

        var serverEphPrivHex = inputs.GetProperty("server_eph_priv").GetString()!;
        var deviceEncPrivHex = inputs.GetProperty("device_enc_priv").GetString()!;
        var expectedSharedSecretHex = outputs.GetProperty("shared_secret_hex").GetString()!;

        var serverEphPriv = HexToBytes(serverEphPrivHex);
        var deviceEncPriv = HexToBytes(deviceEncPrivHex);
        var expectedSharedSecret = HexToBytes(expectedSharedSecretHex);

        // BouncyCastle X25519: create private key parameters from seeds
        var serverPrivParams = new X25519PrivateKeyParameters(serverEphPriv, 0);
        var devicePrivParams = new X25519PrivateKeyParameters(deviceEncPriv, 0);

        // Derive public keys
        var serverPubParams = serverPrivParams.GeneratePublicKey();
        var devicePubParams = devicePrivParams.GeneratePublicKey();

        // Key agreement: device uses its private key + server's public key
        var agreement = new X25519Agreement();
        agreement.Init(devicePrivParams);
        var sharedSecret = new byte[32];
        agreement.CalculateAgreement(serverPubParams, sharedSecret, 0);

        Assert.Equal(expectedSharedSecret, sharedSecret);
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
}
