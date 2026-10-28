package cn.iamplus.pushbox.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「切换对话卡顿、长对话开屏自己滚一路」这一类问题在 JVM 单测里没有可观测量：
 * 没有真机就量不到 LazyListState 的 firstVisibleItemIndex，也不值得为三条断言引 Robolectric。
 * 这里钉的是源码里那两个约定——它们恰好是"顺手就会改回去"的那两处：
 *
 * - 整块屏幕挂在会话的两把 id 上（`key(...)`），换会话等于换一块干净的屏幕：
 *   上一条读到哪儿、下一条就开在哪儿，是这一类症状的前半段；
 * - 状态一律不许挂 `conversation` **这个值**：`ConversationRow` 是 data class，
 *   进详情页那一刻 `markConversationRead` 会让行内容变一次，挂在行上等于每开一个会话
 *   就把自己重置一遍——"首次落位"再跑一次、已读再报一次，这就是后半段；
 * - 开屏那一下用 scrollToItem（瞬移），不用 animateScrollToItem（那个协程挂在 rows.size 上，
 *   Room 每发一帧就打断重来，长会话看起来就是它在往上滚）。
 */
class ConversationStateSourceTest {

    /** 工作目录可能是 app 模块（Gradle 默认），也可能是仓库根（IDE 直接跑），两处都试一遍。 */
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
    fun theWholeScreenIsKeyedByTheConversationIdentity() {
        val shell = region("ConversationDetail")
        assertTrue("ConversationDetail 不再用 key(两把 id) 包一层：换会话时列表滚动位置会留在下一头上",
            shell.contains("key(conversation.channelNo, conversation.senderId)"))
        assertTrue("包进去的是独立的那一屏（ConversationScreen），不是原地改键",
            shell.contains("ConversationScreen(app, conversation, focusMsgId, onBack)"))
    }

    @Test
    fun noStateIsKeyedByTheConversationRowItself() {
        // 只禁"拿行本身当键"这一种写法：conversation.channelNo / conversation.lastSeq 是取值，
        // 不在打击范围内（外层 key 已经管住了"换会话"，这些值当键只会在行内容变更时误重置）
        val offenders = Regex(
            """\b(?:remember|rememberSaveable|LaunchedEffect|DisposableEffect)\(conversation\b(?![.\w])"""
        ).findAll(region("ConversationScreen")).map { it.value }.toList()
        assertTrue("详情页又拿会话行当键了：进这一屏会把未读清零，行内容当场变一次，" +
            "挂在行上等于自己重置自己（$offenders）", offenders.isEmpty())
    }

    @Test
    fun openingALongConversationLandsWithoutAnAnimation() {
        val screen = region("ConversationScreen")
        assertTrue("首次落位不再是 scrollToItem（瞬移）：这个 effect 挂在 rows.size 上，" +
            "动画会被 Room 的下一帧打断重来，长会话开屏就是「它自己在往上滚」",
            screen.contains("if (focusMsgId == null) listState.scrollToItem(rows.lastIndex)"))
        assertFalse("首次落位又改回了 animateScrollToItem",
            screen.contains("if (focusMsgId == null) listState.animateScrollToItem"))
        // 贴着底跟随新消息、点胶囊跳回页尾：这两处是用户期望的动画，不该被一起改掉
        assertTrue("贴着底时的跟随动画被顺手删了",
            screen.contains("nearBottom -> listState.animateScrollToItem(rows.lastIndex)"))
    }

    /** 取 `fun <name>(...)` 到它自己那一列的 `}` 为止的整段源码（跨拆分后的文件找）。 */
    private fun region(name: String): String {
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
