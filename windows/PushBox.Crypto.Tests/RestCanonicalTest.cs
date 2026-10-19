using System;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.Json;
using PushBox.Windows.Crypto;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W3b: the restAuth canonical must be byte-identical, or every /api/v1 call 401s at once.
/// </summary>
public class RestCanonicalTest
{
    private static readonly JsonElement Vectors = Load();

    [Fact]
    public void GetSearchCanonicalMatchesVector()
    {
        var caseNode = Vectors.GetProperty("rest_auth").GetProperty("get_search");

        var canonical = RestCanonical.Build(
            DeviceId(),
            caseNode.GetProperty("method").GetString()!,
            caseNode.GetProperty("path").GetString()!,
            caseNode.GetProperty("query").GetString(),
            null,
            caseNode.GetProperty("ts").GetInt64(),
            ClientNonce());

        Assert.Equal(caseNode.GetProperty("canonical").GetString(), Encoding.UTF8.GetString(canonical));
    }

    [Fact]
    public void PostBodyCanonicalMatchesVector()
    {
        var caseNode = Vectors.GetProperty("rest_auth").GetProperty("post_channels");
        var body = Encoding.UTF8.GetBytes(caseNode.GetProperty("body").GetString()!);

        var canonical = RestCanonical.Build(
            DeviceId(),
            caseNode.GetProperty("method").GetString()!,
            caseNode.GetProperty("path").GetString()!,
            caseNode.GetProperty("query").GetString(),
            body,
            caseNode.GetProperty("ts").GetInt64(),
            ClientNonce());

        var expected = caseNode.GetProperty("canonical").GetString()!;
        Assert.Equal(expected, Encoding.UTF8.GetString(canonical));
        // 空 query 是一个空行，不是省略这一行
        Assert.Equal(8, expected.Split('\n').Length);
        Assert.DoesNotContain("\n\n\n", expected);
    }

    [Fact]
    public void SignaturesMatchVector()
    {
        var seed = HexToBytes(Vectors.GetProperty("inputs").GetProperty("device_sign_seed").GetString()!);
        foreach (var name in new[] { "get_search", "post_channels" })
        {
            var caseNode = Vectors.GetProperty("rest_auth").GetProperty(name);
            var message = Encoding.UTF8.GetBytes(caseNode.GetProperty("canonical").GetString()!);
            var signature = Ed25519Sign.Sign(seed, message);
            Assert.Equal(caseNode.GetProperty("sig_b64").GetString(), B64Url.Encode(signature));
        }
    }

    [Fact]
    public void EmptyBodyHashesToSha256OfEmptyString()
    {
        Assert.Equal(
            Vectors.GetProperty("rest_auth").GetProperty("empty_body_sha256_hex").GetString(),
            RestCanonical.Sha256Hex(Array.Empty<byte>()));
    }

    [Fact]
    public void ReorderedQueryBreaksSignatureVerification()
    {
        // §5.2 最易翻车点：客户端自己按 Map 重拼 query 就验不过，这里把代价钉在测试里
        var caseNode = Vectors.GetProperty("rest_auth").GetProperty("get_search");
        var query = caseNode.GetProperty("query").GetString()!;
        var reordered = "limit=50&" + query[..query.IndexOf("&limit=", StringComparison.Ordinal)];

        var message = RestCanonical.Build(DeviceId(), "GET", caseNode.GetProperty("path").GetString()!,
            reordered, null, caseNode.GetProperty("ts").GetInt64(), ClientNonce());
        var expected = Encoding.UTF8.GetBytes(caseNode.GetProperty("canonical").GetString()!);

        Assert.False(Ed25519Sign.Verify(
            Ed25519Sign.DerivePublic(SignSeed()), expected,
            Ed25519Sign.Sign(SignSeed(), message)));
    }

    private static string DeviceId() => Vectors.GetProperty("rest_auth").GetProperty("device_id").GetString()!;

    private static byte[] ClientNonce() => B64Url.Decode(
        Vectors.GetProperty("rest_auth").GetProperty("client_nonce_b64").GetString()!);

    private static byte[] SignSeed() => HexToBytes(
        Vectors.GetProperty("inputs").GetProperty("device_sign_seed").GetString()!);

    private static JsonElement Load()
    {
        var path = Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "testvectors", "vectors.json");
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
