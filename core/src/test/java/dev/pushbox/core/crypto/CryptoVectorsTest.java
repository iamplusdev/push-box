package dev.pushbox.core.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pushbox.core.protocol.Envelope;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.core.protocol.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * §13：跨端加密向量的唯一权威来源。这些固定输入/输出会被导出成 vectors.json，
 * P3 的 C# 客户端实现必须先对齐它；任何改动导致向量变化，就是这个提交必须同时更新 Windows 侧计划。
 */
class CryptoVectorsTest {

  private static final byte[] SERVER_EPH_PRIV = hex("3a2f5c19d0b84e6a91c7f23d5b0a8e14"
      + "7c6d3f28a1b5e0c94d7f2a6b1e8c35d0");
  private static final byte[] DEVICE_ENC_PRIV = hex("58a3c1e07f2b9d46a0c8e5f31b7d2094"
      + "ae6c3f81b5d20c97e4a6b0f3c81d5290");
  private static final byte[] DEVICE_SIGN_SEED = hex("11cd4a8e3b0f7c25d9a6e1b48c3f07a2"
      + "5d9e6b1c8f4a20d3b7e5c9f01a6d4b28");
  private static final byte[] NONCE = hex("0f1e2d3c4b5a69788796a5b4c3d2e1f0"
      + "0123456789abcdef0123456789abcdef");
  /** REST 的 client nonce 是客户端自造的 16 字节，与 WS auth 用的 32 字节 hello nonce 不同源。 */
  private static final byte[] REST_NONCE = hex("c4d5e6f708192a3b4c5d6e7f8091a2b3");
  /** 响应密封盒的临时私钥：真实实现每次响应新生成，这里固定才能保证向量可复现。 */
  private static final byte[] RESPONSE_EPH_PRIV = hex("6b1f8e04c7a2d9350e8b6c14fa7d3925"
      + "c0e41b58d7f3260a9e5c8b14d02a7f63");

  @Test
  void x25519AgreementIsSymmetric() {
    byte[] deviceEncPub = X25519.publicKeyOf(DEVICE_ENC_PRIV);
    byte[] serverEphPub = X25519.publicKeyOf(SERVER_EPH_PRIV);

    byte[] serverSide = X25519.agree(SERVER_EPH_PRIV, deviceEncPub);
    byte[] deviceSide = X25519.agree(DEVICE_ENC_PRIV, serverEphPub);
    assertArrayEquals(serverSide, deviceSide, "X25519 两侧必须算出同一个 shared secret");
    assertEquals(32, serverSide.length);
  }

  @Test
  void sessionKeyDerivationIsStable() {
    byte[] shared = X25519.agree(SERVER_EPH_PRIV, X25519.publicKeyOf(DEVICE_ENC_PRIV));
    byte[] k1 = FrameChannel.sessionKey(shared, NONCE);
    byte[] k2 = FrameChannel.sessionKey(shared, NONCE);
    assertArrayEquals(k1, k2);
    assertEquals(32, k1.length);
    assertFalse(java.util.Arrays.equals(k1, FrameChannel.sessionKey(shared, new byte[32])),
        "salt(nonce) 变化必须换出不同的会话密钥");
  }

  @Test
  void ed25519SignAndVerify() {
    Ed25519.KeyPair kp = Ed25519.fromPrivateSeed(DEVICE_SIGN_SEED);
    byte[] msg = SignatureTargets.wsAuth("dev_test", NONCE, 1_765_500_000_000L);
    byte[] sig = Ed25519.sign(kp.privateKey(), msg);
    assertTrue(Ed25519.verify(kp.publicKey(), msg, sig));
    sig[0] ^= 1;
    assertFalse(Ed25519.verify(kp.publicKey(), msg, sig));
    assertEquals(64, sig.length);
    assertEquals(32, kp.publicKey().length);
  }

  @Test
  void frameSealOpenRoundTrip() {
    byte[] shared = X25519.agree(SERVER_EPH_PRIV, X25519.publicKeyOf(DEVICE_ENC_PRIV));
    byte[] key = FrameChannel.sessionKey(shared, NONCE);
    FrameChannel sender = new FrameChannel(key, 0L);
    FrameChannel receiver = new FrameChannel(key, 0L);

    Frames.Message message = Frames.Message.of(18482L, "83271504", "01JZK", "grafana", "text",
        "标题", "摘要", "正文内容", "error", null, List.of(), "Grafana 告警", null, 1_765_500_000_000L);
    String wire = Envelope.seal(sender, message);

    String inner = Envelope.open(receiver, wire);
    assertNotNull(inner, "接收端要能还原同一帧");
    Frames.Message decoded = Json.read(inner, Frames.Message.class);
    assertEquals(message.seq(), decoded.seq());
    assertEquals(message.content(), decoded.content());
    assertTrue(decoded.notifyClient());

    // AAD/nonce 绑定帧序号：换个序号解必然失败
    FrameChannel attacker = new FrameChannel(key, 0L);
    assertNull(attacker.open(99, Json.read(wire, Frames.Enc.class).data()));
  }

  @Test
  void tamperedCiphertextIsRejectedNotCrashing() {
    byte[] key = FrameChannel.sessionKey(new byte[32], NONCE);
    FrameChannel sender = new FrameChannel(key, 0L);
    FrameChannel receiver = new FrameChannel(key, 0L);
    Frames.Enc enc = Json.read(Envelope.seal(sender, Frames.Simple.ping()), Frames.Enc.class);
    byte[] raw = B64.dec(enc.data());
    raw[raw.length - 1] ^= 0x40;
    assertNull(receiver.open(enc.n(), B64.enc(raw)));
  }

  @Test
  void ratchetProducesDeterministicNextEpoch() {
    byte[] key = FrameChannel.sessionKey(new byte[32], NONCE);
    FrameChannel a = new FrameChannel(key, 0L);
    FrameChannel b = new FrameChannel(key, 0L);
    a.rotate(1_000L);
    b.rotate(9_999_999_999L);
    assertArrayEquals(a.key(), b.key(), "轮换后的密钥只能取决于 epoch，不能取决于时钟值");
    assertFalse(java.util.Arrays.equals(a.key(), key));
    assertEquals(1, a.epoch());

    FrameChannel peer = new FrameChannel(key, 0L);
    peer.rotate(0L);
    FrameChannel senderSealed = new FrameChannel(a.key(), 0L);
    String sealed = Envelope.seal(senderSealed, Frames.Simple.resumeOk());
    assertNotNull(Envelope.open(peer, sealed));
  }

  @Test
  void rotationTriggerMatchesSpec() {
    byte[] key = FrameChannel.sessionKey(new byte[32], NONCE);
    FrameChannel c = new FrameChannel(key, 1_000L);
    assertFalse(c.shouldRotate(1_000L + FrameChannel.ROTATE_AFTER_MILLIS - 1));
    assertTrue(c.shouldRotate(1_000L + FrameChannel.ROTATE_AFTER_MILLIS));
    assertEquals(1L << 32, FrameChannel.ROTATE_AFTER_FRAMES);
  }

  /** §5.2：restAuth 的 8 行 canonical —— P3 的 C# 必须逐字节对齐，否则整片接口 401。 */
  @Test
  void restAuthCanonicalShape() {
    byte[] signPub = Ed25519.fromPrivateSeed(DEVICE_SIGN_SEED).publicKey();
    String query = "q=CPU%20usage&channel_no=83271504&sender_id=&since=0&limit=50";
    byte[] target = SignatureTargets.restAuth("dev_7f3a", "get", "/api/v1/search", query, null,
        1_765_500_000_000L, REST_NONCE);
    String canonical = new String(target, UTF_8);
    assertEquals(8, canonical.split("\n", -1).length, "canonical 必须正好 8 行");
    assertFalse(canonical.endsWith("\n"), "末尾不能有换行");
    assertTrue(canonical.startsWith(SignatureTargets.REST_AUTH_V1 + "\ndev_7f3a\nGET\n"),
        "入参方法是小写，canonical 里必须被规范化成大写");
    assertTrue(canonical.contains("\n" + query + "\n"), "query 必须原样进签名，含空值那一项");
    assertTrue(canonical.contains(Hashes.sha256Hex(new byte[0])), "空 body 用 sha256(\"\")");
    assertTrue(Ed25519.verify(signPub, target, Ed25519.sign(DEVICE_SIGN_SEED, target)));
  }

  /** §5.2 密封范围：REST 响应是另一条盒，用设备静态 enc 私钥解，且帧序号恒为 0。 */
  @Test
  void restSealedBoxOpensWithDeviceStaticEncKey() {
    byte[] deviceEncPub = X25519.publicKeyOf(DEVICE_ENC_PRIV);
    X25519.KeyPair eph = X25519.fromPrivate(RESPONSE_EPH_PRIV);
    byte[] salt = Hashes.sha256(eph.publicKey());
    assertEquals(32, salt.length, "salt 是公钥字节的原始 sha256，不是 hex 字符串");

    FrameChannel server = new FrameChannel(
        FrameChannel.sessionKey(X25519.agree(eph.privateKey(), deviceEncPub), salt), 0L);
    byte[] plain = Json.writeBytes(Map.of("token", "pbk_1a2b3c4d5e6f"));
    FrameChannel.Sealed sealed = server.seal(plain);
    assertEquals(0L, sealed.frameIndex(), "一回合密封盒的 n 恒为 0");

    FrameChannel client = new FrameChannel(
        FrameChannel.sessionKey(X25519.agree(DEVICE_ENC_PRIV, eph.publicKey()), salt), 0L);
    assertArrayEquals(plain, client.open(sealed.frameIndex(), sealed.data()));
    assertNull(client.open(1L, sealed.data()), "nonce/AAD 绑定帧序号，换 n 必解不开");
  }

  /** 导出 vectors.json（§13 P3 Windows 的开工前提）。 */
  @Test
  void exportVectors() throws Exception {
    byte[] deviceEncPub = X25519.publicKeyOf(DEVICE_ENC_PRIV);
    byte[] deviceSignPub = Ed25519.fromPrivateSeed(DEVICE_SIGN_SEED).publicKey();
    byte[] serverEphPub = X25519.publicKeyOf(SERVER_EPH_PRIV);
    byte[] shared = X25519.agree(SERVER_EPH_PRIV, deviceEncPub);
    byte[] sessionKey = FrameChannel.sessionKey(shared, NONCE);

    FrameChannel channel = new FrameChannel(sessionKey, 0L);
    Frames.Message sample = Frames.Message.of(1L, "83271504", "01JZKTEST000000000000000",
        "grafana", "markdown", "[严重] CPU 使用率 > 90%", "摘要", "正文 **粗体**", "error",
        "https://example.com/d/abc",
        List.of(new Frames.MediaSlot(0, "ready", "media/01JZK/0", "image/png", 1024L, 800, 600,
            "media/01JZK/0.thumb")),
        "Grafana 告警", null, 1_765_500_000_000L);
    String messageJson = Json.write(sample);
    Frames.Enc enc = Json.read(Envelope.seal(channel, sample), Frames.Enc.class);

    byte[] kxBytes = SignatureTargets.kx(serverEphPub, NONCE);
    Ed25519.KeyPair serverId = Ed25519.fromPrivateSeed(hex("0011223344556677"
        + "8899aabbccddeeff00112233445566778899aabbccddeeff"));
    byte[] kxSig = Ed25519.sign(serverId.privateKey(), kxBytes);
    byte[] wsAuthBytes = SignatureTargets.wsAuth("dev_7f3a", NONCE, 1_765_500_000_000L);
    byte[] wsAuthSig = Ed25519.sign(DEVICE_SIGN_SEED, wsAuthBytes);

    // §5.2 restAuth：两条用例分别钉住"query 原样透传（含空值）"和"body 哈希吃字节"
    String restQuery = "q=CPU%20usage&channel_no=83271504&sender_id=&since=0&limit=50";
    byte[] restGetBytes = SignatureTargets.restAuth("dev_7f3a", "get", "/api/v1/search", restQuery,
        null, 1_765_500_000_000L, REST_NONCE);
    byte[] restGetSig = Ed25519.sign(DEVICE_SIGN_SEED, restGetBytes);
    byte[] restPostBody = "{\"name\":\"运维告警\"}".getBytes(UTF_8);
    byte[] restPostBytes = SignatureTargets.restAuth("dev_7f3a", "POST", "/api/v1/channels", null,
        restPostBody, 1_765_500_000_001L, REST_NONCE);
    byte[] restPostSig = Ed25519.sign(DEVICE_SIGN_SEED, restPostBytes);

    // §5.2 密封范围：register/join 的回包也是这种盒，里面是长期凭据，不能用 WS 信封那条路径凑
    X25519.KeyPair responseEph = X25519.fromPrivate(RESPONSE_EPH_PRIV);
    byte[] responseSalt = Hashes.sha256(responseEph.publicKey());
    FrameChannel responseChannel = new FrameChannel(
        FrameChannel.sessionKey(X25519.agree(RESPONSE_EPH_PRIV, deviceEncPub), responseSalt), 0L);
    // 字段与顺序都照 core/api/DeviceApi.RegisterResponse，P3 解析时不能指望少一个字段
    Map<String, Object> registerPayload = new LinkedHashMap<>();
    registerPayload.put("device_id", "dev_7f3a");
    registerPayload.put("group_id", "grp_001");
    registerPayload.put("token", "pbk_1a2b3c4d5e6f");
    // 恢复码是 B64.enc(16 字节随机) ⇒ 恰好 22 个 base64url 字符；P3 的客户端会先按这个形状自检
    registerPayload.put("recovery_code", "9f3c77ad1e58b0c2Xk7mQw");
    registerPayload.put("server_pub_fp", Ed25519.fingerprint(serverId.publicKey()));
    byte[] registerPlain = Json.writeBytes(registerPayload);
    FrameChannel.Sealed registerSealed = responseChannel.seal(registerPlain);
    Map<String, Object> sealedBox = new LinkedHashMap<>();
    sealedBox.put("v", 1);
    sealedBox.put("eph_pub", B64.enc(responseEph.publicKey()));
    sealedBox.put("salt", B64.enc(responseSalt));
    sealedBox.put("data", registerSealed.data());
    sealedBox.put("n", registerSealed.frameIndex());
    // 客户端方向：设备静态 enc 私钥 + eph_pub 必须能自行还原（salt 由服务端算，客户端也要能重算）
    FrameChannel clientSide = new FrameChannel(
        FrameChannel.sessionKey(X25519.agree(DEVICE_ENC_PRIV, responseEph.publicKey()),
            Hashes.sha256(responseEph.publicKey())), 0L);
    assertArrayEquals(registerPlain, clientSide.open(registerSealed.frameIndex(),
        registerSealed.data()), "P3 只拿到 eph_pub/salt/data/n，必须能自解");

    Map<String, Object> vectors = new LinkedHashMap<>();
    vectors.put("version", 1);
    vectors.put("algorithm", Map.of(
        "kx", "X25519",
        "kdf", "HKDF-SHA256(salt=nonce, info=pushbox-frame-v1, len=32)",
        "frame_cipher", "AES-256-GCM(nonce=8B BE(frame_index)+4B zero, AAD=8B BE(frame_index))",
        "identity_sig", "Ed25519",
        "rest_auth", "Ed25519 over 8-line LF canonical: version/device/METHOD/path/rawQuery/sha256hex(body)/ts/b64(nonce)"));
    vectors.put("inputs", Map.of(
        "server_eph_priv", hexStr(SERVER_EPH_PRIV),
        "device_enc_priv", hexStr(DEVICE_ENC_PRIV),
        "device_sign_seed", hexStr(DEVICE_SIGN_SEED),
        "nonce", hexStr(NONCE),
        "rest_nonce", hexStr(REST_NONCE),
        "response_eph_priv", hexStr(RESPONSE_EPH_PRIV)));
    vectors.put("outputs", Map.of(
        "device_enc_pub_b64", B64.enc(deviceEncPub),
        "server_eph_pub_b64", B64.enc(serverEphPub),
        "shared_secret_hex", hexStr(shared),
        "session_key_hex", hexStr(sessionKey),
        "device_sign_pub_b64", B64.enc(deviceSignPub),
        "server_id_pub_b64", B64.enc(serverId.publicKey()),
        "server_pub_fp", Ed25519.fingerprint(serverId.publicKey())));
    vectors.put("signatures", Map.of(
        "kx_message_hex", hexStr(kxBytes),
        "kx_sig_b64", B64.enc(kxSig),
        "ws_auth_message", new String(wsAuthBytes, UTF_8),
        "ws_auth_sig_b64", B64.enc(wsAuthSig)));
    // get_search 故意把 method 写成小写、query 带空值参数：C# 侧任何"顺手规范化"都会在这里露出来
    vectors.put("rest_auth", Map.of(
        "canonical_version", SignatureTargets.REST_AUTH_V1,
        "device_id", "dev_7f3a",
        "client_nonce_b64", B64.enc(REST_NONCE),
        "empty_body_sha256_hex", Hashes.sha256Hex(new byte[0]),
        "get_search", Map.of(
            "method", "get",
            "path", "/api/v1/search",
            "query", restQuery,
            "body", "",
            "ts", 1_765_500_000_000L,
            "canonical", new String(restGetBytes, UTF_8),
            "sig_b64", B64.enc(restGetSig)),
        "post_channels", Map.of(
            "method", "POST",
            "path", "/api/v1/channels",
            "query", "",
            "body", new String(restPostBody, UTF_8),
            "body_sha256_hex", Hashes.sha256Hex(restPostBody),
            "ts", 1_765_500_000_001L,
            "canonical", new String(restPostBytes, UTF_8),
            "sig_b64", B64.enc(restPostSig))));
    vectors.put("sealed_box", Map.of(
        "derive", "K=HKDF-SHA256(ikm=X25519(device_enc_priv,eph_pub), salt=sha256(eph_pub 原始字节), info=pushbox-frame-v1, len=32); n 恒为 0",
        "eph_priv", hexStr(RESPONSE_EPH_PRIV),
        "eph_pub_b64", B64.enc(responseEph.publicKey()),
        "salt_b64", B64.enc(responseSalt),
        "session_key_hex", hexStr(responseChannel.key()),
        "frame_index", registerSealed.frameIndex(),
        "plaintext_json", new String(registerPlain, UTF_8),
        "envelope_json", Json.write(sealedBox)));
    vectors.put("frame", Map.of(
        "plaintext_json", messageJson,
        "frame_index", enc.n(),
        "ciphertext_b64", enc.data(),
        "envelope_json", Json.write(enc)));
    vectors.put("ratchet", Map.of(
        "info", new String(FrameChannel.RATCHET_INFO, UTF_8),
        "epoch1_key_hex", hexStr(nextEpochKey(sessionKey, 1))));
    vectors.put("summary", Map.of(
        "title_priority", "[严重] CPU 使用率 > 90%",
        "markdown_stripped", dev.pushbox.core.text.Summary.stripMarkdown("正文 **粗体**\n> 引用")));

    ObjectMapper mapper = new ObjectMapper();
    mapper.writerWithDefaultPrettyPrinter().writeValue(vectorPath().toFile(), vectors);
    assertTrue(Files.exists(vectorPath()), "vectors.json 必须产出，P3 Windows 依赖它");
  }

  private static byte[] nextEpochKey(byte[] key, int epoch) {
    return Hkdf.derive(key, java.nio.ByteBuffer.allocate(4).putInt(epoch).array(),
        FrameChannel.RATCHET_INFO, 32);
  }

  private static Path vectorPath() {
    String configured = System.getProperty("pushbox.vectors.path");
    // 落在仓库里而不是 target/：target 会被 mvn clean 抹掉，而这份文件是 P3 Windows 端的开工前提，
    // 它必须像契约一样能被检出、被 diff。-D 参数留给 CI 覆盖。
    Path path = Path.of(configured != null ? configured : "../testvectors/vectors.json");
    try {
      Files.createDirectories(path.toAbsolutePath().getParent());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return path;
  }

  private static byte[] hex(String s) {
    String clean = s.replaceAll("[^0-9a-fA-F]", "");
    byte[] out = new byte[clean.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }

  private static String hexStr(byte[] data) {
    return Hashes.toHex(data);
  }
}
