package dev.pushbox.server.media;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * §5.2 头像的尺寸归一化：外链头像第三方给多大就存多大，而客户端只把它画进 40dp（Android）/
 * 44px（Windows）的圆角框里，1024×1024 的原图对谁都没有用。这里把它收成边长 {@code size} 的
 * 正方形，字节和传输都跟着小一个数量级。
 *
 * <p>四条刻意的边界：
 * <ul>
 *   <li>**不放大**。小图放糊了不如原样，而且"1×1 内联图存完字节不变"是被断言钉住的
 *       （{@code MediaSsePipelineTest} ④⑤ 与 {@code AvatarFetchTest} ②）。</li>
 *   <li>**只重编码 png 与 jpeg**。gif 重编码会丢掉动画，webp 是 JDK 的 ImageIO 根本解不了
 *       （与 §4.5 缩略图同一口径），这两类原样存。</li>
 *   <li>**输出格式随源格式**，所以 png 的透明原样保住。§4.5 缩略图要铺白底是因为它一律输出 jpg，
 *       而 jpg 没有 alpha 通道；头像铺了白就会在客户端的圆角框里露出白方块。</li>
 *   <li>**处理失败一律回退原字节**。头像是装饰字段，为一次编解码异常把头像整个丢掉不划算。</li>
 * </ul>
 */
final class AvatarImage {

  private static final Logger log = LoggerFactory.getLogger(AvatarImage.class);

  private AvatarImage() {}

  /**
   * @param width  处理后的宽；没解码过（gif/webp/开关关掉）时为 0
   * @param height 处理后的高；同上
   * @param processed 字节是否真被换掉。{@code false} 时 {@code body} 与入参是同一个数组
   */
  record Normalized(byte[] body, String mime, int width, int height, boolean processed) {}

  /** @param size 目标边长（正方形）；{@code <= 0} 表示不处理。 */
  static Normalized normalize(byte[] body, String mime, int size) {
    if (size <= 0 || body == null || body.length == 0) {
      return new Normalized(body, mime, 0, 0, false);
    }
    String format;
    if ("image/png".equals(mime)) {
      format = "png";
    } else if ("image/jpeg".equals(mime)) {
      format = "jpg";
    } else {
      return new Normalized(body, mime, 0, 0, false);
    }

    BufferedImage image;
    try {
      image = ImageIO.read(new ByteArrayInputStream(body));
    } catch (IOException | RuntimeException e) {
      image = null;
    }
    if (image == null) {
      // 魔数认得但解不动（截断的 png、伪装成 png 的乱字节）：原样存，读侧至少还有机会
      return new Normalized(body, mime, 0, 0, false);
    }
    int width = image.getWidth();
    int height = image.getHeight();
    if (Math.max(width, height) <= size) {
      return new Normalized(body, mime, width, height, false);
    }

    // 两端本来就是"中心裁剪填满"（Android 的 ContentScale.Crop、WPF 的 UniformToFill），
    // 所以服务端先裁成正方形不会改变客户端看到的那张脸，只会让字节更少
    int side = Math.min(width, height);
    byte[] normalized;
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      var builder = Thumbnails.of(image)
          .sourceRegion((width - side) / 2, (height - side) / 2, side, side)
          .size(size, size)
          .keepAspectRatio(false)
          .outputFormat(format);
      if (!"png".equals(format)) {
        // 与 §4.5 缩略图同一个质量档。png 是无损格式，传了反而会被 Thumbnailator 拒
        builder.outputQuality(0.8);
      }
      builder.toOutputStream(out);
      normalized = out.toByteArray();
    } catch (IOException | RuntimeException e) {
      log.warn("头像归一化失败，按原字节存 {}x{}: {}", width, height, e.toString());
      return new Normalized(body, mime, width, height, false);
    }
    return new Normalized(normalized, "jpg".equals(format) ? "image/jpeg" : "image/png",
        size, size, true);
  }
}
