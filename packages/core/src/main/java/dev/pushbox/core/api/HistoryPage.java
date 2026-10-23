package dev.pushbox.core.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.core.protocol.Frames.MediaSlot;
import java.util.List;

/**
 * §5.2 GET /api/v1/messages 的响应正文（整体再包一层 §4.3 的加密信封）。
 * 重装后 since_seq=0 走这里，文字与 media_status 一次拿全（D3 验收③）。
 */
public record HistoryPage(
    @JsonProperty("messages") List<HistoryItem> messages,
    @JsonProperty("tombstones") List<TombstoneItem> tombstones,
    @JsonProperty("latest_seq") long latestSeq,
    @JsonProperty("has_more") boolean hasMore) {

  public record HistoryItem(
      long seq,
      String id,
      @JsonProperty("channel_no") String channelNo,
      @JsonProperty("sender_id") String senderId,
      @JsonProperty("sender_name") String senderName,
      @JsonProperty("sender_avatar") String senderAvatar,
      String type,
      String title,
      String summary,
      String content,
      String level,
      String url,
      List<MediaSlot> media,
      @JsonProperty("media_status") String mediaStatus,
      @JsonProperty("created_at") long createdAt,
      @JsonProperty("deleted_at") Long deletedAt) {

    public Frames.Message toFrame(boolean notify) {
      return new Frames.Message(Frames.T_MESSAGE, seq, channelNo, id, senderId, type, title, summary,
          content, level, url, media, senderName, senderAvatar, createdAt, notify, false);
    }
  }

  public record TombstoneItem(String id, @JsonProperty("deleted_at") long deletedAt) {}
}
