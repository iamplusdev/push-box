using System;
using System.Collections.Generic;
using System.IO;

namespace PushBox.Windows.Data;

/// <summary>
/// §7.3 退出空间要从这台电脑上抹掉的东西：身份、本地库（连它的伴随文件）、两份图片缓存。
/// 目录由调用方给（默认 %LOCALAPPDATA%\PushBox），用例因此能在临时目录上真的删一遍，
/// 而不是去动跑测机器上的那一份。
/// settings.json、日志、已下载的安装包刻意留着：它们描述的是"这台电脑"而不是"这个空间"，
/// 退出再回来时通知开关与窗口几何不该跟着被忘掉。
/// </summary>
public static class LocalSpace
{
    public const string IdentityFile = "identity.json";

    public const string DatabaseFile = "pushbox.db";

    /// <summary>媒体磁盘缓存与两份头像缓存（会话头像、toast 头像）。旧实现只删了库，图片留在盘上。</summary>
    public const string MediaCacheDirectory = "media_cache";

    public const string AvatarDirectory = "avatars";

    public const string ToastAvatarDirectory = "toast-avatar";

    /// <summary>
    /// 库旁边可能出现的伴随文件：SQLite 的 rollback journal 和（开了 WAL 时的）wal/shm。
    /// 只删主文件会在重启后让残留的半页数据被当成"新的这一本"，所以按名字都列上，不存在的跳过。
    /// </summary>
    private static readonly string[] DatabaseSidecars = { "-journal", "-wal", "-shm" };

    public static IReadOnlyList<string> Artifacts(string directory)
    {
        var paths = new List<string>
        {
            Path.Combine(directory, IdentityFile),
            Path.Combine(directory, DatabaseFile),
        };
        foreach (var suffix in DatabaseSidecars) paths.Add(Path.Combine(directory, DatabaseFile + suffix));
        paths.Add(Path.Combine(directory, MediaCacheDirectory));
        paths.Add(Path.Combine(directory, AvatarDirectory));
        paths.Add(Path.Combine(directory, ToastAvatarDirectory));
        return paths;
    }

    /// <summary>
    /// 按 <see cref="Artifacts"/> 删一遍，返回没能删掉的路径（空 = 清干净了）。
    /// 删不掉多半是文件还被句柄攥着——这一条要回到界面上说，而不是像旧实现那样吞掉异常，
    /// 让"身份没了、库还在"这种半退出的状态悄悄留在机器上。
    /// </summary>
    public static IReadOnlyList<string> Purge(string directory)
    {
        var left = new List<string>();
        foreach (var path in Artifacts(directory))
        {
            try
            {
                if (Directory.Exists(path)) Directory.Delete(path, recursive: true);
                else if (File.Exists(path)) File.Delete(path);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                left.Add(path);
            }
        }

        return left;
    }
}
