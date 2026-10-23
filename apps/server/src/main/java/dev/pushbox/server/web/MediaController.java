package dev.pushbox.server.web;

import com.mybatisflex.core.row.Row;
import dev.pushbox.server.media.MediaObjects;
import dev.pushbox.server.repo.PbStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §5.2 的媒体读接口：桶是私有的，服务端校验完设备归属后**自己把对象取回来**再返回字节。
 * 之所以不再 302 到预签名 URL：SSE-C 的客户端密钥不能进预签名（实测：带 SSE-C 写的对象用预签名
 * GET 直接 400 InvalidRequest），而图片流量过一道服务端顺带解决了"设备够不到桶地址"的可达性问题。
 * 单张图有 {@code pb.media.max-image-bytes} 的上限，字节量级和抓取时落在内存里的那份是一样的。
 */
@RestController
@RequestMapping("/api/v1")
public class MediaController {

  /** avatar_token 是 sha256 的小写十六进制：先按格式拦掉乱填的路径，再决定要不要打库。 */
  private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

  private final PbStore store;
  private final DeviceGuard guard;
  private final MediaObjects objects;

  public MediaController(PbStore store, DeviceGuard guard, MediaObjects objects) {
    this.store = store;
    this.guard = guard;
    this.objects = objects;
  }

  @GetMapping("/media/{msgId}/{idx}")
  public ResponseEntity<byte[]> read(@PathVariable String msgId, @PathVariable int idx,
      @RequestParam(name = "size", defaultValue = "full") String size, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    if (!"full".equals(size) && !"thumb".equals(size)) {
      throw ApiException.invalidParam("size 只能是 thumb 或 full");
    }
    Row row = store.mediaSlotForRead(msgId, idx);
    if (row == null || !auth.groupId().equals(row.getString("group_id"))) {
      // 跨组与不存在回同一句话，免得探测别人组的 msg_id 是否存在
      throw ApiException.notFound("media");
    }
    // 消息已被 tombstone：这一版之前的历史数据里 media_objects 还没被标记，所以按消息的删除时间判。
    // 但 sweep 已经真删过对象（purged）时以它为准——这条信息更具体，客户端据此知道"再重试也不会有图"，
    // 联调探针也靠 deleted→purged 这个变化确认桶里的字节确实被删掉了。
    String rowStatus = row.getString("status");
    String status = "purged".equals(rowStatus) ? rowStatus
        : row.getLong("msg_deleted_at") != null ? "deleted" : rowStatus;
    if (!"ready".equals(status)) {
      throw new ApiException(404, "not_found", status);
    }
    String thumbKey = row.getString("thumb_key");
    // 缩略图可能压根没有（WebP 无解码器、原图已经小于边长阈值）：这时降级发原图，
    // 客户端图片加载器不用为"404 后再请求一次 full"写分支
    String key = "thumb".equals(size) && thumbKey != null ? thumbKey : row.getString("object_key");
    return serve(key, row.getBytes("sse_key_wrapped"), row.getString("mime"), "media");
  }

  /**
   * §5.2 的头像读接口。路径参数是 sender_id 的 sha256 而不是原文：第三方自报的 id 可以含
   * {@code /}、{@code ..} 或中文，让它进 URL 就得在路由层做转义，而 token 定长且只含十六进制。
   */
  @GetMapping("/avatars/{token}")
  public ResponseEntity<byte[]> avatar(@PathVariable String token, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    if (!HEX.matcher(token).matches()) {
      throw ApiException.notFound("avatar");
    }
    Row row = store.senderAvatarForRead(auth.groupId(), token);
    String key = row == null ? null : row.getString("avatar_key");
    if (key == null) {
      // 没转存成功与别的组的 token 共用 404，客户端按首字占位画
      throw ApiException.notFound("avatar");
    }
    return serve(key, row.getBytes("sse_key_wrapped"), null, "avatar");
  }

  /**
   * 库里那一列 {@code sse_key_wrapped} 决定这次读要不要带客户钥：老对象是明文写的，
   * 传 null 就按明文读，所以 pb.s3.object-encrypt 的切换不影响历史数据（§4.5）。
   */
  private ResponseEntity<byte[]> serve(String objectKey, byte[] sseKeyWrapped, String fallbackMime,
      String what) {
    MediaObjects.Loaded loaded = objects.load(objectKey, sseKeyWrapped);
    if (loaded == null) {
      // 状态是 ready 但桶里查无此对象：宽限期真删之后不该再被读到，走到这里说明桶被动过
      throw ApiException.notFound(what);
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .contentType(contentType(loaded.contentType(), fallbackMime))
        .body(loaded.body());
  }

  /** Content-Type 用对象当初写进去的那一份；缺了就按库里的 mime 兜，再缺只回二进制。 */
  private static MediaType contentType(String stored, String fallback) {
    for (String candidate : new String[] {stored, fallback}) {
      if (candidate != null && !candidate.isBlank()) {
        try {
          return MediaType.parseMediaType(candidate.trim());
        } catch (RuntimeException ignored) {
          // 自报或历史脏数据里的类型串不认，接着往下兜
        }
      }
    }
    return MediaType.APPLICATION_OCTET_STREAM;
  }
}
