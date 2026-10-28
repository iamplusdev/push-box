package cn.iamplus.pushbox.ui.screens

import cn.iamplus.pushbox.net.UpdateApi
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {

    private fun latest(version: String, apkUrl: String? = "https://example.test/a.apk") =
        UpdateApi.Latest(tag = "v$version", version = version, notes = "",
            apkUrl = apkUrl, apkName = "a.apk", apkSize = 1L)

    @Test
    fun updateCheckKindSeparatesMissingReleaseFromUpToDate() {
        // 发布页只有 Windows 包时 apkUrl 为空：这不是"已是最新"，得让用户知道发布没打包完
        assertEquals(UpdateCheckKind.NoRelease,
            updateCheckKind(latest("2.0.0", apkUrl = null), "1.0.0"))
        assertEquals(UpdateCheckKind.Newer, updateCheckKind(latest("2.0.0"), "1.0.0"))
        assertEquals(UpdateCheckKind.Latest, updateCheckKind(latest("1.0.0"), "1.0.0"))
        assertEquals(UpdateCheckKind.Latest, updateCheckKind(latest("0.9.0"), "1.0.0"))
    }

    @Test
    fun downloadPercentTreatsUnknownTotalAsZero() {
        assertEquals(0, downloadPercent(0L, 0L))
        assertEquals(0, downloadPercent(512L, 0L))
        assertEquals(50, downloadPercent(50L, 100L))
        assertEquals(100, downloadPercent(100L, 100L))
    }

    @Test
    fun installerAndSystemPanelStayOutOfTheViewModel() {
        val vm = sources().first { it.contains("class SettingsViewModel(") }
        assertFalse("ViewModel 里不能拉起安装器/系统设置面板：这些要 Activity 与系统回执",
            vm.contains("startActivity"))
        assertFalse(vm.contains("FileProvider"))
        assertFalse(vm.contains("canRequestPackageInstalls"))
    }

    @Test
    fun updateDialogLaunchesInstallBeforeItStopsComposing() {
        val dialog = region("UpdateConfirmDialog")
        val effect = dialog.indexOf("LaunchedEffect(apk)")
        val earlyReturn = dialog.indexOf("val info = latest ?: return")
        assertTrue("下载完成后 pendingUpdate 已清空，装包 effect 必须挂在早退之前，否则永远不执行",
            effect >= 0 && earlyReturn > effect)
        assertTrue(dialog.contains("startInstall(context, file)"))
        assertFalse("装包又挂回组合级 scope：旋转会把它取消在半路", dialog.contains("scope.launch"))
    }

    /** 工作目录可能是 app 模块（Gradle 默认），也可能是仓库根（IDE 直接跑），两处都试一遍。 */
    private fun sources(): List<String> {
        val root = listOf(
            File("src/main/java/cn/iamplus/pushbox"),
            File("app/src/main/java/cn/iamplus/pushbox"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到 UI 源码目录（工作目录 ${File(".").absolutePath}）")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()
    }

    /** 取 `fun <name>(...)` 到它自己那一列的 `}` 为止的整段源码（跨拆分后的文件找）。 */
    private fun region(name: String): String {
        for (src in sources()) {
            val match = Regex("""(?:private|internal) fun $name\(""").find(src) ?: continue
            val start = match.range.first
            val end = src.indexOf("\n}\n", start)
            require(end > start) { "$name 的结尾没找到" }
            return src.substring(start, end)
        }
        error("UI 源码里找不到 $name")
    }
}
