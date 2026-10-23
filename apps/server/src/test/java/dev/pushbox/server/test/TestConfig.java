package dev.pushbox.server.test;

import dev.pushbox.server.setup.RuntimeConfig;

/**
 * 测试侧的配置入口：与运行期同一个文件（工作目录下的 {@code data/config/pushbox.json}）。
 *
 * <p>以前这批用例靠 {@code @EnabledIfEnvironmentVariable(named = "PB_S3_ACCESS_KEY")} 决定跑不跑。
 * 配置搬进 JSON 之后分成两档，因为它们的真实依赖本来就不一样：
 * <ul>
 *   <li>{@link #databaseReady()} —— 只需要一个连得上的库。存储走本地目录就够了，所以开发机上
 *       （安装页里不勾对象存储）这 13 个类照跑，本机测试不是"全绿但什么都没测"。</li>
 *   <li>{@link #objectStorageReady()} —— 需要真桶。media 那 5 个类验的是 S3 语义本身
 *       （SSE-C 客户钥、封装格式、无钥读 400、删除不需要钥），本地目录照不出这些，只能跳过。</li>
 * </ul>
 * CI 两档都满足（service container 起真 MySQL + 真 RustFS），所以它的 skipped 计数闸门仍然是 0；
 * 任何一条被跳过都意味着凭据没传到位，而不是"环境不一样"。
 */
public final class TestConfig {

  private static final RuntimeConfig CONFIG = RuntimeConfig.loadOrDefault(
      RuntimeConfig.locate(System.getProperty(RuntimeConfig.CONFIG_FILE_PROPERTY)));

  private TestConfig() {}

  public static RuntimeConfig get() {
    return CONFIG;
  }

  public static RuntimeConfig.Storage storage() {
    return CONFIG.getStorage();
  }

  /** 对象存储这一批闸门用例跑不跑：勾了开关且四件套齐全。 */
  public static boolean objectStorageReady() {
    RuntimeConfig.Storage s = storage();
    return s.isS3() && notBlank(s.getEndpoint()) && notBlank(s.getBucket())
        && notBlank(s.getAccessKey()) && notBlank(s.getSecretKey());
  }

  public static boolean databaseReady() {
    return CONFIG.isDatabaseConfigured();
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }
}
