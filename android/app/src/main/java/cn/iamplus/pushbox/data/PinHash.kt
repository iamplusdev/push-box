package cn.iamplus.pushbox.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * D30 App 锁的口令验证器，落盘形状是 `pbkdf2$<迭代>$<盐b64>$<哈希b64>`。
 *
 * 原来那版是无盐单轮 SHA-256：4–6 位数字的空间只有 10⁴–10⁶，摸到那份文件等于当场枚举完；
 * 盐和迭代的作用就是把那次枚举从"毫秒"推到"每条口令都要重算一遍"。
 */
object PinHash {

    private const val PREFIX = "pbkdf2"
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun create(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        return listOf(PREFIX, ITERATIONS.toString(), encode(salt), encode(derive(pin, salt, ITERATIONS)))
            .joinToString("$")
    }

    /** 恒定时间比对；形状不对（旧格式或被改坏）一律算不匹配。 */
    fun matches(pin: String, stored: String): Boolean {
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { decode(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    /** 这串是不是这版之前那份单轮 SHA-256（64 个小写十六进制字符）。 */
    fun isLegacy(stored: String): Boolean =
        stored.length == 64 && stored.all { it in '0'..'9' || it in 'a'..'f' }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance(ALGORITHM)
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS))
            .encoded

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
