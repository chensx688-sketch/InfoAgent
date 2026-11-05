package com.infoagent.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.MemoItemEntity
import com.infoagent.data.repo.IncomingMessage
import com.infoagent.data.repo.MessageRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** §8.3/§8.7 流水线验收：缓存命中、预算拦截、解析失败降级、撤回失效缓存（D04） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BriefingPipelineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: InfoAgentDatabase

    private class FakeClient(var payload: String) : AgentClient {
        var calls = 0
        override suspend fun complete(config: ApiConfig, messages: List<ChatMessage>, onDelta: ((String) -> Unit)?): ChatResult {
            calls++
            return ChatResult(payload, tokensIn = 100, tokensOut = 50)
        }

        override fun cancel() {}
    }

    private val validBrief = """
    {"window":"%WINDOW%","generated_at":"2026-11-04T10:00:00+08:00",
     "wechat_points":[{"conv":"王总","point":"下午三点开会","key_time":"15:00"}],
     "memo_status":{"done":1,"pending_daily":[{"text":"健身","time":"18:30"}],"pending_temp":[]},
     "plan":{"timeline":[{"time":"15:00","title":"开会","source":"chat","detail":"王总","kind":"must","date":null}]},
     "recommendations":[{"text":"开会前整理合同","reason":"上次忘带","memory_refs":[1]}],
     "tomorrow":null,"risks":[]}
    """.trimIndent()

    private fun payload(window: String) = validBrief.replace("%WINDOW%", window)

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    private val clockAt: Long = java.time.LocalDate.parse("2026-11-04")
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 12 * 3_600_000L

    private fun pipeline(client: AgentClient, date: String = "2026-11-04"): BriefingPipeline {
        val config = ApiConfig(baseUrl = "https://example.com", apiKey = "sk-test", model = "deepseek-chat")
        return BriefingPipeline(
            db = db,
            client = client,
            configProvider = { config },
            clock = { clockAt },
            dateProvider = { date },
        )
    }

    private suspend fun seed() {
        val repo = MessageRepository(db)
        repo.ingest(
            IncomingMessage("wechat", "王总", "王总", "王总", "IN", "text", "明天下午三点开会，带合同", clockAt - 3_600_000L),
        )
        db.memoDao().insert(
            MemoItemEntity(kind = "DAILY", text = "健身", sortOrder = 1, cycleDate = "2026-11-04", createdAt = 1, updatedAt = 1),
        )
    }

    @Test
    fun `首次生成成功并落库，二次命中缓存零调用`() = runBlocking {
        db = InfoAgentDatabase.build(context, "pipe-cache.db")
        seed()
        val client = FakeClient(payload("MANUAL"))
        val pipe = pipeline(client)

        val first = pipe.generate("MANUAL", manual = true)
        assertNotNull(first.brief)
        assertEquals(1, client.calls)
        assertTrue(first.fromCache.not())

        val second = pipe.generate("MANUAL", manual = true)
        assertNotNull(second.brief)
        assertTrue("二次应命中缓存", second.fromCache)
        assertEquals("命中缓存不得再调 API", 1, client.calls)
    }

    @Test
    fun `强制重新生成跳过缓存`() = runBlocking {
        db = InfoAgentDatabase.build(context, "pipe-force.db")
        seed()
        val client = FakeClient(payload("MANUAL"))
        val pipe = pipeline(client)

        pipe.generate("MANUAL", manual = true)
        assertEquals(1, client.calls)
        assertTrue(pipe.generate("MANUAL", manual = true).fromCache)

        val forced = pipe.generate("MANUAL", manual = true, force = true)
        assertEquals("force 必须重新调模型", 2, client.calls)
        assertTrue("force 的结果不应标记为缓存", !forced.fromCache)
    }

    @Test
    fun `撤回消息使缓存失效（D04）`() = runBlocking {
        db = InfoAgentDatabase.build(context, "pipe-recall.db")
        seed()
        val client = FakeClient(payload("MANUAL"))
        val pipe = pipeline(client)

        pipe.generate("MANUAL", manual = true)
        assertEquals(1, client.calls)

        db.messageDao().updateRecalled(1L, 1)
        val second = pipe.generate("MANUAL", manual = true)
        assertTrue("撤回后不应命中旧缓存", second.fromCache.not())
        assertEquals(2, client.calls)
    }

    @Test
    fun `解析失败返回错误且不写缓存`() = runBlocking {
        db = InfoAgentDatabase.build(context, "pipe-fail.db")
        seed()
        val client = FakeClient("抱歉，我无法完成")
        val pipe = pipeline(client)

        val run = pipe.generate("MANUAL", manual = true)
        assertNull(run.brief)
        assertNotNull(run.error)
        assertTrue(run.rawText != null)
        assertEquals(1, client.calls)
    }

    @Test
    fun `预算耗尽后拒绝，且提示可读`() = runBlocking {
        db = InfoAgentDatabase.build(context, "pipe-budget.db")
        seed()
        val guard = BudgetGuard(db.agentDao()) { "2026-11-04" }
        repeat(10) { guard.record(BudgetGuard.USE_MANUAL, 1, 1, 0.0) }

        val client = FakeClient(payload("MANUAL"))
        val run = pipeline(client).generate("MANUAL", manual = true)
        assertNull(run.brief)
        assertTrue(run.error!!.contains("额度"))
        assertEquals(0, client.calls)
    }

    @Test
    fun `窗口边界符合计划定义`() {
        db = InfoAgentDatabase.build(context, "pipe-window.db")
        val pipe = pipeline(FakeClient(""))
        val day = "2026-11-04"
        val startOfDay = java.time.LocalDate.parse(day)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val morning = pipe.windowRange("MORNING", day, startOfDay + 10 * 3_600_000L)
        assertEquals(startOfDay - 2 * 3_600_000L, morning[0])
        assertEquals(startOfDay + 10 * 3_600_000L, morning[1])
        val afternoon = pipe.windowRange("AFTERNOON", day, 0)
        assertEquals(startOfDay + 10 * 3_600_000L, afternoon[0])
        assertEquals(startOfDay + 13 * 3_600_000L + 30 * 60_000L, afternoon[1])
        val evening = pipe.windowRange("EVENING", day, 0)
        assertEquals(startOfDay + 13 * 3_600_000L + 30 * 60_000L, evening[0])
        assertEquals(startOfDay + 22 * 3_600_000L, evening[1])
    }

    @Test
    fun `产出后清洗掉会话名与过短要点（A1）`() = runBlocking {
        db = InfoAgentDatabase.build(context, "pipe-sanitize.db")
        seed()
        val payload = """
        {"window":"MANUAL","generated_at":"2026-11-04T12:00:00+08:00",
         "wechat_points":[
           {"conv":"王总","point":"王总"},
           {"conv":"王总","point":"好"},
           {"conv":"王总","point":"王总说明天带合同"},
           {"conv":"王总","point":"下午三点带合同开会"}
         ],
         "memo_status":{"done":0,"pending_daily":[],"pending_temp":[]},
         "plan":{"timeline":[],"note":null},
         "recommendations":[],"tomorrow":null,"risks":[]}
        """.trimIndent()
        val run = pipeline(FakeClient(payload)).generate("MANUAL", manual = true)
        val points = run.brief!!.wechatPoints
        assertEquals("清洗后只应保留一条有效要点", 1, points.size)
        assertEquals("下午三点带合同开会", points.first().point)
    }

    @Test
    fun `窗口边界跟随自定义时点（收紧后）`() {
        db = InfoAgentDatabase.build(context, "pipe-bounds.db")
        val pipe = pipeline(FakeClient(""))
        val day = "2026-11-04"
        val startOfDay = java.time.LocalDate.parse(day)
            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val bounds = BriefingPipeline.WindowBounds(
            morningEnd = 9 * 60,
            afternoonEnd = 14 * 60,
            eveningEnd = 21 * 60,
        )
        val morning = pipe.windowRange("MORNING", day, startOfDay, bounds)
        assertEquals("上午窗口应从前一晚 21:00 开始", startOfDay - 3 * 3_600_000L, morning[0])
        assertEquals(startOfDay + 9 * 3_600_000L, morning[1])
        val afternoon = pipe.windowRange("AFTERNOON", day, startOfDay, bounds)
        assertEquals(startOfDay + 9 * 3_600_000L, afternoon[0])
        assertEquals(startOfDay + 14 * 3_600_000L, afternoon[1])
        val evening = pipe.windowRange("EVENING", day, startOfDay, bounds)
        assertEquals(startOfDay + 14 * 3_600_000L, evening[0])
        assertEquals(startOfDay + 21 * 3_600_000L, evening[1])
    }

    @Test
    fun `没有素材时给出可读提示而不调用 API`() = runBlocking {
        db = InfoAgentDatabase.build(context, "pipe-empty.db")
        val client = FakeClient(payload("MANUAL"))
        val run = pipeline(client).generate("MORNING")
        assertNull(run.brief)
        assertTrue(run.error!!.contains("素材"))
        assertEquals(0, client.calls)
    }
}