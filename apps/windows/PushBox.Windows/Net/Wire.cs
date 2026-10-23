using System.IO;
using System.Text;
using System.Text.Json;

namespace PushBox.Windows.Net;

/// <summary>
/// Outbound §5.3 frames. The client only ever sends the auth frame and pong (the server is the one
/// that pings), and device_id/token ride inside the auth frame — never in the URL, which gets copied
/// into reverse-proxy access logs (§5.3).
/// </summary>
public static class Wire
{
    public static string Auth(string deviceId, string token, long tsMillis, string sigB64)
    {
        var buffer = new MemoryStream();
        using (var writer = new Utf8JsonWriter(buffer))
        {
            writer.WriteStartObject();
            writer.WriteString("t", FrameTypes.Auth);
            writer.WriteString("device_id", deviceId);
            writer.WriteString("token", token);
            writer.WriteNumber("ts", tsMillis);
            writer.WriteString("sig", sigB64);
            writer.WriteEndObject();
        }
        return Encoding.UTF8.GetString(buffer.ToArray());
    }

    public static string Ping() => Simple(FrameTypes.Ping);

    public static string Pong() => Simple(FrameTypes.Pong);

    private static string Simple(string type) => "{\"t\":\"" + type + "\"}";
}
