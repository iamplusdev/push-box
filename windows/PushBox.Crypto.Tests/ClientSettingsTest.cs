using System;
using System.IO;
using PushBox.Windows.Settings;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// W13 的偏好落盘：<c>%LOCALAPPDATA%\PushBox\settings.json</c>。这一份全是可公开的行为开关，
/// 所以它的读法刻意和 <c>IdentityStore</c> 相反——身份文件坏了要报错（不能假装还有效），
/// 设置文件坏了就退回默认值（一次手改坏的文件不该让托盘起不来）。
/// </summary>
public sealed class ClientSettingsTest : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(),
        "pushbox-settings-test-" + Guid.NewGuid().ToString("N"));

    public ClientSettingsTest() => Directory.CreateDirectory(_dir);

    [Fact]
    public void TheFileSitsNextToTheDatabaseAndTheIdentity()
    {
        var local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        Assert.Equal(Path.Combine(local, "PushBox", "settings.json"), ClientSettings.FilePathIn());
        Assert.Equal(Path.Combine(local, "PushBox"), Path.GetDirectoryName(ClientSettings.FilePathIn()));
    }

    [Fact]
    public void AbsentFileReadsAsDefaults()
    {
        var settings = ClientSettings.Load(_dir);
        Assert.Equal(ClientSettings.Defaults, settings);
        Assert.True(settings.NotificationsEnabled);
        Assert.True(settings.CloseToTray);
        Assert.False(settings.QuietEnabled);
        // 开机自启默认**关**：从 bin\Debug 跑起来的开发实例不该在下次开机时自己爬起来
        Assert.False(settings.AutoStartEnabled);
    }

    [Fact]
    public void EverythingRoundTripsThroughDisk()
    {
        var written = new ClientSettings
        {
            NotificationsEnabled = false,
            QuietEnabled = true,
            QuietStartMinute = 23 * 60 + 30,
            QuietEndMinute = 6 * 60 + 15,
            AutoStartEnabled = true,
            CloseToTray = false
        };
        written.Save(_dir);

        Assert.Equal(written, ClientSettings.Load(_dir));
    }

    [Fact]
    public void SavingTwiceOverwritesInsteadOfMerging()
    {
        new ClientSettings { QuietEnabled = true }.Save(_dir);
        ClientSettings.Defaults.Save(_dir);

        // 整份覆盖写：关掉静默就必须真的关掉，留下一行 "quiet_enabled": true 是最难查的那种脏状态
        Assert.False(ClientSettings.Load(_dir).QuietEnabled);
    }

    [Theory]
    [InlineData("")]
    [InlineData("不是 JSON")]
    [InlineData("{\"notifications_enabled\":")]
    [InlineData("[]")]
    [InlineData("null")]
    [InlineData("\"notifications\"")]
    public void ACorruptFileFallsBackToDefaultsInsteadOfThrowing(string body)
    {
        File.WriteAllText(ClientSettings.FilePathIn(_dir), body);
        Assert.Equal(ClientSettings.Defaults, ClientSettings.Load(_dir));
    }

    [Fact]
    public void UnknownOrWronglyTypedFieldsAreSkippedOneByOne()
    {
        // 逐字段挑：一个字段读不动不该把别的字段一起带走，前向兼容（以后加的键）也走这条路
        File.WriteAllText(ClientSettings.FilePathIn(_dir),
            "{\"v\":1,\"notifications_enabled\":false,\"quiet_enabled\":\"yes\"," +
            "\"quiet_start_minute\":\"08:00\",\"future_switch\":true,\"auto_start\":true}");

        var settings = ClientSettings.Load(_dir);
        Assert.False(settings.NotificationsEnabled); // 合法的那一项照常生效
        Assert.True(settings.AutoStartEnabled);
        Assert.False(settings.QuietEnabled); // 这一项是字符串 "yes"：读不动就退回默认（默认关）
        Assert.Equal(ClientSettings.DefaultQuietStart, settings.QuietStartMinute);
    }

    [Theory]
    [InlineData(1440, 1439)]
    [InlineData(-1, 0)]
    [InlineData(100000, 1439)]
    [InlineData(0, 0)]
    [InlineData(1350, 1350)]
    public void MinuteValuesAreClampedRatherThanRejected(int raw, int expected)
    {
        Assert.Equal(expected, ClientSettings.ClampMinute(raw));

        File.WriteAllText(ClientSettings.FilePathIn(_dir),
            "{\"quiet_start_minute\":" + raw + ",\"quiet_end_minute\":" + raw + "}");
        var settings = ClientSettings.Load(_dir);
        Assert.Equal(expected, settings.QuietStartMinute);
        Assert.Equal(expected, settings.QuietEndMinute);
    }

    [Fact]
    public void WithQuietWindowSetsAllThreeAtOnce()
    {
        // 起止与开关一起改：只翻开关而留下上一次拖出来的窗口，是这类设置页最容易写错的地方
        var settings = ClientSettings.Defaults.WithQuietWindow(true, 25 * 60, -10);

        Assert.True(settings.QuietEnabled);
        Assert.Equal(ClientSettings.MinutesPerDay - 1, settings.QuietStartMinute);
        Assert.Equal(0, settings.QuietEndMinute);
        // 关掉静默时窗口值原样留着，再打开就是上次那一段
        Assert.Equal(ClientSettings.MinutesPerDay - 1, settings.WithQuietWindow(false, 1439, 0).QuietStartMinute);
    }

    [Fact]
    public void SavingCreatesTheDirectoryWhenItIsNotThereYet()
    {
        var nested = Path.Combine(_dir, "first", "run");
        Assert.False(Directory.Exists(nested));

        ClientSettings.Defaults.Save(nested);
        Assert.True(File.Exists(ClientSettings.FilePathIn(nested)));
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
