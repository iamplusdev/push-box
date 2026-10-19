package dev.pushbox.server.service;

import dev.pushbox.core.crypto.B64;
import dev.pushbox.core.crypto.Ed25519;
import dev.pushbox.server.repo.PbStore;
import java.security.SecureRandom;
import org.springframework.stereotype.Service;

/**
 * 服务端长期身份密钥：Ed25519 用于签 kx 帧（客户端 TOFU 固定其指纹，§4.3）。
 * 私钥种子首次启动生成后存进 app_state —— 库是唯一持久层，进程重启不会让已固定指纹的客户端失效。
 */
@Service
public class ServerKeys {

  private static final String SIGN_SEED = "server_sign_seed";
  private static final SecureRandom RANDOM = new SecureRandom();

  private final PbStore store;
  private volatile Ed25519.KeyPair identity;

  public ServerKeys(PbStore store) {
    this.store = store;
  }

  public Ed25519.KeyPair identity() {
    Ed25519.KeyPair current = identity;
    if (current == null) {
      synchronized (this) {
        current = identity;
        if (current == null) {
          current = loadOrInit();
          identity = current;
        }
      }
    }
    return current;
  }

  /** 延迟到首次使用才读库：Bean 构造期跑在 Flyway 建表之前。 */
  private Ed25519.KeyPair loadOrInit() {
    String seed = store.getState(SIGN_SEED);
    if (seed == null) {
      seed = B64.enc(randomBytes(32));
      store.putState(SIGN_SEED, seed, System.currentTimeMillis());
    }
    return Ed25519.fromPrivateSeed(B64.dec(seed));
  }

  public String fingerprint() {
    return Ed25519.fingerprint(identity().publicKey());
  }

  public byte[] sign(byte[] message) {
    return Ed25519.sign(identity().privateKey(), message);
  }

  public static byte[] randomBytes(int len) {
    byte[] out = new byte[len];
    RANDOM.nextBytes(out);
    return out;
  }
}
