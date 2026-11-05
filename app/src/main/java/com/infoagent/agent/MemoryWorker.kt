package com.infoagent.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.settings.SettingsRepository
import com.infoagent.data.util.AgentLockGuard
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * B1：记忆提炼夜间任务（此前 MemoryPipeline 从未被实例化，"记忆"功能等于不存在）。
 * 每晚 23:30 跑一次；与简报/自动整理共用 agent_lock。
 */
class MemoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val UNIQUE = "memory_nightly"

        fun schedule(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
            runCatching {
                var next = now.withHour(23).withMinute(30).withSecond(0).withNano(0)
                if (!next.isAfter(now)) next = next.plusDays(1)
                val delay = Duration.between(now, next).toMillis()
                val request = PeriodicWorkRequestBuilder<MemoryWorker>(1, TimeUnit.DAYS)
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

    override suspend fun doWork(): Result {
        val db = InfoAgentDatabase.get(applicationContext)
        val settings = SettingsRepository(applicationContext)
        if (settings.config() == null) return Result.success()
        val owner = "memory-" + UUID.randomUUID()
        val outcome = AgentLockGuard.withLock(db, owner) {
            MemoryPipeline(
                applicationContext,
                db,
                OpenAiCompatibleClient(),
                { settings.config() },
            ).run()
        } ?: return Result.retry()
        return when (outcome) {
            is MemoryPipeline.Outcome.Failed -> Result.retry()
            else -> Result.success()
        }
    }
}