package cn.iamplus.pushbox

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
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
import cn.iamplus.pushbox.ui.screens.*

class MainActivity : FragmentActivity() {

    /**
     * 通知点进来的目标会话：(channel_no, sender_id)，即 ConversationRow 的主键。
     * 用 StateFlow 而不是 intent extra 直读，是因为 App 锁可能正盖在界面上
     * （AppLockGate 在 ON_STOP 时 relock）——目标得留着，等解锁后 MainShell 才接得住。
     */
    private val deepLink = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>(null)

    // 导出必须在 Activity 层注册：AppLockGate 会在 ON_STOP 时 relock()，把 SettingsTab 连同其
    // rememberLauncherForActivityResult 一起从组合树拆掉，SAF 回调就会被丢弃、文件停在 0 字节。
    // Activity 级 registry 与组合生命周期无关，relock 之后仍能收到结果并完成写入。
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { exportJson(App.get(this@MainActivity), this@MainActivity, uri) }
            }
            val msg = if (result.isSuccess) getString(R.string.data_export_ok)
            else getString(R.string.data_export_failed) + "：" +
                (result.exceptionOrNull()?.let { it.message ?: it::class.java.simpleName } ?: "")
            android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun startExport(suggestedName: String) {
        exportLauncher.launch(suggestedName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边：顶栏自己垫状态栏内边距并把栏底画到状态栏后面，所以系统栏保持透明，
        // 只有图标明暗要跟主题走——写死 true 会让深色主题在状态栏上留下一块发白的横条。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        consumeDeepLink(intent)
        App.get(this).checkUpdateSilently()
        setContent {
            val app = App.get(this)
            val mode by app.themeMode.collectAsState()
            // §7.5 外观：light/dark 覆盖系统，system 跟随 isSystemInDarkTheme()
            val dark = when (mode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                insets.isAppearanceLightStatusBars = !dark
                insets.isAppearanceLightNavigationBars = !dark
            }
            // §7.4 字号：small/normal/large 只缩放文字 fontScale，dp 布局不动
            val fontScale by app.fontScale.collectAsState()
            val textScale = when (fontScale) {
                "small" -> 0.9f
                "large" -> 1.18f
                else -> 1f
            }
            val locked by app.locked.collectAsState()
            PushBoxTheme(dark = dark, textScale = textScale) {
                AppLockGate(app) {
                    if (locked) AppLockScreen(app) else PushBoxRoot(deepLink)
                }
            }
        }
    }

    /** 通知点进来时 Activity 还活着：singleTop 下走这里，必须换掉 intent 否则再读还是旧 extra。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDeepLink(intent)
    }

    /**
     * 每次回到前台都把头像复核线推一格：服务端换头像不换 token，缓存自己发现不了变化。
     * 60s 闸门在 [AvatarCache.onForeground] 里，来回切应用不会反复打网络。
     */
    override fun onStart() {
        super.onStart()
        AvatarCache.onForeground()
    }

    private fun consumeDeepLink(intent: Intent?) {
        val channelNo = intent?.getStringExtra(Notifier.EXTRA_CHANNEL)
        val senderId = intent?.getStringExtra(Notifier.EXTRA_SENDER_ID)
        if (!channelNo.isNullOrEmpty() && !senderId.isNullOrEmpty()) {
            deepLink.value = channelNo to senderId
        }
    }
}

/** §8.4 D30 App 锁闸门：退到后台即重新上锁，冷启时 App.locked 已为 true。 */
@Composable
private fun AppLockGate(app: App, content: @Composable () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val lifecycle = owner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> ViewedConversation.setForeground(true)
                Lifecycle.Event.ON_STOP -> {
                    // 人已经切走了：正在看的会话不再算"当场读过"，消息该进通知栏进通知栏
                    ViewedConversation.setForeground(false)
                    app.relock()
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        // 观察者是组合时才挂上的，冷启那次 ON_START 早就过去了，按当前状态补一次
        ViewedConversation.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            ViewedConversation.setForeground(false)
        }
    }
    content()
}

/** 解锁页：九宫格数字键盘输入 PIN，达到 4 位才可解锁，错误则清空重填。 */
@Composable
private fun AppLockScreen(app: App) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val lockUntil by app.pinLockUntil.collectAsState()
    var retryIn by remember { mutableStateOf(app.pinRetrySec()) }
    val scope = rememberCoroutineScope()
    val biometricActivity = LocalContext.current as? FragmentActivity
    // PBKDF2 一次要几百毫秒，倒数与校验都不能压在点击那一刻的主线程上
    LaunchedEffect(lockUntil) {
        while (app.pinRetrySec() > 0) {
            retryIn = app.pinRetrySec()
            delay(1000)
        }
        retryIn = 0
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_lock_unlock_title),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(6) { i ->
                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                    if (i < pin.length) {
                        Box(Modifier.size(12.dp).background(
                            MaterialTheme.colorScheme.onSurface, CircleShape))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when {
                retryIn > 0 -> stringResource(R.string.app_lock_retry_in, retryIn)
                error -> stringResource(R.string.app_lock_wrong_pin)
                else -> " "
            },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        val keys = listOf(
            listOf("1", "2", "3"), listOf("4", "5", "6"),
            listOf("7", "8", "9"), listOf(" ", "0", "⌫"))
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { key ->
                    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                        if (key == "⌫") {
                            TextButton(onClick = {
                                error = false
                                if (pin.isNotEmpty()) pin = pin.dropLast(1)
                            }) { Text(key, style = MaterialTheme.typography.titleMedium) }
                        } else if (key.isNotBlank()) {
                            TextButton(onClick = {
                                error = false
                                if (pin.length < 6) pin += key
                            }) {
                                // 键盘数字是这一屏最大的字，但六档里最大只到 H1 20；原来走 headlineSmall
                                // 拿到的是 Material 基线的 24sp，不在 §9.2 的档位里
                                Text(key, style = MaterialTheme.typography.headlineLarge)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            enabled = pin.length >= 4 && !busy && retryIn == 0,
            onClick = {
                val input = pin
                busy = true
                scope.launch {
                    val ok = app.checkPin(input)
                    busy = false
                    if (ok) app.unlock() else { error = true; pin = "" }
                    retryIn = app.pinRetrySec()
                }
            }) {
            Text(stringResource(if (busy) R.string.app_lock_verifying else R.string.app_lock_unlock))
        }
        // D30 生物识别：开启后在 PIN 键盘下方多一个指纹/面容入口，PIN 始终保留为兜底
        if (app.biometricEnabled.collectAsState().value) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = {
                if (biometricActivity != null) {
                    launchBiometricUnlock(biometricActivity) { app.unlock() }
                }
            }) { Text(stringResource(R.string.app_lock_use_biometric)) }
        }
    }
}

/** §8.4 D30 拉起系统生物识别弹窗解锁；允许设备凭据（PIN/图案）作为兜底认证方式。 */
private fun launchBiometricUnlock(activity: FragmentActivity, onSuccess: () -> Unit) {
    val allowed = BIOMETRIC_STRONG or BIOMETRIC_WEAK
    val prompt = BiometricPrompt(
        activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
        })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.app_lock_biometric_title))
        .setSubtitle(activity.getString(R.string.app_lock_biometric_subtitle))
        .setNegativeButtonText(activity.getString(R.string.app_lock_use_pin))
        .setAllowedAuthenticators(allowed)
        .build()
    prompt.authenticate(info)
}

@Composable
private fun PushBoxRoot(deepLink: kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>) {
    val app = App.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    val viewModelStore = checkNotNull(LocalViewModelStoreOwner.current).viewModelStore
    var refresh by remember { mutableStateOf(0) }
    // 三态而不是"null 即没注册"：DataStore 是异步读的，把 null 直接当"还没有身份"
    // 会让每次冷启动都先画一帧注册页，然后才跳回会话列表。
    var identity by remember { mutableStateOf<Identity?>(null) }
    var identityReady by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        identity = app.identity.load()
        identityReady = true
    }

    // Android 13+ 的 POST_NOTIFICATIONS 是运行时权限；被拒时消息仍入库，只是静默。
    // "这次拒了"和"永久拒了"得分开：前者下次冷启动系统还会再问，后者系统不再弹框，
    // `launch` 只会静默回一个 false —— 那就只剩用户自己去设置里开，所以这里要给出那条路。
    val activity = LocalContext.current as? FragmentActivity
    val recoveryShown by app.notifyRecoveryShown.collectAsState()
    var askRecovery by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        val rationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(
                it, Manifest.permission.POST_NOTIFICATIONS)
        } ?: false
        askRecovery = !granted && !rationale
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val live = identity
    val polling by app.pollingMode.collectAsState()
    // 注册完成后由这里把常驻服务拉起来：服务内部会再读一次身份，所以只需触发一次。
    // §7.2 极致省电轮询模式：开着就不拉 FGS，改由 SyncWorker 周期补拉兜底。
    LaunchedEffect(live, polling) {
        if (live != null) {
            if (polling) SyncWorker.schedule(app) else PushService.start(app)
        }
    }
    val guided by app.keepAliveGuided.collectAsState()
    when {
        // 身份还没读回来：既不是"没注册"也不该画任何一屏，留一块不动的底
        !identityReady -> LoadingState(Modifier.background(MaterialTheme.colorScheme.background))
        live == null -> EnrollScreen(app, onDone = { refresh++ })
        !guided -> {
            // §7.2 D18 首启必经：没看过保活引导就拦一次，看完/跳过即放行
            KeepAliveGuideScreen(onDone = { app.finishKeepAliveGuide() })
        }
        else -> MainShell(live, deepLink, onLogout = {
            // 先卸载旧界面并取消 ViewModel 任务，再清库，避免旧身份的请求写回新会话。
            identityReady = false
            viewModelStore.clear()
            scope.launch {
                PushService.stop(app)
                SyncWorker.cancel(app)
                withContext(Dispatchers.IO) { app.db.clearAllTables() }
                app.identity.clear()
                // 清完当场回到未注册：中间这段不能再画一次旧身份的主界面
                identity = null
                app.connection.value = SyncEngine.State.Idle
            }.invokeOnCompletion { refresh++ }
        })
    }
    // 发现新版的确认框挂在根节点：冷启动静默查到的、还是"关于"里点出来的，都走这同一只，
    // 下载这一步永远要用户在框里点一次才发生。AlertDialog 自成一层，放最后不影响下面的布局。
    UpdateConfirmDialog(app)
    if (askRecovery && !recoveryShown) {
        NotifyRecoveryDialog(onDone = { app.finishNotifyRecovery() })
    }
}

/**
 * §7.2 通知权限被"不再询问"之后的恢复入口：系统不会再弹框，消息一直静默入库，
 * 所以这里给一次可以说清现状的提示，点「去设置」落到本应用的通知设置页。
 * 说过一次就不再拦人，长期入口挂在「设置 → 通知」那一行（见 [NotifyPermissionRow]）。
 */
@Composable
private fun NotifyRecoveryDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.notify_recovery_title)) },
        text = { Text(stringResource(R.string.notify_recovery_body)) },
        confirmButton = {
            Button(onClick = {
                openAppNotificationSettings(context)
                onDone()
            }) { Text(stringResource(R.string.notify_recovery_open)) }
        },
        dismissButton = {
            TextButton(onClick = onDone) { Text(stringResource(R.string.notify_recovery_later)) }
        }
    )
}

/**
 * 微信式三 tab：消息=会话列表，频道=创建与管理，我的=设置。
 * 没有 Web 控制台（D28），接入信息必须能在手机上拿到，否则纯文字闭环走不完。
 * 点开消息列表进入对话详情，用 state 覆盖整个内容区，不引额外导航依赖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(identity: Identity,
    deepLink: kotlinx.coroutines.flow.MutableStateFlow<Pair<String, String>?>, onLogout: () -> Unit) {
    val app = App.get(LocalContext.current)
    // 旋转/进程回收后要回到原来那一屏。选中的会话只存两把 key 不存整行：
    // 会话行是 Room Flow 推出来的，存一份快照只会在数据变化后变成过期副本。
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 设置二级页存名字不存枚举：旋转或进程回收后仍能回到原来那一页
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    val openSettingsPage = settingsPage?.let { name ->
        SettingsPage.entries.firstOrNull { it.name == name }
    }
    var openChannel by rememberSaveable { mutableStateOf<String?>(null) }
    var openSender by rememberSaveable { mutableStateOf<String?>(null) }
    var openFocus by rememberSaveable { mutableStateOf<String?>(null) }
    // null 是"还没读到第一帧"，与"一条会话都没有"必须分开：
    // 冷启动时把空列表当结果，列表页会先闪一屏"还没有会话"。
    val messagesVm: MessagesViewModel = viewModel(factory = MessagesViewModel.factory(app))
    val conversationsState by messagesVm.conversations.collectAsState()
    val conversations = conversationsState.orEmpty()
    val open = remember(openChannel, openSender, openFocus, conversations) {
        val channel = openChannel
        val sender = openSender
        if (channel == null || sender == null) null
        else conversations.firstOrNull { it.channelNo == channel && it.senderId == sender }
            ?.let { ConversationTarget(it, openFocus) }
    }
    val showConversation: (ConversationTarget?) -> Unit = { target ->
        openChannel = target?.conversation?.channelNo
        openSender = target?.conversation?.senderId
        openFocus = target?.focusMsgId
    }
    val conn by app.connection.collectAsState()
    val (connLabel, connColor) = connectionLabel(conn)
    // §7.2 省电模式应对：低电量模式或轮询模式下，实时性下降，顶栏显式提示可能延迟
    val powerSave = rememberPowerSaveMode()
    val polling by app.pollingMode.collectAsState()

    // §7.3 通知深链：点通知要落到那条会话，而不是停在列表页。
    // 会话行是 Room Flow 推出来的，冷启时第一批可能还没到，所以要等它出现再跳；
    // 消费完立刻清空，否则返回上一页会被同一个目标再次拽进详情。
    val target by deepLink.collectAsState()
    LaunchedEffect(target, conversations) {
        val key = target ?: return@LaunchedEffect
        val row = conversations.firstOrNull {
            it.channelNo == key.first && it.senderId == key.second
        }
        if (row != null) {
            showConversation(ConversationTarget(row))
            deepLink.value = null
        }
    }

    val detail = open
    if (detail != null) {
        ConversationDetail(app, detail.conversation, detail.focusMsgId,
            onBack = { showConversation(null) })
        return
    }

    // 二级页里系统返回键先回设置首屏，不退到底栏
    BackHandler(enabled = tab == 2 && openSettingsPage != null) { settingsPage = null }

    Scaffold(topBar = {
        val page = openSettingsPage
        if (tab == 2 && page != null) {
            PushBoxTopBar(
                title = {
                    Text(stringResource(page.title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = { settingsPage = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back))
                    }
                })
        } else {
            PushBoxTopBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.app_name),
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(10.dp))
                    // 顶栏读的是 SyncEngine 镜像进来的真实状态，不再写死"连接中"
                    Text(connLabel, style = MaterialTheme.typography.labelSmall, color = connColor)
                }
            })
        }
    }, bottomBar = {
        // 接入是日常操作，§7.4 D17 把它收进「我的 → 接入」等于每次点两层。
        // 现在拆成 消息 / 频道 / 设置：频道页只管接入这件事，设置页不再夹频道 CRUD。
        PushBoxBottomBar(listOf(
            BottomTab(stringResource(R.string.tab_messages), Icons.AutoMirrored.Filled.Chat),
            BottomTab(stringResource(R.string.tab_channels), Icons.Filled.Hub),
            BottomTab(stringResource(R.string.tab_settings), Icons.Filled.Settings)),
            selected = tab, onSelect = {
                // 在二级页里再点一次当前分栏，回到该分栏的首页
                if (it != tab || tab == 2) settingsPage = null
                tab = it
            })
    }) { inset ->
        Column(Modifier.fillMaxSize().padding(inset)) {
            if (polling) {
                DelayBanner(stringResource(R.string.banner_polling))
            } else if (powerSave) {
                DelayBanner(stringResource(R.string.banner_power_save))
            }
            val content = Modifier.fillMaxWidth().weight(1f)
            when (tab) {
                0 -> MessagesTab(identity, conversationsState, content,
                    onOpen = { row, focus -> showConversation(ConversationTarget(row, focus)) },
                    onNavigateToChannels = { tab = 1 })
                1 -> ChannelsTab(identity, content)
                else -> SettingsTab(identity, openSettingsPage,
                    onPage = { settingsPage = it?.name }, modifier = content, onLogout = onLogout)
            }
        }
    }
}

/** 底栏的一格：文字 + 图标，选中态只换色，不给它加 Material3 那块绿色的胶囊底。 */
private data class BottomTab(val label: String, val icon: ImageVector)

/** 底栏：内容高 56dp 起（Material3 NavigationBar 连内边距要 80dp），底色 #F7F7F7，顶部一条分割线。 */
@Composable
private fun PushBoxBottomBar(tabs: List<BottomTab>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalPushBoxColors.current
    Column(Modifier.fillMaxWidth().background(colors.bottomBar).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.divider))
        // 7+21+3+16+7 = 54：height 写死 52 时常态就溢出，三档字号再放大就压到分割线。
        // 改 heightIn 后大字号由它自己撑高，小字号也不松。
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                // selectable 而不是 clickable：读屏才报得出"已选中/未选中"，
                // Role.Tab 也让 TalkBack 用"标签页"而不是"按钮"来念这一格。
                Column(
                    Modifier.weight(1f)
                        .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.icon, contentDescription = null,
                        tint = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(21.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(tab.label, style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 监听系统低电量模式（省电模式）变化：注册 ACTION_POWER_SAVE_MODE_CHANGED，随生命周期收放。 */
@Composable
private fun rememberPowerSaveMode(): Boolean {
    val context = LocalContext.current
    val pm = remember(context) {
        context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
    }
    val save = remember { mutableStateOf(pm?.isPowerSaveMode ?: false) }
    DisposableEffect(pm) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                save.value = pm?.isPowerSaveMode ?: false
            }
        }
        context.registerReceiver(receiver,
            android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return save.value
}

/** 顶栏下方的延迟提示条：省电/轮询模式下告诉用户消息可能不是即时到达。 */
@Composable
private fun DelayBanner(text: String) {
    Row(Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.secondaryContainer)
        .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.BatterySaver, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

