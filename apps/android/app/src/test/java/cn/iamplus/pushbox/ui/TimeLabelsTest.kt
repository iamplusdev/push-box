package cn.iamplus.pushbox.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 时间文案的边界全在这里：跨午夜、跨年、跨 DST 的日子都只该由日历日决定，
 * 靠 `now - 24h` 之类减法会在有夏令时的时区里滑掉一天（旧实现就是这么写的）。
 */
class TimeLabelsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(text: String): Long =
        ZonedDateTime.parse(text).withZoneSameInstant(zone).toInstant().toEpochMilli()

    private val now = at("2026-10-27T10:00:00+08:00")

    @Test
    fun todayShowsClockInListAndOnlyClockInBubble() {
        val morning = at("2026-10-27T09:05:00+08:00")
        assertEquals("今天", dateSectionLabel(morning, now, zone))
        assertEquals("09:05", listTimeLabel(morning, now, zone))
        assertEquals("09:05", bubbleTimeLabel(morning, zone))
    }

    @Test
    fun midnightStillCountsAsToday() {
        val midnight = at("2026-10-27T00:00:00+08:00")
        assertEquals("今天", dateSectionLabel(midnight, now, zone))
        assertEquals("00:00", listTimeLabel(midnight, now, zone))
    }

    @Test
    fun yesterdayIsNamedNotDated() {
        val late = at("2026-10-26T23:59:00+08:00")
        assertEquals("昨天", dateSectionLabel(late, now, zone))
        assertEquals("昨天", listTimeLabel(late, now, zone))
        // 气泡上面的分节头已经写了"昨天"，这里只补时刻
        assertEquals("23:59", bubbleTimeLabel(late, zone))
    }

    @Test
    fun earlierThisYearShowsMonthAndDay() {
        val older = at("2026-10-20T08:30:00+08:00")
        assertEquals("10月20日", dateSectionLabel(older, now, zone))
        assertEquals("10月20日", listTimeLabel(older, now, zone))
    }

    @Test
    fun previousYearCarriesTheYear() {
        val older = at("2025-12-31T21:00:00+08:00")
        assertEquals("2025年12月31日", dateSectionLabel(older, now, zone))
        assertEquals("2025年12月31日", listTimeLabel(older, now, zone))
    }

    @Test
    fun newYearsEveIsYesterdayNotLastYear() {
        // 跨年的那两天：判定"昨天"要优先于"不是本年"，日历日比较天然做对
        val justAfterMidnight = at("2026-01-01T00:30:00+08:00")
        val eve = at("2025-12-31T23:59:00+08:00")
        assertEquals("昨天", dateSectionLabel(eve, justAfterMidnight, zone))
        assertEquals("昨天", listTimeLabel(eve, justAfterMidnight, zone))
    }

    @Test
    fun missingTimestampsRenderAsNothing() {
        assertEquals("", dateSectionLabel(0L, now, zone))
        assertEquals("", listTimeLabel(0L, now, zone))
        assertEquals("", bubbleTimeLabel(0L, zone))
        assertEquals("", dateTimeLabel(-1L, zone))
    }

    @Test
    fun adminPanelKeepsFullDateAndTime() {
        assertEquals("2026-10-26 23:59", dateTimeLabel(at("2026-10-26T23:59:00+08:00"), zone))
    }

    @Test
    fun dayKeySplitsAtCalendarMidnightAcrossYears() {
        assertEquals("20261027", dayKey(at("2026-10-27T00:00:00+08:00"), zone))
        assertNotEquals(
            dayKey(at("2026-10-26T23:59:59+08:00"), zone),
            dayKey(at("2026-10-27T00:00:00+08:00"), zone),
        )
        assertNotEquals(
            dayKey(at("2025-12-31T23:59:59+08:00"), zone),
            dayKey(at("2026-01-01T00:00:00+08:00"), zone),
        )
    }
}
