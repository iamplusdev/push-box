using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using PushBox.Windows.Data;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W4: the same identity must survive a process restart, and no private key may sit on disk in the clear.
/// </summary>
public class IdentityStoreTest : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(),
        "pushbox-identity-test-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public void RoundTripSurvivesRestart()
    {
        var store = new IdentityStore(_dir);
        Assert.False(store.Exists());

        var fresh = IdentityStore.CreateNew("https://pushbox.iamplus.cn");
        var registered = fresh with
        {
            DeviceId = "dev_7f3a",
            Token = "pbk_secret_token_value",
            GroupId = "grp_001",
            PinnedServerFp = "e76604b23bcf585d",
            LastAppliedSeq = 18482L
        };
        store.Save(registered);
        Assert.True(store.Exists());

        // 另一个 store 实例 = 进程重启后重新读
        var reloaded = new IdentityStore(_dir).Load();
        Assert.Equal(registered.DeviceId, reloaded.DeviceId);
        Assert.Equal(registered.Token, reloaded.Token);
        Assert.Equal(registered.GroupId, reloaded.GroupId);
        Assert.Equal(registered.ServerBaseUrl, reloaded.ServerBaseUrl);
        Assert.Equal(registered.PinnedServerFp, reloaded.PinnedServerFp);
        Assert.Equal(18482L, reloaded.LastAppliedSeq);
        Assert.Equal(registered.EncPrivSeed, reloaded.EncPrivSeed);
        Assert.Equal(registered.SignPrivSeed, reloaded.SignPrivSeed);
        // 公钥由种子确定性派生，重开不能变成另一台设备
        Assert.Equal(registered.EncPubB64, reloaded.EncPubB64);
        Assert.Equal(registered.SignPubB64, reloaded.SignPubB64);
    }

    [Fact]
    public void NothingSecretIsPlaintextOnDisk()
    {
        var store = new IdentityStore(_dir);
        var id = IdentityStore.CreateNew("https://pushbox.iamplus.cn") with
        {
            DeviceId = "dev_7f3a",
            Token = "pbk_secret_token_value"
        };
        store.Save(id);

        var raw = File.ReadAllBytes(store.FilePath);
        Assert.DoesNotContain(Convert.ToBase64String(id.EncPrivSeed), Encoding.UTF8.GetString(raw));
        Assert.DoesNotContain(Convert.ToBase64String(id.SignPrivSeed), Encoding.UTF8.GetString(raw));
        Assert.DoesNotContain(Convert.ToHexString(id.EncPrivSeed), Encoding.UTF8.GetString(raw));
        Assert.DoesNotContain("pbk_secret_token_value", Encoding.UTF8.GetString(raw));
        Assert.DoesNotContain(id.Token, Encoding.UTF8.GetString(raw));
        Assert.False(ContainsBytes(raw, id.EncPrivSeed), "enc 私钥种子不能以原始字节出现在文件里");
        Assert.False(ContainsBytes(raw, id.SignPrivSeed), "sign 私钥种子不能以原始字节出现在文件里");
    }

    [Fact]
    public void BlobProtectedWithDifferentEntropyIsRejected()
    {
        var store = new IdentityStore(_dir);
        var id = IdentityStore.CreateNew("https://pushbox.iamplus.cn");
        store.Save(id);

        // 近似"换个账户/换台机器"：DPAPI 附加熵不同 ⇒ 必然解不开，且要给出可读原因
        var forged = ProtectedData.Protect(id.EncPrivSeed,
            Encoding.UTF8.GetBytes("some-other-app"), DataProtectionScope.CurrentUser);
        var text = File.ReadAllText(store.FilePath);
        var start = text.IndexOf("\"enc_priv\":\"", StringComparison.Ordinal) + 12;
        var end = text.IndexOf('"', start);
        File.WriteAllText(store.FilePath,
            text[..start] + Convert.ToBase64String(forged) + text[end..]);

        var ex = Assert.Throws<InvalidDataException>(() => new IdentityStore(_dir).Load());
        Assert.Contains("enc_priv", ex.Message);
    }

    [Fact]
    public void MissingFileSaysRegisterOrJoin()
    {
        var ex = Assert.Throws<FileNotFoundException>(() => new IdentityStore(_dir).Load());
        Assert.Contains("恢复码", ex.Message);
    }

    [Fact]
    public void TruncatedFileIsReportedNotCrashed()
    {
        var store = new IdentityStore(_dir);
        store.Save(IdentityStore.CreateNew("https://pushbox.iamplus.cn"));
        var text = File.ReadAllText(store.FilePath);
        File.WriteAllText(store.FilePath, text[..(text.Length / 2)]);

        Assert.Throws<InvalidDataException>(() => new IdentityStore(_dir).Load());
    }

    [Fact]
    public void ReSaveLeavesNoTempFile()
    {
        var store = new IdentityStore(_dir);
        var id = IdentityStore.CreateNew("https://pushbox.iamplus.cn");
        store.Save(id);
        store.Save(id with { LastAppliedSeq = 7L });

        Assert.Equal(7L, new IdentityStore(_dir).Load().LastAppliedSeq);
        Assert.False(File.Exists(store.FilePath + ".tmp"));
    }

    [Fact]
    public void WrongSeedLengthIsRejectedBeforeWriting()
    {
        var store = new IdentityStore(_dir);
        var bad = IdentityStore.CreateNew("https://pushbox.iamplus.cn");
        Assert.Throws<ArgumentException>(() =>
            store.Save(bad with { EncPrivSeed = RandomNumberGenerator.GetBytes(16) }));
        Assert.False(store.Exists());
    }

    [Fact]
    public void APinnedServerFingerprintIsNeverReplacedSilently()
    {
        var store = new IdentityStore(_dir);
        store.Save(IdentityStore.CreateNew("https://pushbox.iamplus.cn") with { PinnedServerFp = "e766..." });

        // §4.3：换服务端指纹是要人确认的事，悄悄覆盖等于把 TOFU 变成"谁连上算谁"
        var ex = Assert.Throws<InvalidDataException>(() => store.SaveServerPubFp("ffffffff..."));
        Assert.Contains("重新加入", ex.Message);
        Assert.Equal("e766...", new IdentityStore(_dir).Load().PinnedServerFp);

        // 同一个值重复钉：不写盘，也不报错
        store.SaveServerPubFp("e766...");
        File.SetLastWriteTimeUtc(store.FilePath, new DateTime(2000, 1, 1));
        store.SaveServerPubFp("e766...");
        Assert.Equal(new DateTime(2000, 1, 1), File.GetLastWriteTimeUtc(store.FilePath));
    }

    [Fact]
    public void FirstContactIsTheOnlyTimeTheFingerprintIsWritten()
    {
        var store = new IdentityStore(_dir);
        store.Save(IdentityStore.CreateNew("https://pushbox.iamplus.cn"));

        store.SaveServerPubFp("e76604b23bcf585d");
        Assert.Equal("e76604b23bcf585d", new IdentityStore(_dir).Load().PinnedServerFp);
    }

    [Fact]
    public void TheCursorCopyOnlyEverMovesForward()
    {
        var store = new IdentityStore(_dir);
        store.Save(IdentityStore.CreateNew("https://pushbox.iamplus.cn") with { LastAppliedSeq = 18490L });

        // 断开时回写的是副本：库比它新就前进，比它旧（补拉重来、换库）绝不能把 since_seq 退回去，
        // 否则服务端从更早的游标重放，本地已经通知过的消息会再来一遍
        store.SaveLastAppliedSeq(18482L);
        Assert.Equal(18490L, new IdentityStore(_dir).Load().LastAppliedSeq);

        store.SaveLastAppliedSeq(18512L);
        Assert.Equal(18512L, new IdentityStore(_dir).Load().LastAppliedSeq);
    }

    private static bool ContainsBytes(byte[] haystack, byte[] needle)
    {
        for (var i = 0; i <= haystack.Length - needle.Length; i++)
        {
            var hit = true;
            for (var j = 0; j < needle.Length; j++)
            {
                if (haystack[i + j] != needle[j]) { hit = false; break; }
            }
            if (hit) return true;
        }
        return false;
    }

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_dir)) Directory.Delete(_dir, true);
        }
        catch (IOException)
        {
            // 临时目录删不掉不影响结论
        }
    }
}
