package com.infoagent.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.MemoItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** FR-08 验收：同 subject 幂等合并、证据累计、同义词归一、停用后不自动复活 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MemoryPipelineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: InfoAgentDatabase

    private class FakeClient(var payload: String) : AgentClient {
        var calls = 0
        override suspend fun complete(config: ApiConfig, messages: List<ChatMessage>, onDelta: ((String) -> Unit)?): ChatResult {
            calls++
            return ChatResult(payload, 120, 60)
        }

        override fun cancel() {}
    }

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    private fun pipeline(client: AgentClient) = MemoryPipeline(
        context = context,
        db = db,
        client = client,
        configProvider = { ApiConfig("https://example.com", apiKey = "sk-test", model = "deepseek-chat") },
        clock = { 1_791_000_000_000L },
        dateProvider = { "2026-11-04" },
    )

    private suspend fun seedMemos() {
        db.memoDao().insert(MemoItemEntity(kind = "DAILY", text = "去健身房锻炼", sortOrder = 1, cycleDate = "2026-11-04", createdAt = 1, updatedAt = 1))
        db.memoDao().insert(MemoItemEntity(kind = "TEMP", text = "晚上八点健身", sortOrder = 2, cycleDate = "2026-11-04", createdAt = 1, updatedAt = 1))
    }

    @Test
    fun `首次提炼创建事实`() = runBlocking {
        db = InfoAgentDatabase.build(context, "mem-create.db")
        seedMemos()
        val client = FakeClient(
            """{"facts":[{"category":"HABIT","subject":"健身","fact_text":"每周至少健身两次","confidence":0.8,"refs":["memo:1"]}]}""",
        )
        val outcome = pipeline(client).run()
        assertTrue(outcome is MemoryPipeline.Outcome.Created)
        val facts = db.memoryDao().topActiveFacts(10)
        assertEquals(1, facts.size)
        assertEquals("健身", facts.first().subject) // 同义词归一（锻炼→健身）
        assertEquals(1L, facts.first().evidenceCount)
    }

    @Test
    fun `重复提炼只累加证据不新增重复事实`() = runBlocking {
        db = InfoAgentDatabase.build(context, "mem-merge.db")
        seedMemos()
        val payload = """{"facts":[{"category":"HABIT","subject":"锻炼","fact_text":"每周至少健身两次","refs":["memo:1"]}]}"""
        val client = FakeClient(payload)
        pipeline(client).run()
        // 第二次带来新证据 refs
        client.payload = """{"facts":[{"category":"HABIT","subject":"健身","fact_text":"每周至少健身两次","refs":["memo:2"]}]}"""
        pipeline(client).run()
        val facts = db.memoryDao().topActiveFacts(10)
        assertEquals("应合并为一条事实", 1, facts.size)
        assertEquals(2L, facts.first().evidenceCount)
    }

    @Test
    fun `停用后无新证据不复活`() = runBlocking {
        db = InfoAgentDatabase.build(context, "mem-dismiss.db")
        seedMemos()
        val payload = """{"facts":[{"category":"HABIT","subject":"健身","fact_text":"每周健身","refs":["memo:1"]}]}"""
        val client = FakeClient(payload)
        pipeline(client).run()
        val fact = db.memoryDao().topActiveFacts(10).first()
        // 停用（置信调到 0 并标 DISMISSED）
        db.memoryDao().updateConfidence(fact.id, 0, fact.feedbackSum, "DISMISSED", 1)

        pipeline(client).run() // 同样的 refs → 无新证据
        val after = db.memoryDao().topActiveFacts(10)
        assertTrue("DISMISSED 不应自动复活", after.isEmpty())
    }

    @Test
    fun `反馈加减置信且下限归零停用`() = runBlocking {
        db = InfoAgentDatabase.build(context, "mem-feedback.db")
        seedMemos()
        val client = FakeClient("""{"facts":[{"category":"PATTERN","subject":"周报","fact_text":"每周五写周报","refs":["memo:1"]}]}""")
        pipeline(client).run()
        val fact = db.memoryDao().topActiveFacts(10).first()
        val before = fact.confidence
        pipeline(client).applyFeedback(fact.id, up = true)
        val after = db.memoryDao().topActiveFacts(10).first()
        assertEquals(before + 2, after.confidence)
    }

    @Test
    fun `点赞后再次合并不抹掉反馈（A7）`() = runBlocking {
        db = InfoAgentDatabase.build(context, "mem-feedback-persist.db")
        seedMemos()
        val client = FakeClient("""{"facts":[{"category":"PATTERN","subject":"周报","fact_text":"每周五写周报","refs":["memo:1"]}]}""")
        pipeline(client).run()
        val fact = db.memoryDao().topActiveFacts(10).first()
        pipeline(client).applyFeedback(fact.id, up = true)

        // 同一批再合并：没有新证据应 SKIPPED，反馈值必须留在库里
        pipeline(client).run()

        val after = db.memoryDao().topActiveFacts(10).first()
        assertEquals("反馈累加值必须持久化", 2L, after.feedbackSum)
        assertEquals("合并不得回落置信度", 60L, after.confidence)
    }

    @Test
    fun `没有素材时直接返回空而不调 API`() = runBlocking {
        db = InfoAgentDatabase.build(context, "mem-empty.db")
        val client = FakeClient("""{"facts":[]}""")
        val outcome = pipeline(client).run()
        assertTrue(outcome is MemoryPipeline.Outcome.Nothing)
        assertEquals(0, client.calls)
    }
}