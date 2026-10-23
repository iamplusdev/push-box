package dev.pushbox.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import dev.pushbox.core.protocol.Json;

/**
 * 绑定 {@code byte[]} 的端点共用的解析收口。
 *
 * <p>{@link Json#readBytes} 与 {@link Json#readNode} 把 Jackson 的失败一律包成
 * {@link IllegalArgumentException}，而 {@code GlobalExceptionHandler} 不接它，症状是 HTTP 500 加
 * Spring 默认错误体（还会回显 {@code path}），不是全站那份 {@code {ok,error,hint}} 信封。
 * 这些端点不直接绑记录类是因为要先按原始字节量上限（治理位的 PATCH 还得区分"没提交"与"提交 null"），
 * 所以收口只能落在这一层。措辞与 {@code handleBadJson} 保持一致。
 */
public final class RequestBodyJson {

  private RequestBodyJson() {}

  public static <T> T read(byte[] body, Class<T> type) {
    try {
      return Json.readBytes(body, type);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidParam("请求体必须是合法 JSON");
    }
  }

  /** PATCH 式请求体走树形读取：空请求体在 Jackson 这里可能是 null，也会一路走到 NPE。 */
  public static JsonNode readTree(byte[] body) {
    JsonNode node;
    try {
      node = Json.readNode(body);
    } catch (IllegalArgumentException e) {
      throw ApiException.invalidParam("请求体必须是合法 JSON");
    }
    if (node == null || node.isMissingNode() || !node.isObject()) {
      throw ApiException.invalidParam("请求体必须是一段 JSON 对象");
    }
    return node;
  }
}
