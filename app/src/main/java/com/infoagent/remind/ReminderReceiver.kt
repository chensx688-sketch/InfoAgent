package com.infoagent.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.island.NotificationFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 精确闹钟到点回调：发高优先级通知 + 岛上提示 + 标记已触发 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.infoagent.action.REMINDER") return
        val app = context.applicationContext
        val reminderId = intent.getLongExtra("reminder_id", 0L)
        val title = intent.getStringExtra("title")?.ifBlank { "提醒" } ?: "提醒"
        val fireAt = intent.getLongExtra("fire_at", System.currentTimeMillis())
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotificationChannels.ensure(app)
                val db = InfoAgentDatabase.get(app)
                val timeText = java.time.Instant.ofEpochMilli(fireAt)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5)
                if (NotificationManagerCompat.from(app).areNotificationsEnabled()) {
                    val factory = NotificationFactory(app, db.scheduleDao())
                    // 只发一条：在业务通知上装饰岛，不再单独 notify（否则 +2 条）
                    val (id, builder) = factory.buildReminder(reminderId, title, timeText)
                    com.infoagent.island.IslandRouter.decorate(
                        app,
                        builder,
                        com.infoagent.island.IslandPayload(
                            key = "reminder-$reminderId",
                            title = "⏰ $timeText",
                            content = title,
                            deepLink = com.infoagent.island.IslandIntents.openApp(app, "memo"),
                        ),
                    )
                    NotificationManagerCompat.from(app).notify(id, builder.build())
                }
                if (reminderId > 0) db.scheduleDao().markFired(reminderId, System.currentTimeMillis())
                Log.i("InfoAgentReminder", "fired reminder=$reminderId title=$title at=$timeText")
            } catch (e: Exception) {
                Log.e("InfoAgentReminder", "fire failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}