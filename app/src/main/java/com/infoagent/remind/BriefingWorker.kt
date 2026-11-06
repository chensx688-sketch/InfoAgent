package com.infoagent.remind

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.infoagent.agent.AutoMemoPipeline
import com.infoagent.agent.BriefingPipeline
import com.infoagent.agent.OpenAiCompatibleClient
import com.infoagent.agent.Prompts
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.settings.SettingsRepository
import com.infoagent.island.NotificationFactory

/**
 * 定时简报 Worker（§10）：先跑自动整理，再生成简报并推送；
 * 触发偏差 > ±15 分钟标 DEGRADED（显示「补发·原定 HH:mm」）。
 */
class BriefingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val DEGRADED_THRESHOLD_MS = 15 * 60_000L
    }

    override suspend fun doWork(): Result {
        val window = inputData.getString("window") ?: return Result.success()
        // P2-2：补跑只补简报，跳过自动整理（省 token）
        val catchUp = inputData.getBoolean("catchup", false)
        NotificationChannels.ensure(applicationContext)
        val db = InfoAgentDatabase.get(applicationContext)
        val settings = SettingsRepository(applicationContext)
        val client = OpenAiCompatibleClient()

        // 简报前先跑一次自动整理（失败不阻塞简报；补跑时跳过）
        if (!catchUp) {
            runCatching {
                AutoMemoPipeline(db, client, { settings.config() }).run()
            }
        }

        val scheduled = db.scheduleDao().latestUnfired("BRIEF_$window")
        // A8：expectedAt 取「该窗口的计划时点」，而不是 now —— 否则补发标记永远不会出现
        val slot = BriefingPrefs.slots(applicationContext).firstOrNull { it.window == window }
        val expectedAt = slot?.let { expectedSlotMs(it) } ?: (scheduled?.expectedAt ?: System.currentTimeMillis())
        val recordId = scheduled?.id ?: db.scheduleDao().insertScheduledRun(
            com.infoagent.data.db.ScheduledRunEntity(
                kind = "BRIEF_$window",
                expectedAt = expectedAt,
                state = "PENDING",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        val pipeline = BriefingPipeline(
            db = db,
            client = client,
            configProvider = { settings.config() },
            boundsProvider = {
                val slots = BriefingPrefs.slots(applicationContext)
                com.infoagent.agent.BriefingPipeline.WindowBounds(
                    morningEnd = slots.first { it.window == "MORNING" }.let { it.hour * 60 + it.minute },
                    afternoonEnd = slots.first { it.window == "AFTERNOON" }.let { it.hour * 60 + it.minute },
                    eveningEnd = slots.first { it.window == "EVENING" }.let { it.hour * 60 + it.minute },
                )
            },
        ).withEnricher(com.infoagent.enrich.ContextEnricher.forApp(db, applicationContext))
        // B2：与自动整理共用 agent_lock；拿不到锁则等下一轮（避免并发调用抢配额）
        val owner = "brief-" + java.util.UUID.randomUUID()
        val run = com.infoagent.data.util.AgentLockGuard.withLock(db, owner) {
            pipeline.generateSlice(window)
        } ?: return Result.retry()
        val now = System.currentTimeMillis()

        val brief = run.brief
        if (run.state == "OK" && brief != null) {
            val late = (now - expectedAt) > DEGRADED_THRESHOLD_MS
            val headline = Prompts.briefHeadline(brief)
            val body = Prompts.briefToPlainText(brief)
            val factory = NotificationFactory(applicationContext, db.scheduleDao())
            val windowLabel = when (window) {
                "MORNING" -> "上午简报"
                "AFTERNOON" -> "下午简报"
                "EVENING" -> "晚间简报"
                else -> "手动简报"
            }
            if (NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
                // 一条通知：装饰后直接发，不再另发岛通知
                val (id, builder) = factory.buildBriefing(
                    sliceId = run.sliceId,
                    bizDate = com.infoagent.data.util.DayBoundary.today(),
                    headline = if (late) "补发·原定 ${clockText(expectedAt)}｜$headline" else headline,
                    body = body,
                    stale = false,
                )
                com.infoagent.island.IslandRouter.decorate(
                    applicationContext,
                    builder,
                    com.infoagent.island.IslandPayload(
                        key = "brief-${run.sliceId}",
                        title = "☀ $windowLabel",
                        content = headline,
                        deepLink = com.infoagent.island.IslandIntents.openApp(applicationContext, "agent"),
                    ),
                )
                NotificationManagerCompat.from(applicationContext).notify(id, builder.build())
            }
            scheduled?.let {
                db.scheduleDao().updateRun(it.id, if (late) "DEGRADED" else "FIRED", now, null, run.sliceId, now)
            } ?: db.scheduleDao().updateRun(
                recordId,
                if (late) "DEGRADED" else "FIRED",
                now,
                null,
                run.sliceId,
                now,
            )
            return Result.success()
        }

        db.scheduleDao().updateRun(recordId, "MISSED", now, run.error, null, now)
        // 状态机：FAILED 退避重试（WorkManager 次数由调度控制）；SKIPPED 为终态
        return if (run.state == "FAILED") Result.retry() else Result.success()
    }

    /** 该窗口当天的计划时点（本地时区） */
    private fun expectedSlotMs(slot: BriefingScheduler.Slot): Long {
        val today = com.infoagent.data.util.DayBoundary.today()
        val startOfDay = com.infoagent.data.util.DayBoundary.dayRange(today)[0]
        return startOfDay + slot.hour * 3_600_000L + slot.minute * 60_000L
    }

    private fun clockText(ts: Long): String =
        java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
            .toLocalTime().toString().take(5)
}