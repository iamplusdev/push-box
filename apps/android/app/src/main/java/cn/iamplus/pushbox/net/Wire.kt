package cn.iamplus.pushbox.net

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.pushbox.core.protocol.Json

/**
 * 客户端侧的帧编解码。
 *
 * 为什么不直接用 pushbox-core 里的 record DTO：
 * Jackson 靠 `Class.isRecord()` 识别 record，而这个 API 要 Android 14（API 34）才有，
 * D8 的 record 脱糖也不会让它返回 true —— 结果是在低版本上 record 会被当成普通 Bean，
 * 序列化静默变成 `{}`、反序列化抛 "no suitable creator"。这种失败不报编译错，只在手机上炸，
 * 所以这里只走 Jackson 的树模型（零反射），字段名与 §5.3 逐字对齐；
 * 加解密仍然复用 pbcore 的 FrameChannel/AesGcm/SignatureTargets，保证与服务端字节级一致。
 */
object Wire {

    const val T_HELLO = "hello"
    const val T_KX = "kx"
    const val T_AUTH = "auth"
    const val T_AUTH_OK = "auth_ok"
    const val T_AUTH_FAIL = "auth_fail"
    const val T_ENC = "enc"
    const val T_MESSAGE = "message"
    const val T_MEDIA_READY = "media_ready"
    /** §4.5：服务端重试耗尽后仍失败的合并帧，结构与 media_ready 一致，只是 t 不同。 */
    const val T_MEDIA_FAILED = "media_failed"
    /** §5.3：密钥/号码/配置/设备名单变更。不带 seq，所以只解决"在线的一端即刻知道"。 */
    const val T_CONFIG = "config"
    const val T_READ = "read"
    const val T_TOMBSTONE = "tombstone"
    const val T_PING = "ping"
    const val T_PONG = "pong"
    const val T_RESUME_OK = "resume_ok"
    const val T_RESUME_GAP = "resume_gap"

    fun parse(json: String): JsonNode? = try {
        Json.MAPPER.readTree(json)
    } catch (_: Exception) {
        null
    }

    fun write(node: JsonNode): String = Json.write(node)

    /** 客户端唯一要发的明文帧：token 在帧内、不进 URL（§5.3）。 */
    fun authFrame(deviceId: String, token: String, ts: Long, sigB64: String): String {
        val node = Json.MAPPER.createObjectNode()
        node.put("t", T_AUTH)
        node.put("device_id", deviceId)
        node.put("token", token)
        node.put("ts", ts)
        node.put("sig", sigB64)
        return write(node)
    }

    fun pong(): String = simple(T_PONG)

    fun ping(): String = simple(T_PING)

    private fun simple(type: String): String {
        val node: ObjectNode = Json.MAPPER.createObjectNode()
        node.put("t", type)
        return write(node)
    }
}

/** §5.3 hello：nonce 是签名挑战，heartbeat 决定客户端多久发一次 ping。 */
data class HelloFrame(
    val serverTime: Long,
    val heartbeatSec: Int,
    val serverPubFp: String,
    val nonceB64: String,
)

/** §4.3 kx：同时带服务端身份公钥，客户端先比指纹再验签。 */
data class KxFrame(
    val serverPubB64: String,
    val ephPubB64: String,
    val sigB64: String,
    val epoch: Int,
)

data class EncFrame(val index: Long, val dataB64: String)

/**
 * §4.5 一个媒体槽：服务端 media[] 里每项的映射。字段名与 pbcore Frames.MediaSlot 逐字对齐
 * （idx/status/key/mime/size/w/h/thumb，无 @JsonProperty 所以即组件名）。
 * key/thumb 是对象存储的键，客户端用不到（真取图走 GET /media/{msgId}/{idx} 换预签名 URL），
 * 这里只留渲染与状态判断需要的字段。
 */
data class MediaSlot(
    val idx: Int,
    val status: String,
    val mime: String?,
    val size: Long,
    val w: Int?,
    val h: Int?,
) {
    val ready: Boolean get() = status == "ready"

    companion object {
        /**
         * §4.5 服务端 media_objects 的"图已经不会再有了"的三个终态。
         * 与 pbcore MediaStatus.aggregate 同一口径：这三个都算 failed 计数。
         */
        private val GONE = setOf("failed", "deleted", "purged")

        /** 槽位是否已终态失效：客户端据此决定不再打那一跳必然 404 的签名 GET。 */
        fun isGone(status: String?): Boolean = status in GONE
    }
}

/**
 * 落库形态：`idx:status` 逗号串。用稀疏形式而不是按位逗号列表，因为补发帧只带本次到终态的
 * 那几槽——"第三张已就绪、前两张还没回"要能表达，按位列表得先造出两个假空位。
 */
internal fun List<MediaSlot>.toSlotBlob(): String? =
    if (isEmpty()) null else joinToString(",") { "${it.idx}:${it.status}" }


/** 下发消息帧（§5.3）与历史项（§5.2）字段名一致，这里一份模型两处用。 */
data class MessageFrame(
    val seq: Long,
    val msgId: String,
    val channelNo: String,
    val senderId: String,
    val senderName: String?,
    val senderAvatar: String?,
    val type: String,
    val title: String?,
    val summary: String?,
    val content: String?,
    val level: String,
    val url: String?,
    val mediaStatus: String?,
    val media: List<MediaSlot>,
    val notify: Boolean,
    val createdAt: Long,
    /**
     * §5.3：单帧超 frame-max-bytes 时服务端削正文并置位。这个标记必须落库，
     * 否则本机显示的是一条被削过的正文，用户只会以为"它本来就这么多"。
     */
    val truncated: Boolean,
) {
    /** 会话行显示名：优先服务端给的 sender_name，缺省回落到 sender_id。 */
    val displayName: String get() = senderName?.takeIf { it.isNotBlank() } ?: senderId
    /** 有多少个媒体槽需要渲染。 */
    val mediaCount: Int get() = media.size
}

/** §4.5 media_ready / media_failed：异步抓取完成后回填某条消息的媒体终态。 */
data class MediaReadyFrame(
    val msgId: String,
    val seq: Long,
    val status: String,
    val media: List<MediaSlot>,
    /** t=media_failed 与 t=media_ready 的结构完全一致，只有这一位不同：客户端要落到"已失效"而不是"还在转"。 */
    val failed: Boolean,
) {
    /**
     * 这条一共有几张图。帧里的 media 只带**本次到终态的那几槽**（服务端合并窗口到期后按增量补发），
     * 所以不能取 media.size：三张图的消息会先收到两张、再收到"只有一张"的补发，
     * 照 size 落库就把九宫格缩成了一格。按见过的最大槽号算跨度。
     */
    val slotSpan: Int get() = maxOf(media.size, (media.maxOfOrNull { it.idx } ?: -1) + 1)
}

/** §5.3 config：变更原因用 §5.3 的字符串，channelNo/deviceId 按原因可缺省。 */
data class ConfigFrame(
    val reason: String,
    val channelNo: String?,
    val deviceId: String?,
)

data class ResumeGap(val missingFrom: Long, val latestSeq: Long)

/** §5.3 read：他端把某会话读到 last_read_seq，本端据此即时消红点（D9 多端同步）。 */
data class ReadFrame(
    val channelNo: String,
    val senderId: String,
    val lastReadSeq: Long,
    val deviceId: String?,
)

/** §5.3 tombstone：删除同步。id 定位单条；缺 id 时用 scope(channel_no, sender_id) 清整会话。 */
data class TombstoneFrame(
    val msgId: String?,
    val channelNo: String?,
    val senderId: String?,
    val deletedAt: Long,
)

internal fun JsonNode.str(field: String): String? = path(field).asText(null)
internal fun JsonNode.long(field: String, fallback: Long = 0L): Long =
    path(field).asLong(fallback)

internal fun JsonNode.boolean(field: String, fallback: Boolean): Boolean =
    path(field).asBoolean(fallback)

fun JsonNode.toHello(): HelloFrame? = if (path("t").asText() != Wire.T_HELLO) null else HelloFrame(
    serverTime = long("server_time"),
    heartbeatSec = path("heartbeat").asInt(25),
    serverPubFp = str("server_pub_fp").orEmpty(),
    nonceB64 = str("nonce").orEmpty(),
)

fun JsonNode.toKx(): KxFrame? = if (path("t").asText() != Wire.T_KX) null else KxFrame(
    serverPubB64 = str("server_pub").orEmpty(),
    ephPubB64 = str("eph_pub").orEmpty(),
    sigB64 = str("sig").orEmpty(),
    epoch = path("epoch").asInt(0),
)

fun JsonNode.toEnc(): EncFrame? = if (path("t").asText() != Wire.T_ENC) null else EncFrame(
    index = long("n"),
    dataB64 = str("data").orEmpty(),
)

fun JsonNode.toMessage(): MessageFrame {
    return MessageFrame(
        seq = long("seq"),
        msgId = str("id").orEmpty(),
        channelNo = str("channel_no").orEmpty(),
        senderId = str("sender_id").orEmpty(),
        senderName = str("sender_name"),
        senderAvatar = str("sender_avatar"),
        type = str("type") ?: "text",
        title = str("title"),
        summary = str("summary"),
        // 历史项把正文放在 content，下发帧也是 content（§5.2/§5.3 同名）
        content = str("content"),
        level = str("level") ?: "info",
        url = str("url"),
        // media_status 只在历史项里出现；实时帧靠 media[] 每项各自的 status 判断
        mediaStatus = str("media_status"),
        media = path("media").toMediaSlots(),
        notify = boolean("notify", true),
        createdAt = long("created_at"),
        truncated = boolean("truncated", false),
    )
}

/** media 是数组或缺省；逐项映射，字段名与 pbcore MediaSlot 对齐。 */
internal fun JsonNode.toMediaSlots(): List<MediaSlot> {
    if (!isArray) return emptyList()
    return mapNotNull { n ->
        if (!n.isObject) return@mapNotNull null
        MediaSlot(
            idx = n.path("idx").asInt(0),
            status = n.path("status").asText("pending"),
            mime = n.path("mime").asText(null),
            size = n.path("size").asLong(0L),
            w = if (n.hasNonNull("w")) n.path("w").asInt() else null,
            h = if (n.hasNonNull("h")) n.path("h").asInt() else null,
        )
    }
}

/** §4.5 media_ready / media_failed：id 定位消息，回填媒体终态与整体 status（ready/failed/deleted/purged）。 */
fun JsonNode.toMediaReady(): MediaReadyFrame? {
    val t = path("t").asText()
    if (t != Wire.T_MEDIA_READY && t != Wire.T_MEDIA_FAILED) return null
    return MediaReadyFrame(
        msgId = str("id").orEmpty(),
        seq = long("seq"),
        // 服务端在 media_failed 里给的 status 也是 failed，但缺省时按帧类型补，别让它悬在 pending
        status = str("status") ?: if (t == Wire.T_MEDIA_FAILED) "failed" else "ready",
        media = path("media").toMediaSlots(),
        failed = t == Wire.T_MEDIA_FAILED,
    )
}

/** §5.3 config：reason 决定客户端刷哪一面；channel_no / device_id 按原因可缺省。 */
fun JsonNode.toConfig(): ConfigFrame? =
    if (path("t").asText() != Wire.T_CONFIG) null else ConfigFrame(
        reason = str("reason").orEmpty(),
        channelNo = str("channel_no")?.takeIf { it.isNotBlank() },
        deviceId = str("device_id")?.takeIf { it.isNotBlank() },
    )

fun JsonNode.toResumeGap(): ResumeGap = ResumeGap(
    missingFrom = long("missing_from"),
    latestSeq = long("latest_seq"),
)

fun JsonNode.toRead(): ReadFrame? = if (path("t").asText() != Wire.T_READ) null else ReadFrame(
    channelNo = str("channel_no").orEmpty(),
    senderId = str("sender_id").orEmpty(),
    lastReadSeq = long("last_read_seq"),
    deviceId = str("device_id"),
)

fun JsonNode.toTombstone(): TombstoneFrame? =
    if (path("t").asText() != Wire.T_TOMBSTONE) null else {
        val scope = path("scope")
        TombstoneFrame(
            msgId = str("id")?.takeIf { it.isNotBlank() },
            channelNo = str("channel_no") ?: scope.path("channel_no").asText(null),
            senderId = str("sender_id") ?: scope.path("sender_id").asText(null),
            deletedAt = long("deleted_at"),
        )
    }

/** 出站 JSON 只用树模型构造，理由见文件头。 */
object JsonNodeFactory {
    fun newObject(): ObjectNode = Json.MAPPER.createObjectNode()
}
