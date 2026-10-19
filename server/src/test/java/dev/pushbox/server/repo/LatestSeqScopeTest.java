package dev.pushbox.server.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import dev.pushbox.core.id.Ulid;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * §5.3 补拉判据的取数范围。这里盯的是"落后多少条"该跟谁比：
 * WS 的 resume_gap 阈值原先拿全局 MAX(seq) 比一个组内游标，结果是别的 Space 的流量
 * 能把一条空消息的新 Space 顶过阈值——每次连接只回 resume_gap、永远等不到 resume_ok，
 * 客户端顶栏停在"连接中"而这条连接其实活得好好的（真服务体检 ② 抓到的）。
 */
@EnabledIfEnvironmentVariable(named = "PB_S3_ACCESS_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LatestSeqScopeTest {

  private static final String MY_CHANNEL = "LTSEQ01";
  private static final String THEIR_CHANNEL = "LTSEQ02";
  private static final String MINE = "ltseq-group";
  private static final String QUIET = "ltseq-quiet-group";
  private static final String SENDER = "grafana";

  @Autowired
  private PbStore store;

  private final List<String> ids = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    if (!ids.isEmpty()) {
      String marks = String.join(",", ids.stream().map(x -> "?").toList());
      Db.updateBySql("DELETE FROM media_objects WHERE msg_id IN (" + marks + ")", ids.toArray());
    }
    Db.updateBySql("DELETE FROM messages WHERE group_id IN (?, ?)", MINE, QUIET);
    ids.clear();
  }

  @Test
  @DisplayName("① 一条消息都没有的 Space：组内行高是 0，全局行高却不是——阈值只能比前者")
  void emptySpaceHasItsOwnFloor() {
    message(MINE, "别组在推 一条");
    message(MINE, "别组在推 二条");

    assertThat(store.latestSeq(QUIET)).isZero();
    assertThat(store.latestSeq()).isPositive();
    // 这一对比就是那个 bug 的全部成因：拿后面这个数减 0，任何一台新设备都会被当成"落后一大堆"
    assertThat(store.latestSeq()).isGreaterThan(store.latestSeq(QUIET));
  }

  @Test
  @DisplayName("② 组内行高只跟着本组最新那条走，别组再怎么能推也改不了它")
  void groupFloorTracksOnlyItsOwnNewestRow() {
    String first = message(QUIET, "本组 一条");
    message(MINE, "别组 插队的");
    String last = message(QUIET, "本组 二条");
    message(MINE, "别组 又插队的");

    assertThat(store.latestSeq(QUIET)).isEqualTo(seq(last));
    assertThat(store.latestSeq(QUIET)).isNotEqualTo(seq(first));
    // 全局那两条都比它新，所以组内值一定小于全局值：resume_ok 等的就是这个差不再误判
    assertThat(store.latestSeq(QUIET)).isLessThan(store.latestSeq());
  }

  private String message(String groupId, String body) {
    String msgId = Ulid.next();
    // 两个组各用各的频道号：这条测的是行高，不该顺手造出"两个组共用一个对外号码"这种库里没有的形态
    store.insertMessage(msgId, MINE.equals(groupId) ? MY_CHANNEL : THEIR_CHANNEL,
        groupId, SENDER, "text", null, body, body, null, "info",
        "none", System.currentTimeMillis(), null);
    ids.add(msgId);
    return msgId;
  }

  private long seq(String msgId) {
    Row row = Db.selectOneBySql("SELECT seq FROM messages WHERE msg_id = ?", msgId);
    return row == null ? -1L : row.getLong("seq");
  }
}
