package com.infoagent.island

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** E1：Router 按厂商选适配器；全部走系统通知，不再有悬浮窗分支 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IslandRouterTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `小米与红米 POCO 选 hyperos`() {
        assertEquals("hyperos", IslandRouter.pick(app, "Xiaomi").id)
        assertEquals("hyperos", IslandRouter.pick(app, "Redmi").id)
        assertEquals("hyperos", IslandRouter.pick(app, "POCO").id)
    }

    @Test
    fun `OPPO 系选 coloros`() {
        assertEquals("coloros", IslandRouter.pick(app, "OPPO").id)
        assertEquals("coloros", IslandRouter.pick(app, "realme").id)
        assertEquals("coloros", IslandRouter.pick(app, "OnePlus").id)
    }

    @Test
    fun `其它厂商走系统通知适配器`() {
        assertEquals("system", IslandRouter.pick(app, "samsung").id)
        assertEquals("system", IslandRouter.pick(app, "HUAWEI").id)
    }

    @Test
    fun `载荷归一化：按钮最多2个且进度裁剪`() {
        val pi = PendingIntent.getBroadcast(
            app, 0, Intent("test"), PendingIntent.FLAG_IMMUTABLE,
        )
        val payload = IslandPayload(
            key = "k",
            title = "t".repeat(100),
            content = "c".repeat(200),
            progress = 150,
            actions = listOf(
                IslandAction("a", pi),
                IslandAction("b", pi),
                IslandAction("c", pi),
            ),
        ).normalized()
        assertEquals(2, payload.actions.size)
        assertEquals(100, payload.progress)
        assertEquals(40, payload.title.length)
        assertEquals(80, payload.content.length)
    }
}