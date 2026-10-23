package dev.pushbox.core.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

/** §4.3 的会话密钥与帧序号状态机，服务端与客户端共用。 */
public final class FrameChannel {

  public static final byte[] FRAME_INFO = "pushbox-frame-v1".getBytes(UTF_8);
  public static final byte[] RATCHET_INFO = "pushbox-frame-ratchet-v1".getBytes(UTF_8);
  public static final long ROTATE_AFTER_MILLIS = 24L * 3600 * 1000;
  public static final long ROTATE_AFTER_FRAMES = 1L << 32;

  private byte[] key;
  private long sendCount;
  private long recvExpected;
  private int epoch;
  private long epochStartMillis;

  /** K = HKDF-SHA256(shared, salt=nonce, info="pushbox-frame-v1", 32)。 */
  public static byte[] sessionKey(byte[] sharedSecret, byte[] salt) {
    return Hkdf.derive(sharedSecret, salt, FRAME_INFO, AesGcm.KEY_BYTES);
  }

  public FrameChannel(byte[] sessionKey, long nowMillis) {
    this.key = sessionKey.clone();
    this.epochStartMillis = nowMillis;
  }

  public byte[] key() {
    return key.clone();
  }

  public int epoch() {
    return epoch;
  }

  public long nextFrameIndex() {
    return sendCount;
  }

  public Sealed seal(byte[] plaintext) {
    byte[] ct = AesGcm.seal(key, sendCount, plaintext);
    long n = sendCount++;
    return new Sealed(n, B64.enc(ct));
  }

  public byte[] open(long n, String dataB64) {
    byte[] pt = AesGcm.open(key, n, B64.dec(dataB64));
    if (pt != null && n == recvExpected) {
      recvExpected = n + 1;
    }
    return pt;
  }

  /** 帧序号必须是连续递增的，跳号说明连接被复用或有人重放。 */
  public boolean accepts(long n) {
    return n == recvExpected;
  }

  public boolean shouldRotate(long nowMillis) {
    return sendCount >= ROTATE_AFTER_FRAMES || nowMillis - epochStartMillis >= ROTATE_AFTER_MILLIS;
  }

  public void rotate(long nowMillis) {
    key = Hkdf.derive(key, java.nio.ByteBuffer.allocate(4).putInt(++epoch).array(), RATCHET_INFO,
        AesGcm.KEY_BYTES);
    sendCount = 0;
    epochStartMillis = nowMillis;
  }

  public record Sealed(long frameIndex, String data) {}
}
