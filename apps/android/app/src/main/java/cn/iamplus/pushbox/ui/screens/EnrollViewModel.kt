package cn.iamplus.pushbox.ui.screens

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.iamplus.pushbox.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 地址由主机+端口+协议推导，而不是另存一份：存了就会出现"开关拨了、URL 没跟着变"的半更新状态。 */
internal fun buildBaseUrl(host: String, port: String, https: Boolean): String {
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

/** 历史记录里存的是整条 URL，回填时要拆回主机/端口/协议三个格子，否则用户看到还是上一次的组合。 */
internal data class ServerParts(val https: Boolean, val host: String, val port: String)

internal fun parseServerUrl(url: String): ServerParts {
    val secured = url.startsWith("https://")
    val rest = url.substringAfter("://").substringBefore('/')
    val colon = rest.lastIndexOf(':')
    return ServerParts(
        https = secured,
        host = if (colon > 0) rest.substring(0, colon) else rest,
        port = if (colon > 0) rest.substring(colon + 1) else "",
    )
}

// 普通 ViewModel 只保留配置变更期间的状态，不保证进程死亡后的表单恢复。
internal class EnrollViewModel(private val app: App) : ViewModel() {

    val serverHost = MutableStateFlow("")
    val serverPort = MutableStateFlow("7404")
    val useHttps = MutableStateFlow(false)
    val deviceName = MutableStateFlow(Build.MODEL ?: "Android")
    val recoveryCode = MutableStateFlow("")
    val showRecovery = MutableStateFlow(false)

    val history = MutableStateFlow<List<String>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val issuedCode = MutableStateFlow<String?>(null)

    val baseUrl: StateFlow<String> =
        combine(serverHost, serverPort, useHttps) { h, p, s -> buildBaseUrl(h, p, s) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        viewModelScope.launch {
            val recent = app.identity.serverHistory()
            history.value = recent
            if (recent.isNotEmpty()) applyUrl(recent.first())
        }
    }

    fun applyUrl(url: String) {
        val parts = parseServerUrl(url)
        useHttps.value = parts.https
        serverHost.value = parts.host
        serverPort.value = parts.port
    }

    fun onScanResult(text: String?) {
        if (!text.isNullOrBlank()) recoveryCode.value = text.trim()
    }

    fun openRecovery() {
        showRecovery.value = true
    }

    fun closeRecovery() {
        showRecovery.value = false
        recoveryCode.value = ""
    }

    /** 校验文案由 UI 层取（stringResource），这里只接结果；onDone 是导航回调，点提交那一刻传入。 */
    fun submit(serverRequired: String, serverInvalid: String, onDone: () -> Unit) {
        val url = baseUrl.value.trim().trimEnd('/')
        if (url.isEmpty()) {
            error.value = serverRequired
            return
        }
        if (!SERVER_URL.matches(url)) {
            error.value = serverInvalid
            return
        }
        viewModelScope.launch {
            busy.value = true
            error.value = null
            val outcome = runCatching {
                enroll(app, url, deviceName.value.trim(),
                    recoveryCode.value.trim().ifEmpty { null })
            }
            busy.value = false
            outcome.fold(
                onSuccess = { code -> if (code == null) onDone() else issuedCode.value = code },
                onFailure = { error.value = it.message ?: it::class.java.simpleName })
        }
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                EnrollViewModel(app) as T
        }
    }
}
