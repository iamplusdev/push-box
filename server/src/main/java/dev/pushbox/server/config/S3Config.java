package dev.pushbox.server.config;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** §4.5：对象存储支持 S3 协议（RustFS/MinIO/OSS）或本地文件系统，由 pb.storage.type 控制。 */
@Configuration
public class S3Config {

  /**
   * local 模式下这个 bean 干脆不存在，而不是"存在但值为 null"。
   *
   * <p>{@code @Bean} 方法 return null 时 Spring 注册的是 {@code NullBean}，而 {@code NullBean} 对
   * 必须注入的构造参数等于"没有这个 bean"：{@code MediaObjects} 起手就撞上
   * {@code NoSuchBeanDefinitionException: No qualifying bean of type 'S3Client'}，进程在
   * DataSource 之后、任何业务调用之前死掉。症状是"选了本地存储反而起不来"，
   * 而单测全是 {@code new MediaObjects(null, props)} 手工构造，永远照不出这条接线，
   * 所以本地存储这条路只在单元测试里通过过一次。
   *
   * <p>{@code matchIfMissing=false} 与 {@link PbProperties.Storage#getType()} 的默认值 local 一致：
   * 读不到配置时该走本地目录，而不是拿一组空凭据去建 S3 客户端。
   * {@code havingValue} 的比较不区分大小写，所以 {@code type=S3} 也认。
   */
  @Bean
  @ConditionalOnProperty(prefix = "pb.storage", name = "type", havingValue = "s3")
  public S3Client s3Client(PbProperties props) {
    PbProperties.S3 s3 = props.getS3();
    // 空值必须在这里拒绝启动。晚一拍的代价是：容器起来了、TCP 健康检查过了、/healthz 也说 db=up，
    // 但每条带图的消息一路停在 failed，症状要等到用户报"图不见了"才看得见。
    require(s3.getEndpoint(), "pb.s3.endpoint");
    require(s3.getBucket(), "pb.s3.bucket");
    require(s3.getAccessKey(), "pb.s3.access-key");
    require(s3.getSecretKey(), "pb.s3.secret-key");
    return S3Client.builder()
        .endpointOverride(URI.create(s3.getEndpoint()))
        .region(Region.of(s3.getRegion()))
        .credentialsProvider(credentials(s3))
        .serviceConfiguration(S3Configuration.builder()
            .pathStyleAccessEnabled(s3.isPathStyle())
            .build())
        .build();
  }

  private static void require(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " 不能为空：安装页里勾了「改用对象存储」就要把这几项填全，"
          + "或者删掉 data/config/pushbox.json 重新走一遍安装");
    }
  }

  private static StaticCredentialsProvider credentials(PbProperties.S3 s3) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey()));
  }
}
