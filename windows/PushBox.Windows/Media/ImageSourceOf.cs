using System.IO;
using System.Windows.Media.Imaging;

namespace PushBox.Windows.Media;

/// <summary>
/// 取到的字节变 <see cref="BitmapImage"/>。<c>CacheOption.OnLoad</c> + <see cref="Freeze"/> 是硬要求：
/// 默认的 OnDemand 会让解码器一直攥着那个 MemoryStream，槽位换图时前一张永远不释放；
/// 不 Freeze 则每个位图都只能待在它自己那条线程上，列表复用时会炸跨线程访问。
/// </summary>
public static class ImageSourceOf
{
    public static BitmapImage? FromBytes(byte[]? bytes)
    {
        if (bytes is not { Length: > 0 }) return null;
        try
        {
            using var stream = new MemoryStream(bytes);
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = stream;
            image.EndInit();
            image.Freeze();
            return image;
        }
        catch (System.Exception e) when (e is IOException or System.InvalidOperationException
                                         or System.NotSupportedException)
        {
            // 桶里躺着一张解码不了的字节（服务端 MIME 白名单之外的历史对象）时，
            // 这个槽位回到占位而不是把整个会话列表带崩
            return null;
        }
    }
}
