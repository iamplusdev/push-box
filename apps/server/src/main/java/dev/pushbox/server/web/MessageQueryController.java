package dev.pushbox.server.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mybatisflex.core.row.Row;
import dev.pushbox.core.api.HistoryPage;
import dev.pushbox.core.protocol.Frames;
import dev.pushbox.core.protocol.Json;
import dev.pushbox.server.push.FrameAssembler;
import dev.pushbox.server.push.PushRegistry;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.ChannelService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** §5.2 客户端读取接口：响应体一律过 §4.3 的密封盒，明文不出服务端进程。 */
@RestController
@RequestMapping("/api/v1")
public class MessageQueryController {

  private static final int MAX_LIMIT = 500;

  public record ReadItem(@com.fasterxml.jackson.annotation.JsonProperty("channel_no") String channelNo,
      @com.fasterxml.jackson.annotation.JsonProperty("sender_id") String senderId,
      @com.fasterxml.jackson.annotation.JsonProperty("last_read_seq") long lastReadSeq) {}

  private final PbStore store;
  private final DeviceGuard guard;
  private final FrameAssembler assembler;
  private final PushRegistry registry;
  private final ChannelService channels;

  public MessageQueryController(PbStore store, DeviceGuard guard, FrameAssembler assembler,
      PushRegistry registry, ChannelService channels) {
    this.store = store;
    this.guard = guard;
    this.assembler = assembler;
    this.registry = registry;
    this.channels = channels;
  }

  /** 重连/重装后的增量补拉：after_seq=0 即全量恢复历史（D3 验收③）。 */
  @GetMapping("/messages")
  public Map<String, Object> history(HttpServletRequest http,
      @RequestParam(name = "after_seq", defaultValue = "0") long afterSeq,
      @RequestParam(name = "channel_no", required = false) String channelNo,
      @RequestParam(name = "limit", defaultValue = "200") int limit) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    java.util.Set<String> scope = channels.readScope(auth.device());
    List<Row> rows = store.listMessagesAfter(auth.groupId(), afterSeq, clampLimit(limit), scope);
    List<HistoryPage.HistoryItem> items = new ArrayList<>();
    List<HistoryPage.TombstoneItem> tombstones = new ArrayList<>();
    for (Row row : rows) {
      if (row.getLong("deleted_at") != null) {
        tombstones.add(new HistoryPage.TombstoneItem(row.getString("msg_id"),
            row.getLong("deleted_at")));
      } else if (channelNo == null || channelNo.equals(row.getString("channel_no"))) {
        items.add(assembler.toItem(row));
      }
    }
    HistoryPage page = new HistoryPage(items, tombstones, store.latestSeq(),
        rows.size() >= clampLimit(limit));
    return guard.seal(auth.device(), page);
  }

  /** §5.3：帧里带 truncated:true 时，客户端按 id 从这里取未截断的全文。 */
  @GetMapping("/messages/{msgId}")
  public Map<String, Object> fulltext(@PathVariable String msgId, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    Row row = store.findMessageWithSender(msgId);
    if (row == null || row.getLong("deleted_at") != null
        || !auth.groupId().equals(row.getString("group_id"))) {
      throw ApiException.notFound("message");
    }
    return guard.seal(auth.device(), assembler.toItem(row));
  }

  /** D9：已读用游标，一次 PUT 置整个会话已读，并广播给同组其他端。 */
  @PutMapping("/state/read")
  public Map<String, Object> putReadState(HttpServletRequest http, @RequestBody byte[] body) {
    DeviceGuard.Authenticated auth = guard.require(http, body);
    List<ReadItem> items;
    try {
      items = Json.MAPPER.readValue(body, new TypeReference<List<ReadItem>>() {});
    } catch (Exception e) {
      throw ApiException.invalidParam("请求体必须是 [{channel_no,sender_id,last_read_seq}]");
    }
    long now = System.currentTimeMillis();
    for (ReadItem item : items) {
      if (item.channelNo() == null || item.senderId() == null) {
        throw ApiException.invalidParam("channel_no 与 sender_id 必填");
      }
      store.upsertReadState(auth.groupId(), item.channelNo(), item.senderId(), item.lastReadSeq(),
          auth.deviceId(), now);
      registry.broadcastToChannel(auth.groupId(), item.channelNo(),
          Frames.Read.of(item.channelNo(), item.senderId(), item.lastReadSeq(), auth.deviceId()));
    }
    return guard.seal(auth.device(), Map.of("ok", true, "updated", items.size()));
  }

  @DeleteMapping("/messages/{msgId}")
  public Map<String, Object> delete(@PathVariable String msgId, HttpServletRequest http) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    Row message = store.findMessageScope(msgId);
    if (message == null || !auth.groupId().equals(message.getString("group_id"))) {
      throw ApiException.notFound("message");
    }
    long now = System.currentTimeMillis();
    if (store.tombstoneMessage(msgId, auth.groupId(), now) == 0) {
      throw ApiException.notFound("message");
    }
    // D12：对象归服务端所有，所以删除必须顺带把图片标成待删；真删由 MediaPurgeService 等过宽限期
    store.markMediaDeleted(msgId, now);
    registry.broadcastToChannel(auth.groupId(), message.getString("channel_no"), Frames.Tombstone.of(msgId, now));
    return guard.seal(auth.device(), Map.of("ok", true, "id", msgId, "deleted_at", now));
  }

  /**
   * §5.2 清空整会话（D13）或整频道：一条 UPDATE 标完 + 一条带 scope 的 tombstone 帧。
   * 带 {@code sender_id} 只清该会话；省略 {@code sender_id} 则清整个频道（scope 帧的 sender_id 为 null，
   * 客户端据此清掉该频道下所有会话）。逐条删除在这里是错的：可能上千条，既打爆 SQL 往返也让其他端收到上千帧。
   */
  @DeleteMapping("/messages")
  public Map<String, Object> deleteConversation(HttpServletRequest http,
      @RequestParam(name = "channel_no", required = false) String channelNo,
      @RequestParam(name = "sender_id", required = false) String senderId) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    if (channelNo == null || channelNo.isBlank()) {
      throw ApiException.invalidParam("channel_no 必填");
    }
    long now = System.currentTimeMillis();
    boolean wholeChannel = senderId == null || senderId.isBlank();
    int deleted;
    if (wholeChannel) {
      deleted = store.tombstoneChannel(auth.groupId(), channelNo, now);
      store.markMediaDeletedChannel(auth.groupId(), channelNo, now);
      if (deleted > 0) {
        registry.broadcastToChannel(auth.groupId(), channelNo, Frames.Tombstone.ofScope(channelNo, null, now));
      }
    } else {
      deleted = store.tombstoneConversation(auth.groupId(), channelNo, senderId, now);
      store.markMediaDeletedConversation(auth.groupId(), channelNo, senderId, now);
      if (deleted > 0) {
        registry.broadcastToChannel(auth.groupId(), channelNo, Frames.Tombstone.ofScope(channelNo, senderId, now));
      }
    }
    Map<String, Object> resp = new java.util.LinkedHashMap<>();
    resp.put("ok", true);
    resp.put("channel_no", channelNo);
    resp.put("sender_id", wholeChannel ? "" : senderId);
    resp.put("deleted", deleted);
    resp.put("deleted_at", now);
    // 幂等：会话/频道本来就空或已清空时回 deleted=0，不报 404（客户端重试不该看到"不存在"）
    return guard.seal(auth.device(), resp);
  }

  private static int clampLimit(int limit) {
    return Math.min(Math.max(limit, 1), MAX_LIMIT);
  }
}
