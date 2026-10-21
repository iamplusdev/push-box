package cn.iamplus.pushbox.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.iamplus.pushbox.App
import cn.iamplus.pushbox.MainActivity
import cn.iamplus.pushbox.R
import cn.iamplus.pushbox.net.MessageFrame
import cn.iamplus.pushbox.ui.AvatarCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * §7.3 通知：一个 (channel_no, sender) 一个 NotificationChannel，提醒强度按 level 分档；
 * 锁屏只显示"收到 1 条新消息"（VISIBILITY_PRIVATE + publicVersion，对应 D11）。
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system?.createNotificationChannelGroup(
            NotificationChannelGroup(CHANNEL_GROUP, context.getText(R.string.notif_group_name)))
        ensureServiceChannel()
    }

    fun post(message: MessageFrame, lockPrivate: Boolean = false) {
        // §7.3：补拉/重连的帧 notify=false，已有内容不该再弹一次（验收②"无重复"）
        if (!message.notify) return
        // "我的"页的通知总开关：关掉后消息照常入库，只是不弹通知
        if (!App.get(context).notificationsEnabled.value) return
        // §7.4 静默时段：error 级别仍可打扰，其余降为静默（不响铃、不横幅，仅在通知栏留痕）。
        val quiet = App.get(context).inQuietHours() && message.level != "error"
        val channel = ensureChannel(message.channelNo, message.senderId, message.displayName,
            message.level)
        val groupId = CONVERSATION_GROUP + channel.id
        val summaryId = groupId.hashCode()

        // §7.3 D11：带图消息用 BigPictureStyle；否则 MessagingStyle 堆叠同会话多条。
        val style = if (message.mediaCount > 0) {
            NotificationCompat.BigPictureStyle()
                .bigPicture(loadBigPictureBitmap(message.msgId))
                .setSummaryText(summaryOf(message))
        } else {
            NotificationCompat.MessagingStyle(context.getText(R.string.notif_self_name))
                .addMessage(summaryOf(message), message.createdAt, message.displayName)
        }

        val builder = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_stat_pushbox)
            .setStyle(style)
            .setContentTitle(message.displayName)
            .setContentText(summaryOf(message))
            .setLargeIcon(largeIconOf(message))
            .setWhen(message.createdAt)
            .setShowWhen(true)
            .setColor(accentColor(message.level))
            .setContentIntent(openConversationIntent(message))
            .setGroup(groupId)
            .setAutoCancel(true)
        // §7.3 会话级锁屏可见：SECRET 是整条通知连标题都不上锁屏，比 PRIVATE+publicVersion 更彻底；
        // 默认仍走 PRIVATE + "收到 1 条新消息"的降级副本（D11）。
        if (lockPrivate) {
            builder.setVisibility(Notification.VISIBILITY_SECRET)
        } else {
            builder.setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(lockScreenPlaceholder())
        }
        if (quiet) {
            builder.setPriority(Notification.PRIORITY_MIN).setOnlyAlertOnce(true)
        }
        val notification = builder.build()
        try {
            manager.notify(message.msgId.hashCode(), notification)
            // 更新会话摘要通知（堆叠效果）
            updateSummaryNotification(channel.id, groupId, summaryId, message)
        } catch (_: SecurityException) {
            // Android 13+ 未授予 POST_NOTIFICATIONS：消息照样入库，只是不出通知
        }
    }

    /**
     * §7.3 会话摘要通知：同组多条消息合并为一个"X 条新消息"的汇总条目，
     * 带"全部已读"快捷操作，点击仍进入该会话。
     */
    private fun updateSummaryNotification(channelId: String, groupId: String,
        summaryId: Int, latest: MessageFrame) {
        val existing = manager.activeNotifications.find { it.tag == null && it.id == summaryId }
        val count = (existing?.notification?.number ?: 0) + 1
        val inboxStyle = NotificationCompat.InboxStyle()
            .setSummaryText("$count 条新消息")
            .addLine("${latest.displayName}: ${summaryOf(latest)}")
        val readAllIntent = PendingIntent.getBroadcast(context, channelId.hashCode(),
            Intent(context, MarkAllReadReceiver::class.java).apply {
                putExtra(EXTRA_CHANNEL, latest.channelNo)
                putExtra(EXTRA_SENDER_ID, latest.senderId)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val summary = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_pushbox)
            .setStyle(inboxStyle)
            .setContentTitle(latest.displayName)
            .setContentText("$count 条新消息")
            .setNumber(count)
            .setGroup(groupId)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_stat_pushbox,
                context.getText(R.string.notif_action_mark_all_read), readAllIntent)
            .setContentIntent(openConversationIntent(latest))
            .apply {
                // §7.3 D11：摘要通知也要走锁屏降级，否则安全锁屏+隐藏敏感内容时仍会泄露正文
                setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(lockScreenPlaceholder())
            }
            .build()
        manager.notify(summaryId, summary)
    }

    /** 加载大图用于 BigPictureStyle：从缓存取，没有则返回 null（通知只显示文字摘要）。 */
    private fun loadBigPictureBitmap(msgId: String): android.graphics.Bitmap? =
        runCatching {
            cn.iamplus.pushbox.ui.ImageCache.loadSync(msgId, 0, thumb = false)
        }.getOrNull()

    /**
     * §7.3 大图标＝发送者头像，且只读 [AvatarCache] 里已有的那张：
     * 头像只是让通知更好认，不值得为它多等一次 RTT——消息落库到出通知的 1s 验收线
     * 不能被一张装饰用的图片吃掉。这个会话从没画过头像时这里就是 null，
     * 系统退回 smallIcon，等用户进过一次会话列表缓存自然就在了。
     */
    private fun largeIconOf(message: MessageFrame): android.graphics.Bitmap? =
        message.senderId.takeIf { it.isNotBlank() }?.let { senderId ->
            runCatching { AvatarCache.cached(AvatarCache.tokenOf(senderId)) }.getOrNull()
        }

    /**
     * §7.3 "全部已读"快捷操作：清该会话红点，并只撤掉**本会话组**的通知。
     * 早先这里调 cancelAll()，会把别的会话也一起抹掉——用户点了一条的"全部已读"
     * 却发现另外三条通知也消失了，而那些消息其实根本没读过。
     */
    class MarkAllReadReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val channelNo = intent.getStringExtra(EXTRA_CHANNEL) ?: return
            val senderId = intent.getStringExtra(EXTRA_SENDER_ID) ?: return
            val app = App.get(context)
            GlobalScope.launch(Dispatchers.IO) {
                app.dao.markConversationRead(channelNo, senderId)
            }
            Notifier(context).cancelConversation(channelNo, senderId)
        }
    }

    /**
     * 会话级渠道的 importance 只在创建时生效，之后由用户在系统设置里调整（§7.3）。
     * id 用 sender_id 而不是显示名作键：显示名会被"重命名会话"改掉，
     * 而 id 一旦漂移，"全部已读"就再也按不到当初那组通知，旧渠道成了撤不掉的孤儿。
     */
    private fun ensureChannel(channelNo: String, senderId: String, senderName: String,
        level: String): NotificationChannel {
        val system = context.getSystemService(NotificationManager::class.java)
        val id = channelIdOf(channelNo, senderId)
        system?.getNotificationChannel(id)?.let { return it }
        val importance = when (level) {
            "error" -> NotificationManager.IMPORTANCE_HIGH
            "warning" -> NotificationManager.IMPORTANCE_DEFAULT
            else -> NotificationManager.IMPORTANCE_LOW
        }
        val channel = NotificationChannel(id, senderName, importance).apply {
            group = CHANNEL_GROUP
            description = context.getString(R.string.notif_conversation_desc, senderName)
        }
        system?.createNotificationChannel(channel)
        return channel
    }

    private fun openConversationIntent(message: MessageFrame): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHANNEL, message.channelNo)
            // 会话身份是 (channel_no, sender_id)：这里曾经塞的是显示名，
            // 而 MainActivity 拿它去 dao 查会话，改名之后的会话永远查不到，点通知就停在列表页
            putExtra(EXTRA_SENDER_ID, message.senderId)
        }
        return PendingIntent.getActivity(context, message.msgId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 锁屏降级内容：只有"收到 1 条新消息"，不含任何正文（D11）。 */
    private fun lockScreenPlaceholder(): Notification =
        NotificationCompat.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pushbox)
            .setContentTitle(context.getText(R.string.notif_hidden_title))
            .setContentText(context.getText(R.string.notif_hidden_text))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()

    private fun summaryOf(message: MessageFrame): String =
        message.summary ?: message.title ?: message.content.orEmpty()

    private fun accentColor(level: String): Int = Color.parseColor(
        when (level) {
            "error" -> "#D93025"
            "warning" -> "#B06900"
            else -> "#07C160"
        })

    /**
     * §7.3 桌面图标角标：App 层每有未读变化就调这个入口，把未读数分发给各厂商 launcher。
     */
    fun updateBadge(count: Int) {
        BadgeHelper.update(context, count)
    }

    /**
     * §7.3 撤掉一个会话的全部通知：进了会话＝已经读过，通知栏再留着那条是在骗人，
     * 桌面角标也会停在通知里那个旧 `number` 上。原先只有"全部已读"这个快捷操作会撤，
     * 用户直接从列表点进会话时通知原样躺着。
     */
    fun cancelConversation(channelNo: String, senderId: String) {
        val groupName = CONVERSATION_GROUP + channelIdOf(channelNo, senderId)
        manager.activeNotifications
            .filter { it.notification.group == groupName }
            .forEach { manager.cancel(it.id) }
    }

    /** 常驻服务通知（§7.2）：IMPORTANCE_MIN、无声、onlyAlertOnce，尽量压低存在感。
     *  unreadCount 借 Notification.number 传给支持"读通知数字"的 launcher（三星 One UI 等）作角标。 */
    fun serviceNotification(connected: Boolean, unreadCount: Int = 0): Notification =
        NotificationCompat.Builder(context, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pushbox)
            .setContentTitle(context.getText(R.string.app_name))
            .setContentText(context.getText(
                if (connected) R.string.state_connected else R.string.state_disconnected))
            .setNumber(unreadCount)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun ensureServiceChannel() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(SERVICE_CHANNEL) != null) return
        // API 33+ 才支持 IMPORTANCE_NONE：完全不显示通知，只保留前台服务存活
        val importance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            NotificationManager.IMPORTANCE_NONE
        } else {
            NotificationManager.IMPORTANCE_MIN
        }
        system.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL,
            context.getText(R.string.notif_channel_service_name),
            importance).apply {
            group = CHANNEL_GROUP
            description = context.getText(R.string.notif_channel_service_desc).toString()
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
        })
    }

    companion object {
        const val SERVICE_CHANNEL = "pb_service"
        const val EXTRA_CHANNEL = "channel_no"
        /** 会话的另一半键：通知点进来与"全部已读"都要用它定位会话，不能用显示名（会被改名）。 */
        const val EXTRA_SENDER_ID = "sender_id"
        private const val CHANNEL_GROUP = "pb_group"
        private const val CONVERSATION_GROUP = "pb_conv_group_"

        /** 会话 → 系统通知渠道 id：post 侧建渠道与"全部已读"侧撤通知必须算出同一个值。 */
        fun channelIdOf(channelNo: String, senderId: String): String =
            "pb_conv_${channelNo}_${senderId.hashCode()}"
    }
}
