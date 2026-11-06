package com.infoagent.capture

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 「点一下就真的开始采集」——把原来那个只会调一次系统 API 的按钮做实。
 *
 * 原来那个按钮的问题：`NotificationListenerService.requestRebind()` 在很多 ROM（HyperOS/EMUI/ColorOS）
 * 上是个**空动作**——系统记着「已授权」，服务却没被绑上，点了当然没反应，用户以为按钮坏了。
 *
 * 这里的做法是「穷举 + 验证」：
 * 1) 没授权 → 直接告诉用户去开（不假装成功）；
 * 2) 已连上 → 什么都不做，直接返回；
 * 3) 没连上 → 先请求重绑，等一会儿；还不行就把监听组件**先禁用再启用**（这一步能强制系统重绑，
 *    权限本身不会因此丢失）；再请求一次；
 * 4) 最后**如实回报**结果——连上就是连上，没连上就说没连上，并给下一步。
 */
object CaptureBootstrap {

    private const val TAG = "InfoAgentCapture"
    private const val WAIT_STEP_MS = 400L

    sealed class Outcome {
        /** 本来就在跑，不需要动作 */
        data object AlreadyConnected : Outcome()

        /** 没有通知使用权，必须用户去系统设置开 */
        data object NeedPermission : Outcome()

        /** 这次动作之后确实连上了 */
        data object Connected : Outcome()

        /** 动作做完了仍然没连上（如实告知，不吹） */
        data class StillDown(val hint: String) : Outcome()
    }

    /**
     * 尝试把采集服务弄起来，并在成功时启动前台保活（防止被系统回收后又不响）。
     * 必须在主线程之外的协程里调用（内部已切 IO）。
     */
    suspend fun ensureConnected(context: Context, keepAlive: Boolean = true): Outcome =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            if (!NotificationAccess.isGranted(app)) return@withContext Outcome.NeedPermission
            if (NotifyCaptureService.isConnected) {
                if (keepAlive) CaptureKeepAliveService.start(app)
                return@withContext Outcome.AlreadyConnected
            }

            // 第一轮：标准重绑
            requestRebind(app)
            if (waitForConnect(2_000)) return@withContext finish(app, keepAlive)

            // 第二轮：禁用→启用组件，强制系统重新绑定（很多国产 ROM 只有这条路有用）
            toggleListenerComponent(app)
            delay(300)
            requestRebind(app)
            if (waitForConnect(3_000)) return@withContext finish(app, keepAlive)

            Outcome.StillDown(
                "系统没有把监听重新绑上。到「通知使用权」里把本应用关掉再打开一次即可恢复" +
                    "（设置 → 通知使用权 → 找到「信息助理」取消再勾选）。",
            )
        }

    private fun finish(app: Context, keepAlive: Boolean): Outcome {
        if (keepAlive) CaptureKeepAliveService.start(app)
        return Outcome.Connected
    }

    private suspend fun waitForConnect(totalMs: Long): Boolean {
        var waited = 0L
        while (waited < totalMs) {
            if (NotifyCaptureService.isConnected) return true
            delay(WAIT_STEP_MS)
            waited += WAIT_STEP_MS
        }
        return NotifyCaptureService.isConnected
    }

    private fun requestRebind(app: Context) {
        runCatching {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(app, NotifyCaptureService::class.java),
            )
        }.onFailure { Log.w(TAG, "requestRebind failed", it) }
    }

    /**
     * 先禁用再启用监听组件。
     *
     * 注意：这里改的是**组件的启用状态**，不是权限本身，通知使用权不会因此被撤销；
     * 但系统看到组件重新启用，会重建绑定——这是在没有 adb 的情况下最有效的一招。
     */
    private fun toggleListenerComponent(app: Context) {
        val pm = app.packageManager
        val cn = ComponentName(app, NotifyCaptureService::class.java)
        runCatching {
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }.onFailure { Log.w(TAG, "toggle component failed", it) }
    }
}