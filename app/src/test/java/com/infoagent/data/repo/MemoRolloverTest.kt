package com.infoagent.data.repo

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

/**
 * 用户反馈（2026-11-05）：每日必做隔天被清空 → 改为「顺延 + 重置勾选」；
 * 新增阶段事件：跨天保留，不参与日清。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MemoRolloverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: InfoAgentDatabase

    private val day1 = "2026-11-04"
    private val day2 = "2026-11-05"

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    private fun moveToDay(id: Long, day: String) {
        db.openHelper.writableDatabase.execSQL("UPDATE memo_item SET cycle_date = '$day' WHERE id = $id")
    }

    @Test
    fun `每日必做隔天顺延并重置勾选，不删除`() = runBlocking {
        db = InfoAgentDatabase.build(context, "memo-rollover.db")
        val repo = MemoRepository(db)
        val id = repo.add("DAILY", "健身 45 分钟")
        repo.toggleDone(id, true)
        moveToDay(id, day1)

        repo.archiveBefore(day2)

        val today = repo.daily(day2)
        assertEquals("条目不应当消失", 1, today.size)
        assertEquals("健身 45 分钟", today.first().text)
        assertEquals("新的一天应重置勾选", 0L, today.first().done)
        assertTrue("应保留昨天的完成记录", repo.archiveOf(day1).any { it.text == "健身 45 分钟" })
    }

    @Test
    fun `阶段事件不参与日清，只能手动删除`() = runBlocking {
        db = InfoAgentDatabase.build(context, "memo-stage.db")
        val repo = MemoRepository(db)
        val id = repo.add("STAGE", "项目上线前准备")
        moveToDay(id, day1)

        repo.archiveBefore(day2)

        val stage = repo.stage()
        assertEquals("阶段事件必须保留", 1, stage.size)
        assertEquals("项目上线前准备", stage.first().text)
        assertTrue("阶段事件不应进入每日必做", repo.daily(day2).isEmpty())
        assertTrue("阶段事件不应被当成过期临时事件归档", repo.archiveOf(day1).none { it.text == "项目上线前准备" })
    }

    @Test
    fun `临时事件超过30天才归档`() = runBlocking {
        db = InfoAgentDatabase.build(context, "memo-temp.db")
        val repo = MemoRepository(db)
        val fresh = repo.add("TEMP", "明天的快递")
        val old = repo.add("TEMP", "一个月前的杂事")
        moveToDay(fresh, day2)
        moveToDay(old, "2026-09-01")

        repo.archiveBefore(day2)

        val temps = repo.temp(day2).map { it.text }
        assertTrue("近期临时事件要留着", "明天的快递" in temps)
        assertTrue("超期临时事件应被归档", temps.none { it == "一个月前的杂事" })
        assertTrue(repo.archiveOf("2026-09-01").any { it.text == "一个月前的杂事" })
    }
}