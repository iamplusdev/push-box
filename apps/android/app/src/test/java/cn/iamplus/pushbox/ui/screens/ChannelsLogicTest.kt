package cn.iamplus.pushbox.ui.screens

import cn.iamplus.pushbox.net.PushBoxApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelsLogicTest {

    private fun channel(
        maxLevel: String = "info",
        maxImages: Int = 9,
        rateLimit: Int = 60,
        maxBodyBytes: Int = 8192,
        dailyMsgLimit: Int = 1000,
        dailyImageLimit: Int = 100,
        dailyBytesLimit: Long = 10_000_000L,
        strictAuth: Boolean = false,
        muted: Boolean = false,
        senderWhitelistMode: Boolean = false,
    ) = PushBoxApi.ChannelInfo(
        channelNo = "100001", name = "chan", maxLevel = maxLevel,
        maxImages = maxImages, rateLimit = rateLimit, maxBodyBytes = maxBodyBytes,
        strictAuth = strictAuth, muted = muted, dailyMsgLimit = dailyMsgLimit,
        dailyImageLimit = dailyImageLimit, dailyBytesLimit = dailyBytesLimit,
        senderWhitelistMode = senderWhitelistMode, subscribed = true,
    )

    private fun draft(
        maxLevel: String = "info",
        maxImages: String = "9",
        rateLimit: String = "60",
        bodyBytes: String = "8192",
        dailyMsg: String = "1000",
        dailyImage: String = "100",
        dailyBytes: String = "10000000",
        strictAuth: Boolean = false,
        muted: Boolean = false,
        whitelist: Boolean = false,
    ) = GovernanceDraft(maxLevel, maxImages, rateLimit, bodyBytes, dailyMsg, dailyImage,
        dailyBytes, strictAuth, muted, whitelist)

    @Test
    fun channelNameTrimsFallsBackAndClampsTo24() {
        assertEquals("ops", channelNameOrDefault("  ops  ", "Pixel"))
        assertEquals("Pixel", channelNameOrDefault("   ", "Pixel"))
        assertEquals("Pixel", channelNameOrDefault("", "Pixel"))
        val long = "x".repeat(30)
        assertEquals(24, channelNameOrDefault(long, "Pixel").length)
        assertEquals(long.take(24), channelNameOrDefault(long, "Pixel"))
    }

    @Test
    fun untouchedDraftSendsNothing() {
        val patch = governancePatch(draft(), channel())
        assertNull(patch.maxLevel)
        assertNull(patch.maxImages)
        assertNull(patch.rateLimit)
        assertNull(patch.maxBodyBytes)
        assertNull(patch.dailyMsgLimit)
        assertNull(patch.dailyImageLimit)
        assertNull(patch.dailyBytesLimit)
        assertNull(patch.strictAuth)
        assertNull(patch.muted)
        assertNull(patch.senderWhitelistMode)
    }

    @Test
    fun onlyEditedFieldsCarryTheirValues() {
        val patch = governancePatch(
            draft(maxLevel = "warn", strictAuth = true, dailyBytes = "20000000"),
            channel(),
        )
        assertEquals("warn", patch.maxLevel)
        assertEquals(true, patch.strictAuth)
        assertEquals(20_000_000L, patch.dailyBytesLimit)
        assertNull(patch.maxImages)
        assertNull(patch.rateLimit)
        assertNull(patch.muted)
    }

    @Test
    fun blankOrNonNumericIsTreatedAsNotSubmitted() {
        val patch = governancePatch(
            draft(maxImages = "", rateLimit = "abc", bodyBytes = " ", dailyMsg = "", dailyImage = "x"),
            channel(),
        )
        assertNull(patch.maxImages)
        assertNull(patch.rateLimit)
        assertNull(patch.maxBodyBytes)
        assertNull(patch.dailyMsgLimit)
        assertNull(patch.dailyImageLimit)
    }

    @Test
    fun numericHelpersCompareAgainstCurrentValue() {
        assertNull(changedInt("60", 60))
        assertEquals(61, changedInt("61", 60))
        assertNull(changedInt("", 60))
        assertNull(changedInt(" 61 ", 60))
        assertNull(changedLong("10000000", 10_000_000L))
        assertEquals(10_000_001L, changedLong("10000001", 10_000_000L))
        assertNull(changedLong("nope", 10_000_000L))
    }
}
