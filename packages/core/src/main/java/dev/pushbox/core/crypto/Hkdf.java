package dev.pushbox.core.crypto;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/** HKDF-SHA256（§4.3 的 K = HKDF(shared, info="pushbox-frame-v1", salt=nonce)）。 */
public final class Hkdf {
  private Hkdf() {}

  public static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int outputLength) {
    HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
    hkdf.init(new HKDFParameters(ikm, salt, info));
    byte[] out = new byte[outputLength];
    hkdf.generateBytes(out, 0, outputLength);
    return out;
  }
}
