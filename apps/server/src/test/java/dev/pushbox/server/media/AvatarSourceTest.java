package dev.pushbox.server.media;

import static org.assertj.core.api.Assertions.assertThat;

import dev.pushbox.core.crypto.Hashes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §5.1 的 sender_avatar 入口解析。这里决定"要不要为这条消息出网"和"落库的那一列是什么"，
 * 判错的后果不是报错而是把任意字符串带进头像列，或者把几 MB 的 base64 写进一个 1024 字符的字段。
 */
class AvatarSourceTest {

  /** 1x1 PNG。 */
  private static final String PNG_BASE64 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

  @Test
  @DisplayName("① http(s) URL 原样保留并需要出网抓取；超长 URL 直接当没给")
  void keepsHttpUrls() {
    AvatarSource source = AvatarSource.parse("  https://cdn.example.com/a/b.png?x=1  ");

    assertThat(source).isNotNull();
    assertThat(source.source()).isEqualTo("https://cdn.example.com/a/b.png?x=1");
    assertThat(source.url()).isEqualTo(source.source());
    assertThat(source.needsFetch()).isTrue();

    assertThat(AvatarSource.parse("https://cdn.example.com/" + "p".repeat(AvatarSource.MAX_URL_CHARS)))
        .isNull();
  }

  @Test
  @DisplayName("② 非 http(s) 的写法一律当成没给头像：坏字段不值得让整条消息 400")
  void ignoresOtherSources() {
    assertThat(AvatarSource.parse(null)).isNull();
    assertThat(AvatarSource.parse("   ")).isNull();
    assertThat(AvatarSource.parse("javascript:alert(1)")).isNull();
    assertThat(AvatarSource.parse("ftp://example.com/a.png")).isNull();
    assertThat(AvatarSource.parse("/avatars/me.png")).isNull();
    assertThat(AvatarSource.parse("example.com/a.png")).isNull();
    assertThat(AvatarSource.parse("data:text/plain;base64,AAA=")).isNull();
  }

  @Test
  @DisplayName("③ 内联 data:image 只留 sha256 指纹，字节交给转存，绝不落进 avatar_url 列")
  void inlinesFingerprintNotBytes() {
    byte[] bytes = java.util.Base64.getDecoder().decode(PNG_BASE64);

    AvatarSource source = AvatarSource.parse("data:image/png;base64," + PNG_BASE64);

    assertThat(source).isNotNull();
    assertThat(source.source()).isEqualTo("data:sha256:" + Hashes.sha256Hex(bytes));
    assertThat(source.source()).hasSizeLessThan(128);
    assertThat(source.url()).isNull();
    assertThat(source.needsFetch()).isFalse();
    assertThat(source.inlineBytes()).isEqualTo(bytes);
    assertThat(source.declaredMime()).isEqualTo("image/png");
  }

  @Test
  @DisplayName("④ 内联图的坏写法都退回「没给头像」：缺逗号、非 base64、空字节、超过 512KB")
  void rejectsBadInline() {
    assertThat(AvatarSource.parse("data:image/png;base64")).isNull();
    assertThat(AvatarSource.parse("data:image/png;base64,")).isNull();
    assertThat(AvatarSource.parse("data:image/png," + PNG_BASE64)).isNull();
    assertThat(AvatarSource.parse("data:image/png;base64,!!!not-base64!!!")).isNull();
    assertThat(AvatarSource.parse(
        "data:image/png;base64," + "A".repeat(AvatarSource.MAX_INLINE_CHARS + 1))).isNull();
    // 解码后 0 字节：魔数复验必然失败，不如在入口就拒掉
    assertThat(AvatarSource.parse("data:image/png;base64,"
        + java.util.Base64.getEncoder().encodeToString(new byte[0]))).isNull();
  }

  @Test
  @DisplayName("⑤ 类型大小写与空白不影响解析，但 source 保留原值（它是判断来源变没变的依据）")
  void normalizesForParsingOnly() {
    AvatarSource source = AvatarSource.parse("DATA:IMAGE/GIF;BASE64," + PNG_BASE64);

    assertThat(source).isNotNull();
    assertThat(source.declaredMime()).isEqualTo("image/gif");
    assertThat(source.inlineBytes()).isNotEmpty();
  }
}
