package dev.pushbox.server.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §5.2 头像的尺寸归一化。这份**故意不带** {@code @EnabledIf(objectStorageReady)}：
 * {@code AvatarFetchTest} 那五条要库和桶，本机的 33 条跳过里就有它们，等于这条规则在 CI 上没人看着。
 * 归一化本身是纯函数（字节进字节出），没有理由跟着门控一起被跳过。
 */
class AvatarNormalizeTest {

  /** 与 {@code PbProperties.Media.avatarSize} 的默认值一致；改默认值时这里要跟着改。 */
  private static final int SIZE = 256;

  @Test
  @DisplayName("① 1024×1024 的透明 PNG：收成 256×256，透明区域还是透明（不能铺白底）")
  void shrinksSquarePngAndKeepsAlpha() throws IOException {
    BufferedImage image = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    // 左半留全透明，右半涂不透明红：归一化之后这两半必须还是这个关系
    g.setColor(Color.RED);
    g.fillRect(512, 0, 512, 1024);
    g.dispose();
    byte[] png = encode(image, "png");

    AvatarImage.Normalized result = AvatarImage.normalize(png, "image/png", SIZE);

    assertThat(result.processed()).isTrue();
    assertThat(result.mime()).isEqualTo("image/png");
    assertThat(result.width()).isEqualTo(SIZE);
    assertThat(result.height()).isEqualTo(SIZE);
    assertThat(result.body().length).isLessThan(png.length);

    BufferedImage out = read(result.body());
    assertThat(out.getWidth()).isEqualTo(SIZE);
    assertThat(out.getHeight()).isEqualTo(SIZE);
    // 铺白底是 images[] 缩略图的口径（那边输出 jpg，没有 alpha）；头像输出 png，
    // 铺了白就会在客户端的圆角框里露出白方块
    assertThat(out.getColorModel().hasAlpha()).isTrue();
    assertThat(alphaAt(out, 32, 128)).as("左半必须还是全透明").isZero();
    assertThat(alphaAt(out, 224, 128)).as("右半必须还是不透明").isEqualTo(255);
    assertThat(out.getRGB(224, 128) & 0xFFFFFF).isEqualTo(0xFF0000);
  }

  @Test
  @DisplayName("② 900×300 的三色带 PNG：取的是中间那块正方形，左右两条完全不在结果里")
  void centerCropsToSquare() throws IOException {
    BufferedImage image = new BufferedImage(900, 300, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setColor(Color.RED);
    g.fillRect(0, 0, 300, 300);
    g.setColor(Color.GREEN);
    g.fillRect(300, 0, 300, 300);
    g.setColor(Color.BLUE);
    g.fillRect(600, 0, 300, 300);
    g.dispose();

    AvatarImage.Normalized result = AvatarImage.normalize(encode(image, "png"), "image/png", SIZE);

    assertThat(result.processed()).isTrue();
    assertThat(result.width()).isEqualTo(SIZE);
    assertThat(result.height()).isEqualTo(SIZE);
    BufferedImage out = read(result.body());
    // png 无损，所以这里可以严格判：中心是绿的，而且整张图找不到一枚红或蓝的像素。
    // 两端本来就是"中心裁剪填满"（Android ContentScale.Crop / WPF UniformToFill），
    // 服务端先裁一刀不会改变客户端看到的那张脸
    assertThat(out.getRGB(128, 128) & 0xFFFFFF).isEqualTo(0x00FF00);
    int offendingX = -1;
    int offendingY = -1;
    for (int y = 0; y < out.getHeight() && offendingX < 0; y++) {
      for (int x = 0; x < out.getWidth(); x++) {
        if ((out.getRGB(x, y) & 0xFFFFFF) != 0x00FF00) {
          offendingX = x;
          offendingY = y;
          break;
        }
      }
    }
    assertThat(offendingX)
        .as("整张图都该是中间那块绿，第一枚杂色像素在 x=%d y=%d", offendingX, offendingY)
        .isNegative();
  }

  @Test
  @DisplayName("③ jpeg 源：格式跟着源走，存完还是 jpeg（质量档与 §4.5 缩略图同一个 0.8）")
  void keepsJpegAsJpeg() throws IOException {
    BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    for (int x = 0; x < 800; x += 8) {
      g.setColor(new Color(x * 255 / 800, 90, 200 - x * 200 / 800));
      g.fillRect(x, 0, 8, 600);
    }
    g.dispose();
    byte[] jpeg = encode(image, "jpg");

    AvatarImage.Normalized result = AvatarImage.normalize(jpeg, "image/jpeg", SIZE);

    assertThat(result.processed()).isTrue();
    assertThat(result.mime()).isEqualTo("image/jpeg");
    assertThat(result.width()).isEqualTo(SIZE);
    assertThat(isJpeg(result.body())).as("落库的魔数必须还是 JPEG").isTrue();
    assertThat(result.body().length).isLessThan(jpeg.length);
  }

  @Test
  @DisplayName("④ 已经不大于目标边长：一字节都不动，但原尺寸照样报得出来（不放大）")
  void leavesSmallImagesAlone() throws IOException {
    BufferedImage image = new BufferedImage(200, 120, BufferedImage.TYPE_INT_ARGB);
    byte[] png = encode(image, "png");

    AvatarImage.Normalized result = AvatarImage.normalize(png, "image/png", SIZE);

    assertThat(result.processed()).isFalse();
    assertThat(result.body()).isSameAs(png);
    assertThat(result.mime()).isEqualTo("image/png");
    // 尺寸照报：日志里要能看出"这张本来就不用处理"，而不是"没解码"
    assertThat(result.width()).isEqualTo(200);
    assertThat(result.height()).isEqualTo(120);

    // 1×1 内联头像是被三处断言钉住的（MediaSsePipelineTest ④⑤、AvatarFetchTest ②）：
    // 落库字节必须与入站字节完全相同，所以"小图不重编码"不是优化而是硬约束
    byte[] onePixel = onePixelPng();
    AvatarImage.Normalized tiny = AvatarImage.normalize(onePixel, "image/png", SIZE);
    assertThat(tiny.processed()).isFalse();
    assertThat(tiny.body()).isEqualTo(onePixel);
    assertThat(tiny.width()).isEqualTo(1);
  }

  @Test
  @DisplayName("⑤ gif 与 webp 原样存：连解码都不试（gif 重编码丢动画，webp 是 ImageIO 解不了）")
  void passesAnimatedAndUndecodableFormatsThrough() {
    byte[] fakeGif = new byte[] {'G', 'I', 'F', '8', '9', 'a', 1, 2, 3};
    AvatarImage.Normalized gif = AvatarImage.normalize(fakeGif, "image/gif", SIZE);
    assertThat(gif.processed()).isFalse();
    assertThat(gif.body()).isSameAs(fakeGif);
    assertThat(gif.mime()).isEqualTo("image/gif");
    // width=0 就是"根本没解码"的记号：判格式在解码之前，白花一次读图的功夫是没必要的
    assertThat(gif.width()).isZero();

    byte[] fakeWebp = new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
    AvatarImage.Normalized webp = AvatarImage.normalize(fakeWebp, "image/webp", SIZE);
    assertThat(webp.processed()).isFalse();
    assertThat(webp.body()).isSameAs(fakeWebp);
    assertThat(webp.mime()).isEqualTo("image/webp");
    assertThat(webp.width()).isZero();
  }

  @Test
  @DisplayName("⑥ 魔数认得但解不动：回退原字节。头像是装饰字段，不值得为一次解码异常把它整个丢掉")
  void fallsBackToOriginalWhenUndecodable() {
    byte[] broken = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 1, 2, 3, 4, 5};

    AvatarImage.Normalized result = AvatarImage.normalize(broken, "image/png", SIZE);

    assertThat(result.processed()).isFalse();
    assertThat(result.body()).isSameAs(broken);
    assertThat(result.mime()).isEqualTo("image/png");
    assertThat(result.width()).isZero();
  }

  @Test
  @DisplayName("⑦ 目标边长 <=0 就是不处理：连大 PNG 都不解码，退化路径不能抛异常")
  void nonPositiveSizeDisablesProcessing() throws IOException {
    BufferedImage image = new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB);
    byte[] png = encode(image, "png");

    for (int size : new int[] {0, -1}) {
      AvatarImage.Normalized result = AvatarImage.normalize(png, "image/png", size);
      assertThat(result.processed()).as("size=%d", size).isFalse();
      assertThat(result.body()).as("size=%d", size).isSameAs(png);
      assertThat(result.width()).as("size=%d", size).isZero();
    }
  }

  private static byte[] onePixelPng() throws IOException {
    return encode(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png");
  }

  private static byte[] encode(BufferedImage image, String format) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertThat(ImageIO.write(image, format, out)).as("这台 JDK 得能写 %s", format).isTrue();
    return out.toByteArray();
  }

  private static BufferedImage read(byte[] body) throws IOException {
    BufferedImage image = ImageIO.read(new ByteArrayInputStream(body));
    assertThat(image).as("归一化之后必须还是一张能解开的图").isNotNull();
    return image;
  }

  private static int alphaAt(BufferedImage image, int x, int y) {
    return (image.getRGB(x, y) >>> 24) & 0xFF;
  }

  private static boolean isJpeg(byte[] data) {
    return data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8
        && (data[2] & 0xFF) == 0xFF;
  }
}
