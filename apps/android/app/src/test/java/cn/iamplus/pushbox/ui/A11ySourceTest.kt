package cn.iamplus.pushbox.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍这件事在 JVM 单测里没有运行时可观测量：没有真机就取不到 semantics 树，
 * 也不值得为四条断言引 Robolectric。这里退一步钉源码里的三个约定——
 * 它们恰恰是"改界面时最容易顺手删掉"的那几行：
 *
 * - 返回箭头必须有读屏文案（IconButton 里 contentDescription = null 等于一个不发声的按钮）；
 * - 底栏是 Tab 语义而不是按钮（selectable + Role.Tab），读屏才报"已选中"；
 * - 未读红点要报"3 条未读"，否则合并节点里只剩一个光秃秃的数字。
 */
class A11ySourceTest {

    private val strings: String = sourceOf("src/main/res/values/strings.xml")

    /** 工作目录可能是 app 模块（Gradle 默认），也可能是仓库根（IDE 直接跑），两处都试一遍。 */
    private fun sourceOf(relative: String): String {
        val file = listOf(File(relative), File("app", relative)).firstOrNull { it.isFile }
            ?: error("找不到 $relative（工作目录 ${File(".").absolutePath}）")
        return file.readText()
    }

    /** UI 代码已拆到 MainActivity.kt 与 ui/ 下多个文件，断言要么扫全部，要么定位到函数所在文件。 */
    private fun uiSources(): List<String> {
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

    @Test
    fun backArrowsAreAnnounced() {
        assertFalse(
            "返回箭头又变回了 contentDescription = null，读屏点上去只会说\"按钮\"",
            uiSources().any { it.contains("ArrowBack, contentDescription = null") },
        )
        assertTrue("缺少返回键文案", strings.contains("""<string name="action_back">"""))
    }

    @Test
    fun bottomBarIsATabSetNotThreeButtons() {
        val bar = functionBody("PushBoxBottomBar")
        // 连着写成一整句再比：只比 "Role.Tab" 会被上面注释里的同名文字蒙过去
        assertTrue("底栏没有 selectable(selected = …, role = Role.Tab)，读屏报不出选中态",
            bar.contains("selectable(selected = active, role = Role.Tab"))
        assertFalse("底栏又用回了 clickable：选中态对读屏不可见", bar.contains(".clickable { onSelect"))
        assertTrue("底栏每一格应带读屏标签", strings.contains("""<string name="tab_messages">"""))
    }

    @Test
    fun unreadBadgeSaysHowMany() {
        val row = functionBody("ConversationRowView")
        assertTrue("未读红点没有读屏文案，合并语义里只剩一个数字",
            row.contains("R.string.conv_unread_count"))
        assertTrue("红点上的语义修饰被删了", row.contains(".semantics {"))
        assertTrue("缺少红点文案资源", strings.contains("""<string name="conv_unread_count">"""))
    }

    @Test
    fun unreadBadgeColourKeepsWhiteTextReadable() {
        // 白字压在 F5222D / FF4D4F 上分别只有 4.1:1 与 3.3:1，12sp 的字不够 AA。
        // 只有这两个枚举值能被换回来，这条断言才真的挡得住。
        val theme = sourceOf("src/main/java/cn/iamplus/pushbox/ui/Theme.kt")
        assertFalse("亮色未读红又调回了低对比度", theme.contains("unreadBadge = Color(0xFFF5222D)"))
        assertFalse("深色未读红又调回了低对比度", theme.contains("unreadBadge = Color(0xFFFF4D4F)"))
    }

    private fun functionBody(name: String): String {
        for (src in uiSources()) {
            val match = Regex("""(?:private|internal) fun $name\(""").find(src) ?: continue
            val start = match.range.first
            val end = src.indexOf("\n}\n", start)
            require(end > start) { "$name 的结尾没找到" }
            return src.substring(start, end)
        }
        error("UI 源码里找不到 $name")
    }
}
