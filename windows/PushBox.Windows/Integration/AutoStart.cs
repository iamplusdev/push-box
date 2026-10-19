using Microsoft.Win32;

namespace PushBox.Windows.Integration;

/// <summary>设置与注册表之间那种"看着一样、含义不同"的差值，见 <see cref="AutoStart.DriftOf"/>。</summary>
public enum AutoStartDrift
{
    AlreadyRight,
    MovedInstall,
    UserCleared
}

/// <summary>
/// §8.1 的开机自启：写 <c>HKCU\Software\Microsoft\Windows\CurrentVersion\Run</c> 的一个值，
/// 值内容就是"带引号的 exe 全路径"。用 Run 而不用 Task Scheduler，是因为它不需要管理员权限、
/// 用户在任务管理器的"启动"页里能直接看见并关掉——一个悄悄往系统里塞东西的客户端不值得留。
///
/// 注册表调用只有 <see cref="Apply"/> 一层薄皮，判定（"该写什么值""要不要动这一笔"）留在纯函数里，
/// 免得"路径没变也要重写成同值"这种事只能靠人盯着注册表看。
/// </summary>
public static class AutoStart
{
    public const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";

    /// <summary>注册表里那一项的名字，装上后用户看到的是 "PushBox"。</summary>
    public const string ValueName = "PushBox";

    /// <summary>关掉就是"这一项不该存在"。用一个可空的期望值表达开关，比到处传 bool 少一次分歧。</summary>
    public static string? DesiredValue(bool enabled, string exePath) =>
        enabled ? CommandFor(exePath) : null;

    /// <summary>整条路径加引号：开发机上 exe 常在 <c>Program Files</c> 或带空格的中文路径里，
    /// 不加引号 Run 会把路径按空格拆成"可执行文件 + 参数"，开机时静默启动失败。</summary>
    public static string CommandFor(string exePath) => "\"" + exePath + "\"";

    /// <summary>已经在跑的那个路径与期望一致吗？一致就不动注册表（值相同也写会让"启动"页里的
    /// "程序已更新"提示莫名亮一次）。</summary>
    public static bool NeedsWrite(string? current, string? desired) => current != desired;

    /// <summary>当前是否已开启（且指向这个 exe）：路径换了就等于没开，下次启动会把它写回新路径。</summary>
    public static bool IsEnabled(string exePath) => Read() == CommandFor(exePath);

    /// <summary>
    /// 设置里写着"开"，注册表那一笔却可能已经不是这个意思了。三种走法分开看：
    /// <list type="bullet">
    /// <item><description><c>AlreadyRight</c>：什么都不做，"值相同也要重写"会让启动页里的"程序已更新"莫名亮一次。</description></item>
    /// <item><description><c>MovedInstall</c>：有条目但指着别处——是我们自己换了目录（<c>bin\Debug</c>  ↔  Program Files），
    /// 该跟着搬到新路径，否则开机时启动页里留的是一条永远起不来的旧条目。</description></item>
    /// <item><description><c>UserCleared</c>：条目不见了。§8.1 说的"用户自己就能在任务管理器里关掉"走的就是这条路，
    /// 顺着他把设置改成关，而不是下次开机偷偷再塞回去。</description></item>
    /// </list>
    /// </summary>
    public static AutoStartDrift DriftOf(string? current, string desired) => current switch
    {
        null => AutoStartDrift.UserCleared,
        _ when current == desired => AutoStartDrift.AlreadyRight,
        _ => AutoStartDrift.MovedInstall
    };

    public static string? Read()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKeyPath);
            return key?.GetValue(ValueName) as string;
        }
        catch (Exception e) when (e is UnauthorizedAccessException or System.Security.SecurityException
                                 or ArgumentException)
        {
            return null;
        }
    }

    /// <summary>把开关落到注册表上。<b>只在用户真的动过那个开关时被调用</b>：默认设置是关，
    /// 所以一个从 bin 里跑起来的开发实例不会在下次开机时自己爬起来。</summary>
    public static void Apply(bool enabled, string exePath)
    {
        var desired = DesiredValue(enabled, exePath);
        using var key = Registry.CurrentUser.CreateSubKey(RunKeyPath)
                        ?? throw new InvalidOperationException($"打不开 {RunKeyPath}，开机自启没能修改。");
        if (desired == null)
        {
            if (key.GetValue(ValueName) != null) key.DeleteValue(ValueName, false);
            return;
        }

        key.SetValue(ValueName, desired, RegistryValueKind.String);
    }
}
