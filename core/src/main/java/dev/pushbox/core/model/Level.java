package dev.pushbox.core.model;

/** 提醒强度。error > warning > info，D25 的频道 max_level 用它做向下截断。 */
public enum Level {
  INFO, WARNING, ERROR;

  public String wire() {
    return name().toLowerCase();
  }

  public static Level fromWire(String value) {
    if (value == null || value.isBlank()) {
      return INFO;
    }
    try {
      return valueOf(value.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return INFO;
    }
  }

  /** 把请求带来的 level 截断到频道上限（只会向下，不会升级）。 */
  public Level truncateTo(Level max) {
    return this.ordinal() <= max.ordinal() ? this : max;
  }
}
