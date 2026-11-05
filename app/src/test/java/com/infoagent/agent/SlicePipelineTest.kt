package com.infoagent.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infoagent.data.db.InfoAgentDatabase
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

/**
 * v3 时间轴验收：片段首尾相接、间隔不足 5 分钟跳过、seq 递增。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SlicePipelineTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: InfoAgentDatabase
    private var now: Long = java.time.LocalDate.parse("2026-11-04")
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 10 * 3_600_000L

    private class FakeClient(var payload: String) : AgentClient {
        var calls = 0
        override suspend fun complete(
            config: ApiConfig,
            messages: List<ChatMessage>,
            onDelta: ((String) -> Unit)?,
        ): ChatResult {
            calls++
            return ChatResult(payload, tokensIn = 80, tokensOut = 40)
        }

        override fun cancel() {}
    }

    private val payload = """
        {"window":"MANUAL","generated_at":"2026-11-04T10:00:00+08:00",
         "wechat_points":[{"conv":"王总","point":"下午三点开会","key_time":"15:00"}],
         "memo_status":{"done":0,"pending_daily":[],"pending_temp":[]},
         "plan":{"timeline":[]},"recommendations":[],"tomorrow":null,"risks":[]}
    """.trimIndent()

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    private fun pipeline(client: AgentClient): BriefingPipeline = BriefingPipeline(
        db = db,
        client = client,
        configProvider = { ApiConfig(baseUrl = "https://example.com", apiKey = "sk-test", model = "m") },
        clock = { now },
        dateProvider = { "2026-11-04" },
    )

    private suspend fun seed() {
        MessageRepository(db).ingest(
            IncomingMessage("wechat", "王总", "王总", "王总", "IN", "text", "三点开会", now - 600_000L),
        )
    }

    @Test
    fun `两条片段首尾相接且 seq 递增`() = runBlocking {
        db = InfoAgentDatabase.build(context, "slice-continuity.db")
        val client = FakeClient(payload)
        seed()
        val first = pipeline(client).generateSlice("MORNING")
        assertEquals("OK", first.state)
        assertEquals(1, first.seq)

        now += 60 * 60_000L
        seed()
        val second = pipeline(client).generateSlice("MANUAL")
        assertEquals("OK", second.state)
        assertEquals(2, second.seq)
        assertEquals("第二条的起点必须等于第一条的终点", first.toTs, second.fromTs)
        assertTrue(second.toTs > second.fromTs)
    }

    @Test
    fun `间隔不足5分钟直接跳过且不调用模型`() = runBlocking {
        db = InfoAgentDatabase.build(context, "slice-toosoon.db")
        val client = FakeClient(payload)
        seed()
        val first = pipeline(client).generateSlice("MORNING")
        assertEquals("OK", first.state)
        val callsAfterFirst = client.calls

        val tooSoon = pipeline(client).generateSlice("MANUAL")
        assertEquals("SKIPPED", tooSoon.state)
        assertEquals("间隔不足时不应调用模型", callsAfterFirst, client.calls)
        assertTrue(tooSoon.error.orEmpty().contains("5 分钟"))
    }
}