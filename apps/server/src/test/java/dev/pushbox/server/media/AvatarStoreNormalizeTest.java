package dev.pushbox.server.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.mybatisflex.core.row.Db;
import dev.pushbox.core.crypto.Hashes;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.AbuseControlService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §5.2 归一化接进 {@code storeAvatar} 之后的真链路。{@code AvatarNormalizeTest} 那七条只证明纯函数对，
 * 这里证明三件在函数外面才发生的事：配置项真被读到、存进对象的字节真被换掉、换头像时延用封装钥那条
 * 路径没被顺手改坏。
 *
 * <p>门是 {@code databaseReady} 而不是 {@code objectStorageReady}：进出都走 {@link MediaObjects} 的
 * 抽象口，本地目录模式一样跑得动，所以这一条不跟着 media 那 5 个类在开发机上一起被跳过。
 * 头像一律用内联 data: 图，整条链路不出网。
 */
@EnabledIf("dev.pushbox.server.test.TestConfig#databaseReady")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AvatarStoreNormalizeTest {

  private static final String CHANNEL = "AVNORM01";
  private static final String GROUP = "avnorm-group";

  @Autowired
  private PbStore store;
  @Autowired
  private MediaObjects objects;
  @Autowired
  private MediaFetchService fetcher;
  @Autowired
  private AbuseControlService abuse;

  private final List<String> keys = new ArrayList<>();

  @BeforeEach
  void seed() {
    cleanUp();
    store.insertChannel(CHANNEL, "0".repeat(64), GROUP, "头像归一化测试频道", "error", 4,
        System.currentTimeMillis());
  }

  @AfterEach
  void cleanUp() {
    for (String key : keys) {
      objects.delete(key);
    }
    Db.updateBySql("DELETE FROM senders WHERE channel_no = ?", CHANNEL);
    Db.updateBySql("DELETE FROM channels WHERE channel_no = ?", CHANNEL);
    keys.clear();
  }

  @Test
  @DisplayName("① 超尺寸内联头像落库时被收成正方形，库里那把键照常写好")
  void shrinksOversizedAvatarOnStore() {
    String sender = "avnorm-big";
    byte[] png = png(400, 300);
    String key = track(sender);
    seedSender(sender, png);

    assertThat(fetcher.storeAvatar(CHANNEL, sender, inline(png))).isEqualTo(key);
    assertThat(store.findSenderAvatar(CHANNEL, sender).getString("avatar_key")).isEqualTo(key);

    byte[] stored = objects.load(key, null).body();
    BufferedImage image = read(stored);
    assertThat(image.getWidth()).isEqualTo(256);
    assertThat(image.getHeight()).isEqualTo(256);
    assertThat(isPng(stored)).as("png 进 png 出，透明才不会被换成白底").isTrue();
    assertThat(stored.length).isLessThan(png.length);
  }

  @Test
  @DisplayName("② avatar-size 关到 0：字节原样落库，第三方给多大就存多大")
  void storesOriginalWhenAvatarSizeDisabled() {
    String sender = "avnorm-off";
    byte[] png = png(400, 300);
    String key = track(sender);
    seedSender(sender, png);
    PbProperties off = new PbProperties();
    off.getMedia().setAvatarSize(0);
    MediaFetchService noResize = new MediaFetchService(store, objects, off, abuse);
    try {
      assertThat(noResize.storeAvatar(CHANNEL, sender, inline(png))).isEqualTo(key);
    } finally {
      noResize.shutdown();
    }

    byte[] stored = objects.load(key, null).body();
    assertThat(Hashes.sha256Hex(stored)).as("关掉归一化就该一字节不动").isEqualTo(Hashes.sha256Hex(png));
    BufferedImage image = read(stored);
    assertThat(image.getWidth()).isEqualTo(400);
    assertThat(image.getHeight()).isEqualTo(300);
  }

  @Test
  @DisplayName("③ 本来就小的头像一字节都不动：归一化不许把 1x1 放大")
  void keepsSmallAvatarByteIdentical() {
    String sender = "avnorm-small";
    byte[] png = png(1, 1);
    String key = track(sender);
    seedSender(sender, png);

    assertThat(fetcher.storeAvatar(CHANNEL, sender, inline(png))).isEqualTo(key);

    assertThat(objects.load(key, null).body()).isEqualTo(png);
  }

  /** 换头像是在同一个键上覆盖写，所以每次都要把这一格的对象登记进清理名单。 */
  private String track(String sender) {
    String key = MediaObjects.avatarKey(CHANNEL, MediaObjects.avatarToken(sender));
    keys.add(key);
    return key;
  }

  private void seedSender(String sender, byte[] png) {
    store.upsertSender(CHANNEL, sender, "归一化", "data:sha256:" + Hashes.sha256Hex(png),
        MediaObjects.avatarToken(sender), System.currentTimeMillis());
  }

  private static AvatarSource inline(byte[] png) {
    AvatarSource source =
        AvatarSource.parse("data:image/png;base64," + Base64.getEncoder().encodeToString(png));
    assertThat(source).as("内联头像必须能被解析，否则这一格测的是失败分支").isNotNull();
    return source;
  }

  /** 横向渐变：既不是纯色（纯色压完可能比归一化后的还小），也不至于大过 512KB 的内联上限。 */
  private static byte[] png(int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) {
        image.setRGB(x, y, 0xFF000000 | ((x * 255 / Math.max(1, width - 1)) << 16)
            | ((y * 255 / Math.max(1, height - 1)) << 8) | 0x40);
      }
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      assertThat(ImageIO.write(image, "png", out)).isTrue();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  private static BufferedImage read(byte[] body) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(body));
      assertThat(image).as("落库的字节必须还能解成图").isNotNull();
      return image;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean isPng(byte[] data) {
    return data != null && data.length > 4 && (data[0] & 0xFF) == 0x89 && data[1] == 'P'
        && data[2] == 'N' && data[3] == 'G';
  }
}
