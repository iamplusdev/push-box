package dev.pushbox.core.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * §5.2 GET /api/v1/search 的响应正文（D19，整体再包一层 §4.3 的加密信封）。
 * 只回命中片段而不是全文：客户端拿 id 走本地缓存或 §5.2 全文回读取整条，
 * 一次搜索 200 条命中如果都带正文，跨网的响应体就是几百 KB。
 */
public record SearchPage(
    @JsonProperty("results") List<SearchHit> results,
    @JsonProperty("has_more") boolean hasMore) {

  public record SearchHit(
      long seq,
      String id,
      @JsonProperty("channel_no") String channelNo,
      @JsonProperty("sender_id") String senderId,
      @JsonProperty("sender_name") String senderName,
      String type,
      String title,
      String snippet,
      String level,
      @JsonProperty("media_status") String mediaStatus,
      @JsonProperty("created_at") long createdAt) {}
}
