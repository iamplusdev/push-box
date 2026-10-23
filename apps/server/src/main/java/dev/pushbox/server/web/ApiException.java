package dev.pushbox.server.web;

import dev.pushbox.core.api.ApiError;

/** 业务错误：HTTP 状态码 + §5.1 的错误字符串，交给 GlobalExceptionHandler 渲染。 */
public class ApiException extends RuntimeException {

  private final int status;
  private final String code;
  private final String hint;
  /** 429 的可重试秒数；-1 表示没指定。hint 给人看，这个数给 Retry-After 头看。 */
  private final long retryAfterSeconds;

  public ApiException(int status, String code, String hint) {
    this(status, code, hint, -1L);
  }

  public ApiException(int status, String code, String hint, long retryAfterSeconds) {
    super(code);
    this.status = status;
    this.code = code;
    this.hint = hint;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public int status() {
    return status;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }

  public ApiError toError() {
    return ApiError.of(code, hint);
  }

  public static ApiException invalidParam(String hint) {
    return new ApiException(400, "invalid_param", hint);
  }

  /** §4.4 strict_auth / ts 校验失败。 */
  public static ApiException badRequest(String code, String hint) {
    return new ApiException(400, code, hint);
  }

  public static ApiException invalidCredential() {
    // 频道不存在与密钥错共用同一话术，避免枚举频道号码
    return new ApiException(401, "invalid_credential", null);
  }

  public static ApiException channelLocked() {
    return new ApiException(403, "channel_locked", null);
  }

  public static ApiException tooLarge() {
    return new ApiException(413, "too_large", null);
  }

  public static ApiException rateLimited(long retryAfterSeconds) {
    return new ApiException(429, "rate_limited", String.valueOf(retryAfterSeconds), retryAfterSeconds);
  }

  /**
   * §5.2 管理台：口令错到阈值就按来源 IP 锁定。状态码是 429 而不是 401——
   * 控制台（apps/web-admin/src/views/Login.vue）显示的是 {@code data.hint || '密钥错误'}，
   * 所以 hint 直接写成人话，让"被锁了"和"口令错了"在界面上分得开。
   */
  public static ApiException loginLocked(long retryAfterSeconds) {
    return new ApiException(429, "login_locked",
        "失败次数过多，已临时锁定，请 " + retryAfterSeconds + " 秒后重试", retryAfterSeconds);
  }

  public static ApiException deviceLimitReached() {
    return new ApiException(409, "device_limit_reached", null);
  }

  public static ApiException unauthorized(String hint) {
    return new ApiException(401, "unauthorized", hint);
  }

  public static ApiException notFound(String what) {
    return new ApiException(404, "not_found", what);
  }

  /** §14：白名单拒绝等权限不足场景。 */
  public static ApiException forbidden(String hint) {
    return new ApiException(403, "forbidden", hint);
  }

  public static ApiException notFound() {
    return new ApiException(404, "not_found", null);
  }
}
