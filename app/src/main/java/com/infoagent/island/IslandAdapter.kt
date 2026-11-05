package com.infoagent.island

import android.app.Notification
import android.app.PendingIntent
import android.content.Context

/** 岛能力位：后续接华为/荣耀/vivo/三星时按此登记 */
enum class IslandCapability { BUTTON, PROGRESS, PERSISTENT, OPEN_APP }

data class IslandAction(
    val label: String,
    val pendingIntent: PendingIntent,
)

/** 岛展示载荷（厂商无关）。key 仅用于调试/日志，不再单独发通知 */
data class IslandPayload(
    val key: String,
    val title: String,
    val content: String,
    val progress: Int? = null,
    val iconRes: Int? = null,
    val actions: List<IslandAction> = emptyList(),
    val deepLink: PendingIntent? = null,
) {
    fun normalized(): IslandPayload = copy(
        title = title.take(40),
        content = content.take(80),
        progress = progress?.coerceIn(0, 100),
        actions = actions.take(2),
    )
}

/**
 * E1：厂商适配器契约（装饰模式）。
 * 只往**业务已有的** Notification.Builder 上加 extras / 系统能力，**绝不自己 notify**，
 * 避免双通知与僵尸通知；任何异常由 Router 兜底为原始 builder。
 */
interface IslandAdapter {
    val id: String
    fun isAvailable(ctx: Context): Boolean

    /** 厂商匹配（与 isAvailable 一起判断，测试可注入 manufacturer） */
    fun matches(manufacturer: String): Boolean

    fun capabilities(): Set<IslandCapability>
    fun decorate(ctx: Context, b: Notification.Builder, payload: IslandPayload)
}