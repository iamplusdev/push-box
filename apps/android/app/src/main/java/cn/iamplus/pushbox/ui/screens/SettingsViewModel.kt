package cn.iamplus.pushbox.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.data.Identity
import cn.iamplus.pushbox.net.PushBoxApi
import cn.iamplus.pushbox.net.UpdateApi
import cn.iamplus.pushbox.notify.Notifier
import cn.iamplus.pushbox.ui.ImageCache
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 一次手动检查的三种落点；Newer 不进状态行，只把版本交给根节点的确认框。 */
internal enum class UpdateCheckKind { Newer, Latest, NoRelease }

/** 没有 apk 资产时"最新"和"没发布"要分开说：前者是已到最新，后者是发布页还没打包完。 */
internal fun updateCheckKind(latest: UpdateApi.Latest, currentVersion: String): UpdateCheckKind =
    when {
        latest.apkUrl == null -> UpdateCheckKind.NoRelease
        UpdateApi.isNewer(latest.version, currentVersion) -> UpdateCheckKind.Newer
        else -> UpdateCheckKind.Latest
    }

/** 进度条文案只认百分比；服务端没给总长（chunked）时停在 0%，不拿已下字节冒充进度。 */
internal fun downloadPercent(done: Long, total: Long): Int =
    if (total > 0) ((done * 100) / total).toInt() else 0

/**
 * 设置页的 ViewModel：设备列表、恢复码轮换、本地数据清理、通知渠道行与更新检查/下载。
 * 系统面板（安装器、电池优化、通知设置）与权限判定留在 UI——它们要 Activity 与系统回执，
 * 不是可迁移的纯状态；这里只保证请求本身不会因为旋转换页被打断。
 */
internal class SettingsViewModel(private val app: App) : ViewModel() {

    private val _devices = MutableStateFlow<List<PushBoxApi.DeviceInfo>?>(null)
    val devices = _devices.asStateFlow()
    private val _devicesError = MutableStateFlow<String?>(null)
    val devicesError = _devicesError.asStateFlow()
    private val _devicesBusy = MutableStateFlow(false)
    val devicesBusy = _devicesBusy.asStateFlow()

    // 恢复码从存储重新读：页面拿到的 identity 是进页面那一刻的快照，轮换后它已经过期
    private val _recoveryCode = MutableStateFlow<String?>(null)
    val recoveryCode = _recoveryCode.asStateFlow()
    private val _rotating = MutableStateFlow(false)
    val rotating = _rotating.asStateFlow()
    private val _rotationError = MutableStateFlow<String?>(null)
    val rotationError = _rotationError.asStateFlow()

    private val _notifyRows = MutableStateFlow<List<ChannelNotifyRow>?>(null)
    val notifyRows = _notifyRows.asStateFlow()
    private val _notifyError = MutableStateFlow<String?>(null)
    val notifyError = _notifyError.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking = _checking.asStateFlow()
    private val _checkStatus = MutableStateFlow<UpdateCheckKind?>(null)
    val checkStatus = _checkStatus.asStateFlow()
    private val _checkError = MutableStateFlow<String?>(null)
    val checkError = _checkError.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Pair<Long, Long>?>(null)
    val downloadProgress = _downloadProgress.asStateFlow()
    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError = _downloadError.asStateFlow()
    private val _downloadedApk = MutableStateFlow<File?>(null)
    val downloadedApk = _downloadedApk.asStateFlow()

    fun loadRecoveryCode() {
        viewModelScope.launch { _recoveryCode.value = app.identity.load()?.recoveryCode }
    }

    fun reloadDevices(identity: Identity) {
        viewModelScope.launch {
            _devices.value = null
            _devicesError.value = null
            request { PushBoxApi(identity.baseUrl, app.http).listDevices(identity) }
                .onSuccess { _devices.value = it }
                .onFailure { _devicesError.value = messageOf(it) }
        }
    }

    fun revokeDevice(identity: Identity, deviceId: String) {
        viewModelScope.launch {
            _devicesBusy.value = true
            _devicesError.value = null
            request { PushBoxApi(identity.baseUrl, app.http).revokeDevice(identity, deviceId) }
                .onFailure { _devicesError.value = messageOf(it) }
            _devicesBusy.value = false
            reloadDevices(identity)
        }
    }

    fun rotateRecoveryCode(identity: Identity) {
        viewModelScope.launch {
            _rotating.value = true
            _rotationError.value = null
            request { PushBoxApi(identity.baseUrl, app.http).rotateRecoveryCode(identity) }
                .onSuccess {
                    app.identity.saveRecoveryCode(it)
                    _recoveryCode.value = it
                }
                .onFailure { _rotationError.value = messageOf(it) }
            _rotating.value = false
        }
    }

    fun reloadNotifyRows(identity: Identity) {
        viewModelScope.launch {
            _notifyRows.value = null
            _notifyError.value = null
            request { PushBoxApi(identity.baseUrl, app.http).channelListPage(identity).channels }
                .onSuccess { list ->
                    val notifier = Notifier(app)
                    // 顺手对齐系统渠道名：用户从这一行点进系统设置时看到的应该是频道名而不是号码
                    notifier.syncChannelNames(list.associate { it.channelNo to it.name })
                    _notifyRows.value = list.map {
                        ChannelNotifyRow(it.channelNo, it.name, notifier.channelImportanceOf(it.channelNo))
                    }
                }
                .onFailure { _notifyError.value = messageOf(it) }
        }
    }

    /** §7.4 清空本地库；走 IO、进 viewModelScope，旋转不会把删到一半的事务打断。 */
    fun clearLocalData() {
        viewModelScope.launch(Dispatchers.IO) { app.db.clearAllTables() }
    }

    /** §7.4 只清已读消息关联的图片磁盘缓存，不动消息本体与身份。 */
    fun clearReadMediaCache() {
        viewModelScope.launch(Dispatchers.IO) {
            ImageCache.clearForReadMessages(app.dao.readMessagesWithMedia().map { it.msgId to it.mediaCount })
        }
    }

    fun checkUpdate(currentVersion: String) {
        if (_checking.value) return
        viewModelScope.launch {
            _checking.value = true
            _checkError.value = null
            _checkStatus.value = null
            request { UpdateApi.check(app.http) }
                .onSuccess { latest ->
                    when (updateCheckKind(latest, currentVersion)) {
                        // 发现新版就交给确认框：这里不留下载按钮，避免"两个入口都能起下载"
                        UpdateCheckKind.Newer -> app.pendingUpdate.value = latest
                        UpdateCheckKind.Latest -> _checkStatus.value = UpdateCheckKind.Latest
                        UpdateCheckKind.NoRelease -> _checkStatus.value = UpdateCheckKind.NoRelease
                    }
                }
                .onFailure { _checkError.value = messageOf(it) }
            _checking.value = false
        }
    }

    /**
     * 下载交给 viewModelScope：对话框被组合重建或用户返回上一页都不会把进度弄丢。
     * 完成后只把文件交出去，撤掉 pendingUpdate 与拉起安装面板由 UI 做——
     * 安装器要 Activity，ViewModel 拿不到。
     */
    fun downloadUpdate(latest: UpdateApi.Latest) {
        val url = latest.apkUrl ?: return
        if (_downloadProgress.value != null) return
        viewModelScope.launch {
            _downloadError.value = null
            // 非空即"下载中"：真正的总长要等回调，先占位让按钮立刻禁用
            _downloadProgress.value = 0L to 0L
            request {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                val dest = File(dir, latest.apkName)
                UpdateApi.download(app.http, url, dest) { done, total ->
                    _downloadProgress.value = done to total
                }
                dest
            }.onSuccess {
                _downloadProgress.value = null
                _downloadedApk.value = it
            }.onFailure {
                _downloadProgress.value = null
                _downloadError.value = messageOf(it)
            }
        }
    }

    fun consumeDownloadedApk() {
        _downloadedApk.value = null
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
                SettingsViewModel(app) as T
        }
    }
}
