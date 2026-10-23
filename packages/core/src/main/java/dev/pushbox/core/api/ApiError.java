package dev.pushbox.core.api;

/** 错误响应：绝不回显任何输入字段（§4.4 防枚举与防泄露）。 */
public record ApiError(boolean ok, String error, String hint) {
  public static ApiError of(String error, String hint) {
    return new ApiError(false, error, hint);
  }

  public static ApiError of(String error) {
    return new ApiError(false, error, null);
  }
}
