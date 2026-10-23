package cn.iamplus.pushbox.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.net.PushBoxApi
import cn.iamplus.pushbox.sync.SyncEngine
import java.util.concurrent.TimeUnit

/**
 * §7.2 D18 WorkManager 兜底：常驻服务被 ROM 杀掉时，周期任务用 REST 把游标之后的缺口补上。
 * 只做 REST 补拉而不是起前台服务——Android 12+ 后台起 remoteMessaging FGS 会被拒；
 * 长连接等用户下次进 App 时自然恢复，这里保证的是"不丢消息"而不是"实时到账"。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = App.get(applicationContext)
        val identity = app.identity.load() ?: return Result.success()
        val engine = SyncEngine(app.dao, app.identity, app.notifier) { PushBoxApi(it, app.http) }
        return runCatching { engine.catchUpFromCursor(identity) }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() })
    }

    companion object {
        private const val UNIQUE = "pb_sync_fallback"

        /** 唯一周期任务：UPDATE 策略保证注册/换地址后只有一个在跑，不叠加。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }
}
