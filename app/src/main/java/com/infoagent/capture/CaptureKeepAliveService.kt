package com.infoagent.capture

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.infoagent.R
import com.infoagent.remind.NotificationChannels

/**
 * 采集保活（前台服务）。
 *
 * 为什么需要它：通知监听服务由系统绑定，App 进程一旦被厂商省电策略冻结/回收，
 * 绑定就断了——表现就是「昨天还好好的，今天一条都没进来」。
 * 前台服务带一条常驻通知，等于告诉系统「这个进程在干活」，被杀的优先级大幅下降。
 *
 * 取舍：只放**一条**低打扰常驻通知（可折叠、无声、不可点击进入任意页面之外的跳转），
 * 并且用户随时能用同一个按钮关掉——不做偷偷保活。
 */
class CaptureKeepAliveService : Service() {

    companion object {
        private const val CHANNEL = NotificationChannels.CAPTURE
        private const val NOTIFICATION_ID = 0x1A61
        const val ACTION_STOP = "com.infoagent.capture.STOP_KEEPALIVE"
        const val PREFS = "capture_keepalive"
        const val KEY_RUNNING = "running"

        fun start(context: Context) {
            val intent = Intent(context, CaptureKeepAliveService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    // Android 12+ 后台启动前台服务受限：退一步只发起普通 startService，
                    // 至少不崩，也不会谎报「已开启」
                    runCatching { context.startService(intent) }
                }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureKeepAliveService::class.java))
        }

        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RUNNING, false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 12+ 在后台启动前台服务会被拒（ForegroundServiceStartNotAllowedException），
        // 这里不能崩：崩了用户看到的是「点了按钮 App 闪退」，比没保活更糟。
        val promoted = runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }.isSuccess
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, promoted).apply()
        if (!promoted) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        NotificationChannels.ensure(this)
        val open = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val connected = NotifyCaptureService.isConnected
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (connected) "消息采集运行中" else "消息采集待重连")
            .setContentText(if (connected) "微信/QQ 通知会静默入库，点按可查看" else "监听已断开，点按进入消息页重连")
            .setPriority(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) NotificationCompat.PRIORITY_MIN else NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "停止保活", stop)
            .build()
    }
}