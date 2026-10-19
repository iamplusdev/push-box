package dev.pushbox.server.media;

import java.io.IOException;

/** §4.5 的抓取终态错误：code 原样进 media_objects.error（timeout/ssrf_blocked/too_large/bad_type/http_404…）。 */
class MediaFetchException extends IOException {

  static final String SSRF_BLOCKED = "ssrf_blocked";
  static final String TIMEOUT = "timeout";
  static final String TOO_LARGE = "too_large";
  static final String BAD_TYPE = "bad_type";
  static final String UNREACHABLE = "unreachable";

  final String code;

  MediaFetchException(String code, String message) {
    super(message);
    this.code = code;
  }
}
