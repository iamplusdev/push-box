package dev.pushbox.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.pushbox.core.protocol.Frames;
import dev.pushbox.core.protocol.Json;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 一段完整 JSON 后面再拖一段垃圾（{@code {"a":1}x}）必须解析失败。
 *
 * <p>这条口径落在 {@link Json} 这一把尺子上是有代价的：HTTP 的 {@code byte[]} 端点、WS 入站帧、
 * 管理台 JWT payload 解析共用同一个 mapper，所以在这里改宽松度等于改协议。代价的另一面是——
 * 关掉之后，这四个入站口子（{@code RequestBodyJson} 的两条路 + {@code Envelope} +
 * {@code AdminAuthController}）会一起变严，逐个用 HTTP 层用例去验太慢，所以先在协议层把
 * "哪几条路会因此抛"钉死，服务端那两个 BodyTest 只负责确认抛出来的东西被收成了 400 信封。
 *
 * <p>2026-10-21 改之前实测到的现状是这四条路**全都照单收下**：{@code readValue} 与 {@code readTree}
 * 都不检查尾部残留 token（Jackson 默认 {@code FAIL_ON_TRAILING_TOKENS=false}），
 * 于是 curl 打 {@code {"enabled":1}垃圾} 拿到的是 200 且请求真执行了。
 */
class JsonTrailingTokenTest {

  private static final byte[] TRAILING = "{\"a\":1}x".getBytes(StandardCharsets.UTF_8);
  private static final byte[] TWO_OBJECTS =
      "{\"a\":1}{\"a\":2}".getBytes(StandardCharsets.UTF_8);

  @Test
  @DisplayName("① readBytes：尾部多一个字符就抛，不是静默取第一个对象")
  void readBytesRejectsTrailingToken() {
    assertThrows(IllegalArgumentException.class, () -> Json.readBytes(TRAILING, Map.class));
    assertThrows(IllegalArgumentException.class, () -> Json.readBytes(TWO_OBJECTS, Map.class));
  }

  @Test
  @DisplayName("② readNode（PATCH 那条路）同样不放过尾部垃圾")
  void readNodeRejectsTrailingToken() {
    assertThrows(IllegalArgumentException.class, () -> Json.readNode(TRAILING));
    assertThrows(IllegalArgumentException.class, () -> Json.readNode(TWO_OBJECTS));
  }

  @Test
  @DisplayName("③ WS 帧那条字符串路：一个文本帧只许装一个对象")
  void readRejectsTrailingToken() {
    assertThrows(IllegalArgumentException.class,
        () -> Json.read("{\"t\":\"ping\"}x", Frames.Simple.class));
  }

  @Test
  @DisplayName("④ 变严只针对尾部残留：合法体加换行缩进、未知字段、null 值一律照旧")
  void ordinaryBodiesAreUnaffected() {
    byte[] padded = "\n  {\"a\":1,\n  \"b\":2}\t \n".getBytes(StandardCharsets.UTF_8);
    assertEquals(2, Json.readBytes(padded, Map.class).size());
    assertEquals(1, Json.readNode(bytes("{\"muted\":1}")).path("muted").asInt());
    // 协议向前兼容靠的是忽略未知字段，这一条不能被这次收紧顺带改掉
    Frames.Hello back = Json.read(
        "{\"server_time\":1,\"heartbeat\":25,\"server_pub_fp\":\"fp\",\"nonce\":\"n\",\"extra\":9}",
        Frames.Hello.class);
    assertEquals("fp", back.serverPubFp());
    // 显式 null 仍要能读出来，PATCH 要区分"没提交"和"提交了 null"
    assertTrue(Json.readNode(bytes("{\"name\":null}")).has("name"));
  }

  @Test
  @DisplayName("⑤ 数组体不受牵连：PUT /state/read 绑的就是 List")
  void arraysStillParse() {
    assertEquals(2, Json.readBytes(bytes("[{\"a\":1},{\"b\":2}]"), java.util.List.class).size());
    assertThrows(IllegalArgumentException.class,
        () -> Json.readBytes(bytes("[{\"a\":1},{\"b\":2}]x"), java.util.List.class));
  }

  private static byte[] bytes(String json) {
    return json.getBytes(StandardCharsets.UTF_8);
  }
}
