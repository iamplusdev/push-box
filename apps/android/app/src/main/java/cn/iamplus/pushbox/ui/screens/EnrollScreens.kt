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

/** §7.5 首启：填服务器地址 → 本地生成两把密钥 → register 或 join → 恢复码要用户确认抄下。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnrollScreen(app: App, onDone: () -> Unit) {
    val vm: EnrollViewModel = viewModel(factory = EnrollViewModel.factory(app))
    val serverHost by vm.serverHost.collectAsState()
    val serverPort by vm.serverPort.collectAsState()
    val useHttps by vm.useHttps.collectAsState()
    val deviceName by vm.deviceName.collectAsState()
    val recoveryCode by vm.recoveryCode.collectAsState()
    val showRecovery by vm.showRecovery.collectAsState()
    val history by vm.history.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val issuedCode by vm.issuedCode.collectAsState()
    val baseUrl by vm.baseUrl.collectAsState()
    val scanPrompt = stringResource(R.string.enroll_scan_prompt)
    val serverRequired = stringResource(R.string.enroll_server_required)
    val serverInvalid = stringResource(R.string.enroll_server_invalid)
    // §7.5 添加设备：扫另一台设备"我的"里展示的恢复码二维码，自动填入。首启无 App 锁，
    // 相机页返回不会触发 relock 拆组合，故这里用组合级 launcher 是安全的。
    val scanLauncher = rememberLauncherForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()) { result ->
        vm.onScanResult(result?.contents)
    }

    Scaffold(topBar = {
        PushBoxTopBar(centerTitle = true, title = {
            Text(stringResource(R.string.enroll_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface)
        })
    }) { inset ->
        Column(
            // imePadding 在 verticalScroll 之前：键盘弹起时滚动区跟着缩，输入框不会被键盘盖住
            Modifier.fillMaxSize().padding(inset).imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(stringResource(R.string.enroll_server)) {
                // HTTPS 开关：拨一下就立刻反映到下面那条 URL 上（PC 的接入框同一口径）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.enroll_use_https),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f))
                    Switch(checked = useHttps, onCheckedChange = { vm.useHttps.value = it })
                }
                OutlinedTextField(
                    value = serverHost, onValueChange = { vm.serverHost.value = it },
                    label = { Text(stringResource(R.string.enroll_server_host)) },
                    supportingText = { Text(stringResource(R.string.enroll_server_host_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(
                    value = serverPort, onValueChange = { vm.serverPort.value = it },
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
                            OutlinedButton(enabled = !busy, onClick = { vm.applyUrl(url) }) {
                                Text(url, maxLines = 1)
                            }
                        }
                    }
                }
            }

            SettingsSection(stringResource(R.string.enroll_device_name)) {
                OutlinedTextField(
                    value = deviceName, onValueChange = { vm.deviceName.value = it },
                    label = { Text(stringResource(R.string.enroll_device_name)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }

            // 恢复码这条路不是每个人都要走：默认收成一句说明，点开才给输入框与扫码入口，
            // 否则首启这一屏要同时摆着"创建"和"加入"两套字段
            SettingsSection(stringResource(R.string.enroll_have_code_title)) {
                if (showRecovery) {
                    OutlinedTextField(
                        value = recoveryCode, onValueChange = { vm.recoveryCode.value = it },
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
                        TextButton(enabled = !busy, onClick = { vm.closeRecovery() }) {
                            Text(stringResource(R.string.enroll_have_code_close))
                        }
                    }
                } else {
                    Text(stringResource(R.string.enroll_have_code_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(enabled = !busy, onClick = { vm.openRecovery() }) {
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
                vm.submit(serverRequired, serverInvalid, onDone)
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
internal fun EnrollErrorCard(text: String) {
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
internal fun RecoveryCodeCard(code: String, onConfirm: () -> Unit) {
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