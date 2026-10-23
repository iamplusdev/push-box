using System;
using System.Globalization;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using PushBox.Windows.Crypto;
using PushBox.Windows.Net;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W3b: REST responses are a one-round sealed box, opened with the device's static enc key.
/// </summary>
public class RestSealTest
{
    private static readonly JsonElement Vectors = Load();

    [Fact]
    public void OpenRevealsRegisterPayload()
    {
        var box = Vectors.GetProperty("sealed_box");
        var expected = JsonDocument.Parse(box.GetProperty("plaintext_json").GetString()!).RootElement;

        using var opened = RestSeal.Open(box.GetProperty("envelope_json").GetString()!, DeviceEncPriv());
        var actual = opened.RootElement;

        Assert.Equal(expected.GetProperty("device_id").GetString(), actual.GetProperty("device_id").GetString());
        Assert.Equal(expected.GetProperty("token").GetString(), actual.GetProperty("token").GetString());
        Assert.Equal(expected.GetProperty("recovery_code").GetString(), actual.GetProperty("recovery_code").GetString());
        Assert.Equal(expected.GetProperty("group_id").GetString(), actual.GetProperty("group_id").GetString());
    }

    [Fact]
    public void SaltIsRawSha256OfEphPublicKey()
    {
        var box = Vectors.GetProperty("sealed_box");
        var ephPub = B64Url.Decode(box.GetProperty("eph_pub_b64").GetString()!);
        var salt = B64Url.Decode(box.GetProperty("salt_b64").GetString()!);

        Assert.Equal(32, salt.Length);
        Assert.Equal(SHA256.HashData(ephPub), salt);
    }

    [Fact]
    public void SessionKeyMatchesVector()
    {
        var box = Vectors.GetProperty("sealed_box");
        var ephPub = B64Url.Decode(box.GetProperty("eph_pub_b64").GetString()!);
        var salt = B64Url.Decode(box.GetProperty("salt_b64").GetString()!);

        var shared = X25519Kex.Agree(DeviceEncPriv(), ephPub);
        var key = HkdfSha256.DeriveSessionKey(shared, salt);

        Assert.Equal(box.GetProperty("session_key_hex").GetString()!.ToLowerInvariant(),
            Convert.ToHexString(key).ToLowerInvariant());
    }

    [Fact]
    public void TamperedDataThrows()
    {
        var box = Vectors.GetProperty("sealed_box");
        var envelope = JsonDocument.Parse(box.GetProperty("envelope_json").GetString()!).RootElement;
        var data = B64Url.Decode(envelope.GetProperty("data").GetString()!);
        data[0] ^= 0x01;

        var tampered = JsonSerializer.Serialize(new
        {
            v = envelope.GetProperty("v").GetInt32(),
            eph_pub = envelope.GetProperty("eph_pub").GetString(),
            salt = envelope.GetProperty("salt").GetString(),
            data = B64Url.Encode(data),
            n = envelope.GetProperty("n").GetInt64()
        });

        var ex = Record.Exception(() => RestSeal.Open(tampered, DeviceEncPriv()));
        Assert.IsAssignableFrom<CryptographicException>(ex);
    }

    [Fact]
    public void WrongFrameIndexThrows()
    {
        var box = Vectors.GetProperty("sealed_box");
        var envelope = JsonDocument.Parse(box.GetProperty("envelope_json").GetString()!).RootElement;
        Assert.Equal(0L, envelope.GetProperty("n").GetInt64());

        var wrongIndex = JsonSerializer.Serialize(new
        {
            v = envelope.GetProperty("v").GetInt32(),
            eph_pub = envelope.GetProperty("eph_pub").GetString(),
            salt = envelope.GetProperty("salt").GetString(),
            data = envelope.GetProperty("data").GetString(),
            n = 1L
        });

        var ex = Record.Exception(() => RestSeal.Open(wrongIndex, DeviceEncPriv()));
        Assert.IsAssignableFrom<CryptographicException>(ex);
    }

    private static byte[] DeviceEncPriv() => HexToBytes(
        Vectors.GetProperty("inputs").GetProperty("device_enc_priv").GetString()!);

    private static JsonElement Load()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "..", "contracts", "testvectors", "vectors.json");
        return JsonDocument.Parse(File.ReadAllText(path)).RootElement;
    }

    private static byte[] HexToBytes(string hex)
    {
        var bytes = new byte[hex.Length / 2];
        for (var i = 0; i < bytes.Length; i++)
        {
            bytes[i] = byte.Parse(hex.AsSpan(i * 2, 2), NumberStyles.HexNumber, CultureInfo.InvariantCulture);
        }
        return bytes;
    }
}
