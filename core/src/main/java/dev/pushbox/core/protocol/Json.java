package dev.pushbox.core.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 全项目唯一的 ObjectMapper 配置：只写非 null、忽略未知字段（协议向前兼容的关键）。 */
public final class Json {
  public static final ObjectMapper MAPPER = new ObjectMapper()
      .setSerializationInclusion(JsonInclude.Include.NON_NULL)
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private Json() {}

  public static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("frame serialize failed", e);
    }
  }

  public static <T> T read(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalArgumentException("frame parse failed: " + type.getSimpleName(), e);
    }
  }

  public static byte[] writeBytes(Object value) {
    try {
      return MAPPER.writeValueAsBytes(value);
    } catch (Exception e) {
      throw new IllegalStateException("serialize failed", e);
    }
  }

  public static <T> T readBytes(byte[] data, Class<T> type) {
    try {
      return MAPPER.readValue(data, type);
    } catch (Exception e) {
      throw new IllegalArgumentException("parse failed: " + type.getSimpleName(), e);
    }
  }

  /**
   * 树形读取。PATCH 式接口（改频道治理位）必须区分"这个字段没提交"与"提交了 null"，
   * 反序列化成记录类时两者都变 null，未提交的字段就会被静默清空。
   */
  public static com.fasterxml.jackson.databind.JsonNode readNode(byte[] data) {
    try {
      return MAPPER.readTree(data == null ? new byte[0] : data);
    } catch (Exception e) {
      throw new IllegalArgumentException("parse failed: JsonNode", e);
    }
  }
}
