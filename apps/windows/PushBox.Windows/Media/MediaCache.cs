using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using PushBox.Windows.Data;
using PushBox.Windows.Logging;

namespace PushBox.Windows.Media;

/// <summary>
/// §4.5 的 <c>media_cache/</c>：内存 + 磁盘两级，键是"这条 (msg_id, idx, size) 对应的字节"。
///
/// 为什么不直接拿 object_key 当键：下发的 media[] 里那个 key 是 <c>media/{msg_id}/{idx}</c>
/// （探针 ⑲ 逐帧确认过是确定性拼法），而槽位本身不进本地库（§3.3 只留 media_status/count），
/// 所以补拉来的消息没有 key 可读。用 (msg_id, idx, size) 拼键与按 object_key 命名是同一件事，
/// 但补拉和实时下发拿到的是同一个键——这正是"断网时图片来自缓存"成立的前提。
///
/// 磁盘文件名是键的 UTF-8 十六进制，可逆：按消息清理时只要拿 <c>hex(msg_id + "#")</c> 当前缀扫目录，
/// 不需要为每个对象再存一份索引。
/// </summary>
public sealed class MediaCache
{
    /// <summary>§4.5 说 LRU 清理可配；默认给 64MB，约等于 6 张满额原图（单张 ≤10MB）的量。</summary>
    public const long DefaultDiskCapacity = 64L * 1024 * 1024;

    /// <summary>内存这一层的上限按字节算：单张原图最大 10MB，只按条数限会瞬间吃掉几百 MB。</summary>
    public const long DefaultMemoryCapacity = 12L * 1024 * 1024;

    /// <summary>十六进制文件名能接受的最长长度，超过就退化成整串键的 sha256。</summary>
    private const int MaxHexNameLength = 200;

    private readonly object _gate = new();
    private readonly Dictionary<string, byte[]> _memory = new(StringComparer.Ordinal);
    private readonly Dictionary<string, long> _used = new(StringComparer.Ordinal);
    private readonly long _memoryCapacity;
    private readonly long _diskCapacity;
    private long _memoryBytes;
    private long _diskBytes;
    private long _clock;

    public MediaCache(string? directory = null, long diskCapacityBytes = DefaultDiskCapacity,
        long memoryCapacityBytes = DefaultMemoryCapacity)
    {
        DirectoryPath = directory ?? DefaultDirectory;
        _diskCapacity = diskCapacityBytes;
        _memoryCapacity = memoryCapacityBytes;
        if (Directory.Exists(DirectoryPath))
        {
            // 只算一次：进程活着期间这个目录只有自己会动
            _diskBytes = Directory.EnumerateFiles(DirectoryPath).Sum(FileLength);
        }
    }

    /// <summary>§8.1：与库文件同目录，私有、不进漫游配置。</summary>
    public static string DefaultDirectory =>
        Path.Combine(IdentityStore.DefaultDirectory, "media_cache");

    public string DirectoryPath { get; }

    /// <summary>命中返回字节，没命中返回 null。内存没有就去磁盘，读到则回填内存（两级共用一条 LRU）。</summary>
    public byte[]? Get(string key)
    {
        lock (_gate)
        {
            if (_memory.TryGetValue(key, out var hot))
            {
                Touch(key);
                return hot;
            }

            var path = PathOf(key);
            if (!File.Exists(path)) return null;
            try
            {
                var bytes = File.ReadAllBytes(path);
                TouchFile(path);
                Remember(key, bytes);
                return bytes;
            }
            catch (IOException)
            {
                // 读到一半文件被清了：当成没命中，让调用方回源，而不是把一次看图的点击变成报错
                return null;
            }
        }
    }

    /// <summary>
    /// 写缓存。<paramref name="persist"/> = false 只进内存：头像对象是按 (频道, 发送者)<b>覆盖写</b>的
    /// （§4.5），token 不变而字节会变，落盘等于把换掉的头像留在本机磁盘上再也等不到更新。
    /// </summary>
    public void Put(string key, byte[] bytes, bool persist = true)
    {
        // 失败先记着、出了锁再留痕：日志自己也要抢锁，把 Write 放在 _gate 里等于让一次落盘失败拖住整条缓存
        Exception? diskFailure = null;
        lock (_gate)
        {
            Remember(key, bytes);
            if (!persist) return;

            var path = PathOf(key);
            try
            {
                Directory.CreateDirectory(DirectoryPath);
                // 老长度必须在覆盖之前取：写完之后再去问，问到的已经是新那份了
                var previous = File.Exists(path) ? FileLengthOf(path) : 0L;
                // 先写临时再替换：中途被杀不会留下半个 jpg，那半个 jpg 下一次是解不开的图
                var temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
                File.WriteAllBytes(temp, bytes);
                File.Move(temp, path, true);
                _diskBytes += bytes.LongLength - previous;
                TrimDisk();
            }
            catch (IOException e)
            {
                // 磁盘写不进去（满了、目录被删了）不该让这张图看不见：内存那份已经在，进程内还能用
                diskFailure = e;
            }
            catch (UnauthorizedAccessException e)
            {
                diskFailure = e;
            }
        }

        // 这一行的用处是"为什么这台机器的图每次都要重新取"：没有它，缓存失效和从没建过缓存长得一模一样
        if (diskFailure != null) AppLog.MediaCacheWriteFailed(diskFailure.GetType().Name);
    }

    /// <summary>W14 的删除/清空要连带把字节抹掉：消息都没了，本地图位不能还能翻出来看。</summary>
    public void EvictMessage(string msgId)
    {
        lock (_gate)
        {
            var prefix = msgId + "#";
            foreach (var key in _memory.Keys.Where(k => k.StartsWith(prefix, StringComparison.Ordinal)).ToList())
            {
                DropMemory(key);
            }

            if (!Directory.Exists(DirectoryPath)) return;
            var hexPrefix = Hex(Encoding.UTF8.GetBytes(prefix));
            foreach (var path in Directory.EnumerateFiles(DirectoryPath, hexPrefix + "*"))
            {
                DeleteFile(path);
            }
        }
    }

    /// <summary>退出登录 / 换空间：整个目录连同索引一起清掉。</summary>
    public void Clear()
    {
        lock (_gate)
        {
            _memory.Clear();
            _used.Clear();
            _memoryBytes = 0L;
            _diskBytes = 0L;
            if (!Directory.Exists(DirectoryPath)) return;
            foreach (var path in Directory.EnumerateFiles(DirectoryPath)) DeleteFile(path);
        }
    }

    /// <summary>目录里现在的字节数（测试和"清理缓存"那条 UI 用得上）。</summary>
    public long DiskBytes
    {
        get
        {
            lock (_gate) return _diskBytes;
        }
    }

    private void Remember(string key, byte[] bytes)
    {
        if (_memory.TryGetValue(key, out var old)) _memoryBytes -= old.LongLength;
        _memory[key] = bytes;
        _memoryBytes += bytes.LongLength;
        Touch(key);
        while (_memory.Count > 0 && _memoryBytes > _memoryCapacity) DropMemory(Oldest());
    }

    private void DropMemory(string key)
    {
        if (!_memory.TryGetValue(key, out var bytes)) return;
        _memoryBytes -= bytes.LongLength;
        _memory.Remove(key);
        _used.Remove(key);
    }

    private void Touch(string key) => _used[key] = ++_clock;

    private string Oldest()
    {
        var oldest = _memory.Keys.First();
        var stamp = long.MaxValue;
        foreach (var entry in _used)
        {
            // 只有还在内存里的键才参与淘汰：磁盘命中回填时 Touch 过，顺序是真实的近远
            if (_memory.ContainsKey(entry.Key) && entry.Value < stamp)
            {
                stamp = entry.Value;
                oldest = entry.Key;
            }
        }

        return oldest;
    }

    private void TrimDisk()
    {
        if (_diskBytes <= _diskCapacity || !Directory.Exists(DirectoryPath)) return;

        // 按最后写入时间从旧到新删。命中时会把文件时间推后，所以"最近看过的图"不会先被清掉
        foreach (var path in Directory.EnumerateFiles(DirectoryPath)
                     .Select(p => (Path: p, Time: File.GetLastWriteTimeUtc(p)))
                     .OrderBy(item => item.Time))
        {
            if (_diskBytes <= _diskCapacity) break;
            DeleteFile(path.Path);
        }
    }

    private void DeleteFile(string path)
    {
        try
        {
            var length = FileLengthOf(path);
            File.Delete(path);
            _diskBytes -= length;
        }
        catch (IOException)
        {
        }
        catch (UnauthorizedAccessException)
        {
        }
    }

    private static long FileLength(string path) => new FileInfo(path).Length;

    private long FileLengthOf(string path)
    {
        try
        {
            return FileLength(path);
        }
        catch (FileNotFoundException)
        {
            return 0L;
        }
    }

    private static void TouchFile(string path)
    {
        try
        {
            File.SetLastWriteTimeUtc(path, DateTime.UtcNow);
        }
        catch (IOException)
        {
            // 时间戳推不回去只是让 LRU 排序粗一点，不值得影响这张图
        }
    }

    private string PathOf(string key)
    {
        var hex = Hex(Encoding.UTF8.GetBytes(key));
        // 键特别长时十六进制名会顶到路径长度上限，退化成定长摘要（代价只是按消息清理扫不到它，交给 LRU）
        if (hex.Length > MaxHexNameLength) hex = Hex(SHA256.HashData(Encoding.UTF8.GetBytes(key)));
        return Path.Combine(DirectoryPath, hex + ".img");
    }

    private static string Hex(byte[] bytes) => Convert.ToHexString(bytes).ToLowerInvariant();
}
