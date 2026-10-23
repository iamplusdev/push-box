using System.IO;
using Microsoft.Win32;
using PushBox.Windows.Notify;

namespace PushBox.Windows.Integration;

/// <summary>
/// 裸 WinRT 的 <c>ToastNotificationManager</c> 不给未打包应用（unpackaged）任何注册入口，
/// 而 <c>CreateToastNotifier(aumid)</c> 拿到一个没注册过的 AUMID 会直接拒——这是 WPF 路线上
/// 唯一一个"库里没有对应 API，只能自己补"的地方，所以它单独一层，坏了只坏在这一层。
///
/// 补的是两处 <c>HKCU</c>（都不需要管理员）：
/// - <c>Software\Classes\AppUserModelId\{AUMID}</c>：通知系统据此认这个应用，并取 DisplayName 当
///   用户在"通知设置"里看到的名字；
/// - <c>Software\Classes\pushbox</c>：协议处理器，点通知时 shell 就是执行它，argv 里带那条 URI。
///
/// 每次启动都重写一遍（值就是当前 exe 路径），这样从 <c>bin\Debug</c> 挪到 <c>Program Files</c>
/// 之后点通知不会还去唤起那个已经不存在的旧路径。
/// </summary>
public static class ToastRegistration
{
    /// <summary>AUMID 的格式约定是"公司.应用"，不能有 <c>\</c> 或 <c>/</c>，长度上限 128。</summary>
    public const string Aumid = "PushBox.Windows";

    /// <summary>系统通知设置里显示的应用名（§8.1 那个"PushBox"）。</summary>
    public const string DisplayName = "PushBox";

    public const string ClassesRoot = @"Software\Classes";

    public static string AumidKeyPath => $@"{ClassesRoot}\AppUserModelId\{Aumid}";

    public static string SchemeKeyPath => $@"{ClassesRoot}\{ToastProtocol.Scheme}";

    public static string CommandKeyPath => $@"{SchemeKeyPath}\shell\open\command";

    /// <summary>两处都写好了才叫"可以发通知"。返回值只用于体检输出，通知路径上调用方不看它。</summary>
    public static bool Ensure(string exePath)
    {
        var aumid = WriteAumid();
        var scheme = WriteScheme(exePath);
        return aumid && scheme;
    }

    /// <summary>协议那一段是否已经指向这个 exe：体检判据读的就是它，不是"我刚才没抛异常"。</summary>
    public static bool IsSchemePointedAt(string exePath)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(CommandKeyPath);
            return key?.GetValue(null) as string == ToastProtocol.HandlerCommand(exePath);
        }
        catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException)
        {
            return false;
        }
    }

    private static bool WriteAumid()
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(AumidKeyPath);
            if (key == null) return false;
            key.SetValue(null, DisplayName, RegistryValueKind.String);
            // 这一句是"能不能发"之外的另一半：Windows 用这个值决定要不要给用户显示"允许通知"的开关
            key.SetValue("DisplayName", DisplayName, RegistryValueKind.String);
            return true;
        }
        catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException
                                 or ArgumentException or IOException)
        {
            return false;
        }
    }

    private static bool WriteScheme(string exePath)
    {
        try
        {
            using var scheme = Registry.CurrentUser.CreateSubKey(SchemeKeyPath);
            if (scheme == null) return false;
            scheme.SetValue(null, $"URL:PushBox 通知", RegistryValueKind.String);
            // 空值的 URL Protocol 标记：没有它，shell 不认为这是一个协议处理器而只当一个普通键
            scheme.SetValue("URL Protocol", string.Empty, RegistryValueKind.String);
            using var command = Registry.CurrentUser.CreateSubKey(CommandKeyPath);
            if (command == null) return false;
            command.SetValue(null, ToastProtocol.HandlerCommand(exePath), RegistryValueKind.String);
            return true;
        }
        catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException
                                 or ArgumentException or IOException)
        {
            return false;
        }
    }
}
