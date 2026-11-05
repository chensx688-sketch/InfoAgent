package com.infoagent.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infoagent.data.db.InfoAgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** §8.6 预算闸门验收：简报 3 次/日、手动 10 次、追问 8k token、计数跨重启持久 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BudgetGuardTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: InfoAgentDatabase

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    private fun guard(date: String = "2026-11-04") = BudgetGuard(db.agentDao()) { date }

    @Test
    fun `简报每日 3 次用尽后拒绝`() = runBlocking {
        db = InfoAgentDatabase.build(context, "budget-brief.db")
        val g = guard()
        assertTrue(g.canSpend(BudgetGuard.USE_BRIEF))
        repeat(3) { g.record(BudgetGuard.USE_BRIEF, 100, 50, 0.0) }
        assertFalse(g.canSpend(BudgetGuard.USE_BRIEF))
        assertEquals(0L, g.remaining(BudgetGuard.USE_BRIEF))
    }

    @Test
    fun `手动生成每日上限 10 次`() = runBlocking {
        db = InfoAgentDatabase.build(context, "budget-manual.db")
        val g = guard()
        repeat(10) { g.record(BudgetGuard.USE_MANUAL, 10, 10, 0.0) }
        assertFalse(g.canSpend(BudgetGuard.USE_MANUAL, manual = true))
    }

    @Test
    fun `追问按 token 额度拦截`() = runBlocking {
        db = InfoAgentDatabase.build(context, "budget-followup.db")
        val g = guard()
        assertTrue(g.canSpend(BudgetGuard.USE_FOLLOWUP))
        g.record(BudgetGuard.USE_FOLLOWUP, 5_000, 3_500, 0.0) // 累计 8500 > 8000
        assertFalse(g.canSpend(BudgetGuard.USE_FOLLOWUP))
    }

    @Test
    fun `计数落库，跨实例不归零（D18 跨进程持久）`() = runBlocking {
        db = InfoAgentDatabase.build(context, "budget-persist.db")
        guard().record(BudgetGuard.USE_BRIEF, 100, 50, 0.01)
        val fresh = BudgetGuard(db.agentDao()) { "2026-11-04" }
        assertEquals(2L, fresh.remaining(BudgetGuard.USE_BRIEF))
        assertEquals(0.01, fresh.dailyCost(), 1e-6)
    }

    @Test
    fun `不同日期独立计数`() = runBlocking {
        db = InfoAgentDatabase.build(context, "budget-days.db")
        BudgetGuard(db.agentDao()) { "2026-11-04" }.record(BudgetGuard.USE_BRIEF, 1, 1, 0.0)
        val nextDay = BudgetGuard(db.agentDao()) { "2026-11-05" }
        assertEquals(3L, nextDay.remaining(BudgetGuard.USE_BRIEF))
    }
}