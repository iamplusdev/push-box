package dev.pushbox.core.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** §5.1 请求体：第三方唯一写入口 POST /message，凭据全在 body。 */
public record IngestRequest(
    String channel,
    String secret,
    String type,
    String content,
    @JsonProperty("sender_id") String senderId,
    @JsonProperty("sender_name") String senderName,
    @JsonProperty("sender_avatar") String senderAvatar,
    String title,
    String level,
    String url,
    List<String> images,
    @JsonProperty("msg_key") String msgKey,
    Long ts,
    String nonce,
    String sign) {

  public static final int MAX_CONTENT_CHARS = 32 * 1024;
  public static final int MAX_IMAGES = 4;

  /** 归一化后的发送方标识，省略时按 unknown 归到一个会话。 */
  public String senderIdOrDefault() {
    return (senderId == null || senderId.isBlank()) ? "unknown" : senderId.trim();
  }
}
