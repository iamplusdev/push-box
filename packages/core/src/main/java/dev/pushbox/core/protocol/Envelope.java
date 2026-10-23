package dev.pushbox.core.protocol;

import dev.pushbox.core.crypto.FrameChannel;

/** 把任意帧装进 {t:"enc",n,data} 载体 / 还原，服务端与客户端走同一条代码路径。 */
public final class Envelope {

  private Envelope() {}

  public static String seal(FrameChannel channel, Object frame) {
    FrameChannel.Sealed sealed = channel.seal(Json.writeBytes(frame));
    return Json.write(Frames.Enc.of(sealed.frameIndex(), sealed.data()));
  }

  /** 返回内层帧的 JSON；tag 校验失败或序号不连续返回 null。 */
  public static String open(FrameChannel channel, String encJson) {
    Frames.Enc enc = Json.read(encJson, Frames.Enc.class);
    if (enc == null || !Frames.T_ENC.equals(enc.t())) {
      return null;
    }
    byte[] plain = channel.open(enc.n(), enc.data());
    return plain == null ? null : new String(plain, java.nio.charset.StandardCharsets.UTF_8);
  }

  /** 明文控制帧（hello/kx/auth 之外）的解码。 */
  public static <T> T decode(String json, Class<T> type) {
    return Json.read(json, type);
  }

  public static String typeOf(String json) {
    try {
      return Json.MAPPER.readTree(json).path("t").asText(null);
    } catch (Exception e) {
      return null;
    }
  }
}
