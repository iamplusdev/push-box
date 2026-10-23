package dev.pushbox.core.model;

/** §5.1 type 字段；未知类型按 D 的规则降级为 text，不拒绝整条消息。 */
public enum MsgType {
  TEXT, MARKDOWN, LINK, FILE;

  public String wire() {
    return name().toLowerCase();
  }

  public static MsgType fromWire(String value) {
    if (value == null) {
      return TEXT;
    }
    try {
      return valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return TEXT;
    }
  }
}
