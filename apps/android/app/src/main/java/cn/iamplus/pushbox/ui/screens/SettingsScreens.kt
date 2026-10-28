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
import cn.iamplus.pushbox.MainActivity
import cn.iamplus.pushbox.R

/**
 * 「我的 → 设置」：首屏只放每天会动的开关与一层入口，长说明都收进二级页。
 * 二级页的顶栏与返回键由 MainShell 负责，这里只按页分发内容。
 */
internal enum class SettingsPage(@param:StringRes val title: Int) {
    Connect(R.string.settings_page_connect),
    Notify(R.string.settings_page_notify),
    Security(R.string.settings_page_security),
    KeepAlive(R.string.settings_page_keepalive),
    Data(R.string.settings_page_data),
    About(R.string.settings_page_about),
}

@Composable
internal fun SettingsTab(identity: Identity, page: SettingsPage?, onPage: (SettingsPage?) -> Unit,
    modifier: Modifier, onLogout: () -> Unit) {
    val app = App.get(LocalContext.current)
    // 二级页共用一个实例：切页不丢正在拉的设备列表与更新进度，退出身份时随 ViewModelStore 一起清掉
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(app))
    // 进二级页时首屏会离开组合，滚动位置也跟着丢；按页名留一份 saveable 状态，
    // 返回时还停在原来那一屏，而不是被弹回顶部。
    val states = rememberSaveableStateHolder()
    states.SaveableStateProvider(page?.name ?: "root") {
        when (page) {
            null -> SettingsRoot(identity, modifier, onPage)
            SettingsPage.Connect -> SettingsConnectPage(identity, vm, modifier, onLogout)
            SettingsPage.Notify -> SettingsNotifyPage(identity, vm, modifier)
            SettingsPage.Security -> SettingsSecurityPage(modifier)
            SettingsPage.KeepAlive -> SettingsKeepAlivePage(modifier)
            SettingsPage.Data -> SettingsDataPage(vm, modifier)
            SettingsPage.About -> SettingsAboutPage(vm, modifier)
        }
    }
}

/** 首屏与六个二级页共用的滚动列：16dp 边距、20dp 组间距。 */
@Composable
internal fun SettingsPageColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}

/** 设置首屏：连接、通知、外观、存储四组日常项，加一组通往二级页的入口。 */
@Composable
internal fun SettingsRoot(identity: Identity, modifier: Modifier, onPage: (SettingsPage?) -> Unit) {
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
internal fun SettingsConnectPage(identity: Identity, vm: SettingsViewModel, modifier: Modifier,
    onLogout: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    var revealCode by remember { mutableStateOf(false) }
    var rotateConfirm by remember { mutableStateOf(false) }
    // 只有"这一次刚轮换成功"才自动摊开新码；重进本页拿到存储里那份时保持打码
    var expectReveal by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<PushBoxApi.DeviceInfo?>(null) }
    val devices by vm.devices.collectAsState()
    val devicesError by vm.devicesError.collectAsState()
    val busyDevice by vm.devicesBusy.collectAsState()
    val rotating by vm.rotating.collectAsState()
    val rotationError by vm.rotationError.collectAsState()
    val recoveryCode by vm.recoveryCode.collectAsState()
    LaunchedEffect(Unit) {
        vm.reloadDevices(identity)
        vm.loadRecoveryCode()
    }
    // 轮换成功后旧码已作废，直接摊开新码——这时候还让用户点一次"显示"没有意义
    LaunchedEffect(expectReveal, rotating, recoveryCode) {
        if (expectReveal && !rotating && recoveryCode != identity.recoveryCode && recoveryCode != null) {
            revealCode = true
            expectReveal = false
        }
    }

    // 轮换失败就别留着"等新码"的念头：下次重进本页不该顺手把旧码摊开
    LaunchedEffect(rotationError) {
        if (rotationError != null) expectReveal = false
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
            // 换过恢复码的话以存储里那份为准：identity 是本页进来那一刻的快照
            RecoveryCodeRow(recoveryCode ?: identity.recoveryCode, revealed = revealCode,
                onToggle = { revealCode = !revealCode })
            // §14 轮换恢复码：旧码立即作废（已加入的设备不受影响，只影响后续扫码添加）
            rotationError?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error)
            }
            TextButton(enabled = !rotating, onClick = { rotateConfirm = true }) {
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
            TextButton(enabled = !busyDevice, onClick = { vm.reloadDevices(identity) }) {
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
                    vm.revokeDevice(identity, target.deviceId)
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
                    expectReveal = true
                    vm.rotateRecoveryCode(identity)
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
internal fun SettingsNotifyPage(identity: Identity, vm: SettingsViewModel, modifier: Modifier) {
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
        ChannelNotifyDialog(identity, vm, onDismiss = { showChannelNotify = false })
    }
}

/** 二级页「应用锁」：开关本身在二级页里，生物识别只在锁开启时出现。 */
@Composable
internal fun SettingsSecurityPage(modifier: Modifier) {
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
internal fun SettingsKeepAlivePage(modifier: Modifier) {
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
internal fun SettingsDataPage(vm: SettingsViewModel, modifier: Modifier) {
    val context = LocalContext.current
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
                    vm.clearLocalData()
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
                    vm.clearReadMediaCache()
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
internal fun SettingsAboutPage(vm: SettingsViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val app = App.get(context)
    var apiOpen by rememberSaveable { mutableStateOf(false) }

    SettingsPageColumn(modifier) {
        SettingsSection(stringResource(R.string.section_about), heading = true) {
            val versionName = remember { cn.iamplus.pushbox.net.UpdateApi.currentVersion(context).versionName }
            InfoRow(stringResource(R.string.app_name), versionName)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            UpdateControls(context, app, vm)
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
internal fun formatMinutes(min: Int): String =
    "%02d:%02d".format((min / 60) % 24, min % 60)

/** 弹系统时间选择器，回调"距 0 点分钟数"。 */
internal fun pickTime(context: Context, label: String, current: Int, onPick: (Int) -> Unit) {
    val dialog = android.app.TimePickerDialog(context,
        { _, h, m -> onPick(h * 60 + m) },
        (current / 60) % 24, current % 60, true)
    dialog.setTitle(label)
    dialog.show()
}

@Composable
internal fun QuietTimeButton(label: String, minutes: Int, modifier: Modifier, onClick: () -> Unit) {
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
internal fun AppLockDialog(app: App, enabling: Boolean, onDismiss: () -> Unit) {
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
            Column(Modifier.imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
internal fun RecoveryCodeRow(code: String?, revealed: Boolean, onToggle: () -> Unit) {
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
internal fun RecoveryQrDialog(code: String, onDismiss: () -> Unit) {
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
internal fun generateQrBitmap(content: String, size: Int): android.graphics.Bitmap? = runCatching {
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
internal fun KeepAliveBatteryRow() {
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
internal fun openDeepLink(context: Context, intent: Intent): Boolean =
    runCatching { context.startActivity(intent); true }.getOrElse { false }

/** 直达单条通知渠道的系统设置页；失败时退回 App 级通知设置。 */
internal fun openSystemChannelSettings(context: Context, channelId: String) {
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
internal data class ChannelNotifyRow(val channelNo: String, val name: String, val importance: Int?)

/**
 * §7.3 按频道管理：列出本空间每个频道的当前提醒档位，点一行直达系统渠道设置。
 * 渠道重要度建好后只能由系统设置改，App 里只做导航与状态展示。
 */
@Composable
internal fun ChannelNotifyDialog(identity: Identity, vm: SettingsViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val rows by vm.notifyRows.collectAsState()
    val error by vm.notifyError.collectAsState()
    // 每次打开都重拉：渠道重要度可能刚在系统设置里改过
    LaunchedEffect(Unit) { vm.reloadNotifyRows(identity) }
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
                        TextButton(onClick = { vm.reloadNotifyRows(identity) }) {
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
internal fun importanceLabel(importance: Int): String = when {
    importance == NotificationManager.IMPORTANCE_NONE ->
        stringResource(R.string.notif_manage_importance_none)
    importance >= NotificationManager.IMPORTANCE_HIGH ->
        stringResource(R.string.notif_importance_high)
    importance == NotificationManager.IMPORTANCE_DEFAULT ->
        stringResource(R.string.notif_importance_default)
    else -> stringResource(R.string.notif_importance_low)
}

/** §7.2 D18：按品牌给出保活相关入口（锁定后台/关闭省电）；无通用标准，只能试各家深链。 */
internal fun openBrandSpecific(context: Context, action: String) {
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
internal fun KeepAliveGuideScreen(onDone: () -> Unit) {
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
internal fun measureLocalStorage(context: android.content.Context): Long {
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
internal suspend fun exportJson(app: App, context: android.content.Context,
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
internal fun SwitchRow(title: String, desc: String, checked: Boolean,
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
internal fun GuideRow(title: String, desc: String, onClick: () -> Unit) {
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
internal fun ActionRow(title: String, desc: String, action: String,
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
internal fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
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
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
internal fun SettingsSection(title: String? = null, heading: Boolean = false,
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
internal fun InfoRow(label: String, value: String, valueColor: Color? = null) {
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
internal fun CopyableField(label: String, value: String) {
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
internal fun rememberCopyAction(value: String): () -> Unit {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    return {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
