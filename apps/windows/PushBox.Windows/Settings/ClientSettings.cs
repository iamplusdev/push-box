using System.IO;
using System.Text.Json;

namespace PushBox.Windows.Settings;

/// <summary>
/// §8.1/§7.4 的 PC 侧偏好：通知总开关、静默时段、关窗行为、开机自启。
///
/// 落成 record 而不是类，是因为改一项就是 <c>settings with { QuietEnabled = true }</c>——
/// 托盘菜单、Toast 决策、引擎都在读同一个实例，原地改字段会让"上一次读到的值"变成别名。
///
/// 它和 <see cref="Data.IdentityStore"/> 分文件放是有意的：身份那一份要 DPAPI 绑用户，
/// 这一份全是可公开的行为开关，坏了不该让人进不了应用（读不出来就退回默认值）。
/// </summary>
public sealed record ClientSettings
{
    /// <summary>一天的分钟数，静默时段的起止都取整到它。</summary>
    public const int MinutesPerDay = 24 * 60;

    /// <summary>§7.4 的默认窗口：22:00–08:00，跨午夜。</summary>
    public const int DefaultQuietStart = 22 * 60;

    public const int DefaultQuietEnd = 8 * 60;

    /// <summary>通知总开关（Android 的 notificationsEnabled）：关掉照常入库，只是一条通知都不发。</summary>
    public bool NotificationsEnabled { get; init; } = true;

    /// <summary>§7.4 静默时段：默认关，和 Android 一致——没人希望装完就发现自己半夜被静音。</summary>
    public bool QuietEnabled { get; init; }

    /// <summary>起点，距 0 点的分钟数。允许大于终点（跨午夜），判定见 <see cref="Notify.AlertPolicy.InQuietWindow"/>。</summary>
    public int QuietStartMinute { get; init; } = DefaultQuietStart;

    public int QuietEndMinute { get; init; } = DefaultQuietEnd;

    /// <summary>§8.1 开机自启：<c>HKCU\...\Run</c>。默认<b>关</b>，托盘菜单里勾上才写注册表。
    /// 之所以不像 Android 那样默认开：开发时从 <c>bin\Debug</c> 跑起来的实例也会被写进启动项，
    /// 而那条目在正式安装（W15 的 Inno Setup）之后还指着旧路径，属于自己给自己埋雷。</summary>
    public bool AutoStartEnabled { get; init; }

    /// <summary>§8.1：关闭按钮 = 最小化到托盘；关掉它就是"点关闭直接退出"。</summary>
    public bool CloseToTray { get; init; } = true;

    /// <summary>D32：冷启动是否静默查一次 GitHub 更新。默认<b>开</b>——查到也只弹一个确认框，
    /// 点"下载并安装"才动手，绝不自动下、自动装。关掉它只是不再主动查，设置里的「检查更新」按钮不受影响。</summary>
    public bool AutoCheckUpdate { get; init; } = true;

    public static readonly ClientSettings Defaults = new();

    /// <summary>与 pushbox.db / identity.json 同目录（§8.1），测试注入临时目录。</summary>
    public static string FilePathIn(string? directory = null) =>
        Path.Combine(directory ?? Data.IdentityStore.DefaultDirectory, "settings.json");

    public ClientSettings WithQuietWindow(bool enabled, int startMinute, int endMinute) => this with
    {
        QuietEnabled = enabled,
        QuietStartMinute = ClampMinute(startMinute),
        QuietEndMinute = ClampMinute(endMinute)
    };

    /// <summary>越界只回绕不报错：起止是用户拖出来的数字，为一个 25:00 把整份设置丢掉不值得。</summary>
    public static int ClampMinute(int minute) =>
        minute < 0 ? 0 : minute >= MinutesPerDay ? MinutesPerDay - 1 : minute;

    /// <summary>
    /// 读盘。文件不在/不是合法 JSON/字段类型不对，一律回默认值而不是抛——
    /// 首启时这个文件本来就不存在，而一次手改坏了 settings.json 不该让托盘起不来。
    /// </summary>
    public static ClientSettings Load(string? directory = null)
    {
        var path = FilePathIn(directory);
        if (!File.Exists(path)) return Defaults;

        JsonElement root;
        try
        {
            root = JsonDocument.Parse(File.ReadAllText(path)).RootElement;
        }
        catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException
                                 or System.Text.DecoderFallbackException)
        {
            return Defaults;
        }

        if (root.ValueKind != JsonValueKind.Object) return Defaults;
        return new ClientSettings
        {
            NotificationsEnabled = Bool(root, "notifications_enabled", Defaults.NotificationsEnabled),
            QuietEnabled = Bool(root, "quiet_enabled", Defaults.QuietEnabled),
            QuietStartMinute = ClampMinute(Int(root, "quiet_start_minute", Defaults.QuietStartMinute)),
            QuietEndMinute = ClampMinute(Int(root, "quiet_end_minute", Defaults.QuietEndMinute)),
            AutoStartEnabled = Bool(root, "auto_start", Defaults.AutoStartEnabled),
            CloseToTray = Bool(root, "close_to_tray", Defaults.CloseToTray),
            AutoCheckUpdate = Bool(root, "auto_check_update", Defaults.AutoCheckUpdate)
        };
    }

    /// <summary>整份覆盖写：文件小到不值得增量改，且崩溃在半路只会退成默认值，不会退成半新半旧。</summary>
    public void Save(string? directory = null)
    {
        var path = FilePathIn(directory);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["v"] = 1,
            ["notifications_enabled"] = NotificationsEnabled,
            ["quiet_enabled"] = QuietEnabled,
            ["quiet_start_minute"] = QuietStartMinute,
            ["quiet_end_minute"] = QuietEndMinute,
            ["auto_start"] = AutoStartEnabled,
            ["close_to_tray"] = CloseToTray,
            ["auto_check_update"] = AutoCheckUpdate
        }));
    }

    /// <summary>逐字段挑：不认识的值（包括 null、字符串、缺项）都回默认，而不是把整份设置作废。</summary>
    private static bool Bool(JsonElement root, string name, bool fallback) =>
        root.TryGetProperty(name, out var v) && v.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => fallback
        };

    private static int Int(JsonElement root, string name, int fallback) =>
        root.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var n)
            ? n
            : fallback;
}
