package com.infoagent

import android.app.Application
import com.infoagent.remind.ArchiveWorker
import com.infoagent.remind.BriefingScheduler
import com.infoagent.remind.NotificationChannels

class InfoAgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // C9：冷启动只走一次异常兜底；每个子项各自 runCatching，避免一个失败拖垮其余
        runCatching { NotificationChannels.ensure(this) }
        runCatching { ArchiveWorker.schedule(this) }          // 每日 00:05 归档
        runCatching { BriefingScheduler.scheduleAll(this) }   // 10:00 / 13:30 / 22:00
        runCatching { BriefingScheduler.catchUp(this) }       // P1-3：补跑错过的时段
        runCatching { com.infoagent.remind.CaptureWatchdogWorker.schedulePeriodic(this) } // 监听自愈
        runCatching { com.infoagent.agent.MemoryWorker.schedule(this) } // 每晚 23:30 记忆提炼
        // 覆盖安装/强制停止后，系统可能保留授权但不重新绑定监听服务 → 启动时自愈一次
        runCatching {
            if (!com.infoagent.capture.NotifyCaptureService.isConnected) {
                com.infoagent.capture.NotifyCaptureService.requestRebind(this)
            }
        }
    }
}