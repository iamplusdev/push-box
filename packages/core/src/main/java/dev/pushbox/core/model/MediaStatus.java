package dev.pushbox.core.model;

/** messages.media_status：整条消息的图片聚合状态。 */
public enum MediaStatus {
  NONE, PENDING, READY, PARTIAL, FAILED;

  public String wire() {
    return name().toLowerCase();
  }

  /** 由 media_objects 的单张状态聚合出消息级状态（§4.5 合并窗口用）。 */
  public static MediaStatus aggregate(java.util.List<String> perObjectStatuses) {
    if (perObjectStatuses == null || perObjectStatuses.isEmpty()) {
      return NONE;
    }
    int ready = 0;
    int failed = 0;
    int pending = 0;
    for (String s : perObjectStatuses) {
      switch (s) {
        case "ready" -> ready++;
        case "failed", "deleted", "purged" -> failed++;
        default -> pending++;
      }
    }
    if (pending > 0) {
      return PENDING;
    }
    if (ready == 0) {
      return FAILED;
    }
    return failed == 0 ? READY : PARTIAL;
  }
}
