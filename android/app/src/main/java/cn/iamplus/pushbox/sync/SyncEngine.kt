package cn.iamplus.pushbox.sync

import cn.iamplus.pushbox.data.Identity
import cn.iamplus.pushbox.data.IdentityStore
import cn.iamplus.pushbox.data.MessageRow
import cn.iamplus.pushbox.data.PushBoxDao
import cn.iamplus.pushbox.net.MessageFrame
import cn.iamplus.pushbox.net.MediaReadyFrame
import cn.iamplus.pushbox.net.toSlotBlob
import cn.iamplus.pushbox.net.ConfigFrame
import cn.iamplus.pushbox.net.PushBoxApi
import cn.iamplus.pushbox.net.PushConnection
import cn.iamplus.pushbox.net.ReadFrame
import cn.iamplus.pushbox.net.ResumeGap
import cn.iamplus.pushbox.net.TombstoneFrame
import cn.iamplus.pushbox.notify.Notifier
import cn.iamplus.pushbox.ui.ImageCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * §6.2 接收 → 入库 → 通知的唯一入口，并持有重连状态机（§7.2：1s→2s→…→60s）。
 *
 * 帧处理全部排队到一个 IO 作用域：OkHttp 回调线程不能直接写 Room，
 * 而且必须先入库再发通知，否则点开通知却查不到消息。
 */
class SyncEngine(
    private val dao: PushBoxDao,
    private val store: IdentityStore,
    private val notifier: Notifier,
    /** 服务器地址是可配的（§11.1 联调用局域网 IP），所以按 identity 的 baseUrl 现取。 */
    private val apiFactory: (String) -> PushBoxApi,
) {

    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Online(val lastSeq: Long) : State
        data class Offline(val reason: String, val retryInMillis: Long) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<State>(State.Idle)
    val current: StateFlow<State> = state.asStateFlow()

    private var loop: Job? = null
    private var live: PushConnection? = null

    fun start(identity: Identity) {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop(identity) }
    }

    /** 网络刚恢复时立刻试一次，不等退避计时器（§7.2）。 */
    fun retryNow(identity: Identity) {
        if (state.value is State.Online) return
        loop?.cancel()
        start(identity)
    }

    fun stop() {
        loop?.cancel()
        loop = null
        live?.disconnect()
        live = null
        state.value = State.Idle
    }

    private suspend fun runLoop(initial: Identity) {
        var backoff = BACKOFF_MIN_MILLIS
        while (scope.isActive) {
            // 每次重连都重新读一次身份：since_seq 必须用库里最新的游标，否则断线期间的消息会被漏拉
            val identity = store.load() ?: initial
            state.value = State.Connecting
            val connectedAt = System.currentTimeMillis()
            val reason = connectAndAwait(identity)
            if (!scope.isActive) return
            // 连接活得比一个退避周期长，就不把这次断开当成故障继续指数等待
            backoff = if (System.currentTimeMillis() - connectedAt > backoff * 2) BACKOFF_MIN_MILLIS
            else minOf(backoff * 2, BACKOFF_MAX_MILLIS)
            state.value = State.Offline(reason, backoff)
            delay(backoff)
        }
    }

    /** 挂起直到连接断开，返回断开原因。 */
    private suspend fun connectAndAwait(identity: Identity): String {
        var watchdog: Job? = null
        val reason = suspendCancellableCoroutine<String> { continuation ->
            val connection = PushConnection(identity.baseUrl, identity,
                object : PushConnection.Listener {
                    override fun onMessage(message: MessageFrame) {
                        scope.launch { ingestNow(message) }
                    }

                    override fun onResumeGap(gap: ResumeGap) {
                        scope.launch { backfillFromRest(identity, gap.missingFrom) }
                    }

                    override fun onResumeCaughtUp() {
                        scope.launch { confirmCaughtUp() }
                    }

                    override fun onRead(read: ReadFrame) {
                        scope.launch { applyRead(read) }
                    }

                    override fun onTombstone(tombstone: TombstoneFrame) {
                        scope.launch { applyTombstone(tombstone) }
                    }

                    override fun onMediaReady(ready: MediaReadyFrame) {
                        scope.launch { applyMediaReady(ready) }
                    }

                    override fun onConfig(config: ConfigFrame) {
                        scope.launch { applyConfig(identity, config) }
                    }

                    override fun onConnected() {
                        state.value = State.Online(identity.lastSeq)
                    }

                    override fun onDisconnected(reason: String) {
                        watchdog?.cancel()
                        if (continuation.isActive) continuation.resumeWith(Result.success(reason))
                    }

                    override fun onPinServerFingerprint(fp: String) {
                        scope.launch { store.saveServerPubFp(fp) }
                    }
                }).apply { sinceSeq = identity.lastSeq }
            live = connection
            connection.connect()
            // 心跳看护与连接同生共死：放在 connect() 之后而不是 onConnected 里，
            // 是因为监听器对象是 connection 构造参数的一部分，回指它会成为初始化内的前向引用
            watchdog = scope.launch { keepAlive(connection) }
            continuation.invokeOnCancellation {
                watchdog?.cancel()
                connection.disconnect()
            }
        }
        watchdog?.cancel()
        return reason
    }

    /**
     * §5.3 客户端心跳：按 hello 给的间隔 ping，并把"多久没听到对面说话"当成链路死活来看。
     *
     * 服务端也会主动 ping，所以保活从来不是问题；问题在反方向——移动网络断得不留痕迹时
     * TCP 处于半开，OkHttp 既不报 onFailure 也收不到帧，`readTimeout=0` 意味着它永远不超时，
     * 于是本机顶栏挂着"已连接"、消息却再也不来，直到用户手动杀进程。
     * 两个心跳都没等到对面任何东西就判死，走同一条重连路径（带 since_seq 补拉）。
     */
    private suspend fun keepAlive(connection: PushConnection) {
        val interval = connection.heartbeatSeconds.coerceAtLeast(1) * 1000L
        while (scope.isActive) {
            delay(interval)
            connection.ping()
            if (connection.millisSinceInbound() > interval * STALE_HEARTBEATS) {
                connection.failStale()
                return
            }
        }
    }

    /**
     * §5.3 resume_ok：服务端把 since_seq 之后的都补发完了，本端把游标与状态坐实。
     * 光有 onConnected 不够——那时一个字节都还没验过；也不能等第一条 message，
     * 一个从没发过消息的空间可能永远没有第一条（Windows 侧同一句结论：顶栏会停在"连接中"）。
     */
    private suspend fun confirmCaughtUp() {
        val cursor = maxOf(dao.lastAppliedSeq() ?: 0L, store.load()?.lastSeq ?: 0L)
        store.saveLastSeq(cursor)
        state.value = State.Online(cursor)
    }

    private suspend fun ingestNow(message: MessageFrame, allowNotify: Boolean = true) {
        // 通知只看"这条是不是首次入库"：补拉与实时下发是同一消息的两个副本，
        // msg_id 去重失败就不通知，这正是验收②要的"补齐且无重复"
        if (dao.apply(message.toRow(), message.displayName)) {
            val cursor = maxOf(dao.lastAppliedSeq() ?: 0L, message.seq)
            store.saveLastSeq(cursor)
            // 免打扰（§7.3）：照常入库，只是不弹通知；静音状态来自会话行
            if (allowNotify && message.notify &&
                dao.isMuted(message.channelNo, message.senderId) != true) {
                notifier.post(message,
                    lockPrivate = dao.isLockPrivate(message.channelNo, message.senderId) == true)
            }
        }
        state.value = State.Online(message.seq)
    }

    /** 验收③：重装后凭恢复码 join，再用 after_seq=0 分页把历史全部拉回来。 */
    suspend fun restoreHistory(identity: Identity): Int {
        var after = 0L
        var total = 0
        while (true) {
            val page = api(identity).history(identity, after)
            // 整段历史一次性入库属于"恢复"，逐条弹通知会把通知栏刷爆（§7.3）
            page.messages.forEach { ingestNow(it, allowNotify = false) }
            total += page.messages.size
            val maxSeq = page.messages.maxOfOrNull { it.seq } ?: break
            after = maxSeq
            if (!page.hasMore || maxSeq >= page.latestSeq) break
        }
        return total
    }

    /**
     * §7.2 D18 WorkManager 兜底：常驻被杀时，用 REST 把本地游标之后的缺口补上。
     * 走的是与 resume_gap 相同的分页逻辑，首次入库才通知，所以补齐既不漏也不重（验收②）。
     */
    suspend fun catchUpFromCursor(identity: Identity) {
        val after = dao.lastAppliedSeq() ?: identity.lastSeq
        backfillFromRest(identity, after + 1)
    }

    /** resume_gap 说明落后太多（>2000），帧补拉会被服务端拒绝，只能走 REST（§5.3）。 */
    private suspend fun backfillFromRest(identity: Identity, missingFrom: Long) {
        var after = missingFrom - 1
        while (true) {
            val page = api(identity).history(identity, after)
            page.messages.forEach { ingestNow(it) }
            val maxSeq = page.messages.maxOfOrNull { it.seq } ?: break
            after = maxSeq
            if (!page.hasMore) break
        }
    }

    /** §5.3 read 帧：本端把对应会话红点清掉；自己上报的回环忽略，免得抵消其他端的游标。 */
    private suspend fun applyRead(read: ReadFrame) {
        if (read.channelNo.isEmpty() || read.senderId.isEmpty()) return
        if (read.deviceId != null && read.deviceId == store.load()?.deviceId) return
        dao.markConversationRead(read.channelNo, read.senderId)
    }

    /** §5.3 tombstone 帧：单条按 id 删并重算会话；整会话按 scope 清干净（D13）。 */
    private suspend fun applyTombstone(frame: TombstoneFrame) {
        val id = frame.msgId
        if (id != null) {
            dao.tombstoneMessage(id)?.let { ImageCache.evictMessage(it.msgId, it.mediaCount) }
            return
        }
        val channel = frame.channelNo
        val sender = frame.senderId
        when {
            channel != null && sender != null -> {
                dao.conversationMedia(channel, sender)
                    .forEach { ImageCache.evictMessage(it.msgId, it.mediaCount) }
                dao.tombstoneConversation(channel, sender)
            }
            // sender_id 为 null＝清空整个频道
            channel != null -> {
                dao.channelMedia(channel).forEach { ImageCache.evictMessage(it.msgId, it.mediaCount) }
                dao.tombstoneChannel(channel)
            }
        }
    }

    /**
     * §4.5 media_ready / media_failed：把该条的媒体终态写回本地行，会话详情下次组合刷新即出图。
     * 消息可能尚未入库（补拉与实时竞态），setMedia 命中 0 行即忽略，补拉路径会带上 media[]。
     */
    private suspend fun applyMediaReady(frame: MediaReadyFrame) {
        if (frame.msgId.isEmpty()) return
        dao.setMedia(frame.msgId, frame.status, frame.slotSpan, frame.media.toSlotBlob())
    }

    private fun api(identity: Identity) = apiFactory(identity.baseUrl)

    /**
     * §5.3 config：只有换发频道号码需要动本地数据，其余变更（密钥/治理位/白名单/设备名单/恢复码）
     * 服务端不改本机已有消息，"我的"页每次进都会重拉，收到帧即返回即可。
     *
     * 号码重置的麻烦在于服务端换了 channel_no 却没换 msg_id：旧号下的行既不会被新号覆盖
     * （insertIgnore 撞主键直接丢弃），也不会自己消失，界面上就变成"同一批消息两条会话"。
     * 帧里只带新号，所以按"本地有、服务端列表里没有"来认定它已作废，整频道丢弃后从 0 重拉。
     * 代价是该频道下的本地会话设置（置顶/改名/头像）跟着没了——频道既然改了对外号码，
     * 这些以旧号为键的设置本来也就对不上新的会话线。
     */
    private suspend fun applyConfig(identity: Identity, config: ConfigFrame) {
        if (config.reason != REASON_CHANNEL_NO_RESET) return
        val live = runCatching { api(identity).listChannels(identity).map { it.channelNo }.toSet() }
            .getOrNull() ?: return
        dao.localChannelNos().filter { it !in live }.forEach { dao.tombstoneChannel(it) }
        store.saveLastSeq(0L)
        restoreHistory(identity)
    }

    private fun MessageFrame.toRow() = MessageRow(
        msgId = msgId,
        seq = seq,
        channelNo = channelNo,
        senderId = senderId,
        msgType = type,
        title = title,
        summary = summary,
        body = content,
        level = level,
        linkUrl = url,
        mediaStatus = mediaStatus,
        mediaCount = mediaCount,
        mediaSlots = media.toSlotBlob(),
        createdAt = createdAt,
        senderAvatar = senderAvatar,
        truncated = truncated,
    )

    companion object {
        private const val BACKOFF_MIN_MILLIS = 1_000L
        private const val BACKOFF_MAX_MILLIS = 60_000L
        private const val REASON_CHANNEL_NO_RESET = "channel_no_reset"
        /** 连着几个心跳没收到对面任何帧就判链路已死。 */
        private const val STALE_HEARTBEATS = 2L
    }
}
