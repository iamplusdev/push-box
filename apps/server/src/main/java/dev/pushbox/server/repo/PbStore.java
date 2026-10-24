package dev.pushbox.server.repo;

import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Repository;

/**
 * 数据访问统一走 MyBatis-Flex 的 Row/Db API（D27 定的框架，无实体类、无 APT 依赖，
 * 表结构以 Flyway 为准）。SQL 一律用 ? 占位符绑定，不做字符串拼接。
 */
@Repository
public class PbStore {

  /**
   * Db 首次调用时会把当时的 SqlSessionFactory 永久缓存下来，早于 MyBatis-Flex 自动配置
   * 完成就缓存成 null，之后每个查询都 NPE。注入这个 Bean 只为建立创建顺序，不做任何调用。
   */
  private final SqlSessionFactory sqlSessionFactory;

  public PbStore(SqlSessionFactory sqlSessionFactory) {
    this.sqlSessionFactory = sqlSessionFactory;
  }

  // ---------- groups / devices ----------

  public void insertGroup(String groupId, String secretHash, long now) {
    Db.insertBySql("INSERT INTO `groups` (group_id, group_secret_hash, created_at) VALUES (?,?,?)",
        groupId, secretHash, now);
  }

  public Row findGroupBySecretHash(String secretHash) {
    return Db.selectOneBySql("SELECT group_id FROM `groups` WHERE group_secret_hash = ?", secretHash);
  }

  public Row findGroup(String groupId) {
    return Db.selectOneBySql("SELECT * FROM `groups` WHERE group_id = ?", groupId);
  }

  public void updateGroupSecretHash(String groupId, String newHash) {
    Db.updateBySql("UPDATE `groups` SET group_secret_hash = ? WHERE group_id = ?", newHash, groupId);
  }

  public void insertDevice(String deviceId, String groupId, String signPub, String encPub,
      String name, String platform, String token, long now) {
    Db.insertBySql("""
        INSERT INTO devices (device_id, group_id, device_pubkey, enc_pubkey, name, platform,
                             token, created_at, last_seen_at)
        VALUES (?,?,?,?,?,?,?,?,?)""",
        deviceId, groupId, signPub, encPub, name, platform, token, now, now);
  }

  public Row findDevice(String deviceId) {
    return Db.selectOneBySql("SELECT * FROM devices WHERE device_id = ?", deviceId);
  }

  public Row findDeviceByToken(String token) {
    return Db.selectOneBySql("SELECT * FROM devices WHERE token = ?", token);
  }

  public long countActiveDevices(String groupId) {
    Object count = Db.selectObject(
        "SELECT COUNT(*) FROM devices WHERE group_id = ? AND revoked_at IS NULL", groupId);
    return count instanceof Number number ? number.longValue() : 0L;
  }

  public List<Row> listDevices(String groupId) {
    return Db.selectListBySql("""
        SELECT device_id, name, platform, created_at, last_seen_at, revoked_at
        FROM devices WHERE group_id = ? ORDER BY created_at""", groupId);
  }

  /** Web 管理台：列出所有未吊销设备（跨 Space）。 */
  public List<Row> listAllDevices() {
    return Db.selectListBySql("""
        SELECT device_id, name, platform, group_id, created_at, last_seen_at, revoked_at
        FROM devices WHERE revoked_at IS NULL ORDER BY created_at DESC""");
  }

  /** Web 管理台：按频道号列出已接入该频道的设备。 */
  public List<Row> listDevicesByChannel(String channelNo) {
    return Db.selectListBySql("""
        SELECT d.device_id, d.name, d.platform, d.group_id, d.created_at, d.last_seen_at
        FROM devices d
        INNER JOIN channel_subscriptions cs ON d.device_id = cs.device_id
        WHERE cs.channel_no = ? AND d.revoked_at IS NULL
        ORDER BY d.created_at""", channelNo);
  }

  public void touchDevice(String deviceId, long now) {
    Db.updateBySql("UPDATE devices SET last_seen_at = ? WHERE device_id = ?", now, deviceId);
  }

  public int revokeDevice(String deviceId, String groupId, long now) {
    return Db.updateBySql("""
        UPDATE devices SET revoked_at = ?, token = CONCAT('revoked_', device_id)
        WHERE device_id = ? AND group_id = ? AND revoked_at IS NULL""", now, deviceId, groupId);
  }

  // ---------- channel_subscriptions（§5.2 频道接入） ----------

  /** 接入是幂等的：重复接入同一个频道不该报错，也不该刷新时间戳（那是同一条事实）。 */
  public void subscribeChannel(String deviceId, String channelNo, long now) {
    Db.updateBySql("INSERT IGNORE INTO channel_subscriptions (device_id, channel_no, created_at)"
        + " VALUES (?,?,?)", deviceId, channelNo, now);
  }

  /** 取消接入：返回真删掉的行数，0 就是"本来就没接入"，调用方按幂等处理。 */
  public int unsubscribeChannel(String deviceId, String channelNo) {
    return Db.deleteBySql("DELETE FROM channel_subscriptions WHERE device_id = ? AND channel_no = ?",
        deviceId, channelNo);
  }

  public List<String> subscribedChannelNos(String deviceId) {
    return Db.selectListBySql(
            "SELECT channel_no FROM channel_subscriptions WHERE device_id = ? ORDER BY channel_no",
            deviceId)
        .stream().map(row -> row.getString("channel_no")).toList();
  }

  /**
   * 组内哪些设备该收到这个频道的内容帧：没开过滤模式的照常收，开了的只收自己接入过的。
   * 只按 group 收口、不过滤 {@code revoked_at}——吊销的设备今天也是等心跳超时才掉线，
   * 这里顺手掐掉会让"吊销"出现两条不一致的生效时机。
   */
  public List<String> deviceIdsAllowedForChannel(String groupId, String channelNo) {
    return Db.selectListBySql("""
        SELECT d.device_id FROM devices d
        WHERE d.group_id = ? AND (d.subscribed_only = 0 OR EXISTS (
          SELECT 1 FROM channel_subscriptions s
          WHERE s.device_id = d.device_id AND s.channel_no = ?))""", groupId, channelNo)
        .stream().map(row -> row.getString("device_id")).toList();
  }

  public int setSubscribedOnly(String deviceId, String groupId, int value) {
    return Db.updateBySql("UPDATE devices SET subscribed_only = ? WHERE device_id = ? AND group_id = ?",
        value, deviceId, groupId);
  }

  // ---------- channels ----------

  public void insertChannel(String channelNo, String secretHash, String groupId, String name,
      String maxLevel, int maxImages, long now) {
    Db.insertBySql("""
        INSERT INTO channels (channel_no, secret_hash, group_id, name, max_level, max_images, created_at)
        VALUES (?,?,?,?,?,?,?)""",
        channelNo, secretHash, groupId, name, maxLevel, maxImages, now);
  }

  public Row findChannel(String channelNo) {
    return Db.selectOneBySql("SELECT * FROM channels WHERE channel_no = ?", channelNo);
  }

  /** 归档列表只回可用频道：已归档的频道对客户端等同于不存在（消息按 D3 仍留在库里）。 */
  public List<Row> listChannels(String groupId) {
    return Db.selectListBySql("""
        SELECT channel_no, group_id, name, max_level, max_images, rate_limit, max_body_bytes,
               strict_auth, muted, daily_msg_limit, daily_image_limit, daily_bytes_limit,
               sender_whitelist_mode, created_at, archived_at
        FROM channels WHERE group_id = ? AND archived_at IS NULL ORDER BY created_at""", groupId);
  }

  /** Web 管理台：列出所有未归档频道（跨 Space）。 */
  public List<Row> listAllChannels() {
    return Db.selectListBySql("""
        SELECT channel_no, group_id, name, max_level, max_images, rate_limit, max_body_bytes,
               strict_auth, muted, daily_msg_limit, daily_image_limit, daily_bytes_limit,
               sender_whitelist_mode, created_at, archived_at
        FROM channels WHERE archived_at IS NULL ORDER BY created_at DESC""");
  }

  /**
   * §5.2/§14 治理位写入。列名只能取自这张白名单、值一律走占位符：{@code values} 直接来自不受信的
   * 请求体，拼进 SQL 的就是注入面，所以这里宁可多写一次校验也不靠调用方自觉。
   */
  private static final java.util.Set<String> CHANNEL_GOVERNANCE_COLUMNS = java.util.Set.of(
      "name", "max_level", "max_images", "rate_limit", "max_body_bytes", "strict_auth", "muted",
      "daily_msg_limit", "daily_image_limit", "daily_bytes_limit", "sender_whitelist_mode");

  public int updateChannelGovernance(String channelNo, String groupId,
      java.util.Map<String, ?> values) {
    if (values.isEmpty()) {
      return 0;
    }
    StringBuilder sql = new StringBuilder("UPDATE channels SET ");
    List<Object> args = new ArrayList<>();
    for (String column : CHANNEL_GOVERNANCE_COLUMNS) {
      if (!values.containsKey(column)) {
        continue;
      }
      sql.append(column).append(" = ?,");
      args.add(values.get(column));
    }
    if (args.isEmpty()) {
      return 0;
    }
    sql.setLength(sql.length() - 1);
    sql.append(" WHERE channel_no = ? AND group_id = ?");
    args.add(channelNo);
    args.add(groupId);
    return Db.updateBySql(sql.toString(), args.toArray());
  }

  public void recordCredentialFailure(String channelNo, int failCount, long lockedUntil) {
    Db.updateBySql("UPDATE channels SET fail_count = ?, locked_until = ? WHERE channel_no = ?",
        failCount, lockedUntil, channelNo);
  }

  public void clearCredentialFailures(String channelNo) {
    Db.updateBySql("UPDATE channels SET fail_count = 0, locked_until = 0 WHERE channel_no = ?",
        channelNo);
  }

  public int updateChannelSecret(String channelNo, String groupId, String secretHash) {
    return Db.updateBySql("UPDATE channels SET secret_hash = ? WHERE channel_no = ? AND group_id = ?",
        secretHash, channelNo, groupId);
  }

  public int updateChannelMaxLevel(String channelNo, String groupId, String maxLevel) {
    return Db.updateBySql("UPDATE channels SET max_level = ? WHERE channel_no = ? AND group_id = ?",
        maxLevel, channelNo, groupId);
  }

  public int updateChannelName(String channelNo, String groupId, String name) {
    return Db.updateBySql("UPDATE channels SET name = ? WHERE channel_no = ? AND group_id = ?",
        name, channelNo, groupId);
  }

  /** 软归档：只打时间戳，消息与对象一律保留（D3），入站由 IngestService 按 archived_at 拒写。 */
  public int archiveChannel(String channelNo, String groupId, long now) {
    return Db.updateBySql("UPDATE channels SET archived_at = ? WHERE channel_no = ? AND group_id = ?"
        + " AND archived_at IS NULL", now, channelNo, groupId);
  }

  /**
   * §5.2 频道号码重置：换发一个新的对外号码，把挂在旧号码下的会话、消息、媒体、已读游标一并迁过去。
   * 无 FK 的四张子表手工更新；channels 的主键更新放在最后，sender_allowlist / daily_counters 靠
   * FK 的 {@code ON UPDATE CASCADE} 自动跟随，避免手工改它们时撞上"新号码还不存在"的外键约束。
   */
  public void resetChannelNo(String groupId, String oldNo, String newNo) {
    Db.updateBySql("UPDATE messages SET channel_no = ? WHERE channel_no = ? AND group_id = ?",
        newNo, oldNo, groupId);
    Db.updateBySql("UPDATE media_objects SET channel_no = ? WHERE channel_no = ? AND group_id = ?",
        newNo, oldNo, groupId);
    Db.updateBySql("UPDATE read_state SET channel_no = ? WHERE channel_no = ? AND group_id = ?",
        newNo, oldNo, groupId);
    Db.updateBySql("UPDATE senders SET channel_no = ? WHERE channel_no = ?", newNo, oldNo);
    Db.updateBySql("UPDATE channels SET channel_no = ? WHERE channel_no = ? AND group_id = ?",
        newNo, oldNo, groupId);
  }

  // ---------- senders ----------

  /**
   * 帧与历史里的 {@code sender_avatar}：只暴露服务端自己的读接口，绝不把第三方 URL 原样下发 ——
   * 客户端直连外部头像等于把用户 IP 与设备信息交给别人的服务器（§4.5 转存头像的动机就在这）。
   * 没转存成功时这一列是 NULL，客户端按 §7 用 sender_id 首字生成占位头像。
   * 结尾的空格是必需的：文本块会削掉行首缩进，拼上去就是 {@code ...sender_avatarFROM messages}。
   */
  private static final String AVATAR_SQL =
      "CASE WHEN s.avatar_key IS NULL THEN NULL"
          + " ELSE CONCAT('/api/v1/avatars/', s.avatar_token) END AS sender_avatar\n";

  /**
   * 会话档案：自报字段只在非空时覆盖，计数与时间戳每次都要动。
   * avatarToken 与 sender_id 一一对应，所以每次都写，不参与 COALESCE。
   */
  public void upsertSender(String channelNo, String senderId, String displayName, String avatarUrl,
      String avatarToken, long now) {
    Db.updateBySql("""
        INSERT INTO senders (channel_no, sender_id, display_name, avatar_url, avatar_token,
                             msg_count, last_msg_at)
        VALUES (?,?,?,?,?,1,?)
        ON DUPLICATE KEY UPDATE
          display_name = COALESCE(VALUES(display_name), display_name),
          avatar_url   = COALESCE(VALUES(avatar_url), avatar_url),
          avatar_token = VALUES(avatar_token),
          msg_count    = msg_count + 1,
          last_msg_at  = VALUES(last_msg_at)""",
        channelNo, senderId, displayName, avatarUrl, avatarToken, now);
  }

  /**
   * 头像转存任务的前置查询：来源没变且已经有对象键，就说明这张头像早就存好了。
   * 加密两列也要带上——覆盖写同一个对象键时延用原来那把客户钥，换钥会让读侧撞空窗（§4.5）。
   */
  public Row findSenderAvatar(String channelNo, String senderId) {
    return Db.selectOneBySql("SELECT avatar_url, avatar_key, enc_mode, sse_key_wrapped FROM senders"
        + " WHERE channel_no=? AND sender_id=?", channelNo, senderId);
  }

  /** 转存成功才写键：来源列保持第三方给的原值，用来判断"来源变了没"。 */
  public void setSenderAvatarKey(String channelNo, String senderId, String objectKey, String encMode,
      byte[] sseKeyWrapped) {
    Db.updateBySql("UPDATE senders SET avatar_key=?, enc_mode=?, sse_key_wrapped=?"
        + " WHERE channel_no=? AND sender_id=?", objectKey, encMode, sseKeyWrapped, channelNo,
        senderId);
  }

  /**
   * 头像读接口（§5.2）的查询：group 与"已经转存成功"都进 WHERE，跨组、没存成与不存在就共用同一个
   * 空结果，不给"猜别人组的 sender 是否存在"留缝隙。同一个 sender_id 可以出现在同组的多个频道里，
   * 而对象键是按频道编的，所以必须 ORDER BY 才能保证 LIMIT 1 每次回同一行。
   */
  public Row senderAvatarForRead(String groupId, String avatarToken) {
    return Db.selectOneBySql("""
        SELECT s.avatar_key, s.enc_mode, s.sse_key_wrapped, c.group_id FROM senders s
        JOIN channels c ON c.channel_no = s.channel_no
        WHERE c.group_id = ? AND s.avatar_token = ? AND s.avatar_key IS NOT NULL
        ORDER BY s.channel_no LIMIT 1""", groupId, avatarToken);
  }

  // ---------- messages ----------

  public void insertMessage(String msgId, String channelNo, String groupId, String senderId,
      String msgType, String title, String body, String summary, String linkUrl, String level,
      String mediaStatus, long createdAt, String clientMsgId) {
    Db.insertBySql("""
        INSERT INTO messages (msg_id, channel_no, group_id, sender_id, msg_type, title, body, summary,
                              link_url, level, media_status, created_at, client_msg_id)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        msgId, channelNo, groupId, senderId, msgType, title, body, summary, linkUrl, level,
        mediaStatus, createdAt, clientMsgId);
  }

  public Row findMessageByClientId(String channelNo, String clientMsgId) {
    return Db.selectOneBySql("SELECT * FROM messages WHERE channel_no = ? AND client_msg_id = ?",
        channelNo, clientMsgId);
  }

  /** 下发帧要带发送者名称/头像，所以连着 senders 一次取回，省掉客户端侧的二次查询。 */
  public Row findMessageWithSender(String msgId) {
    return Db.selectOneBySql("""
        SELECT m.*, s.display_name AS sender_name, """ + AVATAR_SQL + """
        FROM messages m
        LEFT JOIN senders s ON s.channel_no = m.channel_no AND s.sender_id = m.sender_id
        WHERE m.msg_id = ?""", msgId);
  }

  public Row findMessageBySeq(long seq) {
    return Db.selectOneBySql("SELECT * FROM messages WHERE seq = ?", seq);
  }

  /** 删除帧要按订阅范围下发，所以先只取归属与号码这两列，不必连带 sender 与正文一起回读。 */
  public Row findMessageScope(String msgId) {
    return Db.selectOneBySql("SELECT msg_id, channel_no, group_id, deleted_at FROM messages"
        + " WHERE msg_id = ?", msgId);
  }

  /** 导出用：按频道拉取消息（不含 tombstone），limit 防 OOM。 */
  public List<Row> listMessagesByChannel(String channelNo, long afterSeq, int limit) {
    return Db.selectListBySql("""
        SELECT m.*, s.display_name AS sender_name, """ + AVATAR_SQL + """
        FROM messages m
        LEFT JOIN senders s ON s.channel_no = m.channel_no AND s.sender_id = m.sender_id
        WHERE m.channel_no = ? AND m.seq > ? AND m.deleted_at IS NULL
        ORDER BY m.seq LIMIT ?""", channelNo, afterSeq, limit);
  }

  /** 增量拉取：按 group 全量（多端一致），含 tombstone，客户端按 seq 去重。 */
  public List<Row> listMessagesAfter(String groupId, long afterSeq, int limit) {
    return listMessagesAfter(groupId, afterSeq, limit, null);
  }

  /**
   * 带订阅过滤的增量拉取：{@code scope} 为 null 表示不过滤（设备未进入过滤模式）；
   * 空集合表示已开过滤但没接入任何频道，一条都不给；非空则只回这些频道的消息。
   */
  public List<Row> listMessagesAfter(String groupId, long afterSeq, int limit,
      java.util.Collection<String> scope) {
    StringBuilder sql = new StringBuilder("""
        SELECT m.seq, m.msg_id, m.channel_no, m.group_id, m.sender_id, m.msg_type, m.title, m.body,
               m.summary, m.link_url, m.level, m.media_status, m.created_at, m.deleted_at,
               s.display_name AS sender_name, """ + AVATAR_SQL + """
        FROM messages m
        LEFT JOIN senders s ON s.channel_no = m.channel_no AND s.sender_id = m.sender_id
        WHERE m.group_id = ? AND m.seq > ?""");
    List<Object> params = new ArrayList<>(List.of(groupId, afterSeq));
    appendScope(sql, params, scope);
    sql.append(" ORDER BY m.seq LIMIT ?");
    params.add(limit);
    return Db.selectListBySql(sql.toString(), params.toArray());
  }

  /**
   * §5.2 D19 服务端全文搜索：ngram 分词命中 (title, summary, body)。
   * 只回 seq/msg_id 这些定位用的列，正文由 service 层裁成片段后再决定给不给客户端。
   */
  public List<Row> searchFulltext(String groupId, String query, String channelNo, String senderId,
      String msgType, long since, int limit) {
    return searchFulltext(groupId, query, channelNo, senderId, msgType, since, limit, null);
  }

  public List<Row> searchFulltext(String groupId, String query, String channelNo, String senderId,
      String msgType, long since, int limit, java.util.Collection<String> scope) {
    StringBuilder sql = new StringBuilder("""
        SELECT m.seq, m.msg_id, m.channel_no, m.sender_id, m.msg_type, m.title, m.body, m.summary,
               m.level, m.media_status, m.created_at, s.display_name AS sender_name
        FROM messages m
        LEFT JOIN senders s ON s.channel_no = m.channel_no AND s.sender_id = m.sender_id
        WHERE m.group_id = ? AND m.deleted_at IS NULL
          AND MATCH(m.title, m.summary, m.body) AGAINST (? IN NATURAL LANGUAGE MODE)""");
    List<Object> params = new ArrayList<>(List.of(groupId, query));
    appendSearchFilters(sql, params, channelNo, senderId, msgType, since);
    appendScope(sql, params, scope);
    sql.append(" ORDER BY m.seq DESC LIMIT ?");
    params.add(limit);
    return Db.selectListBySql(sql.toString(), params.toArray());
  }

  /**
   * LIKE 兜底：ngram_token_size=2 决定了单个汉字的查询在 MATCH 里永远命中不到（§3.2 注释里
   * 点名的就是这条），这类短查询只能走 LIKE。仍然带 group 边界，不允许全表模糊扫别人组的数据。
   */
  public List<Row> searchLike(String groupId, String pattern, String channelNo, String senderId,
      String msgType, long since, int limit) {
    return searchLike(groupId, pattern, channelNo, senderId, msgType, since, limit, null);
  }

  public List<Row> searchLike(String groupId, String pattern, String channelNo, String senderId,
      String msgType, long since, int limit, java.util.Collection<String> scope) {
    StringBuilder sql = new StringBuilder("""
        SELECT m.seq, m.msg_id, m.channel_no, m.sender_id, m.msg_type, m.title, m.body, m.summary,
               m.level, m.media_status, m.created_at, s.display_name AS sender_name
        FROM messages m
        LEFT JOIN senders s ON s.channel_no = m.channel_no AND s.sender_id = m.sender_id
        WHERE m.group_id = ? AND m.deleted_at IS NULL
          AND (m.body LIKE ? OR m.title LIKE ? OR m.summary LIKE ?)""");
    List<Object> params = new ArrayList<>(List.of(groupId, pattern, pattern, pattern));
    appendSearchFilters(sql, params, channelNo, senderId, msgType, since);
    appendScope(sql, params, scope);
    sql.append(" ORDER BY m.seq DESC LIMIT ?");
    params.add(limit);
    return Db.selectListBySql(sql.toString(), params.toArray());
  }

  /** 过滤条件全部参数绑定：搜索词与频道号都来自客户端，绝不能拼进 SQL。 */
  private static void appendSearchFilters(StringBuilder sql, List<Object> params, String channelNo,
      String senderId, String msgType, long since) {
    if (channelNo != null && !channelNo.isBlank()) {
      sql.append(" AND m.channel_no = ?");
      params.add(channelNo.trim());
    }
    if (senderId != null && !senderId.isBlank()) {
      sql.append(" AND m.sender_id = ?");
      params.add(senderId.trim());
    }
    if (msgType != null && !msgType.isBlank()) {
      sql.append(" AND m.msg_type = ?");
      params.add(msgType.trim());
    }
    if (since > 0) {
      sql.append(" AND m.created_at >= ?");
      params.add(since);
    }
  }

  /**
   * 订阅范围：{@code scope} 为 null 表示不过滤；空集合表示开了过滤但没接入任何频道，一条都不给；
   * 非空则只回这些频道的消息。空集合不能拼成 {@code IN ()}（MySQL 语法错），所以用常量假值替代。
   */
  private static void appendScope(StringBuilder sql, List<Object> params,
      java.util.Collection<String> scope) {
    if (scope == null) {
      return;
    }
    if (scope.isEmpty()) {
      sql.append(" AND 1 = 0");
      return;
    }
    sql.append(" AND m.channel_no IN (");
    for (int i = 0; i < scope.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append("?");
    }
    sql.append(")");
    params.addAll(scope);
  }

  // ---------- web admin (cross-space, read-only) ----------

  /**
   * 管理台跨空间消息搜索：不绑定 group_id，只按 LIKE 命中 title/summary/body。
   * 通配符在 {@link #adminLikePattern} 里转义，channel_no / msg_type / since 全部参数绑定，
   * 与 {@link #searchLike} 同一套安全口径；只是去掉了 group 边界并额外带出频道名。
   */
  public List<Row> searchAdmin(String rawQuery, String channelNo, String msgType, long since,
      int limit) {
    String pattern = adminLikePattern(rawQuery.trim());
    StringBuilder sql = new StringBuilder("""
        SELECT m.seq, m.msg_id, m.channel_no, m.group_id, m.sender_id, m.msg_type, m.title,
               m.body, m.summary, m.level, m.media_status, m.created_at,
               s.display_name AS sender_name, c.name AS channel_name
        FROM messages m
        LEFT JOIN senders s ON s.channel_no = m.channel_no AND s.sender_id = m.sender_id
        LEFT JOIN channels c ON c.channel_no = m.channel_no
        WHERE m.deleted_at IS NULL
          AND (m.body LIKE ? OR m.title LIKE ? OR m.summary LIKE ?)""");
    List<Object> params = new ArrayList<>(List.of(pattern, pattern, pattern));
    if (channelNo != null && !channelNo.isBlank()) {
      sql.append(" AND m.channel_no = ?");
      params.add(channelNo.trim());
    }
    if (msgType != null && !msgType.isBlank()) {
      sql.append(" AND m.msg_type = ?");
      params.add(msgType.trim());
    }
    if (since > 0) {
      sql.append(" AND m.created_at >= ?");
      params.add(since);
    }
    sql.append(" ORDER BY m.seq DESC LIMIT ?");
    params.add(limit);
    return Db.selectListBySql(sql.toString(), params.toArray());
  }

  /** 管理台概览计数：今日零点之后的消息单独统计。 */
  public Map<String, Object> adminOverview(long todayStartMs) {
    Row r = Db.selectOneBySql("""
        SELECT
          (SELECT COUNT(*) FROM channels) AS total_channels,
          (SELECT COUNT(*) FROM channels WHERE archived_at IS NULL) AS active_channels,
          (SELECT COUNT(*) FROM devices) AS total_devices,
          (SELECT COUNT(*) FROM devices WHERE revoked_at IS NULL) AS active_devices,
          (SELECT COUNT(*) FROM messages WHERE deleted_at IS NULL) AS live_messages,
          (SELECT COUNT(*) FROM messages WHERE deleted_at IS NULL AND created_at >= ?) AS messages_today
        """, todayStartMs);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("total_channels", longOrZero(r, "total_channels"));
    out.put("active_channels", longOrZero(r, "active_channels"));
    out.put("total_devices", longOrZero(r, "total_devices"));
    out.put("active_devices", longOrZero(r, "active_devices"));
    out.put("live_messages", longOrZero(r, "live_messages"));
    out.put("messages_today", longOrZero(r, "messages_today"));
    return out;
  }

  /** 近 N 天每日消息量趋势，按日期升序；day 为 yyyy-MM-dd 字符串。 */
  public List<Map<String, Object>> messagesByDaySince(long startMs) {
    List<Row> rows = Db.selectListBySql("""
        SELECT DATE(FROM_UNIXTIME(created_at / 1000)) AS day, COUNT(*) AS cnt
        FROM messages
        WHERE deleted_at IS NULL AND created_at >= ?
        GROUP BY day ORDER BY day
        """, startMs);
    List<Map<String, Object>> out = new ArrayList<>();
    for (Row row : rows) {
      Map<String, Object> item = new LinkedHashMap<>();
      Object day = row.get("day");
      item.put("day", day == null ? null : String.valueOf(day));
      item.put("count", longOrZero(row, "cnt"));
      out.add(item);
    }
    return out;
  }

  private static long longOrZero(Row row, String col) {
    if (row == null) {
      return 0L;
    }
    Long v = row.getLong(col);
    return v == null ? 0L : v;
  }

  /** LIKE 的三个通配符都得转义，否则管理台输入一个 % 就是"匹配一切"。 */
  private static String adminLikePattern(String query) {
    StringBuilder sb = new StringBuilder(query.length() + 3);
    sb.append('%');
    for (int i = 0; i < query.length(); i++) {
      char c = query.charAt(i);
      if (c == '\\' || c == '%' || c == '_') {
        sb.append('\\');
      }
      sb.append(c);
    }
    return sb.append('%').toString();
  }

  public long latestSeq() {
    Row row = Db.selectOneBySql("SELECT COALESCE(MAX(seq), 0) AS seq FROM messages");
    return row == null ? 0L : row.getLong("seq");
  }
  /**
   * 本 Space 内已落库的最大 seq。seq 是全局单调的，所以补拉游标用全局值没问题，
   * 但<b>"这台设备落后多少条"只能跟自己组的行高比</b>：拿全局 MAX(seq) 比一个刚建好、
   * 一条消息都没有的 Space，游标恒为 0，别的 Space 推得多就会把它顶过 pb.ws.gap-threshold，
   * 于是每次连接都回 resume_gap、永远等不到 resume_ok——客户端的顶栏就停在"连接中"，
   * 而这条连接其实活得好好的。REST 那一侧的 latest_seq 仍用全局值（那是 after_seq 游标要的）。
   */
  public long latestSeq(String groupId) {
    Row row = Db.selectOneBySql("SELECT COALESCE(MAX(seq), 0) AS seq FROM messages WHERE group_id = ?",
        groupId);
    return row == null ? 0L : row.getLong("seq");
  }

  public int tombstoneMessage(String msgId, String groupId, long now) {
    return Db.updateBySql("UPDATE messages SET deleted_at = ? WHERE msg_id = ? AND group_id = ?"
        + " AND deleted_at IS NULL", now, msgId, groupId);
  }

  /** §5.2 清空整会话：一条 UPDATE 标完整个会话，不做逐条删除（那是几百次往返）。 */
  public int tombstoneConversation(String groupId, String channelNo, String senderId, long now) {
    return Db.updateBySql("""
        UPDATE messages SET deleted_at = ?
        WHERE group_id = ? AND channel_no = ? AND sender_id = ? AND deleted_at IS NULL""",
        now, groupId, channelNo, senderId);
  }

  /** 清空整个频道：一条 UPDATE 标完该频道全部消息，逐会话删除同样会打爆往返与帧流。 */
  public int tombstoneChannel(String groupId, String channelNo, long now) {
    return Db.updateBySql("""
        UPDATE messages SET deleted_at = ?
        WHERE group_id = ? AND channel_no = ? AND deleted_at IS NULL""",
        now, groupId, channelNo);
  }

  public void updateMediaStatus(String msgId, String mediaStatus, String mediaKeysJson) {
    Db.updateBySql("UPDATE messages SET media_status = ?, media_keys = ? WHERE msg_id = ?",
        mediaStatus, mediaKeysJson, msgId);
  }

  // ---------- media_objects ----------

  public void insertMedia(String msgId, int idx, String sourceUrl, String channelNo, String groupId,
      long now) {
    Db.insertBySql("""
        INSERT INTO media_objects (msg_id, idx, source_url, status, channel_no, group_id, created_at)
        VALUES (?,?,?,'pending',?,?,?)""", msgId, idx, sourceUrl, channelNo, groupId, now);
  }

  public List<Row> listMedia(String msgId) {
    return Db.selectListBySql("""
        SELECT idx, status, object_key, thumb_key, mime, width, height, size, sha256, error,
               fetch_attempts, ready_at, deleted_at
        FROM media_objects WHERE msg_id = ? ORDER BY idx""", msgId);
  }

  /** 抓取队列的输入：只有还没到终态的行需要出去拉，group_id 用来做同组 sha256 去重。 */
  public List<Row> pendingMedia(String msgId) {
    return Db.selectListBySql("""
        SELECT idx, source_url, group_id, channel_no FROM media_objects
        WHERE msg_id = ? AND status = 'pending' ORDER BY idx""", msgId);
  }

  /** 媒体读接口（§5.2）一次取回槽位与消息的删除标记，归属校验才能只看一次查询的结果。 */
  public Row mediaSlotForRead(String msgId, int idx) {
    return Db.selectOneBySql("""
        SELECT mo.idx, mo.status, mo.object_key, mo.thumb_key, mo.mime, mo.group_id,
               mo.enc_mode, mo.sse_key_wrapped, m.deleted_at AS msg_deleted_at
        FROM media_objects mo
        JOIN messages m ON m.msg_id = mo.msg_id
        WHERE mo.msg_id = ? AND mo.idx = ?""", msgId, idx);
  }

  /** §4.5 第 4 步：同一 group 已经存过同一张图（sha256 相同）就复用旧对象键，不再重复转存。 */
  /**
   * 同组去重命中时要把加密形态一起带走：复用意味着新槽位指向**同一个对象键**，
   * 那把客户钥自然也同一份，只复制对象键会让读侧拿明文钥去开密文对象。
   */
  public Row reusableMedia(String groupId, String sha256) {
    return Db.selectOneBySql("""
        SELECT object_key, thumb_key, mime, width, height, size, enc_mode, sse_key_wrapped
        FROM media_objects
        WHERE group_id = ? AND sha256 = ? AND status = 'ready' AND object_key IS NOT NULL
        ORDER BY id DESC LIMIT 1""", groupId, sha256);
  }

  public void markMediaReady(String msgId, int idx, String objectKey, String thumbKey, String mime,
      Integer width, Integer height, long size, String sha256, String encMode, byte[] sseKeyWrapped,
      long now) {
    Db.updateBySql("""
        UPDATE media_objects SET status='ready', object_key=?, thumb_key=?, mime=?, width=?, height=?,
               size=?, sha256=?, enc_mode=?, sse_key_wrapped=?, error=NULL, ready_at=?
        WHERE msg_id=? AND idx=?""",
        objectKey, thumbKey, mime, width, height, size, sha256, encMode, sseKeyWrapped, now, msgId,
        idx);
  }

  /**
   * 抓取失败记账：状态当场就是终态，因为正文绝不能因为抓不到图而不发（§4.5）。
   * fetch_attempts 是重试的账本，MediaRetryService 靠它决定还要不要再抓。
   */
  public void markMediaFailed(String msgId, int idx, String error, long now) {
    Db.updateBySql("""
        UPDATE media_objects SET status='failed', error=?, fetch_attempts=fetch_attempts+1
        WHERE msg_id=? AND idx=?""", clampError(error), msgId, idx);
  }

  /**
   * 重试 sweep 的输入（§4.5 的 idx_media_status 就是为它建的）：还没抓够次数、进程重启留下的
   * pending 僵尸，两种都要捞回来。bad_type/too_large 是内容级终态（字节已经拿到且明确不可用），
   * 重试一百次也不会变好，所以直接排除。按消息聚合，一轮一条消息最多抓一次。
   */
  public List<Row> mediaRetryCandidates(long pendingStaleBefore, int retryMax, int limit) {
    return Db.selectListBySql("""
        SELECT mo.msg_id, mo.group_id, m.seq
        FROM media_objects mo JOIN messages m ON m.msg_id = mo.msg_id
        WHERE mo.deleted_at IS NULL AND m.deleted_at IS NULL
          AND ((mo.status='failed' AND mo.fetch_attempts < ?
                AND (mo.error IS NULL OR mo.error NOT IN ('bad_type','too_large')))
            OR (mo.status='pending' AND mo.created_at <= ?))
        GROUP BY mo.msg_id, mo.group_id, m.seq
        ORDER BY MIN(mo.created_at) LIMIT ?""", retryMax, pendingStaleBefore, limit);
  }

  /**
   * 把还能重试的失败槽位放回待抓队列，好让抓取器原样再跑一遍（pendingMedia 只认 pending）。
   * fetch_attempts 不清零，error 抹掉是避免上一轮的失败原因被当成这一轮的结论。
   */
  public int resetMediaForRetry(String msgId, int retryMax) {
    return Db.updateBySql("""
        UPDATE media_objects SET status='pending', error=NULL
        WHERE msg_id=? AND status='failed' AND fetch_attempts < ? AND deleted_at IS NULL
          AND (error IS NULL OR error NOT IN ('bad_type','too_large'))""", msgId, retryMax);
  }

  /** D12：删除消息只记账，对象要等 §5.2 的宽限期到了才真删，所以这里绝不动 object_key。 */
  public int markMediaDeleted(String msgId, long now) {
    return Db.updateBySql("""
        UPDATE media_objects SET status='deleted', deleted_at=?
        WHERE msg_id = ? AND deleted_at IS NULL""", now, msgId);
  }

  /** 会话级删除的媒体记账，规则与单条 markMediaDeleted 一致，只是范围换成整个会话。 */
  public int markMediaDeletedConversation(String groupId, String channelNo, String senderId,
      long now) {
    return Db.updateBySql("""
        UPDATE media_objects mo JOIN messages m ON m.msg_id = mo.msg_id
        SET mo.status='deleted', mo.deleted_at=?
        WHERE m.group_id=? AND m.channel_no=? AND m.sender_id=? AND mo.deleted_at IS NULL""",
        now, groupId, channelNo, senderId);
  }

  /** 频道级清空：把该频道全部消息的图片一并标待删，真删交给 MediaPurgeService 过宽限期后执行。 */
  public int markMediaDeletedChannel(String groupId, String channelNo, long now) {
    return Db.updateBySql("""
        UPDATE media_objects mo JOIN messages m ON m.msg_id = mo.msg_id
        SET mo.status='deleted', mo.deleted_at=?
        WHERE m.group_id=? AND m.channel_no=? AND mo.deleted_at IS NULL""",
        now, groupId, channelNo);
  }

  /** 巡检的输入：已过宽限期、且确实有字节躺在桶里的行（失败槽位没有对象，不必反复扫）。 */
  public List<Row> mediaDueForPurge(long notAfter, int limit) {
    return Db.selectListBySql("""
        SELECT id, msg_id, idx, object_key, thumb_key FROM media_objects
        WHERE deleted_at IS NOT NULL AND deleted_at <= ? AND status <> 'purged'
          AND object_key IS NOT NULL
        ORDER BY deleted_at LIMIT ?""", notAfter, limit);
  }

  /**
   * 对象已经真删，客户钥也就没用了：连它一起抹掉，库里不再留着打不开任何东西的密钥材料
   * （宽限期内的行仍然带钥可读，所以那一段时间里密钥必须留着）。
   */
  public void markMediaPurged(long id) {
    Db.updateBySql("UPDATE media_objects SET status='purged', enc_mode='none', sse_key_wrapped=NULL"
        + " WHERE id=?", id);
  }

  private static String clampError(String error) {
    if (error == null) {
      return "unknown";
    }
    return error.length() <= 255 ? error : error.substring(0, 255);
  }

  // ---------- read_state ----------

  public void upsertReadState(String groupId, String channelNo, String senderId, long lastReadSeq,
      String deviceId, long now) {
    Db.updateBySql("""
        INSERT INTO read_state (group_id, channel_no, sender_id, last_read_seq, updated_at, device_id)
        VALUES (?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE last_read_seq = VALUES(last_read_seq), updated_at = VALUES(updated_at),
          device_id = VALUES(device_id)""",
        groupId, channelNo, senderId, lastReadSeq, now, deviceId);
  }

  public List<Row> listReadStates(String groupId) {
    return Db.selectListBySql("""
        SELECT channel_no, sender_id, last_read_seq, updated_at FROM read_state WHERE group_id = ?""",
        groupId);
  }

  // ---------- app_state（服务端身份密钥落库） ----------

  public String getState(String key) {
    Row row = Db.selectOneBySql("SELECT state_value FROM app_state WHERE state_key = ?", key);
    return row == null ? null : row.getString("state_value");
  }

  public void putState(String key, String value, long now) {
    Db.updateBySql("""
        INSERT INTO app_state (state_key, state_value, updated_at) VALUES (?,?,?)
        ON DUPLICATE KEY UPDATE state_value = VALUES(state_value), updated_at = VALUES(updated_at)""",
        key, value, now);
  }

  /**
   * 只在键不存在时写入。多实例同时首启时用它抢同一个 KDF 参数：后到的实例不覆盖先到的盐，
   * 而是回读赢家那份参数再派生——否则两个实例会各持一把 KEK。
   */
  public boolean putStateIfAbsent(String key, String value, long now) {
    return Db.updateBySql("""
        INSERT IGNORE INTO app_state (state_key, state_value, updated_at) VALUES (?,?,?)""",
        key, value, now) > 0;
  }

  /** 库里是否已有任何 sse-c 封装钥：切换主钥来源前必须问这一句。 */
  public boolean hasAnyWrappedKey() {
    Row media = Db.selectOneBySql(
        "SELECT 1 AS present FROM media_objects WHERE sse_key_wrapped IS NOT NULL LIMIT 1");
    if (media != null) {
      return true;
    }
    Row sender = Db.selectOneBySql(
        "SELECT 1 AS present FROM senders WHERE sse_key_wrapped IS NOT NULL LIMIT 1");
    return sender != null;
  }

  // ---------- §14 abuse control: sender allowlist, daily counters, capacity ----------

  /** 检查发送者是否在白名单里（频道开启白名单模式时才查）。 */
  public boolean isSenderAllowed(String channelNo, String senderId) {
    Row row = Db.selectOneBySql(
        "SELECT 1 FROM sender_allowlist WHERE channel_no=? AND sender_id=? AND enabled=1",
        channelNo, senderId);
    return row != null;
  }

  /** 添加/移除发送者白名单。停用不是删除：记录要留着（enabled=0），否则"先停用再启用"查不到历史。 */
  public void upsertSenderAllowlist(String channelNo, String senderId, boolean enabled, long now) {
    // 停用不能走 UPDATE：发送者第一次出现就被停用时表里根本没有这条记录，UPDATE 影响 0 行，
    // "停用只是 enabled=0、记录还在列表里"这个口径就破了。统一走 INSERT ... ON DUPLICATE。
    Db.updateBySql("""
        INSERT INTO sender_allowlist (channel_no, sender_id, enabled, created_at)
        VALUES (?,?,?,?)
        ON DUPLICATE KEY UPDATE enabled=VALUES(enabled)""",
        channelNo, senderId, enabled ? 1 : 0, now);
  }

  /** 获取频道的白名单发送者列表。 */
  public List<Row> listSenderAllowlist(String channelNo) {
    return Db.selectListBySql(
        "SELECT sender_id, enabled FROM sender_allowlist WHERE channel_no=? ORDER BY created_at",
        channelNo);
  }

  /** 递增日计数器：消息数、图片数、字节数。{@code day} 用 CURDATE()，与 {@link #getTodayCounter} 同一口径。 */
  public void incrementDailyCounter(String channelNo, int msgImages, int contentBytes, long now) {
    Db.updateBySql("""
        INSERT INTO daily_counters (channel_no, day, msg_count, image_count, total_bytes, updated_at)
        VALUES (?,CURDATE(),1,?,?,?)
        ON DUPLICATE KEY UPDATE
          msg_count = msg_count + 1,
          image_count = image_count + VALUES(image_count),
          total_bytes = total_bytes + VALUES(total_bytes),
          updated_at = VALUES(updated_at)""", channelNo, msgImages, contentBytes, now);
  }

  /** 查询今日已用配额。 */
  public Row getTodayCounter(String channelNo) {
    return Db.selectOneBySql(
        "SELECT msg_count, image_count, total_bytes FROM daily_counters WHERE channel_no=? AND day=CURDATE()",
        channelNo);
  }

  /**
   * 容量报告：按**频道**统计消息总数、总字节数、最早消息时间、图片总数。
   * 图片子查询必须同时过 {@code m.deleted_at}：只过滤 {@code mo.deleted_at} 会让已删消息的图
   * 继续计数，而消息数已经把它们排除了，两个口径放在同一张报表里就是自相矛盾。
   */
  public Row capacityReport(String channelNo) {
    return Db.selectOneBySql("""
        SELECT
          COUNT(*) AS total_messages,
          COALESCE(SUM(LENGTH(body)), 0) AS total_text_bytes,
          MIN(created_at) AS earliest_msg_at,
          (SELECT COUNT(*) FROM media_objects mo JOIN messages m ON m.msg_id=mo.msg_id
           WHERE m.channel_no=? AND m.deleted_at IS NULL AND mo.deleted_at IS NULL) AS total_images,
          (SELECT COALESCE(SUM(mo.size), 0) FROM media_objects mo JOIN messages m ON m.msg_id=mo.msg_id
           WHERE m.channel_no=? AND m.deleted_at IS NULL AND mo.deleted_at IS NULL) AS total_image_bytes
        FROM messages WHERE channel_no=? AND deleted_at IS NULL""", channelNo, channelNo, channelNo);
  }

  /**
   * §14 日计数器只按天新增、从不回收，跑一年就是一张只涨不涨用的表。留 {@code keepDays} 天：
   * 配额判断只看当天（{@link #getTodayCounter}），历史行的价值只在排障，超过排障窗口就可以清。
   */
  public int deleteStaleDailyCounters(int keepDays) {
    return Db.updateBySql(
        "DELETE FROM daily_counters WHERE day < DATE_SUB(CURDATE(), INTERVAL ? DAY)", keepDays);
  }

  // ---------- scheduled push tasks ----------

  public void insertScheduledTask(Row task) {
    Db.insertBySql("""
        INSERT INTO scheduled_tasks (task_id, group_id, channel_no, name, category, title, content,
                                    level, sender_name, images_json, cron_expr, timezone, enabled,
                                    next_fire_at, created_at, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        task.getString("task_id"), task.getString("group_id"), task.getString("channel_no"),
        task.getString("name"), task.getString("category"), task.getString("title"),
        task.getString("content"),
        task.getString("level"), task.getString("sender_name"), task.getString("images_json"),
        task.getString("cron_expr"), task.getString("timezone"), task.getInt("enabled"),
        task.getLong("next_fire_at"),
        task.getLong("created_at"), task.getLong("updated_at"));
  }

  /** 编辑任务：可改的载荷与调度字段整体覆盖，执行结果列（last_*）保留。 */
  public int updateScheduledTask(Row task) {
    return Db.updateBySql("""
        UPDATE scheduled_tasks SET channel_no = ?, name = ?, category = ?, title = ?, content = ?,
               level = ?, sender_name = ?, images_json = ?, cron_expr = ?, timezone = ?,
               enabled = ?, next_fire_at = ?, updated_at = ?
        WHERE task_id = ?""",
        task.getString("channel_no"), task.getString("name"), task.getString("category"),
        task.getString("title"),
        task.getString("content"), task.getString("level"), task.getString("sender_name"),
        task.getString("images_json"), task.getString("cron_expr"), task.getString("timezone"),
        task.getInt("enabled"),
        task.getLong("next_fire_at"), task.getLong("updated_at"), task.getString("task_id"));
  }

  public Row findScheduledTask(String taskId) {
    return Db.selectOneBySql("SELECT * FROM scheduled_tasks WHERE task_id = ?", taskId);
  }

  /** Web 管理台：跨 Space 列出全部定时任务，带上频道名供列表直接展示。 */
  public List<Row> listScheduledTasks() {
    return Db.selectListBySql("""
        SELECT t.*, c.name AS channel_name
        FROM scheduled_tasks t LEFT JOIN channels c ON c.channel_no = t.channel_no
        ORDER BY t.created_at DESC""");
  }

  public int deleteScheduledTask(String taskId) {
    return Db.updateBySql("DELETE FROM scheduled_tasks WHERE task_id = ?", taskId);
  }

  /** 启停只动这三列：载荷与 last_* 执行结果都保持原样。 */
  public int setScheduledTaskEnabled(String taskId, int enabled, long nextFireAt, long now) {
    return Db.updateBySql(
        "UPDATE scheduled_tasks SET enabled = ?, next_fire_at = ?, updated_at = ? WHERE task_id = ?",
        enabled, nextFireAt, now, taskId);
  }

  /**
   * 到期任务。只读不抢：抢占由 {@link #claimScheduledTaskSlot} 用 next_fire_at 的旧值做条件更新完成，
   * 两个实例同时扫到同一行也只有一个能改成功，因此不需要 SELECT ... FOR UPDATE 占着连接。
   */
  public List<Row> dueScheduledTasks(long now, int limit) {
    return Db.selectListBySql("""
        SELECT * FROM scheduled_tasks
        WHERE enabled = 1 AND next_fire_at <= ? ORDER BY next_fire_at LIMIT ?""", now, limit);
  }

  /** 抢占一个到期时隙：CAS 成功（返回 1）才允许触发。 */
  public int claimScheduledTaskSlot(String taskId, long expectedNextFireAt, long nextFireAt,
      long now) {
    return Db.updateBySql("""
        UPDATE scheduled_tasks SET next_fire_at = ?, last_fire_at = ?, updated_at = ?
        WHERE task_id = ? AND next_fire_at = ? AND enabled = 1""",
        nextFireAt, expectedNextFireAt, now, taskId, expectedNextFireAt);
  }

  /** 记回本次触发的结论；{@code enabled} 一并回写，用于"频道已归档就自动停用"这类止损。 */
  public void recordScheduledTaskRun(String taskId, String result, String error, String msgId,
      int enabled, long now) {
    Db.updateBySql("""
        UPDATE scheduled_tasks SET last_result = ?, last_error = ?, last_msg_id = ?, enabled = ?,
               updated_at = ?
        WHERE task_id = ?""", result, error, msgId, enabled, now, taskId);
  }
}
