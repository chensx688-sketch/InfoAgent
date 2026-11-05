package com.infoagent.island

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infoagent.data.db.InfoAgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** D08 验收：通知 ID 持久分配、同实体复用、不同实体不碰撞 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NotificationIdTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: InfoAgentDatabase

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun `同实体复用同一 ID，不同实体各不相同`() = runBlocking {
        db = InfoAgentDatabase.build(context, "notif-id.db")
        val factory = NotificationFactory(context, db.scheduleDao())

        val a1 = factory.idFor("briefing:1")
        val a2 = factory.idFor("briefing:1")
        assertEquals("同实体应复用", a1, a2)

        val ids = (1..50).map { factory.idFor("reminder:$it") }
        assertEquals("50 个实体应有 50 个不同 ID", 50, ids.toSet().size)
        assertTrue(ids.all { it >= 1000 })
    }

    @Test
    fun `ID 持久化，重启后仍复用`() = runBlocking {
        db = InfoAgentDatabase.build(context, "notif-id2.db")
        val factory = NotificationFactory(context, db.scheduleDao())
        val first = factory.idFor("briefing:9")
        db.close()

        val reopened = InfoAgentDatabase.build(context, "notif-id2.db")
        val factory2 = NotificationFactory(context, reopened.scheduleDao())
        assertEquals(first, factory2.idFor("briefing:9"))
        reopened.close()
    }
}