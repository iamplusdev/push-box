using System;

namespace PushBox.Windows.Media;

/// <summary>
/// 按魔数认图片类型。缓存只存字节（§4.5 的 media_cache 没有地方放响应头），而磁盘命中那条路
/// 拿不到当初的 Content-Type，所以这里用文件头补回来。
///
/// 敢这么认的前提是 §4.5 的抓取侧：入库前已经过 Content-Type 白名单 + 魔数复验，桶里只可能是
/// 这四种（SVG 在服务端就被拒了，永远不会出现在这里）。
/// </summary>
public static class ImageMime
{
    public static string? Sniff(byte[]? bytes)
    {
        if (bytes is not { Length: > 12 }) return null;

        // jpeg：FFD8FF；gif：GIF87a/GIF89a 都认
        if (bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF) return "image/jpeg";
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') return "image/gif";

        // png：89 50 4E 47 0D 0A 1A 0A
        if (bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
            && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A)
        {
            return "image/png";
        }

        // webp：RIFF .... WEBP
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
            && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P')
        {
            return "image/webp";
        }

        return null;
    }
}
