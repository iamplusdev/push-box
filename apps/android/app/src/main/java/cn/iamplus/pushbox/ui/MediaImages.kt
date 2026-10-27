package cn.iamplus.pushbox.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.R
import cn.iamplus.pushbox.net.MediaSlot
import cn.iamplus.pushbox.net.PushBoxApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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

    /**
     * 记下每张图第一次解码出的宽高比。单图占位框按 4:3 画、解完再按真实比例换高，
     * 列表会在眼前跳一下；把比例留住之后，滚回来时第一帧就是对的。
     * 条目很小（只有比例），跟着淘汰走，不与位图缓存抢预算。
     */
    private val ratios = LruCache<String, Float>(512)

    private fun diskDir(context: Context): File =
        File(context.cacheDir, "media-img").apply { if (!exists()) mkdirs() }

    private fun diskFile(context: Context, key: String): File =
        File(diskDir(context), key.hashCode().toString(16))

    /** 取一张图：内存 → 磁盘 → 网络。thumb=true 优先缩略图。失败返回 null，调用方显示占位。 */
    suspend fun load(msgId: String, idx: Int, thumb: Boolean): Bitmap? {
        val bmp = loadBitmap(msgId, idx, thumb) ?: return null
        rememberRatio(msgId, idx, bmp.width.toFloat() / bmp.height)
        return bmp
    }

    private suspend fun loadBitmap(msgId: String, idx: Int, thumb: Boolean): Bitmap? {
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

    /** 上次解出来的宽高比；没见过就返回 null，由调用方退回 4:3 占位。 */
    fun ratio(msgId: String, idx: Int): Float? = ratios.get("$msgId#$idx")

    private fun rememberRatio(msgId: String, idx: Int, ratio: Float) {
        if (ratio > 0f && ratio.isFinite()) ratios.put("$msgId#$idx", ratio)
    }

    private fun dropSlot(context: Context, msgId: String, idx: Int) {
        ratios.remove("$msgId#$idx")
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
fun MediaImage(msgId: String, idx: Int, count: Int = 1, mediaStatus: String? = null,
    slotStatus: (Int) -> String? = { null }, single: Boolean = true, cell: Dp = GRID_CELL,
    modifier: Modifier = Modifier) {
    var showFull by remember { mutableStateOf(false) }
    // 重试计数：produceState 的 key 里必须带上它，否则 evict 完缓存组合层根本不会重新取图
    var attempt by remember(msgId, idx) { mutableStateOf(0) }
    val own = slotStatus(idx)
    val gone = slotGone(own, mediaStatus)
    val fetching = !gone && own != null && own != "ready"
    val outcome by produceState<LoadOutcome>(LoadOutcome.Loading, msgId, idx, attempt, own,
        mediaStatus) {
        value = when {
            gone -> LoadOutcome.Gone
            fetching -> LoadOutcome.Loading
            else -> ImageCache.load(msgId, idx, true)?.let { LoadOutcome.Ready(it) }
                ?: LoadOutcome.Missing
        }
    }
    val boxModifier = if (single) {
        // 比例优先取这次解码出来的，其次取上次记下的；都没有才退回 4:3。
        // 宽度是"最多 220dp、且不超过气泡给的空间"——写死 220 时窄屏会把气泡顶宽。
        val ratio = (outcome as? LoadOutcome.Ready)?.bmp?.let { it.width.toFloat() / it.height }
            ?: ImageCache.ratio(msgId, idx)
            ?: 4f / 3f
        modifier.widthIn(max = SINGLE_MAX_WIDTH).fillMaxWidth().aspectRatio(ratio)
    } else {
        modifier.size(cell)
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
    // §7.4 点开即进全屏看图，从这一格原尺寸开始翻
    if (showFull) {
        ImageViewer(msgId, count, idx, mediaStatus, slotStatus, onDismiss = { showFull = false })
    }
}

/** 槽位终态失效判定；老库的行没有 media_slots（null），退回消息级的聚合口径。 */
private fun slotGone(status: String?, mediaStatus: String?): Boolean =
    if (status == null) mediaStatus == "failed" else MediaSlot.isGone(status)

/**
 * §7.4 全屏看图：黑底、左右翻页、双指缩放（双击复位）、顶栏保存。
 *
 * 早先是个定高 400dp 的 AlertDialog：四角只能在那个小窗口里看，一条消息九张图只能一张张重开，
 * 想留下还得先退出去长按气泡。全屏之后手势与出口跟微信一致：点一下收/放顶栏，点关闭或按返回退出。
 *
 * 翻页与缩放共用一个手势面，靠 `userScrollEnabled = !zoomed` 分时复用：两根手指一平移如果不先
 * 把 pager 关掉，手势会先被判成翻页，放大后的图永远拖不动。
 */
@Composable
private fun ImageViewer(msgId: String, count: Int, start: Int, mediaStatus: String?,
    slotStatus: (Int) -> String?, onDismiss: () -> Unit) {
    val pages = count.coerceAtLeast(1)
    val pager = rememberPagerState(initialPage = start.coerceIn(0, pages - 1)) { pages }
    var zoomed by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    val save = rememberMediaSaver()
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize()) { page ->
                ViewerPage(msgId, page, slotGone(slotStatus(page), mediaStatus),
                    active = pager.currentPage == page,
                    onZoomChange = { zoomed = it },
                    onTap = { chromeVisible = !chromeVisible })
            }
            if (chromeVisible) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close,
                            contentDescription = stringResource(R.string.confirm_close),
                            tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    if (pages > 1) {
                        Text("${pager.currentPage + 1} / $pages", color = Color.White,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { save(msgId, pager.currentPage) }) {
                        Icon(Icons.Filled.Download,
                            contentDescription = stringResource(R.string.image_viewer_save),
                            tint = Color.White)
                    }
                }
            }
        }
    }
}

/**
 * 一页原图。取不到原图（预签名过期、断网）是真会出现的，所以与格子一样走三态：
 * 只转圈不给出路的话，黑屏上除了返回键什么都按不动。
 */
@Composable
private fun ViewerPage(msgId: String, idx: Int, gone: Boolean, active: Boolean,
    onZoomChange: (Boolean) -> Unit, onTap: () -> Unit) {
    var attempt by remember(msgId, idx) { mutableStateOf(0) }
    var scale by remember(msgId, idx) { mutableStateOf(1f) }
    var offset by remember(msgId, idx) { mutableStateOf(Offset.Zero) }
    val outcome by produceState<LoadOutcome>(LoadOutcome.Loading, msgId, idx, attempt, gone) {
        value = if (gone) LoadOutcome.Gone else
            ImageCache.load(msgId, idx, thumb = false)?.let { LoadOutcome.Ready(it) }
                ?: LoadOutcome.Missing
    }
    // 翻走时把缩放收回 1：pager 会留着相邻页的组合状态，不收回的话滑回来看到的是放大图，
    // 手势却已经回到"可翻页"的档上，两边对不上
    LaunchedEffect(active) {
        if (!active && scale != 1f) {
            scale = 1f
            offset = Offset.Zero
            onZoomChange(false)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val current = outcome) {
            is LoadOutcome.Ready -> Image(
                bitmap = current.bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    // graphicsLayer 在外层：放大后的画面才在命中范围里。
                    // translation 是缩放之后施加、单位是屏幕像素，所以 pan 要乘回当前倍数才跟手。
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .pointerInput(idx) {
                        // 不能直接用 detectTransformGestures：它一旦过了 touch slop 就会把单指位移
                        // 也吃掉，而这里单指位移在未放大时属于 pager。所以自己判——只有"双指"或
                        // "已经放大了"才消费事件，其余原样放行给外层翻页。
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                pressed = event.changes.any { it.pressed }
                                if (event.changes.any { it.isConsumed }) return@awaitEachGesture
                                val multiTouch = event.changes.count { it.pressed } >= 2
                                if (!multiTouch && scale <= 1f) continue
                                val next = (scale * event.calculateZoom())
                                    .coerceIn(MIN_ZOOM, MAX_ZOOM)
                                val pan = event.calculatePan()
                                scale = next
                                offset = if (next <= 1f) Offset.Zero else {
                                    val maxX = size.width * (next - 1f) / 2f
                                    val maxY = size.height * (next - 1f) / 2f
                                    Offset((offset.x + pan.x * next).coerceIn(-maxX, maxX),
                                        (offset.y + pan.y * next).coerceIn(-maxY, maxY))
                                }
                                onZoomChange(next > 1f)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    }
                    .pointerInput(idx) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = DOUBLE_TAP_ZOOM
                                }
                                onZoomChange(scale > 1f)
                            },
                            onTap = { onTap() },
                        )
                    })

            LoadOutcome.Loading -> CircularProgressIndicator(color = Color.White)

            LoadOutcome.Gone -> Text("原图已失效",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f))

            LoadOutcome.Missing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("原图加载失败", style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f))
                TextButton(onClick = {
                    // 与格子同一手法：先丢掉这一格的两级缓存，再让 attempt 进 produceState 的 key
                    ImageCache.evict(msgId, idx)
                    attempt++
                }) { Text("重试", color = Color.White) }
            }
        }
    }
}

/**
 * §7.4 保存当前页到相册。
 *
 * Android 10+ 走 MediaStore：自己登记、自己收回 IS_PENDING，既不需要任何权限也不用等媒体扫描。
 * 8/9 只能写公共目录，WRITE_EXTERNAL_STORAGE 由 [rememberMediaSaver] 事先要到。
 * 早先这里是直接往 Download 目录 FileOutputStream：在 Android 10 之后那是一条必然 EACCES 的路，
 * 而调用处 runCatching 吞掉异常，用户看到的就是"点了没反应"。
 */
internal suspend fun saveImageToGallery(context: Context, msgId: String, idx: Int): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val app = App.current
            val identity = app.identity.load() ?: return@withContext false
            val bytes = PushBoxApi(identity.baseUrl, app.http)
                .fetchMedia(identity, msgId, idx, thumb = false)
            val mime = imageMimeOf(bytes)
            val name = imageFileName(msgId, idx, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/PushBox")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext false
                val written = runCatching {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
                }.getOrDefault(false)
                if (!written) {
                    runCatching { resolver.delete(uri, null, null) }
                    return@withContext false
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "PushBox")
                if (!dir.exists() && !dir.mkdirs()) return@withContext false
                val file = File(dir, name)
                FileOutputStream(file).use { it.write(bytes) }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath),
                    arrayOf(mime), null)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

/** 按文件头判格式：扩展名写错会让相册认不出来。服务端转存时保留了原格式，这里不能一律当 jpg。 */
internal fun imageMimeOf(bytes: ByteArray): String = when {
    bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
    bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() -> "image/gif"
    bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
    else -> "image/jpeg"
}

/** 落盘名：msg_id 是服务端下发的字符串，直接拼进路径等于把文件名交给外部输入。 */
internal fun imageFileName(msgId: String, idx: Int, mime: String): String {
    val safe = msgId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)
    val ext = when (mime) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "jpg"
    }
    return "PushBox_${safe.ifEmpty { "img" }}_$idx.$ext"
}

/**
 * 保存动作：权限与结果提示收在这一处，看图页顶栏和气泡长按菜单共用。
 * 26–28 的写公共目录要运行时权限；29+ 的 MediaStore 不要，也不该为了它去要一个系统不会再给的权限。
 */
@Composable
internal fun rememberMediaSaver(): (String, Int) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.image_saved)
    val failedText = stringResource(R.string.image_save_failed)
    val deniedText = stringResource(R.string.image_save_denied)
    var pending by remember { mutableStateOf<Pair<String, Int>?>(null) }
    fun save(msgId: String, idx: Int) {
        scope.launch {
            val ok = saveImageToGallery(context, msgId, idx)
            Toast.makeText(context, if (ok) savedText else failedText, Toast.LENGTH_SHORT).show()
        }
    }
    val askStorage = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        val target = pending
        pending = null
        if (granted && target != null) save(target.first, target.second)
        else Toast.makeText(context, deniedText, Toast.LENGTH_SHORT).show()
    }
    return { msgId, idx ->
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pending = msgId to idx
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            save(msgId, idx)
        }
    }
}

/**
 * 一条消息里的多张图：**竖排九宫格**，不再横向滚动。
 *
 * 横排的问题不在于不好看，而在于它把"这条消息有几张图"藏进了一个需要手势才能发现的容器里：
 * 用户滑到第三张才想起来数，而气泡本身会被横向滚动条撑出与正文不一致的宽度。
 * 微信的做法是 1 张大图、2～9 张定尺寸方格按行铺开，超过 9 张折叠成 +N，这里照抄。
 *
 * 用 Column/Row 而不是 LazyVerticalGrid：Lazy 系列是 SubcomposeLayout，
 * 一旦外层改回按内在尺寸测量（IntrinsicSize）就会直接崩在 intrinsic 查询上。
 * 格子边长由 `BoxWithConstraints` 现算：3×88 + 2×4 = 272dp，360dp 屏上气泡只剩 260–270dp，
 * 固定尺寸会把第三格顶出气泡背景；改成 `(可用宽 - 槽)/列数` 且不超过 88dp 后，窄屏自己缩、宽屏不涨。
 */
@Composable
fun MediaGrid(msgId: String, count: Int, mediaStatus: String? = null,
    slotStatus: (Int) -> String? = { null }) {
    if (count <= 0) return
    if (count == 1) {
        MediaImage(msgId, 0, count = count, mediaStatus = mediaStatus, slotStatus = slotStatus,
            single = true)
        return
    }
    val shown = minOf(count, GRID_MAX)
    // 4 张排 3+1 会留一个孤零零的格子，微信是 2×2；其余张数按 3 列铺
    val columns = if (shown == 4) 2 else GRID_COLUMNS
    BoxWithConstraints {
        val cell = minOf(GRID_CELL,
            (maxWidth - GUTTER * (columns - 1)) / columns).coerceAtLeast(1.dp)
        Column(verticalArrangement = Arrangement.spacedBy(GUTTER)) {
            var rowStart = 0
            while (rowStart < shown) {
                Row(horizontalArrangement = Arrangement.spacedBy(GUTTER)) {
                    for (i in rowStart until minOf(rowStart + columns, shown)) {
                        MediaImage(msgId, i, count = count, mediaStatus = mediaStatus,
                            slotStatus = slotStatus, single = false, cell = cell)
                    }
                }
                rowStart += columns
            }
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
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private val SINGLE_MAX_WIDTH = 220.dp
private val GRID_CELL = 88.dp
private val GUTTER = 4.dp
