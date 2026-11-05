package com.infoagent.island

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.infoagent.MainActivity

/** 岛点击/按钮用的 PendingIntent 工厂（统一带 open 深链参数） */
object IslandIntents {

    fun openApp(ctx: Context, open: String): PendingIntent = PendingIntent.getActivity(
        ctx,
        open.hashCode(),
        Intent(ctx, MainActivity::class.java).apply {
            putExtra("open", open)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun broadcast(ctx: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            requestCode,
            Intent(action).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}