package com.infoagent.ui.util

import com.infoagent.data.util.DayBoundary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 列表时间显示：今天 HH:mm / 昨天 / MM-dd / yyyy-MM-dd */
object TimeFormat {

    private val hm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val md: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")
    private val ymd: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun listTime(ts: Long, now: Long = System.currentTimeMillis()): String {
        if (ts <= 0) return ""
        val zone: ZoneId = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            date == today -> Instant.ofEpochMilli(ts).atZone(zone).format(hm)
            date == today.minusDays(1) -> "昨天"
            date.year == today.year -> date.format(md)
            else -> date.format(ymd)
        }
    }

    fun dayLabel(ts: Long): String {
        if (ts <= 0) return ""
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> date.format(md)
        }
    }

    fun clock(ts: Long): String =
        if (ts <= 0) "" else Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(hm)

    fun todayString(): String = DayBoundary.today()
}