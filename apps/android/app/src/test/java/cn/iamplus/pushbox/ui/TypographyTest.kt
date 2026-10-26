package cn.iamplus.pushbox.ui

import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §9.2 的六档字号只有被断言钉住才不会漂。Material3 对没覆盖的槽位给的是基线值，
 * 而基线里的 24（headlineSmall）和 14（titleSmall / labelLarge）都不在档位里——
 * 这类漏档编译期不报，预览也不一定看得出来，本用例逐个槽位比字号与行高。
 *
 * 新增槽位时把那一行也加进 [all]，否则新槽位等于没被这条闸覆盖。
 */
class TypographyTest {

    private val all: List<Pair<String, TextStyle>> = listOf(
        "displayLarge" to PushBoxTypography.displayLarge,
        "displayMedium" to PushBoxTypography.displayMedium,
        "displaySmall" to PushBoxTypography.displaySmall,
        "headlineLarge" to PushBoxTypography.headlineLarge,
        "headlineMedium" to PushBoxTypography.headlineMedium,
        "headlineSmall" to PushBoxTypography.headlineSmall,
        "titleLarge" to PushBoxTypography.titleLarge,
        "titleMedium" to PushBoxTypography.titleMedium,
        "titleSmall" to PushBoxTypography.titleSmall,
        "bodyLarge" to PushBoxTypography.bodyLarge,
        "bodyMedium" to PushBoxTypography.bodyMedium,
        "bodySmall" to PushBoxTypography.bodySmall,
        "labelLarge" to PushBoxTypography.labelLarge,
        "labelMedium" to PushBoxTypography.labelMedium,
        "labelSmall" to PushBoxTypography.labelSmall,
    )

    private val lineHeights = mapOf(
        20f to 28f,
        18f to 25f,
        16f to 22f,
        15f to 23f,
        13f to 18f,
        12f to 16f,
    )

    @Test
    fun everySlotIsOnTheSixStepScaleAndCarriesItsLineHeight() {
        for ((name, style) in all) {
            assertTrue(
                "$name 的字号 ${style.fontSize.value} 不在 {12,13,15,16,18,20} 里",
                style.fontSize.value in lineHeights.keys,
            )
            assertTrue("$name 的行高没写成 sp，会落回字体默认的 ~1.2×", style.lineHeight.isSp)
        }
    }

    @Test
    fun lineHeightFollowsTheSpecTable() {
        for ((name, style) in all) {
            assertEquals(
                "$name（${style.fontSize.value}sp）的行高",
                lineHeights.getValue(style.fontSize.value),
                style.lineHeight.value,
                0f,
            )
        }
    }

    @Test
    fun theSlotsTheChatListUsesKeepTheirExactValues() {
        // 会话行 / 消息正文 / 摘要 / 时间戳：这四个数直接决定消息列表的观感，单独钉一份
        assertEquals(16f, PushBoxTypography.titleMedium.fontSize.value)
        assertEquals(22f, PushBoxTypography.titleMedium.lineHeight.value)
        assertEquals(15f, PushBoxTypography.bodyLarge.fontSize.value)
        assertEquals(23f, PushBoxTypography.bodyLarge.lineHeight.value)
        assertEquals(13f, PushBoxTypography.bodyMedium.fontSize.value)
        assertEquals(18f, PushBoxTypography.bodyMedium.lineHeight.value)
        assertEquals(12f, PushBoxTypography.labelSmall.fontSize.value)
        assertEquals(16f, PushBoxTypography.labelSmall.lineHeight.value)
    }
}
