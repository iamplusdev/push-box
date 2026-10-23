package dev.pushbox.server.web;

import dev.pushbox.core.api.ApiError;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * §4.4 的响应纪律：错误体只有 {ok,error,hint} 三样，hint 是人话而不是回显输入；
 * 日志里同样禁止出现请求体（正文可能含敏感信息）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
    ApiError error = e.toError();
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.status());
    if (e.status() == 429) {
      // 机器读 Retry-After、人读 hint：锁定那条的 hint 是中文话术，不能当秒数发出去
      String retryAfter = e.retryAfterSeconds() >= 0 ? String.valueOf(e.retryAfterSeconds())
          : error.hint();
      if (retryAfter != null) {
        builder.header("Retry-After", retryAfter);
      }
    }
    if (e.status() >= 500) {
      log.error("服务端错误 code={}", error.error(), e);
    }
    return builder.body(Map.of("ok", false, "error", error.error(),
        "hint", error.hint() == null ? "" : error.hint()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> handleBadJson(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest().body(
        Map.of("ok", false, "error", "invalid_param", "hint", "请求体必须是合法 JSON"));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
    return ResponseEntity.badRequest().body(
        Map.of("ok", false, "error", "invalid_param", "hint", "缺少参数"));
  }
}
