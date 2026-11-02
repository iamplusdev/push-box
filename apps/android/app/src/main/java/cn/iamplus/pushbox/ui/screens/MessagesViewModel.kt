package cn.iamplus.pushbox.ui.screens

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.data.ConversationRow
import cn.iamplus.pushbox.data.Identity
import cn.iamplus.pushbox.data.MessageRow
import cn.iamplus.pushbox.net.PushBoxApi
import cn.iamplus.pushbox.ui.ImageCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import cn.iamplus.pushbox.data.MessageAnchor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ConversationMessages(
    val query: String? = null,
    val rows: List<MessageRow>? = null,
)

/** 首屏与每次向上补页的条数。50 条在手机上一屏约 8–10 条，够滚五六屏，也不会为一次点开读整段历史。 */
internal const val TimelinePageSize = 50

@OptIn(ExperimentalCoroutinesApi::class)
internal fun conversationMessages(
    queries: Flow<String>,
    load: (String?) -> Flow<List<MessageRow>>,
): Flow<ConversationMessages> = queries.transformLatest { raw ->
    if (raw.isNotBlank()) delay(250)
    emit(raw.trim().ifEmpty { null })
}.distinctUntilChanged().flatMapLatest { query ->
    // 查询与结果一起发布，退出筛选时不能拿筛选下标定位全量列表。
    load(query).map { ConversationMessages(query, it) }
        .onStart { emit(ConversationMessages(query)) }
}

internal class MessagesViewModel(private val app: App) : ViewModel() {
    val conversations = app.dao.conversations().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _fetching = MutableStateFlow<Set<String>>(emptySet())
    val fetching = _fetching.asStateFlow()
    private val _clearError = MutableStateFlow<String?>(null)
    val clearError = _clearError.asStateFlow()

    /**
     * 当前时间线窗口：下界是已经加载的最旧一条，向上补页时只把这条往下挪。
     * 查询本身是 Room 的 Flow，所以窗口里任何一行的增删改都会照常刷新，
     * 而窗口外的历史不会被读出来——打开一条几千条的会话不再等于把几千行搬进内存。
     */
    private data class TimelineWindow(
        val channelNo: String,
        val senderId: String,
        val since: MessageAnchor?,
    ) {
        fun isFor(channelNo: String, senderId: String) =
            this.channelNo == channelNo && this.senderId == senderId
    }

    private val window = MutableStateFlow<TimelineWindow?>(null)
    private val _hasOlder = MutableStateFlow(false)
    val hasOlder = _hasOlder.asStateFlow()
    private val _loadingEarlier = MutableStateFlow(false)
    val loadingEarlier = _loadingEarlier.asStateFlow()

    /**
     * 打开会话时先算出首屏下界，再让时间线订阅窗口。
     * 这一步跑在 IO：锚点查询走 (channel_no, sender_id, created_at, seq) 索引，
     * 只读一页而不碰更早的历史。空会话锚点为 null，窗口不设下界。
     */
    fun openTimeline(channelNo: String, senderId: String) {
        if (window.value?.isFor(channelNo, senderId) == true) return
        viewModelScope.launch(Dispatchers.IO) {
            val anchor = app.dao.timelineAnchor(channelNo, senderId, TimelinePageSize - 1)
            val older = anchor != null &&
                app.dao.hasMessagesOlderThan(channelNo, senderId, anchor.createdAt, anchor.seq)
            if (window.value?.isFor(channelNo, senderId) == true) return@launch
            _hasOlder.value = older
            _loadingEarlier.value = false
            window.value = TimelineWindow(channelNo, senderId, anchor)
        }
    }

    /** 向上补一页：先把更早的一页读出来，成功后才把窗口下界往下挪，失败时列表保持原样。 */
    fun loadEarlier() {
        val current = window.value ?: return
        val since = current.since ?: return
        if (!_hasOlder.value || _loadingEarlier.value) return
        _loadingEarlier.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val page = app.dao.messagesOlderThan(current.channelNo, current.senderId,
                    since.createdAt, since.seq, TimelinePageSize)
                if (page.isEmpty()) {
                    _hasOlder.value = false
                    return@launch
                }
                // 查询按 DESC 取最近的一页，最后一条才是这一页里最旧的
                val oldest = page.last()
                val anchor = MessageAnchor(oldest.createdAt, oldest.seq)
                val older = app.dao.hasMessagesOlderThan(current.channelNo, current.senderId,
                    anchor.createdAt, anchor.seq)
                if (window.value == current) {
                    _hasOlder.value = older
                    window.value = current.copy(since = anchor)
                }
            } finally {
                _loadingEarlier.value = false
            }
        }
    }

    fun messages(channelNo: String, senderId: String, queries: Flow<String>) =
        conversationMessages(queries) { q ->
            // 搜索要的是整段历史里的命中项，不受时间线窗口约束
            if (q != null) {
                app.dao.messagesFlow(channelNo, senderId, q)
            } else {
                window.flatMapLatest { w ->
                    if (w?.isFor(channelNo, senderId) != true) emptyFlow()
                    else app.dao.messagesSince(channelNo, senderId, w.since?.createdAt, w.since?.seq)
                }
            }
        }

    fun setAvatar(row: ConversationRow, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            persistAvatar(app.applicationContext, row, uri)?.let {
                app.dao.setAvatar(row.channelNo, row.senderId, it)
            }
        }
    }

    fun togglePin(row: ConversationRow) {
        viewModelScope.launch(Dispatchers.IO) {
            app.dao.setPinned(row.channelNo, row.senderId, !row.pinned)
        }
    }

    fun toggleMute(row: ConversationRow) {
        viewModelScope.launch(Dispatchers.IO) {
            app.dao.setMuted(row.channelNo, row.senderId, !row.muted)
        }
    }

    fun toggleLockPrivate(row: ConversationRow) {
        viewModelScope.launch(Dispatchers.IO) {
            app.dao.setLockPrivate(row.channelNo, row.senderId, !row.lockPrivate)
        }
    }

    fun rename(row: ConversationRow, name: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            app.dao.renameConversation(row.channelNo, row.senderId, name)
        }
    }

    fun clear(row: ConversationRow, identity: Identity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                app.dao.conversationMedia(row.channelNo, row.senderId)
                    .forEach { ImageCache.evictMessage(it.msgId, it.mediaCount) }
                app.dao.clearConversation(row.channelNo, row.senderId)
            }
            try {
                PushBoxApi(identity.baseUrl, app.http)
                    .clearConversation(identity, row.channelNo, row.senderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _clearError.value = e.message ?: e::class.java.simpleName
            }
        }
    }

    fun consumeClearError() {
        _clearError.value = null
    }

    // 本地清未读必须随详情离屏取消，不能把离屏后刚收到的消息也清掉。
    suspend fun markRead(row: ConversationRow) {
        withContext(Dispatchers.IO) {
            app.dao.markConversationRead(row.channelNo, row.senderId)
        }
        app.notifier.cancelConversation(row.channelNo, row.senderId)
        val live = app.identity.load() ?: return
        viewModelScope.launch {
            try {
                PushBoxApi(live.baseUrl, app.http).reportRead(live,
                    listOf(PushBoxApi.ReadEntry(row.channelNo, row.senderId, row.lastSeq)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 上报失败不撤回本地已读。
            }
        }
    }

    fun delete(row: MessageRow) {
        viewModelScope.launch(Dispatchers.IO) {
            app.dao.tombstoneMessage(row.msgId)
                ?.let { ImageCache.evictMessage(it.msgId, it.mediaCount) }
            val live = app.identity.load() ?: return@launch
            try {
                PushBoxApi(live.baseUrl, app.http).deleteMessage(live, row.msgId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 上报失败不恢复本地消息。
            }
        }
    }

    fun fetchFulltext(msgId: String) {
        if (msgId in _fetching.value) return
        _fetching.update { it + msgId }
        viewModelScope.launch {
            try {
                val live = app.identity.load() ?: return@launch
                val full = PushBoxApi(live.baseUrl, app.http).fulltext(live, msgId)
                full?.content?.takeIf { it.isNotBlank() }?.let {
                    withContext(Dispatchers.IO) { app.dao.setFulltext(msgId, it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 按钮恢复可点，由用户重试。
            } finally {
                _fetching.update { it - msgId }
            }
        }
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MessagesViewModel(app) as T
        }
    }
}
