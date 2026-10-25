package cn.iamplus.pushbox.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.iamplus.pushbox.data.IdentityStore
import cn.iamplus.pushbox.data.PushBoxDatabase
import cn.iamplus.pushbox.net.MessageFrame
import cn.iamplus.pushbox.notify.Notifier
import cn.iamplus.pushbox.ui.AvatarCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 落帧路径上的负缓存作废：帧里带 sender_avatar 才说明"服务端现在有这人的头像"，
 * 这一条必须真的把本机的 404 结论擦掉——只测 [AvatarCache.invalidate] 本身证明不了接线在。
 */
@RunWith(AndroidJUnit4::class)
class SyncEngineAvatarTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, PushBoxDatabase::class.java).build()

    @After
    fun tearDown() = db.close()

    @Test
    fun frameCarryingSenderAvatarClearsAbsentMarker() = runBlocking {
        val senderId = "sync-avatar-late"
        val marker = absentMarker(senderId)
        marker.writeText("absent")

        engine().ingestNow(frame(senderId, senderAvatar = "/api/v1/avatars/${AvatarCache.tokenOf(senderId)}"))

        assertFalse(marker.exists())
    }

    @Test
    fun frameWithoutSenderAvatarKeepsAbsentMarker() = runBlocking {
        val senderId = "sync-avatar-absent"
        val marker = absentMarker(senderId)
        marker.writeText("absent")

        engine().ingestNow(frame(senderId, senderAvatar = null))

        assertTrue(marker.exists())
    }

    private fun engine() = SyncEngine(db.dao(), IdentityStore(context), Notifier(context)) {
        error("本用例只投帧，不该联网")
    }

    private fun absentMarker(senderId: String) =
        AvatarCache.absentFile(context, AvatarCache.tokenOf(senderId))
            .apply { parentFile?.mkdirs(); delete() }

    private fun frame(senderId: String, senderAvatar: String?) = MessageFrame(
        seq = 1,
        msgId = "msg-$senderId-${senderAvatar.hashCode()}",
        channelNo = "99999990",
        senderId = senderId,
        senderName = "头像测试",
        senderAvatar = senderAvatar,
        type = "text",
        title = null,
        summary = "摘要",
        content = "正文",
        level = "info",
        url = null,
        mediaStatus = null,
        media = emptyList(),
        notify = false,
        createdAt = System.currentTimeMillis(),
        truncated = false,
    )
}
