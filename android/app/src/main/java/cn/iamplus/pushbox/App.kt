package cn.iamplus.pushbox

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.iamplus.pushbox.data.IdentityStore
import cn.iamplus.pushbox.data.PinHash
import cn.iamplus.pushbox.data.PushBoxDatabase
import cn.iamplus.pushbox.notify.Notifier
import cn.iamplus.pushbox.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * P0 手写依赖容器：全 App 就这四个单例，上 Hilt 只是把四行代码变成四个注解。
 * 等 feature 模块真按 §7.1 拆开了再换 DI 框架不迟。
 */
class App : android.app.Application() {

    lateinit var identity: IdentityStore
        private set
    lateinit var db: PushBoxDatabase
        private set
    lateinit var notifier: Notifier
        private set
    lateinit var http: OkHttpClient
        private set

    /** 长连接状态由 PushService 内的 SyncEngine 镜像进来，UI 顶栏据此显示真实连接态。 */
    val connection = MutableStateFlow<SyncEngine.State>(SyncEngine.State.Idle)

    /** §7.3 未读总数：App 冷启和每次 DAO 变化都刷新，PushService 借此更新常驻通知的 number。 */
    val totalUnread = MutableStateFlow(0)

    /** 通知总开关，"我的"页可切换；Notifier 在弹通知前读它。 */
    val notificationsEnabled = MutableStateFlow(true)

    /** §7.5 外观模式：system / light / dark，UI 观察它即时换肤。 */
    val themeMode = MutableStateFlow("system")

    /** §7.4 外观字号：small / normal / large，只缩放文字不改 dp。 */
    val fontScale = MutableStateFlow("normal")

    /** §7.4 外观摘要行数：会话列表摘要 1 或 2 行。 */
    val summaryLines = MutableStateFlow(1)

    fun setFontScale(scale: String) {
        fontScale.value = scale
        scope.launch { identity.setFontScale(scale) }
    }

    fun setSummaryLines(lines: Int) {
        summaryLines.value = lines
        scope.launch { identity.setSummaryLines(lines) }
    }

    /** §7.2 D18 首启保活引导页是否已完成；true 之后不再拦截主界面。 */
    val keepAliveGuided = MutableStateFlow(true)

    /** §7.2 极致省电轮询模式：开则停常驻长连接、改由 WorkManager 周期补拉（消息可能延迟）。 */
    val pollingMode = MutableStateFlow(false)

    /** D32 启动时自动检查更新：默认开；关掉后冷启动那一趟 GitHub 请求不发，只留"关于"里的手动按钮。 */
    val autoCheckUpdate = MutableStateFlow(true)

    /**
     * 发现但没有当场处理的新版：冷启动静默查到、或"关于"里点出来的，都塞这里给同一只确认框观察。
     * 只有用户在框里点"下载并安装"才会真的取包——更新提示是"问一句"，不是"直接下"。
     */
    val pendingUpdate = MutableStateFlow<cn.iamplus.pushbox.net.UpdateApi.Latest?>(null)

    fun setAutoCheckUpdate(enabled: Boolean) {
        autoCheckUpdate.value = enabled
        scope.launch { identity.setAutoCheckUpdate(enabled) }
    }

    fun setPollingMode(enabled: Boolean) {
        pollingMode.value = enabled
        scope.launch { identity.setPollingMode(enabled) }
        // 切换即时生效：轮询模式停 FGS 并排周期任务；否则取消周期任务、拉起常驻服务。
        if (enabled) {
            cn.iamplus.pushbox.push.PushService.stop(this)
            cn.iamplus.pushbox.push.SyncWorker.schedule(this)
            connection.value = SyncEngine.State.Idle
        } else {
            cn.iamplus.pushbox.push.SyncWorker.cancel(this)
            cn.iamplus.pushbox.push.PushService.start(this)
        }
    }

    fun finishKeepAliveGuide() {
        keepAliveGuided.value = true
        scope.launch { identity.setKeepAliveGuided(true) }
    }

    fun setThemeMode(mode: String) {
        themeMode.value = mode
        scope.launch { identity.setThemeMode(mode) }
    }

    /** §7.4 通知静默时段：默认关；起止为"距 0 点分钟数"，跨零点时 start>end。 */
    val quietEnabled = MutableStateFlow(false)
    val quietStart = MutableStateFlow(22 * 60)
    val quietEnd = MutableStateFlow(8 * 60)

    /** 静默窗口判定：Now（Calendar 已含时区）落在 [start,end) 内即为静默；跨零点走补集判断。 */
    fun inQuietHours(): Boolean {
        if (!quietEnabled.value) return false
        val cal = java.util.Calendar.getInstance()
        val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val s = quietStart.value
        val e = quietEnd.value
        return if (s <= e) now in s until e else now >= s || now < e
    }

    fun setQuiet(enabled: Boolean, startMin: Int, endMin: Int) {
        quietEnabled.value = enabled
        quietStart.value = startMin
        quietEnd.value = endMin
        scope.launch { identity.setQuiet(enabled, startMin, endMin) }
    }

    /** §8.4 D30 App 锁：默认关闭；locked 冷启即为 true（若开启），退到后台也会重新上锁。 */
    val appLockEnabled = MutableStateFlow(false)
    val locked = MutableStateFlow(false)
    /** 生物识别解锁：仅在 App 锁开启时生效，PIN 始终保留为兜底。 */
    val biometricEnabled = MutableStateFlow(false)

    fun setBiometric(enabled: Boolean) {
        biometricEnabled.value = enabled
        scope.launch { identity.setBiometricEnabled(enabled) }
    }

    /** 还在退避期就返回剩余秒数，0 表示可以试下一次；UI 用它置灰按钮并倒数。 */
    fun pinRetrySec(): Int {
        val left = pinLockUntil.value - SystemClock.elapsedRealtime()
        return if (left > 0) ((left + 999) / 1000).toInt() else 0
    }

    /**
     * 校验一次 PIN。PBKDF2 那 12 万轮在低端机上是几百毫秒，所以这个函数是 suspend
     * 且把推导切到 Default 线程——调用方只能在协程里点解锁，不能一边打字一边逐字符验。
     * 失败按 5/15/30/60 秒（封顶）递增退避，成功一次清零：拦的是守在这儿枚举数字的人。
     */
    suspend fun checkPin(pin: String): Boolean {
        val stored = pinHash?.takeIf { appLockEnabled.value } ?: return false
        if (pinRetrySec() > 0) return false
        var upgraded = false
        val ok = withContext(Dispatchers.Default) {
            if (PinHash.isLegacy(stored)) {
                // 旧版是无盐单轮 SHA-256。验过就当场换成带盐的 PBKDF2，用户不用重设 PIN。
                val match = sha256(pin) == stored
                if (match) {
                    pinHash = PinHash.create(pin)
                    upgraded = true
                }
                match
            } else {
                PinHash.matches(pin, stored)
            }
        }
        if (ok) {
            pinFailures = 0
            pinLockUntil.value = 0L
            if (upgraded) {
                val toStore = pinHash
                scope.launch { identity.setAppLock(true, toStore) }
            }
        } else {
            pinFailures += 1
            val waitSec = BACKOFF_SEC[minOf(pinFailures, BACKOFF_SEC.size) - 1]
            pinLockUntil.value = SystemClock.elapsedRealtime() + waitSec * 1000L
        }
        return ok
    }

    fun unlock() {
        if (appLockEnabled.value) locked.value = false
    }

    fun relock() {
        if (appLockEnabled.value) locked.value = true
    }

    /**
     * 开关与设置/清除 PIN 都走这里：pin=null 表示仅改开关、沿用旧 PIN。
     * suspend 的理由同 [checkPin]——derive 一次 PBKDF2 不是主线程该干的活。
     */
    suspend fun setAppLock(enabled: Boolean, pin: String?) {
        appLockEnabled.value = enabled
        pinFailures = 0
        pinLockUntil.value = 0L
        if (enabled) {
            if (pin != null) pinHash = withContext(Dispatchers.Default) { PinHash.create(pin) }
            locked.value = true
            val toStore = pinHash
            scope.launch { identity.setAppLock(true, toStore) }
        } else {
            pinHash = null
            locked.value = false
            biometricEnabled.value = false
            scope.launch { identity.setAppLock(false, null) }
            scope.launch { identity.setBiometricEnabled(false) }
        }
    }

    @Volatile private var pinHash: String? = null
    @Volatile private var pinFailures = 0

    /** 退避到什么时候（elapsedRealtime 毫秒，0=没在退避）。UI 观察它置灰按钮并倒数。 */
    val pinLockUntil = MutableStateFlow(0L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 更新检查是低频公开接口（api.github.com 未认证 60 次/小时/IP），一个进程只被动查一次，手点另算。
    @Volatile private var updateCheckedThisProcess = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        identity = IdentityStore(this)
        db = Room.databaseBuilder(this, PushBoxDatabase::class.java, "pushbox.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigration()
            .build()
        notifier = Notifier(this)
        http = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        scope.launch { notificationsEnabled.value = identity.notificationsEnabled() }
        scope.launch { themeMode.value = identity.themeMode() }
        scope.launch { fontScale.value = identity.fontScale() }
        scope.launch { summaryLines.value = identity.summaryLines() }
        scope.launch { keepAliveGuided.value = identity.keepAliveGuided() }
        scope.launch { pollingMode.value = identity.pollingMode() }
        scope.launch { autoCheckUpdate.value = identity.autoCheckUpdate() }
        scope.launch {
            quietEnabled.value = identity.quietEnabled()
            quietStart.value = identity.quietStartMin()
            quietEnd.value = identity.quietEndMin()
        }
        scope.launch {
            val enabled = identity.appLockEnabled()
            appLockEnabled.value = enabled
            if (enabled) {
                pinHash = identity.appLockPinHash()
                locked.value = true
                biometricEnabled.value = identity.biometricEnabled()
            }
        }
        // §7.3 桌面图标角标：未读总数一变就同步到 launcher（各厂商反射 + 服务通知 number 兜底）
        scope.launch {
            dao.totalUnread().collect {
                totalUnread.value = it
                notifier.updateBadge(it)
            }
        }
    }

    val dao get() = db.dao()

    /**
     * 冷启动被动查一次 GitHub Releases（只在开关开着时）：发现可装的新版就交给同一只确认框，
     * 下载这一步仍要用户在框里点"下载并安装"——更新提示是"问一句"，不是"直接下"。
     * 读盘里的开关而不是内存里的 StateFlow：这条在 onCreate 紧接着跑，那份异步回填可能还没落地。
     */
    fun checkUpdateSilently() {
        if (updateCheckedThisProcess) return
        updateCheckedThisProcess = true
        scope.launch {
            runCatching {
                if (!identity.autoCheckUpdate()) return@runCatching
                val cur = cn.iamplus.pushbox.net.UpdateApi.currentVersion(this@App)
                val latest = cn.iamplus.pushbox.net.UpdateApi.check(http)
                if (latest.apkUrl != null &&
                    cn.iamplus.pushbox.net.UpdateApi.isNewer(latest.version, cur.versionName)) {
                    pendingUpdate.value = latest
                }
            }
        }
    }

    companion object {
        private lateinit var instance: App

        /** 给非 Composable 的单例（如 ImageCache）取用；Application 一定先于任何页面创建。 */
        val current: App get() = instance

        fun sha256(pin: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(pin.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** 连续第 N 次失败要等多久（第 4 次以后一直是 60 秒）。 */
        private val BACKOFF_SEC = listOf(5, 15, 30, 60)

        // v1→v2：给 conversations 加一列本地重命名（custom_name），ALTER 即可，保留既有消息
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN custom_name TEXT")
            }
        }

        // v2→v3：给 messages 加 media_count（§4.5 图片链路），既有行默认 0 即无图
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN media_count INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v3→v4：补上之前漏掉的迁移脚本（当时靠 fallbackToDestructiveMigration 清库）。
         * 现在用户可能已有会话设置，必须用 ALTER 加列保留数据。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // conversations 表新增字段
                db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN muted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN avatar_path TEXT")
                // messages 表新增字段
                db.execSQL("ALTER TABLE messages ADD COLUMN read_state INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN sender_avatar TEXT")
            }
        }

        /**
         * v4→v5：truncated + lock_private。旧数据留 NULL/0＝"不知道各槽状态"，UI 退回按消息级判断，不影响取图
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN truncated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN lock_private INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v5→v6：每槽媒体终态（§4.5）。旧行留 NULL＝"不知道各槽状态"，UI 退回按消息级判断，不影响取图
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN media_slots TEXT")
            }
        }

        fun get(context: Context): App = context.applicationContext as App
    }
}
