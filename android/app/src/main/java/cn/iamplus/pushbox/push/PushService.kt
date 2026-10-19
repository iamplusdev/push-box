package cn.iamplus.pushbox.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.data.Identity
import cn.iamplus.pushbox.net.PushBoxApi
import cn.iamplus.pushbox.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * §7.2 后台常驻：长连接唯一持有者。
 * 重连与退避在 SyncEngine 里，本服务只负责"活着 + 网络一变就催它重试"。
 */
class PushService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: SyncEngine? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var identity: Identity? = null

    override fun onCreate() {
        super.onCreate()
        val app = App.get(this)
        // remoteMessaging 类型只在 API 34+ 存在；低版本传 0 让系统自己按权限归类
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, app.notifier.serviceNotification(false), type)
        engine = SyncEngine(app.dao, app.identity, app.notifier) { base ->
            PushBoxApi(base, app.http)
        }
        // 把连接状态镜像到 App 单例，UI 顶栏才能显示真实的"已连接/连接中/未连接"
        val live = engine!!
        scope.launch { live.current.collect { app.connection.value = it } }
        // §7.3 桌面图标角标：连接态或未读数任一变化，都用同一 id 重发常驻通知，让读
        // Notification.number 的 launcher（三星 One UI 等）在图标上刷出数字。
        scope.launch {
            kotlinx.coroutines.flow.combine(app.connection, app.totalUnread) { state, unread ->
                val connected = state is cn.iamplus.pushbox.sync.SyncEngine.State.Online
                app.notifier.serviceNotification(connected, unread)
            }.collect { n ->
                androidx.core.app.NotificationManagerCompat.from(this@PushService)
                    .notify(NOTIFICATION_ID, n)
            }
        }
        registerNetworkListener()
        // 常驻之外再挂一层 WorkManager 兜底：被 ROM 杀掉时周期用 REST 补缺口（§7.2 D18）
        SyncWorker.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            val loaded = App.get(applicationContext).identity.load()
            if (loaded == null) {
                // 开机自启 / WorkManager 兜底可能跑在首启之前：没注册就别常驻
                stopSelf()
                return@launch
            }
            identity = loaded
            engine?.start(loaded)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        callback?.let { live ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(live)
            }
        }
        callback = null
        engine?.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 断网时 OkHttp 的 onFailure 会触发退避，网络恢复则要立刻重试而不是等 60s（§7.2）。 */
    private fun registerNetworkListener() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val live = identity ?: return
                scope.launch { engine?.retryNow(live) }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        manager.registerNetworkCallback(request, listener)
        callback = listener
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "cn.iamplus.pushbox.action.STOP"

        fun start(context: Context) = context.startForegroundService(Intent(context, PushService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, PushService::class.java).setAction(ACTION_STOP))
    }
}
