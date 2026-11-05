package com.infoagent.data.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 日界工具（§7/§10）：cycle_date/归档/简报 biz_date 一律用 'yyyy-MM-dd' 本地日，日界 00:00。
 * 简报窗口跨度（前日 22:00 起）由调度层以本工具的 dayRange 加减偏移计算。
 */
object DayBoundary {

    private val fmt: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    val zone: ZoneId get() = ZoneId.systemDefault()

    fun today(): String = LocalDate.now(zone).format(fmt)

    fun dayOf(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().format(fmt)

    /** [start, end) 本地日毫秒区间 */
    fun dayRange(day: String): LongArray {
        val date = LocalDate.parse(day, fmt)
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return longArrayOf(start, end)
    }

    fun shift(day: String, deltaDays: Long): String = LocalDate.parse(day, fmt).plusDays(deltaDays).format(fmt)

    fun isValid(day: String): Boolean = runCatching { LocalDate.parse(day, fmt) }.isSuccess
}