package dev.pushbox.core.model;

/** 单张外链的抓取状态（media_objects.status）。 */
public enum FetchStatus {
  PENDING, READY, FAILED, DELETED;

  public String wire() {
    return name().toLowerCase();
  }

  /** 终态 = 到这儿就不会再变了，合并窗口可以据此提前广播（§4.5）。 */
  public boolean terminal() {
    return this != PENDING;
  }
}
