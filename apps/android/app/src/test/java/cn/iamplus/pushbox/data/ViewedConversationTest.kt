package cn.iamplus.pushbox.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "正在看"必须两个条件同时成立。只判会话，人按 Home 走了消息还会被当场读掉、通知一起哑；
 * 只判前台，在列表页也算读了。这里把三条判据钉住，顺带钉住换会话时旧页晚到的 close。
 */
class ViewedConversationTest {

    @Before
    fun reset() {
        ViewedConversation.open(RESET, RESET)
        ViewedConversation.close(RESET, RESET)
        ViewedConversation.setForeground(false)
    }

    @Test
    fun openConversationInForegroundCountsAsViewing() {
        ViewedConversation.setForeground(true)
        ViewedConversation.open("ch", "sender")
        assertTrue(ViewedConversation.isViewing("ch", "sender"))
    }

    @Test
    fun openButBackgroundDoesNotCount() {
        ViewedConversation.open("ch", "sender")
        ViewedConversation.setForeground(false)
        assertFalse(ViewedConversation.isViewing("ch", "sender"))
    }

    @Test
    fun foregroundOnAnotherConversationDoesNotCount() {
        ViewedConversation.setForeground(true)
        ViewedConversation.open("ch", "sender")
        assertFalse(ViewedConversation.isViewing("other", "sender"))
        assertFalse(ViewedConversation.isViewing("ch", "someone-else"))
    }

    @Test
    fun lateCloseOfThePreviousConversationKeepsTheNewOne() {
        ViewedConversation.setForeground(true)
        ViewedConversation.open("first", "sender")
        ViewedConversation.open("second", "sender")
        // 换会话时旧详情页的 onDispose 可能晚于新页的 open，不能把新的那条清掉
        ViewedConversation.close("first", "sender")
        assertTrue(ViewedConversation.isViewing("second", "sender"))
    }

    @Test
    fun closingLeavesNothingElseOpen() {
        ViewedConversation.setForeground(true)
        ViewedConversation.open("ch", "sender")
        ViewedConversation.close("ch", "sender")
        assertFalse(ViewedConversation.isViewing("ch", "sender"))
    }

    private companion object {
        const val RESET = "reset"
    }
}
