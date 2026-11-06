package com.infoagent.island

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * 统一的「发通知」入口。
 *
 * 存在的理由：API 33(Android 13) 起 `notify()` 需要 POST_NOTIFICATIONS 运行时权限，
 * 直接调用会在用户拒绝后抛 SecurityException（也是 lint MissingPermission 错误的来源）。
 * 这里集中做「权限 + 通知总开关」判断，调用方不必各自复制一遍。
 */
object NotificationPoster {

    fun canPost(context: Context): Boolean {
        val ctx = context.applicationContext
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permissionGranted && NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    /**
     * 权限/开关都就绪才发；否则静默跳过并返回 false（调用方可据此提示用户去授权）。
     * 权限判断在 [canPost] 里，notify 之前显式校验，因此这里抑制 lint 的静态误报。
     */
    @SuppressLint("MissingPermission")
    fun notify(context: Context, id: Int, notification: Notification): Boolean {
        if (!canPost(context)) return false
        NotificationManagerCompat.from(context.applicationContext).notify(id, notification)
        return true
    }

    /** 直接发 Builder 的便捷重载（会自行 build）。 */
    fun notify(context: Context, id: Int, builder: Notification.Builder): Boolean =
        notify(context, id, builder.build())
}