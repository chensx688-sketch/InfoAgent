package com.infoagent.capture

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「启动后台采集」按钮的行为契约：能不能成就说成不成，不许假装。
 *
 * 没授权时最容易被写成「返回成功」——那样用户点了没反应，还以为按钮坏了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureBootstrapTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `没授权时不假装成功`() = runBlocking {
        // Robolectric 默认没有通知使用权
        assertFalse(NotificationAccess.isGranted(context))
        val outcome = CaptureBootstrap.ensureConnected(context, keepAlive = true)
        assertEquals(CaptureBootstrap.Outcome.NeedPermission, outcome)
        // 也不该偷偷起保活服务
        assertFalse(CaptureKeepAliveService.isRunning(context))
    }

    @Test
    fun `保活状态默认是关的`() {
        assertFalse(CaptureKeepAliveService.isRunning(context))
    }

    @Test
    fun `保活开关写进状态位`() {
        context.getSharedPreferences(CaptureKeepAliveService.PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(CaptureKeepAliveService.KEY_RUNNING, true).commit()
        assertTrue(CaptureKeepAliveService.isRunning(context))
        context.getSharedPreferences(CaptureKeepAliveService.PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(CaptureKeepAliveService.KEY_RUNNING, false).commit()
        assertFalse(CaptureKeepAliveService.isRunning(context))
    }
}