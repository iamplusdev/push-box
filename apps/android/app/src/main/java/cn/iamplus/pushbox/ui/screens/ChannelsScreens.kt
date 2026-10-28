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
import cn.iamplus.pushbox.net.PushBoxApi
import kotlinx.coroutines.Dispatchers
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
import cn.iamplus.pushbox.data.Identity
import cn.iamplus.pushbox.ui.dateTimeLabel
import cn.iamplus.pushbox.ui.LocalPushBoxColors
import cn.iamplus.pushbox.ui.PushBoxTheme
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.R

/** 频道页：自定义命名创建 + 加入频道（订阅）+ 频道列表 + 重置密钥。 */
@Composable
internal fun ChannelsTab(identity: Identity, modifier: Modifier) {
    val app = App.get(LocalContext.current)
    val vm: ChannelsViewModel = viewModel(factory = ChannelsViewModel.factory(app))
    val name by vm.name.collectAsState()
    val joinNo by vm.joinNo.collectAsState()
    val joinSecret by vm.joinSecret.collectAsState()
    val subscribeSecret by vm.subscribeSecret.collectAsState()
    val channels by vm.channels.collectAsState()
    val subscribedOnly by vm.subscribedOnly.collectAsState()
    val issued by vm.issued.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val joinDone by vm.joinDone.collectAsState()
    // 本机记过的明文密钥（创建/重置/接入那三个时刻留下的）。服务端只存哈希，
    // 所以"复制密钥"能给的只有这一份；没记过的频道在弹窗里直接说明原因。
    val secrets by app.channelSecrets.collectAsState()
    var archiveTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    var renameTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    val renameText by vm.renameText.collectAsState()
    var clearTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    // §14 App 是唯一控制台（D28）：治理位、容量报表、白名单、换发号码都只能从这里进
    var governanceTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    var capacityTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    var sendersTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    var resetNoTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    // §5.2 接入（订阅）：频道号+密钥登记这台设备；过滤模式是设备级开关，默认关
    var subscribeTarget by remember { mutableStateOf<PushBoxApi.ChannelInfo?>(null) }
    val needBoth = stringResource(R.string.channel_join_needs_both)
    val doneTip = stringResource(R.string.channel_join_done)

    // 每次切回这一页都重拉一次：别处的改动（PC/管理台）不该等到下次冷启才看见。
    LaunchedEffect(Unit) { vm.reload(identity) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 对齐 PC 频道面板（§8.4 W16）的五块：新建 / 接入信息 / 加入 / 接收范围 / 我的频道。
        // 每块一张卡，操作收进每行的 ⋮ 里——以前 12 个按钮摊在一行上，一条频道能占满两屏。
        SettingsSection(stringResource(R.string.channel_create)) {
            OutlinedTextField(
                value = name, onValueChange = { vm.name.value = it },
                label = { Text(stringResource(R.string.channel_name_optional)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy, modifier = Modifier.weight(1f),
                    onClick = { vm.create(identity) }) {
                    Text(stringResource(R.string.channel_create))
                }
                OutlinedButton(enabled = !busy, onClick = { vm.reload(identity) }) {
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
                value = joinNo, onValueChange = { vm.joinNo.value = it },
                label = { Text(stringResource(R.string.channel_join_no_hint)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(
                value = joinSecret, onValueChange = { vm.joinSecret.value = it },
                label = { Text(stringResource(R.string.channel_join_secret_hint)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(),
                onClick = { vm.join(identity, needBoth) }) {
                Text(stringResource(R.string.channel_join_title))
            }
        }

        SettingsSection(stringResource(R.string.channel_filter_only)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.channel_filter_only_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Switch(checked = subscribedOnly, enabled = !busy,
                    onCheckedChange = { want -> vm.setSubscribedOnly(identity, want) })
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
        if (joinDone) Text(doneTip, color = MaterialTheme.colorScheme.primary)

        SettingsSection(stringResource(R.string.channel_list_title)) {
            val list = channels
            when {
                list == null -> Text(stringResource(R.string.channel_loading))
                list.isEmpty() -> Text(stringResource(R.string.channel_none))
                else -> list.forEachIndexed { index, ch ->
                    ChannelListRow(ch, busy = busy, secret = secrets[ch.channelNo],
                        onRotate = { vm.rotate(identity, ch.channelNo) },
                        onArchive = { archiveTarget = ch },
                        onRename = { renameTarget = ch; vm.renameText.value = ch.name },
                        onClear = { clearTarget = ch },
                        onGovernance = { governanceTarget = ch },
                        onCapacity = { capacityTarget = ch },
                        onSenders = { sendersTarget = ch },
                        onResetNo = { resetNoTarget = ch },
                        onSubscribe = { subscribeTarget = ch; vm.subscribeSecret.value = "" },
                        onUnsubscribe = { vm.leave(identity, ch.channelNo) })
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
                    vm.archive(identity, t.channelNo)
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
                    value = renameText, onValueChange = { vm.renameText.value = it },
                    label = { Text(stringResource(R.string.channel_rename_hint)) },
                    singleLine = true, modifier = Modifier.imePadding())
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    val target = renameTarget
                    renameTarget = null
                    if (target != null) vm.rename(identity, target.channelNo)
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
                    vm.clear(identity, t.channelNo)
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
        // 点保存即关：与归档/改名/清空同一口径，进度与失败都落在页面那一行上；
        // 请求进 ViewModel 后不再随弹窗销毁被取消。
        ChannelGovernanceDialog(t, onDismiss = { governanceTarget = null },
            onSave = { patch -> governanceTarget = null; vm.saveGovernance(identity, t.channelNo, patch) })
    }
    capacityTarget?.let { t ->
        ChannelCapacityDialog(identity, t, onDismiss = { capacityTarget = null })
    }
    sendersTarget?.let { t ->
        ChannelSendersDialog(identity, t, onDismiss = { sendersTarget = null },
            onError = { vm.reportError(it) })
    }
    resetNoTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { resetNoTarget = null },
            title = { Text(stringResource(R.string.channel_reset_no_title)) },
            text = { Text(stringResource(R.string.channel_reset_no_desc)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    resetNoTarget = null
                    vm.resetNo(identity, t.channelNo)
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
                Column(Modifier.imePadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.channelNo)
                    OutlinedTextField(
                        value = subscribeSecret, onValueChange = { vm.subscribeSecret.value = it },
                        label = { Text(stringResource(R.string.channel_join_secret_hint)) },
                        singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && subscribeSecret.isNotBlank(), onClick = {
                    val target = subscribeTarget
                    subscribeTarget = null
                    if (target != null) vm.subscribe(identity, target.channelNo)
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
internal fun ChannelGovernanceDialog(channel: PushBoxApi.ChannelInfo,
    onDismiss: () -> Unit, onSave: (PushBoxApi.ChannelPatch) -> Unit) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(onClick = {
                onSave(governancePatch(GovernanceDraft(maxLevel, maxImages, rateLimit, bodyBytes,
                    dailyMsg, dailyImage, dailyBytes, strictAuth, muted, whitelist), channel))
            }) { Text(stringResource(R.string.channel_governance_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm_cancel))
            }
        })
}

@Composable
internal fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.filter { c -> c.isDigit() }) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true, modifier = Modifier.fillMaxWidth())
}

/** 只有真的改了才提交；非数字（含空串）也视作"没改"，让校验交给服务端。 */
internal fun changedInt(text: String, current: Int): Int? =
    text.toIntOrNull()?.takeIf { it != current }

internal fun changedLong(text: String, current: Long): Long? =
    text.toLongOrNull()?.takeIf { it != current }

/** §14 容量报表：服务端按频道统计，用来判断"该清一清了"。 */
@Composable
internal fun ChannelCapacityDialog(identity: Identity, channel: PushBoxApi.ChannelInfo,
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
internal fun ChannelSendersDialog(identity: Identity, channel: PushBoxApi.ChannelInfo,
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
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()),
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

internal fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** 创建/重置成功后唯一能看到明文密钥的地方：频道号、密钥、curl 命令各带一个复制。 */
@Composable
internal fun IssuedChannelCard(identity: Identity, channel: PushBoxApi.Channel) {
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
internal fun ChannelListRow(channel: PushBoxApi.ChannelInfo, busy: Boolean, secret: String?,
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
internal fun ChannelActionsDialog(channel: PushBoxApi.ChannelInfo, secret: String?,
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
internal fun DeviceRow(dev: PushBoxApi.DeviceInfo, busy: Boolean, onRevoke: () -> Unit) {
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
internal fun UpdateControls(context: android.content.Context, app: App,
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
internal fun UpdateConfirmDialog(app: App) {
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