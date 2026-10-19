using System;
using System.Text;
using System.Text.Json;
using Org.BouncyCastle.Crypto.Digests;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Parameters;
using Xunit;

namespace PushBox.Crypto.Tests;

public class HkdfSessionKeyTest
{
    [Fact]
    public void HKDF_SessionKey_MatchesVector()
    {
        // Read vectors.json
        var vectorsPath = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "testvectors", "vectors.json");
        var json = File.ReadAllText(vectorsPath);
        using var doc = JsonDocument.Parse(json);
        var inputs = doc.RootElement.GetProperty("inputs");
        var outputs = doc.RootElement.GetProperty("outputs");
        var algorithm = doc.RootElement.GetProperty("algorithm");

        // Parse inputs
        var sharedSecretHex = outputs.GetProperty("shared_secret_hex").GetString()!;
        var nonceHex = inputs.GetProperty("nonce").GetString()!;
        var expectedSessionKeyHex = outputs.GetProperty("session_key_hex").GetString()!;

        var sharedSecret = HexToBytes(sharedSecretHex);
        var salt = HexToBytes(nonceHex); // salt = nonce per §4.3
        var expectedSessionKey = HexToBytes(expectedSessionKeyHex);

        // KDF info string from algorithm.kdf field: "pushbox-frame-v1"
        var kdfInfo = algorithm.GetProperty("kdf").GetString()!;
        // Extract info from "HKDF-SHA256(salt=nonce, info=pushbox-frame-v1, len=32)"
        var infoStart = kdfInfo.IndexOf("info=") + 5;
        var infoEnd = kdfInfo.IndexOf(',', infoStart);
        var infoString = kdfInfo.Substring(infoStart, infoEnd - infoStart);
        var infoBytes = Encoding.UTF8.GetBytes(infoString);

        // BouncyCastle HKDF
        var sessionKey = DeriveHkdfSha256(sharedSecret, salt, infoBytes, 32);

        Assert.Equal(expectedSessionKey, sessionKey);
    }

    private static byte[] DeriveHkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outputLength)
    {
        var hkdf = new HkdfBytesGenerator(new Sha256Digest());
        hkdf.Init(new HkdfParameters(ikm, salt, info));
        var output = new byte[outputLength];
        hkdf.GenerateBytes(output, 0, outputLength);
        return output;
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
