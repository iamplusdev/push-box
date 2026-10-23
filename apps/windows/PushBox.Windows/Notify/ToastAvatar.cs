using System.IO;
using PushBox.Windows.Media;

namespace PushBox.Windows.Notify;

/// <summary>
/// 把头像字节落成通知平台读得到的那一个临时文件。
///
/// W10 定过"头像只进内存"（<see cref="MediaLoader"/> 的缓存不落盘，因为头像对象是覆盖写的，
/// 落盘等于把换掉的头像永久留在这台机器上）。<b>但 toast 的图片只能是一个 URI</b>：渲染通知的是系统进程，
/// 它读不到本进程的内存，也带不上我们那发签名请求的头。所以这一份字节必须在"进通知"这一刻落地一次——
/// 这条把 W10 那条口径的适用范围划清了：<b>它管的是缓存层，不是通知层</b>。
///
/// 既然落盘不可避免，就把代价收在两个数上：同一个人固定同一个文件名（覆盖写，换头像不会攒历史），
/// 整个目录最多 <see cref="FileCap"/> 个文件（超出按最后写入时间淘汰最旧的）。
/// </summary>
public static class ToastAvatar
{
    /// <summary>目录名，放在 <c>%LOCALAPPDATA%\PushBox\</c> 下，和 pushbox.db 同层、好找好清。</summary>
    public const string DirectoryName = "toast-avatar";

    public const string FilePrefix = "avatar-";

    /// <summary>封顶的头像文件数：一个人一张，八张够同时在线的会话数了，再多就是给磁盘攒旧头像。</summary>
    public const int FileCap = 8;

    public static string DirectoryOf(string? baseDir = null) =>
        Path.Combine(baseDir ?? Data.IdentityStore.DefaultDirectory, DirectoryName);

    /// <summary>token 本来就是 sender_id 的 sha256（§4.5），这里只做一次文件名消毒，
    /// 因为它是自由文本：万一哪天换成 base64 带 <c>/</c> 或 <c>+</c>，直接拼会写出目录外或读不回的文件。</summary>
    public static string FileName(string token)
    {
        var chars = new char[Math.Min(token.Length, 64)];
        for (var i = 0; i < chars.Length; i++)
        {
            var c = token[i];
            chars[i] = char.IsAsciiLetterOrDigit(c) || c == '-' || c == '_' ? c : '_';
        }

        return FilePrefix + new string(chars);
    }

    /// <summary>通知平台认的几种格式；webp 不在其中（系统解码器不接），认不出来就当没头像。</summary>
    public static string? ExtensionFor(string? mime) => mime switch
    {
        "image/png" => ".png",
        "image/jpeg" => ".jpg",
        "image/gif" => ".gif",
        "image/bmp" => ".bmp",
        _ => null
    };

    /// <summary>
    /// 写这一张并淘汰超额的，返回通知里能用的绝对路径；任何一步失败都回 null（没头像的通知照样发）。
    /// 失败不回抛是 §14 之外的另一条理由：取字节这条链上出的错都跟"要不要打扰用户"无关。
    /// </summary>
    public static string? Stage(string baseDir, string token, byte[]? bytes, string? mime = null)
    {
        if (bytes is not { Length: > 0 }) return null;
        var ext = ExtensionFor(mime ?? ImageMime.Sniff(bytes));
        if (ext == null) return null;

        try
        {
            var dir = DirectoryOf(baseDir);
            Directory.CreateDirectory(dir);
            var name = FileName(token) + ext;
            var path = Path.Combine(dir, name);
            File.WriteAllBytes(path, bytes);
            Prune(dir, name);
            return path;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    /// <summary>
    /// 淘汰：先删同名的另一种扩展（一个人只留一张），再把总数压到 <paramref name="cap"/> 以内。
    /// 摘出来成静态方法是为了让"第 9 张挤掉最旧那张、当前这张永远不被挤掉"能在临时目录里被验证。
    /// </summary>
    public static void Prune(string dir, string keep, int cap = FileCap)
    {
        var all = Directory.EnumerateFiles(dir, FilePrefix + "*").ToList();
        var stem = Path.GetFileNameWithoutExtension(keep);
        foreach (var other in all.Where(f => Path.GetFileNameWithoutExtension(f) == stem
                                             && Path.GetFileName(f) != keep))
        {
            TryDelete(other);
        }

        var kept = Directory.EnumerateFiles(dir, FilePrefix + "*")
            .OrderByDescending(f => File.GetLastWriteTimeUtc(f))
            .Skip(Math.Max(1, cap))
            .ToList();
        foreach (var old in kept.Where(f => Path.GetFileName(f) != keep)) TryDelete(old);
    }

    /// <summary>"我的"页那句清理缓存会调它；调用方不必知道目录名。</summary>
    public static void Clear(string? baseDir = null)
    {
        var dir = DirectoryOf(baseDir);
        if (!Directory.Exists(dir)) return;
        foreach (var file in Directory.EnumerateFiles(dir, FilePrefix + "*")) TryDelete(file);
    }

    private static void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            // 系统进程还在读那张图（通知还挂在屏幕上）：删不掉就留着，下次再删，不值得惊动用户
        }
    }
}
