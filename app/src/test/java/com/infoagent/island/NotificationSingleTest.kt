package com.infoagent.island

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.infoagent.remind.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 复审 1.6：岛只装饰业务通知，notify 只调用 1 次（双通知回归）。
 * 不走数据库，直接验证「装饰 + 单次 notify」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationSingleTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `装饰后只发一条 且复用业务渠道`() {
        val pi = PendingIntent.getActivity(
            app,
            1,
            Intent(app, com.infoagent.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(app, NotificationChannels.KEY_TIME)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ 09:00")
            .setContentText("喝水")
            .setContentIntent(pi)

        IslandRouter.decorate(
            app,
            builder,
            IslandPayload(key = "reminder-4242", title = "⏰ 09:00", content = "喝水", progress = 30),
        )
        NotificationManagerCompat.from(app).notify(4242, builder.build())

        val shadow = shadowOf(app.getSystemService(NotificationManager::class.java))
        assertEquals("一次提醒只应有一条通知", 1, shadow.size())
        val posted = shadow.allNotifications.single()
        assertEquals("key_time_reminder", posted.channelId)
        assertNotNull("通知必须带有可点击内容意图", posted.contentIntent)

        // P0-1 回归：业务通知不能被设置超时自动消失
        assertEquals("提醒类通知不得设置 timeoutAfter", 0L, timeoutAfterOf(posted))
    }

    /** Notification.timeoutAfter 不是稳定公开 API，用反射读字段 */
    private fun timeoutAfterOf(n: Notification): Long = runCatching {
        val f = Notification::class.java.getDeclaredField("timeoutAfter").apply { isAccessible = true }
        f.getLong(n)
    }.getOrElse {
        runCatching {
            Notification::class.java.getMethod("getTimeoutAfter").invoke(n) as? Long ?: 0L
        }.getOrDefault(0L)
    }
}