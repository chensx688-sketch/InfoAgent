package com.infoagent.remind

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.repo.MemoRepository
import com.infoagent.data.util.DayBoundary
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 每日 00:05 归档 Worker（§10 日界/归档）。
 * 阶段 8 会在此追加「先检查晚间简报是否完成（D52）」的协调逻辑。
 */
class ArchiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = InfoAgentDatabase.get(applicationContext)
        return runCatching {
            MemoRepository(db).archiveBefore(DayBoundary.today())
            // B1：顺手清理过期的回收站条目
            db.memoryDao().purgeExpiredRecycle(System.currentTimeMillis())
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val UNIQUE = "daily_archive"

        /** 注册每日 00:05 的归档任务（幂等，可多次调用；WorkManager 未初始化时静默跳过） */
        fun schedule(context: Context) {
            runCatching {
                val now = ZonedDateTime.now()
                var next = now.with(LocalTime.of(0, 5))
                if (!next.isAfter(now)) next = next.plusDays(1)
                val delay = Duration.between(now, next).toMillis()
                val request = PeriodicWorkRequestBuilder<ArchiveWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            }
        }
    }
}