package cn.iamplus.pushbox.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.net.MessageFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 锁屏策略与静默时段：锁屏默认显示发送者+摘要，关掉退回"收到 1 条新消息"，
 * 会话私密始终 SECRET；静默时段走 pb_quiet（IMPORTANCE_LOW）而不是在正常渠道上压低 priority。
 */
@RunWith(AndroidJUnit4::class)
class NotifierPrivacyTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var app: App

    private val channelNo = "99999996"
    private val senderId = "privacy-a"
    private val quietChannelNo = "99999995"
    private val lowChannelNo = "99999994"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        app = App.get(context)
        app.notificationsEnabled.value = true
        app.lockScreenContent.value = true
        app.defaultImportance.value = "high"
        app.quietEnabled.value = false
        cleanup()
    }

    @After
    fun tearDown() {
        app.lockScreenContent.value = true
        app.defaultImportance.value = "high"
        app.quietEnabled.value = false
        cleanup()
    }

    @Test
    fun lockScreenShowsSenderAndSummaryByDefault() {
        Notifier(context).post(message("p-1", channelNo, senderId, summary = "服务器磁盘 92%"))

        val posted = await("p-1".hashCode())
        assertNotNull(posted)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted!!.notification.visibility)
        val public = posted.notification.publicVersion
        assertNotNull(public)
        assertEquals(senderId, public.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("服务器磁盘 92%",
            public.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test
    fun lockScreenContentOffFallsBackToHiddenPlaceholder() {
        app.lockScreenContent.value = false
        Notifier(context).post(message("p-2", channelNo, senderId, summary = "服务器磁盘 92%"))

        val posted = await("p-2".hashCode())
        assertNotNull(posted)
        val public = posted!!.notification.publicVersion
        assertNotNull(public)
        assertEquals(context.getString(cn.iamplus.pushbox.R.string.notif_hidden_text),
            public.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test
    fun privateConversationStaysSecret() {
        Notifier(context).post(message("p-3", channelNo, senderId, summary = "只给本人看"), lockPrivate = true)

        val posted = await("p-3".hashCode())
        assertNotNull(posted)
        assertEquals(Notification.VISIBILITY_SECRET, posted!!.notification.visibility)
        assertNull(posted.notification.publicVersion)
    }

    @Test
    fun quietHoursRouteToQuietChannel() {
        app.quietEnabled.value = true
        app.quietStart.value = 0
        app.quietEnd.value = 1440
        val notifier = Notifier(context)
        notifier.post(message("p-4", quietChannelNo, senderId, summary = "夜间消息"))

        val quiet = manager.getNotificationChannel(Notifier.QUIET_CHANNEL)
        assertNotNull(quiet)
        assertEquals(NotificationManager.IMPORTANCE_LOW, quiet!!.importance)
        val posted = await("p-4".hashCode())
        assertNotNull(posted)
        assertEquals(Notifier.QUIET_CHANNEL, posted!!.notification.channelId)

        // error 级别不受静默时段影响，仍走频道自己的渠道
        notifier.post(message("p-5", quietChannelNo, senderId, summary = "紧急", level = "error"))
        val urgent = await("p-5".hashCode())
        assertNotNull(urgent)
        assertEquals(Notifier.channelIdOf(quietChannelNo), urgent!!.notification.channelId)
    }

    @Test
    fun defaultImportanceAppliesToNewChannel() {
        app.defaultImportance.value = "low"
        Notifier(context).post(message("p-6", lowChannelNo, senderId, summary = "低档"))

        val channel = manager.getNotificationChannel(Notifier.channelIdOf(lowChannelNo))
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel!!.importance)
    }

    private fun message(msgId: String, channelNo: String, senderId: String,
        summary: String, level: String = "info"): MessageFrame =
        MessageFrame(
            seq = 1,
            msgId = msgId,
            channelNo = channelNo,
            senderId = senderId,
            senderName = null,
            senderAvatar = null,
            type = "text",
            title = null,
            summary = summary,
            content = summary,
            level = level,
            url = null,
            mediaStatus = null,
            media = emptyList(),
            notify = true,
            createdAt = System.currentTimeMillis(),
            truncated = false,
        )

    private fun await(id: Int, timeoutMs: Long = 2_000): StatusBarNotification? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            manager.activeNotifications.firstOrNull { it.id == id }?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    private fun cleanup() {
        val channelIds = setOf(
            Notifier.channelIdOf(channelNo),
            Notifier.channelIdOf(quietChannelNo),
            Notifier.channelIdOf(lowChannelNo),
            Notifier.QUIET_CHANNEL,
        )
        manager.activeNotifications
            .filter { it.notification.channelId in channelIds }
            .forEach { manager.cancel(it.id) }
        channelIds.forEach { manager.deleteNotificationChannel(it) }
    }
}
