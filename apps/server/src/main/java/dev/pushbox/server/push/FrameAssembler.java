package dev.pushbox.server.push;

import com.mybatisflex.core.row.Row;
import dev.pushbox.core.api.HistoryPage;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.core.protocol.Frames.MediaSlot;
import dev.pushbox.server.repo.PbStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 把 messages + media_objects 行组装成下发帧 / 历史页，两条路径共用一份映射规则。 */
@Component
public class FrameAssembler {

  private static final String ELLIPSIS = "…";

  private final PbStore store;

  public FrameAssembler(PbStore store) {
    this.store = store;
  }

  private List<MediaSlot> mediaSlots(String msgId) {
    List<MediaSlot> slots = new ArrayList<>();
    for (Row row : store.listMedia(msgId)) {
      slots.add(new MediaSlot(
          intValue(row, "idx", 0),
          row.getString("status"),
          row.getString("object_key"),
          row.getString("mime"),
          row.getLong("size"),
          intValueOrNull(row, "width"),
          intValueOrNull(row, "height"),
          row.getString("thumb_key")));
    }
    return slots;
  }

  /**
   * media_status=none 的行必然没有媒体记录，直接跳过查询：
   * 纯文字消息的下发与补拉（一次 500 条）否则每条都要多打一次库，跨网环境里这就是秒级差距。
   */
  public List<MediaSlot> mediaSlots(Row message) {
    String status = message.getString("media_status");
    if (status == null || dev.pushbox.core.model.MediaStatus.NONE.wire().equals(status)) {
      return List.of();
    }
    return mediaSlots(message.getString("msg_id"));
  }

  public Frames.Message toFrame(Row message, boolean notifyClient) {
    HistoryPage.HistoryItem item = toItem(message);
    return item.toFrame(notifyClient);
  }

  public HistoryPage.HistoryItem toItem(Row message) {
    String msgId = message.getString("msg_id");
    return new HistoryPage.HistoryItem(
        longValue(message, "seq", 0L),
        msgId,
        message.getString("channel_no"),
        message.getString("sender_id"),
        message.getString("sender_name"),
        message.getString("sender_avatar"),
        message.getString("msg_type"),
        message.getString("title"),
        message.getString("summary"),
        message.getString("body"),
        message.getString("level"),
        message.getString("link_url"),
        mediaSlots(message),
        message.getString("media_status"),
        longValue(message, "created_at", 0L),
        message.getLong("deleted_at"));
  }

  /**
   * 单帧超上限时削正文并置 truncated，客户端再走 GET /api/v1/messages/{id} 取全文（§5.3）。
   * 削多少必须按**字节**算：UTF-8 下一个中文占 3 字节却只算 1 个字符，
   * 之前"按超出的字节数删字符"对中文等于一次删掉三倍需求量，7KB 正文会被直接删空只剩省略号
   * （{@code FrameAssemblerCapTest} ④ 就是这条回归）。这里改成对"保留多长前缀"做二分，
   * log2(字符数) 次序列化拿到装得下的最长前缀。
   */
  public Frames.Message capFrame(Frames.Message frame, int maxBytes) {
    if (frame.content() == null || JsonBytes.of(frame) <= maxBytes) {
      return frame;
    }
    String content = frame.content();
    int low = 0;
    int high = content.length();
    while (low < high) {
      int mid = (low + high + 1) >>> 1;
      if (JsonBytes.of(withContent(frame, content.substring(0, mid) + ELLIPSIS)) <= maxBytes) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    // 代理对不能劈开：半个字符序列化出来是替换符，客户端画成一串乱码
    int keep = low > 0 && Character.isHighSurrogate(content.charAt(low - 1)) ? low - 1 : low;
    return withContent(frame, content.substring(0, keep) + ELLIPSIS);
  }

  private static Frames.Message withContent(Frames.Message frame, String content) {
    return new Frames.Message(Frames.T_MESSAGE, frame.seq(), frame.channelNo(), frame.id(),
        frame.senderId(), frame.type(), frame.title(), frame.summary(), content, frame.level(),
        frame.url(), frame.media(), frame.senderName(), frame.senderAvatar(), frame.createdAt(),
        frame.notifyClient(), true);
  }

  private static int intValue(Row row, String column, int fallback) {
    Integer v = row.getInt(column);
    return v == null ? fallback : v;
  }

  private static Integer intValueOrNull(Row row, String column) {
    return row.getInt(column);
  }

  private static long longValue(Row row, String column, long fallback) {
    Long v = row.getLong(column);
    return v == null ? fallback : v;
  }

  private static final class JsonBytes {
    static int of(Object frame) {
      return dev.pushbox.core.protocol.Json.writeBytes(frame).length;
    }
  }
}
