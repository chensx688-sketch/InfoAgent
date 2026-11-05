package com.infoagent.remind

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.infoagent.capture.NotifyCaptureService
import java.util.concurrent.TimeUnit

/**
 * 采集看门狗：HyperOS/ColorOS 冻结进程后，监听会被系统解绑（设置里还在、但不收消息）。
 * 每 15 分钟检查一次「最近连接时间」，超过阈值就 requestRebind 自愈；断连时也会立即触发一次。
 */
class CaptureWatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val PERIODIC = "capture_watchdog_periodic"
        private const val ONESHOT = "capture_watchdog_once"
        private const val STALE_MS = 20 * 60_000L

        fun schedulePeriodic(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<CaptureWatchdogWorker>(15, TimeUnit.MINUTES).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            }
        }

        fun scheduleNow(context: Context) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<CaptureWatchdogWorker>()
                    .setInitialDelay(30, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, request)
            }
        }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val state = NotifyCaptureService.state(context)
        val enabled = com.infoagent.capture.NotificationAccess.isGranted(context)
        if (!enabled) return Result.success() // 用户关了权限，需要人手动开
        val connectedAt = state.getLong(NotifyCaptureService.KEY_CONNECTED_AT, 0L)
        val stale = connectedAt <= 0L || System.currentTimeMillis() - connectedAt > STALE_MS
        if (stale) {
            NotifyCaptureService.requestRebind(context)
        }
        return Result.success()
    }
}