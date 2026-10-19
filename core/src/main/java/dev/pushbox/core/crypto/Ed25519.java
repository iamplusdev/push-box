package dev.pushbox.core.crypto;

import java.security.SecureRandom;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/** Ed25519 设备身份签名（§4.2），用 BC 轻量 API，不走 JCA Provider，服务端与 Android 行为一致。 */
public final class Ed25519 {

  public record KeyPair(byte[] privateKey, byte[] publicKey) {}

  private Ed25519() {}

  public static KeyPair generate() {
    byte[] priv = new byte[32];
    new SecureRandom().nextBytes(priv);
    return fromPrivateSeed(priv);
  }

  /** 由 32 字节种子确定性派生（服务端身份密钥落库时用）。 */
  public static KeyPair fromPrivateSeed(byte[] seed) {
    Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(seed, 0);
    return new KeyPair(seed.clone(), priv.generatePublicKey().getEncoded());
  }

  public static byte[] sign(byte[] privateKeySeed, byte[] message) {
    Ed25519Signer signer = new Ed25519Signer();
    signer.init(true, new Ed25519PrivateKeyParameters(privateKeySeed, 0));
    signer.update(message, 0, message.length);
    return signer.generateSignature();
  }

  public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
    if (publicKey == null || publicKey.length != 32 || signature == null || signature.length != 64) {
      return false;
    }
    Ed25519Signer signer = new Ed25519Signer();
    signer.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
    signer.update(message, 0, message.length);
    return signer.verifySignature(signature);
  }

  /** 公钥指纹：sha256 前 16 字节 hex，客户端 TOFU 固定用（§4.3）。 */
  public static String fingerprint(byte[] publicKey) {
    return Hashes.sha256Hex(publicKey).substring(0, 16);
  }
}
