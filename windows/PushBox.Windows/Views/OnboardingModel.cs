using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using PushBox.Windows.Data;
using PushBox.Windows.Net;

namespace PushBox.Windows.Views;

/// <summary>Outcome of one first-launch attempt: either an identity, or one sentence to show.</summary>
public sealed record OnboardingResult(DeviceIdentity? Identity, string? Error, bool Cancelled = false)
{
    public bool Succeeded => Identity != null;

    /// <summary>True when this device created the Space, so the recovery code must be抄下来 now.</summary>
    public bool NeedsBackup => Identity?.RecoveryCode != null;

    public static OnboardingResult Cancel() => new(null, null, true);
}

/// <summary>
/// Everything the first-launch screen does, kept free of WPF types so the flow is unit-testable
/// against a fake server. The window only moves text between controls and this class.
/// </summary>
public sealed class OnboardingModel
{
    private readonly EnrollmentFlow _flow;

    public OnboardingModel(EnrollmentFlow? flow = null)
    {
        _flow = flow ?? new EnrollmentFlow();
    }

    public bool IsEnrolled() => _flow.IsEnrolled();

    /// <summary>Machine name is the least surprising default; the user can rename before submitting.</summary>
    public static string SuggestedDeviceName()
    {
        var host = Environment.MachineName;
        return string.IsNullOrWhiteSpace(host) ? "Windows PC"
            : host.Length > EnrollmentFlow.MaxDeviceNameLength ? host[..EnrollmentFlow.MaxDeviceNameLength] : host;
    }

    public Task<OnboardingResult> CreateSpaceAsync(string serverBaseUrl, string deviceName,
        CancellationToken ct = default)
        => Run(() => _flow.CreateSpaceAsync(serverBaseUrl, deviceName, ct));

    public Task<OnboardingResult> JoinAsync(string serverBaseUrl, string recoveryCode,
        string deviceName, CancellationToken ct = default)
        => Run(() => _flow.JoinSpaceAsync(serverBaseUrl, recoveryCode, deviceName, ct));

    private static async Task<OnboardingResult> Run(Func<Task<DeviceIdentity>> action)
    {
        try
        {
            return new OnboardingResult(await action().ConfigureAwait(false), null);
        }
        catch (OperationCanceledException)
        {
            return OnboardingResult.Cancel();
        }
        catch (Exception e) when (IsReadable(e))
        {
            // Message 已经被翻译成一句人话（ApiErrorText / EnrollmentFlow 的参数校验）
            return new OnboardingResult(null, e.Message);
        }
    }

    private static bool IsReadable(Exception e)
    {
        return e is ArgumentException or PushBoxApiException or PushBoxTransportException
            || e is System.IO.FileNotFoundException or InvalidDataException;
    }
}
