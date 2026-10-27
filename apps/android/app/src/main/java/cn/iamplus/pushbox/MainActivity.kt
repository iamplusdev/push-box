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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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
import cn.iamplus.pushbox.push.PushService
import cn.iamplus.pushbox.push.SyncWorker
import cn.iamplus.pushbox.ui.LocalPushBoxColors
import cn.iamplus.pushbox.ui.PushBoxTheme
import kotlinx.coroutines.Job

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
            // 更换服务器=重新注册：停服务、清库清身份，回到首启注册页
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

/** 跳本应用的通知设置页；ROM 上这个 action 偶尔没人实现，退回应用详情页，再不行就只能算了。 */
private fun openAppNotificationSettings(context: android.content.Context) {
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
private fun notifPermissionGranted(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 「设置 → 通知」里的长期恢复入口：系统级 POST_NOTIFICATIONS 没开就一直挂着这一行，
 * 用户从设置页回来（ON_RESUME）就重读一次，开了即消失。
 * 冷启动那次提示只说一遍，这条是之后还能找到路的凭据。
 */
@Composable
private fun NotifyPermissionRow() {
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

/** §7.5 首启：填服务器地址 → 本地生成两把密钥 → register 或 join → 恢复码要用户确认抄下。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnrollScreen(app: App, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var serverHost by remember { mutableStateOf("") }
    var serverPort by remember { mutableStateOf("7404") }
    var useHttps by remember { mutableStateOf(false) }
    // 地址由主机+端口+协议推导，而不是另存一份：存了就会出现"开关拨了、URL 没跟着变"的半更新状态
    var history by remember { mutableStateOf<List<String>>(emptyList()) }
    var deviceName by remember { mutableStateOf(Build.MODEL ?: "Android") }
    var recoveryCode by remember { mutableStateOf("") }
    var showRecovery by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var issuedCode by remember { mutableStateOf<String?>(null) }
    val scanPrompt = stringResource(R.string.enroll_scan_prompt)
    val serverRequired = stringResource(R.string.enroll_server_required)
    val serverInvalid = stringResource(R.string.enroll_server_invalid)
    // §7.5 添加设备：扫另一台设备"我的"里展示的恢复码二维码，自动填入。首启无 App 锁，
    // 相机页返回不会触发 relock 拆组合，故这里用组合级 launcher 是安全的。
    val scanLauncher = rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()) { result ->
        val text = result?.contents
        if (!text.isNullOrBlank()) recoveryCode = text.trim()
    }

    // 根据主机、端口和 HTTPS 开关自动生成完整 URL
    fun buildBaseUrl(host: String, port: String, https: Boolean): String {
        val trimmedHost = host.trim().trimEnd('/')
        if (trimmedHost.isEmpty()) return ""
        val scheme = if (https) "https" else "http"
        val trimmedPort = port.trim()
        return if (trimmedPort.isNotEmpty() && trimmedPort != "80" && trimmedPort != "443") {
            "$scheme://$trimmedHost:$trimmedPort"
        } else {
            "$scheme://$trimmedHost"
        }
    }

    val baseUrl = remember(serverHost, serverPort, useHttps) {
        buildBaseUrl(serverHost, serverPort, useHttps)
    }

    // 历史记录里存的是整条 URL，回填时要拆回主机/端口/协议三个格子，否则用户看到还是上一次的组合
    fun applyUrl(url: String) {
        val secured = url.startsWith("https://")
        val rest = url.substringAfter("://").substringBefore('/')
        val colon = rest.lastIndexOf(':')
        useHttps = secured
        serverHost = if (colon > 0) rest.substring(0, colon) else rest
        serverPort = if (colon > 0) rest.substring(colon + 1) else ""
    }

    LaunchedEffect(Unit) {
        val recent = app.identity.serverHistory()
        history = recent
        if (recent.isNotEmpty()) applyUrl(recent.first())
    }

    Scaffold(topBar = {
        PushBoxTopBar(centerTitle = true, title = {
            Text(stringResource(R.string.enroll_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
        })
    }) { inset ->
        Column(
            Modifier.fillMaxSize().padding(inset).padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(stringResource(R.string.enroll_server)) {
                // HTTPS 开关：拨一下就立刻反映到下面那条 URL 上（PC 的接入框同一口径）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.enroll_use_https),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f))
                    Switch(checked = useHttps, onCheckedChange = { useHttps = it })
                }
                OutlinedTextField(
                    value = serverHost, onValueChange = { serverHost = it },
                    label = { Text(stringResource(R.string.enroll_server_host)) },
                    supportingText = { Text(stringResource(R.string.enroll_server_host_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    value = serverPort, onValueChange = { serverPort = it },
                    label = { Text(stringResource(R.string.enroll_server_port)) },
                    supportingText = { Text(stringResource(R.string.enroll_server_port_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (baseUrl.isNotEmpty()) {
                    Text(stringResource(R.string.enroll_generated_url, baseUrl),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary)
                }
            }

            if (history.isNotEmpty()) {
                SettingsSection(stringResource(R.string.server_history_title)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.forEach { url ->
                            OutlinedButton(enabled = !busy, onClick = { applyUrl(url) }) {
                                Text(url, maxLines = 1)
                            }
                        }
                    }
                }
            }

            SettingsSection(stringResource(R.string.enroll_device_name)) {
                OutlinedTextField(
                    value = deviceName, onValueChange = { deviceName = it },
                    label = { Text(stringResource(R.string.enroll_device_name)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }

            // 恢复码这条路不是每个人都要走：默认收成一句说明，点开才给输入框与扫码入口，
            // 否则首启这一屏要同时摆着"创建"和"加入"两套字段
            SettingsSection(stringResource(R.string.enroll_have_code_title)) {
                if (showRecovery) {
                    OutlinedTextField(
                        value = recoveryCode, onValueChange = { recoveryCode = it },
                        label = { Text(stringResource(R.string.enroll_recovery_code)) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        // 扫码填入恢复码：手输 base64 易错，扫一下即可
                        TextButton(onClick = {
                            scanLauncher.launch(
                                com.journeyapps.barcodescanner.ScanOptions()
                                    .setDesiredBarcodeFormats(
                                        com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                                    .setPrompt(scanPrompt)
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(false))
                        }) { Text(stringResource(R.string.enroll_scan)) }
                        TextButton(enabled = !busy, onClick = {
                            showRecovery = false; recoveryCode = ""
                        }) { Text(stringResource(R.string.enroll_have_code_close)) }
                    }
                } else {
                    Text(stringResource(R.string.enroll_have_code_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(enabled = !busy, onClick = { showRecovery = true }) {
                        Text(stringResource(R.string.enroll_join))
                    }
                }
            }

            error?.let { EnrollErrorCard(it) }
            if (busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.enroll_working),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                val url = baseUrl.trim().trimEnd('/')
                if (url.isEmpty()) {
                    error = serverRequired
                    return@Button
                }
                if (!SERVER_URL.matches(url)) {
                    error = serverInvalid
                    return@Button
                }
                scope.launch {
                    busy = true
                    error = null
                    val outcome = runCatching {
                        enroll(app, url, deviceName.trim(),
                            recoveryCode.trim().ifEmpty { null })
                    }
                    busy = false
                    outcome.fold(
                        onSuccess = { code ->
                            if (code == null) onDone() else issuedCode = code
                        },
                        onFailure = { error = it.message ?: it::class.java.simpleName })
                }
            }) {
                Text(stringResource(
                    if (showRecovery) R.string.enroll_join else R.string.enroll_create))
            }
            issuedCode?.let { code ->
                RecoveryCodeCard(code = code, onConfirm = onDone)
            }
        }
    }
}

/** 首启的报错：一张带红色左轨的卡片，而不是贴在表单尾巴上的一行红字。 */
@Composable
private fun EnrollErrorCard(text: String) {
    val colors = LocalPushBoxColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.levelError))
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
    }
}

@Composable
private fun RecoveryCodeCard(code: String, onConfirm: () -> Unit) {
    // §7.5：恢复码=Space 根密钥，展示期间禁截图/禁进最近任务预览，离开页面立刻解除
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.recovery_code_title),
                style = MaterialTheme.typography.titleMedium)
            Text(code, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = rememberCopyAction(code)) {
                Text(stringResource(R.string.copy))
            }
            Text(stringResource(R.string.recovery_code_body),
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onConfirm) { Text(stringResource(R.string.recovery_code_confirm)) }
        }
    }
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
    val conversationsState by app.dao.conversations().collectAsState(initial = null)
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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

/**
 * 顶栏：48dp 内容高（Material3 的 TopAppBar 是 64dp，压到微信那一档），底色一直画到状态栏后面，
 * 所以状态栏看起来就是顶栏的延续；底部一条分割线把它和列表分开。
 */
@Composable
private fun PushBoxTopBar(title: @Composable () -> Unit,
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
                Column(
                    Modifier.weight(1f)
                        .clickable { onSelect(index) }
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

/**
 * 取第一帧数据时的占位：不写字，也不该被读成"空"或"错"。
 * 三处（启动、列表、详情）用的是同一个，免得一处转圈一处留白。
 */
@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp), strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessagesTab(identity: Identity, conversations: List<ConversationRow>?,
    modifier: Modifier, onOpen: (ConversationRow, String?) -> Unit,
    onNavigateToChannels: (() -> Unit)? = null) {
    val app = App.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<ConversationRow?>(null) }
    var renameFor by remember { mutableStateOf<ConversationRow?>(null) }
    // §7.4 本地"设头像"：记住当前要设头像的会话，SAF 回来后把选中的图落到 filesDir 并写库。
    var avatarFor by remember { mutableStateOf<ConversationRow?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()) { uri ->
        val row = avatarFor
        avatarFor = null
        if (uri != null && row != null) {
            scope.launch(Dispatchers.IO) {
                val path = persistAvatar(app.applicationContext, row, uri)
                if (path != null) app.dao.setAvatar(row.channelNo, row.senderId, path)
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        if (conversations == null) {
            LoadingState()
        } else if (conversations.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Hub, contentDescription = null,
                    modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.empty_conversations),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.empty_conversations_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { onNavigateToChannels?.invoke() }) {
                    Text("创建第一个频道")
                }
            }
        } else {
            // §9.1 列表：整页铺白，行间一条 #DEDEDE 分割线，且分割线起于文本起点（缩过头像那一格）
            val pbc = LocalPushBoxColors.current
            LazyColumn(Modifier.fillMaxWidth().background(pbc.rowBackground)) {
                items(conversations, key = { "${it.channelNo}:${it.senderId}" }) { row ->
                    ConversationRowView(row, onOpen = { onOpen(row, null) },
                        onLong = { menuFor = row })
                    HorizontalDivider(Modifier.padding(start = 66.dp),
                        thickness = 0.5.dp, color = pbc.divider)
                }
            }
        }
    }
    val menuRow = menuFor
    if (menuRow != null) {
        ConversationMenu(menuRow, onDismiss = { menuFor = null },
            onTogglePin = {
                scope.launch(Dispatchers.IO) {
                    app.dao.setPinned(menuRow.channelNo, menuRow.senderId, !menuRow.pinned)
                }
                menuFor = null
            },
            onToggleMute = {
                scope.launch(Dispatchers.IO) {
                    app.dao.setMuted(menuRow.channelNo, menuRow.senderId, !menuRow.muted)
                }
                menuFor = null
            },
            onToggleLockPrivate = {
                scope.launch(Dispatchers.IO) {
                    app.dao.setLockPrivate(menuRow.channelNo, menuRow.senderId,
                        !menuRow.lockPrivate)
                }
                menuFor = null
            },
            onRename = { renameFor = menuRow; menuFor = null },
            onSetAvatar = { avatarFor = menuRow; menuFor = null;
                avatarPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onClear = {
                val contextForToast = app.applicationContext
                scope.launch(Dispatchers.IO) {
                    // §7.3/§5.2 D13：先本地即时清空，再上报服务端以广播 scope tombstone（他端隐藏、重装不回放）；
                    // 服务端失败不阻断本机（best-effort，与已读游标上报同策略）。
                    app.dao.conversationMedia(menuRow.channelNo, menuRow.senderId)
                        .forEach { ImageCache.evictMessage(it.msgId, it.mediaCount) }
                    app.dao.clearConversation(menuRow.channelNo, menuRow.senderId)
                    val result = runCatching {
                        PushBoxApi(identity.baseUrl, app.http)
                            .clearConversation(identity, menuRow.channelNo, menuRow.senderId)
                    }
                    result.onFailure {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(contextForToast,
                                "本地已清空，但同步到其他设备失败：${it.message}",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            })
    }
    val renameRow = renameFor
    if (renameRow != null) {
        RenameDialog(renameRow, onDismiss = { renameFor = null }, onSave = { name ->
            scope.launch(Dispatchers.IO) {
                app.dao.renameConversation(renameRow.channelNo, renameRow.senderId, name)
            }
        })
    }
}

/** 长按会话行的操作面板：置顶 / 免打扰 / 重命名 / 设头像 / 清空（§7.3/§7.4）。 */
@Composable
private fun ConversationMenu(row: ConversationRow, onDismiss: () -> Unit,
    onTogglePin: () -> Unit, onToggleMute: () -> Unit, onToggleLockPrivate: () -> Unit,
    onRename: () -> Unit,
    onSetAvatar: () -> Unit, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val notifier = cn.iamplus.pushbox.notify.Notifier(context)
    // 例外渠道由用户显式建，建完就能在系统设置里单独调；这里只判断有没有，用来决定菜单给哪一组动作
    val hasSenderException = remember(row) {
        notifier.hasSenderException(row.channelNo, row.senderId)
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_dialog_title)) },
            text = { Text(stringResource(R.string.clear_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear(); onDismiss() }) {
                    Text(stringResource(R.string.confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.displayName, maxLines = 1) },
        text = {
            Column {
                MenuOption(stringResource(
                    if (row.pinned) R.string.conv_action_unpin else R.string.conv_action_pin),
                    onTogglePin)
                MenuOption(stringResource(
                    if (row.muted) R.string.conv_action_unmute else R.string.conv_action_mute),
                    onToggleMute)
                MenuOption(stringResource(
                    if (row.lockPrivate) R.string.conv_action_unlock_private
                    else R.string.conv_action_lock_private), onToggleLockPrivate)
                // §7.3 发送者例外：不影响同频道其他发送者；恢复后立刻回到频道渠道
                if (hasSenderException) {
                    MenuOption(stringResource(R.string.conv_action_sender_notify_adjust), {
                        openSystemChannelSettings(context,
                            cn.iamplus.pushbox.notify.Notifier.senderChannelIdOf(
                                row.channelNo, row.senderId))
                        onDismiss()
                    })
                    MenuOption(stringResource(R.string.conv_action_sender_notify_restore), {
                        notifier.removeSenderException(row.channelNo, row.senderId)
                        Toast.makeText(context, R.string.conv_sender_notify_restored,
                            Toast.LENGTH_SHORT).show()
                        onDismiss()
                    })
                } else {
                    MenuOption(stringResource(R.string.conv_action_sender_notify), {
                        notifier.createSenderException(row.channelNo, row.senderId, row.displayName)
                        openSystemChannelSettings(context,
                            cn.iamplus.pushbox.notify.Notifier.senderChannelIdOf(
                                row.channelNo, row.senderId))
                        onDismiss()
                    })
                }
                MenuOption(stringResource(R.string.conv_action_rename), onRename)
                MenuOption(stringResource(R.string.conv_action_set_avatar), onSetAvatar)
                MenuOption(stringResource(R.string.conv_action_clear), { confirmClear = true })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_cancel)) }
        })
}

@Composable
private fun MenuOption(label: String, onClick: () -> Unit, checked: Boolean = false,
    danger: Boolean = false) {
    TextButton(modifier = Modifier.fillMaxWidth(), onClick = onClick,
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            if (checked) Text("✓", color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 弹窗里的一组：一个小标题压着几条 [MenuOption]。频道操作有十项，不分段就是一片同质的按钮。 */
@Composable
private fun DialogGroup(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    content()
}

/** 会话改名弹窗：留空即清除本地名、回落到服务端下发的发送者名。 */
@Composable
private fun RenameDialog(row: ConversationRow, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(row.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim().ifBlank { null }); onDismiss() }) {
                Text(stringResource(R.string.rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_cancel)) }
        })
}

/** 详情页目标：会话 + 可选"要定位到哪条"（从搜索结果点进来时带上）。 */
private data class ConversationTarget(val conversation: ConversationRow,
    val focusMsgId: String? = null)

/** 对话详情（参考微信）：正流展示全部消息、进入即清未读、长按复制/删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationDetail(app: App, conversation: ConversationRow, focusMsgId: String?,
    onBack: () -> Unit) {
    // 详情页覆盖在 shell 之上：系统返回键要收起详情，而不是退出 App
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    // §7.4：对话页不做"全部/未读/已读"筛选（与 PC 同步删掉）。进这一屏本来就会整条标已读，
    // 筛选按钮点下去只会把刚刚还看得见的内容变没，没有读者。
    // §7.4 会话内搜索：输入先防抖再进 Room 的查询参数，
    // 每敲一个字就重查一次全表在慢机上会把列表打字卡住。
    // 关键词与搜索条开合要过旋转：正在搜的时候转一下屏，结果和输入框一起没了等于白敲。
    // 键用会话的两把 id，换会话才重置；`query` 是防抖后的派生物，跟着 rawQuery 重算即可。
    var searchOpen by rememberSaveable(conversation.channelNo, conversation.senderId) {
        mutableStateOf(false)
    }
    var rawQuery by rememberSaveable(conversation.channelNo, conversation.senderId) {
        mutableStateOf("")
    }
    var query by remember(conversation) { mutableStateOf<String?>(null) }
    LaunchedEffect(rawQuery) {
        if (rawQuery.isBlank()) {
            query = null
        } else {
            delay(250)
            query = rawQuery.trim()
        }
    }
    // 同上：null 是"还没读到第一帧"。Room 的第一次发射不是同步的，把空列表当结果
    // 会让每个会话都先闪一下"还没有消息"。
    val rowsState by remember(conversation, query) {
        app.dao.messagesFlow(conversation.channelNo, conversation.senderId, query)
    }.collectAsState(initial = null)
    val rows = rowsState.orEmpty()

    // 告诉收消息那条链路"这一屏正开着"：它据此不弹通知、不给这条涨红点。
    // 键只用两把 id，行本身每来一条消息就是一个新对象，挂上去会来回重注册。
    DisposableEffect(conversation.channelNo, conversation.senderId) {
        ViewedConversation.open(conversation.channelNo, conversation.senderId)
        onDispose { ViewedConversation.close(conversation.channelNo, conversation.senderId) }
    }

    LaunchedEffect(conversation) {
        withContext(Dispatchers.IO) {
            app.dao.markConversationRead(conversation.channelNo, conversation.senderId)
        }
        // 红点清了就要把通知一起撤掉，否则角标停在通知里那个旧 number 上，看着像还有三条没读
        app.notifier.cancelConversation(conversation.channelNo, conversation.senderId)
        // §5.2 已读游标上报：尽力而为，服务端尚未实现该端点时静默失败，本地红点照常清（不阻塞 UI）
        val live = app.identity.load()
        if (live != null) runCatching {
            PushBoxApi(live.baseUrl, app.http).reportRead(live,
                listOf(PushBoxApi.ReadEntry(conversation.channelNo, conversation.senderId,
                    conversation.lastSeq)))
        }
    }

    val listState = rememberLazyListState()
    var selected by remember { mutableStateOf<MessageRow?>(null) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    // 从搜索结果点进来要落在命中那条上：定位完成前先把"滚到最新"按住，否则两个效果互相拽。
    // 命中项只存在于服务端（本机没缓存正文）时这里找不到，退回页尾——那一条点开仍能看摘要。
    var awaitingFocus by remember(conversation, focusMsgId) { mutableStateOf(focusMsgId != null) }
    // 会话内搜索点中一条命中：先退出筛选，等未筛选的那一帧回来再定位——
    // 筛选列表里的下标与全量列表不是一回事，直接拿它 scrollToItem 会落到别的消息上。
    var pendingFocus by remember(conversation) { mutableStateOf<String?>(null) }
    // 键只挂 rowsState：定位完要把 pendingFocus 清掉，若它也当键，清空会取消
    // 这个协程里后半段"延时后取消高亮"，高亮就再也褪不掉了。
    LaunchedEffect(rowsState) {
        val target = pendingFocus ?: return@LaunchedEffect
        if (query != null || rowsState == null) return@LaunchedEffect
        val index = rows.indexOfFirst { it.msgId == target }
        if (index < 0) return@LaunchedEffect
        pendingFocus = null
        listState.scrollToItem(index)
        highlightId = target
        delay(FOCUS_HIGHLIGHT_MILLIS)
        highlightId = null
    }
    LaunchedEffect(awaitingFocus, rows) {
        val target = focusMsgId ?: return@LaunchedEffect
        if (!awaitingFocus) return@LaunchedEffect
        val index = rows.indexOfFirst { it.msgId == target }
        if (index < 0) {
            if (rows.isNotEmpty()) {
                listState.scrollToItem(rows.lastIndex)
                awaitingFocus = false
            }
            return@LaunchedEffect
        }
        listState.scrollToItem(index)
        awaitingFocus = false
        highlightId = target
        delay(FOCUS_HIGHLIGHT_MILLIS)
        highlightId = null
    }
    // §9.3 只在"人本来就贴着底"时跟随新消息。距底不足 2 条也算贴在底——
    // 上一版每次落库都 animateScrollToItem(最后一条)，翻旧账的人每来一条新消息就被拽回页尾。
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 - NEAR_BOTTOM_ITEMS
        }
    }
    var unseen by remember(conversation) { mutableIntStateOf(0) }
    var lastCount by remember(conversation) { mutableIntStateOf(0) }
    var placed by remember(conversation) { mutableStateOf(false) }
    LaunchedEffect(nearBottom) { if (nearBottom) unseen = 0 }
    LaunchedEffect(rows.size, awaitingFocus, pendingFocus) {
        // 正在定位（外部命中或退出筛选）时不抢滚动，也不把这批长度变化算成"新消息"：
        // 跟着把 lastCount 对齐，否则定位结束后 grew 会是整条会话的长度，白白冒一个胶囊。
        if (awaitingFocus || pendingFocus != null) {
            lastCount = rows.size
            return@LaunchedEffect
        }
        if (rows.isEmpty()) return@LaunchedEffect
        val grew = rows.size - lastCount
        lastCount = rows.size
        when {
            // 首次落位只做一次；带命中目标的会话由上面那个 effect 负责，这里不抢
            !placed -> {
                placed = true
                if (focusMsgId == null) listState.animateScrollToItem(rows.lastIndex)
            }

            nearBottom -> listState.animateScrollToItem(rows.lastIndex)
            grew > 0 -> unseen += grew
        }
    }

    Scaffold(topBar = {
        // §9.1 详情页顶栏：标题居中（微信口径），底色与主列表顶栏同一条 #EDEDED。
        PushBoxTopBar(centerTitle = true,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(conversation.displayName, conversation.avatarPath, conversation.senderId, sizeDp = 30)
                    Spacer(Modifier.width(8.dp))
                    Text(conversation.displayName, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            actions = {
                // 只有一个动作就不套一层菜单：放大镜直接开搜索条，少一次点击也少一个"点开发现只有一项"。
                IconButton(onClick = { searchOpen = true }) {
                    Icon(Icons.Filled.Search, contentDescription =
                    stringResource(R.string.conv_search_entry))
                }
            })
    }) { inset ->
        Column(Modifier.fillMaxSize().padding(inset)) {
            if (searchOpen) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = rawQuery, onValueChange = { rawQuery = it },
                        placeholder = { Text(stringResource(R.string.conv_search_hint)) },
                        singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { searchOpen = false; rawQuery = "" }) {
                        Text(stringResource(R.string.conv_search_close))
                    }
                }
            }
            if (query != null && rowsState != null) {
                // 会话内搜索态：点一条就退出，免得用户找不到出口。
                // 24dp 是原来的一行，手指点不中；抬到 Material 的 48dp 最小触控高，底色本身照旧。
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { rawQuery = "" }
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.conv_search_hits, rows.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (rowsState == null) {
                LoadingState()
            } else if (rows.isEmpty()) {
                // P0-3：插画 + 文案的空状态
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp, start = 32.dp, end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 简单的对话气泡图标（使用 Material Icons）
                    Icon(
                        Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(64.dp)
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    
                    Text(
                        stringResource(if (query != null) R.string.conv_search_none
                            else R.string.empty_messages),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    // 副标题必须跟着"为什么空"走：这里已经是一条打开着的会话，
                    // 再劝人"选择一个会话开始聊天"就是句错话
                    Text(
                        stringResource(if (query != null) R.string.conv_search_none_hint
                            else R.string.empty_messages_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(rows, key = { _, row -> row.msgId }) { index, row ->
                            // §7.3 按日期分节：与上一条不同日则插一个分节头（今天/昨天/M月d日）
                            if (index == 0 || dayKey(rows[index - 1].createdAt) != dayKey(row.createdAt)) {
                                DateHeader(dateSectionLabel(row.createdAt))
                            }
                            // 时间智能分组：5分钟内的连续消息只显示一次时间戳
                            val showTime = index == 0 || 
                                (row.createdAt - rows[index - 1].createdAt) > 5 * 60 * 1000L ||
                                dayKey(rows[index - 1].createdAt) != dayKey(row.createdAt)
                            MessageBubble(row, conversation.displayName, showTime = showTime,
                                highlighted = highlightId == row.msgId,
                                onClick = if (query == null) null else {
                                    {
                                        // 先关筛选；定位交给 pendingFocus，等全量那一帧回来再滚
                                        pendingFocus = row.msgId
                                        searchOpen = false
                                        rawQuery = ""
                                    }
                                }) { selected = row }
                        }
                    }
                    // 人在上面读历史时，新消息只在底部报个数，不抢滚动位置
                    if (unseen > 0) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable {
                                    unseen = 0
                                    scope.launch { listState.animateScrollToItem(rows.lastIndex) }
                                }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.conv_new_messages, unseen),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    }

    val target = selected
    if (target != null) {
        MessageActionSheet(target, onDismiss = { selected = null },
            onDelete = {
                scope.launch(Dispatchers.IO) {
                    // 乐观本地即时隐藏（§4.4 D13），顺带把这条的图从缓存丢掉
                    app.dao.tombstoneMessage(target.msgId)
                        ?.let { ImageCache.evictMessage(it.msgId, it.mediaCount) }
                    val live = app.identity.load()
                    if (live != null) runCatching {           // 上报服务端，失败不影响本机
                        PushBoxApi(live.baseUrl, app.http).deleteMessage(live, target.msgId)
                    }
                }
            })
    }
}

/** 定位到命中那条后高亮停留多久。再久一点就会被读成"这条消息有什么特别"。 */
private const val FOCUS_HIGHLIGHT_MILLIS = 1_600L

/** 距列表末尾不足两条就算"贴着底"：新消息可以跟随；再往上就是人在读历史，只报数不抢滚动。 */
private const val NEAR_BOTTOM_ITEMS = 1

/** 长按气泡的操作面板：复制 / 删除（§7.3 长按气泡动作）。 */
@Composable
private fun MessageActionSheet(row: MessageRow, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val copy = rememberCopyAction(messageText(row))
    var confirmDelete by remember { mutableStateOf(false) }
    var showJson by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // §7.4 D17：长按气泡补"保存图片 / 查看原始 JSON"
    val scope = rememberCoroutineScope()
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_dialog_title)) },
            text = { Text(stringResource(R.string.delete_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDismiss(); onDelete() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )) {
                    Text(stringResource(R.string.confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            })
        return
    }
    if (showJson) {
        AlertDialog(
            onDismissRequest = { showJson = false },
            title = { Text("JSON") },
            text = {
                Text(rawMessageJson(row), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = { showJson = false }) {
                    Text(stringResource(R.string.confirm_ok))
                }
            })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_actions_title)) },
        text = {
            Column {
                MenuOption(stringResource(R.string.message_action_copy), copy)
                if (row.mediaCount > 0) {
                    MenuOption(
                        label = stringResource(R.string.message_action_save_image),
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                runCatching { saveMediaToDownloads(context, row.msgId, 0) }
                            }
                        })
                }
                MenuOption(
                    label = stringResource(R.string.message_action_raw_json),
                    onClick = { showJson = true })
                MenuOption(stringResource(R.string.message_action_delete),
                    { confirmDelete = true })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_cancel)) }
        })
}

/** 把消息序列化成易读的 JSON 文本（用于调试/导出排查）。 */
private fun rawMessageJson(row: MessageRow): String = buildString {
    append("{\n")
    append("  \"msg_id\": \"${row.msgId}\",\n")
    append("  \"seq\": ${row.seq},\n")
    append("  \"channel_no\": \"${row.channelNo}\",\n")
    append("  \"sender_id\": \"${row.senderId}\",\n")
    append("  \"msg_type\": \"${row.msgType}\",\n")
    append("  \"title\": ${row.title?.let { "\"$it\"" } ?: "null"},\n")
    append("  \"summary\": ${row.summary?.let { "\"$it\"" } ?: "null"},\n")
    append("  \"body\": ${row.body?.let { "\"$it\"" } ?: "null"},\n")
    append("  \"level\": \"${row.level}\",\n")
    append("  \"link_url\": ${row.linkUrl?.let { "\"$it\"" } ?: "null"},\n")
    append("  \"media_status\": ${row.mediaStatus?.let { "\"$it\"" } ?: "null"},\n")
    append("  \"media_count\": ${row.mediaCount},\n")
    append("  \"created_at\": ${row.createdAt},\n")
    append("  \"read_state\": ${row.readState}\n")
    append("}")
}

/** §7.4 保存媒体到系统下载目录：取第一张图，写进 Download 文件夹并通知 MediaStore。 */
private suspend fun saveMediaToDownloads(context: Context, msgId: String, idx: Int) {
    val app = App.get(context)
    val identity = app.identity.load() ?: return
    val bytes = PushBoxApi(identity.baseUrl, app.http).fetchMedia(identity, msgId, idx, thumb = false)
    val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
    val file = java.io.File(dir, "pushbox_${msgId}_$idx.jpg")
    java.io.FileOutputStream(file).use { it.write(bytes) }
    // 通知系统扫描新文件（不依赖 FileProvider）
    android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null) { _, _ -> }
}

private fun messageText(row: MessageRow): String = row.body ?: row.summary ?: row.title.orEmpty()

@Composable
private fun MessageBubble(row: MessageRow, senderName: String, showTime: Boolean = true,
    highlighted: Boolean = false, onClick: (() -> Unit)? = null, onLong: () -> Unit) {
    val app = App.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    val colors = LocalPushBoxColors.current
    val context = LocalContext.current
    val text = messageText(row)
    val accent = levelAccent(row.level)
    
    // 使用 Column 包裹整个消息，确保气泡主体与头像顶部对齐
    Column {
        Row(verticalAlignment = Alignment.Top) {
            // §7.3 微信式对话流：每条左侧带发送者头像，与列表页/标题栏用的是同一套取图规则
            Avatar(senderName, senderId = row.senderId, sizeDp = 40)
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.Start) {
                // §7.3 消息类型标签：改为带背景色的 Badge 样式
                typeLabel(row.msgType)?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    ) {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Column(
                    Modifier
                        .widthIn(max = 320.dp)
                        .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer
                            else colors.bubble, RoundedCornerShape(8.dp))
                        // §7.3 非 info 级别在气泡左侧加一条竖向色条（warning 琥珀 / error 红）。
                        // 画在自身背景之上而不是另起一个 3dp Box：外层不再需要 IntrinsicSize.Min，
                        // 九宫格里的图片单元是不定高度的，内在测量会在 intrinsic 查询上直接崩。
                        .then(if (accent == null) Modifier else Modifier.drawBehind {
                            drawRect(accent, size = Size(3.dp.toPx(), size.height))
                        })
                        // 边框走 token：原先写死黑色 8% 透明，深色气泡上压一道黑边等于没有
                        .then(Modifier.drawBehind {
                            drawRect(colors.divider, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
                        })
                        // 只在会话内搜索时点得动：那一下是"退出筛选并跳到这条"，
                        // 不搜索时气泡保持原样（点它不该有任何事发生）
                        .pointerInput(text, onClick) {
                            detectTapGestures(onTap = onClick?.let { hit -> { _ -> hit() } },
                                onLongPress = { onLong() })
                        }
                        .padding(10.dp)) {
                    // §4.5 图片链路：有媒体槽就先出图（竖排九宫格），正文（若有）在图下方
                    if (row.mediaCount > 0) {
                        MediaGrid(row.msgId, row.mediaCount, row.mediaStatus) { idx -> row.slotStatus(idx) }
                    }
                    if (text.isNotBlank()) MarkdownBody(text)
                }
                // §5.3 实时帧被 frame-max-bytes 削过正文：本机这条只是前缀，点一下按 id 回捞全文。
                // 落库后 messagesFlow 会重新发射，这个按钮随 truncated 位一起消失。
                if (row.truncated) {
                    var fetching by remember(row.msgId) { mutableStateOf(false) }
                    TextButton(enabled = !fetching, onClick = {
                        scope.launch(Dispatchers.IO) {
                            fetching = true
                            val live = app.identity.load()
                            val full = live?.let {
                                runCatching {
                                    PushBoxApi(it.baseUrl, app.http).fulltext(it, row.msgId)
                                }.getOrNull()
                            }
                            full?.content?.takeIf { it.isNotBlank() }?.let {
                                app.dao.setFulltext(row.msgId, it)
                            }
                            fetching = false
                        }
                    }) {
                        Text(stringResource(if (fetching) R.string.message_fulltext_loading
                            else R.string.message_fulltext))
                    }
                }
                // §4.4 带 link 的消息给一个可点"打开链接"（第三方把 URL 放在 url 字段而非正文）
                row.linkUrl?.takeIf { it.isNotBlank() }?.let { link ->
                    TextButton(onClick = { openUrl(context, link) },
                        modifier = Modifier.padding(start = 0.dp, top = 4.dp)) {
                        Icon(Icons.Default.Link, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.message_open_link), maxLines = 1,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        // 时间智能显示：只在分组第一条或跨日期时显示，放在 Row 外面不影响对齐
        if (showTime) {
            Text(bubbleTimeLabel(row.createdAt), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp, top = 2.dp)) // 48dp = 头像40 + 间距8
        }
    }
}

private fun typeLabel(type: String): String? = when (type) {
    "markdown" -> "Markdown"
    "image" -> "图片"
    "file" -> "文件"
    "audio" -> "音频"
    "video" -> "视频"
    "link" -> "链接"
    else -> null
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

private val INLINE_URL = Regex("""https?://[^\s<>"']+""")
private val INLINE_MD = Regex("""\*\*(.+?)\*\*|`(.+?)`""")

/**
 * §8 轻量 Markdown：仅 **粗体** 与 `行内代码`，并给正文里的裸 URL 上蓝色下划线样式。
 * 分词后一次性拼 AnnotatedString，URL 着色在拼接后的整串上扫描，索引天然对齐；
 * 完整 CommonMark 渲染留给后续引入渲染库，这里不手搓解析器以免边界 bug。
 */
@Composable
private fun renderRichText(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeColor = MaterialTheme.colorScheme.onSurface
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    return buildAnnotatedString {
        var cursor = 0
        INLINE_MD.findAll(text).forEach { m ->
            if (m.range.first > cursor) append(text.substring(cursor, m.range.first))
            val g = m.groups
            when {
                g[1] != null -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(g[1]!!.value); pop()
                }
                g[2] != null -> {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor,
                        background = codeBg))
                    append(g[2]!!.value); pop()
                }
            }
            cursor = m.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
        val plain = toString()
        INLINE_URL.findAll(plain).forEach {
            addStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                it.range.first, it.range.last + 1)
        }
    }
}

/** §7.4/§9.2 轻量 Markdown：标题/列表/引用/代码块(横向滚动)/表格/段落；内联沿用 renderRichText。 */
@Composable
private fun MarkdownBody(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        parseMarkdownBlocks(text).forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(renderRichText(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface)
                // 标题只走 H1–H4 四档，且必须带行高：只写 fontSize 时行高落回字体默认的 ~1.2×，
                // 同一个气泡里 H1 反而比正文（1.53×）更挤
                is MdBlock.Heading -> {
                    val base = when (block.level) {
                        1 -> MaterialTheme.typography.headlineLarge
                        2 -> MaterialTheme.typography.headlineMedium
                        3 -> MaterialTheme.typography.headlineSmall
                        else -> MaterialTheme.typography.bodyLarge
                    }
                    Text(renderRichText(block.text),
                        style = base.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp))
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline))
                    Text(renderRichText(block.text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp))
                }
                is MdBlock.Code -> Box(
                    Modifier.horizontalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(6.dp))) {
                    Text(block.text, fontFamily = FontFamily.Monospace, softWrap = false,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(8.dp))
                }
                is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { i, item ->
                        val bullet = if (block.ordered) "${i + 1}." else "•"
                        Row {
                            Text(bullet, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(renderRichText(item),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                is MdBlock.Table -> Column {
                    block.rows.forEachIndexed { r, cells ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            cells.forEach { c ->
                                Text(renderRichText(c),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f).padding(end = 6.dp))
                            }
                        }
                        if (r == 0) HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

private sealed class MdBlock {
    class Paragraph(val text: String) : MdBlock()
    class Heading(val level: Int, val text: String) : MdBlock()
    class Quote(val text: String) : MdBlock()
    class Code(val text: String) : MdBlock()
    class ListBlock(val items: List<String>, val ordered: Boolean) : MdBlock()
    class Table(val rows: List<List<String>>) : MdBlock()
}

private val MD_HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val MD_UL = Regex("""^\s*[-*+]\s+(.*)$""")
private val MD_OL = Regex("""^\s*\d+\.\s+(.*)$""")
private val MD_QUOTE = Regex("""^\s*>\s?(.*)$""")

/** 逐行分组为块：代码围栏、表格、引用、有序/无序列表、段落；未识别语法降级为普通段落。 */
private fun parseMarkdownBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.split("\n")
    var i = 0
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotEmpty()) {
            out.add(MdBlock.Paragraph(para.toString().trimEnd()))
            para.setLength(0)
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trim().startsWith("```") -> {
                flushPara(); i++
                val code = StringBuilder()
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                i++
                out.add(MdBlock.Code(code.toString().trimEnd('\n')))
            }
            line.contains('|') && i + 1 < lines.size &&
                lines[i + 1].contains(Regex("""[-|: ]+\|""")) &&
                lines[i + 1].contains('-') -> {
                flushPara()
                val rows = mutableListOf<List<String>>()
                rows.add(splitTableRow(line)); i += 2   // 跳过分隔行
                while (i < lines.size && lines[i].contains('|')) {
                    rows.add(splitTableRow(lines[i])); i++
                }
                out.add(MdBlock.Table(rows))
            }
            MD_HEADING.matches(line) -> {
                flushPara()
                val m = MD_HEADING.matchEntire(line)!!
                out.add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()))
                i++
            }
            MD_QUOTE.matches(line) -> {
                flushPara()
                val q = StringBuilder()
                while (i < lines.size && MD_QUOTE.matches(lines[i])) {
                    if (q.isNotEmpty()) q.append('\n')
                    q.append(MD_QUOTE.matchEntire(lines[i])!!.groupValues[1]); i++
                }
                out.add(MdBlock.Quote(q.toString()))
            }
            MD_UL.matches(line) || MD_OL.matches(line) -> {
                flushPara()
                val ordered = MD_OL.matches(line)
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val rx = if (ordered) MD_OL else MD_UL
                    if (!rx.matches(lines[i])) break
                    items.add(rx.matchEntire(lines[i])!!.groupValues[1]); i++
                }
                out.add(MdBlock.ListBlock(items, ordered))
            }
            line.isBlank() -> { flushPara(); i++ }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line); i++
            }
        }
    }
    flushPara()
    if (out.isEmpty()) out.add(MdBlock.Paragraph(src))
    return out
}

private fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|")
        .map { it.trim() }

/** 会话列表/详情页的日期分节头：居中、弱化的时间标签。 */
@Composable
private fun DateHeader(label: String) {
    if (label.isEmpty()) return
    Text(label, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun levelAccent(level: String): Color? = when (level) {
    // 深浅两套各有一档：写死的深红/琥珀在深色气泡上会发闷
    "error" -> LocalPushBoxColors.current.levelError
    "warning" -> LocalPushBoxColors.current.levelWarning
    else -> null
}

/** 频道页：自定义命名创建 + 加入频道（订阅）+ 频道列表 + 重置密钥。 */
@Composable
private fun ChannelsTab(identity: Identity, modifier: Modifier) {
    val app = App.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var channels by remember { mutableStateOf<List<PushBoxApi.ChannelInfo>?>(null) }
    var issued by remember { mutableStateOf<PushBoxApi.Channel?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 本机记过的明文密钥（创建/重置/接入那三个时刻留下的）。服务端只存哈希，
    // 所以"复制密钥"能给的只有这一份；没记过的频道在弹窗里直接说明原因。
    val secrets by app.channelSecrets.collectAsState()
    var archiveTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    var renameTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var clearTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    // §14 App 是唯一控制台（D28）：治理位、容量报表、白名单、换发号码都只能从这里进
    var governanceTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    var capacityTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    var sendersTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    var resetNoTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    // §5.2 接入（订阅）：频道号+密钥登记这台设备；过滤模式是设备级开关，默认关
    var joinNo by remember { mutableStateOf("") }
    var joinSecret by remember { mutableStateOf("") }
    var joinedTip by remember { mutableStateOf<String?>(null) }
    var subscribedOnly by remember { mutableStateOf(false) }
    var subscribeTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    var subscribeSecret by remember { mutableStateOf("") }
    val needBoth = stringResource(R.string.channel_join_needs_both)
    val doneTip = stringResource(R.string.channel_join_done)

    suspend fun reload() {
        runCatching { PushBoxApi(identity.baseUrl, app.http).channelListPage(identity) }
            .onSuccess {
                channels = it.channels
                // 开关只认服务端回显：本机自己记一份，早晚变成"屏幕上开着、服务端关着"
                subscribedOnly = it.subscribedOnly
                // 通知渠道建得比频道页早（第一次收消息时只有号码），拿到名字后回写，系统设置里才不是光秃秃的「频道 号码」
                cn.iamplus.pushbox.notify.Notifier(app)
                    .syncChannelNames(it.channels.associate { ch -> ch.channelNo to ch.name })
            }
            .onFailure { error = it.message ?: it::class.java.simpleName }
    }
    LaunchedEffect(Unit) { reload() }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 对齐 PC 频道面板（§8.4 W16）的五块：新建 / 接入信息 / 加入 / 接收范围 / 我的频道。
        // 每块一张卡，操作收进每行的 ⋮ 里——以前 12 个按钮摊在一行上，一条频道能占满两屏。
        SettingsSection(stringResource(R.string.channel_create)) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.channel_name_optional)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                    scope.launch {
                        busy = true; error = null
                        val created = name.trim().ifEmpty { Build.MODEL ?: "channel" }.take(24)
                        val outcome = runCatching {
                            PushBoxApi(identity.baseUrl, app.http).createChannel(identity, created)
                        }
                        busy = false
                        outcome.fold(
                            onSuccess = { issued = it; app.saveChannelSecret(it.channelNo, it.secret)
                                name = ""; reload() },
                            onFailure = { error = it.message ?: it::class.java.simpleName })
                    }
                }) { Text(stringResource(R.string.channel_create)) }
                OutlinedButton(enabled = !busy, onClick = { scope.launch { reload() } }) {
                    Text(stringResource(R.string.channels_refresh))
                }
            }
        }

        issued?.let { IssuedChannelCard(identity, it) }

        // §5.2 加入频道：把这台设备登记进某个频道的接收集。号码与密钥都手输，
        // 因为服务端只在同 Space 内校验它们成对（跨 Space 一律 404），本机不猜归属
        SettingsSection(stringResource(R.string.channel_join_title)) {
            Text(stringResource(R.string.channel_join_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = joinNo, onValueChange = { joinNo = it },
                label = { Text(stringResource(R.string.channel_join_no_hint)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                value = joinSecret, onValueChange = { joinSecret = it },
                label = { Text(stringResource(R.string.channel_join_secret_hint)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                if (joinNo.isBlank() || joinSecret.isBlank()) {
                    error = needBoth
                    return@Button
                }
                scope.launch {
                    busy = true; error = null; joinedTip = null
                    // 先把这两串抓住：成功后要清输入框，而记档用的正是用户刚敲的这份明文
                    // （joinChannel 是 void，服务端不回显密钥）
                    val no = joinNo.trim()
                    val plain = joinSecret.trim()
                    val outcome = runCatching {
                        PushBoxApi(identity.baseUrl, app.http).joinChannel(identity, no, plain)
                    }
                    busy = false
                    outcome.fold(
                        onSuccess = {
                            app.saveChannelSecret(no, plain)
                            joinNo = ""; joinSecret = ""; joinedTip = doneTip; reload()
                        },
                        onFailure = { error = it.message ?: it::class.java.simpleName })
                }
            }) { Text(stringResource(R.string.channel_join_title)) }
        }

        SettingsSection(stringResource(R.string.channel_filter_only)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.channel_filter_only_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Switch(checked = subscribedOnly, enabled = !busy, onCheckedChange = { want ->
                    scope.launch {
                        busy = true; error = null
                        val outcome = runCatching {
                            PushBoxApi(identity.baseUrl, app.http).setSubscriptionMode(identity, want)
                        }
                        busy = false
                        outcome.fold(
                            onSuccess = { subscribedOnly = it },
                            onFailure = { error = it.message ?: it::class.java.simpleName })
                    }
                })
            }
            // 开着过滤却一个都没接入＝这台设备从此静默，这必须是用户自己选的结论，但要提前说出来
            if (subscribedOnly && channels?.any { it.subscribed } != true) {
                Text(stringResource(R.string.channel_filter_only_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
            }
        }

        if (busy) Text(stringResource(R.string.channel_rotate_working))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        joinedTip?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        SettingsSection(stringResource(R.string.channel_list_title)) {
            val list = channels
            when {
                list == null -> Text(stringResource(R.string.channel_loading))
                list.isEmpty() -> Text(stringResource(R.string.channel_none))
                else -> list.forEachIndexed { index, ch ->
                    ChannelListRow(ch, busy = busy, secret = secrets[ch.channelNo],
                        onRotate = {
                            scope.launch {
                                busy = true; error = null
                                val outcome = runCatching {
                                    PushBoxApi(identity.baseUrl, app.http)
                                        .rotateSecret(identity, ch.channelNo)
                                }
                                busy = false
                                outcome.fold(
                                    onSuccess = { issued = it
                                        app.saveChannelSecret(it.channelNo, it.secret) },
                                    onFailure = { error = it.message ?: it::class.java.simpleName })
                            }
                        },
                        onArchive = { archiveTarget = ch },
                        onRename = { renameTarget = ch; renameText = ch.name },
                        onClear = { clearTarget = ch },
                        onGovernance = { governanceTarget = ch },
                        onCapacity = { capacityTarget = ch },
                        onSenders = { sendersTarget = ch },
                        onResetNo = { resetNoTarget = ch },
                        onSubscribe = { subscribeTarget = ch; subscribeSecret = "" },
                        onUnsubscribe = {
                            scope.launch {
                                busy = true; error = null
                                val outcome = runCatching {
                                    PushBoxApi(identity.baseUrl, app.http).leaveChannel(identity, ch.channelNo)
                                }
                                busy = false
                                outcome.fold(
                                    onSuccess = { joinedTip = doneTip; reload() },
                                    onFailure = { error = it.message ?: it::class.java.simpleName })
                            }
                        })
                    if (index < list.lastIndex) {
                        HorizontalDivider(thickness = 0.5.dp,
                            color = LocalPushBoxColors.current.divider)
                    }
                }
            }
        }
    }
    archiveTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { archiveTarget = null },
            title = { Text(stringResource(R.string.channel_archive_title)) },
            text = { Text(stringResource(R.string.channel_archive_desc)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    archiveTarget = null
                    scope.launch {
                        busy = true; error = null
                        runCatching {
                            PushBoxApi(identity.baseUrl, app.http).archiveChannel(identity, t.channelNo)
                        }.onFailure { error = it.message ?: it::class.java.simpleName }
                        busy = false
                        reload()
                    }
                }, colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )) { Text(stringResource(R.string.confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { archiveTarget = null }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            })
    }
    renameTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.channel_rename_title)) },
            text = {
                OutlinedTextField(
                    value = renameText, onValueChange = { renameText = it },
                    label = { Text(stringResource(R.string.channel_rename_hint)) },
                    singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val target = renameTarget
                    renameTarget = null
                    if (target != null) scope.launch {
                        busy = true; error = null
                        runCatching {
                            PushBoxApi(identity.baseUrl, app.http)
                                .renameChannel(identity, target.channelNo, renameText.trim())
                        }.onFailure { error = it.message ?: it::class.java.simpleName }
                        busy = false
                        reload()
                    }
                }) { Text(stringResource(R.string.confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            })
    }
    clearTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { clearTarget = null },
            title = { Text(stringResource(R.string.channel_clear_title)) },
            text = { Text(stringResource(R.string.channel_clear_desc)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    clearTarget = null
                    scope.launch {
                        busy = true; error = null
                        runCatching {
                            PushBoxApi(identity.baseUrl, app.http).clearChannel(identity, t.channelNo)
                        }.onFailure { error = it.message ?: it::class.java.simpleName }
                        busy = false
                    }
                }, colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )) { Text(stringResource(R.string.confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { clearTarget = null }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            })
    }
    governanceTarget?.let { t ->
        ChannelGovernanceDialog(identity, t,
            onDismiss = { governanceTarget = null },
            onSaved = { governanceTarget = null; scope.launch { reload() } },
            onError = { error = it })
    }
    capacityTarget?.let { t ->
        ChannelCapacityDialog(identity, t, onDismiss = { capacityTarget = null })
    }
    sendersTarget?.let { t ->
        ChannelSendersDialog(identity, t, onDismiss = { sendersTarget = null },
            onError = { error = it })
    }
    resetNoTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { resetNoTarget = null },
            title = { Text(stringResource(R.string.channel_reset_no_title)) },
            text = { Text(stringResource(R.string.channel_reset_no_desc)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    resetNoTarget = null
                    scope.launch {
                        busy = true; error = null
                        val outcome = runCatching {
                            PushBoxApi(identity.baseUrl, app.http)
                                .resetChannelNo(identity, t.channelNo)
                        }
                        busy = false
                        // 服务端换了对外号码：明文密钥没变，但接入地址要按新号重抄一份
                        outcome.fold(
                            onSuccess = { issued = PushBoxApi.Channel(it, "", "")
                                // 记档跟着号码走，否则新号码的「复制密钥」会查不到（密钥其实没变）
                                secrets[t.channelNo]?.let { old ->
                                    app.saveChannelSecret(t.channelNo, "")
                                    app.saveChannelSecret(it, old)
                                } },
                            onFailure = { error = it.message ?: it::class.java.simpleName })
                        reload()
                    }
                }) { Text(stringResource(R.string.confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { resetNoTarget = null }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            })
    }
    // 同一张列表里的频道只差密钥：号码已经在这行了，再让人抄一遍频道号只会多出一种打错的方式
    subscribeTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { subscribeTarget = null },
            title = { Text(stringResource(R.string.channel_join_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.channelNo)
                    OutlinedTextField(
                        value = subscribeSecret, onValueChange = { subscribeSecret = it },
                        label = { Text(stringResource(R.string.channel_join_secret_hint)) },
                        singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && subscribeSecret.isNotBlank(), onClick = {
                    val target = subscribeTarget
                    subscribeTarget = null
                    if (target != null) scope.launch {
                        busy = true; error = null; joinedTip = null
                        val outcome = runCatching {
                            PushBoxApi(identity.baseUrl, app.http)
                                .joinChannel(identity, target.channelNo, subscribeSecret)
                        }
                        busy = false
                        outcome.fold(
                            onSuccess = {
                                app.saveChannelSecret(target.channelNo, subscribeSecret.trim())
                                joinedTip = doneTip; reload()
                            },
                            onFailure = { error = it.message ?: it::class.java.simpleName })
                    }
                }) { Text(stringResource(R.string.confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { subscribeTarget = null }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            })
    }
}

/**
 * §14 频道治理位编辑。数值字段一律留空即"不提交这一项"：
 * 服务端 PUT 区分"没提交"与"提交 null"，把空输入框当 0 发过去会静默清掉用户没打算动的限额。
 */
@Composable
private fun ChannelGovernanceDialog(identity: Identity, channel: PushBoxApi.ChannelInfo,
    onDismiss: () -> Unit, onSaved: () -> Unit, onError: (String) -> Unit) {
    val app = App.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var maxImages by remember { mutableStateOf(channel.maxImages.toString()) }
    var rateLimit by remember { mutableStateOf(channel.rateLimit.toString()) }
    var bodyBytes by remember { mutableStateOf(channel.maxBodyBytes.toString()) }
    var dailyMsg by remember { mutableStateOf(channel.dailyMsgLimit.toString()) }
    var dailyImage by remember { mutableStateOf(channel.dailyImageLimit.toString()) }
    var dailyBytes by remember { mutableStateOf(channel.dailyBytesLimit.toString()) }
    var strictAuth by remember { mutableStateOf(channel.strictAuth) }
    var muted by remember { mutableStateOf(channel.muted) }
    var whitelist by remember { mutableStateOf(channel.senderWhitelistMode) }
    // D25 提醒强度上限：info < warning < error，第三方自报的 level 会被截断到这里。
    // 它以前是列表行上的三个按钮，现在跟其余治理位一起收在这张卡里。
    var maxLevel by remember { mutableStateOf(channel.maxLevel) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.channel_governance_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.channel_max_level),
                    style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("info", "warning", "error").forEach { lvl ->
                        ChoiceChip(lvl, selected = maxLevel == lvl) { maxLevel = lvl }
                    }
                }
                NumberField(stringResource(R.string.channel_g_max_images), maxImages) { maxImages = it }
                NumberField(stringResource(R.string.channel_g_rate_limit), rateLimit) { rateLimit = it }
                NumberField(stringResource(R.string.channel_g_body_bytes), bodyBytes) { bodyBytes = it }
                NumberField(stringResource(R.string.channel_g_daily_msg), dailyMsg) { dailyMsg = it }
                NumberField(stringResource(R.string.channel_g_daily_image), dailyImage) { dailyImage = it }
                NumberField(stringResource(R.string.channel_g_daily_bytes), dailyBytes) { dailyBytes = it }
                Text(stringResource(R.string.channel_g_hint_zero),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                MenuOption(stringResource(R.string.channel_g_strict_auth),
                    { strictAuth = !strictAuth }, checked = strictAuth)
                MenuOption(stringResource(R.string.channel_g_muted),
                    { muted = !muted }, checked = muted)
                MenuOption(stringResource(R.string.channel_g_whitelist),
                    { whitelist = !whitelist }, checked = whitelist)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    // 与当前值相同的字段发 null＝不提交：既少写一遍库，也避免把
                    // 服务端 clamp 过的值（如 max_images 上限 16）原样回写成越界值
                    val patch = PushBoxApi.ChannelPatch(
                        maxLevel = maxLevel.takeIf { it != channel.maxLevel },
                        maxImages = changedInt(maxImages, channel.maxImages),
                        rateLimit = changedInt(rateLimit, channel.rateLimit),
                        maxBodyBytes = changedInt(bodyBytes, channel.maxBodyBytes),
                        dailyMsgLimit = changedInt(dailyMsg, channel.dailyMsgLimit),
                        dailyImageLimit = changedInt(dailyImage, channel.dailyImageLimit),
                        dailyBytesLimit = changedLong(dailyBytes, channel.dailyBytesLimit),
                        strictAuth = strictAuth.takeIf { it != channel.strictAuth },
                        muted = muted.takeIf { it != channel.muted },
                        senderWhitelistMode = whitelist.takeIf { it != channel.senderWhitelistMode },
                    )
                    val outcome = runCatching {
                        PushBoxApi(identity.baseUrl, app.http)
                            .updateChannel(identity, channel.channelNo, patch)
                    }
                    busy = false
                    outcome.fold(onSuccess = { onSaved() },
                        onFailure = { onError(it.message ?: it::class.java.simpleName); onDismiss() })
                }
            }) { Text(stringResource(R.string.channel_governance_save)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.confirm_cancel))
            }
        })
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true, modifier = Modifier.fillMaxWidth())
}

/** 只有真的改了才提交；非数字（含空串）也视作"没改"，让校验交给服务端。 */
private fun changedInt(text: String, current: Int): Int? =
    text.toIntOrNull()?.takeIf { it != current }

private fun changedLong(text: String, current: Long): Long? =
    text.toLongOrNull()?.takeIf { it != current }

/** §14 容量报表：服务端按频道统计，用来判断"该清一清了"。 */
@Composable
private fun ChannelCapacityDialog(identity: Identity, channel: PushBoxApi.ChannelInfo,
    onDismiss: () -> Unit) {
    val app = App.get(LocalContext.current)
    val report by produceState<PushBoxApi.Capacity?>(initialValue = null, channel) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                PushBoxApi(identity.baseUrl, app.http).channelCapacity(identity, channel.channelNo)
            }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.channel_capacity)) },
        text = {
            val r = report
            if (r == null) Text(stringResource(R.string.channel_capacity_loading))
            else Text(stringResource(R.string.channel_capacity_body,
                r.totalMessages, humanBytes(r.totalTextBytes), r.totalImages,
                humanBytes(r.totalImageBytes),
                if (r.earliestMsgAt > 0) dateTimeLabel(r.earliestMsgAt) else "—"))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_ok)) }
        })
}

/** §14 白名单：开模式前必须先加人，服务端那条 400 的文案就是照这个界面写的。 */
@Composable
private fun ChannelSendersDialog(identity: Identity, channel: PushBoxApi.ChannelInfo,
    onDismiss: () -> Unit, onError: (String) -> Unit) {
    val app = App.get(LocalContext.current)
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<PushBoxApi.SenderEntry>?>(null) }
    var newSender by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    suspend fun reload() {
        runCatching { PushBoxApi(identity.baseUrl, app.http).listSenders(identity, channel.channelNo) }
            .onSuccess { entries = it }
            .onFailure { onError(it.message ?: it::class.java.simpleName) }
    }
    LaunchedEffect(channel) { reload() }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.channel_senders_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newSender, onValueChange = { newSender = it },
                        label = { Text(stringResource(R.string.channel_senders_hint)) },
                        singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TextButton(enabled = !busy && newSender.isNotBlank(), onClick = {
                        scope.launch {
                            busy = true
                            runCatching {
                                PushBoxApi(identity.baseUrl, app.http).putSender(identity,
                                    channel.channelNo, newSender.trim(), true)
                            }.onFailure { onError(it.message ?: it::class.java.simpleName) }
                            newSender = ""
                            busy = false
                            reload()
                        }
                    }) { Text(stringResource(R.string.channel_senders_add)) }
                }
                val list = entries
                when {
                    list == null -> Text(stringResource(R.string.channel_loading))
                    list.isEmpty() -> Text(stringResource(R.string.channel_senders_empty),
                        style = MaterialTheme.typography.bodyMedium)
                    else -> list.forEach { e ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(e.senderId, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f), maxLines = 1)
                            Text(stringResource(if (e.enabled) R.string.channel_senders_enabled
                                else R.string.channel_senders_disabled),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(enabled = !busy, onClick = {
                                scope.launch {
                                    busy = true
                                    runCatching {
                                        PushBoxApi(identity.baseUrl, app.http).putSender(identity,
                                            channel.channelNo, e.senderId, !e.enabled)
                                    }.onFailure { onError(it.message ?: it::class.java.simpleName) }
                                    busy = false
                                    reload()
                                }
                            }) { Text(stringResource(R.string.channel_senders_toggle)) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_ok)) }
        })
}

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 创建/重置成功后唯一能看到明文密钥的地方：频道号、密钥、curl 命令各带一个复制。 */
@Composable
private fun IssuedChannelCard(identity: Identity, channel: PushBoxApi.Channel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.channel_created),
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.secret_once_hint),
                style = MaterialTheme.typography.bodyMedium)
            CopyableField(stringResource(R.string.field_channel_no), channel.channelNo)
            CopyableField(stringResource(R.string.field_channel_secret), channel.secret)
            Text(stringResource(R.string.channel_curl_hint),
                style = MaterialTheme.typography.bodyMedium)
            CopyableField(stringResource(R.string.field_curl), curlExample(identity, channel))
        }
    }
}

/**
 * 频道列表的一行：一行放得下（名称 + 频道号 + 接入状态），操作全收进 ⋮。
 * 之前这张卡把每个动作都摊成一个按钮，一条频道占两屏高，频道一多整页就没法看了。
 */
@Composable
private fun ChannelListRow(channel: PushBoxApi.ChannelInfo, busy: Boolean, secret: String?,
    onRotate: () -> Unit, onArchive: () -> Unit,
    onRename: () -> Unit, onClear: () -> Unit, onGovernance: () -> Unit,
    onCapacity: () -> Unit, onSenders: () -> Unit, onResetNo: () -> Unit,
    onSubscribe: () -> Unit, onUnsubscribe: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val copyNo = rememberCopyAction(channel.channelNo)
    // 本机没记档时这个闭包不会被触发（弹窗里那一项直接不出现），给个空串只为满足 remember 的位置
    val copySecret = rememberCopyAction(secret.orEmpty())
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(channel.name.ifBlank { stringResource(R.string.channel_unnamed) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                // §5.2 接入标记是设备级的：同一张列表在另一台设备上可以全是"未接入"
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (channel.subscribed) R.string.channel_subscribed
                    else R.string.channel_not_subscribed),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (channel.subscribed) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
                if (channel.muted) {
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.channel_flag_muted),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row {
                Text(channel.channelNo, style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false))
                Text(" · ${channel.maxLevel}", style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(enabled = !busy, onClick = { menu = true }) {
            Icon(Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.channel_actions))
        }
    }
    if (menu) {
        val dismiss: () -> Unit = { menu = false }
        ChannelActionsDialog(channel, secret, dismiss, copyNo, copySecret,
            onSubscribe = onSubscribe, onUnsubscribe = onUnsubscribe, onRename = onRename,
            onGovernance = { dismiss(); onGovernance() },
            onSenders = { dismiss(); onSenders() },
            onCapacity = { dismiss(); onCapacity() },
            onRotate = { dismiss(); onRotate() },
            onResetNo = { dismiss(); onResetNo() },
            onClear = { dismiss(); onClear() },
            onArchive = { dismiss(); onArchive() })
    }
}

/**
 * ⋮ 的内容。原先是 10 项 `DropdownMenu`：拉到屏幕底、危险项夹在中间，而且"密钥"这件事
 * 根本没地方交代——服务端只存 sha256，本机没记档时用户点了复制只会拿到一个空。
 * 弹窗能分组、能放说明文字，项再多也滚得动。
 */
@Composable
private fun ChannelActionsDialog(channel: PushBoxApi.ChannelInfo, secret: String?,
    onDismiss: () -> Unit, copyNo: () -> Unit, copySecret: () -> Unit,
    onSubscribe: () -> Unit, onUnsubscribe: () -> Unit, onRename: () -> Unit,
    onGovernance: () -> Unit, onSenders: () -> Unit, onCapacity: () -> Unit,
    onRotate: () -> Unit, onResetNo: () -> Unit, onClear: () -> Unit, onArchive: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(channel.name.ifBlank { stringResource(R.string.channel_unnamed) },
            maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(channel.channelNo, style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                DialogGroup(stringResource(R.string.channel_grp_access)) {
                    MenuOption(stringResource(if (channel.subscribed) R.string.channel_unsubscribe
                        else R.string.channel_subscribe),
                        onClick = if (channel.subscribed) onUnsubscribe else onSubscribe)
                    MenuOption(stringResource(R.string.channel_rename), onClick = onRename)
                    MenuOption(stringResource(R.string.channel_copy_no), onClick = copyNo)
                }
                DialogGroup(stringResource(R.string.channel_grp_secret)) {
                    if (secret != null) {
                        // 只给复制、不把明文显示出来：这一屏随时可能被人从肩膀后面看见
                        MenuOption(stringResource(R.string.channel_copy_secret),
                            onClick = copySecret)
                    } else {
                        Text(stringResource(R.string.channel_secret_missing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DialogGroup(stringResource(R.string.channel_grp_govern)) {
                    MenuOption(stringResource(R.string.channel_governance), onClick = onGovernance)
                    MenuOption(stringResource(R.string.channel_senders), onClick = onSenders)
                    MenuOption(stringResource(R.string.channel_capacity), onClick = onCapacity)
                }
                DialogGroup(stringResource(R.string.channel_grp_danger)) {
                    MenuOption(stringResource(R.string.channel_rotate), onClick = onRotate)
                    MenuOption(stringResource(R.string.channel_reset_no), onClick = onResetNo)
                    MenuOption(stringResource(R.string.channel_clear),
                        danger = true, onClick = onClear)
                    MenuOption(stringResource(R.string.channel_archive),
                        danger = true, onClick = onArchive)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_close)) }
        })
}

/** §5.2 设备行：名称 + 平台 + 最近在线；本机标「当前」，他端给吊销入口，已吊销只读。 */
@Composable
private fun DeviceRow(dev: PushBoxApi.DeviceInfo, busy: Boolean, onRevoke: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(dev.name.ifBlank { dev.deviceId }, style = MaterialTheme.typography.titleMedium)
            val seen = if (dev.lastSeenAt > 0) dateTimeLabel(dev.lastSeenAt) else "-"
            val sub = buildString {
                if (dev.platform.isNotBlank()) append(dev.platform).append(" · ")
                append(stringResource(R.string.device_last_seen)).append(' ').append(seen)
            }
            Text(sub, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            dev.current -> Text(stringResource(R.string.device_current),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            dev.revoked -> Text(stringResource(R.string.device_revoked),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error)
            else -> OutlinedButton(enabled = !busy, onClick = onRevoke) {
                Text(stringResource(R.string.device_revoke))
            }
        }
    }
}

/** 关于页的版本块：自动检查开关 + 手动"检查更新"。查到新版只把它交给根节点的 [UpdateConfirmDialog]，
 *  下载并安装这一步统一在那只确认框里点，和冷启动静默查到的走同一条路。 */
@Composable
private fun UpdateControls(context: android.content.Context, app: App,
    scope: kotlinx.coroutines.CoroutineScope) {
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val current = remember { cn.iamplus.pushbox.net.UpdateApi.currentVersion(context) }
    val autoCheck by app.autoCheckUpdate.collectAsState()

    fun doCheck() {
        checking = true; error = null; status = null
        scope.launch {
            runCatching { cn.iamplus.pushbox.net.UpdateApi.check(app.http) }
                .onSuccess {
                    // 发现新版就交给确认框：这里不留下载按钮，避免"两个入口都能起下载"
                    if (it.apkUrl != null &&
                        cn.iamplus.pushbox.net.UpdateApi.isNewer(it.version, current.versionName)) {
                        app.pendingUpdate.value = it
                    } else if (it.apkUrl == null) {
                        status = context.getString(R.string.update_no_release)
                    } else {
                        status = context.getString(R.string.update_latest)
                    }
                }
                .onFailure { error = it.message ?: it::class.java.simpleName }
            checking = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_auto_check), style = bodyMedium)
                Text(stringResource(R.string.update_auto_check_desc),
                    style = bodyMedium, color = variant)
            }
            Switch(checked = autoCheck, onCheckedChange = { app.setAutoCheckUpdate(it) })
        }
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.update_source),
                style = bodyMedium, color = variant, modifier = Modifier.weight(1f))
            TextButton(onClick = { doCheck() }, enabled = !checking) {
                Text(stringResource(if (checking) R.string.update_checking else R.string.update_check))
            }
        }
        status?.let { Text(it, style = bodyMedium, color = variant) }
        error?.let { Text(it, style = bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * 发现新版的确认框：挂在 [PushBoxRoot] 上观察 App.pendingUpdate。
 * 文案只"问一句"，点"下载并安装"才真的取包；下完拉起系统安装面板——Android 无法静默自装，这步系统强制用户点。
 */
@Composable
private fun UpdateConfirmDialog(app: App) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by app.pendingUpdate.collectAsState()
    val info = latest ?: return
    var progress by remember(info) { mutableStateOf<Pair<Long, Long>?>(null) }
    var error by remember(info) { mutableStateOf<String?>(null) }
    val bodyMedium = MaterialTheme.typography.bodyMedium
    val variant = MaterialTheme.colorScheme.onSurfaceVariant

    fun startInstall(file: java.io.File) {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            // 未授予"安装未知应用"：先跳系统设置页，用户开回来后再点一次才会真正拉起安装
            val settings = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
                .onFailure { error = "无法打开系统设置：${it.message}" }
            error = "请先在系统设置里允许本应用安装未知应用，再回来点一次。"
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { error = "无法打开安装面板：${it.message}" }
    }

    fun startDownload() {
        val url = info.apkUrl ?: return
        error = null
        scope.launch {
            runCatching {
                val dir = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
                val dest = java.io.File(dir, info.apkName)
                cn.iamplus.pushbox.net.UpdateApi.download(app.http, url, dest) { d, t ->
                    progress = d to t
                }
                dest
            }.onSuccess { file ->
                // 下完就撤掉待处理版本：确认框随即消失（latest 变 null），安装面板接手这一屏
                app.pendingUpdate.value = null
                startInstall(file)
            }.onFailure { error = it.message ?: it::class.java.simpleName }
            progress = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (progress == null) app.pendingUpdate.value = null },
        title = { Text(stringResource(R.string.update_available, info.version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.update_confirm_body), style = bodyMedium, color = variant)
                if (info.notes.isNotBlank()) {
                    Text(info.notes.take(600), style = bodyMedium, color = variant)
                }
                val p = progress
                if (p != null) {
                    val pct = if (p.second > 0) (p.first * 100 / p.second).toInt() else 0
                    Text(stringResource(R.string.update_downloading, "$pct%"),
                        style = bodyMedium, color = variant)
                } else {
                    Text(stringResource(R.string.update_install_hint), style = bodyMedium, color = variant)
                }
                error?.let { Text(it, style = bodyMedium, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { startDownload() }, enabled = progress == null) {
                Text(stringResource(R.string.update_download))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (progress == null) app.pendingUpdate.value = null }) {
                Text(stringResource(R.string.update_later))
            }
        }
    )
}

/**
 * 「我的 → 设置」：首屏只放每天会动的开关与一层入口，长说明都收进二级页。
 * 二级页的顶栏与返回键由 MainShell 负责，这里只按页分发内容。
 */
private enum class SettingsPage(@param:StringRes val title: Int) {
    Connect(R.string.settings_page_connect),
    Notify(R.string.settings_page_notify),
    Security(R.string.settings_page_security),
    KeepAlive(R.string.settings_page_keepalive),
    Data(R.string.settings_page_data),
    About(R.string.settings_page_about),
}

@Composable
private fun SettingsTab(identity: Identity, page: SettingsPage?, onPage: (SettingsPage?) -> Unit,
    modifier: Modifier, onLogout: () -> Unit) {
    // 进二级页时首屏会离开组合，滚动位置也跟着丢；按页名留一份 saveable 状态，
    // 返回时还停在原来那一屏，而不是被弹回顶部。
    val states = rememberSaveableStateHolder()
    states.SaveableStateProvider(page?.name ?: "root") {
        when (page) {
            null -> SettingsRoot(identity, modifier, onPage)
            SettingsPage.Connect -> SettingsConnectPage(identity, modifier, onLogout)
            SettingsPage.Notify -> SettingsNotifyPage(identity, modifier)
            SettingsPage.Security -> SettingsSecurityPage(modifier)
            SettingsPage.KeepAlive -> SettingsKeepAlivePage(modifier)
            SettingsPage.Data -> SettingsDataPage(modifier)
            SettingsPage.About -> SettingsAboutPage(modifier)
        }
    }
}

/** 首屏与六个二级页共用的滚动列：16dp 边距、20dp 组间距。 */
@Composable
private fun SettingsPageColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}

/** 设置首屏：连接、通知、外观、存储四组日常项，加一组通往二级页的入口。 */
@Composable
private fun SettingsRoot(identity: Identity, modifier: Modifier, onPage: (SettingsPage?) -> Unit) {
    val context = LocalContext.current
    val app = App.get(context)
    val scope = rememberCoroutineScope()
    val notif by app.notificationsEnabled.collectAsState()
    val themeMode by app.themeMode.collectAsState()
    val fontScale by app.fontScale.collectAsState()
    val summaryLines by app.summaryLines.collectAsState()
    val quietOn by app.quietEnabled.collectAsState()
    val quietStart by app.quietStart.collectAsState()
    val quietEnd by app.quietEnd.collectAsState()
    val lockContent by app.lockScreenContent.collectAsState()
    // 每次回到首屏重算一次：二级页里清完缓存返回，这两行会重新量。
    val localCount by produceState(initialValue = -1) {
        value = withContext(Dispatchers.IO) { app.dao.messageCount() }
    }
    val storageBytes by produceState(initialValue = -1L, localCount) {
        value = withContext(Dispatchers.IO) { measureLocalStorage(context) }
    }

    SettingsPageColumn(modifier) {
        SettingsSection(stringResource(R.string.section_connection), heading = true) {
            // 连接状态本身顶栏一直挂着，这里只放它在设置页里才有意义的两项
            InfoRow(stringResource(R.string.current_server), identity.baseUrl.ifBlank { "-" })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            GuideRow(stringResource(R.string.settings_page_connect),
                stringResource(R.string.settings_entry_connect_desc)) { onPage(SettingsPage.Connect) }
        }

        SettingsSection(stringResource(R.string.section_notification), heading = true) {
            SwitchRow(stringResource(R.string.notif_new_message),
                stringResource(R.string.notif_new_message_desc), notif) {
                app.notificationsEnabled.value = it
                scope.launch { app.identity.setNotificationsEnabled(it) }
            }
            // 应用内这个开关只管"要不要发"，Android 13+ 上面还压着一道系统级权限：
            // 权限被拒（尤其是勾了"不再询问"）时消息照样入库但永不出通知，所以长期入口挂在这儿。
            NotifyPermissionRow()
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow(stringResource(R.string.notif_lock_screen_title),
                stringResource(R.string.notif_lock_screen_desc), lockContent) {
                app.setLockScreenContent(it)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // §7.4 静默时段：窗口内非 error 消息只静默入通知栏，不响铃不横幅
            SwitchRow(stringResource(R.string.notif_quiet_title),
                stringResource(R.string.notif_quiet_desc), quietOn) {
                app.setQuiet(it, quietStart, quietEnd)
            }
            if (quietOn) {
                val startLabel = stringResource(R.string.notif_quiet_start)
                val endLabel = stringResource(R.string.notif_quiet_end)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuietTimeButton(startLabel, quietStart, Modifier.weight(1f)) {
                        pickTime(context, startLabel, quietStart) { m ->
                            app.setQuiet(true, m, quietEnd)
                        }
                    }
                    QuietTimeButton(endLabel, quietEnd, Modifier.weight(1f)) {
                        pickTime(context, endLabel, quietEnd) { m ->
                            app.setQuiet(true, quietStart, m)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            GuideRow(stringResource(R.string.settings_page_notify),
                stringResource(R.string.settings_entry_notify_desc)) { onPage(SettingsPage.Notify) }
        }

        SettingsSection(stringResource(R.string.section_appearance), heading = true) {
            ThemeOption(stringResource(R.string.theme_system), themeMode == "system") {
                app.setThemeMode("system")
            }
            ThemeOption(stringResource(R.string.theme_light), themeMode == "light") {
                app.setThemeMode("light")
            }
            ThemeOption(stringResource(R.string.theme_dark), themeMode == "dark") {
                app.setThemeMode("dark")
            }
            // §7.4 字号：small/normal/large，即时生效，只缩放文字
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.appearance_font_scale),
                style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip(stringResource(R.string.font_small), fontScale == "small") {
                    app.setFontScale("small")
                }
                ChoiceChip(stringResource(R.string.font_normal), fontScale == "normal") {
                    app.setFontScale("normal")
                }
                ChoiceChip(stringResource(R.string.font_large), fontScale == "large") {
                    app.setFontScale("large")
                }
            }
            // §7.4 摘要行数：会话列表摘要 1 / 2 行
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.appearance_summary_lines),
                style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChoiceChip(stringResource(R.string.summary_lines_1), summaryLines == 1) {
                    app.setSummaryLines(1)
                }
                ChoiceChip(stringResource(R.string.summary_lines_2), summaryLines == 2) {
                    app.setSummaryLines(2)
                }
            }
        }

        SettingsSection(stringResource(R.string.section_data), heading = true) {
            InfoRow(stringResource(R.string.data_local_count_label),
                if (localCount < 0) "-" else localCount.toString())
            InfoRow(stringResource(R.string.data_local_size_label),
                if (storageBytes < 0L) "-"
                else android.text.format.Formatter.formatShortFileSize(context, storageBytes))
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            GuideRow(stringResource(R.string.settings_page_data),
                stringResource(R.string.settings_entry_data_desc)) { onPage(SettingsPage.Data) }
        }

        SettingsSection(stringResource(R.string.section_more), heading = true) {
            GuideRow(stringResource(R.string.section_app_lock),
                stringResource(R.string.settings_entry_security_desc)) { onPage(SettingsPage.Security) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            GuideRow(stringResource(R.string.section_keepalive),
                stringResource(R.string.settings_entry_keepalive_desc)) { onPage(SettingsPage.KeepAlive) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            GuideRow(stringResource(R.string.settings_page_about),
                stringResource(R.string.settings_entry_about_desc)) { onPage(SettingsPage.About) }
        }
    }
}

/** 二级页「接入与设备」：当前地址、设备身份与恢复码、本空间全部设备。 */
@Composable
private fun SettingsConnectPage(identity: Identity, modifier: Modifier, onLogout: () -> Unit) {
    val context = LocalContext.current
    val app = App.get(context)
    val scope = rememberCoroutineScope()
    var showConfirm by remember { mutableStateOf(false) }
    var revealCode by remember { mutableStateOf(false) }
    var rotating by remember { mutableStateOf(false) }
    var rotateConfirm by remember { mutableStateOf(false) }
    var rotationError by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<PushBoxApi.DeviceInfo>?>(null) }
    var devicesTick by remember { mutableIntStateOf(0) }
    var devicesError by remember { mutableStateOf<String?>(null) }
    var busyDevice by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<PushBoxApi.DeviceInfo?>(null) }
    LaunchedEffect(devicesTick) {
        devices = null; devicesError = null
        runCatching { PushBoxApi(identity.baseUrl, app.http).listDevices(identity) }
            .onSuccess { devices = it }
            .onFailure { devicesError = it.message ?: it::class.java.simpleName }
    }

    SettingsPageColumn(modifier) {
        SettingsSection(stringResource(R.string.section_server), heading = true) {
            InfoRow(stringResource(R.string.current_server), identity.baseUrl.ifBlank { "-" })
            TextButton(enabled = !showConfirm, onClick = { showConfirm = true }) {
                Text(stringResource(R.string.change_server))
            }
            Text(stringResource(R.string.change_server_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsSection(stringResource(R.string.section_device), heading = true) {
            InfoRow(stringResource(R.string.device_name_label), Build.MODEL ?: "-")
            CopyableField(stringResource(R.string.device_id_label), identity.deviceId)
            RecoveryCodeRow(identity.recoveryCode, revealed = revealCode,
                onToggle = { revealCode = !revealCode })
            // §14 轮换恢复码：旧码立即作废（已加入的设备不受影响，只影响后续扫码添加）
            rotationError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
            }
            TextButton(enabled = !rotating, onClick = { rotationError = null; rotateConfirm = true }) {
                Text(stringResource(R.string.recovery_code_rotate))
            }
        }

        // §5.2/D26 设备与同步：列出本空间全部设备，可远程吊销他端（作废 token + 踢下线）
        SettingsSection(stringResource(R.string.section_devices), heading = true) {
            devicesError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
            }
            val list = devices
            when {
                list == null && devicesError == null ->
                    Text(stringResource(R.string.devices_loading),
                        style = MaterialTheme.typography.bodyMedium)
                list != null && list.isEmpty() ->
                    Text(stringResource(R.string.devices_none),
                        style = MaterialTheme.typography.bodyMedium)
                else -> list?.forEach { dev ->
                    DeviceRow(dev, busy = busyDevice, onRevoke = { revokeTarget = dev })
                }
            }
            TextButton(enabled = !busyDevice, onClick = { devicesTick++ }) {
                Text(stringResource(R.string.devices_refresh))
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.change_server)) },
            text = { Text(stringResource(R.string.change_server_desc)) },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; onLogout() }) {
                    Text(stringResource(R.string.confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            })
    }
    revokeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text(stringResource(R.string.device_revoke_title)) },
            text = { Text(stringResource(R.string.device_revoke_desc)) },
            confirmButton = {
                TextButton(enabled = !busyDevice, onClick = {
                    revokeTarget = null
                    busyDevice = true
                    scope.launch {
                        runCatching {
                            PushBoxApi(identity.baseUrl, app.http)
                                .revokeDevice(identity, target.deviceId)
                        }.onFailure { devicesError = it.message ?: it::class.java.simpleName }
                        busyDevice = false
                        devicesTick++
                    }
                }) { Text(stringResource(R.string.confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            })
    }
    if (rotateConfirm) {
        AlertDialog(
            onDismissRequest = { rotateConfirm = false },
            title = { Text(stringResource(R.string.recovery_code_rotate_title)) },
            text = { Text(stringResource(R.string.recovery_code_rotate_desc)) },
            confirmButton = {
                TextButton(enabled = !rotating, onClick = {
                    rotateConfirm = false
                    rotating = true
                    scope.launch {
                        runCatching {
                            PushBoxApi(identity.baseUrl, app.http).rotateRecoveryCode(identity)
                        }.onSuccess {
                            app.identity.saveRecoveryCode(it)
                            revealCode = true
                        }.onFailure { rotationError = it.message ?: it::class.java.simpleName }
                        rotating = false
                    }
                }) { Text(stringResource(R.string.confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { rotateConfirm = false }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            })
    }
}

/** 二级页「提醒与频道」：新频道默认档位、按频道导航，以及首屏那三个开关的细则。 */
@Composable
private fun SettingsNotifyPage(identity: Identity, modifier: Modifier) {
    val context = LocalContext.current
    val app = App.get(context)
    val importance by app.defaultImportance.collectAsState()
    var showChannelNotify by remember { mutableStateOf(false) }

    SettingsPageColumn(modifier) {
        SettingsSection(stringResource(R.string.section_notification), heading = true) {
            Text(stringResource(R.string.notif_importance_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "high" to R.string.notif_importance_high,
                    "default" to R.string.notif_importance_default,
                    "low" to R.string.notif_importance_low,
                ).forEach { (level, label) ->
                    val selected = importance == level
                    FilterChip(selected = selected,
                        onClick = { app.setDefaultImportance(level) },
                        label = { Text(stringResource(label)) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
                        } else null)
                }
            }
        }

        // 这一组只有一行，组标题就是行标题，再写一遍等于同一句话说两次
        SettingsSection {
            GuideRow(stringResource(R.string.notif_manage_title),
                stringResource(R.string.notif_manage_entry_desc)) { showChannelNotify = true }
        }

        SettingsSection(stringResource(R.string.notif_detail_title), heading = true) {
            Text(stringResource(R.string.notif_lock_screen_title),
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.notif_lock_screen_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.notif_quiet_title),
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.notif_quiet_detail),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (showChannelNotify) {
        ChannelNotifyDialog(identity, onDismiss = { showChannelNotify = false })
    }
}

/** 二级页「应用锁」：开关本身在二级页里，生物识别只在锁开启时出现。 */
@Composable
private fun SettingsSecurityPage(modifier: Modifier) {
    val context = LocalContext.current
    val app = App.get(context)
    val appLock by app.appLockEnabled.collectAsState()
    val biometric by app.biometricEnabled.collectAsState()
    var lockDialog by remember { mutableStateOf<String?>(null) }
    // D30 生物识别只在 App 锁开启时可选；无可用硬件时置灰并提示
    val canBiometric = BiometricManager.from(context)
        .canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    SettingsPageColumn(modifier) {
        SettingsSection {
            SwitchRow(stringResource(R.string.app_lock_enable),
                stringResource(R.string.app_lock_desc), appLock) {
                lockDialog = if (it) "enable" else "disable"
            }
            if (appLock) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SwitchRow(stringResource(R.string.app_lock_biometric_enable),
                    if (canBiometric) stringResource(R.string.app_lock_biometric_desc)
                    else stringResource(R.string.app_lock_biometric_unavailable),
                    biometric, enabled = canBiometric) { app.setBiometric(it) }
            }
        }
    }

    lockDialog?.let { mode ->
        AppLockDialog(app, enabling = mode == "enable", onDismiss = { lockDialog = null })
    }
}

/** 二级页「后台常驻」：电池优化、轮询、后台监听渠道与自启动。 */
@Composable
private fun SettingsKeepAlivePage(modifier: Modifier) {
    val context = LocalContext.current
    val app = App.get(context)
    val polling by app.pollingMode.collectAsState()

    SettingsPageColumn(modifier) {
        SettingsSection {
            KeepAliveBatteryRow()
            // §7.2 极致省电轮询模式：关掉常驻长连接，改 WorkManager 周期补拉，省电但消息会延迟
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SwitchRow(stringResource(R.string.keepalive_polling),
                stringResource(R.string.keepalive_polling_desc), polling) {
                app.setPollingMode(it)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ActionRow(stringResource(R.string.keepalive_notification),
                stringResource(R.string.keepalive_notification_desc),
                stringResource(R.string.keepalive_request)) {
                runCatching {
                    // §7.2：直达"后台监听"通知渠道，而非整个 App 的通知设置页。
                    val channelId = cn.iamplus.pushbox.notify.Notifier.SERVICE_CHANNEL
                    val intent = Intent("android.settings.CHANNEL_NOTIFICATION_SETTINGS").apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                    }
                    if (!openDeepLink(context, intent)) {
                        // 降级：打开 App 级通知设置
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ActionRow(stringResource(R.string.keepalive_autostart),
                stringResource(R.string.keepalive_autostart_desc),
                stringResource(R.string.keepalive_request)) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}")))
                }
            }
        }
    }
}

/** 二级页「清理与导出」：两个危险动作分开写清楚后果，导出不动任何本地数据。 */
@Composable
private fun SettingsDataPage(modifier: Modifier) {
    val context = LocalContext.current
    val app = App.get(context)
    val scope = rememberCoroutineScope()
    var showClear by remember { mutableStateOf(false) }
    var showClearMedia by remember { mutableStateOf(false) }
    val stamp = remember {
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
    }

    SettingsPageColumn(modifier) {
        SettingsSection(stringResource(R.string.data_section_clear), heading = true) {
            ActionRow(stringResource(R.string.data_clear),
                stringResource(R.string.data_clear_desc),
                stringResource(R.string.data_clear_action), danger = true) { showClear = true }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // §7.4 只清已读消息关联的图片磁盘缓存，不动未读消息图片、不动消息本体与身份
            ActionRow(stringResource(R.string.data_clear_media),
                stringResource(R.string.data_clear_media_desc),
                stringResource(R.string.data_clear_action), danger = true) { showClearMedia = true }
        }

        SettingsSection(stringResource(R.string.data_section_export), heading = true) {
            // §7.3 导出为 JSON：SAF 新建文档，用户挑位置，避免"存储权限"这套老模型。
            // 结果回调在 MainActivity 层注册（见 exportLauncher），relock 也不会丢结果，故这里只触发。
            ActionRow(stringResource(R.string.data_export),
                stringResource(R.string.data_export_desc),
                stringResource(R.string.data_export_action)) {
                (context as? MainActivity)?.startExport("pushbox-export-$stamp.json")
            }
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text(stringResource(R.string.data_clear)) },
            text = { Text(stringResource(R.string.data_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClear = false
                    scope.launch(Dispatchers.IO) { app.db.clearAllTables() }
                }) { Text(stringResource(R.string.confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            })
    }
    // §7.4 清已读图片缓存：仅删已读消息关联的图片文件，消息本体与身份不动
    if (showClearMedia) {
        AlertDialog(
            onDismissRequest = { showClearMedia = false },
            title = { Text(stringResource(R.string.data_clear_media)) },
            text = { Text(stringResource(R.string.data_clear_media_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearMedia = false
                    scope.launch(Dispatchers.IO) {
                        val rows = app.dao.readMessagesWithMedia()
                            .map { it.msgId to it.mediaCount }
                        cn.iamplus.pushbox.ui.ImageCache.clearForReadMessages(rows)
                    }
                }) { Text(stringResource(R.string.confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearMedia = false }) {
                    Text(stringResource(R.string.confirm_cancel))
                }
            })
    }
}

/** 二级页「关于与帮助」：版本与更新、接口摘要（默认折叠）、脱敏日志说明。 */
@Composable
private fun SettingsAboutPage(modifier: Modifier) {
    val context = LocalContext.current
    val app = App.get(context)
    val scope = rememberCoroutineScope()
    var apiOpen by rememberSaveable { mutableStateOf(false) }

    SettingsPageColumn(modifier) {
        SettingsSection(stringResource(R.string.section_about), heading = true) {
            val versionName = remember { cn.iamplus.pushbox.net.UpdateApi.currentVersion(context).versionName }
            InfoRow(stringResource(R.string.app_name), versionName)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            UpdateControls(context, app, scope)
        }

        SettingsSection(stringResource(R.string.about_api_doc_title), heading = true) {
            Row(Modifier.fillMaxWidth().clickable { apiOpen = !apiOpen }
                .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.about_api_doc_summary), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if (apiOpen) R.string.about_api_doc_collapse
                    else R.string.about_api_doc_expand),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (apiOpen) {
                Text(stringResource(R.string.about_api_doc_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SettingsSection(stringResource(R.string.about_log_title), heading = true) {
            Text(stringResource(R.string.about_log_desensitized),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 距 0 点分钟数 → "HH:mm"。 */
private fun formatMinutes(min: Int): String =
    "%02d:%02d".format((min / 60) % 24, min % 60)

/** 弹系统时间选择器，回调"距 0 点分钟数"。 */
private fun pickTime(context: Context, label: String, current: Int, onPick: (Int) -> Unit) {
    val dialog = android.app.TimePickerDialog(context,
        { _, h, m -> onPick(h * 60 + m) },
        (current / 60) % 24, current % 60, true)
    dialog.setTitle(label)
    dialog.show()
}

@Composable
private fun QuietTimeButton(label: String, minutes: Int, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMinutes(minutes), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * App 锁设置弹窗：开启需输入两遍 PIN 确认，关闭需验证当前 PIN。
 * 验证只在点"确定"那一刻做并在协程里等——PBKDF2 每敲一个字符跑一次会把输入卡成一串顿挫。
 */
@Composable
private fun AppLockDialog(app: App, enabling: Boolean, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wrongCurrent by remember { mutableStateOf(false) }
    val valid = if (enabling) {
        pin.length in 4..6 && pin == confirm
    } else {
        current.isNotEmpty()
    }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (enabling) R.string.app_lock_set_pin
            else R.string.app_lock_disable)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (enabling) {
                    OutlinedTextField(value = pin, onValueChange = {
                        pin = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text(stringResource(R.string.app_lock_pin)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true)
                    OutlinedTextField(value = confirm, onValueChange = {
                        confirm = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text(stringResource(R.string.app_lock_pin_confirm)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true)
                    if (pin.isNotEmpty() && confirm.isNotEmpty() && pin != confirm) {
                        Text(stringResource(R.string.app_lock_pin_mismatch),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    OutlinedTextField(value = current, onValueChange = {
                        current = it.filter { c -> c.isDigit() }
                        wrongCurrent = false },
                        label = { Text(stringResource(R.string.app_lock_current_pin)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true)
                    if (wrongCurrent) {
                        Text(stringResource(R.string.app_lock_wrong_pin),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = {
                busy = true
                scope.launch {
                    if (enabling) {
                        app.setAppLock(true, pin)
                        onDismiss()
                    } else if (app.checkPin(current)) {
                        app.setAppLock(false, null)
                        onDismiss()
                    } else {
                        wrongCurrent = true
                        current = ""
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.confirm_ok)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.confirm_cancel))
            }
        })
}

@Composable
private fun RecoveryCodeRow(code: String?, revealed: Boolean, onToggle: () -> Unit) {
    var showQr by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.recovery_code_label),
                style = MaterialTheme.typography.labelSmall)
            Text(
                when {
                    code == null -> stringResource(R.string.recovery_none)
                    revealed -> code
                    else -> "••••••••••••"
                },
                style = MaterialTheme.typography.bodyMedium)
        }
        if (code != null) {
            TextButton(onClick = onToggle) {
                Text(stringResource(if (revealed) R.string.hide else R.string.reveal))
            }
            TextButton(onClick = rememberCopyAction(code)) {
                Text(stringResource(R.string.copy))
            }
            // §7.5 添加设备：新设备扫这枚二维码即可 join，无需手抄 base64
            TextButton(onClick = { showQr = true }) {
                Text(stringResource(R.string.recovery_qr))
            }
        }
    }
    if (showQr && code != null) {
        RecoveryQrDialog(code = code, onDismiss = { showQr = false })
    }
}

/** §7.5 恢复码二维码弹层：把 base64 恢复码画成 QR，供另一台设备"添加设备"时扫描。 */
@Composable
private fun RecoveryQrDialog(code: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_ok)) } },
        title = { Text(stringResource(R.string.recovery_qr_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()) {
                val bitmap = remember(code) { generateQrBitmap(code, 700) }
                if (bitmap != null) {
                    Image(bitmap = bitmap.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.size(240.dp))
                } else {
                    Text(stringResource(R.string.recovery_qr_failed))
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.recovery_qr_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** ZXing 编码：文本 → BitMatrix → Bitmap；失败（内容过长等）返回 null 由上层降级为文案。 */
private fun generateQrBitmap(content: String, size: Int): android.graphics.Bitmap? = runCatching {
    val hints = mapOf(
        com.google.zxing.EncodeHintType.MARGIN to 1,
        com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = com.google.zxing.qrcode.QRCodeWriter()
        .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) {
        bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK
        else android.graphics.Color.WHITE)
    }
    bmp
}.getOrNull()

/** 电池优化白名单开关行（§7.2 D18）：读系统实时状态，从设置页返回后 tick++ 重新检查。 */
@Composable
private fun KeepAliveBatteryRow() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val relaunch = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { tick++ }
    val ignoring = remember(tick) {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    Row(Modifier.fillMaxWidth().clickable(enabled = !ignoring, onClick = {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        runCatching { relaunch.launch(intent) }
    }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.keepalive_battery), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.keepalive_battery_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(if (ignoring) R.string.keepalive_status_on else R.string.keepalive_status_off),
                style = MaterialTheme.typography.labelSmall,
                color = if (ignoring) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 已允许时不再给动作，状态行本身就是结论
        if (!ignoring) {
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.keepalive_request),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 尝试打开深链 intent；失败返回 false，调用方可降级。 */
private fun openDeepLink(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent); true }.getOrElse { false }

/** 直达单条通知渠道的系统设置页；失败时退回 App 级通知设置。 */
private fun openSystemChannelSettings(context: Context, channelId: String) {
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    }
    if (!openDeepLink(context, intent)) {
        openDeepLink(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
}

/** 「按频道管理」一行：importance 为 null 表示这个频道还没发过通知、系统里还没有渠道。 */
private data class ChannelNotifyRow(val channelNo: String, val name: String, val importance: Int?)

/**
 * §7.3 按频道管理：列出本空间每个频道的当前提醒档位，点一行直达系统渠道设置。
 * 渠道重要度建好后只能由系统设置改，App 里只做导航与状态展示。
 */
@Composable
private fun ChannelNotifyDialog(identity: Identity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = App.get(context)
    var rows by remember { mutableStateOf<List<ChannelNotifyRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        rows = null; error = null
        runCatching { PushBoxApi(identity.baseUrl, app.http).channelListPage(identity).channels }
            .onSuccess { list ->
                val notifier = cn.iamplus.pushbox.notify.Notifier(context)
                // 顺手对齐系统渠道名：用户从这一行点进系统设置时看到的应该是频道名而不是号码
                notifier.syncChannelNames(list.associate { it.channelNo to it.name })
                rows = list.map {
                    ChannelNotifyRow(it.channelNo, it.name, notifier.channelImportanceOf(it.channelNo))
                }
            }
            .onFailure { error = it.message ?: it::class.java.simpleName }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notif_manage_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.notif_manage_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                val list = rows
                when {
                    error != null -> {
                        Text(stringResource(R.string.notif_manage_error, error!!),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { tick++ }) {
                            Text(stringResource(R.string.channels_refresh))
                        }
                    }
                    list == null -> Text(stringResource(R.string.notif_manage_loading),
                        style = MaterialTheme.typography.bodyMedium)
                    list.isEmpty() -> Text(stringResource(R.string.notif_manage_empty),
                        style = MaterialTheme.typography.bodyMedium)
                    else -> list.forEach { row ->
                        Row(Modifier.fillMaxWidth()
                            .clickable(enabled = row.importance != null) {
                                openSystemChannelSettings(context,
                                    cn.iamplus.pushbox.notify.Notifier.channelIdOf(row.channelNo))
                            }
                            .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.name.ifBlank {
                                    context.getString(R.string.notif_channel_fallback_name, row.channelNo)
                                }, style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.notif_manage_channel_no, row.channelNo),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (row.importance == null) {
                                stringResource(R.string.notif_manage_no_channel)
                            } else {
                                importanceLabel(row.importance)
                            }, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm_ok)) }
        })
}

@Composable
private fun importanceLabel(importance: Int): String = when {
    importance == NotificationManager.IMPORTANCE_NONE ->
        stringResource(R.string.notif_manage_importance_none)
    importance >= NotificationManager.IMPORTANCE_HIGH ->
        stringResource(R.string.notif_importance_high)
    importance == NotificationManager.IMPORTANCE_DEFAULT ->
        stringResource(R.string.notif_importance_default)
    else -> stringResource(R.string.notif_importance_low)
}

/** §7.2 D18：按品牌给出保活相关入口（锁定后台/关闭省电）；无通用标准，只能试各家深链。 */
private fun openBrandSpecific(context: Context, action: String) {
    val brand = Build.BRAND.lowercase()
    val pkg = context.packageName
    val intents = when {
        brand.contains("xiaomi") || brand.contains("redmi") -> listOf(
            // MIUI：最近任务锁定 / 省电模式
            if (action == "recent_tasks") Intent().apply {
                component = android.content.ComponentName(
                    "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            } else null,
            if (action == "power_saving") Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS) else null,
        )
        brand.contains("huawei") || brand.contains("honor") -> listOf(
            // EMUI/HarmonyOS：应用启动管理 / 省电
            if (action == "recent_tasks") Intent().apply {
                component = android.content.ComponentName(
                    "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            } else null,
            if (action == "power_saving") Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS) else null,
        )
        brand.contains("samsung") -> listOf(
            // One UI：自动运行 / 省电
            if (action == "recent_tasks") Intent().apply {
                component = android.content.ComponentName(
                    "com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")
            } else null,
            if (action == "power_saving") Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS) else null,
        )
        brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> listOf(
            // ColorOS/OxygenOS：自启动 / 省电
            if (action == "recent_tasks") Intent().apply {
                component = android.content.ComponentName(
                    "com.coloros.safecenter", "com.coloros.safecenter.permissionstartup.StartupAppListActivity")
            } else null,
            if (action == "power_saving") Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS) else null,
        )
        brand.contains("vivo") -> listOf(
            // Funtouch OS：后台高耗电 / 省电
            if (action == "recent_tasks") Intent().apply {
                component = android.content.ComponentName(
                    "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
            } else null,
            if (action == "power_saving") Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS) else null,
        )
        else -> listOf(null, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    }
    for (intent in intents.filterNotNull()) {
        if (openDeepLink(context, intent)) return
    }
    // 全失败：降级到 App 详情
    openDeepLink(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.parse("package:$pkg")))
}

/** §7.2 D18 首启保活引导页：逐项列出保活动作 + 实时状态；可跳过、不阻断使用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeepAliveGuideScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.keepalive_guide_title)) })
    }) { inset ->
        Column(
            Modifier.fillMaxSize().padding(inset).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.keepalive_guide_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("1) ${stringResource(R.string.keepalive_battery)}",
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.keepalive_battery_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            KeepAliveBatteryRow()
            Spacer(Modifier.height(8.dp))
            Text("2) ${stringResource(R.string.keepalive_autostart)}",
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.keepalive_autostart_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}")))
                }
            }) { Text(stringResource(R.string.keepalive_request)) }
            Spacer(Modifier.height(8.dp))
            Text("3) ${stringResource(R.string.keepalive_guide_lock)}",
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.keepalive_guide_lock_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // §7.2：按品牌给出"锁定后台任务"入口；无通用标准，只能尝试各家深链。
            TextButton(onClick = {
                openBrandSpecific(context, "recent_tasks")
            }) { Text(stringResource(R.string.keepalive_request)) }
            Spacer(Modifier.height(8.dp))
            Text("4) ${stringResource(R.string.keepalive_guide_battery_settings)}",
                style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.keepalive_guide_battery_settings_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                openBrandSpecific(context, "power_saving")
            }) { Text(stringResource(R.string.keepalive_request)) }
            Spacer(Modifier.height(20.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.keepalive_guide_done))
            }
        }
    }
}

/** §7.6 本地占用：Room 库文件（含 -wal/-shm）+ 图片磁盘缓存目录，递归求字节数。 */
private fun measureLocalStorage(context: android.content.Context): Long {
    fun sizeOf(f: java.io.File): Long = when {
        !f.exists() -> 0L
        f.isDirectory -> f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
        else -> f.length()
    }
    val dbBase = context.getDatabasePath("pushbox.db")
    var total = sizeOf(dbBase)
    total += sizeOf(java.io.File(dbBase.path + "-wal"))
    total += sizeOf(java.io.File(dbBase.path + "-shm"))
    total += sizeOf(java.io.File(context.cacheDir, "media-img"))
    return total
}

/** §7.3 数据导出为 JSON：把本地全部消息写成 JSON 数组，经 SAF URI 落到用户选的位置。 */
private suspend fun exportJson(app: App, context: android.content.Context,
    uri: android.net.Uri) {
    val rows = app.dao.allMessages()
    val arr = org.json.JSONArray()
    rows.forEach { m ->
        arr.put(org.json.JSONObject().apply {
            put("msg_id", m.msgId)
            put("seq", m.seq)
            put("channel_no", m.channelNo)
            put("sender_id", m.senderId)
            put("type", m.msgType)
            put("title", m.title ?: org.json.JSONObject.NULL)
            put("summary", m.summary ?: org.json.JSONObject.NULL)
            put("body", m.body ?: org.json.JSONObject.NULL)
            put("level", m.level)
            put("link_url", m.linkUrl ?: org.json.JSONObject.NULL)
            put("media_status", m.mediaStatus ?: org.json.JSONObject.NULL)
            put("media_count", m.mediaCount)
            put("created_at", m.createdAt)
            put("read", m.readState == 1)
        })
    }
    val root = org.json.JSONObject().apply {
        put("app", "PushBox")
        put("export_version", 1)
        put("exported_at", System.currentTimeMillis())
        put("count", rows.size)
        put("messages", arr)
    }
    context.contentResolver.openOutputStream(uri)?.use { out ->
        out.write(root.toString(2).toByteArray())
        out.flush()
    } ?: throw IllegalStateException("无法打开导出文件")
}

/** 一行「标题 + 说明 + 开关」。设置页里六处开关共用，说明保持一行，放不下就由调用方缩短。 */
@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean,
    enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

/** 一行"标题 + 说明 + 进入箭头"，点击跳到对应的系统设置页。 */
@Composable
private fun GuideRow(title: String, desc: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 动作行：标题 + 说明 + 尾行动词（"去设置"/"清理"）。动词只说明这一行会做什么，
 * 整行都是点击区，不再额外摆一个按钮——设置页里按钮一多就跟开关行长得很像。
 * 危险动作只染动词：标题保持正文色，扫读时不至于整段泛红。
 */
@Composable
private fun ActionRow(title: String, desc: String, action: String,
    danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text(action, style = MaterialTheme.typography.titleMedium,
            color = if (danger) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary)
    }
}

/** 主题选择行：标题 + 选中态勾选。整行可点，高度按 48dp 最小触控目标兜住。 */
@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .heightIn(min = 48.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (selected) {
            Text("✓", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** §7.4 外观分段选择：字号 / 摘要行数一行可摆多个，选中项用主色描边填充。 */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.small,
        color = bg,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
        ),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(label, color = fg,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

/** 设置页的一个分组：标题 + 一张卡片，卡片内竖排若干行。heading 用 H2（设置页），其余页仍是小号灰字。 */
@Composable
private fun SettingsSection(title: String? = null, heading: Boolean = false,
    content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (title != null) Text(title,
            style = if (heading) MaterialTheme.typography.headlineMedium
            else MaterialTheme.typography.labelSmall,
            color = if (heading) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) { content() }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
    }
}

/** 一行"标签 + 值 + 复制按钮"，值用等宽易读、可整段复制。 */
@Composable
private fun CopyableField(label: String, value: String) {
    val copy = rememberCopyAction(value)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 值可能是整行 curl（一百多字符）：最多占两行，其余省略，完整内容交给右边那个"复制"
            Text(value, style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = copy) { Text(stringResource(R.string.copy)) }
    }
}

/** 复制到系统剪贴板并弹一个 Toast 反馈；返回给 TextButton 的 onClick 直接用。 */
@Composable
private fun rememberCopyAction(value: String): () -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}

/** 顶栏与设置页共用：把 SyncEngine 状态翻译成"已连接/连接中/未连接" + 颜色。 */
@Composable
private fun connectionLabel(state: SyncEngine.State): Pair<String, Color> {
    val offlineColor = LocalPushBoxColors.current.offline
    return when (state) {
        is SyncEngine.State.Online ->
            stringResource(R.string.state_connected) to MaterialTheme.colorScheme.primary
        is SyncEngine.State.Offline ->
            stringResource(R.string.state_disconnected) to offlineColor
        else ->
            stringResource(R.string.state_connecting) to offlineColor
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRowView(row: ConversationRow, onOpen: () -> Unit, onLong: () -> Unit) {
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
                Box(Modifier.background(colors.unreadBadge, RoundedCornerShape(9.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp)) {
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
private fun Avatar(name: String, avatarPath: String? = null, senderId: String? = null, sizeDp: Int = 40) {
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

/** 与 PC 的 AvatarVisual.Palette 逐值对齐：同名会话在两个屏上必须同色。 */
private val AVATAR_PALETTE = listOf(
    Color(0xFF07C160), Color(0xFF198CFF), Color(0xFFFA5151),
    Color(0xFFB06900), Color(0xFF7B61FF), Color(0xFF00B3A7),
)

private fun avatarColor(name: String): Color {
    val idx = (name.hashCode() and Int.MAX_VALUE) % AVATAR_PALETTE.size
    return AVATAR_PALETTE[idx]
}

/**
 * §7.4 本地"设头像"落地：把 SAF 选中的图解码、缩到 256px、压成 JPEG 存进 filesDir/avatars，
 * 返回绝对路径写进会话行。URI 读权限是一次性的，必须当场复制成常驻文件，不能只存 URI。
 * 同一份字节再交给 [AvatarCache.put] 一次：通知栏只查那一级缓存，不回查会话行。
 */
private fun persistAvatar(context: Context, row: ConversationRow, uri: Uri): String? =
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

/** 一条搜索命中：本地与服务端结果统一成这个形状，才能合并、去重、并排渲染。 */
private data class SearchEntry(val msgId: String, val channelNo: String, val senderId: String,
    val senderName: String, val text: String, val createdAt: Long, val fromServer: Boolean)

/** 搜索结果行：命中片段（查询词高亮）+ 归属会话 + 时间，点击进入所属会话（§7.4 D19）。 */
@Composable
private fun SearchResultRow(entry: SearchEntry, query: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(highlighted(entry.text, query), maxLines = 2,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
        val where = stringResource(if (entry.fromServer) R.string.search_from_server
        else R.string.search_from_local)
        Text("${entry.senderName} · ${dateSectionLabel(entry.createdAt)} · $where",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 把 text 里所有（大小写不敏感的）query 命中片段刷成主色，其余保持默认色。 */
private fun highlighted(text: String, query: String): AnnotatedString = buildAnnotatedString {
    if (query.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    val lower = text.lowercase()
    val needle = query.lowercase()
    var i = 0
    while (i < text.length) {
        val at = lower.indexOf(needle, i)
        if (at < 0) {
            append(text.substring(i))
            break
        }
        append(text.substring(i, at))
        withStyle(SpanStyle(color = Color(0xFF1A73E8), fontWeight = FontWeight.Bold)) {
            append(text.substring(at, at + needle.length))
        }
        i = at + needle.length
    }
}

/**
 * 首启的服务器地址一律由用户自己填：预填一个开发机的内网 IP，等于让每一个装上手的人
 * 第一次点注册就撞在一个连不通的地址上。这里也不替缺协议头的输入猜 http/https——
 * 猜错的方向是明文，不该由代码来猜。
 */
private val SERVER_URL = Regex("""^https?://[^\s/]+(/\S*)?$""", RegexOption.IGNORE_CASE)
private fun curlExample(identity: Identity, channel: PushBoxApi.Channel): String =
    "curl -X POST ${identity.baseUrl}/message -H 'content-type: application/json' " +
        "-d '{\"channel\":\"${channel.channelNo}\",\"secret\":\"${channel.secret}\"," +
        "\"type\":\"text\",\"content\":\"hello pushbox\"}'"

/**
 * 首启注册（§7.5）。register 与 join 只差一个 group_secret，密钥都在本地先生成，
 * 服务端返回的密封盒要用这把 enc 私钥解，所以整个过程不出本地密钥。
 * 返回恢复码表示"这是空间的第一台设备"，必须让用户抄下来。
 */
private suspend fun enroll(app: App, baseUrl: String, deviceName: String,
    groupSecret: String?): String? {
    val (enc, sign) = app.identity.newKeys()
    val enrolled = PushBoxApi(baseUrl, app.http)
        .register(enc.privateKey(), sign.privateKey(), deviceName, groupSecret)
    app.identity.saveEnrolled(enrolled.deviceId, enrolled.token, enrolled.groupId,
        enrolled.recoveryCode, enrolled.serverPubFp, baseUrl)
    app.identity.addServerHistory(baseUrl)
    return enrolled.recoveryCode
}
