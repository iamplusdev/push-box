package cn.iamplus.pushbox.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * §3.3 客户端本地表（P0 只落文字链路需要的三张：messages / conversations / sync_state）。
 * body 是本机明文副本 —— 服务端本来就明文归档，本地不再承担保密职责（§3.3）。
 */
@Entity(tableName = "messages", primaryKeys = ["msg_id"],
    indices = [Index("seq"), Index(value = ["channel_no", "sender_id"])])
data class MessageRow(
    @ColumnInfo(name = "msg_id") val msgId: String,
    val seq: Long,
    @ColumnInfo(name = "channel_no") val channelNo: String,
    @ColumnInfo(name = "sender_id") val senderId: String,
    @ColumnInfo(name = "msg_type") val msgType: String,
    val title: String?,
    val summary: String?,
    val body: String?,
    val level: String,
    @ColumnInfo(name = "link_url") val linkUrl: String?,
    @ColumnInfo(name = "media_status") val mediaStatus: String?,
    @ColumnInfo(name = "media_count") val mediaCount: Int = 0,
    /**
     * §4.5 每个槽位各自的终态（`idx:status` 逗号串）。消息级 media_status 只能说"这条有图坏了"，
     * 九宫格就得把三格里的好图也一起标成失效；有了这一列，坏的那格才单独灰。
     */
    @ColumnInfo(name = "media_slots") val mediaSlots: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "read_state") val readState: Int = 0,
    @ColumnInfo(name = "sender_avatar") val senderAvatar: String? = null,
    /**
     * §5.3：实时帧被 frame-max-bytes 削过正文。为 1 时 body 只是前缀，
     * 详情页要显示"展开全文"并按 msg_id 回捞服务端全文。
     */
    @ColumnInfo(name = "truncated") val truncated: Boolean = false,
) {
    /** 第 idx 槽的终态。库里没有这一项返回 null，调用方按"未知"处理、照常尝试取图。 */
    fun slotStatus(idx: Int): String? = parseSlotBlob(mediaSlots)[idx]
}

/** 解析 `idx:status` 逗号串。畸形项（无冒号、槽号非数字）直接跳过，不让一列脏数据毁掉整条消息。 */
internal fun parseSlotBlob(blob: String?): Map<Int, String> {
    if (blob.isNullOrEmpty()) return emptyMap()
    val out = LinkedHashMap<Int, String>()
    for (part in blob.split(',')) {
        val at = part.indexOf(':')
        if (at <= 0) continue
        val idx = part.substring(0, at).toIntOrNull() ?: continue
        out[idx] = part.substring(at + 1)
    }
    return out
}

/** 合并两份槽位串：新帧覆盖同槽旧值，其余原样留着——补发帧只带本次到终态的那几槽。 */
internal fun mergeSlotBlob(old: String?, added: String?): String? {
    val merged = LinkedHashMap(parseSlotBlob(old))
    merged.putAll(parseSlotBlob(added))
    if (merged.isEmpty()) return null
    return merged.entries.joinToString(",") { "${it.key}:${it.value}" }
}

/** 一个 (channel_no, sender_id) 就是一条会话线（§7.3：一个软件一条会话）。 */
@Entity(tableName = "conversations", primaryKeys = ["channel_no", "sender_id"])
data class ConversationRow(
    @ColumnInfo(name = "channel_no") val channelNo: String,
    @ColumnInfo(name = "sender_id") val senderId: String,
    val name: String,
    @ColumnInfo(name = "custom_name") val customName: String? = null,
    @ColumnInfo(name = "avatar_path") val avatarPath: String? = null,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    /** §7.3 会话级锁屏可见：为 true 时该会话的通知不带内容且不显示在锁屏，比全局静音更细。 */
    @ColumnInfo(name = "lock_private") val lockPrivate: Boolean = false,
    @ColumnInfo(name = "unread_count") val unreadCount: Int = 0,
    @ColumnInfo(name = "last_seq") val lastSeq: Long = 0,
    @ColumnInfo(name = "last_msg_at") val lastMsgAt: Long = 0,
    @ColumnInfo(name = "last_summary_cached") val lastSummary: String? = null,
) {
    /** 列表显示名：本地重命名优先，回落到服务端下发的 sender 名（§7.3 会话改名）。 */
    val displayName: String get() = customName?.takeIf { it.isNotBlank() } ?: name
}

/** 游标只有一行；重连与 WorkManager 兜底都拿它当 `since_seq`（§7.2）。 */
@Entity(tableName = "sync_state")
data class SyncRow(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "last_applied_seq") val lastAppliedSeq: Long = 0,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long = 0,
)

/** §7.4 清已读图片缓存用的投影行：只取 msg_id + media_count 两列，不还原整条消息。 */
data class ReadMediaRow(val msgId: String, val mediaCount: Int)

@Dao
interface PushBoxDao {

    @Query("SELECT * FROM conversations ORDER BY pinned DESC, last_msg_at DESC")
    fun conversations(): Flow<List<ConversationRow>>

    @Query("SELECT * FROM messages WHERE channel_no = :channelNo AND sender_id = :senderId " +
        "ORDER BY seq DESC LIMIT :limit")
    suspend fun messages(channelNo: String, senderId: String, limit: Int): List<MessageRow>

    /**
     * 会话详情页：按时间正序流式读取，新消息实时上屏。
     * §7.4 D17：readState 为 null 看全部、0 只看未读、1 只看已读，切换不改动底层数据。
     * [q] 非空即"在会话中搜索"：只留正文/摘要/标题命中关键词的那几条，与 readState 是两条正交的轴。
     * 
     * 复合排序：先按 created_at 再按 seq，确保相同时间戳的消息严格按 seq 升序显示。
     * 断网重连后补拉的消息虽然时间戳相同，但 seq 单调递增，此排序可保证正确顺序。
     */
    @Query("SELECT * FROM messages WHERE channel_no = :channelNo AND sender_id = :senderId " +
        "AND (:readState IS NULL OR read_state = :readState) " +
        "AND (:q IS NULL OR (body LIKE '%'||:q||'%' OR summary LIKE '%'||:q||'%' " +
        "OR title LIKE '%'||:q||'%')) ORDER BY created_at ASC, seq ASC")
    fun messagesFlow(channelNo: String, senderId: String,
        readState: Int?, q: String?): Flow<List<MessageRow>>

    @Query("SELECT last_applied_seq FROM sync_state WHERE id = 1")
    suspend fun lastAppliedSeq(): Long?

    /** §7.4 本地即时搜索：先命中缓存消息正文/摘要/标题，服务端 /search 到位前离线可用。
     * 搜索结果按时间倒序（最新优先），相同时间再按 seq 倒序。 */
    @Query("SELECT * FROM messages WHERE body LIKE '%'||:q||'%' OR summary LIKE '%'||:q||'%' " +
        "OR title LIKE '%'||:q||'%' ORDER BY created_at DESC, seq DESC LIMIT 100")
    suspend fun searchMessages(q: String): List<MessageRow>

    @Query("SELECT * FROM conversations WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun conversation(channelNo: String, senderId: String): ConversationRow?

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun messageCount(): Int

    /** §7.3 数据导出为 JSON：按 seq 正序取全部本地消息。 */
    @Query("SELECT * FROM messages ORDER BY seq ASC")
    suspend fun allMessages(): List<MessageRow>

    /**
     * §7.3 D-角标：桌面图标的未读总数。用 Flow 让 App 侧持续镜像刷新。
     * 免打扰会话不计入：列表里那条会话照旧显示红点（用户随时点得进去），
     * 但桌面角标是"有东西等你处理"的全局信号，把静音频道算进去就等于让用户自己关的
     * 免打扰又把他叫回来——微信与系统通知栏的口径都是静音不进角标。
     */
    @Query("SELECT COALESCE(SUM(unread_count), 0) FROM conversations WHERE muted = 0")
    fun totalUnread(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageRow): Long

    @Query("INSERT OR IGNORE INTO sync_state (id, last_applied_seq, last_sync_at) VALUES (1, 0, 0)")
    suspend fun ensureCursor()

    @Query("UPDATE sync_state SET last_applied_seq = :seq, last_sync_at = :now WHERE id = 1")
    suspend fun updateCursor(seq: Long, now: Long)

    @Query("""
        INSERT INTO conversations (channel_no, sender_id, name, pinned, muted, lock_private,
                                   unread_count, last_seq, last_msg_at, last_summary_cached)
        VALUES (:channelNo, :senderId, :name, 0, 0, 0, 1, :seq, :createdAt, :summary)
        ON CONFLICT(channel_no, sender_id) DO UPDATE SET
          name = excluded.name,
          unread_count = unread_count + 1,
          last_seq = MAX(last_seq, excluded.last_seq),
          last_msg_at = excluded.last_msg_at,
          last_summary_cached = excluded.last_summary_cached
    """)
    suspend fun bumpConversation(channelNo: String, senderId: String, name: String, seq: Long,
        createdAt: Long, summary: String?)

    @Query("UPDATE conversations SET unread_count = 0 WHERE channel_no = :channelNo " +
        "AND sender_id = :senderId")
    suspend fun clearConversationBadge(channelNo: String, senderId: String)

    @Query("UPDATE messages SET read_state = 1 WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun markMessagesRead(channelNo: String, senderId: String)

    /**
     * 置已读要同时清会话红点并落每条的 read_state：
     * 删除消息后重算未读数依赖 read_state，二者不同步会让红点在 tombstone 后凭空复现。
     */
    @Transaction
    suspend fun markConversationRead(channelNo: String, senderId: String) {
        clearConversationBadge(channelNo, senderId)
        markMessagesRead(channelNo, senderId)
    }

    /** §7.4 数据：清已读图片缓存 —— 列出所有已读且带图的消息，供 ImageCache 按 key 精确删除磁盘/内存条目。 */
    @Query("SELECT msg_id AS msgId, media_count AS mediaCount FROM messages " +
        "WHERE read_state = 1 AND media_count > 0")
    suspend fun readMessagesWithMedia(): List<ReadMediaRow>

    @Query("SELECT * FROM messages WHERE msg_id = :msgId")
    suspend fun messageById(msgId: String): MessageRow?

    /**
     * §4.5 media_ready / media_failed 回填：更新整条的媒体终态、槽数与每槽状态。
     * 槽数取 MAX 而不是直接覆盖：补发帧只带本次到终态的那几槽，直接写就会把
     * "三张图"缩成后到那一帧的槽数，九宫格少一格。槽位状态同理只能合并不能替换。
     */
    @Query("UPDATE messages SET media_status = :status, media_count = MAX(media_count, :count), " +
        "media_slots = :slots WHERE msg_id = :msgId")
    suspend fun updateMedia(msgId: String, status: String, count: Int, slots: String?)

    /** 先读旧行再写：Room 的 @Query 没法在 UPDATE 里做字符串合并。命中 0 行即消息尚未入库，忽略。 */
    @Transaction
    suspend fun setMedia(msgId: String, status: String, count: Int, slots: String?) {
        val row = messageById(msgId) ?: return
        updateMedia(msgId, status, count, mergeSlotBlob(row.mediaSlots, slots))
    }

    /** §5.3 全文回捞成功后落库：换回未截断正文并清掉 truncated，"展开全文"按钮随即消失。 */
    @Query("UPDATE messages SET body = :body, truncated = 0 WHERE msg_id = :msgId")
    suspend fun setFulltext(msgId: String, body: String)

    @Query("SELECT COUNT(*) FROM messages WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun messagesRemaining(channelNo: String, senderId: String): Int

    @Query("DELETE FROM messages WHERE msg_id = :msgId")
    suspend fun deleteMessage(msgId: String)

    @Query("DELETE FROM messages WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun deleteConversationMessages(channelNo: String, senderId: String)

    @Query("DELETE FROM conversations WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun deleteConversation(channelNo: String, senderId: String)

    @Query("DELETE FROM messages WHERE channel_no = :channelNo")
    suspend fun deleteChannelMessages(channelNo: String)

    @Query("DELETE FROM conversations WHERE channel_no = :channelNo")
    suspend fun deleteChannelConversations(channelNo: String)

    /** 删掉一条后按存活的行重算会话聚合，保持红点数/末条时间与真实消息一致。 */
    @Query("""
        UPDATE conversations SET
          unread_count = (SELECT COUNT(*) FROM messages WHERE channel_no = :channelNo
                          AND sender_id = :senderId AND read_state = 0),
          last_seq = (SELECT COALESCE(MAX(seq), 0) FROM messages WHERE channel_no = :channelNo
                      AND sender_id = :senderId),
          last_msg_at = (SELECT COALESCE(MAX(created_at), 0) FROM messages WHERE channel_no = :channelNo
                         AND sender_id = :senderId)
        WHERE channel_no = :channelNo AND sender_id = :senderId
    """)
    suspend fun recomputeConversation(channelNo: String, senderId: String)

    /** §5.3 tombstone（单条）：删行 → 会话空了删会话，否则重算聚合。返回被删行，供调用方精确驱逐图片缓存。 */
    @Transaction
    suspend fun tombstoneMessage(msgId: String): MessageRow? {
        val row = messageById(msgId) ?: return null
        deleteMessage(msgId)
        if (messagesRemaining(row.channelNo, row.senderId) == 0) {
            deleteConversation(row.channelNo, row.senderId)
        } else {
            recomputeConversation(row.channelNo, row.senderId)
        }
        return row
    }

    /**
     * 清空作用域前先把带图的行取出来：删完行就再也查不到 msg_id/槽数，
     * 那些字节会永远留在 cacheDir（清已读缓存也扫不到，因为行已经没了）。
     */
    @Query("SELECT msg_id AS msgId, media_count AS mediaCount FROM messages " +
        "WHERE channel_no = :channelNo AND sender_id = :senderId AND media_count > 0")
    suspend fun conversationMedia(channelNo: String, senderId: String): List<ReadMediaRow>

    @Query("SELECT msg_id AS msgId, media_count AS mediaCount FROM messages " +
        "WHERE channel_no = :channelNo AND media_count > 0")
    suspend fun channelMedia(channelNo: String): List<ReadMediaRow>

    /** §5.3 tombstone（整会话 scope）：清空该会话所有消息并移除会话行。 */
    @Transaction
    suspend fun tombstoneConversation(channelNo: String, senderId: String) {
        deleteConversationMessages(channelNo, senderId)
        deleteConversation(channelNo, senderId)
    }

    /** §5.3 tombstone（整频道 scope，sender_id 为 null）：清掉该频道下所有会话与消息。 */
    @Transaction
    suspend fun tombstoneChannel(channelNo: String) {
        deleteChannelMessages(channelNo)
        deleteChannelConversations(channelNo)
    }

    // —— 会话管理（§7.3 长按菜单：置顶 / 免打扰 / 重命名 / 清空）——

    @Query("UPDATE conversations SET pinned = :pinned WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun setPinned(channelNo: String, senderId: String, pinned: Boolean)

    @Query("UPDATE conversations SET muted = :muted WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun setMuted(channelNo: String, senderId: String, muted: Boolean)

    @Query("UPDATE conversations SET custom_name = :name WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun renameConversation(channelNo: String, senderId: String, name: String?)

    /** §7.4 本地"设头像"：存一张已落盘图片的绝对路径；null 表示恢复默认首字占位。 */
    @Query("UPDATE conversations SET avatar_path = :path WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun setAvatar(channelNo: String, senderId: String, path: String?)

    /** 免打扰判定：会话不存在返回 null，调用方据此按"未静音"处理。 */
    @Query("SELECT muted FROM conversations WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun isMuted(channelNo: String, senderId: String): Boolean?

    /** §7.3 锁屏可见判定：同 isMuted，会话不存在按"可见"处理。 */
    @Query("UPDATE conversations SET lock_private = :lockPrivate WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun setLockPrivate(channelNo: String, senderId: String, lockPrivate: Boolean)

    @Query("SELECT lock_private FROM conversations WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun isLockPrivate(channelNo: String, senderId: String): Boolean?

    /**
     * §5.2 频道号码重置后本地对账用：服务端换了 channel_no 但 msg_id 不变，
     * 旧号下的行既不会被新帧覆盖（insertIgnore 命中重复），也不会自己消失，
     * 只能按"本地有、服务端列表里没有"这条规则整频道丢弃后重拉。
     */
    @Query("SELECT DISTINCT channel_no FROM messages")
    suspend fun localChannelNos(): List<String>

    @Query("UPDATE conversations SET unread_count = 0, last_seq = 0, last_msg_at = 0, " +
        "last_summary_cached = NULL WHERE channel_no = :channelNo AND sender_id = :senderId")
    suspend fun resetConversationAggregate(channelNo: String, senderId: String)

    /** 本地清空某会话的聊天记录：删消息、重置聚合，但保留会话行（空会话仍可见）。 */
    @Transaction
    suspend fun clearConversation(channelNo: String, senderId: String) {
        deleteConversationMessages(channelNo, senderId)
        resetConversationAggregate(channelNo, senderId)
    }

    /**
     * 消息、会话行、游标必须同事务：进程在两步之间被杀的话红点数会永久对不上（§6.2）。
     * 返回 false 表示 msg_id 已存在 —— 补拉与实时下发是同一条消息的两个副本（验收②），
     * 调用方据此决定"要不要发通知"，比看 seq 游标更准。
     */
    @Transaction
    suspend fun apply(message: MessageRow, displayName: String): Boolean {
        if (insertIgnore(message) == 0L) return false
        bumpConversation(message.channelNo, message.senderId, displayName, message.seq,
            message.createdAt, message.summary ?: message.title)
        ensureCursor()
        val current = lastAppliedSeq()
        if (current == null || message.seq > current) {
            updateCursor(message.seq, System.currentTimeMillis())
        }
        return true
    }
}

@Database(entities = [MessageRow::class, ConversationRow::class, SyncRow::class], version = 6,
    exportSchema = false)
abstract class PushBoxDatabase : androidx.room.RoomDatabase() {
    abstract fun dao(): PushBoxDao
}
