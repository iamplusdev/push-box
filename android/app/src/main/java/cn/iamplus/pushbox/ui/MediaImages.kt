package cn.iamplus.pushbox.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.net.MediaSlot
import cn.iamplus.pushbox.net.PushBoxApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * §4.5 图片链路的客户端缓存：内存 LruCache + 磁盘（cacheDir）两级，命中即返回，
 * 未命中才走签名 GET 换预签名 URL 取字节。取回后解码成 Bitmap 再逐级回填。
 * 单例即可：全 App 就一个图片工作集，上 Coil 反而要为鉴权重定向写自定义 Fetcher，不值当。
 */
object ImageCache {

    private val maxMem = (Runtime.getRuntime().maxMemory() / 1024).toInt()

    private val mem = object : LruCache<String, Bitmap>(maxMem / 8) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun diskDir(context: Context): File =
        File(context.cacheDir, "media-img").apply { if (!exists()) mkdirs() }

    private fun diskFile(context: Context, key: String): File =
        File(diskDir(context), key.hashCode().toString(16))

    /** 取一张图：内存 → 磁盘 → 网络。thumb=true 优先缩略图。失败返回 null，调用方显示占位。 */
    suspend fun load(msgId: String, idx: Int, thumb: Boolean): Bitmap? {
        val app = App.current
        val context = app.applicationContext
        val key = "$msgId#$idx#${if (thumb) 't' else 'f'}"
        mem.get(key)?.let { return it }
        val fromDisk = withContext(Dispatchers.IO) {
            val f = diskFile(context, key)
            if (f.length() > 0) BitmapFactory.decodeFile(f.absolutePath) else null
        }
        if (fromDisk != null) {
            mem.put(key, fromDisk)
            return fromDisk
        }
        val identity = app.identity.load() ?: return null
        return try {
            val bytes = PushBoxApi(identity.baseUrl, app.http)
                .fetchMedia(identity, msgId, idx, thumb)
            val bmp = withContext(Dispatchers.IO) { decode(bytes) } ?: return null
            withContext(Dispatchers.IO) {
                runCatching {
                    diskFile(context, key).writeBytes(bytes)
                    trimDisk(context)
                }
            }
            mem.put(key, bmp)
            bmp
        } catch (_: Exception) {
            null
        }
    }

    /** §7.3 通知用同步版：阻塞当前线程直接返回 Bitmap（用于 BigPictureStyle）。 */
    fun loadSync(msgId: String, idx: Int, thumb: Boolean): Bitmap? =
        kotlinx.coroutines.runBlocking { load(msgId, idx, thumb) }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val req = 1080
        while (bounds.outWidth / sample > req || bounds.outHeight / sample > req) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * 消息删除/清空时按 (msgId, count) 精确丢掉内存与磁盘条目。
     * 早先这里是空实现（"靠下次同 key 覆盖自然淘汰"），可同 key 永远不会再被覆盖：
     * msg_id 是全局唯一的，删掉的消息不再出现，于是那张图的字节一直躺在 cacheDir 里，
     * 直到用户手动"清已读图片缓存"或系统挤爆存储才走。
     */
    fun evictMessage(msgId: String, count: Int) {
        val context = App.current.applicationContext
        for (i in 0 until count) dropSlot(context, msgId, i)
    }

    /** §7.4 重试单张图：清除该索引的缓存（thumb + full），强制重新拉取。 */
    fun evict(msgId: String, idx: Int) {
        dropSlot(App.current.applicationContext, msgId, idx)
    }

    private fun dropSlot(context: Context, msgId: String, idx: Int) {
        for (flag in charArrayOf('t', 'f')) {
            val key = "$msgId#$idx#$flag"
            mem.remove(key)
            runCatching { diskFile(context, key).delete() }
        }
    }

    /**
     * 落盘后把目录压回上限：按最后修改时间从旧到新删（LRU）。
     * cacheDir 在系统眼里是"可随时清空"的，但用户不会主动去系统设置清缓存，
     * 一个没有上限的图片缓存就等于把手机存储借给聊天记录，直到 OS 来收账。
     */
    private fun trimDisk(context: Context) {
        val dir = diskDir(context)
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            runCatching { f.delete() }
            if (total <= MAX_DISK_BYTES) return
        }
    }

    /**
     * §7.4 清已读图片缓存：按 (msgId, mediaCount) 精确构造 load 用的 key，
     * 逐条 mem.remove + 磁盘 delete，避免误删未读消息或跨消息命中同哈希。
     */
    fun clearForReadMessages(rows: List<Pair<String, Int>>) {
        val context = App.current.applicationContext
        for ((msgId, count) in rows) for (i in 0 until count) dropSlot(context, msgId, i)
    }

    private const val MAX_DISK_BYTES = 128L * 1024 * 1024
}

/** 一张缩略图的四种结局：还在取、取到了、本机这次没取到、服务端说这张已经不会再有了。 */
private sealed interface LoadOutcome {
    data object Loading : LoadOutcome
    data class Ready(val bmp: Bitmap) : LoadOutcome
    data object Missing : LoadOutcome
    data object Gone : LoadOutcome
}

/**
 * 一格媒体：单图按原比例铺满大格（微信口径），九宫格里的格子是定尺寸方图。
 * 失败态分两种——槽位终态是 failed/deleted/purged 意味着"图已经不在了"，重试没有意义，
 * 连那一跳必然 404 的签名 GET 都不必打；本机 load 返回 null 只是"这次没取到"（断网/超时），
 * 要给重试。槽位还在 pending（服务端仍在抓图）时也只转圈不发请求。
 *
 * 老库里的行没有 media_slots（迁移留 NULL），退回按消息级 media_status 判断：
 * 聚合口径下 failed 就是"每槽都坏了"，可以直接判死；partial 分不清是哪格，只能照常试。
 */
@Composable
fun MediaImage(msgId: String, idx: Int, slotStatus: String? = null, mediaStatus: String? = null,
    single: Boolean = true, modifier: Modifier = Modifier) {
    var showFull by remember { mutableStateOf(false) }
    // 重试计数：produceState 的 key 里必须带上它，否则 evict 完缓存组合层根本不会重新取图
    var attempt by remember(msgId, idx) { mutableStateOf(0) }
    val gone = slotStatus?.let { MediaSlot.isGone(it) } ?: (mediaStatus == "failed")
    val fetching = !gone && slotStatus != null && slotStatus != "ready"
    val outcome by produceState<LoadOutcome>(LoadOutcome.Loading, msgId, idx, attempt, slotStatus,
        mediaStatus) {
        value = when {
            gone -> LoadOutcome.Gone
            fetching -> LoadOutcome.Loading
            else -> ImageCache.load(msgId, idx, true)?.let { LoadOutcome.Ready(it) }
                ?: LoadOutcome.Missing
        }
    }
    val boxModifier = if (single) {
        val ratio = (outcome as? LoadOutcome.Ready)?.bmp?.let { it.width.toFloat() / it.height }
            ?: 4f / 3f
        modifier.width(SINGLE_MAX_WIDTH).aspectRatio(ratio)
    } else {
        modifier.size(GRID_CELL)
    }
    Box(modifier = boxModifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        when (val current = outcome) {
            is LoadOutcome.Ready -> Image(
                bitmap = current.bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = if (single) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clickable { showFull = true },
            )

            LoadOutcome.Loading -> CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary)

            LoadOutcome.Gone -> Text(
                if (single) "图片已失效" else "已失效",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            LoadOutcome.Missing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 九宫格一格只有 88dp，"图片加载失败"五个字加一个重试按钮会横向溢出到隔壁：
                // Box 不裁剪子内容，越界的部分直接压在气泡上。所以格子里一律用短词。
                Text(if (single) "图片加载失败" else "加载失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = {
                    ImageCache.evict(msgId, idx)
                    attempt++
                }) { Text("重试", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
    // §7.4 全屏预览：用 size=full 取原图，Dialog 展示
    if (showFull) {
        FullImageDialog(msgId, idx, gone, onDismiss = { showFull = false })
    }
}

/**
 * §7.4 全屏预览对话框：取原图（size=full），支持关闭。
 *
 * 原图这一跳与缩略图是两次独立请求，缩略图在、原图却取不到（预签名过期、断网）是真会出现的，
 * 所以这里也必须走三态。取不到时要给"重试"和关闭，不能只留一个转圈——
 * 转圈的对话框没有可点的出路，用户只能杀掉页面重进。
 * [gone] 由调用格给出：槽位已终态失效时原图更不必试，重试按钮也只会让人白点。
 */
@Composable
private fun FullImageDialog(msgId: String, idx: Int, gone: Boolean, onDismiss: () -> Unit) {
    var attempt by remember(msgId, idx) { mutableStateOf(0) }
    val outcome by produceState<LoadOutcome>(LoadOutcome.Loading, msgId, idx, attempt, gone) {
        value = if (gone) LoadOutcome.Gone else
            ImageCache.load(msgId, idx, thumb = false)?.let { LoadOutcome.Ready(it) }
                ?: LoadOutcome.Missing
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("原图") },
        text = {
            Box(modifier = Modifier.fillMaxWidth().height(400.dp),
                contentAlignment = Alignment.Center) {
                when (val current = outcome) {
                    is LoadOutcome.Ready -> Image(bitmap = current.bmp.asImageBitmap(),
                        contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize())

                    LoadOutcome.Loading -> CircularProgressIndicator()

                    LoadOutcome.Gone -> Text("原图已失效", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    LoadOutcome.Missing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("原图加载失败", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            // 与缩略图同一手法：先丢掉这一格的两级缓存，再让 attempt 进 produceState 的 key
                            ImageCache.evict(msgId, idx)
                            attempt++
                        }) { Text("重试", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        })
}

/**
 * 一条消息里的多张图：**竖排九宫格**，不再横向滚动。
 *
 * 横排的问题不在于不好看，而在于它把"这条消息有几张图"藏进了一个需要手势才能发现的容器里：
 * 用户滑到第三张才想起来数，而气泡本身会被横向滚动条撑出与正文不一致的宽度。
 * 微信的做法是 1 张大图、2～9 张定尺寸方格按行铺开，超过 9 张折叠成 +N，这里照抄。
 *
 * 用 Column/Row 而不是 LazyVerticalGrid：外层气泡按内在尺寸测量（IntrinsicSize），
 * Lazy 系列是 SubcomposeLayout 不支持内在测量，混用直接崩在 intrinsic 查询上。
 */
@Composable
fun MediaGrid(msgId: String, count: Int, mediaStatus: String? = null,
    slotStatus: (Int) -> String? = { null }) {
    if (count <= 0) return
    if (count == 1) {
        MediaImage(msgId, 0, slotStatus(0), mediaStatus, single = true)
        return
    }
    val shown = minOf(count, GRID_MAX)
    // 4 张排 3+1 会留一个孤零零的格子，微信是 2×2；其余张数按 3 列铺
    val columns = if (shown == 4) 2 else GRID_COLUMNS
    Column(verticalArrangement = Arrangement.spacedBy(GUTTER)) {
        var rowStart = 0
        while (rowStart < shown) {
            Row(horizontalArrangement = Arrangement.spacedBy(GUTTER)) {
                for (i in rowStart until minOf(rowStart + columns, shown)) {
                    MediaImage(msgId, i, slotStatus(i), mediaStatus, single = false)
                }
            }
            rowStart += columns
        }
    }
    if (count > GRID_MAX) {
        Text("另有 ${count - GRID_MAX} 张", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = GUTTER))
    }
}

private const val GRID_COLUMNS = 3
private const val GRID_MAX = 9
private val SINGLE_MAX_WIDTH = 220.dp
private val GRID_CELL = 88.dp
private val GUTTER = 4.dp
