using System;
using System.Collections.Generic;
using System.IO;
using Microsoft.Data.Sqlite;
using PushBox.Windows.Net;

namespace PushBox.Windows.Data;

/// <summary>§3.3 messages：本机的明文副本（服务端本来就明文归档，本地不再承担保密职责）。
/// <see cref="Truncated"/> 是服务端下发时截断过的标记：正文只有一半，"展开全文"要按 msg_id 单独回捞（§5.2）。</summary>
public sealed record MessageRow(
    string MsgId, long Seq, string ChannelNo, string SenderId, string MsgType, string? Title,
    string? Summary, string? Body, string Level, string? LinkUrl, string? MediaStatus,
    int MediaCount, long CreatedAt, string? SenderAvatar, bool Truncated = false);

/// <summary>
/// §3.3 conversations：一个 (channel_no, sender_id) 就是一条会话线。
/// unread_count 不是列而是算出来的（§3.3：由 last_read_seq 与 seq 比较得出）——存成列的话
/// tombstone 删掉一条未读就得记得重算，红点会凭空复现（Android 为此专门写了 recompute）。
/// </summary>
public sealed record ConversationRow(
    string ChannelNo, string SenderId, string Name, string? CustomName, string? AvatarPath,
    bool Pinned, bool Muted, long LastReadSeq, long LastSeq, long LastMsgAt, string? LastSummary,
    int UnreadCount, string? LastLevel)
{
    /// <summary>§7.3 会话改名：本地重命名优先，回落到服务端下发的 sender 名。</summary>
    public string DisplayName => string.IsNullOrWhiteSpace(CustomName) ? Name : CustomName!;
}

/// <summary>
/// 一条会话里<b>只有本机知道</b>的那几列。乐观删除要能把它原样放回去（§9.3 的回滚兜底）：
/// last_seq / last_msg_at / last_summary_cached / 未读数全是从 messages 现算的，消息一回来它们自己就回来了；
/// 而 name / custom_name / avatar_path / pinned / muted / last_read_seq 在那一行被删掉之后就没处再取了，
/// 所以回滚票必须带上它们。
/// </summary>
public sealed record ConversationMeta(string ChannelNo, string SenderId, string Name, string? CustomName,
    string? AvatarPath, bool Pinned, bool Muted, long LastReadSeq);

/// <summary>删一条消息之前它在库里的样子，见 <see cref="DeleteMessageLocal"/>。</summary>
public sealed record MessageUndo(MessageRow Message, ConversationMeta Conversation);

/// <summary>
/// 清一条会话之前它在库里的样子：整段正文 + 那一行的本机列。
/// 一条消息的回滚就是"只装了一条消息的这份"，所以放回只有一条路径（<see cref="RestoreConversation"/>）。
/// </summary>
public sealed record ClearUndo(ConversationMeta Conversation, IReadOnlyList<MessageRow> Messages);

/// <summary>
/// §3.3 的本地库：conversations / messages / sync_state / fts_messages。
///
/// 游标的分工（W4 定下的口径，两处不能互相覆盖）：
/// - <see cref="IdentityStore"/> 的 last_applied_seq 是"还没开库就知道"的那一份，建连的
///   since_seq 由它起头；
/// - 本表的 <see cref="GroupCursorKey"/> 行是落库之后的权威游标，和消息插入在同一个事务里推进
///   （§6.2：进程在两步之间被杀，游标绝不能跑到消息前面）。
/// 因为 DB 写总是先发生，两者不一致时 DB 只会更大，取 max 不会漏消息。
///
/// fts_messages 是外部内容表（content='messages'），靠三个触发器同步，正文只在 §3.3 的位置明文存一份。
/// </summary>
public sealed class DeviceStore : IDisposable
{
    /// <summary>sync_state 里存"全局已落库游标"那行的键；行名沿用 §3.3 的 channel_no 列。</summary>
    public const string GroupCursorKey = "";

    /// <summary>中文/单字查询走 LIKE 的下限：trigram 分词按 3 字符切片，短于 3 字符无法命中索引。</summary>
    private const int TrigramMinTermChars = 3;

    private const string MessageColumns =
        "msg_id, seq, channel_no, sender_id, msg_type, title, summary, body, level, link_url, " +
        "media_status, media_count, created_at, sender_avatar, truncated";

    private static readonly string[] Schema =
    {
        """
        CREATE TABLE IF NOT EXISTS messages(
          msg_id        TEXT PRIMARY KEY,
          seq           INTEGER NOT NULL,
          channel_no    TEXT NOT NULL,
          sender_id     TEXT NOT NULL,
          msg_type      TEXT NOT NULL,
          title         TEXT,
          summary       TEXT,
          body          TEXT,
          level         TEXT NOT NULL DEFAULT 'info',
          link_url      TEXT,
          media_status  TEXT,
          media_count   INTEGER NOT NULL DEFAULT 0,
          created_at    INTEGER NOT NULL,
          sender_avatar TEXT,
          truncated     INTEGER NOT NULL DEFAULT 0
        );
        """,
        "CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(channel_no, sender_id, seq);",
        "CREATE INDEX IF NOT EXISTS idx_messages_seq ON messages(seq);",
        """
        CREATE TABLE IF NOT EXISTS conversations(
          channel_no          TEXT NOT NULL,
          sender_id           TEXT NOT NULL,
          name                TEXT NOT NULL,
          custom_name         TEXT,
          avatar_path         TEXT,
          pinned              INTEGER NOT NULL DEFAULT 0,
          muted               INTEGER NOT NULL DEFAULT 0,
          last_read_seq       INTEGER NOT NULL DEFAULT 0,
          last_seq            INTEGER NOT NULL DEFAULT 0,
          last_msg_at         INTEGER NOT NULL DEFAULT 0,
          last_summary_cached TEXT,
          PRIMARY KEY(channel_no, sender_id)
        );
        """,
        """
        CREATE TABLE IF NOT EXISTS sync_state(
          channel_no       TEXT PRIMARY KEY,
          last_applied_seq INTEGER NOT NULL DEFAULT 0,
          last_sync_at     INTEGER NOT NULL DEFAULT 0
        );
        """,
        // 与 §3.2 服务端 ngram(2) 的动机一致，只是 SQLite 只有 trigram：≥3 字符进索引，
        // 短查询由 Search() 的 LIKE 分支兜底。
        """
        CREATE VIRTUAL TABLE IF NOT EXISTS fts_messages USING fts5(
          title, summary, body,
          content='messages', content_rowid='rowid',
          tokenize='trigram'
        );
        """,
        """
        CREATE TRIGGER IF NOT EXISTS fts_messages_ai AFTER INSERT ON messages BEGIN
          INSERT INTO fts_messages(rowid, title, summary, body)
          VALUES (new.rowid, coalesce(new.title, ''), coalesce(new.summary, ''), coalesce(new.body, ''));
        END;
        """,
        // 外部内容表的 'delete' 必须提交与入库时完全一致的老值，否则索引和正文会长期对不上。
        """
        CREATE TRIGGER IF NOT EXISTS fts_messages_ad AFTER DELETE ON messages BEGIN
          INSERT INTO fts_messages(fts_messages, rowid, title, summary, body)
          VALUES ('delete', old.rowid, coalesce(old.title, ''), coalesce(old.summary, ''), coalesce(old.body, ''));
        END;
        """,
        // media_ready 只改 media_status，不值得重建索引。
        """
        CREATE TRIGGER IF NOT EXISTS fts_messages_au AFTER UPDATE ON messages
        WHEN old.title IS NOT new.title OR old.summary IS NOT new.summary OR old.body IS NOT new.body BEGIN
          INSERT INTO fts_messages(fts_messages, rowid, title, summary, body)
          VALUES ('delete', old.rowid, coalesce(old.title, ''), coalesce(old.summary, ''), coalesce(old.body, ''));
          INSERT INTO fts_messages(rowid, title, summary, body)
          VALUES (new.rowid, coalesce(new.title, ''), coalesce(new.summary, ''), coalesce(new.body, ''));
        END;
        """,
    };

    private const string InsertMessageSql =
        """
        INSERT INTO messages (msg_id, seq, channel_no, sender_id, msg_type, title, summary, body,
                              level, link_url, media_status, media_count, created_at, sender_avatar,
                              truncated)
        VALUES (:msg_id, :seq, :channel_no, :sender_id, :msg_type, :title, :summary, :body,
                :level, :link_url, :media_status, :media_count, :created_at, :sender_avatar,
                :truncated)
        ON CONFLICT(msg_id) DO NOTHING;
        """;

    private const string BumpConversationSql =
        """
        INSERT INTO conversations (channel_no, sender_id, name, last_seq, last_msg_at, last_summary_cached)
        VALUES (:channel_no, :sender_id, :name, :seq, :created_at, :summary)
        ON CONFLICT(channel_no, sender_id) DO UPDATE SET
          name = excluded.name,
          last_seq = MAX(conversations.last_seq, excluded.last_seq),
          last_msg_at = MAX(conversations.last_msg_at, excluded.last_msg_at),
          last_summary_cached = excluded.last_summary_cached;
        """;

    private const string AdvanceCursorSql =
        """
        INSERT INTO sync_state (channel_no, last_applied_seq, last_sync_at)
        VALUES (:key, :seq, :now)
        ON CONFLICT(channel_no) DO UPDATE SET
          last_applied_seq = MAX(sync_state.last_applied_seq, excluded.last_applied_seq),
          last_sync_at = excluded.last_sync_at;
        """;

    // §9.1 的"会话行小圆点"要的是这条会话**最后一条**消息的 level：不落列（跟 unread_count 同一条理由——
    // tombstone 删掉最后一条时圆点得自己跟着变），现取一次的相关子查询就是那个语义。
    private const string ConversationsWithUnreadSql =
        """
        SELECT channel_no, sender_id, name, custom_name, avatar_path, pinned, muted,
               last_read_seq, last_seq, last_msg_at, last_summary_cached,
               (SELECT COUNT(*) FROM messages m
                  WHERE m.channel_no = conversations.channel_no
                    AND m.sender_id = conversations.sender_id
                    AND m.seq > conversations.last_read_seq) AS unread_count,
               (SELECT m.level FROM messages m
                  WHERE m.channel_no = conversations.channel_no
                    AND m.sender_id = conversations.sender_id
                  ORDER BY m.seq DESC LIMIT 1) AS last_level
        FROM conversations ORDER BY pinned DESC, last_msg_at DESC;
        """;

    private const string PageMessagesSql =
        "SELECT " + MessageColumns + " FROM messages" +
        " WHERE channel_no = :channel_no AND sender_id = :sender_id AND seq <= :from_seq" +
        " ORDER BY seq DESC LIMIT :limit;";

    private const string FtsSearchSql =
        "SELECT " + MessageColumns + " FROM messages" +
        " WHERE rowid IN (SELECT rowid FROM fts_messages WHERE fts_messages MATCH :term)";

    // lower() 只折叠 ASCII，与 trigram 默认大小写不敏感的口径一致；中文不受影响。
    // 三个 OR 必须整体括起来：SQL 里 AND 结合得比 OR 紧，不括的话后面追加的"仅本会话"条件
    // 只管得住最后一个 OR 分支，另外两列就成了全库扫——搜出来的是别的会话。
    private const string LikeSearchSql =
        "SELECT " + MessageColumns + " FROM messages" +
        " WHERE (lower(body) LIKE :pattern ESCAPE '\\' OR lower(title) LIKE :pattern ESCAPE '\\'" +
        " OR lower(summary) LIKE :pattern ESCAPE '\\')";

    /// <summary>搜索的两条 SQL 共用的尾巴：§7.4 的结果列表按时间倒序，与服务端 /search 同序。</summary>
    private const string SearchTailSql = " ORDER BY seq DESC LIMIT :limit;";

    private readonly SqliteConnection _db;
    private readonly object _gate = new();

    public DeviceStore(string databasePath)
    {
        DatabasePath = databasePath;
        if (databasePath != ":memory:")
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(databasePath))!);

        _db = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = databasePath,
            Mode = SqliteOpenMode.ReadWriteCreate,
        }.ToString());
        _db.Open();
        Execute("PRAGMA busy_timeout = 5000;");
        foreach (var statement in Schema) Execute(statement);
        // CREATE TABLE IF NOT EXISTS 只照顾新库：已经跑过一版的机器上那张表早就存在，
        // 新列只能靠这里补一次，否则旧库读一行就"no such column"（SQLite 没有 ADD COLUMN IF NOT EXISTS）
        EnsureColumn("messages", "truncated",
            "ALTER TABLE messages ADD COLUMN truncated INTEGER NOT NULL DEFAULT 0");
    }

    /// <summary>按 PRAGMA table_info 决定补不补这一列。默认值必须与建表语句里那条一致，
    /// 否则同一份库在新旧机器上的读法不同。</summary>
    private void EnsureColumn(string table, string column, string alterSql)
    {
        lock (_gate)
        {
            using var probe = Command(_db, null, $"PRAGMA table_info({table});");
            using var reader = probe.ExecuteReader();
            while (reader.Read())
            {
                if (string.Equals(reader.GetString(1), column, StringComparison.OrdinalIgnoreCase)) return;
            }
        }

        Execute(alterSql);
    }

    /// <summary>§3.3：Windows 落在 %LOCALAPPDATA%\PushBox\pushbox.db，私有目录、不进漫游配置。</summary>
    public static string DefaultDatabasePath =>
        Path.Combine(IdentityStore.DefaultDirectory, "pushbox.db");

    public static DeviceStore OpenDefault() => new(DefaultDatabasePath);

    public string DatabasePath { get; }

    /// <summary>建连时的 since_seq 用得上（§5.3）；没落过库就是 0，也就是全量拉。</summary>
    public long GroupCursor()
    {
        lock (_gate)
        {
            using var cmd = Command(_db, null, "SELECT last_applied_seq FROM sync_state WHERE channel_no = :key;");
            cmd.Parameters.AddWithValue(":key", GroupCursorKey);
            var value = cmd.ExecuteScalar();
            return value is long seq ? seq : 0L;
        }
    }

    /// <summary>
    /// §7.3 免打扰：会话行的 muted 只决定"要不要弹通知"，跟入库一点关系都没有。
    /// 会话行还不存在时回 false——第一条消息、已读游标、静音状态谁先到没有保证，
    /// "没有这一行"的意思是"没人说过要静音"，而不是"静音状态未知所以别弹"（Android 的 <c>!= true</c> 同义）。
    /// </summary>
    public bool IsMuted(string channelNo, string senderId)
    {
        lock (_gate)
        {
            using var cmd = Command(_db, null,
                "SELECT muted FROM conversations WHERE channel_no = :channel_no AND sender_id = :sender_id;");
            cmd.Parameters.AddWithValue(":channel_no", channelNo);
            cmd.Parameters.AddWithValue(":sender_id", senderId);
            return cmd.ExecuteScalar() is long value && value != 0L;
        }
    }

    /// <summary>
    /// §5.3 config（channel_no_reset）：服务端换了对外号码但没换 msg_id，本机以旧号为键的那些行
    /// 既不会被新号覆盖也不会自己消失，界面上就成了"同一批消息两条会话线"。
    /// 这里只负责认出本机缓存过哪些号码，删行交给 <see cref="ApplyTombstone"/> 的整频道路径
    /// （连带 FTS 触发器一起走）。
    /// </summary>
    public IReadOnlyList<string> ChannelNumbers()
    {
        lock (_gate)
        {
            using var cmd = Command(_db, null, "SELECT DISTINCT channel_no FROM messages;");
            using var reader = cmd.ExecuteReader();
            var list = new List<string>();
            while (reader.Read()) list.Add(reader.GetString(0));
            return list;
        }
    }

    /// <summary>§5.2 全文回捞落库：换回未截断的正文并清掉 truncated，"展开全文"那一行跟着消失。
    /// body 变了会自动走 FTS 的 au 触发器，所以不需要额外维护索引。</summary>
    public void ApplyFulltext(string msgId, string body)
    {
        RunTransaction(tx =>
        {
            using var cmd = Command(_db, tx,
                "UPDATE messages SET body = :body, truncated = 0 WHERE msg_id = :msg_id;");
            cmd.Parameters.AddWithValue(":body", body);
            cmd.Parameters.AddWithValue(":msg_id", msgId);
            cmd.ExecuteNonQuery();
        });
    }

    /// <summary>
    /// §7.3 会话长按菜单的四项本机设置：置顶 / 免打扰 / 重命名 / 头像。传 null 的那项不动。
    /// 重命名给空串就是"取消自定义名"（写回 NULL，显示时落回服务端下发的 sender 名）。
    /// 会话行还没被第一条消息建出来时这几句命中 0 行——那正是"设置先于消息到达"的时序；
    /// 与 <see cref="ApplyRead"/> 不同，这几项没有需要先亮的灯，所以不建壳行。
    /// </summary>
    public void UpdateConversation(string channelNo, string senderId, bool? pinned, bool? muted,
        string? customName, string? avatarPath)
    {
        var sets = new List<string>();
        if (pinned != null) sets.Add("pinned = :pinned");
        if (muted != null) sets.Add("muted = :muted");
        if (customName != null) sets.Add("custom_name = :custom_name");
        if (avatarPath != null) sets.Add("avatar_path = :avatar_path");
        if (sets.Count == 0) return;

        RunTransaction(tx =>
        {
            using var cmd = Command(_db, tx,
                "UPDATE conversations SET " + string.Join(", ", sets)
                + " WHERE channel_no = :channel_no AND sender_id = :sender_id;");
            cmd.Parameters.AddWithValue(":channel_no", channelNo);
            cmd.Parameters.AddWithValue(":sender_id", senderId);
            if (pinned != null) cmd.Parameters.AddWithValue(":pinned", pinned.Value ? 1L : 0L);
            if (muted != null) cmd.Parameters.AddWithValue(":muted", muted.Value ? 1L : 0L);
            if (customName != null)
                AddNullable(cmd, ":custom_name", customName.Length == 0 ? null : customName);
            if (avatarPath != null) AddNullable(cmd, ":avatar_path", avatarPath.Length == 0 ? null : avatarPath);
            cmd.ExecuteNonQuery();
        });
    }

    /// <summary>
    /// 消息、会话行、游标同事务（§6.2）。返回 false 表示 msg_id 已在库里——补拉与实时下发是同一条
    /// 消息的两个副本，调用方据此决定"要不要发通知"，比看 seq 游标准。
    /// </summary>
    public bool ApplyMessage(MessageFrame frame)
    {
        if (frame.Id.Length == 0) return false;

        return RunTransaction(tx =>
        {
            using (var insert = Command(_db, tx, InsertMessageSql))
            {
                insert.Parameters.AddWithValue(":msg_id", frame.Id);
                insert.Parameters.AddWithValue(":seq", frame.Seq);
                insert.Parameters.AddWithValue(":channel_no", frame.ChannelNo);
                insert.Parameters.AddWithValue(":sender_id", frame.SenderId);
                insert.Parameters.AddWithValue(":msg_type", frame.Type);
                AddNullable(insert, ":title", frame.Title);
                AddNullable(insert, ":summary", frame.Summary);
                AddNullable(insert, ":body", frame.Content);
                insert.Parameters.AddWithValue(":level", frame.Level);
                AddNullable(insert, ":link_url", frame.Url);
                AddNullable(insert, ":media_status", frame.MediaStatus);
                insert.Parameters.AddWithValue(":media_count", frame.Media.Count);
                insert.Parameters.AddWithValue(":created_at", frame.CreatedAt);
                AddNullable(insert, ":sender_avatar", frame.SenderAvatar);
                insert.Parameters.AddWithValue(":truncated", frame.Truncated ? 1L : 0L);
                if (insert.ExecuteNonQuery() == 0) return false;
            }

            using (var bump = Command(_db, tx, BumpConversationSql))
            {
                bump.Parameters.AddWithValue(":channel_no", frame.ChannelNo);
                bump.Parameters.AddWithValue(":sender_id", frame.SenderId);
                bump.Parameters.AddWithValue(":name", frame.DisplayName);
                bump.Parameters.AddWithValue(":seq", frame.Seq);
                bump.Parameters.AddWithValue(":created_at", frame.CreatedAt);
                AddNullable(bump, ":summary", frame.Summary ?? frame.Title);
                bump.ExecuteNonQuery();
            }

            using (var cursor = Command(_db, tx, AdvanceCursorSql))
            {
                cursor.Parameters.AddWithValue(":key", GroupCursorKey);
                cursor.Parameters.AddWithValue(":seq", frame.Seq);
                cursor.Parameters.AddWithValue(":now", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
                cursor.ExecuteNonQuery();
            }

            return true;
        });
    }

    /// <summary>
    /// §4.5 media_ready / media_failed 的回填：只改媒体终态，不动游标、不动正文。
    /// 槽数取 <c>MAX</c> 而不是覆盖：补发帧只带本次到终态的那几槽，覆盖会把"三张图"缩到后到那一帧的槽数。
    /// 返回 false 表示那条消息还没落库（补拉与实时下发的竞态），调用方要把它暂存到消息到达。
    /// </summary>
    public bool ApplyMediaStatus(string msgId, string status, int mediaCount)
    {
        return RunTransaction(tx =>
        {
            using var cmd = Command(_db, tx,
                "UPDATE messages SET media_status = :status, media_count = MAX(media_count, :count) WHERE msg_id = :msg_id;");
            cmd.Parameters.AddWithValue(":status", status);
            cmd.Parameters.AddWithValue(":count", mediaCount);
            cmd.Parameters.AddWithValue(":msg_id", msgId);
            return cmd.ExecuteNonQuery() > 0;
        });
    }

    /// <summary>
    /// §5.3 read：他端把游标推进了，本端红点要跟着降。
    /// 会话行可能还不存在——已读帧先到、消息后补拉是正常时序，所以这里 upsert 一个壳行，
    /// 否则 last_read_seq 会在第一条消息 bump 时被建成 0，把已读的历史重新算成未读。
    /// </summary>
    public void ApplyRead(string channelNo, string senderId, long lastReadSeq)
    {
        RunTransaction(tx =>
        {
            using var cmd = Command(_db, tx,
                """
                INSERT INTO conversations (channel_no, sender_id, name, last_read_seq)
                VALUES (:channel_no, :sender_id, :sender_id, :last_read_seq)
                ON CONFLICT(channel_no, sender_id) DO UPDATE SET
                  last_read_seq = MAX(conversations.last_read_seq, excluded.last_read_seq);
                """);
            cmd.Parameters.AddWithValue(":channel_no", channelNo);
            cmd.Parameters.AddWithValue(":sender_id", senderId);
            cmd.Parameters.AddWithValue(":last_read_seq", lastReadSeq);
            cmd.ExecuteNonQuery();
        });
    }

    /// <summary>
    /// 本机翻开会话：游标推到该会话的最后一条（MAX，只增不减），并把推进后的值回传给 W9 上报。
    /// 会话不在库里就返回 0——没有消息可标已读，也就没有东西值得 PUT。
    /// </summary>
    public long MarkConversationRead(string channelNo, string senderId)
    {
        return RunTransaction(tx =>
        {
            using (var cmd = Command(_db, tx,
                "UPDATE conversations SET last_read_seq = MAX(last_read_seq, last_seq)" +
                " WHERE channel_no = :channel_no AND sender_id = :sender_id;"))
            {
                cmd.Parameters.AddWithValue(":channel_no", channelNo);
                cmd.Parameters.AddWithValue(":sender_id", senderId);
                cmd.ExecuteNonQuery();
            }

            using var read = Command(_db, tx,
                "SELECT last_read_seq FROM conversations" +
                " WHERE channel_no = :channel_no AND sender_id = :sender_id;");
            read.Parameters.AddWithValue(":channel_no", channelNo);
            read.Parameters.AddWithValue(":sender_id", senderId);
            return read.ExecuteScalar() is long seq ? seq : 0L;
        });
    }

    /// <summary>§5.3 tombstone：id 定位单条，scope 定位整会话，只有 channel 则是整频道。</summary>
    public void ApplyTombstone(TombstoneFrame frame)
    {
        if (!string.IsNullOrEmpty(frame.Id))
        {
            RunTransaction(tx =>
            {
                string? channelNo;
                string? senderId;
                using (var find = Command(_db, tx, SelectConversationOfSql))
                {
                    find.Parameters.AddWithValue(":msg_id", frame.Id!);
                    using var reader = find.ExecuteReader();
                    if (!reader.Read()) return;
                    channelNo = reader.GetString(0);
                    senderId = reader.GetString(1);
                }

                DeleteMessageRow(tx, frame.Id!, channelNo!, senderId!);
            });
            return;
        }

        if (string.IsNullOrEmpty(frame.ChannelNo)) return;

        RunTransaction(tx =>
        {
            if (!string.IsNullOrEmpty(frame.SenderId))
            {
                DeleteScoped(tx, "channel_no = :channel_no AND sender_id = :sender_id",
                    frame.ChannelNo!, frame.SenderId!);
                return;
            }

            using (var messages = Command(_db, tx, "DELETE FROM messages WHERE channel_no = :channel_no;"))
            {
                messages.Parameters.AddWithValue(":channel_no", frame.ChannelNo!);
                messages.ExecuteNonQuery();
            }

            using (var conversations = Command(_db, tx,
                       "DELETE FROM conversations WHERE channel_no = :channel_no;"))
            {
                conversations.Parameters.AddWithValue(":channel_no", frame.ChannelNo!);
                conversations.ExecuteNonQuery();
            }
        });
    }

    /// <summary>一条消息被删掉之后会话行该剩什么，两个入口（本机的乐观删除、他端的 tombstone 帧）共用这一段。</summary>
    private void DeleteMessageRow(SqliteTransaction tx, string msgId, string channelNo, string senderId)
    {
        using (var delete = Command(_db, tx, "DELETE FROM messages WHERE msg_id = :msg_id;"))
        {
            delete.Parameters.AddWithValue(":msg_id", msgId);
            delete.ExecuteNonQuery();
        }

        // 会话空了就连行一起删（与 Android 的 tombstoneMessage 一致），否则重算聚合
        using (var recompute = Command(_db, tx, RecomputeConversationSql))
        {
            recompute.Parameters.AddWithValue(":channel_no", channelNo);
            recompute.Parameters.AddWithValue(":sender_id", senderId);
            recompute.ExecuteNonQuery();
        }

        using (var drop = Command(_db, tx, DropEmptyConversationSql))
        {
            drop.Parameters.AddWithValue(":channel_no", channelNo);
            drop.Parameters.AddWithValue(":sender_id", senderId);
            drop.ExecuteNonQuery();
        }
    }

    /// <summary>
    /// §9.3 的"本地乐观删除"：先把这条从库里拿掉、交出回滚票，UI 当场就能重画，服务端那一发之后才补。
    /// 删的动作与 <see cref="ApplyTombstone"/> 的 id 分支共用同一段（删行 → 重算聚合 → 空了就删会话行），
    /// 所以"本机立刻没显示"和"tombstone 帧到了"落进的是同一个库里状态——W14 验收里那句"等 tombstone 收敛"
    /// 靠的就是这个同一性，而不是两套代码各自小心。
    /// 返回 null 表示库里没有这条：没有东西要藏，也没有东西要回滚。
    /// </summary>
    public MessageUndo? DeleteMessageLocal(string msgId)
    {
        if (string.IsNullOrEmpty(msgId)) return null;

        return RunTransaction(tx =>
        {
            MessageRow? message = null;
            using (var read = Command(_db, tx, SelectMessageByIdSql))
            {
                read.Parameters.AddWithValue(":msg_id", msgId);
                using var reader = read.ExecuteReader();
                if (reader.Read()) message = ReadMessageRow(reader);
            }

            if (message == null) return null;
            // 会话行与消息是同事务成对写的（ApplyMessage 的 bump），所以这条一定读得到
            var meta = ReadMeta(tx, message.ChannelNo, message.SenderId)!;
            DeleteMessageRow(tx, msgId, message.ChannelNo, message.SenderId);
            return new MessageUndo(message, meta);
        });
    }

    /// <summary>
    /// §9.3 的乐观清空：与 <see cref="ApplyTombstone"/> 的 scope 分支同一套动作，把整段正文和那一行的
    /// 本机列一起交成回滚票。会话本来就不在库里回 null——不必发请求，也就没有东西值得放回。
    /// </summary>
    public ClearUndo? ClearConversationLocal(string channelNo, string senderId)
    {
        if (string.IsNullOrEmpty(channelNo) || string.IsNullOrEmpty(senderId)) return null;

        return RunTransaction(tx =>
        {
            var meta = ReadMeta(tx, channelNo, senderId);
            if (meta == null) return null;

            var messages = new List<MessageRow>();
            using (var read = Command(_db, tx, SelectConversationMessagesSql))
            {
                read.Parameters.AddWithValue(":channel_no", channelNo);
                read.Parameters.AddWithValue(":sender_id", senderId);
                using var reader = read.ExecuteReader();
                while (reader.Read()) messages.Add(ReadMessageRow(reader));
            }

            DeleteScoped(tx, "channel_no = :channel_no AND sender_id = :sender_id", channelNo, senderId);
            return new ClearUndo(meta, messages);
        });
    }

    /// <summary>一条消息的回滚就是一次"票上只装了一条"的清空回滚——放回路径只有一条，不写第二遍。</summary>
    public void RestoreMessage(MessageUndo undo) =>
        RestoreConversation(new ClearUndo(undo.Conversation, new[] { undo.Message }));

    /// <summary>
    /// 回滚兜底（§9.3）：把票上的正文一条条放回，补出那条会话行，最后按消息重算聚合。
    /// "删出去这段时间里可能发生的事"有三种，共同点是都不许把别人的新值按回去：
    /// - 他端把这条会话读了（read 帧推高游标）→ 会话行按 <c>MAX(last_read_seq)</c>，只增不减，
    ///   否则本机一次<b>失败</b>的删除会把手机上刚灭的红点又点亮；
    /// - 期间有新消息落进同一会话 → name / custom_name / pinned 一概不覆盖，行已存在就留着它现在的值；
    /// - 同一条消息又被落库一次（补拉与实时下发的竞态）→ <c>ON CONFLICT(msg_id) DO NOTHING</c>，
    ///   库里那份带着更新的媒体终态，不该被票上的旧值盖掉。
    /// 聚合值（last_seq / last_msg_at / last_summary_cached）不从票上来，一律重算。
    /// 全局游标 <see cref="GroupCursorKey"/> 那一行不动：删除不回退游标，放回也不推进它。
    /// </summary>
    public void RestoreConversation(ClearUndo undo)
    {
        RunTransaction(tx =>
        {
            foreach (var message in undo.Messages) InsertRestored(tx, message);

            using (var shell = Command(_db, tx, RestoreConversationSql))
            {
                BindMeta(shell, undo.Conversation);
                shell.ExecuteNonQuery();
            }

            using (var recompute = Command(_db, tx, RecomputeConversationSql))
            {
                recompute.Parameters.AddWithValue(":channel_no", undo.Conversation.ChannelNo);
                recompute.Parameters.AddWithValue(":sender_id", undo.Conversation.SenderId);
                recompute.ExecuteNonQuery();
            }
        });
    }

    private ConversationMeta? ReadMeta(SqliteTransaction tx, string channelNo, string senderId)
    {
        using var cmd = Command(_db, tx, SelectMetaSql);
        cmd.Parameters.AddWithValue(":channel_no", channelNo);
        cmd.Parameters.AddWithValue(":sender_id", senderId);
        using var reader = cmd.ExecuteReader();
        return reader.Read()
            ? new ConversationMeta(reader.GetString(0), reader.GetString(1), reader.GetString(2),
                reader.IsDBNull(3) ? null : reader.GetString(3),
                reader.IsDBNull(4) ? null : reader.GetString(4),
                reader.GetInt64(5) != 0, reader.GetInt64(6) != 0, reader.GetInt64(7))
            : null;
    }

    private void InsertRestored(SqliteTransaction tx, MessageRow row)
    {
        using var cmd = Command(_db, tx, RestoreMessageSql);
        cmd.Parameters.AddWithValue(":msg_id", row.MsgId);
        cmd.Parameters.AddWithValue(":seq", row.Seq);
        cmd.Parameters.AddWithValue(":channel_no", row.ChannelNo);
        cmd.Parameters.AddWithValue(":sender_id", row.SenderId);
        cmd.Parameters.AddWithValue(":msg_type", row.MsgType);
        AddNullable(cmd, ":title", row.Title);
        AddNullable(cmd, ":summary", row.Summary);
        AddNullable(cmd, ":body", row.Body);
        cmd.Parameters.AddWithValue(":level", row.Level);
        AddNullable(cmd, ":link_url", row.LinkUrl);
        AddNullable(cmd, ":media_status", row.MediaStatus);
        cmd.Parameters.AddWithValue(":media_count", row.MediaCount);
        cmd.Parameters.AddWithValue(":created_at", row.CreatedAt);
        AddNullable(cmd, ":sender_avatar", row.SenderAvatar);
        cmd.Parameters.AddWithValue(":truncated", row.Truncated ? 1L : 0L);
        cmd.ExecuteNonQuery();
    }

    private static void BindMeta(SqliteCommand cmd, ConversationMeta meta)
    {
        cmd.Parameters.AddWithValue(":channel_no", meta.ChannelNo);
        cmd.Parameters.AddWithValue(":sender_id", meta.SenderId);
        cmd.Parameters.AddWithValue(":name", meta.Name);
        AddNullable(cmd, ":custom_name", meta.CustomName);
        AddNullable(cmd, ":avatar_path", meta.AvatarPath);
        // SQLite 那两列是 INTEGER 0/1（见 Schema），布尔按 Android 的写法落成 0/1
        cmd.Parameters.AddWithValue(":pinned", meta.Pinned ? 1L : 0L);
        cmd.Parameters.AddWithValue(":muted", meta.Muted ? 1L : 0L);
        cmd.Parameters.AddWithValue(":last_read_seq", meta.LastReadSeq);
    }

    private const string RecomputeConversationSql =
        """
        UPDATE conversations SET
          last_seq = COALESCE((SELECT MAX(seq) FROM messages
                                WHERE channel_no = :channel_no AND sender_id = :sender_id), 0),
          last_msg_at = COALESCE((SELECT MAX(created_at) FROM messages
                                   WHERE channel_no = :channel_no AND sender_id = :sender_id), 0),
          last_summary_cached = (SELECT COALESCE(NULLIF(summary, ''), title) FROM messages
                                  WHERE channel_no = :channel_no AND sender_id = :sender_id
                                  ORDER BY seq DESC LIMIT 1)
        WHERE channel_no = :channel_no AND sender_id = :sender_id;
        """;

    private const string DropEmptyConversationSql =
        """
        DELETE FROM conversations
        WHERE channel_no = :channel_no AND sender_id = :sender_id
          AND NOT EXISTS (SELECT 1 FROM messages
                          WHERE channel_no = :channel_no AND sender_id = :sender_id);
        """;

    private void DeleteScoped(SqliteTransaction tx, string predicate, string channelNo, string senderId)
    {
        using var messages = Command(_db, tx, $"DELETE FROM messages WHERE {predicate};");
        messages.Parameters.AddWithValue(":channel_no", channelNo);
        messages.Parameters.AddWithValue(":sender_id", senderId);
        messages.ExecuteNonQuery();

        using var conversations = Command(_db, tx, $"DELETE FROM conversations WHERE {predicate};");
        conversations.Parameters.AddWithValue(":channel_no", channelNo);
        conversations.Parameters.AddWithValue(":sender_id", senderId);
        conversations.ExecuteNonQuery();
    }

    private const string SelectConversationOfSql =
        "SELECT channel_no, sender_id FROM messages WHERE msg_id = :msg_id;";

    private const string SelectMessageByIdSql =
        "SELECT " + MessageColumns + " FROM messages WHERE msg_id = :msg_id;";

    /// <summary>回滚票要把整段正文都带上（清空一整条会话的情况），所以这里不设 limit：
    /// 票的寿命只有一次网络往返，删成功后它连同这些字符串一起变垃圾回收的对象。</summary>
    private const string SelectConversationMessagesSql =
        "SELECT " + MessageColumns + " FROM messages" +
        " WHERE channel_no = :channel_no AND sender_id = :sender_id ORDER BY seq;";

    private const string SelectMetaSql =
        "SELECT channel_no, sender_id, name, custom_name, avatar_path, pinned, muted, last_read_seq" +
        " FROM conversations WHERE channel_no = :channel_no AND sender_id = :sender_id;";

    /// <summary>放回时只补本机那几列：行已存在就一概不覆盖（期间别处写进去的是更新的值），
    /// 只有 last_read_seq 按 MAX 合并——与 <see cref="ApplyRead"/>、<see cref="AdvanceCursorSql"/>
    /// 同一条"只增不减"，否则一次失败的删除会把手机刚灭的红点点回来。</summary>
    private const string RestoreConversationSql =
        """
        INSERT INTO conversations (channel_no, sender_id, name, custom_name, avatar_path,
                                   pinned, muted, last_read_seq)
        VALUES (:channel_no, :sender_id, :name, :custom_name, :avatar_path, :pinned, :muted, :last_read_seq)
        ON CONFLICT(channel_no, sender_id) DO UPDATE SET
          last_read_seq = MAX(conversations.last_read_seq, excluded.last_read_seq);
        """;

    /// <summary>票上的正文原样放回，<c>DO NOTHING</c> 而不是 REPLACE：期间这条又被落库一次的话，
    /// 库里那份带着更新的媒体终态。放回的插入照样触发 fts_messages_ai，所以索引跟着正文一起回来。</summary>
    private const string RestoreMessageSql =
        "INSERT INTO messages (" + MessageColumns + ") VALUES (:msg_id, :seq, :channel_no, :sender_id," +
        " :msg_type, :title, :summary, :body, :level, :link_url, :media_status, :media_count," +
        " :created_at, :sender_avatar, :truncated) ON CONFLICT(msg_id) DO NOTHING;";

    public IReadOnlyList<ConversationRow> Conversations()
    {
        lock (_gate)
        {
            using var cmd = Command(_db, null, ConversationsWithUnreadSql);
            using var reader = cmd.ExecuteReader();
            var rows = new List<ConversationRow>();
            while (reader.Read())
            {
                rows.Add(new ConversationRow(
                    reader.GetString(0), reader.GetString(1), reader.GetString(2),
                    reader.IsDBNull(3) ? null : reader.GetString(3),
                    reader.IsDBNull(4) ? null : reader.GetString(4),
                    reader.GetInt64(5) != 0, reader.GetInt64(6) != 0,
                    reader.GetInt64(7), reader.GetInt64(8), reader.GetInt64(9),
                    reader.IsDBNull(10) ? null : reader.GetString(10),
                    (int)reader.GetInt64(11),
                    reader.IsDBNull(12) ? null : reader.GetString(12)));
            }

            return rows;
        }
    }

    /// <summary>
    /// 向上翻页：取 seq &lt;= fromSeq 的最新 limit 条，返回时已按 seq 正序（屏从上往下读、
    /// 下一页把本页最小 seq 减 1 传进来，所以不会重也不会漏）。
    /// </summary>
    public IReadOnlyList<MessageRow> MessagesPage(string channelNo, string senderId,
        long fromSeq = long.MaxValue, int limit = 50)
    {
        List<MessageRow> page;
        lock (_gate)
        {
            using var cmd = Command(_db, null, PageMessagesSql);
            cmd.Parameters.AddWithValue(":channel_no", channelNo);
            cmd.Parameters.AddWithValue(":sender_id", senderId);
            cmd.Parameters.AddWithValue(":from_seq", fromSeq);
            cmd.Parameters.AddWithValue(":limit", limit);
            using var reader = cmd.ExecuteReader();
            page = ReadMessages(reader);
        }

        // 上面按 DESC 取窗口，正序返回才和会话详情页的时间线一致
        page.Reverse();
        return page;
    }

    /// <summary>
    /// §3.3 本地即时搜索：≥3 字符进 FTS5 trigram 索引，更短的单字/双字查询退回 LIKE。
    /// channelNo/senderId 给定时就是 §7.4 的"仅本会话"——对话页那个搜索框只查这一条会话线。
    /// </summary>
    public IReadOnlyList<MessageRow> Search(string term, string? channelNo = null,
        string? senderId = null, int limit = 100)
    {
        var query = term?.Trim() ?? string.Empty;
        if (query.Length == 0) return Array.Empty<MessageRow>();

        // 条件只能是这两条，所以直接拼进语句；值一律参数绑定
        var scope = (!string.IsNullOrWhiteSpace(channelNo) ? " AND channel_no = :channel_no" : "")
                    + (!string.IsNullOrWhiteSpace(senderId) ? " AND sender_id = :sender_id" : "");
        var useFts = query.Length >= TrigramMinTermChars;

        lock (_gate)
        {
            using var cmd = Command(_db, null,
                (useFts ? FtsSearchSql : LikeSearchSql) + scope + SearchTailSql);
            if (useFts)
            {
                // 整串当短语进 trigram，双引号转义成两个双引号，不给它解析操作符的机会
                cmd.Parameters.AddWithValue(":term", "\"" + query.Replace("\"", "\"\"") + "\"");
            }
            else
            {
                cmd.Parameters.AddWithValue(":pattern",
                    "%" + query.ToLowerInvariant().Replace("\\", "\\\\").Replace("%", "\\%").Replace("_", "\\_") + "%");
            }

            if (!string.IsNullOrWhiteSpace(channelNo)) cmd.Parameters.AddWithValue(":channel_no", channelNo);
            if (!string.IsNullOrWhiteSpace(senderId)) cmd.Parameters.AddWithValue(":sender_id", senderId);
            cmd.Parameters.AddWithValue(":limit", limit);
            using var reader = cmd.ExecuteReader();
            return ReadMessages(reader);
        }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _db.Close();
            _db.Dispose();
        }
    }

    private static List<MessageRow> ReadMessages(SqliteDataReader reader)
    {
        var rows = new List<MessageRow>();
        while (reader.Read()) rows.Add(ReadMessageRow(reader));
        return rows;
    }

    /// <summary>按 <see cref="MessageColumns"/> 的列序读一行；乐观删除取单条时走同一个读法。</summary>
    private static MessageRow ReadMessageRow(SqliteDataReader reader) =>
        new(reader.GetString(0), reader.GetInt64(1), reader.GetString(2), reader.GetString(3),
            reader.GetString(4),
            reader.IsDBNull(5) ? null : reader.GetString(5),
            reader.IsDBNull(6) ? null : reader.GetString(6),
            reader.IsDBNull(7) ? null : reader.GetString(7),
            reader.GetString(8),
            reader.IsDBNull(9) ? null : reader.GetString(9),
            reader.IsDBNull(10) ? null : reader.GetString(10),
            (int)reader.GetInt64(11), reader.GetInt64(12),
            reader.IsDBNull(13) ? null : reader.GetString(13),
            reader.GetInt64(14) != 0);

    private void Execute(string sql)
    {
        lock (_gate)
        {
            using var cmd = Command(_db, null, sql);
            cmd.ExecuteNonQuery();
        }
    }

    private T RunTransaction<T>(Func<SqliteTransaction, T> body)
    {
        lock (_gate)
        {
            using var tx = _db.BeginTransaction();
            var result = body(tx);
            tx.Commit();
            return result;
        }
    }

    private void RunTransaction(Action<SqliteTransaction> body) =>
        RunTransaction<object?>(tx =>
        {
            body(tx);
            return null;
        });

    private static SqliteCommand Command(SqliteConnection db, SqliteTransaction? tx, string sql)
    {
        var cmd = db.CreateCommand();
        cmd.CommandText = sql;
        if (tx != null) cmd.Transaction = tx;
        return cmd;
    }

    private static void AddNullable(SqliteCommand cmd, string name, string? value) =>
        cmd.Parameters.AddWithValue(name, (object?)value ?? DBNull.Value);
}
