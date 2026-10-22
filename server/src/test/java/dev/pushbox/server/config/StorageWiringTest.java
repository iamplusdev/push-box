package dev.pushbox.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pushbox.server.media.MediaKeyService;
import dev.pushbox.server.media.MediaObjects;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * §4.5 存储后端的接线只在真容器里才成立，所以这些用例跑的是 Spring 上下文，不是手工 {@code new}。
 *
 * <p>{@code pb.storage.type=local} 是 README 与部署文档都写明的本机起步路径，而它曾经起不来：
 * {@code S3Config} 在 local 下 {@code return null}，Spring 把 null 注册成 {@code NullBean}，
 * {@code MediaObjects} 那个必须注入的 {@code S3Client} 参数于是报
 * {@code NoSuchBeanDefinitionException}。单测全部直接构造 {@code MediaObjects}，这一层看不见。
 */
class StorageWiringTest {

  @Test
  @DisplayName("① local 模式：容器起得来，而且 S3Client 这个 bean 根本不存在")
  void localStorageModeStartsWithoutS3Client() {
    try (AnnotationConfigApplicationContext ctx = context("local")) {
      assertThat(ctx.getBean(MediaObjects.class)).isNotNull();
      // 判"没有 bean"而不是"bean 是 null"：后者正是原来那个坑的样子， NullBean 照样能让这条过了。
      assertThat(ctx.getBeanNamesForType(S3Client.class)).isEmpty();
    }
  }

  @Test
  @DisplayName("② s3 模式：客户端照常建出来，别把默认路径一起关掉")
  void s3StorageModeBuildsTheClient() {
    try (AnnotationConfigApplicationContext ctx = context("s3")) {
      assertThat(ctx.getBeanNamesForType(S3Client.class)).hasSize(1);
      assertThat(ctx.getBean(MediaObjects.class)).isNotNull();
    }
  }

  @Test
  @DisplayName("③ 大小写照收，尾巴多个空格就不收：报的是「只认 s3|local」而不是 NullBean 堆栈")
  void storageTypeIsTrimmedButNotFuzzyMatched() {
    try (AnnotationConfigApplicationContext ctx = context("S3")) {
      assertThat(ctx.getBeanNamesForType(S3Client.class)).hasSize(1);
    }
    // "s3 " 才是会掉进坑里的那个值：条件注解比的是原始字符串，不认它；而 MediaObjects 自己 trim 过，
    // 于是两边都不沾——必须是人话报错，不能是上面①那个 NoSuchBeanDefinitionException。
    assertThatThrownBy(() -> new MediaObjects(null, props("s3 ")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("只认 s3|local");
  }

  private static AnnotationConfigApplicationContext context(String storageType) {
    AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
    // @ConditionalOnProperty 读的是 Environment 里那个原始属性，不是绑定后的 PbProperties，
    // 所以两边都要给：只设一个的话测的就不是生产那条路径了。
    ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
        "storage-type", Map.of("pb.storage.type", storageType)));
    ctx.getBeanFactory().registerSingleton("pbProperties", props(storageType));
    // 这条用例不接数据库：随机主钥模式不需要 app_state，传 null 的 PbStore 即可。
    ctx.getBeanFactory().registerSingleton("mediaKeyService",
        new MediaKeyService(props(storageType), null));
    ctx.register(S3Config.class, MediaObjects.class);
    ctx.refresh();
    return ctx;
  }

  private static PbProperties props(String storageType) {
    PbProperties.S3 s3 = new PbProperties.S3();
    s3.setEndpoint("http://127.0.0.1:1");
    s3.setBucket("pushbox");
    s3.setAccessKey("ak");
    s3.setSecretKey("sk");
    s3.setObjectEncrypt(MediaObjects.ENC_SSE_C);
    s3.setMasterKey(Base64.getEncoder().encodeToString(new byte[32]));

    PbProperties.Storage storage = new PbProperties.Storage();
    storage.setType(storageType);
    storage.setLocalPath(System.getProperty("java.io.tmpdir") + "/pb-wiring-" + System.nanoTime());

    PbProperties props = new PbProperties();
    props.setS3(s3);
    props.setStorage(storage);
    return props;
  }
}
