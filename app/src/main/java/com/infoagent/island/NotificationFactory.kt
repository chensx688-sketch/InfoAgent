package com.infoagent.island

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import com.infoagent.MainActivity
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.ScheduleDao
import com.infoagent.remind.NotificationChannels

/**
 * 通知工厂（§9）：统一出口 + 持久 notification_id。
 * 只构建 builder，不再自己发通知；岛装饰由调用方经 IslandRouter.decorate 完成（避免双通知）。
 */
class NotificationFactory(
    private val context: Context,
    private val dao: ScheduleDao,
) {

    private val db = InfoAgentDatabase.get(context)

    private fun pendingIntent(open: String, extraId: Long? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("open", open)
            extraId?.let { putExtra("entity_id", it) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            (open.hashCode() * 31 + (extraId ?: 0).toInt()) and 0x7fffffff,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 持久分配通知 ID（D08：同实体复用不堆叠；新实体递增不碰撞） */
    suspend fun idFor(entityKey: String): Int = db.withTransaction {
        dao.notificationId(entityKey)?.toInt()?.let { return@withTransaction it }
        val seed = dao.maxNotificationId()
        val next = (if (seed < 1000) 1000 else seed + 1).toInt()
        dao.putNotificationId(entityKey, next.toLong())
        next
    }

    suspend fun buildBriefing(
        sliceId: Long,
        bizDate: String,
        headline: String,
        body: String,
        stale: Boolean,
    ): Pair<Int, Notification.Builder> {
        val id = idFor("briefing:$sliceId")
        val title = if (stale) "简报（上次结果）" else "简报已生成"
        val builder = Notification.Builder(context, NotificationChannels.BRIEFING)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(headline)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent("agent", sliceId))
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .addExtras(
                android.os.Bundle().apply {
                    putLong("slice_id", sliceId)
                    putString("biz_date", bizDate)
                },
            )
        return id to builder
    }

    suspend fun buildReminder(reminderId: Long, title: String, whenText: String): Pair<Int, Notification.Builder> {
        val id = idFor("reminder:$reminderId")
        val builder = Notification.Builder(context, NotificationChannels.KEY_TIME)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ $whenText")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent("memo", reminderId))
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
        return id to builder
    }

    suspend fun buildAutoMemo(memoId: Long, count: Int, sourceConv: String): Pair<Int, Notification.Builder> {
        val id = idFor("automemo:$memoId")
        val builder = Notification.Builder(context, NotificationChannels.AUTO_MEMO)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("AI 整理出 $count 条待办")
            .setContentText("来源：$sourceConv")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent("memo", memoId))
            .setPriority(Notification.PRIORITY_LOW)
        return id to builder
    }
}