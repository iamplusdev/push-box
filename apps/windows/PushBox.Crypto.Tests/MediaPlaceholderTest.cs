using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using PushBox.Windows.Controls;
using PushBox.Windows.Media;
// WPF 的控件体系里也有一个 MediaState，测试里要的始终是 PushBox 那一个
using MediaState = PushBox.Windows.Media.MediaState;

namespace PushBox.Crypto.Tests;

/// <summary>
/// 单图那一格的占位高来自库里那条消息的原图宽高（§4.5 抓到字节才回填 w/h）。
/// 断的是两件事：<b>字节到之前</b>那一格已经是最终高度，以及<b>字节到的那一刻</b>不再动它——
/// 忽高忽低的那一次改高度会把整行重新量排一遍，正落在"刚切完会话、已经贴到底"的那一帧上。
/// </summary>
public class MediaPlaceholderTest
{
    private static readonly MarkdownStyle Style = Paint.Style();

    [Fact]
    public void OneImageIsSizedFromTheStoredDimensionsBeforeAnyBytesArrive()
    {
        Sta.Run(() =>
        {
            // 1600×900 摆进去就是 round(220 × 900 / 1600) = 124，而不是 4:3 兜底的 165
            var column = (StackPanel)MediaStrip.Build(1, _ => NeverReturns(), Style,
                naturalWidth: 1600, naturalHeight: 900);
            var single = (Border)column.Children[0];
            Assert.Equal(220d, single.Width);
            Assert.Equal(124d, single.Height);
        });
    }

    [Fact]
    public void BytesThatDecodeToADifferentShapeDoNotMoveThatSlotAgain()
    {
        Sta.Run(() =>
        {
            // 解码出来的这张是 4×4，按老算法会把那一格撑到 220；库里已经带过比例，就不该再改
            var column = (StackPanel)MediaStrip.Build(1, _ => Ready(Png(4, 4)), Style,
                naturalWidth: 1600, naturalHeight: 900);
            var single = (Border)column.Children[0];
            MediaStrip.Realize(column);
            Sta.PumpFor(120);
            Assert.Equal(124d, single.Height);
            Assert.IsType<Image>(single.Child);
        });
    }

    [Fact]
    public void NoStoredDimensionsKeepsTheFourThreeFallbackAndTheDecodedRatioStillGrowsTheSlot()
    {
        Sta.Run(() =>
        {
            var column = (StackPanel)MediaStrip.Build(1, _ => Ready(Png(4, 8)), Style);
            var single = (Border)column.Children[0];
            Assert.Equal(165d, single.Height);
            MediaStrip.Realize(column);
            Sta.PumpFor(120);
            // 4×8 是 1:2，按 220 宽就是 440，封顶在 400——和宽高那条路同一个式子
            Assert.Equal(400d, single.Height);
        });
    }

    [Fact]
    public void StoredDimensionsClampToTheSameBandTheDecodedPathUses()
    {
        Assert.Equal(88d, MediaStrip.RatioHeight(1600, 100));
        Assert.Equal(400d, MediaStrip.RatioHeight(900, 4000));
        // 宽高缺失或为 0（服务端还没抓到）都退回兜底，不能算出 0 高的一格
        Assert.Equal(MediaStrip.SingleHeight, MediaStrip.RatioHeight(0, 0));
    }

    [Fact]
    public void GridCellsStaySquareWhateverTheStoredDimensionsSay()
    {
        Sta.Run(() =>
        {
            var column = (StackPanel)MediaStrip.Build(3, _ => NeverReturns(), Style,
                naturalWidth: 1600, naturalHeight: 900);
            var row = (StackPanel)column.Children[0];
            foreach (UIElement cell in row.Children)
                Assert.Equal(88d, ((Border)cell).Height);
        });
    }

    private static Task<MediaAsset> NeverReturns() => new TaskCompletionSource<MediaAsset>().Task;

    private static Task<MediaAsset> Ready(byte[] png) =>
        Task.FromResult(new MediaAsset(MediaState.Ready, png, "image/png"));

    /// <summary>现画一张指定尺寸的 PNG：让 WPF 自己编码，比在测试里嵌一段来路不明的 base64 清楚。</summary>
    private static byte[] Png(int width, int height)
    {
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
            dc.DrawRectangle(Brushes.Red, null, new Rect(0, 0, width, height));
        var target = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32);
        target.Render(visual);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(target));
        using var stream = new MemoryStream();
        encoder.Save(stream);
        return stream.ToArray();
    }
}
