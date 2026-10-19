package cn.iamplus.pushbox.net

import android.content.Context
import android.os.Build
import dev.pushbox.core.protocol.Json
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GitHub Releases 版本更新检查（自托管 / 直装分发用）。
 *
 * 这是与业务服务器 [PushBoxApi] 完全无关的第三条网络出口：明文公开 JSON、不需要 device token、
 * 不签名、也不是密封盒——所以绝不能复用 PushBoxApi（它强制 unseal），这里只用一个带 UA 的裸 GET。
 * api.github.com 未认证限流 60 次/小时/IP，检查必须低频（启动一次 + 手点），不要放进 15min 轮询。
 */
object UpdateApi {

    private const val OWNER = "iamplusdev"
    private const val REPO = "push-box"
    private const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    data class Latest(
        val tag: String,
        val version: String,
        val notes: String,
        val apkUrl: String?,
        val apkName: String,
        val apkSize: Long,
    )

    data class Current(val versionName: String, val versionCode: Long)

    /** 运行时版本只认 packageInfo：写进 strings.xml 的那份迟早和真实构建号对不上。 */
    fun currentVersion(context: Context): Current {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION") pi.versionCode.toLong()
        }
        return Current(pi.versionName ?: "0.0.0", code)
    }

    suspend fun check(client: OkHttpClient): Latest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST_URL)
            .header("User-Agent", "PushBox-Android")
            .header("Accept", "application/vnd.github+json")
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("更新检查失败：HTTP ${response.code}")
            }
            val text = response.body?.string() ?: throw java.io.IOException("更新检查：空响应")
            val root = Json.MAPPER.readTree(text)
            val tag = root.path("tag_name").asText("")
            val version = tag.removePrefix("v")
            val notes = root.path("body").asText("")
            val assets = root.path("assets")
            // 只认 CI 产的签名包 pushbox-release-*。不再退回 debug 包，也不"随便挑一个 .apk"：
            // 装上签名不同的包，下一次升级就是 INSTALL_FAILED_UPDATE_INCOMPATIBLE，
            // 用户只能卸载重装、本地历史全丢——而这一步当场看不出任何问题。
            // 挑不到就留 apkUrl=null，界面按"有新版本但没有可安装的资产"提示。
            var chosen: com.fasterxml.jackson.databind.JsonNode? = null
            if (assets.isArray) {
                for (a in assets) {
                    if (a.path("name").asText("").startsWith("pushbox-release-")) {
                        chosen = a
                        break
                    }
                }
            }
            val pick = chosen
            Latest(
                tag = tag,
                version = version,
                notes = notes,
                apkUrl = pick?.path("browser_download_url")?.asText(null),
                apkName = pick?.path("name")?.asText("pushbox-update.apk") ?: "pushbox-update.apk",
                apkSize = pick?.path("size")?.asLong(0L) ?: 0L,
            )
        }
    }

    /**
     * 纯数字段比较（每段取前导数字，容忍 "1.2.0-beta" 与尾随缺失段）。
     * >0 表示 latest 比 current 新。versionCode 暂不参与（用户拍板：检测用 versionName/tag）。
     */
    fun isNewer(latestVersion: String, currentVersion: String): Boolean =
        compare(latestVersion, currentVersion) > 0

    private fun compare(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").split('.')
        val pb = b.trim().removePrefix("v").split('.')
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val na = leadingInt(pa.getOrElse(i) { "" })
            val nb = leadingInt(pb.getOrElse(i) { "" })
            if (na != nb) return if (na > nb) 1 else -1
        }
        return 0
    }

    private fun leadingInt(s: String): Int {
        val digits = s.takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: 0
    }

    /**
     * 流式下载 APK 到 [dest]。用一个关掉整调用超时的客户端（业务 app.http 的 callTimeout=30s 会掐断大包）。
     * newBuilder 复用连接池/线程，只覆盖超时。
     */
    suspend fun download(baseClient: OkHttpClient, url: String, dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit) = withContext(Dispatchers.IO) {
        val client = baseClient.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(url)
            .header("User-Agent", "PushBox-Android").build()
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".tmp")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("下载失败：HTTP ${response.code}")
            val total = response.body?.contentLength() ?: -1L
            response.body?.byteStream()?.use { input ->
                temp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        output.write(buf, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            } ?: throw java.io.IOException("下载失败：空响应体")
        }
        // 先写临时再替换：中途被杀不会留下半个 apk（对齐 MediaCache 的做法）
        if (dest.exists()) dest.delete()
        temp.renameTo(dest) || throw java.io.IOException("下载失败：无法落位 $dest")
        Unit
    }
}
