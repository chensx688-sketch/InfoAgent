package com.infoagent.remind

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 三段简报定时（§10）：10:00 / 13:30 / 22:00（可调）。
 * 用 PeriodicWorkRequest + initialDelay 对齐下一个触发点；unique name 幂等 UPDATE。
 */
object BriefingScheduler {

    data class Slot(val window: String, val hour: Int, val minute: Int, val label: String)

    val SLOTS = listOf(
        Slot("MORNING", 10, 0, "上午"),
        Slot("AFTERNOON", 13, 30, "下午"),
        Slot("EVENING", 22, 0, "晚间"),
    )

    /** 下一次触发时间（纯函数，便于测试） */
    fun nextTrigger(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        var next = now.with(LocalTime.of(hour, minute)).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next
    }

    fun scheduleAll(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        runCatching {
            // N2：时点/时区指纹没变就用 UPDATE，避免「进程被定时拉起 → onCreate 重排 → 取消马上要跑的任务」
            val slots = BriefingPrefs.slots(context)
            val fingerprint = slots.joinToString("|") { "${it.window}:${it.hour}:${it.minute}" } +
                "@" + java.util.TimeZone.getDefault().id
            val prefs = context.getSharedPreferences("brief_schedule", Context.MODE_PRIVATE)
            val changed = prefs.getString("fingerprint", null) != fingerprint
            val policy = if (changed) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE

            slots.forEach { slot ->
                val delay = Duration.between(now, nextTrigger(now, slot.hour, slot.minute)).toMillis()
                val request = PeriodicWorkRequestBuilder<BriefingWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf("window" to slot.window))
                    .build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "briefing_${slot.window}",
                    policy,
                    request,
                )
            }
            if (changed) prefs.edit().putString("fingerprint", fingerprint).apply()
        }
    }

    /**
     * P1-3：补跑错过的时段（关机/被冻结导致 PENDING 过期）。
     * Application 启动与开机广播各扫一次；每种过期档期只补一次（unique name KEEP）。
     */
    fun catchUp(context: Context) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val db = com.infoagent.data.db.InfoAgentDatabase.get(context)
                val now = System.currentTimeMillis()
                // P2-3：只补最近 24 小时内的档期，两周前的 PENDING 不再拉起
                val lowerBound = now - 24 * 60 * 60 * 1000L
                db.scheduleDao().pendingOverdue(now - 60_000)
                    .filter { it.expectedAt >= lowerBound }
                    .forEach { row ->
                    val window = row.kind?.removePrefix("BRIEF_")
                        ?.takeIf { it in setOf("MORNING", "AFTERNOON", "EVENING") } ?: "MANUAL"
                    val req = androidx.work.OneTimeWorkRequestBuilder<BriefingWorker>()
                        .setInputData(
                            workDataOf(
                                "window" to window,
                                "catchup" to true,
                            ),
                        )
                        .build()
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        "brief_catchup_${row.id}",
                        androidx.work.ExistingWorkPolicy.KEEP,
                        req,
                    )
                }
            }
        }
    }
}