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
 * §7.3 通知：默认一个频道一个 NotificationChannel，同频道的所有发送者共用一份提醒设置；
 * 只有用户给某个发送者单独设过例外（pb_sender_*）时才走那条独立渠道。
 * 锁屏默认显示发送者 + 摘要；用户可关成"收到 1 条新消息"，会话级私密则连标题都不上锁屏（D11）。
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        val system = context.getSystemService(NotificationManager::class.java)
        system?.createNotificationChannelGroup(
            NotificationChannelGroup(CHANNEL_GROUP, context.getText(R.string.notif_group_name)))
        ensureServiceChannel()
        ensureQuietChannel()
        retireLegacyChannels(system)
    }

    /**
     * 旧模型按 (channel_no, sender_id) 建渠道（pb_conv_*）。渠道重要度建好后不能上调，
     * 留着只会让同频道的一堆发送者继续各占一条系统设置；启动时清掉，幂等。
     * 消息本身在库里不受影响，但通知栏里属于旧渠道的条目会随渠道一起消失。
     */
    private fun retireLegacyChannels(system: NotificationManager?) {
        if (system == null) return
        system.notificationChannels
            .filter { it.id.startsWith(LEGACY_SENDER_PREFIX) }
            .forEach { runCatching { system.deleteNotificationChannel(it.id) } }
    }

    fun post(message: MessageFrame, lockPrivate: Boolean = false) {
        // §7.3：补拉/重连的帧 notify=false，已有内容不该再弹一次（验收②"无重复"）
        if (!message.notify) return
        // "我的"页的通知总开关：关掉后消息照常入库，只是不弹通知
        if (!App.get(context).notificationsEnabled.value) return
        // §7.4 静默时段：error 级别仍可打扰，其余走 pb_quiet——渠道重要度高于一切，
        // 在 HIGH 渠道上挂 PRIORITY_MIN 依然会响会横幅，只有换一条 LOW 渠道才真的静下来。
        val quiet = App.get(context).inQuietHours() && message.level != "error"
        val channel = if (quiet) ensureQuietChannel() else channelFor(message)
        val groupId = groupIdOf(message.channelNo, message.senderId)
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
        // §7.3 锁屏可见：
        //  - 会话级「私密」永远 SECRET，连标题都不上锁屏；
        //  - 全局「锁屏显示发送者与摘要」开着时给一份只含发送者+摘要的 publicVersion，
        //    不用 PUBLIC 是因为后者会把大图等正文原样搬到锁屏；
        //  - 关掉时退回 D11 的"收到 1 条新消息"。
        val lockScreenContent = App.get(context).lockScreenContent.value
        if (lockPrivate) {
            builder.setVisibility(Notification.VISIBILITY_SECRET)
        } else {
            builder.setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    if (lockScreenContent) lockScreenContentOf(channel.id, message)
                    else lockScreenPlaceholder(channel.id))
        }
        val notification = builder.build()
        try {
            manager.notify(message.msgId.hashCode(), notification)
            // 更新会话摘要通知（堆叠效果）
            updateSummaryNotification(channel.id, groupId, summaryId, message, lockPrivate)
        } catch (_: SecurityException) {
            // Android 13+ 未授予 POST_NOTIFICATIONS：消息照样入库，只是不出通知
        }
    }

    /**
     * §7.3 会话摘要通知：同组多条消息合并为一个"X 条新消息"的汇总条目，
     * 带"全部已读"快捷操作，点击仍进入该会话。
     */
    private fun updateSummaryNotification(channelId: String, groupId: String,
        summaryId: Int, latest: MessageFrame, lockPrivate: Boolean) {
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
                // §7.3 摘要通知跟随同一条锁屏策略，否则安全锁屏+隐藏敏感内容时仍会泄露正文
                if (lockPrivate) {
                    setVisibility(Notification.VISIBILITY_SECRET)
                } else {
                    setVisibility(Notification.VISIBILITY_PRIVATE)
                    setPublicVersion(
                        if (App.get(context).lockScreenContent.value) {
                            lockScreenContentOf(channelId, latest)
                        } else {
                            lockScreenPlaceholder(channelId)
                        })
                }
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
     * 渠道选择：默认跟随频道；只有这个发送者建过例外渠道时才单独走。
     * 例外渠道由用户在会话菜单里显式创建，「恢复跟随频道」会把它删掉。
     */
    private fun channelFor(message: MessageFrame): NotificationChannel {
        val system = context.getSystemService(NotificationManager::class.java)
        system?.getNotificationChannel(senderChannelIdOf(message.channelNo, message.senderId))
            ?.let { return it }
        return ensureChannelChannel(message.channelNo)
    }

    /**
     * 频道级渠道：同一频道下所有发送者共用。重要度只在创建时生效，之后由用户在系统设置里调整（§7.3）。
     * 频道名要等「我的频道」加载后才有，先拿频道号占位；加载后由频道页统一改名。
     */
    private fun ensureChannelChannel(channelNo: String): NotificationChannel {
        val system = context.getSystemService(NotificationManager::class.java)
        val id = channelIdOf(channelNo)
        system?.getNotificationChannel(id)?.let { return it }
        val channel = NotificationChannel(id,
            context.getString(R.string.notif_channel_fallback_name, channelNo),
            defaultImportanceOf()).apply {
            group = CHANNEL_GROUP
            description = context.getString(R.string.notif_channel_channel_desc, channelNo)
        }
        system?.createNotificationChannel(channel)
        return channel
    }

    /** §7.3 全局默认提醒档：只在新频道第一次出通知时生效；建好之后由用户去系统设置里改。 */
    private fun defaultImportanceOf(): Int =
        when (App.get(context).defaultImportance.value) {
            "low" -> NotificationManager.IMPORTANCE_LOW
            "default" -> NotificationManager.IMPORTANCE_DEFAULT
            else -> NotificationManager.IMPORTANCE_HIGH
        }

    /**
     * §7.4 静默时段专用渠道：IMPORTANCE_LOW 才意味着不响铃、不悬浮。
     * 在正常渠道上改 priority 没用——通知渠道的重要度会盖过它。
     */
    private fun ensureQuietChannel(): NotificationChannel {
        val system = context.getSystemService(NotificationManager::class.java)
        system?.getNotificationChannel(QUIET_CHANNEL)?.let { return it }
        val channel = NotificationChannel(QUIET_CHANNEL,
            context.getText(R.string.notif_channel_quiet_name),
            NotificationManager.IMPORTANCE_LOW).apply {
            group = CHANNEL_GROUP
            description = context.getText(R.string.notif_channel_quiet_desc).toString()
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
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
    private fun lockScreenPlaceholder(channelId: String): Notification =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_pushbox)
            .setContentTitle(context.getText(R.string.notif_hidden_title))
            .setContentText(context.getText(R.string.notif_hidden_text))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()

    /**
     * 锁屏显示发送者 + 摘要：单独拼一份纯文字副本，而不是直接把主通知设成 PUBLIC——
     * PUBLIC 会把 BigPicture 的原图一起送到锁屏上，而用户要的只是"谁说了什么"。
     * 渠道沿用消息自己的那条：publicVersion 挂在 pb_service（IMPORTANCE_NONE）上根本不会显示。
     */
    private fun lockScreenContentOf(channelId: String, message: MessageFrame): Notification =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_pushbox)
            .setContentTitle(message.displayName)
            .setContentText(summaryOf(message))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
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
        val groupName = groupIdOf(channelNo, senderId)
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
        /** 静默时段专用渠道：IMPORTANCE_LOW，不响不横幅。 */
        const val QUIET_CHANNEL = "pb_quiet"
        const val EXTRA_CHANNEL = "channel_no"
        /** 会话的另一半键：通知点进来与"全部已读"都要用它定位会话，不能用显示名（会被改名）。 */
        const val EXTRA_SENDER_ID = "sender_id"
        private const val CHANNEL_GROUP = "pb_group"
        private const val LEGACY_SENDER_PREFIX = "pb_conv_"
        private const val CHANNEL_PREFIX = "pb_ch_"
        private const val SENDER_PREFIX = "pb_sender_"
        private const val CONVERSATION_GROUP = "pb_conv_group_"

        /** 频道级系统渠道 id：同频道的所有发送者默认共用这一条。 */
        fun channelIdOf(channelNo: String): String = "$CHANNEL_PREFIX$channelNo"

        /** 发送者例外渠道 id：只有用户主动给某个发送者单独设置时才创建。 */
        fun senderChannelIdOf(channelNo: String, senderId: String): String =
            "$SENDER_PREFIX${channelNo}_${senderId.hashCode()}"

        /** 通知分组按会话而不是渠道：同频道不同发送者不能共用组，否则"全部已读"会串。 */
        private fun groupIdOf(channelNo: String, senderId: String): String =
            "$CONVERSATION_GROUP${channelNo}_${senderId.hashCode()}"
    }
}
