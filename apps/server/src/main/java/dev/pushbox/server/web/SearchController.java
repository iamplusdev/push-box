package dev.pushbox.server.web;

import dev.pushbox.core.api.SearchPage;
import dev.pushbox.server.service.ChannelService;
import dev.pushbox.server.service.SearchService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §5.2 GET /api/v1/search：D19 把全文搜索放在服务端做，客户端不需要持有历史全文。
 * query 参数顺序固定为 q, channel_no, sender_id, type, since, limit —— REST 签名把原始 query 串
 * 逐字节进 canonical，任何一端换序都会整片验签失败。
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

  private final SearchService search;
  private final DeviceGuard guard;
  private final ChannelService channels;

  public SearchController(SearchService search, DeviceGuard guard, ChannelService channels) {
    this.search = search;
    this.guard = guard;
    this.channels = channels;
  }

  @GetMapping("/search")
  public Map<String, Object> search(HttpServletRequest http,
      @RequestParam(name = "q") String q,
      @RequestParam(name = "channel_no", required = false) String channelNo,
      @RequestParam(name = "sender_id", required = false) String senderId,
      @RequestParam(name = "type", required = false) String type,
      @RequestParam(name = "since", defaultValue = "0") long since,
      @RequestParam(name = "limit", defaultValue = "50") int limit) {
    DeviceGuard.Authenticated auth = guard.require(http, null);
    java.util.Set<String> scope = channels.readScope(auth.device());
    SearchPage page = search.search(auth.groupId(), q, channelNo, senderId, type, since, limit, scope);
    return guard.seal(auth.device(), page);
  }
}
