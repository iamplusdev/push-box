package dev.pushbox.server.config;

import java.net.URI;
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

  @Bean
  public S3Client s3Client(PbProperties props) {
    String storageType = props.getStorage().getType();
    if ("local".equalsIgnoreCase(storageType)) {
      // 本地存储模式下不需要 S3Client，返回一个占位符以避免 Spring 依赖注入失败
      return null;
    }

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
      throw new IllegalStateException(name + " 不能为空（容器里它由 PB_S3_* 透传，见 deploy/env/prod.env.example）");
    }
  }

  private static StaticCredentialsProvider credentials(PbProperties.S3 s3) {
    return StaticCredentialsProvider.create(
        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey()));
  }
}
