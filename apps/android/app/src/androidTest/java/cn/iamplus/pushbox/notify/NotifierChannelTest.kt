package cn.iamplus.pushbox.notify

import android.Manifest
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 渠道粒度回归：同频道的发送者默认共用一条系统渠道，只有显式建过例外才单独走；
 * 通知分组按会话而不是渠道；旧模型的 pb_conv_* 在 Notifier 初始化时被清掉。
 */
@RunWith(AndroidJUnit4::class)
class NotifierChannelTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    private val channelNo = "99999999"
    private val senderA = "test-a"
    private val senderB = "test-b"

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
        val app = App.get(context)
        app.notificationsEnabled.value = true
        app.quietEnabled.value = false
        cleanup()
    }

    @After
    fun tearDown() {
        cleanup()
    }

    @Test
    fun sameChannelSendersShareOneSystemChannel() {
        val notifier = Notifier(context)
        notifier.post(message("m-a", senderA, 1))
        notifier.post(message("m-b", senderB, 2))

        val channel = manager.getNotificationChannel(Notifier.channelIdOf(channelNo))
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel!!.importance)

        val a = await("m-a".hashCode())
        val b = await("m-b".hashCode())
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(channel.id, a!!.notification.channelId)
        assertEquals(channel.id, b!!.notification.channelId)
        assertTrue(a.notification.group != b.notification.group)
        assertNull(manager.getNotificationChannel(Notifier.senderChannelIdOf(channelNo, senderA)))
    }

    @Test
    fun senderExceptionTakesOverOnlyThatSender() {
        val exceptionId = Notifier.senderChannelIdOf(channelNo, senderA)
        manager.createNotificationChannel(NotificationChannel(exceptionId, "A 例外",
            NotificationManager.IMPORTANCE_LOW))
        val notifier = Notifier(context)
        notifier.post(message("m-a2", senderA, 3))
        notifier.post(message("m-b2", senderB, 4))

        val a = await("m-a2".hashCode())
        val b = await("m-b2".hashCode())
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(exceptionId, a!!.notification.channelId)
        assertEquals(Notifier.channelIdOf(channelNo), b!!.notification.channelId)
    }

    @Test
    fun cancelConversationLeavesOtherSendersAlone() {
        val notifier = Notifier(context)
        notifier.post(message("m-a3", senderA, 5))
        notifier.post(message("m-b3", senderB, 6))
        notifier.cancelConversation(channelNo, senderA)

        assertNull(await("m-a3".hashCode(), timeoutMs = 300))
        assertNotNull(await("m-b3".hashCode()))
    }

    @Test
    fun legacySenderChannelIsDeletedOnNotifierInit() {
        val legacyId = "pb_conv_${channelNo}_${senderA.hashCode()}"
        manager.createNotificationChannel(NotificationChannel(legacyId, "旧渠道",
            NotificationManager.IMPORTANCE_LOW))
        assertNotNull(manager.getNotificationChannel(legacyId))
        Notifier(context)
        assertNull(manager.getNotificationChannel(legacyId))
    }

    private fun message(msgId: String, senderId: String, seq: Long): MessageFrame =
        MessageFrame(
            seq = seq,
            msgId = msgId,
            channelNo = channelNo,
            senderId = senderId,
            senderName = null,
            senderAvatar = null,
            type = "text",
            title = null,
            summary = "测试消息",
            content = "测试消息",
            level = "info",
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
        val channelId = Notifier.channelIdOf(channelNo)
        val senderPrefix = "pb_sender_${channelNo}_"
        manager.activeNotifications
            .filter {
                it.notification.channelId == channelId ||
                    it.notification.channelId.startsWith(senderPrefix)
            }
            .forEach { manager.cancel(it.id) }
        manager.notificationChannels
            .filter { it.id == channelId || it.id.startsWith(senderPrefix) }
            .forEach { manager.deleteNotificationChannel(it.id) }
        manager.notificationChannels
            .filter { it.id.startsWith("pb_conv_${channelNo}_") }
            .forEach { manager.deleteNotificationChannel(it.id) }
    }
}
