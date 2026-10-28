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
import cn.iamplus.pushbox.sync.SyncEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.R

@Composable
internal fun MessagesTab(identity: Identity, conversations: List<ConversationRow>?,
    modifier: Modifier, onOpen: (ConversationRow, String?) -> Unit,
    onNavigateToChannels: (() -> Unit)? = null) {
    val app = App.get(LocalContext.current)
    val vm: MessagesViewModel = viewModel(factory = MessagesViewModel.factory(app))
    val clearError by vm.clearError.collectAsState()
    LaunchedEffect(clearError) {
        clearError?.let {
            Toast.makeText(app, "本地已清空，但同步到其他设备失败：$it", Toast.LENGTH_LONG).show()
            vm.consumeClearError()
        }
    }
    var menuFor by remember { mutableStateOf<ConversationRow?>(null) }
    var renameFor by remember { mutableStateOf<ConversationRow?>(null) }
    // §7.4 本地"设头像"：记住当前要设头像的会话，SAF 回来后把选中的图落到 filesDir 并写库。
    var avatarFor by remember { mutableStateOf<ConversationRow?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()) { uri ->
        val row = avatarFor
        avatarFor = null
        if (uri != null && row != null) {
            vm.setAvatar(row, uri)
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
                    Text(stringResource(R.string.empty_create_first_channel))
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
                vm.togglePin(menuRow)
                menuFor = null
            },
            onToggleMute = {
                vm.toggleMute(menuRow)
                menuFor = null
            },
            onToggleLockPrivate = {
                vm.toggleLockPrivate(menuRow)
                menuFor = null
            },
            onRename = { renameFor = menuRow; menuFor = null },
            onSetAvatar = { avatarFor = menuRow; menuFor = null;
                avatarPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onClear = { vm.clear(menuRow, identity) })
    }
    val renameRow = renameFor
    if (renameRow != null) {
        RenameDialog(renameRow, onDismiss = { renameFor = null }, onSave = { name ->
            vm.rename(renameRow, name)
        })
    }
}

/** 长按会话行的操作面板：置顶 / 免打扰 / 重命名 / 设头像 / 清空（§7.3/§7.4）。 */
@Composable
internal fun ConversationMenu(row: ConversationRow, onDismiss: () -> Unit,
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
internal fun MenuOption(label: String, onClick: () -> Unit, checked: Boolean = false,
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
internal fun DialogGroup(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
    content()
}

/** 会话改名弹窗：留空即清除本地名、回落到服务端下发的发送者名。 */
@Composable
internal fun RenameDialog(row: ConversationRow, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(row.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it },
                singleLine = true, modifier = Modifier.fillMaxWidth().imePadding())
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
internal data class ConversationTarget(val conversation: ConversationRow,
    val focusMsgId: String? = null)

/** 对话详情（参考微信）：正流展示全部消息、进入即清未读、长按复制/删除。 */
@Composable
internal fun ConversationDetail(app: App, conversation: ConversationRow, focusMsgId: String?,
    onBack: () -> Unit) {
    // 换会话 = 换一块干净的屏幕。外层 key 一变，里面所有 remember 全部重建，
    // 包括 rememberLazyListState() 那个滚动位置——不做这件事，上一条会话读到哪儿、
    // 下一条就开在哪儿，然后被"首次落位"拽回页尾，看上去就是"切个会话它自己滚了一路"。
    // 键只用两把 id 而不是行本身：ConversationRow 是 data class，进这一屏会把未读清零，
    // 行内容当场变一次，拿它当键等于每开一个会话就自己重置自己一遍。
    key(conversation.channelNo, conversation.senderId) {
        ConversationScreen(app, conversation, focusMsgId, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(app: App, conversation: ConversationRow, focusMsgId: String?,
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
    val vm: MessagesViewModel = viewModel(factory = MessagesViewModel.factory(app))
    val fetching by vm.fetching.collectAsState()
    // 外层 key 隔离会话，冷流只在详情可见时订阅，不为每个打开过的会话保留 ViewModel。
    val result by remember(vm) {
        vm.messages(conversation.channelNo, conversation.senderId, snapshotFlow { rawQuery })
    }.collectAsState(initial = ConversationMessages())
    val query = result.query
    val rowsState = result.rows
    val rows = rowsState.orEmpty()

    // 告诉收消息那条链路"这一屏正开着"：它据此不弹通知、不给这条涨红点。
    // 键只用两把 id，行本身每来一条消息就是一个新对象，挂上去会来回重注册。
    DisposableEffect(conversation.channelNo, conversation.senderId) {
        ViewedConversation.open(conversation.channelNo, conversation.senderId)
        onDispose { ViewedConversation.close(conversation.channelNo, conversation.senderId) }
    }

    // 开屏那一次就够：正在看的这条不涨红点（ViewedConversation 那道门），所以行内容
    // 后续再变也没新的要标；挂行本身等于每次变更都重发一遍已读上报。
    LaunchedEffect(conversation.channelNo, conversation.senderId) {
        vm.markRead(conversation)
    }

    val listState = rememberLazyListState()
    var selected by remember { mutableStateOf<MessageRow?>(null) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    // 从搜索结果点进来要落在命中那条上：定位完成前先把"滚到最新"按住，否则两个效果互相拽。
    // 命中项只存在于服务端（本机没缓存正文）时这里找不到，退回页尾——那一条点开仍能看摘要。
    var awaitingFocus by remember(focusMsgId) { mutableStateOf(focusMsgId != null) }
    // 会话内搜索点中一条命中：先退出筛选，等未筛选的那一帧回来再定位——
    // 筛选列表里的下标与全量列表不是一回事，直接拿它 scrollToItem 会落到别的消息上。
    var pendingFocus by remember { mutableStateOf<String?>(null) }
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
    var unseen by remember { mutableIntStateOf(0) }
    var lastCount by remember { mutableIntStateOf(0) }
    var placed by remember { mutableStateOf(false) }
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
            // 首次落位只做一次；带命中目标的会话由上面那个 effect 负责，这里不抢。
            // 这一次用 scrollToItem 而不是 animate：这个协程挂在 rows.size 上，Room 每发一帧
            // 就重跑一遍，动画会被打断重来——长会话开屏那一下就是"它自己在往上滚"。
            !placed -> {
                placed = true
                if (focusMsgId == null) listState.scrollToItem(rows.lastIndex)
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back))
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
                                fetching = row.msgId in fetching,
                                onFetchFulltext = { vm.fetchFulltext(row.msgId) },
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
            onDelete = { vm.delete(target) })
    }
}

/** 定位到命中那条后高亮停留多久。再久一点就会被读成"这条消息有什么特别"。 */
private const val FOCUS_HIGHLIGHT_MILLIS = 1_600L

/** 距列表末尾不足两条就算"贴着底"：新消息可以跟随；再往上就是人在读历史，只报数不抢滚动。 */
private const val NEAR_BOTTOM_ITEMS = 1

/** 长按气泡的操作面板：复制 / 删除（§7.3 长按气泡动作）。 */
@Composable
internal fun MessageActionSheet(row: MessageRow, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val copy = rememberCopyAction(messageText(row))
    var confirmDelete by remember { mutableStateOf(false) }
    var showJson by remember { mutableStateOf(false) }
    // §7.4 D17：长按气泡补"保存图片 / 查看原始 JSON"
    val saveImage = rememberMediaSaver()
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
                        onClick = { saveImage(row.msgId, 0) })
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
internal fun rawMessageJson(row: MessageRow): String = buildString {
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

internal fun messageText(row: MessageRow): String = row.body ?: row.summary ?: row.title.orEmpty()

@Composable
internal fun MessageBubble(row: MessageRow, senderName: String, showTime: Boolean = true,
    highlighted: Boolean = false, fetching: Boolean, onFetchFulltext: () -> Unit,
    onClick: (() -> Unit)? = null, onLong: () -> Unit) {
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
                    TextButton(enabled = !fetching, onClick = onFetchFulltext) {
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

internal fun typeLabel(type: String): String? = when (type) {
    "markdown" -> "Markdown"
    "image" -> "图片"
    "file" -> "文件"
    "audio" -> "音频"
    "video" -> "视频"
    "link" -> "链接"
    else -> null
}

internal fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

internal val INLINE_URL = Regex("""https?://[^\s<>"']+""")
internal val INLINE_MD = Regex("""\*\*(.+?)\*\*|`(.+?)`""")

/**
 * §8 轻量 Markdown：仅 **粗体** 与 `行内代码`，并给正文里的裸 URL 上蓝色下划线样式。
 * 分词后一次性拼 AnnotatedString，URL 着色在拼接后的整串上扫描，索引天然对齐；
 * 完整 CommonMark 渲染留给后续引入渲染库，这里不手搓解析器以免边界 bug。
 */
@Composable
internal fun renderRichText(text: String): AnnotatedString {
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
internal fun MarkdownBody(text: String) {
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

internal sealed class MdBlock {
    class Paragraph(val text: String) : MdBlock()
    class Heading(val level: Int, val text: String) : MdBlock()
    class Quote(val text: String) : MdBlock()
    class Code(val text: String) : MdBlock()
    class ListBlock(val items: List<String>, val ordered: Boolean) : MdBlock()
    class Table(val rows: List<List<String>>) : MdBlock()
}

internal val MD_HEADING = Regex("""^(#{1,6})\s+(.*)$""")
internal val MD_UL = Regex("""^\s*[-*+]\s+(.*)$""")
internal val MD_OL = Regex("""^\s*\d+\.\s+(.*)$""")
internal val MD_QUOTE = Regex("""^\s*>\s?(.*)$""")

/** 逐行分组为块：代码围栏、表格、引用、有序/无序列表、段落；未识别语法降级为普通段落。 */
internal fun parseMarkdownBlocks(src: String): List<MdBlock> {
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

internal fun splitTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|")
        .map { it.trim() }

/** 会话列表/详情页的日期分节头：居中、弱化的时间标签。 */
@Composable
internal fun DateHeader(label: String) {
    if (label.isEmpty()) return
    Text(label, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun levelAccent(level: String): Color? = when (level) {
    // 深浅两套各有一档：写死的深红/琥珀在深色气泡上会发闷
    "error" -> LocalPushBoxColors.current.levelError
    "warning" -> LocalPushBoxColors.current.levelWarning
    else -> null
}
