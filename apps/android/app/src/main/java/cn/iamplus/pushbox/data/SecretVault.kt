package cn.iamplus.pushbox.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * §4.2 端上密钥的落盘封条。私钥 / token / 恢复码在写进 DataStore 之前先过一遍
 * AndroidKeyStore 里那把**不可导出**的 AES-GCM 密钥，于是应用私有文件里只剩
 * `v1:<iv>:<密文>`；要还原就得在这台设备上跑本应用的代码，而不是把文件拷走慢慢解。
 *
 * 解不开时调用方按"没有身份"处理（load 返回 null → 回到首启重注册）：密钥绑死这台设备的
 * Keystore，卸载或清 Keystore 就没了，而恢复码那条路本来就是"重新注册一把新密钥"。
 */
class SecretVault {

    private val key: SecretKey by lazy { createOrLoadKey() }

    /** 加密一个值。IV 每次新生成，所以同一份明文两次封出来的串不一样。 */
    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plain.toByteArray())
        return PREFIX + encode(cipher.iv) + SEPARATOR + encode(ct)
    }

    /**
     * 解开一个值。没有 `v1:` 前缀的是这层加入之前落盘的明文，照原样认（调用方下次写回时补封）：
     * 那串本来就在盘上，拒绝它只会让老用户白丢身份，不会给攻击者多添一道坎。
     */
    fun open(stored: String): String? {
        if (!isSealed(stored)) return stored
        val parts = stored.removePrefix(PREFIX).split(SEPARATOR)
        if (parts.size != 2) return null
        val iv = runCatching { decode(parts[0]) }.getOrNull() ?: return null
        val ct = runCatching { decode(parts[1]) }.getOrNull() ?: return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return runCatching { String(cipher.doFinal(ct)) }.getOrNull()
    }

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    private companion object {
        const val KEY_ALIAS = "pushbox.identity.aes"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val SEPARATOR = ":"
        const val TAG_BITS = 128

        fun createOrLoadKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }

        fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
    }
}
