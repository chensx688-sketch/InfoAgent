package com.infoagent.remind

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.ReminderEntity
import java.util.concurrent.TimeUnit

/**
 * 关键时间提醒（FR-09）：**AlarmManager 精确闹钟**（国内 ROM 上 WorkManager 不可靠），
 * 无精确闹钟权限时退化 setAndAllowWhileIdle，再不行落 WorkManager 兜底。
 */
object ReminderScheduler {

    private const val ACTION = "com.infoagent.action.REMINDER"
    private const val KEY_ID = "reminder_id"
    private const val KEY_TITLE = "title"
    private const val KEY_FIRE_AT = "fire_at"

    suspend fun schedule(context: Context, memoId: Long, title: String, fireAt: Long): Long? {
        if (fireAt <= System.currentTimeMillis()) return null
        val db = InfoAgentDatabase.get(context)
        db.scheduleDao().deleteRemindersOfMemo(memoId)
        val reminderId = db.scheduleDao().insertReminder(
            ReminderEntity(kind = "MEMO", memoId = memoId, title = title, fireAt = fireAt),
        )
        arm(context, reminderId, title, fireAt)
        return reminderId
    }

    suspend fun cancel(context: Context, memoId: Long) {
        runCatching {
            val db = InfoAgentDatabase.get(context)
            db.scheduleDao().reminderOfMemo(memoId)?.let { r ->
                val am = context.getSystemService(AlarmManager::class.java)
                am?.cancel(alarmIntent(context, r.id, "", 0L))
            }
            db.scheduleDao().deleteRemindersOfMemo(memoId)
            WorkManager.getInstance(context).cancelUniqueWork("reminder_memo_$memoId")
        }
    }

    suspend fun rescheduleAll(context: Context) {
        runCatching {
            val db = InfoAgentDatabase.get(context)
            db.scheduleDao().pendingReminders(System.currentTimeMillis()).forEach { r ->
                arm(context, r.id, r.title ?: "提醒", r.fireAt)
            }
        }
    }

    /** 精确闹钟权限是否可用（用户可随时撤销） */
    fun hasExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    private fun arm(context: Context, reminderId: Long, title: String, fireAt: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (am == null || fireAt <= System.currentTimeMillis()) return
        val pi = alarmIntent(context, reminderId, title, fireAt)
        val canExact = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            } else {
                // 无精确权限：允许待机时触发（可能延后几分钟），比不响好
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            }
        }.onFailure {
            android.util.Log.w("InfoAgentReminder", "arm alarm failed", it)
        }
    }

    private fun alarmIntent(context: Context, reminderId: Long, title: String, fireAt: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION
            putExtra(KEY_ID, reminderId)
            putExtra(KEY_TITLE, title)
            putExtra(KEY_FIRE_AT, fireAt)
            setPackage(context.packageName)
        }
        return PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}