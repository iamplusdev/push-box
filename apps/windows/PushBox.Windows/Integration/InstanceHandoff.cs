using System.IO;
using System.IO.Pipes;
using System.Security.Cryptography;
using System.Text;

namespace PushBox.Windows.Integration;

/// <summary>
/// 单实例与"把点通知那一下交给已经在跑的那个实例"。
///
/// 为什么必须有它：§8.1 说托盘常驻，而通知点击走的是自定义协议（见 <see cref="Notify.ToastProtocol"/>）——
/// shell 执行协议处理器时**不看应用是不是已经活着**，它只会再起一个进程。没有这一层，
/// 用户点一条通知就多一个托盘图标、多一条长连接、多一个开同一份 SQLite 的写者。
///
/// 所以分工是：抢得到 mutex 的那个实例是主实例，起管道服务等着收参数；抢不到的那个把 argv 里那条
/// URI 写进管道然后立刻退出。名字由 exe 全路径派生，两处都要它：
/// 换一份安装（比如从 <c>bin\Debug</c> 挪到 <c>Program Files</c>）不该互相顶掉。
/// </summary>
public sealed class InstanceHandoff : IDisposable
{
    /// <summary>派生名字用的那个"安装标识"，同一目录下的两份构建算同一个实例锁。</summary>
    private readonly string _key;

    private Mutex? _mutex;
    private CancellationTokenSource? _stop;
    private Task? _loop;

    public InstanceHandoff(string exePath) => _key = KeyOf(exePath);

    public bool IsPrimary => _mutex != null;

    /// <summary>主实例的判定：抢到才算。抢不到就直接走"转发然后退出"那条路。</summary>
    public bool TryBecomePrimary()
    {
        if (_mutex != null) return true;
        // initiallyOwned 必须是 true，而且要读 createdNew：**Mutex(false, name) + WaitOne(0) 是错的**——
        // 同名互斥体已经存在时构造函数不把所有权交给本线程，于是"谁都没持有"和"别人持有"在这条路上读起来一样，
        // 第二个实例也会自认为是主实例，多出一条长连接和一个开同一份库的写者
        var mutex = new Mutex(true, MutexName(_key), out var createdNew);
        try
        {
            if (createdNew)
            {
                _mutex = mutex;
                return true;
            }

            // 已经存在：只可能是上一个主实例被杀（而不是自己 release）之后留下的 abandoned 锁。
            // 等得到就是接管，等不到就是真的有一个活着的主实例在跑。
            try
            {
                if (!mutex.WaitOne(TimeSpan.Zero, false))
                {
                    mutex.Dispose();
                    return false;
                }
            }
            catch (AbandonedMutexException)
            {
                // 这一句等待已经把锁交给我们了，异常本身就是"上一个持有者没归还"的通知
            }

            _mutex = mutex;
            return true;
        }
        catch (Exception)
        {
            mutex.Dispose();
            throw; // 名字非法或权限被拒：这是装配错误，不该伪装成"已经有另一个实例在跑"
        }
    }

    /// <summary>主实例收参数。<paramref name="onArgument"/> 在管道线程上被调用，回调方自己转回 UI 线程。</summary>
    public void StartListening(Action<string> onArgument)
    {
        if (!IsPrimary) throw new InvalidOperationException("只有主实例能收参数");
        var cts = new CancellationTokenSource();
        _stop = cts;
        _loop = Task.Run(() => ServeAsync(PipeName(_key), onArgument, cts.Token));
    }

    /// <summary>非主实例把 argv 里那条 URI 递给主实例。连不上或写不进去就回 false，调用方按"没送达"处理。</summary>
    public bool Send(string argument, int timeoutMs = 2000)
    {
        var deadline = Environment.TickCount + Math.Max(0, timeoutMs);
        while (Environment.TickCount < deadline)
        {
            try
            {
                using var client = new NamedPipeClientStream(".", PipeName(_key), PipeDirection.Out);
                client.Connect(Math.Max(50, Math.Min(500, deadline - Environment.TickCount)));
                using var writer = new StreamWriter(client, new UTF8Encoding(false)) { AutoFlush = true };
                writer.WriteLine(argument);
                client.WaitForPipeDrain();
                return true;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException or TimeoutException)
            {
                Thread.Sleep(30); // 主实例正在起，管道还没就位：这一等是点通知到打开会话之间唯一的延迟
            }
        }

        return false;
    }

    private static async Task ServeAsync(string pipeName, Action<string> onArgument, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            NamedPipeServerStream? server = null;
            try
            {
                server = new NamedPipeServerStream(pipeName, PipeDirection.In, 1,
                    PipeTransmissionMode.Byte, PipeOptions.Asynchronous);
                await server.WaitForConnectionAsync(ct).ConfigureAwait(false);
                using var reader = new StreamReader(server, Encoding.UTF8);
                var line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
                if (string.IsNullOrEmpty(line)) continue;
                try
                {
                    onArgument(line);
                }
                catch (Exception)
                {
                    // 回调里炸了（比如那一瞬间窗口正在关）不该把服务线程带走：下一条通知还要能进来
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception)
            {
                // 管道被对面提前断开、名字被占：换一个实例继续等，而不是让单实例这件事悄悄失效
                if (!ct.IsCancellationRequested) await Task.Delay(100, ct).ContinueWith(_ => { })
                    .ConfigureAwait(false);
            }
            finally
            {
                server?.Dispose();
            }
        }
    }

    /// <summary>安装标识：exe 全路径的小写 sha256 前 12 位。取前 12 位是因为这两个名字要出现在
    /// 任务管理器和命名管道列表里，长到看不清就等于没有。</summary>
    private static string KeyOf(string exePath)
    {
        var normalized = (exePath ?? string.Empty).Trim().Replace('\\', '/').ToLowerInvariant();
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(normalized));
        return Convert.ToHexString(hash[..6]).ToLowerInvariant();
    }

    public static string MutexNameFor(string exePath) => MutexName(KeyOf(exePath));

    public static string PipeNameFor(string exePath) => PipeName(KeyOf(exePath));

    private static string MutexName(string key) => "PushBox.Windows." + key;

    private static string PipeName(string key) => "PushBox." + key;

    public void Dispose()
    {
        _stop?.Cancel();
        var mutex = _mutex;
        _mutex = null;
        if (mutex == null) return;
        try
        {
            mutex.ReleaseMutex();
        }
        catch (ApplicationException)
        {
            // 已经不是我们持有的了（被 abandon 过又转手）：释放别人的锁不该做成，直接丢掉句柄
        }

        mutex.Dispose();
        try
        {
            _loop?.Wait(TimeSpan.FromMilliseconds(200));
        }
        catch (Exception)
        {
            // 收工不等服务线程：它会被取消自己退出
        }

        _stop?.Dispose();
    }
}
