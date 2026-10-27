package cn.iamplus.pushbox.data

/**
 * "此刻是不是正盯着这条会话"的唯一事实来源，由界面写、由收消息的引擎读。
 *
 * 两件事必须同时成立才算"正在看"：
 * - 界面在这条会话上（[open] 由详情页的 DisposableEffect 写入）；
 * - App 在前台（[foreground] 跟随 ON_START / ON_STOP）。
 *
 * 只判会话不判前台，等于人已经按 Home 键走了、消息还在源源不断地被"当场读掉"，
 * 通知栏与红点一起哑掉；只判前台不判会话，等于在列表页也算读了。
 */
object ViewedConversation {

    @Volatile
    private var current: Pair<String, String>? = null

    @Volatile
    private var foreground = false

    fun open(channelNo: String, senderId: String) {
        current = channelNo to senderId
    }

    /** 只清"还是我自己"的那种：换会话时旧页的 onDispose 可能晚于新页的 open。 */
    fun close(channelNo: String, senderId: String) {
        if (current == channelNo to senderId) current = null
    }

    fun setForeground(value: Boolean) {
        foreground = value
    }

    fun isViewing(channelNo: String, senderId: String): Boolean =
        foreground && current == channelNo to senderId
}
