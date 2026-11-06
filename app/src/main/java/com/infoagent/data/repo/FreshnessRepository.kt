package com.infoagent.data.repo

import com.infoagent.capture.NotificationAccess
import com.infoagent.capture.NotifyCaptureService
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.util.DayBoundary

/**
 * 数据新鲜度 / 覆盖率（回答「为什么简报没跟上最新消息」）。
 *
 * 简报只能看到「已经采到的通知」，采不到的原因通常只有三种，用户必须能一眼分辨：
 * 1) 通知读取权限没开 / 服务被系统杀了 → [listenerOn] false 或 [connected] false
 * 2) 微信压根没发通知（后台被杀、免打扰）→ 有权限但 [minutesSinceLast] 很大
 * 3) 有数据但简报窗口挑错了 → 看片段自带的区间与「新增 N」
 *
 * 只在本地读库，不发网络请求，不花钱。
 */
data class Freshness(
    val now: Long,
    /** 最近一条被采到的消息时间（null=一条都没有） */
    val lastMessageAt: Long?,
    /** 今天（业务日界）采到多少条 */
    val todayCount: Int,
    /** 系统里通知读取权限是否还在 */
    val listenerOn: Boolean,
    /** 服务当前是否真的绑定着（重装/被冻结时权限在、绑定没了） */
    val connected: Boolean,
    /** 最近一次成功入库时间 */
    val lastIngestAt: Long?,
) {
    /** 距最近一条消息多少分钟 */
    val minutesSinceLast: Int?
        get() = lastMessageAt?.let { ((now - it).coerceAtLeast(0L) / 60_000L).toInt() }

    /** 明显不新鲜：超过 2 小时没有任何新消息 */
    val stale: Boolean
        get() = lastMessageAt == null || (minutesSinceLast ?: 0) >= 120

    /** 一句话结论（直接显示在简报顶部） */
    fun summary(): String = when {
        !listenerOn -> "通知读取未授权：现在采不到任何新消息"
        !connected -> "采集服务未连接：权限在但没绑定，点一下重新连接"
        lastMessageAt == null -> "今天还没有采到消息"
        todayCount == 0 -> "今天还没有新消息"
        else -> {
            val m = minutesSinceLast ?: 0
            val ago = when {
                m < 1 -> "刚刚"
                m < 60 -> "${m} 分钟前"
                else -> "${m / 60} 小时前"
            }
            if (stale) "最近一条消息在 $ago（超过 2 小时没新消息）" else "最近一条消息在 $ago"
        }
    }
}

class FreshnessRepository(
    private val db: InfoAgentDatabase,
    private val context: android.content.Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun load(bizDate: String = DayBoundary.today()): Freshness {
        val now = clock()
        val startOfDay = runCatching {
            java.time.LocalDate.parse(bizDate)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(now)
        val last = runCatching { db.messageDao().maxTs() }.getOrNull()
        val today = runCatching {
            db.messageDao().countBetween(startOfDay, startOfDay + 86_400_000L)
        }.getOrDefault(0)
        val prefs = NotifyCaptureService.state(context)
        val ingest = prefs.getLong(NotifyCaptureService.KEY_LAST_INGEST_AT, 0L)
        return Freshness(
            now = now,
            lastMessageAt = last?.takeIf { it > 0 },
            todayCount = today,
            listenerOn = runCatching { NotificationAccess.isGranted(context) }.getOrDefault(false),
            connected = NotifyCaptureService.isConnected,
            lastIngestAt = ingest.takeIf { it > 0 },
        )
    }
}