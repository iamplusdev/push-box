package dev.pushbox.server.admin;

import com.mybatisflex.core.row.Row;
import dev.pushbox.server.config.PbProperties;
import dev.pushbox.server.media.MediaPurgeService;
import dev.pushbox.server.repo.PbStore;
import dev.pushbox.server.service.ChannelService;
import dev.pushbox.server.service.DeviceService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * §11 管理 CLI：通过 {@code --admin=<command>} 触发一次性管理操作，执行完即退出。
 * 用法示例：
 * <pre>{@code
 *   java -jar pushbox-server.jar --admin=device-revoke --device-id=dev_xxx
 *   java -jar pushbox-server.jar --admin=rotate-secret --channel-no=12345678
 *   java -jar pushbox-server.jar --admin=export-json --channel-no=12345678 --output=out.json
 *   java -jar pushbox-server.jar --admin=media-sweep
 *   java -jar pushbox-server.jar --admin=flyway-info
 * }</pre>
 */
@Component
public class AdminCli implements CommandLineRunner {

  private final PbStore store;
  private final DeviceService devices;
  private final ChannelService channels;
  private final MediaPurgeService purge;
  // ObjectProvider 而不是 Flyway：spring.flyway.enabled=false（DBA 手工迁移那类部署）时
  // Boot 根本不会建这个 bean，直接注入会让整条 web 服务起不来——管理子命令缺一个依赖
  // 不该拖垮推送主链路。
  private final ObjectProvider<Flyway> flyway;
  private final PbProperties props;

  public AdminCli(PbStore store, DeviceService devices, ChannelService channels,
      MediaPurgeService purge, ObjectProvider<Flyway> flyway, PbProperties props) {
    this.store = store;
    this.devices = devices;
    this.channels = channels;
    this.purge = purge;
    this.flyway = flyway;
    this.props = props;
  }

  @Override
  public void run(String... args) throws Exception {
    String adminCmd = extractArg(args, "--admin");
    if (adminCmd == null) {
      return;  // 正常 web 启动，不拦截
    }

    switch (adminCmd) {
      case "device-revoke" -> {
        String deviceId = requireArg(args, "--device-id");
        Row device = store.findDevice(deviceId);
        if (device == null) {
          err("设备不存在: " + deviceId);
          System.exit(1);
        }
        devices.revoke(device.getString("group_id"), deviceId);
        out("已吊销设备 " + deviceId);
      }
      case "rotate-secret" -> {
        String channelNo = requireArg(args, "--channel-no");
        Row channel = store.findChannel(channelNo);
        if (channel == null) {
          err("频道不存在: " + channelNo);
          System.exit(1);
        }
        // rotateSecret 的 SQL 按 (channel_no, group_id) 收口，null 组号会匹配 0 行、必 404，
        // 所以运维侧只报得出频道号时，先按频道号查回它自己的组号再传进去。
        ChannelService.Created created =
            channels.rotateSecret(channelNo, channel.getString("group_id"));
        out("频道 " + channelNo + " 密钥已轮换");
        out("新 secret: " + created.secret());
      }
      case "export-json" -> {
        String output = requireArg(args, "--output");
        String groupId = extractArg(args, "--group");
        String channelNo = extractArg(args, "--channel-no");
        if (groupId != null) {
          exportGroupJson(groupId, Path.of(output));
          out("已导出频道组 " + groupId + " 到 " + output);
        } else if (channelNo != null) {
          exportChannelJson(channelNo, Path.of(output));
          out("已导出频道 " + channelNo + " 到 " + output);
        } else {
          err("export-json 需要 --group=<group_id> 或 --channel-no=<no>");
          System.exit(1);
        }
      }
      case "media-sweep" -> {
        int purged = purge.sweep();
        out("宽限期巡检完成，真删了 " + purged + " 个媒体对象");
      }
      case "flyway-info" -> {
        Flyway fw = flyway.getIfAvailable();
        if (fw == null) {
          err("Flyway 未启用（spring.flyway.enabled=false），迁移状态请由 DBA 侧查 flyway_schema_history");
          System.exit(1);
        }
        var info = fw.info();
        for (var migration : info.all()) {
          out(migration.getVersion() + " | " + migration.getDescription()
              + " | " + migration.getState());
        }
      }
      default -> {
        err("未知命令: " + adminCmd);
        printUsage();
        System.exit(1);
      }
    }
    System.exit(0);
  }

  private void exportChannelJson(String channelNo, Path outputPath) throws IOException {
    Row channel = store.findChannel(channelNo);
    if (channel == null) {
      err("频道不存在: " + channelNo);
      System.exit(1);
    }
    StringBuilder json = new StringBuilder("{\n");
    json.append("  \"channel_no\": ").append(jsonStr(channelNo)).append(",\n");
    json.append("  \"name\": ").append(jsonStr(channel.getString("name"))).append(",\n");
    json.append("  \"messages\": ").append(messagesJson(store.listMessagesByChannel(channelNo, 0, 10000), "  "));
    json.append("\n}\n");
    Files.writeString(outputPath, json.toString());
  }

  /** §11 按组导出：把该组所有未归档频道的消息整成一个 JSON，用于换机/灾备前的人工留档。 */
  private void exportGroupJson(String groupId, Path outputPath) throws IOException {
    List<Row> chans = store.listChannels(groupId);
    StringBuilder json = new StringBuilder("{\n");
    json.append("  \"group_id\": ").append(jsonStr(groupId)).append(",\n");
    json.append("  \"channels\": [\n");
    for (int c = 0; c < chans.size(); c++) {
      Row ch = chans.get(c);
      String no = ch.getString("channel_no");
      json.append("    {\n");
      json.append("      \"channel_no\": ").append(jsonStr(no)).append(",\n");
      json.append("      \"name\": ").append(jsonStr(ch.getString("name"))).append(",\n");
      json.append("      \"messages\": ")
          .append(messagesJson(store.listMessagesByChannel(no, 0, 10000), "      "));
      json.append("\n    }").append(c < chans.size() - 1 ? "," : "").append("\n");
    }
    json.append("  ]\n}\n");
    Files.writeString(outputPath, json.toString());
  }

  /** 把一组消息行序列成 JSON 数组文本；indent 是数组闭合括号的缩进，逐条再深两格。 */
  private static String messagesJson(List<Row> messages, String indent) {
    StringBuilder sb = new StringBuilder("[\n");
    for (int i = 0; i < messages.size(); i++) {
      Row msg = messages.get(i);
      sb.append(indent).append("  {\n");
      sb.append(indent).append("    \"id\": ").append(jsonStr(msg.getString("msg_id"))).append(",\n");
      sb.append(indent).append("    \"seq\": ").append(msg.getLong("seq")).append(",\n");
      sb.append(indent).append("    \"sender_id\": ").append(jsonStr(msg.getString("sender_id"))).append(",\n");
      sb.append(indent).append("    \"type\": ").append(jsonStr(msg.getString("msg_type"))).append(",\n");
      sb.append(indent).append("    \"title\": ").append(jsonStr(msg.getString("title"))).append(",\n");
      sb.append(indent).append("    \"body\": ").append(jsonStr(msg.getString("body"))).append(",\n");
      sb.append(indent).append("    \"created_at\": ").append(msg.getLong("created_at")).append("\n");
      sb.append(indent).append("  }").append(i < messages.size() - 1 ? "," : "").append("\n");
    }
    sb.append(indent).append("]");
    return sb.toString();
  }

  private static String jsonStr(String s) {
    return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
  }

  private static String extractArg(String[] args, String key) {
    for (String arg : args) {
      if (arg.startsWith(key + "=")) {
        return arg.substring(key.length() + 1);
      }
    }
    return null;
  }

  private static String requireArg(String[] args, String key) {
    String value = extractArg(args, key);
    if (value == null) {
      err("缺少必需参数: " + key);
      System.exit(1);
    }
    return value;
  }

  private static void out(String msg) {
    System.out.println(msg);
  }

  private static void err(String msg) {
    System.err.println(msg);
  }

  private static void printUsage() {
    out("""
        用法: java -jar pushbox-server.jar --admin=<command> [options]

        可用命令:
          device-revoke    --device-id=<id>     吊销指定设备
          rotate-secret    --channel-no=<no>     轮换频道密钥
          export-json      --group=<group_id> 或 --channel-no=<no> --output=<file>  导出消息为 JSON
          media-sweep                            触发宽限期媒体清理
          flyway-info                            显示数据库迁移状态
        """);
  }
}
