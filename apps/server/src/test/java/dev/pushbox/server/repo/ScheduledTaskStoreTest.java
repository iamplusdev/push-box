package dev.pushbox.server.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 定时任务的落库侧，只在真库上跑（因此它同时是 Flyway V9 的往返证明）。
 *
 * <p>{@link dev.pushbox.server.service.ScheduledTaskLogicTest} 里 PbStore 是 mock，
 * 而 {@code insertScheduledTask} 的列清单与 {@code ?} 占位符是靠人手工对齐的两份列表：
 * 加一列忘了改另一处，Mockito 一路绿灯，线上才是 {@code Incorrect number of column values}。
 * 这里不读源码、不数占位符，直接插一行再读回来——列数、顺序、{@code category} 的落值一次性判掉。
 */
@EnabledIf("dev.pushbox.server.test.TestConfig#databaseReady")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScheduledTaskStoreTest {

  private static final String TASK_ID = "01TASK000000000000000CAT1";
  private static final String CHANNEL_NO = "CATT0001";
  private static final String GROUP = "cat-store-group";
  /** 2096-09-… 刻意摆到遥远的将来：任何一台在跑的服务按真实时钟扫描都捞不到这行。 */
  private static final long SLOT = 4_000_000_000_000L;

  @Autowired
  private PbStore store;

  @BeforeEach
  void seedChannel() {
    cleanUp();
    store.insertChannel(CHANNEL_NO, "0".repeat(64), GROUP, "分类落库测试频道", "error", 4,
        System.currentTimeMillis());
  }

  @AfterEach
  void cleanUp() {
    Db.updateBySql("DELETE FROM scheduled_tasks WHERE task_id = ?", TASK_ID);
    Db.updateBySql("DELETE FROM channels WHERE channel_no = ?", CHANNEL_NO);
  }

  @Test
  @DisplayName("① 分类写得进也读得回：INSERT 的列清单、SELECT * 的两条读路径都带得到 category")
  void categorySurvivesInsertAndReadBack() {
    store.insertScheduledTask(task("运维巡检"));

    assertThat(store.findScheduledTask(TASK_ID).getString("category")).isEqualTo("运维巡检");
    // 列表页那条走的是 LEFT JOIN + t.*，分类下拉的选项就从这里来
    assertThat(categoryIn(store.listScheduledTasks())).isEqualTo("运维巡检");
    // 扫描那条走的是另一句 SELECT *，抢完时隙还要靠这行里的字段投递
    assertThat(categoryIn(store.dueScheduledTasks(SLOT, 200))).isEqualTo("运维巡检");
  }

  @Test
  @DisplayName("② 编辑整体覆盖分类，清空落回空串而不是 null——列是 NOT NULL DEFAULT ''")
  void updateOverwritesCategoryAndEmptyIsNotNull() {
    store.insertScheduledTask(task("公告"));
    assertThat(store.findScheduledTask(TASK_ID).getString("category")).isEqualTo("公告");

    Row edited = task("公告");
    edited.set("category", "");
    assertThat(store.updateScheduledTask(edited)).isEqualTo(1);

    Row back = store.findScheduledTask(TASK_ID);
    assertThat(back.getString("category")).isEmpty();
    assertThat(back.get("category")).isNotNull();
  }

  private String categoryIn(List<Row> rows) {
    return rows.stream().filter(r -> TASK_ID.equals(r.getString("task_id")))
        .map(r -> r.getString("category"))
        .findFirst()
        .orElse(null);
  }

  /** 一行分类用例的任务；{@code category} 之外全部是与被测点无关的合法值。 */
  private static Row task(String category) {
    Row row = new Row();
    row.set("task_id", TASK_ID);
    row.set("group_id", GROUP);
    row.set("channel_no", CHANNEL_NO);
    row.set("name", "分类落库用例");
    row.set("category", category);
    row.set("title", "标题");
    row.set("content", "正文");
    row.set("level", "info");
    row.set("sender_name", "定时推送");
    row.set("images_json", null);
    row.set("cron_expr", "0 0 9 * * *");
    row.set("enabled", 1);
    // 放在很久以前会被任何一台真服务的扫描当成到期行，所以往未来摆：只有用例传进来的 now 能命中它
    row.set("next_fire_at", SLOT);
    row.set("created_at", SLOT);
    row.set("updated_at", SLOT);
    return row;
  }
}
