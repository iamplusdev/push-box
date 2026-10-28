package cn.iamplus.pushbox.ui.screens

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.iamplus.pushbox.data.MessageRow
import cn.iamplus.pushbox.net.PushBoxApi
import cn.iamplus.pushbox.sync.SyncEngine
import cn.iamplus.pushbox.ui.ImageCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import cn.iamplus.pushbox.data.ConversationRow
import cn.iamplus.pushbox.data.Identity
import cn.iamplus.pushbox.data.ViewedConversation
import cn.iamplus.pushbox.notify.Notifier
import cn.iamplus.pushbox.ui.MediaGrid
import cn.iamplus.pushbox.ui.AvatarCache
import cn.iamplus.pushbox.ui.bubbleTimeLabel
import cn.iamplus.pushbox.ui.dateSectionLabel
import cn.iamplus.pushbox.ui.dateTimeLabel
import cn.iamplus.pushbox.ui.dayKey
import cn.iamplus.pushbox.ui.listTimeLabel
import cn.iamplus.pushbox.ui.rememberMediaSaver
import cn.iamplus.pushbox.push.PushService
import cn.iamplus.pushbox.push.SyncWorker
import cn.iamplus.pushbox.ui.LocalPushBoxColors
import cn.iamplus.pushbox.ui.PushBoxTheme
import kotlinx.coroutines.Job
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.R

/** 跳本应用的通知设置页；ROM 上这个 action 偶尔没人实现，退回应用详情页，再不行就只能算了。 */
internal fun openAppNotificationSettings(context: android.content.Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** 低版本没有这个运行时权限，一律当作"能发通知"，免得在 Android 12 上挂出一行无意义的提醒。 */
internal fun notifPermissionGranted(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 「设置 → 通知」里的长期恢复入口：系统级 POST_NOTIFICATIONS 没开就一直挂着这一行，
 * 用户从设置页回来（ON_RESUME）就重读一次，开了即消失。
 * 冷启动那次提示只说一遍，这条是之后还能找到路的凭据。
 */
@Composable
internal fun NotifyPermissionRow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(notifPermissionGranted(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = notifPermissionGranted(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted) return
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.notif_permission_title),
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.notif_permission_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { openAppNotificationSettings(context) }) {
            Text(stringResource(R.string.notify_recovery_open))
        }
    }
}

@Composable
internal fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp), strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 顶栏与设置页共用：把 SyncEngine 状态翻译成"已连接/连接中/未连接" + 颜色。 */
@Composable
internal fun connectionLabel(state: SyncEngine.State): Pair<String, Color> {
    val pbColors = LocalPushBoxColors.current
    return when (state) {
        is SyncEngine.State.Online ->
            stringResource(R.string.state_connected) to MaterialTheme.colorScheme.primary
        is SyncEngine.State.Offline ->
            stringResource(R.string.state_disconnected) to pbColors.offline
        else ->
            stringResource(R.string.state_connecting) to pbColors.connecting
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationRowView(row: ConversationRow, onOpen: () -> Unit, onLong: () -> Unit) {
    val colors = LocalPushBoxColors.current
    val app = App.get(LocalContext.current)
    val summaryLines by app.summaryLines.collectAsState()
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLong)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(row.displayName, row.avatarPath, row.senderId)
            // §7.3 会话级"锁屏隐藏"：微信不在列表上标这个，但本机既然存了就得让用户看得见，
            // 否则下次找不到开关的是同一个人
            if (row.lockPrivate) {
                Text(stringResource(R.string.conv_private_flag),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false))
                if (row.pinned) {
                    Text(" · ", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.conv_pinned_flag),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (row.muted) {
                    Text(" · ", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.conv_muted_flag),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(row.lastSummary.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                maxLines = summaryLines, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(listTimeLabel(row.lastMsgAt), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.unreadCount > 0) {
                // §7.3 微信式未读：红底白字的气泡，而不是一个孤零零的红字——
                // 列表里数字与时间同为红色小字时根本分不出哪个是未读
                // 整行是合并语义节点，不加说明读屏只会念一个"3"；这里报真实条数，
                // 界面上那个"99+"只是排版上的省略
                val spoken = stringResource(R.string.conv_unread_count, row.unreadCount)
                Box(Modifier.background(colors.unreadBadge, RoundedCornerShape(9.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
                    .semantics { contentDescription = spoken }) {
                    Text(if (row.unreadCount > 99) "99+" else row.unreadCount.toString(),
                        color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * §7.3/§7.4 会话头像：三级回落——本地"设头像"那张 → 服务端 sender_avatar（[AvatarCache]，
 * 含"这人确实没头像"的负缓存）→ 显示名首字 + 名字哈希定色占位。
 */
@Composable
internal fun Avatar(name: String, avatarPath: String? = null, senderId: String? = null, sizeDp: Int = 40) {
    // 正画着占位的头像不会因为磁盘变了就自己重组，所以把"需要重查几次"也当键：
    // 帧里补来 sender_avatar 或 App 回到前台时推一格，下一次解析才真的会去打网络
    val revision by AvatarCache.revision.collectAsState()
    val bitmap: Bitmap? by produceState<Bitmap?>(initialValue = null, avatarPath, senderId, revision) {
        val localPath = avatarPath
        val id = senderId?.takeIf { it.isNotBlank() }
        // 本地那张优先：那是用户在自己手机上挑的，服务端转存的副本不该盖掉它
        if (localPath != null || id == null) {
            value = withContext(Dispatchers.IO) { localPath?.let { AvatarCache.decodeFile(it) } }
            return@produceState
        }
        // 换了头像不换 token，只能在回前台或 TTL 到期时问一次；先把旧图顶上去，别让头像为了一次复核闪成占位
        value = withContext(Dispatchers.IO) { AvatarCache.cachedFor(id) }
        value = withContext(Dispatchers.IO) { AvatarCache.resolve(id) }
    }
    val bmp = bitmap
    // §9.2 微信式方圆形头像：圆角随边长等比走（44dp 那一档正好 6dp），与 PC 的 AvatarBox 同一比例。
    // 之前是正圆，同一台机器换到 PC 上就成了两套头像形状。
    val shape = RoundedCornerShape((sizeDp * 6 / 44f).dp)
    if (bmp != null) {
        Image(bitmap = bmp.asImageBitmap(), contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(sizeDp.dp).clip(shape))
    } else {
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        Box(contentAlignment = Alignment.Center,
            modifier = Modifier.size(sizeDp.dp).clip(shape).background(avatarColor(name), shape)) {
            Text(initial, color = Color.White, fontSize = (sizeDp * 16 / 44).sp,
                fontWeight = FontWeight.Medium)
        }
    }
}

/** 与 PC 的 AvatarVisual.Palette 逐值对齐：同名会话在两个屏上必须同色。
 * 第一格从 #07C160 收进品牌绿 #0F7A4F：首字是白字，#07C160 上只有 2.4:1，#0F7A4F 是 5.4:1。 */
internal val AVATAR_PALETTE = listOf(
    Color(0xFF0F7A4F), Color(0xFF198CFF), Color(0xFFFA5151),
    Color(0xFFB06900), Color(0xFF7B61FF), Color(0xFF00B3A7),
)

internal fun avatarColor(name: String): Color {
    val idx = (name.hashCode() and Int.MAX_VALUE) % AVATAR_PALETTE.size
    return AVATAR_PALETTE[idx]
}

/**
 * §7.4 本地"设头像"落地：把 SAF 选中的图解码、缩到 256px、压成 JPEG 存进 filesDir/avatars，
 * 返回绝对路径写进会话行。URI 读权限是一次性的，必须当场复制成常驻文件，不能只存 URI。
 * 同一份字节再交给 [AvatarCache.put] 一次：通知栏只查那一级缓存，不回查会话行。
 */
internal fun persistAvatar(context: Context, row: ConversationRow, uri: Uri): String? =
    runCatching {
        val src = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null
        val side = 256
        val scaled = Bitmap.createScaledBitmap(
            src, (src.width * side / maxOf(src.width, src.height)).coerceAtLeast(1),
            (src.height * side / maxOf(src.width, src.height)).coerceAtLeast(1), true)
        val jpeg = java.io.ByteArrayOutputStream().also { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }.toByteArray()
        val dir = java.io.File(context.filesDir, "avatars").apply { if (!exists()) mkdirs() }
        val out = java.io.File(dir, "${row.channelNo}_${row.senderId}.jpg")
        out.writeBytes(jpeg)
        AvatarCache.put(row.senderId, jpeg)
        out.absolutePath
    }.getOrNull()

/**
 * 首启的服务器地址一律由用户自己填：预填一个开发机的内网 IP，等于让每一个装上手的人
 * 第一次点注册就撞在一个连不通的地址上。这里也不替缺协议头的输入猜 http/https——
 * 猜错的方向是明文，不该由代码来猜。
 */
internal val SERVER_URL = Regex("""^https?://[^\s/]+(/\S*)?$""", RegexOption.IGNORE_CASE)
internal fun curlExample(identity: Identity, channel: PushBoxApi.Channel): String =
    "curl -X POST ${identity.baseUrl}/message -H 'content-type: application/json' " +
        "-d '{\"channel\":\"${channel.channelNo}\",\"secret\":\"${channel.secret}\"," +
        "\"type\":\"text\",\"content\":\"hello pushbox\"}'"

/**
 * 首启注册（§7.5）。register 与 join 只差一个 group_secret，密钥都在本地先生成，
 * 服务端返回的密封盒要用这把 enc 私钥解，所以整个过程不出本地密钥。
 * 返回恢复码表示"这是空间的第一台设备"，必须让用户抄下来。
 */
internal suspend fun enroll(app: App, baseUrl: String, deviceName: String,
    groupSecret: String?): String? {
    val (enc, sign) = app.identity.newKeys()
    val enrolled = PushBoxApi(baseUrl, app.http)
        .register(enc.privateKey(), sign.privateKey(), deviceName, groupSecret)
    app.identity.saveEnrolled(enrolled.deviceId, enrolled.token, enrolled.groupId,
        enrolled.recoveryCode, enrolled.serverPubFp, baseUrl)
    app.identity.addServerHistory(baseUrl)
    return enrolled.recoveryCode
}

/**
 * 顶栏：48dp 内容高（Material3 的 TopAppBar 是 64dp，压到微信那一档），底色一直画到状态栏后面，
 * 所以状态栏看起来就是顶栏的延续；底部一条分割线把它和列表分开。
 */
@Composable
internal fun PushBoxTopBar(title: @Composable () -> Unit,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    centerTitle: Boolean = false) {
    val colors = LocalPushBoxColors.current
    Column(Modifier.fillMaxWidth().background(colors.topBar)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(48.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (navigationIcon != null) navigationIcon()
            Box(
                Modifier.weight(1f).padding(horizontal = 8.dp),
                contentAlignment = if (centerTitle) Alignment.Center else Alignment.CenterStart) {
                title()
            }
            Row(Modifier.padding(end = 4.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
    }
}
