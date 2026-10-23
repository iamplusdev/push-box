package dev.pushbox.server.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.ThrowableAssert.catchThrowableOfType;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import dev.pushbox.core.crypto.B64;
import dev.pushbox.core.crypto.Ed25519;
import dev.pushbox.core.crypto.SignatureTargets;
import dev.pushbox.core.crypto.X25519;
import dev.pushbox.core.id.Ulid;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.web.ApiException;
import dev.pushbox.server.web.MediaController;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * §4.5 + §5.2 的加密闭环：抓下来的图以 SSE-C 落桶（桶里只有密文），设备再从服务端代理口读回明文字节。
 * 这条链路必须整条跑，因为三处各自都可能出错：抓取侧忘了生成客户钥、去重复用时库里那列没跟着走、
 * 读侧拿错封装钥。头像与正文图片在同一个桶里，所以也一并断言。
 *
 * <p>主钥是测试自造的常量：它只加密这一批跑完就删掉的临时对象，不是任何环境的凭据。
 */
@EnabledIf("dev.pushbox.server.test.TestConfig#objectStorageReady")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"pb.storage.type=s3",
        // 这一条是这一类自己的前提，不能指望配置文件给：安装页没勾「改用对象存储」时默认是 local
        // （本机只装一个 MySQL 就能起步），而 local 模式下 S3Config 不建 S3Client bean，
        // @Autowired 直接 UnsatisfiedDependencyException。
        // CI 写出的那份 pushbox.json 勾了 s3、这 7 条被门放开，跑法却是 local —— 于是 main 上第一次红就在这里。
        "pb.s3.object-encrypt=sse-c",
        // base64(0x01..0x20)，正好 32 字节：不写就会被配置文件里那个只填了口令、masterKey 留空的取值顶成非法配置
        "pb.s3.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="})
class MediaSsePipelineTest {

  private static final String CHANNEL = "SSEPIPE1";
  private static final String GROUP = "grp_sse_pipeline_test";
  private static final String BIG_JPEG = "https://picsum.photos/1200/800";
  private static final String SMALL_PNG = "https://httpbin.org/image/png";
  /** 1x1 PNG，内联头像完全不出网。 */
  private static final String PNG_BASE64 =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
  private static final SecureRandom RANDOM = new SecureRandom();

  @Autowired
  private PbStore store;
  @Autowired
  private MediaObjects objects;
  @Autowired
  private MediaFetchService fetcher;
  @Autowired
  private MediaController controller;
  @Autowired
  private PbProperties props;
  @Autowired
  private S3Client s3;

  private final List<String> messages = new ArrayList<>();
  private final List<String> keys = new ArrayList<>();
  private String deviceId;
  private String deviceToken;
  private Ed25519.KeyPair sign;

  @BeforeEach
  void seed() {
    cleanUp();
    Db.insertBySql("INSERT IGNORE INTO `groups` (group_id, group_secret_hash, created_at)"
        + " VALUES (?,?,?)", GROUP, "sse-pipeline-secret-hash", System.currentTimeMillis());
    store.insertChannel(CHANNEL, "0".repeat(64), GROUP, "SSE-C 链路测试频道", "error", 4,
        System.currentTimeMillis());
    sign = Ed25519.generate();
    X25519.KeyPair enc = X25519.generate();
    deviceId = "dev_" + Ulid.next();
    deviceToken = B64.enc(randomBytes(32));
    store.insertDevice(deviceId, GROUP, B64.enc(sign.publicKey()), B64.enc(enc.publicKey()),
        "SSE-C 测试机", "android", deviceToken, System.currentTimeMillis());
  }

  @AfterEach
  void cleanUp() {
    for (String key : keys) {
      objects.delete(key);
    }
    keys.clear();
    for (String msgId : messages) {
      for (int idx = 0; idx < 4; idx++) {
        objects.delete(MediaObjects.fullKey(msgId, idx));
        objects.delete(MediaObjects.thumbKey(msgId, idx));
      }
      Db.updateBySql("DELETE FROM media_objects WHERE msg_id = ?", msgId);
      Db.updateBySql("DELETE FROM messages WHERE msg_id = ?", msgId);
    }
    messages.clear();
    Db.updateBySql("DELETE FROM senders WHERE channel_no = ?", CHANNEL);
    Db.updateBySql("DELETE FROM channels WHERE channel_no = ?", CHANNEL);
    Db.updateBySql("DELETE FROM devices WHERE group_id = ?", GROUP);
  }

  @AfterAll
  static void removeGroup() {
    Db.updateBySql("DELETE FROM `groups` WHERE group_id = ?", GROUP);
  }

  @Test
  @DisplayName("① 抓取即密文：库里记 61 字节封装钥，不带钥读桶里对象被拒，代理读回的是原图")
  void fetchStoresCiphertextAndProxyServesPlaintext() {
    String msgId = message(BIG_JPEG);

    assertThat(fetcher.fetch(msgId)).isEqualTo("ready");

    Row row = media(msgId, 0);
    assertThat(row.getString("enc_mode")).isEqualTo("sse-c");
    assertThat(row.getBytes("sse_key_wrapped")).hasSize(61);
    String objectKey = row.getString("object_key");
    // 桶里落盘非明文：不带客户钥直接读，RustFS 当场拒绝，客户端就算摸到桶地址也拿不到图
    assertThat(catchThrowableOfType(() -> objects.load(objectKey, null), S3Exception.class))
        .isNotNull()
        .matches(e -> e.statusCode() == 400);

    ResponseEntity<byte[]> served = controller.read(msgId, 0, "full",
        signed("/api/v1/media/" + msgId + "/0", "size=full"));

    assertThat(served.getStatusCode().value()).isEqualTo(200);
    assertThat(served.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
    assertThat(isJpeg(served.getBody())).isTrue();
    assertThat(served.getBody()).hasSize(row.getLong("size").intValue());
  }

  @Test
  @DisplayName("② 原图与缩略图共用一列封装钥：thumb 不需要额外的密钥列")
  void thumbnailSharesTheSameCustomerKey() {
    String msgId = message(BIG_JPEG);
    assertThat(fetcher.fetch(msgId)).isEqualTo("ready");

    Row row = media(msgId, 0);
    String thumbKey = row.getString("thumb_key");
    assertThat(thumbKey).isEqualTo(MediaObjects.thumbKey(msgId, 0));
    // 库里那一列既开原图也开缩略图——这正是"一个槽位一把钥"要成立的前提
    assertThat(objects.load(thumbKey, row.getBytes("sse_key_wrapped")).body()).isNotNull();

    ResponseEntity<byte[]> served = controller.read(msgId, 0, "thumb",
        signed("/api/v1/media/" + msgId + "/0", "size=thumb"));

    assertThat(served.getStatusCode().value()).isEqualTo(200);
    assertThat(isJpeg(served.getBody())).isTrue();
    assertThat(served.getBody()).hasSizeLessThan(row.getLong("size").intValue());
  }

  @Test
  @DisplayName("③ 同组去重：复用行把加密形态一起带走，两行指向同一对象与同一把封装钥")
  void deduplicationCarriesEncryptionState() {
    String first = message(SMALL_PNG);
    assertThat(fetcher.fetch(first)).isEqualTo("ready");

    String second = message(SMALL_PNG);
    assertThat(fetcher.fetch(second)).isEqualTo("ready");

    Row a = media(first, 0);
    Row b = media(second, 0);
    assertThat(b.getString("object_key")).isEqualTo(a.getString("object_key"));
    assertThat(b.getString("enc_mode")).isEqualTo("sse-c");
    assertThat(b.getBytes("sse_key_wrapped")).isEqualTo(a.getBytes("sse_key_wrapped"));
    // 复制过来的封装钥必须真能开那个对象，否则去重换来的是"永远读不出的图"
    assertThat(controller.read(second, 0, "full", signed("/api/v1/media/" + second + "/0",
        "size=full")).getStatusCode().value()).isEqualTo(200);
  }

  @Test
  @DisplayName("④ 头像也在同一个桶里：转存后 senders 记 sse-c，代理读回 PNG，无钥读被拒")
  void avatarIsEncryptedToo() {
    String sender = "sse-avatar-sender";
    String avatarToken = MediaObjects.avatarToken(sender);
    AvatarSource source = AvatarSource.parse("data:image/png;base64," + PNG_BASE64);
    store.upsertSender(CHANNEL, sender, "头像", source.source(), avatarToken,
        System.currentTimeMillis());

    String key = fetcher.storeAvatar(CHANNEL, sender, source);
    keys.add(key);

    Row row = store.findSenderAvatar(CHANNEL, sender);
    assertThat(row.getString("enc_mode")).isEqualTo("sse-c");
    assertThat(row.getBytes("sse_key_wrapped")).hasSize(61);
    assertThat(catchThrowableOfType(() -> objects.load(key, null), S3Exception.class))
        .isNotNull()
        .matches(e -> e.statusCode() == 400);

    ResponseEntity<byte[]> served =
        controller.avatar(avatarToken, signed("/api/v1/avatars/" + avatarToken, null));

    assertThat(served.getStatusCode().value()).isEqualTo(200);
    assertThat(isPng(served.getBody())).isTrue();
    assertThat(served.getBody()).isEqualTo(Base64.getDecoder().decode(PNG_BASE64));
  }

  @Test
  @DisplayName("⑤ 换头像覆盖写复用同一把客户钥：更新前的旧封装钥照样能开新字节，读侧没有空窗")
  void avatarRewriteKeepsWorking() {
    String sender = "sse-avatar-rewrite";
    String avatarToken = MediaObjects.avatarToken(sender);
    store.upsertSender(CHANNEL, sender, "头像", SMALL_PNG, avatarToken, System.currentTimeMillis());

    String key = fetcher.storeAvatar(CHANNEL, sender, AvatarSource.parse(SMALL_PNG));
    keys.add(key);
    byte[] wrappedBefore = store.findSenderAvatar(CHANNEL, sender).getBytes("sse_key_wrapped");

    // 来源变了才走覆盖写；同一来源重复提交会命中复用分支，一次都不重写
    String rewritten = fetcher.storeAvatar(CHANNEL, sender,
        AvatarSource.parse("data:image/png;base64," + PNG_BASE64));

    assertThat(rewritten).isEqualTo(key);
    assertThat(objects.load(key, wrappedBefore).body())
        .isEqualTo(Base64.getDecoder().decode(PNG_BASE64));
    assertThat(controller.avatar(avatarToken, signed("/api/v1/avatars/" + avatarToken, null))
        .getStatusCode().value()).isEqualTo(200);
  }

  @Test
  @DisplayName("⑥ 开关切换不影响历史对象：库里记 none 的明文行照样能读，不需要封装钥")
  void legacyPlainObjectsStillReadable() {
    String msgId = message(SMALL_PNG);
    // 手工造一条"开关打开之前"的记录：明文对象 + enc_mode='none' + 没有封装钥
    String objectKey = MediaObjects.fullKey(msgId, 0);
    byte[] legacy = new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
    MediaObjects.PutResult plain = plainObjects().put(objectKey, legacy, "image/png");
    keys.add(objectKey);
    assertThat(plain.encMode()).isEqualTo("none");
    assertThat(plain.sseKeyWrapped()).isNull();
    store.markMediaReady(msgId, 0, objectKey, null, "image/png", 1, 1, legacy.length,
        "0".repeat(64), plain.encMode(), null, System.currentTimeMillis());

    ResponseEntity<byte[]> served = controller.read(msgId, 0, "full",
        signed("/api/v1/media/" + msgId + "/0", "size=full"));

    assertThat(served.getStatusCode().value()).isEqualTo(200);
    assertThat(served.getBody()).isEqualTo(legacy);
  }

  @Test
  @DisplayName("⑦ 真删前报 deleted、巡检删完桶之后报 purged：客户端能区分还会不会有图")
  void purgedSlotReportsPurged() {
    String msgId = message(SMALL_PNG);
    assertThat(fetcher.fetch(msgId)).isEqualTo("ready");
    Row row = media(msgId, 0);

    // tombstone 只是打标记，字节还在宽限期里等巡检
    store.markMediaDeleted(msgId, System.currentTimeMillis());
    assertThat(statusHint(msgId)).isEqualTo("deleted");

    // 走 sweep 用的同一个方法：DeleteObject 成功之后才允许写 purged
    objects.delete(row.getString("object_key"));
    objects.delete(row.getString("thumb_key"));
    store.markMediaPurged(row.getLong("id"));

    assertThat(statusHint(msgId)).isEqualTo("purged");
    assertThat(media(msgId, 0).getBytes("sse_key_wrapped")).isNull();
  }

  /** 读接口对非 ready 槽位抛 404，hint 就是那一列的状态。 */
  private String statusHint(String msgId) {
    String uri = "/api/v1/media/" + msgId + "/0";
    ApiException error = catchThrowableOfType(
        () -> controller.read(msgId, 0, "full", signed(uri, "size=full")), ApiException.class);
    return error.toError().hint();
  }

  /** 与 Spring 里那个 sse-c 门面同一个客户端、但模式为 none：只在造历史明文对象时才需要。 */
  private MediaObjects plainObjects() {
    PbProperties.S3 current = props.getS3();
    PbProperties.S3 plain = new PbProperties.S3();
    plain.setEndpoint(current.getEndpoint());
    plain.setRegion(current.getRegion());
    plain.setBucket(current.getBucket());
    plain.setAccessKey(current.getAccessKey());
    plain.setSecretKey(current.getSecretKey());
    plain.setPathStyle(current.isPathStyle());
    plain.setObjectEncrypt("none");
    PbProperties copy = new PbProperties();
    copy.setS3(plain);
    // Storage 默认 local：不显式指到 s3 的话这个"历史明文对象"会写到本地盘，
    // 而 controller 读的是桶，于是第六组用例拿到 404 而不是那张图。
    copy.getStorage().setType("s3");
    return new MediaObjects(s3, copy);
  }

  private MockHttpServletRequest signed(String uri, String query) {
    long ts = System.currentTimeMillis();
    byte[] nonce = randomBytes(16);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
    request.setRequestURI(uri);
    if (query != null) {
      request.setQueryString(query);
    }
    byte[] target = SignatureTargets.restAuth(deviceId, "GET", uri, query, new byte[0], ts, nonce);
    request.addHeader("Authorization", "Bearer " + deviceToken);
    request.addHeader("X-PB-Device", deviceId);
    request.addHeader("X-PB-Ts", String.valueOf(ts));
    request.addHeader("X-PB-Nonce", B64.enc(nonce));
    request.addHeader("X-PB-Sig", B64.enc(Ed25519.sign(sign.privateKey(), target)));
    return request;
  }

  private String message(String... urls) {
    String msgId = Ulid.next();
    long now = System.currentTimeMillis();
    store.insertMessage(msgId, CHANNEL, GROUP, "tester", "text", null, "SSE-C 链路测试正文", "摘要",
        null, "info", "pending", now, null);
    for (int i = 0; i < urls.length; i++) {
      store.insertMedia(msgId, i, urls[i], CHANNEL, GROUP, now);
    }
    messages.add(msgId);
    return msgId;
  }

  /** 加密两列是这一版才加上的，直接按列查：不借用 listMedia，免得为了测试拓宽生产查询。 */
  private Row media(String msgId, int idx) {
    Row row = Db.selectOneBySql("""
        SELECT id, idx, status, object_key, thumb_key, mime, size, enc_mode, sse_key_wrapped
        FROM media_objects WHERE msg_id = ? AND idx = ?""", msgId, idx);
    if (row == null) {
      throw new AssertionError("缺少 media_objects 行 msg_id=" + msgId + " idx=" + idx);
    }
    return row;
  }

  private static boolean isJpeg(byte[] data) {
    return data != null && data.length > 4 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
  }

  private static boolean isPng(byte[] data) {
    return data != null && data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P'
        && data[2] == 'N' && data[3] == 'G';
  }

  private static byte[] randomBytes(int size) {
    byte[] bytes = new byte[size];
    RANDOM.nextBytes(bytes);
    return bytes;
  }
}
