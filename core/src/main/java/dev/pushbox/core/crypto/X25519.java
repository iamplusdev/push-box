package dev.pushbox.core.crypto;

import java.security.SecureRandom;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

/** X25519 密钥协商（§4.3 下行帧密钥）。 */
public final class X25519 {

  public record KeyPair(byte[] privateKey, byte[] publicKey) {}

  private X25519() {}

  public static KeyPair generate() {
    byte[] priv = new byte[32];
    new SecureRandom().nextBytes(priv);
    return fromPrivate(priv);
  }

  public static KeyPair fromPrivate(byte[] priv) {
    X25519PrivateKeyParameters p = new X25519PrivateKeyParameters(priv, 0);
    return new KeyPair(priv.clone(), p.generatePublicKey().getEncoded());
  }

  public static byte[] publicKeyOf(byte[] priv) {
    return fromPrivate(priv).publicKey();
  }

  /** 32 字节共享密钥；对端公钥长度不对时直接拒绝。 */
  public static byte[] agree(byte[] privateKey, byte[] peerPublicKey) {
    if (peerPublicKey == null || peerPublicKey.length != 32) {
      throw new IllegalArgumentException("bad X25519 public key length");
    }
    X25519Agreement agreement = new X25519Agreement();
    agreement.init(new X25519PrivateKeyParameters(privateKey, 0));
    byte[] shared = new byte[agreement.getAgreementSize()];
    agreement.calculateAgreement(new X25519PublicKeyParameters(peerPublicKey, 0), shared, 0);
    return shared;
  }
}
