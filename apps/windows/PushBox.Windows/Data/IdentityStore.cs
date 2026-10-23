using System;
using System.Collections.Generic;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using PushBox.Windows.Crypto;

namespace PushBox.Windows.Data;

/// <summary>
/// §4.2 identity: the two private seeds never leave the device, the rest is what the server
/// handed back after register/join. RecoveryCode is only set on the device that created the Space
/// (join answers null), and §4.2 treats it as the account, so it is stored sealed like the seeds.
/// </summary>
public sealed record DeviceIdentity(
    string DeviceId,
    byte[] EncPrivSeed,
    byte[] SignPrivSeed,
    string Token,
    string GroupId,
    string ServerBaseUrl,
    string? PinnedServerFp,
    long LastAppliedSeq,
    string? RecoveryCode = null)
{
    /// <summary>X25519 public key used by the §4.3 agreement, base64url as the API wants it.</summary>
    public string EncPubB64 => B64Url.Encode(X25519Kex.DerivePublic(EncPrivSeed));

    /// <summary>Ed25519 public key registered with the server, base64url.</summary>
    public string SignPubB64 => B64Url.Encode(Ed25519Sign.DerivePublic(SignPrivSeed));

    public static byte[] NewSeed() => RandomNumberGenerator.GetBytes(32);
}

/// <summary>
/// Identity on disk under %LOCALAPPDATA%\PushBox\ (§8.1). Seeds and token are DPAPI-sealed to the
/// current Windows user, so a copied file is unreadable from another account or another machine.
///
/// Per-conversation cursors are deliberately not here — §3.3 puts them in the sync_state table (W7).
/// Only one global downlink seq is kept, because W6 reconnects before the database exists.
/// </summary>
public sealed class IdentityStore
{
    /// <summary>DPAPI optional entropy: same-user processes still need this string to open the blobs.</summary>
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("pushbox.identity.v1");

    private readonly string _filePath;

    public IdentityStore(string? directory = null)
    {
        _filePath = Path.Combine(directory ?? DefaultDirectory, "identity.json");
    }

    public static string DefaultDirectory =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PushBox");

    public string FilePath => _filePath;

    public bool Exists() => File.Exists(_filePath);

    /// <summary>Fresh key material for the first-launch screen; token/group are empty until register answers.</summary>
    public static DeviceIdentity CreateNew(string serverBaseUrl) => new(
        "",
        DeviceIdentity.NewSeed(),
        DeviceIdentity.NewSeed(),
        "",
        "",
        serverBaseUrl,
        null,
        0L);

    public DeviceIdentity Load()
    {
        if (!File.Exists(_filePath))
            throw new FileNotFoundException($"本机还没有设备身份：{_filePath}，先走首启注册或用恢复码加入。");

        JsonDocument doc;
        try
        {
            doc = JsonDocument.Parse(File.ReadAllText(_filePath));
        }
        catch (JsonException e)
        {
            throw new InvalidDataException($"身份文件已损坏，无法解析：{_filePath}", e);
        }

        using (doc)
        {
            var root = doc.RootElement;
            return new DeviceIdentity(
                Text(root, "device_id"),
                Open(root, "enc_priv"),
                Open(root, "sign_priv"),
                Encoding.UTF8.GetString(Open(root, "token")),
                Text(root, "group_id"),
                Text(root, "server_base_url"),
                root.TryGetProperty("pinned_server_fp", out var fp) ? fp.GetString() : null,
                root.TryGetProperty("last_applied_seq", out var seq) && seq.ValueKind == JsonValueKind.Number
                    ? seq.GetInt64()
                    : 0L,
                root.TryGetProperty("recovery_code", out _) ? OpenOptional(root, "recovery_code") : null);
        }
    }

    public void Save(DeviceIdentity identity)
    {
        if (identity.EncPrivSeed.Length != 32 || identity.SignPrivSeed.Length != 32)
            throw new ArgumentException("§4.2 的两把私钥种子都必须是 32 字节");

        Directory.CreateDirectory(Path.GetDirectoryName(_filePath)!);
        var fields = new Dictionary<string, object>
        {
            ["v"] = 1,
            ["device_id"] = identity.DeviceId,
            ["group_id"] = identity.GroupId,
            ["server_base_url"] = identity.ServerBaseUrl,
            ["last_applied_seq"] = identity.LastAppliedSeq,
            ["enc_priv"] = Seal(identity.EncPrivSeed),
            ["sign_priv"] = Seal(identity.SignPrivSeed),
            ["token"] = Seal(Encoding.UTF8.GetBytes(identity.Token)),
        };
        if (identity.PinnedServerFp != null) fields["pinned_server_fp"] = identity.PinnedServerFp;
        // 只有创建空间那台设备持有恢复码明文，加入者永远拿不到，所以这一项可缺
        if (identity.RecoveryCode != null) fields["recovery_code"] = Seal(Encoding.UTF8.GetBytes(identity.RecoveryCode));

        // 先写临时文件再替换：中途崩溃不会留下半个把私钥丢掉的身份文件
        File.WriteAllText(_filePath + ".tmp", JsonSerializer.Serialize(fields), new UTF8Encoding(false));
        File.Move(_filePath + ".tmp", _filePath, true);
    }

    /// <summary>
    /// §4.3 TOFU：首次连上某个服务端就把指纹钉在这里，之后每一次连接都比对它——包括那些连 kx
    /// 都没走完的失败连接。只在真正钉下时写盘，重复钉同一个值不动文件。
    /// </summary>
    public void SaveServerPubFp(string fingerprint)
    {
        var current = Load();
        if (current.PinnedServerFp == fingerprint) return;
        if (current.PinnedServerFp != null)
            throw new InvalidDataException(
                "本机已经钉过另一个服务端指纹，绝不会悄悄换掉：请先确认服务器地址，"
                + "再删除身份文件重新加入。");

        Save(current with { PinnedServerFp = fingerprint });
    }

    /// <summary>断开时回写的那一份游标副本（§8.1）。库里 <c>sync_state</c> 的 <see cref="DeviceStore.GroupCursorKey"/>
    /// 行才是权威值，这份只管"下次开库之前就该知道的 since_seq"，所以只增不减。</summary>
    public void SaveLastAppliedSeq(long seq)
    {
        DeviceIdentity current;
        try
        {
            current = Load();
        }
        catch (Exception e) when (e is FileNotFoundException or InvalidDataException)
        {
            // 文件还没落盘或被改坏：这里没有"要更新的那一行"，而游标的权威在库里（SyncEngine 取的是
            // max(文件, 库)），所以跳过这一次回写既不会漏消息，也不会让同步循环在收工那一行死掉——
            // 那份读不出的事实已经由 SyncEngine.LoadIdentity 留过痕了，这里不再重复一次
            return;
        }

        if (seq <= current.LastAppliedSeq) return;
        Save(current with { LastAppliedSeq = seq });
    }

    private static string Seal(byte[] data)
    {
        return Convert.ToBase64String(
            ProtectedData.Protect(data, Entropy, DataProtectionScope.CurrentUser));
    }

    private static byte[] Open(JsonElement root, string name)
    {
        var blob = Text(root, name);
        if (blob.Length == 0)
            throw new InvalidDataException($"身份文件缺少 '{name}'，请重新注册或用恢复码加入。");
        try
        {
            return ProtectedData.Unprotect(Convert.FromBase64String(blob), Entropy,
                DataProtectionScope.CurrentUser);
        }
        catch (CryptographicException e)
        {
            throw new InvalidDataException(
                $"'{name}' 无法用当前 Windows 用户解开（文件是从别的机器或别的账户复制过来的？）。", e);
        }
    }

    /// <summary>Sealed-but-maybe-absent field: only the device that created the Space holds a recovery code.</summary>
    private static string? OpenOptional(JsonElement root, string name)
    {
        if (!root.TryGetProperty(name, out var el) || el.ValueKind != JsonValueKind.String
            || el.GetString() is not { Length: > 0 }) return null;
        return Encoding.UTF8.GetString(Open(root, name));
    }

    private static string Text(JsonElement root, string name)
    {
        return root.TryGetProperty(name, out var el) && el.ValueKind == JsonValueKind.String
            ? el.GetString() ?? ""
            : "";
    }

    /// <summary>§7.3 退出空间：删除身份文件，清空所有凭据。与 Android IdentityStore.clear() 同效。</summary>
    public void Clear()
    {
        if (File.Exists(_filePath))
        {
            File.Delete(_filePath);
        }
    }
}
