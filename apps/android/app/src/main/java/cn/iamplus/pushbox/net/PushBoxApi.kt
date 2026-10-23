package cn.iamplus.pushbox.net

import cn.iamplus.pushbox.data.Identity
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.pushbox.core.crypto.B64
import dev.pushbox.core.crypto.Ed25519
import dev.pushbox.core.crypto.FrameChannel
import dev.pushbox.core.crypto.SignatureTargets
import dev.pushbox.core.crypto.X25519
import dev.pushbox.core.protocol.Json
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * §5.2 设备侧 REST：请求要 Bearer + Ed25519 签名，响应是一回合密封盒（§4.3），
 * 所以"发请求"和"解响应"必须成对出现，合并成一个类就不会有哪个调用忘了 unseal。
 */
class PushBoxApi(private val baseUrl: String, private val client: OkHttpClient) {

    /** register/join 是开放接口（只受 IP 限流），此时还没有 device_id/token，签名头一律不发。 */
    suspend fun register(encPrivate: ByteArray, signPrivate: ByteArray, name: String,
        groupSecret: String?): Enrolled {
        val body = JsonNodeFactory.newObject()
        body.put("enc_pubkey", B64.enc(X25519.publicKeyOf(encPrivate)))
        body.put("sign_pubkey", B64.enc(Ed25519.fromPrivateSeed(signPrivate).publicKey()))
        body.put("name", name)
        body.put("platform", "android")
        if (groupSecret != null) body.put("group_secret", groupSecret)
        val path = if (groupSecret == null) "/api/v1/devices/register" else "/api/v1/devices/join"
        val node = exchange(path, "", null, encPrivate, Json.write(body).toByteArray(), "POST")
        return Enrolled(
            deviceId = node.path("device_id").asText(),
            groupId = node.path("group_id").asText(),
            token = node.path("token").asText(),
            recoveryCode = node.path("recovery_code").asText(null),
            serverPubFp = node.path("server_pub_fp").asText(null),
        )
    }

    /** §5.2 历史分页：after_seq=0 就是重装后的全量恢复（验收③）。 */
    suspend fun history(identity: Identity, afterSeq: Long, limit: Int = 200): HistoryPage {
        val query = "after_seq=$afterSeq&limit=$limit"
        val node = exchange("/api/v1/messages", query, identity, identity.encPrivate,
            ByteArray(0), "GET")
        val items = node.path("messages").mapNotNull { it.takeIf { n -> n.isObject }?.toMessage() }
        return HistoryPage(items, node.path("latest_seq").asLong(),
            node.path("has_more").asBoolean(false))
    }

    /** §5.2 频道管理：没有 Web 控制台（D28），App 里建频道才能拿到 channel + secret。 */
    suspend fun createChannel(identity: Identity, name: String): Channel {
        val body = JsonNodeFactory.newObject()
        body.put("name", name)
        val node = exchange("/api/v1/channels", "", identity, identity.encPrivate,
            Json.write(body).toByteArray(), "POST")
        return Channel(node.path("channel_no").asText(), node.path("secret").asText(),
            node.path("max_level").asText("info"))
    }

    /**
     * §5.2 频道列表 + 本机过滤模式：一次 GET 同时拿到「已接入」标记与顶部的 subscribed_only。
     * 服务端只存密钥的 sha256，这里永远拿不到明文 secret
     * （ChannelService.view 从不放 secret 键），密钥仅创建/重置那一次可见。
     * 列表项与 create/rotate 用同一个 snake_case 视图，所以一次读全治理位，
     * 设置页不必再为每个频道单独发一次请求。
     */
    suspend fun channelListPage(identity: Identity): ChannelList {
        val node = exchange("/api/v1/channels", "", identity, identity.encPrivate,
            ByteArray(0), "GET")
        return ChannelList(
            node.path("channels").mapNotNull { n -> if (n.isObject) ChannelInfo.from(n) else null },
            node.path("subscribed_only").asInt(0) != 0,
        )
    }

    suspend fun listChannels(identity: Identity): List<ChannelInfo> =
        channelListPage(identity).channels

    /**
     * §5.2 接入频道：拿频道号 + 密钥登记到本机的订阅集。
     * 密钥在这里只是二次确认，不是读凭据（§4.1）——同 Space 的设备凭 token 一直读得到历史，
     * 所以"加入"改变的是这台设备收不收内容帧，而不是它有没有权限。
     * 跨 Space、已归档、未知号码都回 404；密钥不对回 401。
     */
    suspend fun joinChannel(identity: Identity, channelNo: String, secret: String) {
        val body = Json.MAPPER.createObjectNode()
            .put("channel_no", channelNo.trim()).put("secret", secret.trim())
        exchange("/api/v1/channels/join", "", identity, identity.encPrivate,
            Json.write(body).toByteArray(), "POST")
    }

    /** §5.2 取消接入：服务端幂等，没接入过也回成功。 */
    suspend fun leaveChannel(identity: Identity, channelNo: String) {
        val body = Json.MAPPER.createObjectNode().put("channel_no", channelNo.trim())
        exchange("/api/v1/channels/leave", "", identity, identity.encPrivate,
            Json.write(body).toByteArray(), "POST")
    }

    /**
     * §5.2 切换"只接收已接入频道"。这一档是设备级的，默认关：
     * 接入第一个频道不该顺手把其余频道静默掐断，所以它单独一个开关而不是从订阅集推导。
     */
    suspend fun setSubscriptionMode(identity: Identity, subscribedOnly: Boolean): Boolean {
        val body = Json.MAPPER.createObjectNode()
            .put("subscribed_only", if (subscribedOnly) 1 else 0)
        val node = exchange("/api/v1/channels/subscription-mode", "", identity,
            identity.encPrivate, Json.write(body).toByteArray(), "POST")
        return node.path("subscribed_only").asInt(0) != 0
    }

    /** §5.2 重置密钥：新明文密钥同样只回显这一次，返回体里带 secret。 */
    suspend fun rotateSecret(identity: Identity, channelNo: String): Channel {
        val node = exchange("/api/v1/channels/$channelNo/rotate-secret", "", identity,
            identity.encPrivate, ByteArray(0), "POST")
        return Channel(node.path("channel_no").asText(), node.path("secret").asText(), "")
    }

    /**
     * §5.2/§14 改频道治理位：PUT /api/v1/channels/{no}。
     * 只把非 null 的字段放进 body —— 服务端区分"没提交"与"提交 null"，
     * 多发一个 null 就会把那一列当成清空来处理。返回服务端真正认下后的频道视图。
     */
    suspend fun updateChannel(identity: Identity, channelNo: String,
        patch: ChannelPatch): ChannelInfo {
        val body = Json.MAPPER.createObjectNode()
        patch.writeTo(body)
        val node = exchange("/api/v1/channels/$channelNo", "", identity, identity.encPrivate,
            Json.write(body).toByteArray(), "PUT")
        return ChannelInfo.from(node)
    }

    /** §5.2 归档频道：DELETE /api/v1/channels/{no}，归档后该频道拒绝新投递（403 channel_locked）。 */
    suspend fun archiveChannel(identity: Identity, channelNo: String) {
        exchange("/api/v1/channels/$channelNo", "", identity, identity.encPrivate,
            ByteArray(0), "DELETE")
    }

    /** §5.2 频道改名：同一个 PUT 端点，body 只带 name。 */
    suspend fun renameChannel(identity: Identity, channelNo: String, name: String) {
        updateChannel(identity, channelNo, ChannelPatch(name = name))
    }

    /**
     * §5.2 换发新的对外频道号码：旧号即刻作废（第三方往旧号发＝401/404），历史随迁、secret 不变。
     * 返回新号码，调用方要把本机以旧号存的会话游标界面重拉一次。
     */
    suspend fun resetChannelNo(identity: Identity, channelNo: String): String {
        val node = exchange("/api/v1/channels/$channelNo/reset-channel-no", "", identity,
            identity.encPrivate, ByteArray(0), "POST")
        return node.path("channel_no").asText()
    }

    /** §14 容量报表：该频道在服务器上的消息数/字节数/图片数，用来判断"该不该清一清"。 */
    suspend fun channelCapacity(identity: Identity, channelNo: String): Capacity {
        val node = exchange("/api/v1/channels/$channelNo/capacity", "", identity,
            identity.encPrivate, ByteArray(0), "GET")
        return Capacity(
            totalMessages = node.path("total_messages").asLong(),
            totalTextBytes = node.path("total_text_bytes").asLong(),
            earliestMsgAt = node.path("earliest_msg_at").asLong(),
            totalImages = node.path("total_images").asLong(),
            totalImageBytes = node.path("total_image_bytes").asLong(),
        )
    }

    /** §14 白名单：列出该频道允许发送的 sender_id（含已停用的）。 */
    suspend fun listSenders(identity: Identity, channelNo: String): List<SenderEntry> {
        val node = exchange("/api/v1/channels/$channelNo/senders", "", identity,
            identity.encPrivate, ByteArray(0), "GET")
        return node.path("senders").mapNotNull { n ->
            if (!n.isObject) return@mapNotNull null
            SenderEntry(n.path("sender_id").asText(), n.path("enabled").asInt(1) != 0)
        }
    }

    /** §14 白名单：加一个或停用一个；enabled=false 是停用而不是删除。 */
    suspend fun putSender(identity: Identity, channelNo: String, senderId: String,
        enabled: Boolean) {
        val body = Json.MAPPER.createObjectNode()
            .put("sender_id", senderId).put("enabled", enabled)
        exchange("/api/v1/channels/$channelNo/senders", "", identity, identity.encPrivate,
            Json.write(body).toByteArray(), "POST")
    }

    /** §14 轮换恢复码：旧码即刻失效，新码只在这次响应里出现。 */
    suspend fun rotateRecoveryCode(identity: Identity): String {
        val node = exchange("/api/v1/devices/rotate-recovery-code", "", identity,
            identity.encPrivate, ByteArray(0), "POST")
        return node.path("recovery_code").asText()
    }

    /**
     * §5.3 取全文：实时帧被 frame-max-bytes 削过正文时（truncated=true），
     * 按 msg_id 从未截断的库里取回 content；取不到（离线/已删）返回 null，界面保留截断文本。
     */
    suspend fun fulltext(identity: Identity, msgId: String): MessageFrame? = try {
        val node = exchange("/api/v1/messages/${urlEncode(msgId)}", "", identity,
            identity.encPrivate, ByteArray(0), "GET")
        if (node.isObject) node.toMessage() else null
    } catch (_: Exception) {
        null
    }

    /**
     * §5.2 清空整个频道：DELETE /api/v1/messages?channel_no={no}（省略 sender_id＝整频道）。
     * 服务端一条 UPDATE 标完 + 广播一条 sender_id 为 null 的 scope tombstone，各端（含本机）据此清本地。
     */
    suspend fun clearChannel(identity: Identity, channelNo: String) {
        exchange("/api/v1/messages", "channel_no=$channelNo", identity, identity.encPrivate,
            ByteArray(0), "DELETE")
    }

    /**
     * §5.2/§7.3 清空单个会话：DELETE /api/v1/messages?channel_no={no}&sender_id={id}。
     * 服务端 tombstone 该会话并广播一条带 sender_id 的 scope tombstone，各端（含本机）据此清本地，
     * 他端同步隐藏、卸载重装也不再回放 —— 只清本地 dao 做不到这点。
     */
    suspend fun clearConversation(identity: Identity, channelNo: String, senderId: String) {
        val query = "channel_no=" + urlEncode(channelNo) + "&sender_id=" + urlEncode(senderId)
        exchange("/api/v1/messages", query, identity, identity.encPrivate,
            ByteArray(0), "DELETE")
    }

    /**
     * §5.2 设备列表（D26 上限 5 台）：GET /api/v1/devices 直接密封一个 DeviceView 数组，
     * 字段是 snake_case（device_id/created_at/last_seen_at/revoked_at/current）。
     */
    /** §7.4 服务端全文搜索命中：只回片段与定位信息，正文仍走本地缓存/历史。 */
    data class SearchHit(val msgId: String, val seq: Long, val channelNo: String,
        val senderId: String, val senderName: String, val type: String, val title: String?,
        val snippet: String, val level: String, val mediaStatus: String?, val createdAt: Long)

    /**
     * §7.4/§5.2 D19 GET /api/v1/search：正文不进本机缓存的历史由服务端用 ngram/LIKE 召回命中片段。
     * query 参数顺序固定为 q, channel_no, sender_id, since, limit，每个值都要百分号编码 ——
     * 签名 canonical 覆盖的是原始 query 串，与最终发出的必须逐字节一致，所以先编码再拼，
     * 不能把空格/中文原样塞进去（OkHttp 会二次规范化导致验签失败）。
     */
    suspend fun search(identity: Identity, q: String, channelNo: String? = null,
        senderId: String? = null, limit: Int = 50): List<SearchHit> {
        val parts = buildList {
            add("q=" + urlEncode(q))
            if (!channelNo.isNullOrEmpty()) add("channel_no=" + urlEncode(channelNo))
            if (!senderId.isNullOrEmpty()) add("sender_id=" + urlEncode(senderId))
            add("since=0")
            add("limit=$limit")
        }
        val node = exchange("/api/v1/search", parts.joinToString("&"), identity,
            identity.encPrivate, ByteArray(0), "GET")
        return node.path("results").mapNotNull { n ->
            if (!n.isObject) return@mapNotNull null
            SearchHit(
                msgId = n.path("id").asText(),
                seq = n.path("seq").asLong(),
                channelNo = n.path("channel_no").asText(),
                senderId = n.path("sender_id").asText(),
                senderName = n.path("sender_name").asText(""),
                type = n.path("type").asText("text"),
                title = n.path("title").asText(null),
                snippet = n.path("snippet").asText(""),
                level = n.path("level").asText("info"),
                mediaStatus = n.path("media_status").asText(null),
                createdAt = n.path("created_at").asLong(),
            )
        }
    }

    /** RFC 3986 查询段编码：只保留 unreserved，其余按 UTF-8 百分号编码（大写十六进制）。 */
    private fun urlEncode(value: String): String = buildString {
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val keep = (c in 65..90) || (c in 97..122) || (c in 48..57) ||
                c == 45 || c == 95 || c == 46 || c == 126
            if (keep) append(c.toChar()) else append("%" + c.toString(16).uppercase())
        }
    }

    suspend fun listDevices(identity: Identity): List<DeviceInfo> {
        val node = exchange("/api/v1/devices", "", identity, identity.encPrivate,
            ByteArray(0), "GET")
        return node.mapNotNull { d ->
            d.takeIf { it.isObject }?.let {
                DeviceInfo(it.path("device_id").asText(), it.path("name").asText(""),
                    it.path("platform").asText(""), it.path("created_at").asLong(0),
                    it.path("last_seen_at").asLong(0), it.path("revoked_at").asLong(0),
                    it.path("current").asBoolean(false))
            }
        }
    }

    /** §5.2 远程吊销：DELETE /api/v1/devices/{id}，服务端作废 token 并即时踢掉在线连接（验收④）。 */
    suspend fun revokeDevice(identity: Identity, deviceId: String) {
        exchange("/api/v1/devices/$deviceId", "", identity, identity.encPrivate,
            ByteArray(0), "DELETE")
    }

    /**
     * §4.5/§5.2 头像读接口：avatar_token = sha256hex(sender_id)，服务端代理回字节。
     * 返回三态而不是可空字节：服务端注释里写明"没转存成功与别的组的 token 共用 404"，
     * 所以 404 是一个**结论**（这个发送者就是没有头像，可以负缓存），
     * 而超时/5xx 只是**这次没取到**，缓存了就会把一次网络抖动固化成永久空白。
     */
    suspend fun fetchAvatar(identity: Identity, avatarToken: String): AvatarResult {
        return try {
            val path = "/api/v1/avatars/$avatarToken"
            val url = baseUrl.trimEnd('/') + path
            val nonce = SecureRandom().generateSeed(NONCE_BYTES)
            val ts = System.currentTimeMillis()
            val target = SignatureTargets.restAuth(identity.deviceId, "GET", path, "",
                null, ts, nonce)
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer ${identity.token}")
                .header("X-PB-Device", identity.deviceId)
                .header("X-PB-Ts", ts.toString())
                .header("X-PB-Nonce", B64.enc(nonce))
                .header("X-PB-Sig", B64.enc(Ed25519.sign(identity.signPrivate, target)))
                .get().build()
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful ->
                            response.body?.bytes()?.let { AvatarResult.Found(it) }
                                ?: AvatarResult.Unknown
                        response.code == 404 -> AvatarResult.None
                        else -> AvatarResult.Unknown
                    }
                }
            }
        } catch (_: Exception) {
            AvatarResult.Unknown
        }
    }

    /** 一台设备：revokedAt>0 表示已吊销；current 是本机，不提供吊销入口。 */
    data class DeviceInfo(val deviceId: String, val name: String, val platform: String,
        val createdAt: Long, val lastSeenAt: Long, val revokedAt: Long, val current: Boolean) {
        val revoked get() = revokedAt > 0
    }

    /**
     * §5.2/§4.4 已读游标上报（D9）：一次 PUT 把若干会话整段置已读，
     * 服务端写 read_state 后再向本空间其他设备广播 read 帧，红点即时消失。
     */
    suspend fun reportRead(identity: Identity, entries: List<ReadEntry>) {
        val body = Json.MAPPER.createArrayNode()
        entries.forEach { e ->
            val o = body.addObject()
            o.put("channel_no", e.channelNo)
            o.put("sender_id", e.senderId)
            o.put("last_read_seq", e.lastReadSeq)
        }
        exchange("/api/v1/state/read", "", identity, identity.encPrivate,
            Json.write(body).toByteArray(), "PUT")
    }

    /** 一个会话的已读游标：last_read_seq 即该会话已看到的最大 seq。 */
    data class ReadEntry(val channelNo: String, val senderId: String, val lastReadSeq: Long)

    /**
     * §4.5 取一张媒体：签名 GET /api/v1/media/{msgId}/{idx}?size=thumb|full，
     * 服务端校验设备归属后自己把对象从桶里取回来（pb.s3.object-encrypt=sse-c 时带着客户钥解密）
     * 再以 200 返回字节，所以设备不需要能连到 RustFS 的地址。
     * 响应不是密封盒（是图片流），所以不走 exchange；返回的字节按 media_cache/ 的键原样落盘即可。
     */
    suspend fun fetchMedia(identity: Identity, msgId: String, idx: Int, thumb: Boolean): ByteArray {
        val path = "/api/v1/media/$msgId/$idx"
        val query = if (thumb) "size=thumb" else "size=full"
        val url = baseUrl.trimEnd('/') + path + "?" + query
        val nonce = SecureRandom().generateSeed(NONCE_BYTES)
        val ts = System.currentTimeMillis()
        val target = SignatureTargets.restAuth(identity.deviceId, "GET", path, query,
            null, ts, nonce)
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${identity.token}")
            .header("X-PB-Device", identity.deviceId)
            .header("X-PB-Ts", ts.toString())
            .header("X-PB-Nonce", B64.enc(nonce))
            .header("X-PB-Sig", B64.enc(Ed25519.sign(identity.signPrivate, target)))
            .get().build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ApiError(response.code, "media ${response.code}")
                response.body?.bytes() ?: throw ApiError(response.code, "empty media body")
            }
        }
    }

    /**
     * §5.2/§4.4 删除消息（D13）：服务端落 tombstone、删 RustFS 对象，再向其他设备广播 tombstone 帧。
     * 客户端先做乐观本地隐藏（见 SyncEngine/DAO），这里的失败不影响本机即时生效。
     */
    suspend fun deleteMessage(identity: Identity, msgId: String) {
        exchange("/api/v1/messages/$msgId", "", identity, identity.encPrivate,
            ByteArray(0), "DELETE")
    }

    private suspend fun exchange(path: String, query: String, identity: Identity?,
        unsealKey: ByteArray, bodyBytes: ByteArray, method: String): JsonNode {
        val url = buildString {
            append(baseUrl.trimEnd('/'))
            append(path)
            if (query.isNotEmpty()) append('?').append(query)
        }
        val builder = Request.Builder().url(url)
        if (identity != null) {
            val nonce = SecureRandom().generateSeed(NONCE_BYTES)
            val ts = System.currentTimeMillis()
            // canonical 里的 query 必须与实际发送的完全一致，所以两边共用同一个变量（§5.2）
            val target = SignatureTargets.restAuth(identity.deviceId, method, path,
                query.ifEmpty { null },
                (if (bodyBytes.isEmpty()) null else bodyBytes), ts, nonce)
            builder.header("Authorization", "Bearer ${identity.token}")
                .header("X-PB-Device", identity.deviceId)
                .header("X-PB-Ts", ts.toString())
                .header("X-PB-Nonce", B64.enc(nonce))
                .header("X-PB-Sig", B64.enc(Ed25519.sign(identity.signPrivate, target)))
        }
        // 重置密钥/换发频道号/轮换恢复码这三条是"无体 POST"，而 OkHttp 对请求体的有无管得很死：
        // POST/PUT/PATCH 给 null 抛"must have a request body."，GET/HEAD 给非 null 抛"must not have one."
        // （点一下按钮只弹这行红字，功能整条不通）。所以按方法决定要不要那个长度 0 的体。
        // 签名 canonical 仍按"无体"算（上面 bodyBytes 为空时传 null），与服务端的 require(http, null)
        // 对得上，因此这只是为了过 OkHttp 的入参校验，不改变被签名的字节。
        val needsBody = method == "POST" || method == "PUT" || method == "PATCH"
        val requestBody = if (needsBody) bodyBytes.toRequestBody(JSON) else null
        builder.method(method, requestBody)
        // execute() 是同步阻塞调用，必须切到 IO 线程；否则从 Compose 主线程调过来会抛
        // NetworkOnMainThreadException（register/createChannel 都在 rememberCoroutineScope 里跑）。
        return withContext(Dispatchers.IO) {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiError(response.code, text)
                val envelope = Wire.parse(text) ?: throw ApiError(response.code, text)
                // 每个 /api/v1 响应都是密封盒；没有 eph_pub 说明对端不是 PushBox
                if (!envelope.has("eph_pub")) throw ApiError(response.code, "响应不是密封盒")
                Unseal.open(envelope, unsealKey)
            }
        }
    }

    class ApiError(val code: Int, val body: String) : Exception(formatErrorMessage(code, body)) {
        companion object {
            /** 从服务端响应的 JSON 中提取 hint 字段，如果没有则返回通用错误信息 */
            private fun formatErrorMessage(code: Int, body: String): String {
                return try {
                    val node = Json.MAPPER.readTree(body)
                    val hint = node.path("hint").asText(null)
                    if (!hint.isNullOrBlank()) hint
                    else "请求失败（HTTP $code）"
                } catch (_: Exception) {
                    // 如果不是 JSON 或解析失败，返回原始 body
                    if (body.isNotBlank()) body.take(200) else "网络请求失败"
                }
            }
        }
    }

    data class Enrolled(
        val deviceId: String,
        val groupId: String,
        val token: String,
        val recoveryCode: String?,
        val serverPubFp: String?,
    )

    data class HistoryPage(val messages: List<MessageFrame>, val latestSeq: Long,
        val hasMore: Boolean)

    data class Channel(val channelNo: String, val secret: String, val maxLevel: String)

    /**
     * 列表项＝服务端 ChannelService.view 的完整快照：号码、名字与全部治理位。
     * 位宽按服务端 col 的 int/long 走，daily_bytes_limit 是 BIGINT 所以是 Long。
     * {@code subscribed} 是服务端在 deviceId 已知时恒给的接入标记；缺失只可能来自老服务端，
     * 按"未接入"读比按"未知"渲染出一个空徽章要诚实。
     */
    data class ChannelInfo(
        val channelNo: String,
        val name: String,
        val maxLevel: String,
        val maxImages: Int,
        val rateLimit: Int,
        val maxBodyBytes: Int,
        val strictAuth: Boolean,
        val muted: Boolean,
        val dailyMsgLimit: Int,
        val dailyImageLimit: Int,
        val dailyBytesLimit: Long,
        val senderWhitelistMode: Boolean,
        val subscribed: Boolean,
    ) {
        companion object {
            fun from(n: JsonNode) = ChannelInfo(
                channelNo = n.path("channel_no").asText(""),
                name = n.path("name").asText(""),
                maxLevel = n.path("max_level").asText("info"),
                maxImages = n.path("max_images").asInt(4),
                rateLimit = n.path("rate_limit").asInt(60),
                maxBodyBytes = n.path("max_body_bytes").asInt(32768),
                strictAuth = n.path("strict_auth").asInt(0) != 0,
                muted = n.path("muted").asInt(0) != 0,
                dailyMsgLimit = n.path("daily_msg_limit").asInt(0),
                dailyImageLimit = n.path("daily_image_limit").asInt(0),
                dailyBytesLimit = n.path("daily_bytes_limit").asLong(0L),
                senderWhitelistMode = n.path("sender_whitelist_mode").asInt(0) != 0,
                subscribed = n.path("subscribed").asBoolean(false),
            )
        }
    }

    /** §5.2 GET /channels 的一页：频道 + 本机的"只接收已接入频道"开关当前值。 */
    data class ChannelList(val channels: List<ChannelInfo>, val subscribedOnly: Boolean)

    /** PUT 补丁：null 一律不写进 body（服务端把"没提交"与"提交 null"当成两件事）。 */
    data class ChannelPatch(
        val name: String? = null,
        val maxLevel: String? = null,
        val maxImages: Int? = null,
        val rateLimit: Int? = null,
        val maxBodyBytes: Int? = null,
        val strictAuth: Boolean? = null,
        val muted: Boolean? = null,
        val dailyMsgLimit: Int? = null,
        val dailyImageLimit: Int? = null,
        val dailyBytesLimit: Long? = null,
        val senderWhitelistMode: Boolean? = null,
    ) {
        fun writeTo(body: ObjectNode) {
            name?.let { body.put("name", it) }
            maxLevel?.let { body.put("max_level", it) }
            maxImages?.let { body.put("max_images", it) }
            rateLimit?.let { body.put("rate_limit", it) }
            maxBodyBytes?.let { body.put("max_body_bytes", it) }
            strictAuth?.let { body.put("strict_auth", if (it) 1 else 0) }
            muted?.let { body.put("muted", if (it) 1 else 0) }
            dailyMsgLimit?.let { body.put("daily_msg_limit", it) }
            dailyImageLimit?.let { body.put("daily_image_limit", it) }
            dailyBytesLimit?.let { body.put("daily_bytes_limit", it) }
            senderWhitelistMode?.let { body.put("sender_whitelist_mode", if (it) 1 else 0) }
        }
    }

    /** §14 频道容量报表。 */
    data class Capacity(val totalMessages: Long, val totalTextBytes: Long,
        val earliestMsgAt: Long, val totalImages: Long, val totalImageBytes: Long)

    /** §14 白名单条目。 */
    data class SenderEntry(val senderId: String, val enabled: Boolean)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val NONCE_BYTES = 16
    }
}

/**
 * §4.5 头像取的三种结局。分这么细是因为只有 None 值得被负缓存：
 * 服务端把"没转存成功"与"别的组的 token"都回成 404，那是一个结论；
 * 而超时/5xx/解密失败只说明这一次没成，缓存它等于把一次抖动固化成永久空白。
 */
sealed interface AvatarResult {
    data class Found(val bytes: ByteArray) : AvatarResult
    data object None : AvatarResult
    data object Unknown : AvatarResult
}

/**
 * §4.3 一回合密封盒：salt = sha256(server_eph_pub)，
 * K = HKDF(X25519(device_priv, eph_pub), salt, "pushbox-frame-v1")，帧序号恒为 0。
 * 与服务端 DeviceGuard.seal 逐字节对应。
 */
object Unseal {

    fun open(envelope: JsonNode, encPrivate: ByteArray): JsonNode {
        val ephPub = B64.tryDec(envelope.path("eph_pub").asText(null))
            ?: throw IllegalArgumentException("密封盒缺 eph_pub")
        val salt = B64.tryDec(envelope.path("salt").asText(null))
            ?: throw IllegalArgumentException("密封盒缺 salt")
        val shared = X25519.agree(encPrivate, ephPub)
        val channel = FrameChannel(FrameChannel.sessionKey(shared, salt), System.currentTimeMillis())
        val plain = channel.open(envelope.path("n").asLong(0), envelope.path("data").asText())
            ?: throw IllegalStateException("sealed box 解密失败")
        return Wire.parse(String(plain, Charsets.UTF_8))
            ?: throw IllegalStateException("sealed box 内容不是 JSON")
    }
}
