package dev.pushbox.core.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;

/** 所有 Ed25519 签名覆盖的 canonical 字节，服务端与客户端（以及 P3 的 C#）必须逐字节一致。 */
public final class SignatureTargets {

  public static final String WS_AUTH_V1 = "pushbox-ws-auth-v1";
  public static final String REST_AUTH_V1 = "pushbox-rest-auth-v1";

  private SignatureTargets() {}

  /** §4.3：服务端 kx 帧的 sig = Ed25519(server_eph_pub ‖ nonce)。 */
  public static byte[] kx(byte[] ephPub, byte[] nonce) {
    byte[] out = new byte[ephPub.length + nonce.length];
    System.arraycopy(ephPub, 0, out, 0, ephPub.length);
    System.arraycopy(nonce, 0, out, ephPub.length, nonce.length);
    return out;
  }

  public static byte[] wsAuth(String deviceId, byte[] serverNonce, long ts) {
    return (WS_AUTH_V1 + "\n" + deviceId + "\n" + B64.enc(serverNonce) + "\n" + ts).getBytes(UTF_8);
  }

  public static byte[] restAuth(String deviceId, String method, String path, String query,
      byte[] requestBody, long ts, byte[] clientNonce) {
    String bodyHash = Hashes.sha256Hex(requestBody == null ? new byte[0] : requestBody);
    return (REST_AUTH_V1 + "\n" + deviceId + "\n" + method.toUpperCase() + "\n" + path + "\n"
        + (query == null ? "" : query) + "\n" + bodyHash + "\n" + ts + "\n"
        + B64.enc(clientNonce)).getBytes(UTF_8);
  }
}
