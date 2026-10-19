package dev.pushbox.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * §11.2：pb.s3.* 的必填项要在建客户端这一步就把话说死。
 *
 * <p>这里刻意不碰网络——S3Client 是懒连接的，所以"配齐了能建出来"和"留空了拒绝启动"两条
 * 都能在没有任何桶的环境里跑，每条 {@code mvn test} 都会执行。
 */
class S3ConfigTest {

  @Test
  @DisplayName("① endpoint/bucket/两把钥任一留空都拒绝启动，并且报错点出是哪一个键")
  void blankRequiredValuesRefuseToStart() {
    for (String key : new String[] {"pb.s3.endpoint", "pb.s3.bucket", "pb.s3.access-key",
        "pb.s3.secret-key"}) {
      for (String blank : new String[] {null, "", "   "}) {
        PbProperties props = complete();
        set(props.getS3(), key, blank);
        assertThatThrownBy(() -> new S3Config().s3Client(props))
            .as(key + " = " + blank)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(key);
      }
    }
  }

  @Test
  @DisplayName("② 配齐就建得出客户端：校验只拦空值，不去探测桶是否可达")
  void completeConfigBuildsClientWithoutTouchingNetwork() {
    try (S3Client client = new S3Config().s3Client(complete())) {
      assertThat(client).isNotNull();
    }
  }

  private static PbProperties complete() {
    PbProperties.S3 s3 = new PbProperties.S3();
    s3.setEndpoint("http://127.0.0.1:1");
    s3.setBucket("pushbox");
    s3.setAccessKey("ak");
    s3.setSecretKey("sk");
    PbProperties props = new PbProperties();
    props.setS3(s3);
    return props;
  }

  private static void set(PbProperties.S3 s3, String key, String value) {
    switch (key) {
      case "pb.s3.endpoint" -> s3.setEndpoint(value);
      case "pb.s3.bucket" -> s3.setBucket(value);
      case "pb.s3.access-key" -> s3.setAccessKey(value);
      case "pb.s3.secret-key" -> s3.setSecretKey(value);
      default -> throw new IllegalArgumentException("未覆盖的键: " + key);
    }
  }
}
