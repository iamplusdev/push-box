package dev.pushbox.server.web;

import dev.pushbox.core.api.IngestRequest;
import dev.pushbox.core.api.IngestResponse;
import dev.pushbox.server.service.IngestService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** §5.1 唯一入站写接口：根路径、无版本前缀、频道号不进 URL。 */
@RestController
public class MessageController {

  private final IngestService ingest;

  public MessageController(IngestService ingest) {
    this.ingest = ingest;
  }

  @PostMapping(path = "${pb.ingest.base-path:/message}", consumes = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<IngestResponse> message(@RequestBody IngestRequest request,
      HttpServletRequest http) {
    return ResponseEntity.ok(ingest.ingest(request, Requests.clientIp(http)));
  }
}
