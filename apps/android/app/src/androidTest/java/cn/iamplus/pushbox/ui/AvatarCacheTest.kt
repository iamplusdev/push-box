package cn.iamplus.pushbox.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 24h 负缓存的作废：帧里补来 sender_avatar 时删掉 404 标记（并推一格 revision 让占位重查），
 * 没有标记时不产生任何副作用——绝大多数消息都带头像，不能每条都刷新整屏。
 */
@RunWith(AndroidJUnit4::class)
class AvatarCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun invalidateDropsAbsentMarkerAndBumpsRevision() {
        val senderId = "avatar-late-arrival"
        val marker = AvatarCache.absentFile(context, AvatarCache.tokenOf(senderId))
        marker.parentFile?.mkdirs()
        marker.writeText("absent")
        val before = AvatarCache.revision.value

        assertTrue(AvatarCache.invalidate(senderId))
        assertFalse(marker.exists())
        assertEquals(before + 1, AvatarCache.revision.value)
    }

    @Test
    fun invalidateWithoutMarkerIsNoOp() {
        val senderId = "avatar-never-fetched"
        AvatarCache.absentFile(context, AvatarCache.tokenOf(senderId)).delete()
        val before = AvatarCache.revision.value

        assertFalse(AvatarCache.invalidate(senderId))
        assertFalse(AvatarCache.invalidate(senderId))
        assertEquals(before, AvatarCache.revision.value)
    }
}
