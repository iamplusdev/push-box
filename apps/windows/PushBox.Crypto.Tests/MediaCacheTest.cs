using System;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using PushBox.Windows.Media;
using Xunit;

namespace PushBox.Crypto.Tests;

/// <summary>
/// 写盘挪到全局锁外面之后，最容易坏的是账：同一个键被并发写、或者写到一半被作废，
/// 磁盘上的字节数与缓存自己的计数会对不上，最后表现为 LRU 提前清掉刚看过的图、或者目录越攒越大。
/// </summary>
public sealed class MediaCacheTest : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(),
        "pushbox-media-cache-" + Guid.NewGuid().ToString("N"));

    private static readonly byte[] A = Enumerable.Repeat((byte)1, 1024).ToArray();
    private static readonly byte[] B = Enumerable.Repeat((byte)2, 2048).ToArray();

    [Fact]
    public void ConcurrentWritesOfOneKeyLeaveExactlyOneFileAndHonestAccounting()
    {
        var cache = new MediaCache(_dir, diskCapacityBytes: 1 << 20, memoryCapacityBytes: 0);

        Parallel.For(0, 32, i =>
            cache.Put("avatar#1", i % 2 == 0 ? A : B));

        var files = Directory.EnumerateFiles(_dir).Where(p => !p.EndsWith(".tmp")).ToArray();
        var only = Assert.Single(files);
        Assert.Equal(new FileInfo(only).Length, cache.DiskBytes);

        // 落盘内容只能是最后一次写成的那一份，不会出现"两个键的文件各写一半"
        var bytes = cache.Get("avatar#1");
        Assert.NotNull(bytes);
        Assert.True(bytes!.SequenceEqual(A) || bytes.SequenceEqual(B));
    }

    [Fact]
    public void AnEvictionAfterTheWritesTakesTheFileAndTheAccountingWithIt()
    {
        var cache = new MediaCache(_dir, diskCapacityBytes: 1 << 20, memoryCapacityBytes: 0);
        cache.Put("avatar#1", A);

        cache.Evict("avatar#1");

        Assert.Empty(Directory.EnumerateFiles(_dir));
        Assert.Equal(0L, cache.DiskBytes);
        Assert.Null(cache.Get("avatar#1"));
    }

    public void Dispose()
    {
        try
        {
            Directory.Delete(_dir, true);
        }
        catch (IOException)
        {
            // 用例结论已经拿到，临时目录留给系统清
        }
    }
}
