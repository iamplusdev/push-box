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
     * 完整解析：缓存 → 网络。404 会落一个负标记，[ABSENT_TTL_MILLIS] 内不再重试；
     * 之所以是"一段时间"而不是"永久"：token 只由 sender_id 算出、永远不变，
     * 发送者后来才上传头像时，永久负标记会让这个会话再也看不到新头像。
     */
    suspend fun resolve(senderId: String): Bitmap? = withContext(Dispatchers.IO) {
        val token = tokenOf(senderId)
        // 整段都在 IO 线程：会话列表一次组合能起十几个头像协程，
        // 磁盘解码留在主线程就是滚动卡顿
        cached(token)?.let { return@withContext it }
        if (markedAbsent(token)) return@withContext null
        val app = App.current
        val identity = app.identity.load() ?: return@withContext null
        when (val result = PushBoxApi(identity.baseUrl, app.http).fetchAvatar(identity, token)) {
            is AvatarResult.Found -> {
                runCatching {
                    avatarFile(app.applicationContext, token).writeBytes(result.bytes)
                    absentFile(app.applicationContext, token).delete()
                }
                decode(result.bytes)?.also { mem.put(token, it) }
            }

            // 404 是个结论，记下来；Unknown 只是这次没取到，什么都不记
            AvatarResult.None -> {
                runCatching { absentFile(app.applicationContext, token).writeText(token) }
                null
            }

            AvatarResult.Unknown -> null
        }
    }

    private fun markedAbsent(token: String): Boolean {
        val file = absentFile(App.current.applicationContext, token)
        if (!file.exists()) return false
        return System.currentTimeMillis() - file.lastModified() <= ABSENT_TTL_MILLIS
    }

    private fun avatarFile(context: Context, token: String): File =
        File(avatarsDir(context), token)

    private fun absentFile(context: Context, token: String): File =
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
}
