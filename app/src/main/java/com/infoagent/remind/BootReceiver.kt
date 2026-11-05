package com.infoagent.remind

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/** 开机重建：通知渠道 + 三段简报定时 + 未触发提醒（§10）；C2：不阻塞主线程 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val app = context.applicationContext
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                NotificationChannels.ensure(app)
                BriefingScheduler.scheduleAll(app)
                BriefingScheduler.catchUp(app) // P1-3：开机补跑错过的时段
                ReminderScheduler.rescheduleAll(app)
                CaptureWatchdogWorker.schedulePeriodic(app)
                com.infoagent.agent.MemoryWorker.schedule(app)
            } catch (e: Exception) {
                android.util.Log.w("InfoAgentBoot", "reschedule failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}