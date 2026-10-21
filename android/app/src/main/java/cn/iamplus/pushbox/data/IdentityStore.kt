package cn.iamplus.pushbox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pushbox.core.crypto.B64
import dev.pushbox.core.crypto.Ed25519
import dev.pushbox.core.crypto.X25519
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * §4.2 设备身份：X25519(下行帧加密) + Ed25519(请求签名) 两把私钥只在首启时生成一次，
 * 之后一直留在本机私有存储里；重装要靠恢复码重新注册一把新的。
 *
 * 私钥 / token / 恢复码 / PIN 验证串都先过 [SecretVault] 再落盘，所以那份私有文件里
 * 只剩 `v1:<iv>:<密文>`；设备号 / 分组 / 服务端指纹 / 地址不是凭据，仍然直接可读，方便排查。
 */
class IdentityStore(context: Context) {

    private val store: DataStore<Preferences> = context.applicationContext.dataStore
    private val vault = SecretVault()

    suspend fun load(): Identity? {
        val p = store.data.first()
        // 老版本把这几串明文直接写在盘上，就地补封一次，之后文件里再也读不出裸值
        upgradeStorage(p)
        val enc = p[ENC_PRIV]?.let { vault.open(it) } ?: return null
        val sign = p[SIGN_PRIV]?.let { vault.open(it) } ?: return null
        val deviceId = p[DEVICE_ID] ?: return null
        val token = p[TOKEN]?.let { vault.open(it) } ?: return null
        return Identity(
            encPrivate = B64.dec(enc),
            signPrivate = B64.dec(sign),
            deviceId = deviceId,
            token = token,
            groupId = p[GROUP_ID].orEmpty(),
            recoveryCode = p[RECOVERY_CODE]?.let { vault.open(it) },
            serverPubFp = p[SERVER_FP],
            baseUrl = p[BASE_URL].orEmpty(),
            // 帧序号是每个连接各自从 0 开始的（服务端断连即丢状态），落库的只有会话游标
            lastSeq = p[LAST_SEQ]?.toLongOrNull() ?: 0L,
        )
    }

    /**
     * 首启用 Keystore 密封之后的老数据兼容：那几串凭据在旧版本里是裸写明文的，
     * 读到就顺手补封一次并回写，不需要清数据重装。已是 `v1:` 前缀的一律跳过，
     * 所以稳态下这个函数只读不写。
     *
     * 密封用的是本机 Keystore 里那把不可导出的密钥，换机/恢复备份都解不开，
     * 但这份文件本来也只在本机读；真正的跨设备迁移一直是走恢复码重新注册。
     */
    private suspend fun upgradeStorage(p: Preferences) {
        val pending = SECRET_KEYS.filter { key ->
            val raw = p[key]
            raw != null && raw.isNotEmpty() && !vault.isSealed(raw)
        }
        if (pending.isEmpty()) return
        store.edit { mut ->
            // 不用 key to value：datastore 给 Preferences.Key 定义过同名的 to，返回的 Pair 没有解构分量
            for (key in pending) {
                val raw = p[key] ?: continue
                mut[key] = vault.seal(raw)
            }
        }
    }

    /** 首启新生成的密钥要等 register 返回 device_id/token 才算完整，所以分两步写。 */
    suspend fun newKeys(): Pair<X25519.KeyPair, Ed25519.KeyPair> {
        val enc = X25519.generate()
        val sign = Ed25519.generate()
        store.edit {
            it[ENC_PRIV] = vault.seal(B64.enc(enc.privateKey()))
            it[SIGN_PRIV] = vault.seal(B64.enc(sign.privateKey()))
        }
        return enc to sign
    }

    suspend fun saveEnrolled(
        deviceId: String,
        token: String,
        groupId: String,
        recoveryCode: String?,
        serverPubFp: String?,
        baseUrl: String,
    ) = store.edit {
        it[DEVICE_ID] = deviceId
        it[TOKEN] = vault.seal(token)
        it[GROUP_ID] = groupId
        if (recoveryCode != null) it[RECOVERY_CODE] = vault.seal(recoveryCode)
        if (serverPubFp != null) it[SERVER_FP] = serverPubFp
        it[BASE_URL] = baseUrl
    }

    suspend fun saveServerPubFp(fp: String) = store.edit { it[SERVER_FP] = fp }

    /** §14 轮换恢复码后要把新码写回来，否则"我的"页继续展示一个已作废的旧码。 */
    suspend fun saveRecoveryCode(code: String) = store.edit { it[RECOVERY_CODE] = vault.seal(code) }

    suspend fun saveLastSeq(seq: Long) = store.edit { it[LAST_SEQ] = seq.toString() }

    /** 换服务器=重新注册：清空整份身份，回到首启注册页。历史地址保留在另一处，不随此清空。 */
    suspend fun clear() = store.edit { it.clear() }

    /** 最近用过的服务器地址，最新在前（换行分隔，因 StringSet 无序）。 */
    suspend fun serverHistory(): List<String> =
        store.data.first()[SERVER_HISTORY]?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    suspend fun addServerHistory(url: String) = store.edit {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return@edit
        val current = it[SERVER_HISTORY]?.split('\n')?.filter { s -> s.isNotBlank() }.orEmpty()
        val next = (listOf(trimmed) + current.filter { s -> s != trimmed }).take(10)
        it[SERVER_HISTORY] = next.joinToString("\n")
    }

    suspend fun notificationsEnabled(): Boolean =
        store.data.first()[NOTIF_ENABLED] ?: true

    suspend fun setNotificationsEnabled(enabled: Boolean) =
        store.edit { it[NOTIF_ENABLED] = enabled }

    /** §7.4 静默时段：默认关；起止是"距 0 点分钟数"，跨零点时 start>end。 */
    suspend fun quietEnabled(): Boolean = store.data.first()[QUIET_ENABLED] ?: false
    suspend fun quietStartMin(): Int = store.data.first()[QUIET_START] ?: (22 * 60)
    suspend fun quietEndMin(): Int = store.data.first()[QUIET_END] ?: (8 * 60)

    suspend fun setQuiet(enabled: Boolean, startMin: Int, endMin: Int) = store.edit {
        it[QUIET_ENABLED] = enabled
        it[QUIET_START] = startMin
        it[QUIET_END] = endMin
    }

    /** §7.5 外观：system / light / dark，与身份同库但互不影响。 */
    suspend fun themeMode(): String = store.data.first()[THEME_MODE] ?: "system"

    suspend fun setThemeMode(mode: String) = store.edit { it[THEME_MODE] = mode }

    /** §7.4 外观字号：small / normal / large，仅作用于文字缩放，不改 dp 尺寸。 */
    suspend fun fontScale(): String = store.data.first()[FONT_SCALE] ?: "normal"

    suspend fun setFontScale(scale: String) = store.edit { it[FONT_SCALE] = scale }

    /** §7.4 外观摘要行数：会话列表摘要显示 1 或 2 行。 */
    suspend fun summaryLines(): Int = store.data.first()[SUMMARY_LINES] ?: 1

    suspend fun setSummaryLines(lines: Int) = store.edit { it[SUMMARY_LINES] = lines }

    /** §7.2 D18 首启保活引导页：默认未看；看完或跳过后置位，下次不再拦。 */
    suspend fun keepAliveGuided(): Boolean = store.data.first()[KEEPALIVE_GUIDED] ?: false

    suspend fun setKeepAliveGuided(done: Boolean) = store.edit { it[KEEPALIVE_GUIDED] = done }

    /**
     * §7.2 通知权限被"不再询问"之后那一发恢复提示：默认未提示，永久拒绝时提示一次。
     * 只留一个布尔位是为了别变成天天拦人——系统的框已经不会再出现了，本应用也只想说一次。
     */
    suspend fun notifyRecoveryShown(): Boolean = store.data.first()[NOTIFY_RECOVERY_SHOWN] ?: false

    suspend fun setNotifyRecoveryShown(shown: Boolean) = store.edit { it[NOTIFY_RECOVERY_SHOWN] = shown }

    /** §7.2 极致省电轮询模式：默认关（走常驻长连接）；开则停 FGS 改为 WorkManager 周期补拉。 */
    suspend fun pollingMode(): Boolean = store.data.first()[POLLING_MODE] ?: false

    suspend fun setPollingMode(enabled: Boolean) = store.edit { it[POLLING_MODE] = enabled }

    /** D32 启动时自动检查 GitHub 更新：默认开；关掉后冷启动那一趟 GitHub 请求就不发了。 */
    suspend fun autoCheckUpdate(): Boolean = store.data.first()[AUTO_CHECK_UPDATE] ?: true

    suspend fun setAutoCheckUpdate(enabled: Boolean) = store.edit { it[AUTO_CHECK_UPDATE] = enabled }

    /**
     * 本机经手过的频道明文密钥（`channel_no -> secret`）。
     *
     * 服务端只存密钥的 sha256，明文**只在创建 / 重置 / 接入那一次**经过客户端，所以"复制密钥"
     * 这个动作能给的只有本机记过的那份；在别的设备上建的频道这里就是查不到，只能提示去重置。
     * 值与私钥同一口径过 [SecretVault]；格式沿用 [SERVER_HISTORY] 的行式存储，一行一条 `号码\t密文`。
     */
    suspend fun channelSecrets(): Map<String, String> {
        val raw = store.data.first()[CHANNEL_SECRETS] ?: return emptyMap()
        return secretLines(raw).mapNotNull { (no, sealed) ->
            vault.open(sealed)?.let { no to it }
        }.toMap()
    }

    /** 空 secret 表示"把这条忘掉"（换发频道号之后旧号码的记档该跟着走）。 */
    suspend fun putChannelSecret(channelNo: String, secret: String) = store.edit { mut ->
        val kept = secretLines(mut[CHANNEL_SECRETS].orEmpty()).filter { it.first != channelNo }
        val next = if (secret.isEmpty()) kept else kept + (channelNo to vault.seal(secret))
        if (next.isEmpty()) mut.remove(CHANNEL_SECRETS)
        else mut[CHANNEL_SECRETS] = next.joinToString("\n") { (no, sealed) -> "$no\t$sealed" }
    }

    private fun secretLines(raw: String): List<Pair<String, String>> =
        raw.lineSequence().mapNotNull { line ->
            line.split('\t').takeIf { it.size == 2 && it[0].isNotEmpty() }?.let { it[0] to it[1] }
        }.toList()

    /** §8.4 D30 App 锁：默认关闭；库里只存 PIN 的 PBKDF2 验证串，明文 PIN 与密钥都过 Keystore 密封。 */
    suspend fun appLockEnabled(): Boolean = store.data.first()[APP_LOCK_ENABLED] ?: false

    suspend fun appLockPinHash(): String? = store.data.first()[APP_LOCK_PIN]?.let { vault.open(it) }

    suspend fun setAppLock(enabled: Boolean, pinHash: String?) = store.edit {
        it[APP_LOCK_ENABLED] = enabled
        if (pinHash != null) it[APP_LOCK_PIN] = vault.seal(pinHash) else it.remove(APP_LOCK_PIN)
    }

    /** §8.4 D30 生物识别解锁：在已设 PIN 的前提下额外允许指纹/面容，PIN 始终是兜底通道。 */
    suspend fun biometricEnabled(): Boolean = store.data.first()[BIOMETRIC_ENABLED] ?: false

    suspend fun setBiometricEnabled(enabled: Boolean) = store.edit {
        it[BIOMETRIC_ENABLED] = enabled
    }

    companion object {
        private val ENC_PRIV = stringPreferencesKey("enc_priv")
        private val SIGN_PRIV = stringPreferencesKey("sign_priv")
        private val DEVICE_ID = stringPreferencesKey("device_id")
        private val TOKEN = stringPreferencesKey("token")
        private val GROUP_ID = stringPreferencesKey("group_id")
        private val RECOVERY_CODE = stringPreferencesKey("recovery_code")
        private val SERVER_FP = stringPreferencesKey("server_fp")
        private val BASE_URL = stringPreferencesKey("base_url")
        private val LAST_SEQ = stringPreferencesKey("last_seq")
        private val SERVER_HISTORY = stringPreferencesKey("server_history")
        private val NOTIF_ENABLED = booleanPreferencesKey("notif_enabled")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        private val KEEPALIVE_GUIDED = booleanPreferencesKey("keepalive_guided")
        private val NOTIFY_RECOVERY_SHOWN = booleanPreferencesKey("notify_recovery_shown")
        private val APP_LOCK_PIN = stringPreferencesKey("app_lock_pin")
        private val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        private val QUIET_ENABLED = booleanPreferencesKey("quiet_enabled")
        private val QUIET_START = intPreferencesKey("quiet_start")
        private val QUIET_END = intPreferencesKey("quiet_end")
        private val POLLING_MODE = booleanPreferencesKey("polling_mode")
        private val AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
        private val FONT_SCALE = stringPreferencesKey("font_scale")
        private val SUMMARY_LINES = intPreferencesKey("summary_lines")
        private val CHANNEL_SECRETS = stringPreferencesKey("channel_secrets")

        /** 只有这几串算凭据，需要密封；设备号/分组/指纹/地址要留 plaintext 才好排查问题。 */
        private val SECRET_KEYS = listOf(ENC_PRIV, SIGN_PRIV, TOKEN, RECOVERY_CODE, APP_LOCK_PIN)
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("pb_identity")

data class Identity(
    val encPrivate: ByteArray,
    val signPrivate: ByteArray,
    val deviceId: String,
    val token: String,
    val groupId: String,
    val recoveryCode: String?,
    /** §4.3 TOFU：首连记下的服务端身份公钥指纹，之后每次 kx 都要对得上。 */
    val serverPubFp: String?,
    val baseUrl: String,
    val lastSeq: Long,
)

val Identity.encPublicKey: ByteArray get() = X25519.publicKeyOf(encPrivate)
val Identity.signPublicKey: ByteArray get() = Ed25519.fromPrivateSeed(signPrivate).publicKey()
