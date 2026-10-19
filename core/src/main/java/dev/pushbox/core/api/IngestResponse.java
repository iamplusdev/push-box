package dev.pushbox.core.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/** §5.1 响应：统一 200（不是 201），level 回显的是频道 max_level 截断后的实际生效值。 */
public record IngestResponse(
    boolean ok,
    String id,
    long seq,
    String summary,
    @JsonProperty("media_status") String mediaStatus,
    @JsonProperty("media_count") int mediaCount,
    String level) {

  public static IngestResponse accepted(String id, long seq, String summary, boolean hasImages,
      int mediaCount, String level) {
    return new IngestResponse(true, id, seq, summary, hasImages ? "queued" : "none", mediaCount,
        level);
  }
}
