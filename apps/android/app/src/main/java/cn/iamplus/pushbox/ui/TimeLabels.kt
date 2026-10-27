package cn.iamplus.pushbox.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 会话里所有"某一刻是什么时候"的文案都从这里出，别再在界面里就地拼 SimpleDateFormat。
 *
 * 三处口径各不相同，但"哪天"的判定只有一套（今天 / 昨天 / 本年 / 更早）：
 * - 列表右上角：当天只给 HH:mm，别的日子给日子本身——一屏会话行挤满 timestamp 反而读不出新旧；
 * - 气泡下方：分节头已经写明哪一天，这里只补 HH:mm，不再重复一遍日期；
 * - 分节头：今天 / 昨天 / M月d日 / yyyy年M月d日。
 *
 * [now] 与 [zone] 都带默认值，只为了测试能钉住边界；界面侧不必传。
 */
private enum class DayTier { TODAY, YESTERDAY, THIS_YEAR, EARLIER }

/** 同一天的判定键（yyyyMMdd）：跨天分节、5 分钟分组都靠它，跨年时前四位天然区分。 */
internal fun dayKey(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        .format(DateTimeFormatter.BASIC_ISO_DATE)

internal fun dateSectionLabel(
    millis: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (millis <= 0L) return ""
    val day = localDate(millis, zone)
    return when (tierOf(day, localDate(now, zone))) {
        DayTier.TODAY -> "今天"
        DayTier.YESTERDAY -> "昨天"
        DayTier.THIS_YEAR -> day.format(monthDay)
        DayTier.EARLIER -> day.format(fullDate)
    }
}

internal fun listTimeLabel(
    millis: Long,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (millis <= 0L) return ""
    val day = localDate(millis, zone)
    return when (tierOf(day, localDate(now, zone))) {
        DayTier.TODAY -> timeOfDay(millis, zone)
        DayTier.YESTERDAY -> "昨天"
        DayTier.THIS_YEAR -> day.format(monthDay)
        DayTier.EARLIER -> day.format(fullDate)
    }
}

/** 气泡只在分节头之下出现，日期由分节头承担，这里再印一遍就是每屏四五个重复的"M月d日"。 */
internal fun bubbleTimeLabel(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (millis <= 0L) "" else timeOfDay(millis, zone)

/** 管理面板里的"最早消息 / 最后在线"要能对得上服务端日志，日期与时间都要完整。 */
internal fun dateTimeLabel(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (millis <= 0L) "" else Instant.ofEpochMilli(millis).atZone(zone).format(dateTime)

private fun localDate(millis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/**
 * 按日历日比较，不按毫秒差：`now - 24h` 在有夏令时的时区里回不到昨天，
 * 跨年那两天也会把 12 月 31 日算成"更早"而不是"昨天"。
 */
private fun tierOf(day: LocalDate, today: LocalDate): DayTier = when {
    day == today -> DayTier.TODAY
    day == today.minusDays(1) -> DayTier.YESTERDAY
    day.year == today.year -> DayTier.THIS_YEAR
    else -> DayTier.EARLIER
}

private fun timeOfDay(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).format(hourMinute)

// DateTimeFormatter 不可变，建一次即可；Locale 只影响数字形状，跟随系统
private val hourMinute = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val monthDay = DateTimeFormatter.ofPattern("M月d日", Locale.getDefault())
private val fullDate = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.getDefault())
private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())
