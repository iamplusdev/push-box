package cn.iamplus.pushbox.net

import com.fasterxml.jackson.databind.JsonNode
import cn.iamplus.pushbox.data.Identity
import dev.pushbox.core.crypto.B64
import dev.pushbox.core.crypto.Ed25519
import dev.pushbox.core.crypto.FrameChannel
import dev.pushbox.core.crypto.SignatureTargets
import dev.pushbox.core.crypto.X25519
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * §5.3 长连接客户端：hello → auth → kx → 之后一律 {t:"enc",n,data}。
 *
 * 帧序号连续性由 pbcore 的 FrameChannel 把关：服务端每个连接都新建一个 channel 从 0 计数，
 * 所以这里也必须在每次握手时新建，跨连接复用会把 open() 全部判成重放。
 */
class PushConnection(
    private val baseUrl: String,
    private val identity: Identity,
    private val listener: Listener,
) {

    interface Listener {
        fun onMessage(message: MessageFrame)
        fun onResumeGap(gap: ResumeGap)
        /**
         * §5.3 resume_ok：服务端的补发批次取空了，本地游标到此可以当"已追平"用。
         * 没有正文，纯状态帧，所以只有"重连后第一条"有意义——后续实时流里不会再出现。
         */
        fun onResumeCaughtUp()
        /** §5.3 他端已读回传：本端据此即时消红点（D9 多端同步）。 */
        fun onRead(read: ReadFrame)
        /** §5.3 删除同步：本端立刻隐藏该消息/会话（D13）。 */
        fun onTombstone(tombstone: TombstoneFrame)
        /** §4.5 media_ready / media_failed：异步抓图完成后回填某条消息的媒体终态。 */
        fun onMediaReady(ready: MediaReadyFrame)
        /** §5.3 config：凭据/频道/设备名单变了，本端立刻重拉对应界面（离线的一端靠重连补拉兜底）。 */
        fun onConfig(config: ConfigFrame)
        fun onConnected()
        fun onDisconnected(reason: String)
        /** 首连固定服务端身份公钥指纹（§4.3 TOFU）。 */
        fun onPinServerFingerprint(fp: String)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var channel: FrameChannel? = null
    private var helloNonce: ByteArray = ByteArray(0)
    private var heartbeatSec = DEFAULT_HEARTBEAT_SEC

    /** 会话游标即 §5.3 的 since_seq，断线重连时用它补拉（验收②）。 */
    @Volatile
    var sinceSeq: Long = 0L

    /**
     * 收到服务端任何一帧的时刻（ping/pong/业务帧都算）。心跳看护用它判"半开连接"：
     * TCP 看着还在、OkHttp 也不报 onFailure，但对端早就不说话了——
     * 没有这个计时器，本机就会一直挂着"已连接"却再也收不到消息（验收②最阴的一种失败）。
     */
    @Volatile
    private var lastInboundMillis: Long = System.currentTimeMillis()

    val heartbeatSeconds: Int get() = heartbeatSec

    fun millisSinceInbound(): Long = System.currentTimeMillis() - lastInboundMillis

    fun connect() {
        val url = buildString {
            append(wsScheme(baseUrl.trimEnd('/')))
            append("/ws?since_seq=").append(sinceSeq)
        }
        channel = null
        lastInboundMillis = System.currentTimeMillis()
        socket = client.newWebSocket(Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    lastInboundMillis = System.currentTimeMillis()
                    listener.onConnected()
                }

                override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onDisconnected(reason.ifEmpty { "closing $code" })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onDisconnected(t.message ?: "network")
                }
            })
    }

    fun disconnect() {
        socket?.close(CLOSE_NORMAL, "客户端主动断开")
        socket = null
        channel = null
    }

    /** §5.3：服务端 25s 无息判死，客户端按 hello 里的 heartbeat 主动 ping 保活。 */
    fun ping() {
        socket?.send(Wire.ping())
    }

    /**
     * 看护判定这条链路已经不再说话了：直接拆掉并上报断开，不等对端的关闭帧。
     * 用 [okhttp3.WebSocket.cancel] 而不是 close——后者要走一次关闭握手，
     * 而半开的连接恰恰是握手发不出去，上层会一直悬在"已连接"。
     */
    fun failStale() {
        val dying = socket
        socket = null
        channel = null
        dying?.cancel()
        listener.onDisconnected("stale_connection")
    }

    private fun handle(text: String) {
        lastInboundMillis = System.currentTimeMillis()
        val node = Wire.parse(text) ?: return
        when (node.path("t").asText()) {
            Wire.T_HELLO -> {
                val hello = node.toHello() ?: return
                helloNonce = B64.dec(hello.nonceB64)
                heartbeatSec = hello.heartbeatSec
                sendAuth()
            }

            Wire.T_KX -> onKx(node)
            Wire.T_ENC -> onEnc(node)

            // 本服务端不发 auth_ok：authenticate() 验签通过直接回 kx，所以"kx 到了"就是握手成功的
            // 信号（见 onKx 建 channel）。留着这一支只是为了让第三方实现发出的 auth_ok
            // 也被明确当作"无需动作"，而不是混进"未知帧"里下次读代码的人重新猜一遍。
            Wire.T_AUTH_OK -> Unit

            Wire.T_AUTH_FAIL -> {
                // token 失效 / 设备被吊销（验收④）：不再重连，要用户重新注册
                disconnect()
                listener.onDisconnected("auth_fail:" + node.path("error").asText())
            }

            Wire.T_PING -> socket?.send(Wire.pong())

            // 明文 pong 只有本端 ping 的回声才会走到这里；作用已经落在上面的 lastInboundMillis
            Wire.T_PONG -> Unit

            else -> Unit // §5.3：未知帧类型忽略而不是崩溃
        }
    }

    private fun sendAuth() {
        val ts = System.currentTimeMillis()
        val target = SignatureTargets.wsAuth(identity.deviceId, helloNonce, ts)
        val sig = B64.enc(Ed25519.sign(identity.signPrivate, target))
        socket?.send(Wire.authFrame(identity.deviceId, identity.token, ts, sig))
    }

    private fun onKx(node: JsonNode) {
        val kx = node.toKx() ?: return
        val serverPub = B64.tryDec(kx.serverPubB64) ?: return
        val ephPub = B64.tryDec(kx.ephPubB64) ?: return
        val sig = B64.tryDec(kx.sigB64) ?: return
        val fp = Ed25519.fingerprint(serverPub)
        val pinned = identity.serverPubFp
        if (pinned == null) {
            listener.onPinServerFingerprint(fp)
        } else if (pinned != fp) {
            // 中间人换了服务端身份密钥：宁可断，也不解密
            disconnect()
            listener.onDisconnected("server_identity_mismatch")
            return
        }
        if (!Ed25519.verify(serverPub, SignatureTargets.kx(ephPub, helloNonce), sig)) {
            disconnect()
            listener.onDisconnected("bad_kx_signature")
            return
        }
        val shared = X25519.agree(identity.encPrivate, ephPub)
        channel = FrameChannel(FrameChannel.sessionKey(shared, helloNonce), System.currentTimeMillis())
    }

    private fun onEnc(node: JsonNode) {
        val live = channel ?: return
        val enc = node.toEnc() ?: return
        // 序号不连续说明这条连接被复用或有人在重放（§4.3），直接断开重连带 since_seq
        if (!live.accepts(enc.index)) {
            disconnect()
            listener.onDisconnected("frame_index_gap")
            return
        }
        val plain = live.open(enc.index, enc.dataB64) ?: return
        val inner = Wire.parse(String(plain, Charsets.UTF_8)) ?: return
        when (inner.path("t").asText()) {
            Wire.T_MESSAGE -> {
                val message = inner.toMessage()
                sinceSeq = maxOf(sinceSeq, message.seq)
                listener.onMessage(message)
            }

            Wire.T_RESUME_GAP -> listener.onResumeGap(inner.toResumeGap())
            // 补发批次取空的那句"到此结束"：没有正文，只有重连后的那一次，
            // 但上层要靠它把游标确认为"已追平"，所以不能和心跳帧一起当噪音吞掉
            Wire.T_RESUME_OK -> listener.onResumeCaughtUp()
            Wire.T_READ -> inner.toRead()?.let(listener::onRead)
            Wire.T_TOMBSTONE -> inner.toTombstone()?.let(listener::onTombstone)
            Wire.T_MEDIA_READY, Wire.T_MEDIA_FAILED ->
                inner.toMediaReady()?.let(listener::onMediaReady)
            Wire.T_CONFIG -> inner.toConfig()?.let(listener::onConfig)
            Wire.T_PING -> socket?.send(Wire.pong())
            // 加密 pong 是本端 ping 的回声，也是链路还活着的证据；活性已记在 handle() 里
            Wire.T_PONG -> Unit
            else -> Unit
        }
    }

    companion object {
        private const val CLOSE_NORMAL = 1000
        private const val DEFAULT_HEARTBEAT_SEC = 25

        fun wsScheme(base: String): String = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
    }
}
