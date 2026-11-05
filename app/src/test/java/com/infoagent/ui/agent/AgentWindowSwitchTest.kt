package com.infoagent.ui.agent

import android.os.Looper
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf
import com.infoagent.data.db.AgentRunEntity
import com.infoagent.data.db.InfoAgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * 用户反馈：「上午/下午/晚间/手动四个栏目切换没有任何区别」。
 * 这里验证 AgentViewModel 切换栏目时确实读取该 window 的 run 与窗口条数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AgentWindowSwitchTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var db: InfoAgentDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        app.getDatabasePath("window-switch.db").delete()
    }

    private fun briefJson(window: String) = """
        {"window":"$window","generated_at":"2026-11-05T10:00:00+08:00",
         "wechat_points":[{"conv":"王总","point":"下午三点开会"}],
         "memo_status":{"done":0,"pending_daily":[],"pending_temp":[]},
         "plan":{"timeline":[],"note":null},"recommendations":[],"tomorrow":null,"risks":[]}
    """.trimIndent()

    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    @Test
    fun `切换栏目读取各自窗口的简报`() = runBlocking {
        // 走单例库：AgentViewModel 内部用的是 InfoAgentDatabase.get(app)
        val database = InfoAgentDatabase.get(app).also { db = it }
        database.openHelper.writableDatabase.execSQL("DELETE FROM agent_run")
        kotlinx.coroutines.delay(200)
        // 直接给 MORNING 与 EVENING 各写一份 run（模拟历史上生成过）
        database.agentDao().insertRun(
            AgentRunEntity(
                bizDate = com.infoagent.data.util.DayBoundary.today(),
                window = "MORNING",
                inputHash = "h1",
                cacheKey = "brief_v1:h1",
                contractVersion = "brief_v1",
                promptVersion = "1",
                model = "m",
                temperature = 0.3,
                maxTokens = 2048,
                briefJson = briefJson("MORNING"),
                tokensUsed = 100,
                costEst = 0.0,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.agentDao().insertRun(
            AgentRunEntity(
                bizDate = com.infoagent.data.util.DayBoundary.today(),
                window = "EVENING",
                inputHash = "h2",
                cacheKey = "brief_v1:h2",
                contractVersion = "brief_v1",
                promptVersion = "1",
                model = "m",
                temperature = 0.3,
                maxTokens = 2048,
                briefJson = briefJson("EVENING"),
                tokensUsed = 200,
                costEst = 0.0,
                createdAt = 2L,
                updatedAt = 2L,
            ),
        )

        val vm = AgentViewModel(app)
        // 等 init（DataStore + 默认窗口加载）跑完
        waitUntil { vm.windowInfo.value != null }
        // 硬校验：数据确实入库且能被契约解析
        val persisted = database.agentDao().latestRun(com.infoagent.data.util.DayBoundary.today(), "MORNING")
        assertNotNull("MORNING run 应已入库", persisted)
        val parsedCheck = com.infoagent.agent.SafeParser.parseBrief(persisted!!.briefJson!!, "MORNING")
        assertNotNull("MORNING 简报应能解析：$parsedCheck", parsedCheck as? com.infoagent.agent.SafeParser.Result.Ok)
        // init 会异步加载默认窗口；这里直接触发两个窗口，等结果
        vm.selectWindow("MORNING")
        waitUntil { vm.run.value != null }
        val morning = vm.run.value
        assertNotNull("上午栏目应加载到上午简报", morning)
        assertEquals("MORNING", morning!!.brief!!.window)

        vm.selectWindow("EVENING")
        waitUntil { vm.run.value?.runId == 2L }
        val evening = vm.run.value
        assertNotNull("晚间栏目应加载到晚间简报", evening)
        assertEquals("EVENING", evening!!.brief!!.window)
        assertEquals(2L, evening.runId)

        vm.selectWindow("AFTERNOON")
        waitUntil { vm.run.value == null && vm.windowInfo.value?.window == "AFTERNOON" }
        assertNull("没有下午简报的窗口应为空，而不是沿用上一份", vm.run.value)
        assertEquals("AFTERNOON", vm.windowInfo.value?.window)
    }
}