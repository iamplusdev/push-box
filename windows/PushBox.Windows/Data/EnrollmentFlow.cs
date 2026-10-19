using System;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Net;

namespace PushBox.Windows.Data;

/// <summary>
/// §7.5 first launch: mint the key pair locally, name the device, enroll, then persist.
/// Nothing is written before the server answers, so a refused register (5 devices already bound,
/// a mistyped recovery code) leaves no half-enrolled device on disk.
/// </summary>
public sealed class EnrollmentFlow
{
    /// <summary>The server truncates names at 64 chars; erroring here beats silently truncating.</summary>
    public const int MaxDeviceNameLength = 64;

    /// <summary>§4.2: the recovery code is base64url of 16 random bytes, shown as-is by the phone.</summary>
    public const int RecoveryCodeLength = 22;

    private static readonly Regex RecoveryCodeShape = new("^[A-Za-z0-9_-]+$", RegexOptions.Compiled);

    private readonly DeviceClient _client;
    private readonly IdentityStore _store;

    public EnrollmentFlow(DeviceClient? client = null, IdentityStore? store = null)
    {
        _client = client ?? new DeviceClient();
        _store = store ?? new IdentityStore();
    }

    public bool IsEnrolled() => _store.Exists();

    public DeviceIdentity LoadIdentity() => _store.Load();

    /// <summary>First device of a new Space: the answer carries the recovery code exactly once.</summary>
    public Task<DeviceIdentity> CreateSpaceAsync(string serverBaseUrl, string deviceName,
        CancellationToken ct = default)
        => EnrollAsync(serverBaseUrl, deviceName, null, ct);

    /// <summary>"我有恢复码" — join the phone's Space, then W7/W8 pull the whole history.</summary>
    public Task<DeviceIdentity> JoinSpaceAsync(string serverBaseUrl, string recoveryCode,
        string deviceName, CancellationToken ct = default)
        => EnrollAsync(serverBaseUrl, deviceName, NormalizeRecoveryCode(recoveryCode), ct);

    private async Task<DeviceIdentity> EnrollAsync(string serverBaseUrl, string deviceName,
        string? recoveryCode, CancellationToken ct)
    {
        var baseUrl = DeviceClient.NormalizeBaseUrl(serverBaseUrl);
        var name = ValidateDeviceName(deviceName);

        // 密钥在这里就生成：服务端用回包里的公钥加密 token，私钥从不离开本机（§4.2）
        var pending = IdentityStore.CreateNew(baseUrl);
        var enrolled = recoveryCode == null
            ? await _client.RegisterAsync(pending, name, ct).ConfigureAwait(false)
            : await _client.JoinAsync(pending, recoveryCode, name, ct).ConfigureAwait(false);

        var identity = pending with
        {
            DeviceId = enrolled.DeviceId,
            Token = enrolled.Token,
            GroupId = enrolled.GroupId,
            PinnedServerFp = enrolled.ServerPubFp,
            RecoveryCode = enrolled.RecoveryCode,
        };
        _store.Save(identity);
        return identity;
    }

    public static string ValidateDeviceName(string deviceName)
    {
        var name = (deviceName ?? "").Trim();
        if (name.Length == 0)
            throw new ArgumentException("设备名称不能为空，例如「Office PC」。");
        if (name.Length > MaxDeviceNameLength)
            throw new ArgumentException($"设备名称最长 {MaxDeviceNameLength} 个字符，现在有 {name.Length} 个。");
        return name;
    }

    /// <summary>
    /// Spaces and line breaks are paste artefacts, so they are dropped before the shape check —
    /// the server hashes the code byte-for-byte and would otherwise answer 401 without saying why.
    /// </summary>
    public static string NormalizeRecoveryCode(string recoveryCode)
    {
        var code = new string((recoveryCode ?? "").Replace(" ", "").Replace("\t", "")
            .Replace("\r", "").Replace("\n", "").ToCharArray());
        if (code.Length == 0)
            throw new ArgumentException("恢复码不能为空：在手机的「我的 → 备份与恢复」里能看到它和二维码。");
        if (code.Length != RecoveryCodeLength)
            throw new ArgumentException(
                $"恢复码应该是 {RecoveryCodeLength} 位，现在有 {code.Length} 位——多半是复制时漏了字符，请重新复制一次。");
        if (!RecoveryCodeShape.IsMatch(code))
            throw new ArgumentException("恢复码只由字母、数字、- 和 _ 组成，请确认复制完整。");
        return code;
    }
}
