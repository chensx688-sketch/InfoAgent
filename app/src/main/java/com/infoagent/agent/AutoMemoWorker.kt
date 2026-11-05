package com.infoagent.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.settings.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * 自动整理 Worker（FR-06 / §8.2）：消息到达后防抖 5 分钟触发；
 * 简报生成前也会同步跑一次（阶段 8 接入）。
 */
class AutoMemoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = InfoAgentDatabase.get(applicationContext)
        val settings = SettingsRepository(applicationContext)
        com.infoagent.remind.NotificationChannels.ensure(applicationContext)
        val pipeline = AutoMemoPipeline(db, OpenAiCompatibleClient(), { settings.config() })
        // B2：与简报/记忆共用 agent_lock，拿不到锁说明有任务在跑 → 稍后重试
        val owner = "automemo-" + java.util.UUID.randomUUID()
        val outcome = com.infoagent.data.util.AgentLockGuard.withLock(db, owner) { pipeline.run() }
            ?: return Result.retry()
        return when (outcome) {
            is AutoMemoPipeline.Outcome.Created -> {
                runCatching {
                    val memo = db.memoDao().list(
                        com.infoagent.data.util.DayBoundary.today(),
                        "TEMP",
                    ).lastOrNull { it.id == outcome.memoIds.first() }
                    val conv = memo?.sourceConv ?: "微信"
                    if (androidx.core.app.NotificationManagerCompat.from(applicationContext)
                            .areNotificationsEnabled()
                    ) {
                        val factory = com.infoagent.island.NotificationFactory(applicationContext, db.scheduleDao())
                        val (id, builder) = factory.buildAutoMemo(outcome.memoIds.first(), outcome.count, conv)
                        com.infoagent.island.IslandRouter.decorate(
                            applicationContext,
                            builder,
                            com.infoagent.island.IslandPayload(
                                key = "automemo-${outcome.memoIds.first()}",
                                title = "✦ AI 待办",
                                content = "从「$conv」整理出 ${outcome.count} 条",
                                deepLink = com.infoagent.island.IslandIntents.openApp(applicationContext, "memo"),
                            ),
                        )
                        androidx.core.app.NotificationManagerCompat.from(applicationContext)
                            .notify(id, builder.build())
                    }
                }
                Result.success()
            }
            is AutoMemoPipeline.Outcome.Nothing -> Result.success()
            is AutoMemoPipeline.Outcome.Skipped -> Result.success()
            is AutoMemoPipeline.Outcome.Failed -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "auto_memo_debounce"

        /** 防抖：每条新消息重置 5 分钟计时（REPLACE 语义仅用于该防抖场景） */
        fun scheduleDebounced(context: Context, delayMinutes: Long = 5) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<AutoMemoWorker>()
                    .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                    .build()
                // B3：首次消息起最多 5 分钟就必跑一次；用 KEEP 不再被后续消息无限重置
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }
        }
    }
}