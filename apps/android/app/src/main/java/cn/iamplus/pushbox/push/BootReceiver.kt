package cn.iamplus.pushbox.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * §7.2 D18 开机自启 / 升级重装后：不直接起 FGS（Android 12+ 后台起 remoteMessaging 会被拒），
 * 只确保 WorkManager 周期兜底在位，长连接等用户下次进 App 再自然恢复。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        SyncWorker.schedule(context)
    }
}
