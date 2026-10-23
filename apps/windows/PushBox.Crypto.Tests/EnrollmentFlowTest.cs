using System;
using System.IO;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading.Tasks;
using PushBox.Windows.Crypto;
using PushBox.Windows.Data;
using PushBox.Windows.Net;
using PushBox.Windows.Views;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W5: the enrollment half of §7.5 — keys minted locally, identity persisted only after the server
/// answers, and every input mistake caught before it costs a round trip.
/// </summary>
public class EnrollmentFlowTest : IDisposable
{
    private const string RegisterPlaintext =
        "{\"device_id\":\"dev_7f3a\",\"group_id\":\"grp_001\",\"token\":\"pbk_1a2b3c4d5e6f\","
        + "\"recovery_code\":\"9f3c77ad1e58b0c2Xk7mQw\",\"server_pub_fp\":\"e76604b23bcf585d\"}";

    private const string JoinPlaintext =
        "{\"device_id\":\"dev_pc2\",\"group_id\":\"grp_001\",\"token\":\"pbk_join_token\","
        + "\"recovery_code\":null,\"server_pub_fp\":\"e76604b23bcf585d\"}";

    private readonly string _dir = Path.Combine(Path.GetTempPath(),
        "pushbox-enroll-test-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task RegisterPersistsIdentityAndSealsRecoveryCode()
    {
        var store = new IdentityStore(_dir);
        var flow = Flow(RegisterPlaintext, store);

        var identity = await flow.CreateSpaceAsync("https://push.test/", "Office PC");

        Assert.True(store.Exists());
        var reloaded = new IdentityStore(_dir).Load();
        Assert.Equal("dev_7f3a", reloaded.DeviceId);
        Assert.Equal("grp_001", reloaded.GroupId);
        Assert.Equal("pbk_1a2b3c4d5e6f", reloaded.Token);
        Assert.Equal("e76604b23bcf585d", reloaded.PinnedServerFp);
        Assert.Equal("9f3c77ad1e58b0c2Xk7mQw", reloaded.RecoveryCode);
        // 尾斜杠不进身份文件：W6 的 wss URL 和 W9 的签名 path 都从这个根拼
        Assert.Equal("https://push.test", reloaded.ServerBaseUrl);
        // 本机生成的私钥必须和注册用的公钥配对，重开后还是同一台设备
        Assert.Equal(identity.EncPubB64, reloaded.EncPubB64);
        Assert.Equal(identity.SignPubB64, reloaded.SignPubB64);

        var raw = Encoding.UTF8.GetString(File.ReadAllBytes(store.FilePath));
        Assert.DoesNotContain("pbk_1a2b3c4d5e6f", raw);
        Assert.DoesNotContain("9f3c77ad1e58b0c2Xk7mQw", raw, StringComparison.Ordinal);
    }

    [Fact]
    public async Task JoinStoresNoRecoveryCodeBecauseTheServerNeverRepeatsIt()
    {
        var handler = new FakeHandler(Support.EnrollResponder(JoinPlaintext));
        var store = new IdentityStore(_dir);
        var flow = Flow(handler, store);

        var identity = await flow.JoinSpaceAsync("https://push.test", "9f3c77ad1e58b0c2Xk7mQw", "Office PC");

        Assert.Null(identity.RecoveryCode);
        Assert.Null(new IdentityStore(_dir).Load().RecoveryCode);
        var request = handler.Single();
        Assert.Equal("/api/v1/devices/join", request.Path);
        Assert.Equal("9f3c77ad1e58b0c2Xk7mQw", Support.Str(request.BodyJson(), "group_secret"));
    }

    [Fact]
    public async Task FailedRegisterLeavesNoHalfDeviceBehind()
    {
        var store = new IdentityStore(_dir);
        var flow = Flow(_ => Support.Reply(HttpStatusCode.Conflict,
            Support.ErrorBody("device_limit_reached")), store);

        await Assert.ThrowsAsync<PushBoxApiException>(
            () => flow.CreateSpaceAsync("https://push.test", "Office PC"));

        Assert.False(store.Exists());
        Assert.False(File.Exists(store.FilePath + ".tmp"));
    }

    [Fact]
    public async Task PastedCodeWithSpacesStillReachesTheServerTrimmed()
    {
        var handler = new FakeHandler(Support.EnrollResponder(JoinPlaintext));
        var flow = Flow(handler, new IdentityStore(_dir));

        // 手机上是长按选中的复制，末尾常带一个空格；服务端按字节哈希，多一个字符就是 401
        await flow.JoinSpaceAsync("https://push.test", " 9f3c77ad1e58b0c2Xk7mQw\n", "Office PC");

        Assert.Equal("9f3c77ad1e58b0c2Xk7mQw", Support.Str(handler.Single().BodyJson(), "group_secret"));
    }

    [Fact]
    public void GeneratedKeysAreRealAndDistinctPerAttempt()
    {
        var a = IdentityStore.CreateNew("https://push.test");
        var b = IdentityStore.CreateNew("https://push.test");
        Assert.Equal(32, a.EncPrivSeed.Length);
        Assert.Equal(32, a.SignPrivSeed.Length);
        Assert.NotEqual(a.EncPubB64, b.EncPubB64);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    public void BlankDeviceNameIsRejected(string input)
    {
        var e = Assert.Throws<ArgumentException>(() => EnrollmentFlow.ValidateDeviceName(input));
        Assert.Contains("设备名称", e.Message);
    }

    [Fact]
    public void DeviceNameIsTrimmedBeforeSending()
    {
        Assert.Equal("Office PC", EnrollmentFlow.ValidateDeviceName("  Office PC "));
    }

    [Fact]
    public void OverlongDeviceNameIsRejectedBeforeTheServerTruncatesIt()
    {
        var e = Assert.Throws<ArgumentException>(
            () => EnrollmentFlow.ValidateDeviceName(new string('名', EnrollmentFlow.MaxDeviceNameLength + 1)));
        Assert.Contains("64", e.Message);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   \n  ")]
    public void EmptyRecoveryCodeNamesThePhoneScreen(string input)
    {
        var e = Assert.Throws<ArgumentException>(() => EnrollmentFlow.NormalizeRecoveryCode(input));
        Assert.Contains("恢复码", e.Message);
    }

    [Fact]
    public void ShortRecoveryCodeIsCalledOutAsACopyMistake()
    {
        var e = Assert.Throws<ArgumentException>(() => EnrollmentFlow.NormalizeRecoveryCode("9f3c-77ad-1e58-b0c"));
        Assert.Contains("22 位", e.Message);
    }

    [Fact]
    public void NonBase64RecoveryCodeIsRejectedLocally()
    {
        var e = Assert.Throws<ArgumentException>(() => EnrollmentFlow.NormalizeRecoveryCode("9f3c77ad1e58b0c2Xk7m.Q"));
        Assert.Contains("字母", e.Message);
    }

    [Fact]
    public async Task ModelTurnsEveryFailureIntoOneSentence()
    {
        var model = new OnboardingModel(Flow(_ => Support.Reply(HttpStatusCode.Conflict,
            Support.ErrorBody("device_limit_reached")), new IdentityStore(_dir)));

        var result = await model.CreateSpaceAsync("https://push.test", "Office PC");

        Assert.False(result.Succeeded);
        Assert.False(result.Cancelled);
        Assert.Contains("吊销", result.Error);
    }

    [Fact]
    public async Task OnlyTheSpaceCreatorSeesTheRecoveryCode()
    {
        var register = new OnboardingModel(Flow(RegisterPlaintext, new IdentityStore(_dir)));
        var joined = new OnboardingModel(Flow(JoinPlaintext, new IdentityStore(_dir)));

        var created = await register.CreateSpaceAsync("https://push.test", "Office PC");
        var join = await joined.JoinAsync("https://push.test", "9f3c77ad1e58b0c2Xk7mQw", "Office PC");

        // 第 2 屏只在 NeedsBackup 时出现
        Assert.True(created.NeedsBackup);
        Assert.False(join.NeedsBackup);
    }

    [Fact]
    public async Task CancelledAttemptIsNotReportedAsAFailure()
    {
        using var cts = new System.Threading.CancellationTokenSource();
        cts.Cancel();
        var model = new OnboardingModel(Flow(RegisterPlaintext, new IdentityStore(_dir)));

        var result = await model.CreateSpaceAsync("https://push.test", "Office PC", cts.Token);

        Assert.True(result.Cancelled);
        Assert.Null(result.Error);
    }

    [Fact]
    public void SuggestedDeviceNameIsUsable()
    {
        var name = OnboardingModel.SuggestedDeviceName();
        Assert.False(string.IsNullOrWhiteSpace(name));
        Assert.True(name.Length <= EnrollmentFlow.MaxDeviceNameLength);
    }

    [Fact]
    public void IsEnrolledFollowsTheDisk()
    {
        var store = new IdentityStore(_dir);
        var flow = Flow(RegisterPlaintext, store);
        Assert.False(flow.IsEnrolled());
        Assert.False(new OnboardingModel(flow).IsEnrolled());
    }

    /// <summary>Sealed answer for whatever key pair the flow minted, like the real server.</summary>
    private static EnrollmentFlow Flow(string sealedPayload, IdentityStore store) =>
        Flow(Support.EnrollResponder(sealedPayload), store);

    private static EnrollmentFlow Flow(Func<RecordedRequest, HttpResponseMessage> responder, IdentityStore store)
        => Flow(new FakeHandler(responder), store);

    private static EnrollmentFlow Flow(FakeHandler handler, IdentityStore store) =>
        new(new DeviceClient(new HttpClient(handler)), store);

    public void Dispose()
    {
        try
        {
            if (Directory.Exists(_dir)) Directory.Delete(_dir, true);
        }
        catch (IOException)
        {
            // 临时目录清不掉不影响结论
        }
    }
}
