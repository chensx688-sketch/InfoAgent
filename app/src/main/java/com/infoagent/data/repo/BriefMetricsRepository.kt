package com.infoagent.data.repo

import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.MessageDao
import com.infoagent.data.util.DayBoundary

/** 某小时的消息量（0–23） */
data class HourPoint(val hour: Int, val count: Int)

/** 某会话当天的消息量（用于会话热度条） */
data class PeerVolume(val peer: String, val count: Int)

/**
 * 简报可视化用的「真实数据」。
 *
 * 设计原则（§8.3 可视化）：图表只画**数据库里真有的事实**（消息量、峰值、完成率），
 * 语义（要点/结论）来自模型产出，两者不混在一张图里，避免模型数字幻觉。
 */
data class DayMetrics(
    val date: String,
    /** 0..23 补齐，缺失小时为 0 */
    val todayHours: List<HourPoint>,
    val yesterdayHours: List<HourPoint>,
    val todayTotal: Int,
    val yesterdayTotal: Int,
    /** Top N 会话（按条数降序） */
    val topPeers: List<PeerVolume>,
    val dailyDone: Int,
    val dailyTotal: Int,
    val tempCount: Int,
) {
    val peak: HourPoint? get() = todayHours.maxByOrNull { it.count }?.takeIf { it.count > 0 }

    val delta: Int get() = todayTotal - yesterdayTotal

    val completion: Float
        get() = if (dailyTotal <= 0) 0f else dailyDone.toFloat() / dailyTotal.toFloat()

    /** 上午 / 下午 / 晚间三段的消息分布，给「一天节奏」用 */
    fun phase(hour: Int): Int = when (hour) {
        in 5..11 -> 0
        in 12..17 -> 1
        else -> 2
    }

    val phaseCounts: List<Int>
        get() {
            val acc = intArrayOf(0, 0, 0)
            todayHours.forEach { acc[phase(it.hour)] += it.count }
            return acc.toList()
        }
}

class BriefMetricsRepository(private val db: InfoAgentDatabase) {

    suspend fun dayMetrics(date: String): DayMetrics {
        val range = DayBoundary.dayRange(date)
        val prevRange = DayBoundary.dayRange(DayBoundary.shift(date, -1))

        val todayHours = runCatching { db.messageDao().hourlyCounts(range[0], range[1]) }
            .getOrDefault(emptyList())
            .padHours()
        val yesterdayHours = runCatching { db.messageDao().hourlyCounts(prevRange[0], prevRange[1]) }
            .getOrDefault(emptyList())
            .padHours()

        val peers = runCatching { db.messageDao().countsByPeerBetween(range[0], range[1], TOP_PEERS) }
            .getOrDefault(emptyList())
            .map { PeerVolume(it.peerId, it.cnt.toInt()) }

        val daily = runCatching { db.memoDao().list(date, "DAILY") }.getOrDefault(emptyList())
        val temp = runCatching { db.memoDao().list(date, "TEMP") }.getOrDefault(emptyList())

        return DayMetrics(
            date = date,
            todayHours = todayHours,
            yesterdayHours = yesterdayHours,
            todayTotal = todayHours.sumOf { it.count },
            yesterdayTotal = yesterdayHours.sumOf { it.count },
            topPeers = peers,
            dailyDone = daily.count { it.done == 1L },
            dailyTotal = daily.size,
            tempCount = temp.size,
        )
    }

    private fun List<MessageDao.HourCount>.padHours(): List<HourPoint> {
        val map = associate { it.hour to it.cnt.toInt() }
        return (0..23).map { HourPoint(it, map[it] ?: 0) }
    }

    private companion object {
        const val TOP_PEERS = 6
    }
}