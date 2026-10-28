package cn.iamplus.pushbox.ui.screens

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.data.Identity
import cn.iamplus.pushbox.net.PushBoxApi
import cn.iamplus.pushbox.notify.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 建频道时名称留空就用设备型号；服务端上限 24 字符，超了直接截断而不是让用户去猜。 */
internal fun channelNameOrDefault(raw: String, fallback: String): String =
    raw.trim().ifEmpty { fallback }.take(24)

/** 治理位草稿。数值一律按文本存：输入框里允许空，空＝"这一项没填"，与 0 不是一回事。 */
internal data class GovernanceDraft(
    val maxLevel: String,
    val maxImages: String,
    val rateLimit: String,
    val bodyBytes: String,
    val dailyMsg: String,
    val dailyImage: String,
    val dailyBytes: String,
    val strictAuth: Boolean,
    val muted: Boolean,
    val whitelist: Boolean,
)

/** 与当前值相同的字段发 null＝不提交：既少写一遍库，也避免把服务端 clamp 过的值回写成越界值。 */
internal fun governancePatch(draft: GovernanceDraft,
    current: PushBoxApi.ChannelInfo): PushBoxApi.ChannelPatch = PushBoxApi.ChannelPatch(
    maxLevel = draft.maxLevel.takeIf { it != current.maxLevel },
    maxImages = changedInt(draft.maxImages, current.maxImages),
    rateLimit = changedInt(draft.rateLimit, current.rateLimit),
    maxBodyBytes = changedInt(draft.bodyBytes, current.maxBodyBytes),
    dailyMsgLimit = changedInt(draft.dailyMsg, current.dailyMsgLimit),
    dailyImageLimit = changedInt(draft.dailyImage, current.dailyImageLimit),
    dailyBytesLimit = changedLong(draft.dailyBytes, current.dailyBytesLimit),
    strictAuth = draft.strictAuth.takeIf { it != current.strictAuth },
    muted = draft.muted.takeIf { it != current.muted },
    senderWhitelistMode = draft.whitelist.takeIf { it != current.senderWhitelistMode },
)

/**
 * 频道页的 ViewModel：列表、草稿、忙碌与错误都留在这里，
 * 请求走 viewModelScope——旋转或切页不再把刚发出的一次创建/重置打断在半路。
 */
internal class ChannelsViewModel(private val app: App) : ViewModel() {

    val name = MutableStateFlow("")
    val joinNo = MutableStateFlow("")
    val joinSecret = MutableStateFlow("")
    val subscribeSecret = MutableStateFlow("")
    val renameText = MutableStateFlow("")

    private val _channels = MutableStateFlow<List<PushBoxApi.ChannelInfo>?>(null)
    val channels = _channels.asStateFlow()
    val subscribedOnly = MutableStateFlow(false)

    private val _issued = MutableStateFlow<PushBoxApi.Channel?>(null)
    val issued = _issued.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _joinDone = MutableStateFlow(false)
    val joinDone = _joinDone.asStateFlow()

    /** 白名单编辑器仍自持列表，失败文案借用页面这一行。 */
    fun reportError(message: String) {
        _error.value = message
    }

    fun reload(identity: Identity) {
        viewModelScope.launch { refresh(identity) }
    }

    private suspend fun refresh(identity: Identity) {
        request { PushBoxApi(identity.baseUrl, app.http).channelListPage(identity) }
            .onSuccess { page ->
                _channels.value = page.channels
                // 开关只认服务端回显：本机自己记一份，早晚变成"屏幕上开着、服务端关着"
                subscribedOnly.value = page.subscribedOnly
                // 通知渠道建得比频道页早（第一次收消息时只有号码），拿到名字后回写，系统设置里才不是光秃秃的「频道 号码」
                Notifier(app).syncChannelNames(page.channels.associate { it.channelNo to it.name })
            }
            .onFailure { _error.value = messageOf(it) }
    }

    fun create(identity: Identity) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            val created = channelNameOrDefault(name.value, Build.MODEL ?: "channel")
            request { PushBoxApi(identity.baseUrl, app.http).createChannel(identity, created) }
                .onSuccess {
                    _issued.value = it
                    app.saveChannelSecret(it.channelNo, it.secret)
                    name.value = ""
                    _busy.value = false
                    refresh(identity)
                }
                .onFailure {
                    _error.value = messageOf(it)
                    _busy.value = false
                }
        }
    }

    fun join(identity: Identity, needBoth: String) {
        // 先把这两串抓住：成功后要清输入框，而记档用的正是用户刚敲的这份明文
        // （joinChannel 是 void，服务端不回显密钥）
        val no = joinNo.value.trim()
        val plain = joinSecret.value.trim()
        if (no.isEmpty() || plain.isEmpty()) {
            _error.value = needBoth
            return
        }
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            _joinDone.value = false
            request { PushBoxApi(identity.baseUrl, app.http).joinChannel(identity, no, plain) }
                .onSuccess {
                    app.saveChannelSecret(no, plain)
                    joinNo.value = ""
                    joinSecret.value = ""
                    _joinDone.value = true
                    _busy.value = false
                    refresh(identity)
                }
                .onFailure {
                    _error.value = messageOf(it)
                    _busy.value = false
                }
        }
    }

    fun subscribe(identity: Identity, channelNo: String) {
        val plain = subscribeSecret.value.trim()
        if (plain.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            _joinDone.value = false
            request {
                PushBoxApi(identity.baseUrl, app.http).joinChannel(identity, channelNo, plain)
            }.onSuccess {
                app.saveChannelSecret(channelNo, plain)
                _joinDone.value = true
                _busy.value = false
                refresh(identity)
            }.onFailure {
                _error.value = messageOf(it)
                _busy.value = false
            }
        }
    }

    fun leave(identity: Identity, channelNo: String) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            request { PushBoxApi(identity.baseUrl, app.http).leaveChannel(identity, channelNo) }
                .onSuccess {
                    _joinDone.value = true
                    _busy.value = false
                    refresh(identity)
                }
                .onFailure {
                    _error.value = messageOf(it)
                    _busy.value = false
                }
        }
    }

    fun rotate(identity: Identity, channelNo: String) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            request { PushBoxApi(identity.baseUrl, app.http).rotateSecret(identity, channelNo) }
                .onSuccess {
                    _issued.value = it
                    app.saveChannelSecret(it.channelNo, it.secret)
                }
                .onFailure { _error.value = messageOf(it) }
            _busy.value = false
        }
    }

    fun archive(identity: Identity, channelNo: String) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            request { PushBoxApi(identity.baseUrl, app.http).archiveChannel(identity, channelNo) }
                .onFailure { _error.value = messageOf(it) }
            _busy.value = false
            refresh(identity)
        }
    }

    fun rename(identity: Identity, channelNo: String) {
        val text = renameText.value.trim()
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            request {
                PushBoxApi(identity.baseUrl, app.http).renameChannel(identity, channelNo, text)
            }.onFailure { _error.value = messageOf(it) }
            _busy.value = false
            refresh(identity)
        }
    }

    fun clear(identity: Identity, channelNo: String) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            request { PushBoxApi(identity.baseUrl, app.http).clearChannel(identity, channelNo) }
                .onFailure { _error.value = messageOf(it) }
            _busy.value = false
        }
    }

    fun resetNo(identity: Identity, channelNo: String) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            request { PushBoxApi(identity.baseUrl, app.http).resetChannelNo(identity, channelNo) }
                .onSuccess { newNo ->
                    // 服务端换了对外号码：明文密钥没变，但接入地址要按新号重抄一份
                    _issued.value = PushBoxApi.Channel(newNo, "", "")
                    // 记档跟着号码走，否则新号码的「复制密钥」会查不到（密钥其实没变）
                    app.channelSecrets.value[channelNo]?.let { old ->
                        app.saveChannelSecret(channelNo, "")
                        app.saveChannelSecret(newNo, old)
                    }
                }
                .onFailure { _error.value = messageOf(it) }
            _busy.value = false
            refresh(identity)
        }
    }

    fun setSubscribedOnly(identity: Identity, want: Boolean) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            request { PushBoxApi(identity.baseUrl, app.http).setSubscriptionMode(identity, want) }
                .onSuccess { subscribedOnly.value = it }
                .onFailure { _error.value = messageOf(it) }
            _busy.value = false
        }
    }

    fun saveGovernance(identity: Identity, channelNo: String, patch: PushBoxApi.ChannelPatch) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            request { PushBoxApi(identity.baseUrl, app.http).updateChannel(identity, channelNo, patch) }
                .onFailure { _error.value = messageOf(it) }
            _busy.value = false
            refresh(identity)
        }
    }

    private suspend fun <T> request(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun messageOf(e: Throwable): String = e.message ?: e::class.java.simpleName

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChannelsViewModel(app) as T
        }
    }
}
