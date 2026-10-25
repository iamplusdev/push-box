package cn.iamplus.pushbox.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 24h 负缓存的作废：帧里补来 sender_avatar 时删掉 404 标记（并推一格 revision 让占位重查），
 * 没有标记时不产生任何副作用——绝大多数消息都带头像，不能每条都刷新整屏。
 *
 * 正缓存按 24h 复核：过期只是"该回源问一次"，手上那张旧图仍然要能显示。
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

    @Test
    fun cachedAvatarIsFreshForOneDay() {
        val file = File(context.cacheDir, "avatar-freshness-probe").apply { writeText("x") }
        val now = 1_800_000_000_000L
        file.setLastModified(now - 23 * 60 * 60 * 1000L)
        assertTrue(AvatarCache.isFresh(file, now))
        file.setLastModified(now - 25 * 60 * 60 * 1000L)
        assertFalse(AvatarCache.isFresh(file, now))
        file.delete()
    }

    @Test
    fun staleAvatarWithFreshAbsentMarkerStillReturnsTheCachedImage() = runBlocking {
        val senderId = "avatar-stale-but-present"
        AvatarCache.put(senderId, ONE_PIXEL_PNG)
        val token = AvatarCache.tokenOf(senderId)
        AvatarCache.avatarFile(context, token)
            .setLastModified(System.currentTimeMillis() - 48 * 60 * 60 * 1000L)
        AvatarCache.absentFile(context, token).writeText(token)

        // 复核还没发生、手里的图虽然过期，也不能因为一枚 404 标记就变成占位
        assertNotNull(AvatarCache.resolve(senderId))
    }

    /** 1×1 的 PNG：只要求 [AvatarCache] 能解码，不参与任何断言比较。 */
    private companion object {
        val ONE_PIXEL_PNG: ByteArray = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            android.util.Base64.DEFAULT,
        )
    }
}
