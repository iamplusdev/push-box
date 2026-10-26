package cn.iamplus.pushbox.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.net.AvatarResult
import cn.iamplus.pushbox.net.PushBoxApi
import dev.pushbox.core.crypto.Hashes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * §4.5 发送者头像缓存：内存 Bitmap → filesDir/avatars/ → 签名 GET。
 *
 * 单独一个类而不是复用 [ImageCache]，因为头像有两条媒体图没有的规矩：
 * 一是"确认这人没头像"也要能缓存——服务端对没转存成功的头像直接回 404，
 * 不记下来就会每次重组会话列表都为一堆注定 404 的 token 打一遍网络；
 * 二是通知栏只能在投递线程上同步取字节，不能为了一张头像把通知卡在一次 RTT 上，
 * 所以查询分两个入口：[cached] 只读缓存、[resolve] 才允许联网。
 */
object AvatarCache {

    private val mem = object : LruCache<String, Bitmap>(MEM_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /**
     * 缓存需要重查的次数（负标记被作废、或 App 回到前台），只增不减。
     * UI 拿它当头像解析的键：已经画出来的头像不会因为磁盘变了就自己重组。
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /**
     * 上一次回前台（进而发起复核）的时刻。取回时间早于它的缓存图都当成"这一次前台还没问过"，
     * [isFresh] 因此会把它判成过期、让 [resolve] 打一次网络。
     */
    @Volatile
    private var lastForegroundAt = 0L

    /** 头像的缓存键：sha256hex(sender_id)，与服务端 /api/v1/avatars/{token} 的路径参数同一算法。 */
    fun tokenOf(senderId: String): String =
        Hashes.sha256Hex(senderId.toByteArray(Charsets.UTF_8))

    /** §7.4 本地"设头像"那张：路径来自会话行，命名与 token 无关，所以单独一个解码入口。 */
    fun decodeFile(path: String): Bitmap? =
        runCatching { decode(File(path).readBytes()) }.getOrNull()

    /** 只查缓存，绝不打网络。取不到就返回 null，调用方按首字占位画。 */
    fun cached(token: String): Bitmap? {
        mem.get(token)?.let { return it }
        val file = avatarFile(App.current.applicationContext, token)
        if (file.length() <= 0L) return null
        val bmp = runCatching { decode(file.readBytes()) }.getOrNull() ?: return null
        mem.put(token, bmp)
        return bmp
    }

    /** [cached] 的 senderId 入口：UI 在回源复核之前先把（可能已过期的）旧图顶上去。 */
    fun cachedFor(senderId: String): Bitmap? = cached(tokenOf(senderId))

    /**
     * 本地"设头像"额外写进这里一份（会话行里的 avatar_path 仍是显示首选）。
     * 目的是让通知栏拿得到它：出通知的那条路径上不该为了一张头像回查会话行，
     * 而 [cached] 是唯一能在投递线程上同步返回的入口。
     */
    fun put(senderId: String, bytes: ByteArray) {
        val token = tokenOf(senderId)
        val context = App.current.applicationContext
        runCatching {
            avatarFile(context, token).writeBytes(bytes)
            // 本地挑的图就是"这个人有头像"，把服务端那个 404 结论作废
            absentFile(context, token).delete()
        }
        decode(bytes)?.let { mem.put(token, it) }
    }

    /**
     * 完整解析：缓存 → 网络。这一次前台还没复核过的旧图要先回源问一次——
     * token 只由 sender_id 算出、服务端按 (频道, 发送者) 覆盖写，所以"这人换了头像"
     * 在本机看来和"没换"完全一样，只能靠 [onForeground] 与 [FRESH_TTL_MILLIS] 重新问一遍。
     * 404 会落一个负标记，[ABSENT_TTL_MILLIS] 内不再重试。
     */
    suspend fun resolve(senderId: String): Bitmap? = withContext(Dispatchers.IO) {
        val token = tokenOf(senderId)
        val context = App.current.applicationContext
        // 整段都在 IO 线程：会话列表一次组合能起十几个头像协程，
        // 磁盘解码留在主线程就是滚动卡顿
        val hit = cached(token)
        if (hit != null && isFresh(avatarFile(context, token))) return@withContext hit
        // 24h 内确认过"这人没头像"就不再问；手上要是有张旧图，照旧给旧图，不能把它变成占位
        if (markedAbsent(token)) return@withContext hit
        val app = App.current
        val identity = app.identity.load() ?: return@withContext hit
        when (val result = PushBoxApi(identity.baseUrl, app.http).fetchAvatar(identity, token)) {
            is AvatarResult.Found -> {
                runCatching {
                    avatarFile(context, token).writeBytes(result.bytes)
                    absentFile(context, token).delete()
                }
                decode(result.bytes)?.also { mem.put(token, it) }
            }

            // 404 是个结论：记下来，并把可能残留的旧图一起丢掉（服务端都没了，界面上不该还挂着）
            AvatarResult.None -> {
                runCatching {
                    absentFile(context, token).writeText(token)
                    avatarFile(context, token).delete()
                    mem.remove(token)
                }
                null
            }

            // 没问到（超时/5xx/401）就继续用旧图，什么都不记
            AvatarResult.Unknown -> hit
        }
    }

    /**
     * 帧里的 `sender_avatar` 非空即"服务端已经有这人的头像"——比 24h 负缓存更新鲜，
     * 因此把那条 404 结论作废，下一次 [resolve] 才会真的去打网络。
     *
     * 只有真的删掉了一枚标记才推 [revision]：绝大多数消息都带 sender_avatar，
     * 每条都让整屏头像重跑一遍解析，等于把一次刷新变成常态。
     * 返回是否真删掉了标记，用例靠它判"这次有没有产生副作用"。
     */
    fun invalidate(senderId: String): Boolean {
        val removed = runCatching {
            absentFile(App.current.applicationContext, tokenOf(senderId)).delete()
        }.getOrDefault(false)
        if (removed) _revision.value += 1
        return removed
    }

    /**
     * App 回到前台时调用：把这一刻记成复核线，并推一格 [revision] 让界面上画着的头像重跑解析。
     *
     * 没有它就只能等 [FRESH_TTL_MILLIS] 自然到期：用户换完头像切回 App，看到的是旧图，
     * 而且这一整个前台周期都不会变——[resolve] 只在组合键变化时才被再调一次。
     * 闸门 60s 是给"切出去瞄一眼通知又回来"这类抖动用的，闸门内不动任何状态。
     */
    fun onForeground(now: Long = System.currentTimeMillis()) {
        if (now - lastForegroundAt < FOREGROUND_GATE_MILLIS) return
        lastForegroundAt = now
        _revision.value += 1
    }

    private fun markedAbsent(token: String): Boolean {
        val file = absentFile(App.current.applicationContext, token)
        if (!file.exists()) return false
        return System.currentTimeMillis() - file.lastModified() <= ABSENT_TTL_MILLIS
    }

    /**
     * 缓存文件的取回时刻就是它的 mtime（[resolve] 每次回源都重写一遍）。
     * 过期不等于不能用——[resolve] 复核失败时会把它当回落，UI 也先拿它顶占位。
     * 取回时间早于 [foregroundAt] 的一律算过期：那一轮前台还没问过服务端。
     */
    internal fun isFresh(
        file: File,
        now: Long = System.currentTimeMillis(),
        foregroundAt: Long = lastForegroundAt,
    ): Boolean {
        val fetchedAt = file.lastModified()
        if (fetchedAt <= 0L) return false
        if (fetchedAt < foregroundAt) return false
        return now - fetchedAt <= FRESH_TTL_MILLIS
    }

    internal fun avatarFile(context: Context, token: String): File =
        File(avatarsDir(context), token)

    internal fun absentFile(context: Context, token: String): File =
        File(avatarsDir(context), "$token.absent")

    private fun avatarsDir(context: Context): File =
        File(context.filesDir, AVATARS_DIR).apply { if (!exists()) mkdirs() }

    /**
     * 头像最多只画在 48dp 的圆里，按 [MAX_SIDE] 上限下采样：
     * 第三方上传的原图可能是几兆的照片，会话列表一行十来个头像全按原尺寸解，
     * 光内存就能把 LruCache 撑爆到每次读都未命中。
     */
    private fun decode(data: ByteArray): Bitmap? {
        if (data.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private const val AVATARS_DIR = "avatars"
    private const val MAX_SIDE = 256
    private const val MEM_KB = 6 * 1024
    private const val ABSENT_TTL_MILLIS = 24L * 60 * 60 * 1000

    /**
     * 正缓存的有效期。token 恒定、服务端覆盖写，所以换头像只能靠时间发现；
     * 10 分钟是为了"在另一台设备上换了头像，这台最多十分钟内看到"，
     * 同时 [onForeground] 让切回前台的那一刻就复核，不必真等满一个周期。
     */
    private const val FRESH_TTL_MILLIS = 10L * 60 * 1000

    /** 两次前台复核之间的最小间隔：切出去瞄一眼通知不算一次新的前台周期。 */
    private const val FOREGROUND_GATE_MILLIS = 60L * 1000
}
