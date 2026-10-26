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
 * 正缓存按 10 分钟复核，且回到前台那一刻就先复核一次：过期只是"该回源问一次"，
 * 手上那张旧图仍然要能显示。
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
    fun cachedAvatarIsFreshForTenMinutes() {
        val file = File(context.cacheDir, "avatar-freshness-probe").apply { writeText("x") }
        val now = 1_800_000_000_000L
        file.setLastModified(now - 9 * 60 * 1000L)
        assertTrue(AvatarCache.isFresh(file, now, foregroundAt = 0L))
        file.setLastModified(now - 11 * 60 * 1000L)
        assertFalse(AvatarCache.isFresh(file, now, foregroundAt = 0L))
        file.delete()
    }

    @Test
    fun avatarFetchedBeforeForegroundNeedsRevalidation() {
        val file = File(context.cacheDir, "avatar-foreground-probe").apply { writeText("x") }
        val now = 1_800_000_000_000L
        file.setLastModified(now - 60 * 1000L)
        // 复核线在取回之前：这张图跨过了一次前台，必须重问一次
        assertFalse(AvatarCache.isFresh(file, now, foregroundAt = now - 30 * 1000L))
        // 取回在复核线之后：这一轮前台已经问过，按 TTL 算
        assertTrue(AvatarCache.isFresh(file, now, foregroundAt = now - 120 * 1000L))
        file.delete()
    }

    @Test
    fun onForegroundBumpsRevisionAtMostOncePerMinute() {
        val now = 1_900_000_000_000L
        val before = AvatarCache.revision.value

        AvatarCache.onForeground(now)
        assertEquals(before + 1, AvatarCache.revision.value)
        // 切出去瞄一眼通知又回来，不该再让整屏头像重跑
        AvatarCache.onForeground(now + 30_000L)
        assertEquals(before + 1, AvatarCache.revision.value)
        AvatarCache.onForeground(now + 61_000L)
        assertEquals(before + 2, AvatarCache.revision.value)
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
