package dev.pushbox.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import dev.pushbox.core.crypto.B64;
import dev.pushbox.core.crypto.Ed25519;
import dev.pushbox.core.crypto.FrameChannel;
import dev.pushbox.core.crypto.SignatureTargets;
import dev.pushbox.core.crypto.X25519;
import dev.pushbox.core.id.Ulid;
import dev.pushbox.server.repo.PbStore;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * §5.2 的设备侧守卫：Bearer + Ed25519 签名 + nonce 防重放 + 响应密封盒。
 * 走真库是因为 byToken 与 revoked_at 本身就是库里的判定；请求用 MockHttpServletRequest 构造，
 * 为的是能逐字节控制 method/URI/query/body —— 签名 canonical 就差在这四段上。
 */
@EnabledIfEnvironmentVariable(named = "PB_S3_ACCESS_KEY", matches = ".+")
// RANDOM_PORT 不是随手写的：MOCK 上下文里 WebSocketConfig 拿不到 ServerContainer，上下文起不来。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeviceGuardTest {

  private static final String GROUP = "grp_guard_test";
  private static final SecureRandom RANDOM = new SecureRandom();

  @Autowired
  private DeviceGuard guard;
  @Autowired
  private PbStore store;

  private record Principal(String deviceId, String token, Ed25519.KeyPair sign,
      X25519.KeyPair enc) {}

  private final List<String> enrolled = new ArrayList<>();
  private Principal self;

  @BeforeEach
  void enrollGroup() {
    Db.insertBySql("INSERT IGNORE INTO `groups` (group_id, group_secret_hash, created_at)"
        + " VALUES (?,?,?)", GROUP, "guard-test-secret-hash", System.currentTimeMillis());
    self = enroll();
  }

  @AfterEach
  void removeDevices() {
    for (String deviceId : enrolled) {
      Db.updateBySql("DELETE FROM devices WHERE device_id = ?", deviceId);
    }
    enrolled.clear();
  }

  @AfterAll
  static void removeGroup() {
    Db.updateBySql("DELETE FROM devices WHERE group_id = ?", GROUP);
    Db.updateBySql("DELETE FROM `groups` WHERE group_id = ?", GROUP);
  }

  private Principal enroll() {
    Ed25519.KeyPair sign = Ed25519.generate();
    X25519.KeyPair enc = X25519.generate();
    String deviceId = "dev_" + Ulid.next();
    String token = B64.enc(randomBytes(32));
    // created_at 故意留到一分钟前，touch 的效果才看得出来
    store.insertDevice(deviceId, GROUP, B64.enc(sign.publicKey()), B64.enc(enc.publicKey()),
        "守卫测试机", "android", token, System.currentTimeMillis() - 60_000L);
    enrolled.add(deviceId);
    return new Principal(deviceId, token, sign, enc);
  }

  @Test
  @DisplayName("① 缺 Bearer 或缺任一签名头都是 401，不给 403 之类可枚举的差别")
  void missingHeadersAreUnauthorized() {
    MockHttpServletRequest noToken = signed(self, "GET", "/api/v1/messages", null, null);
    noToken.removeHeader("Authorization");
    assertUnauthorized(noToken, "缺少设备 token");

    for (String header : new String[] {"X-PB-Device", "X-PB-Ts", "X-PB-Nonce", "X-PB-Sig"}) {
      MockHttpServletRequest request = signed(self, "GET", "/api/v1/messages", null, null);
      request.removeHeader(header);
      assertUnauthorized(request, "缺少签名头");
    }
  }

  @Test
  @DisplayName("② token 查无此设备、或 X-PB-Device 与 token 不同步：同一句 401")
  void unknownTokenOrForeignDeviceId() {
    MockHttpServletRequest badToken = signed(self, "GET", "/api/v1/messages", null, null);
    badToken.removeHeader("Authorization");
    badToken.addHeader("Authorization", "Bearer " + B64.enc(randomBytes(32)));
    assertUnauthorized(badToken, "设备无效");

    // 换了个别人的 device_id：token 依然有效，但两边必须互相印证
    MockHttpServletRequest foreign = signed(self, "GET", "/api/v1/messages", null, null);
    foreign.removeHeader("X-PB-Device");
    foreign.addHeader("X-PB-Device", "dev_" + Ulid.next());
    assertUnauthorized(foreign, "设备无效");
  }

  @Test
  @DisplayName("③ 签名时间戳只在 ±300s 内有效，越界报 clock_skew，非数字报 epoch 毫秒")
  void timestampWindow() {
    long now = System.currentTimeMillis();
    assertThat(guard.require(signed(self, "GET", "/api/v1/messages", null, null, now + 299_000L,
        randomBytes(16)), new byte[0]).deviceId()).isEqualTo(self.deviceId());

    for (long skew : new long[] {now + 301_000L, now - 301_000L}) {
      assertUnauthorized(signed(self, "GET", "/api/v1/messages", null, null, skew, randomBytes(16)),
          "clock_skew");
    }

    MockHttpServletRequest notNumber = signed(self, "GET", "/api/v1/messages", null, null);
    notNumber.removeHeader("X-PB-Ts");
    notNumber.addHeader("X-PB-Ts", "2026-10-14T00:00:00Z");
    assertUnauthorized(notNumber, "epoch 毫秒");
  }

  @Test
  @DisplayName("④ nonce 至少 16 字节；同一设备的同一 nonce 第二次是 409 replay")
  void nonceLengthAndReplay() {
    assertUnauthorized(signed(self, "GET", "/api/v1/messages", null, null,
        System.currentTimeMillis(), randomBytes(15)), "nonce 长度不足");

    byte[] nonce = randomBytes(16);
    long ts = System.currentTimeMillis();
    assertThat(guard.require(
        signed(self, "GET", "/api/v1/messages", null, null, ts, nonce), new byte[0]).groupId())
        .isEqualTo(GROUP);
    // 签名本身完全合法，拦下来只能靠 nonce 缓存
    assertThatThrownBy(() -> guard.require(
        signed(self, "GET", "/api/v1/messages", null, null, ts, nonce), new byte[0]))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(409);
          assertThat(e.toError().error()).isEqualTo("replay");
        });
  }

  @Test
  @DisplayName("⑤ 缓存键带 device_id：别的设备用同一个 nonce 不受牵连")
  void nonceCacheIsScopedPerDevice() {
    Principal other = enroll();
    byte[] nonce = randomBytes(16);
    long ts = System.currentTimeMillis();
    assertThat(guard.require(
        signed(self, "GET", "/api/v1/messages", null, null, ts, nonce), new byte[0])).isNotNull();
    assertThat(guard.require(
        signed(other, "GET", "/api/v1/messages", null, null, ts, nonce), new byte[0])).isNotNull();
  }

  @Test
  @DisplayName("⑥ 签名覆盖 method/URI/query/body：签完之后动任何一段都验不过")
  void signatureCoversEverySegment() {
    byte[] body = "{\"q\":\"磁盘\"}".getBytes(StandardCharsets.UTF_8);
    long ts = System.currentTimeMillis();
    assertThat(guard.require(
        signed(self, "POST", "/api/v1/search", "page=1", body, ts, randomBytes(16)), body)
        .deviceId()).isEqualTo(self.deviceId());

    // 每个非法请求都得带自己的 nonce：nonce 去重在验签之前，撞上去就变成 409 而不是 401 了。
    // body 少一个字节：请求头带的是原签名，服务端按实际收到的字节重算摘要
    assertUnauthorized(signed(self, "POST", "/api/v1/search", "page=1", body, ts, randomBytes(16)),
        Arrays.copyOf(body, body.length - 1));
    // query 换个字符：得先按原 query 签好，再改掉请求里的 query，否则签名与请求本来就一致
    MockHttpServletRequest tamperedQuery = signed(self, "POST", "/api/v1/search", "page=1", body,
        ts, randomBytes(16));
    tamperedQuery.setQueryString("page=2");
    assertUnauthorized(tamperedQuery, body);
    // method 同理：canonical 里是大写方法名，签完再改成 DELETE 就验不过
    MockHttpServletRequest tamperedMethod = signed(self, "POST", "/api/v1/search", "page=1", body,
        ts, randomBytes(16));
    tamperedMethod.setMethod("DELETE");
    assertUnauthorized(tamperedMethod, body);
    // 用另一台设备的私钥签同一个 canonical
    byte[] nonce = randomBytes(16);
    MockHttpServletRequest wrongKey = signed(self, "POST", "/api/v1/search", "page=1", body, ts,
        nonce);
    wrongKey.removeHeader("X-PB-Sig");
    wrongKey.addHeader("X-PB-Sig", B64.enc(
        Ed25519.sign(Ed25519.generate().privateKey(),
            SignatureTargets.restAuth(self.deviceId(), "POST", "/api/v1/search", "page=1", body, ts,
                nonce))));
    assertUnauthorized(wrongKey, body);
  }

  @Test
  @DisplayName("⑦ 验签通过后 touch last_seen_at；吊销后旧 token 即刻失效")
  void seenAtAdvancesAndRevocationKillsToken() {
    long beforeTouch = store.findDevice(self.deviceId()).getLong("last_seen_at");
    guard.require(signed(self, "GET", "/api/v1/messages", null, null), new byte[0]);
    assertThat(store.findDevice(self.deviceId()).getLong("last_seen_at")).isGreaterThan(beforeTouch);

    store.revokeDevice(self.deviceId(), GROUP, System.currentTimeMillis());
    // revoke 把 token 一并换掉，旧 token 直接查不到行
    assertUnauthorized(signed(self, "GET", "/api/v1/messages", null, null), "设备无效");
  }

  @Test
  @DisplayName("⑧ 响应密封盒：五键齐全，设备侧用 enc 私钥能原样解出明文")
  void sealIsSelfConsistent() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("ok", true);
    payload.put("count", 3);
    Row device = store.findDevice(self.deviceId());

    Map<String, Object> box = guard.seal(device, payload);
    assertThat(box).containsOnlyKeys("v", "eph_pub", "salt", "data", "n");
    assertThat(box.get("v")).isEqualTo(1);

    byte[] ephPub = B64.dec((String) box.get("eph_pub"));
    byte[] salt = B64.dec((String) box.get("salt"));
    FrameChannel channel = new FrameChannel(
        FrameChannel.sessionKey(X25519.agree(self.enc().privateKey(), ephPub), salt),
        System.currentTimeMillis());
    byte[] plain = channel.open(((Number) box.get("n")).longValue(), (String) box.get("data"));

    assertThat(new String(plain, StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true,\"count\":3}");
  }

  @Test
  @DisplayName("⑨ 每次密封都换临时密钥：同一 payload 两次的密文与 eph_pub 都不同")
  void sealUsesFreshEphemeralKeyEachTime() {
    Row device = store.findDevice(self.deviceId());
    Map<String, Object> first = guard.seal(device, Map.of("v", "同一条"));
    Map<String, Object> second = guard.seal(device, Map.of("v", "同一条"));

    assertThat(first.get("eph_pub")).isNotEqualTo(second.get("eph_pub"));
    assertThat(first.get("data")).isNotEqualTo(second.get("data"));
  }

  private MockHttpServletRequest signed(Principal p, String method, String uri, String query,
      byte[] body) {
    return signed(p, method, uri, query, body, System.currentTimeMillis(), randomBytes(16));
  }

  private MockHttpServletRequest signed(Principal p, String method, String uri, String query,
      byte[] body, long ts, byte[] nonce) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.setRequestURI(uri);
    if (query != null) {
      request.setQueryString(query);
    }
    if (body != null) {
      request.setContent(body);
    }
    byte[] target = SignatureTargets.restAuth(p.deviceId(), method, uri, query,
        body == null ? new byte[0] : body, ts, nonce);
    request.addHeader("Authorization", "Bearer " + p.token());
    request.addHeader("X-PB-Device", p.deviceId());
    request.addHeader("X-PB-Ts", String.valueOf(ts));
    request.addHeader("X-PB-Nonce", B64.enc(nonce));
    request.addHeader("X-PB-Sig", B64.enc(Ed25519.sign(p.sign().privateKey(), target)));
    return request;
  }

  /** 过了设备与限流关卡、只在验签这一步失败的请求。 */
  private void assertUnauthorized(MockHttpServletRequest request, byte[] body) {
    assertThatThrownBy(() -> guard.require(request, body)).isInstanceOfSatisfying(
        ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(401);
          assertThat(e.toError().error()).isEqualTo("unauthorized");
          assertThat(e.toError().hint()).isEqualTo("bad_signature");
        });
  }

  private void assertUnauthorized(MockHttpServletRequest request, String hint) {
    assertThatThrownBy(() -> guard.require(request, new byte[0])).isInstanceOfSatisfying(
        ApiException.class, e -> {
          assertThat(e.status()).isEqualTo(401);
          assertThat(e.toError().error()).isEqualTo("unauthorized");
          assertThat(e.toError().hint()).contains(hint);
        });
  }

  private static byte[] randomBytes(int len) {
    byte[] out = new byte[len];
    RANDOM.nextBytes(out);
    return out;
  }
}
