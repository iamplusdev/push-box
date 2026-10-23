using System;
using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace PushBox.Windows.Sync;

/// <summary>
/// The socket, as far as <see cref="PushConnection"/> cares. Pulling it out is what lets the §5.3
/// handshake be tested frame by frame against contracts/testvectors/vectors.json without a server, and the
/// assertions there are the ones that decide whether a forged handshake is refused.
/// </summary>
public interface IPushTransport
{
    Task ConnectAsync(Uri url, CancellationToken ct);

    /// <summary>Next text frame. Null means the peer closed the stream (EOF).</summary>
    Task<string?> ReceiveAsync(CancellationToken ct);

    Task SendAsync(string text, CancellationToken ct);

    Task CloseAsync(string reason, CancellationToken ct);

    /// <summary>
    /// Tear the socket down from another thread: used by the §5.3 "40s 无息判死" timer, whose only way
    /// to stop a blocked ReceiveAsync is to kill the socket under it.
    /// </summary>
    void Abort();
}

/// <summary>Real socket. One text message per §5.3 frame, UTF-8 decoded only once EndOfMessage lands.</summary>
public sealed class ClientWebSocketTransport : IPushTransport, IDisposable
{
    /// <summary>Server caps a frame at 32KB (§5.3); this is the guard rail for a peer that ignores its own limit.</summary>
    private const int MaxFrameBytes = 1 << 20;

    private readonly ClientWebSocket _socket = new();
    private readonly byte[] _chunk = new byte[16 * 1024];
    private readonly MemoryStream _pending = new();

    public Task ConnectAsync(Uri url, CancellationToken ct) => _socket.ConnectAsync(url, ct);

    public async Task<string?> ReceiveAsync(CancellationToken ct)
    {
        _pending.SetLength(0);
        while (true)
        {
            WebSocketReceiveResult result;
            try
            {
                result = await _socket.ReceiveAsync(new ArraySegment<byte>(_chunk), ct);
            }
            catch (WebSocketException e) when (e.WebSocketErrorCode == WebSocketError.ConnectionClosedPrematurely)
            {
                return null;
            }

            if (result.MessageType == WebSocketMessageType.Close) return null;
            if (result.MessageType != WebSocketMessageType.Text)
                throw new WebSocketException("PushBox 的 §5.3 只走文本帧，收到的是二进制帧");

            _pending.Write(_chunk, 0, result.Count);
            if (_pending.Length > MaxFrameBytes) throw new WebSocketException("单帧超过 1MB，按协议异常断开");
            if (result.EndOfMessage) return Encoding.UTF8.GetString(_pending.ToArray());
        }
    }

    public async Task SendAsync(string text, CancellationToken ct)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        await _socket.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, ct);
    }

    public async Task CloseAsync(string reason, CancellationToken ct)
    {
        if (_socket.State != WebSocketState.Open && _socket.State != WebSocketState.CloseReceived) return;
        try
        {
            await _socket.CloseAsync(WebSocketCloseStatus.NormalClosure, reason, ct);
        }
        catch (WebSocketException)
        {
            // 对端已经先断开：这里没有可报告的失败，断开原因由调用方给出
        }
        catch (ObjectDisposedException)
        {
        }
    }

    public void Abort() => _socket.Abort();

    public void Dispose()
    {
        _socket.Dispose();
        _pending.Dispose();
    }
}
