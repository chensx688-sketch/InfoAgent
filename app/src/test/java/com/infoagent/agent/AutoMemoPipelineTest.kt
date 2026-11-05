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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** FR-06 验收：自动整理生成 AI 备忘（来源/多来源回跳/去重/批次幂等） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AutoMemoPipelineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: InfoAgentDatabase

    private class FakeClient(var payload: String) : AgentClient {
        var calls = 0
        override suspend fun complete(config: ApiConfig, messages: List<ChatMessage>, onDelta: ((String) -> Unit)?): ChatResult {
            calls++
            return ChatResult(payload, 80, 40)
        }

        override fun cancel() {}
    }

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    private fun pipeline(client: AgentClient, date: String = "2026-11-04") = AutoMemoPipeline(
        db = db,
        client = client,
        configProvider = { ApiConfig("https://example.com", apiKey = "sk-test", model = "deepseek-chat") },
        clock = { 1_791_000_000_000L },
        dateProvider = { date },
    )

    private suspend fun seedTwoMessages() {
        val repo = MessageRepository(db)
        val base = 1_791_000_000_000L
        repo.ingest(IncomingMessage("wechat", "王总", "王总", "王总", "IN", "text", "明天下午三点开会，记得带合同", base - 60_000))
        repo.ingest(IncomingMessage("wechat", "家人群", "家人群", "妈妈", "IN", "text", "周末回家吃饭", base - 30_000, isGroup = true))
    }

    @Test
    fun `抽取事项落为 AI 备忘并标注来源与多来源回跳`() = runBlocking {
        db = InfoAgentDatabase.build(context, "automemo-ok.db")
        seedTwoMessages()
        val msgs = db.messageDao().messagesSince(0, 10)
        val first = msgs.first()
        val payload = """
        {"items":[{"title":"明天下午三点带合同开会","due":"2026-11-05T15:00:00+08:00","urgency":"urgent","source_msg_ids":[${first.id}]}],"skip":false}
        """.trimIndent()
        val client = FakeClient(payload)

        val outcome = pipeline(client).run()
        assertTrue(outcome is AutoMemoPipeline.Outcome.Created)
        assertEquals(1, client.calls)

        val memos = db.memoDao().list("2026-11-04", "TEMP")
        assertEquals(1, memos.size)
        val memo = memos.first()
        assertEquals("AI", memo.origin)
        assertEquals("王总", memo.sourceConv)
        assertEquals("wechat", memo.sourceApp)
        assertEquals(first.id, memo.sourceMsgId)
        assertTrue(memo.assignedTime != null)
        assertEquals(1, db.memoDao().sourcesOfMemo(memo.id).size)

        // 同批次再跑：幂等不重复调用、不重复落库
        val second = pipeline(client).run()
        assertTrue(second is AutoMemoPipeline.Outcome.Skipped || second is AutoMemoPipeline.Outcome.Nothing)
        assertEquals(1, client.calls)
        assertEquals(1, db.memoDao().list("2026-11-04", "TEMP").size)
    }

    @Test
    fun `标题规范化去重避免重复待办`() = runBlocking {
        db = InfoAgentDatabase.build(context, "automemo-dup.db")
        seedTwoMessages()
        // 已存在一条相同语义的手动备忘
        db.memoDao().insert(
            MemoItemEntity(kind = "TEMP", text = "明天下午三点带合同开会！", sortOrder = 1, cycleDate = "2026-11-04", createdAt = 1, updatedAt = 1),
        )
        val client = FakeClient(
            """{"items":[{"title":"明天下午三点带合同开会","source_msg_ids":[]}],"skip":false}""",
        )
        pipeline(client).run()
        // 原有 1 条 + 不新增重复
        assertEquals(1, db.memoDao().list("2026-11-04", "TEMP").size)
    }

    @Test
    fun `无新消息时不调用 API`() = runBlocking {
        db = InfoAgentDatabase.build(context, "automemo-none.db")
        val client = FakeClient("""{"items":[],"skip":true}""")
        val outcome = pipeline(client).run()
        assertTrue(outcome is AutoMemoPipeline.Outcome.Nothing)
        assertEquals(0, client.calls)
    }

    @Test
    fun `响应缺少 items 时按失败处理且不推进水位（A2）`() = runBlocking {
        db = InfoAgentDatabase.build(context, "automemo-noitems.db")
        seedTwoMessages()
        val client = FakeClient("""{"ok":true}""")
        val first = pipeline(client).run()
        assertTrue("缺 items 必须走 Failed", first is AutoMemoPipeline.Outcome.Failed)
        val job = db.agentDao().jobsByState("FAILED", 5).first()
        assertTrue("失败批次不能写 maxMsgId（否则消息永久丢弃）", !job.resultJson.orEmpty().contains("maxMsgId"))

        // 再跑一次仍应处理同一批消息，而不是把它们当成已处理
        val second = pipeline(client).run()
        assertTrue(second is AutoMemoPipeline.Outcome.Failed)
        assertEquals(2, client.calls)
    }

    @Test
    fun `同一批连续失败三次后进入死信并推进水位（A3）`() = runBlocking {
        db = InfoAgentDatabase.build(context, "automemo-dead.db")
        seedTwoMessages()
        val client = FakeClient("""{"ok":true}""")
        repeat(3) { pipeline(client).run() }

        val job = db.agentDao().jobsByState("FAILED", 5).first()
        val json = org.json.JSONObject(job.resultJson.orEmpty())
        assertTrue("第 3 次应打 dead 标记", json.optBoolean("dead", false))
        assertTrue("死信必须写 maxMsgId 以推进水位", json.optLong("maxMsgId", 0L) > 0L)

        // 水位已推进：没有新消息时不再调用模型
        val callsBefore = client.calls
        val outcome = pipeline(client).run()
        assertTrue(outcome is AutoMemoPipeline.Outcome.Nothing)
        assertEquals(callsBefore, client.calls)
    }
}