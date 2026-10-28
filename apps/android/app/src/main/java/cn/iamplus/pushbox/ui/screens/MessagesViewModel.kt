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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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

    fun messages(channelNo: String, senderId: String, queries: Flow<String>) =
        conversationMessages(queries) { app.dao.messagesFlow(channelNo, senderId, it) }

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
