package com.infoagent.remind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** 通知渠道（§9）：简报 / 关键时间 / 自动备忘（岛装饰复用这三条，不再单开频道） */
object NotificationChannels {

    const val BRIEFING = "briefing_push"
    const val KEY_TIME = "key_time_reminder"
    const val AUTO_MEMO = "auto_memo"
    const val CAPTURE = "capture_alive"

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(BRIEFING, "简报推送", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "上午 / 下午 / 晚间简报"
                enableVibration(false)
                setShowBadge(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(KEY_TIME, "关键时间提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "备忘里的时间点提醒"
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(AUTO_MEMO, "AI 自动备忘", NotificationManager.IMPORTANCE_LOW).apply {
                description = "AI 从消息里整理出待办时提示"
            },
        )
        // 采集保活：常驻但不打扰（无声、折叠、优先级最低），用户可一键关
        nm.createNotificationChannel(
            NotificationChannel(CAPTURE, "消息采集保活", NotificationManager.IMPORTANCE_MIN).apply {
                description = "保持通知监听不被系统冻结，可随时关闭"
                setShowBadge(false)
                enableVibration(false)
            },
        )
    }
}