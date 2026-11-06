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
        var lastPrompt: String = ""
        /** 只看 user 消息：system 提示词里也提到「外部信息」，不能拿整串判断 */
        var lastUserPrompt: String = ""
        override suspend fun complete(
            config: ApiConfig,
            messages: List<ChatMessage>,
            onDelta: ((String) -> Unit)?,
        ): ChatResult {
            calls++
            lastPrompt = messages.joinToString("\n") { it.content }
            lastUserPrompt = messages.filter { it.role == "user" }.joinToString("\n") { it.content }
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

    private suspend fun seed2() {
        MessageRepository(db).ingest(
            IncomingMessage("wechat", "李总", "李总", "李总", "IN", "text", "报价单已发你", now - 60_000L),
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

        // 自动触发（非手动）才受 5 分钟防抖约束
        val tooSoon = pipeline(client).generateSlice("AFTERNOON")
        assertEquals("SKIPPED", tooSoon.state)
        assertEquals("间隔不足时不应调用模型", callsAfterFirst, client.calls)
        assertTrue(tooSoon.error.orEmpty().contains("5 分钟"))
    }

    @Test
    fun `手动补一份不受5分钟防抖限制`() = runBlocking {
        db = InfoAgentDatabase.build(context, "slice-manual-force.db")
        val client = FakeClient(payload)
        seed()
        assertEquals("OK", pipeline(client).generateSlice("MORNING").state)
        val callsAfterFirst = client.calls

        // 用户显式点击：即使 1 分钟不到也必须真的跑一次，否则界面上就是「点了没反应」
        seed2() // 有新消息，才有东西可总结
        val manual = pipeline(client).generateSlice("MANUAL", force = true)
        assertEquals("OK", manual.state)
        assertTrue("手动必须真的调用模型", client.calls > callsAfterFirst)
    }

    @Test
    fun `时间戳比上一条片段更早的新消息也会被读到`() = runBlocking {
        db = InfoAgentDatabase.build(context, "slice-late-arrival.db")
        val client = FakeClient(payload)

        // 第一条片段覆盖到 10:00 之前
        seed()
        assertEquals("OK", pipeline(client).generateSlice("MORNING").state)

        // 新消息晚到，而且时间戳被系统写成 09:00（早于上一条片段的终点）——
        // 按时间窗切的话它会被永久挡在水位线之外，正是「再补一份还是旧内容」的成因
        now += 60 * 60_000L
        MessageRepository(db).ingest(
            IncomingMessage("wechat", "李总", "李总", "李总", "IN", "text", "合同今天下午必须签", now - 2 * 3_600_000L),
        )

        val second = pipeline(client).generateSlice("MANUAL", force = true)
        assertEquals("OK", second.state)
        assertTrue(
            "迟到的新消息必须进入本轮素材（按消息 id 增量）",
            client.lastPrompt.contains("合同今天下午必须签"),
        )
    }

    @Test
    fun `没有新消息时跳过原因写清覆盖区间与今日条数`() = runBlocking {
        db = InfoAgentDatabase.build(context, "slice-empty-reason.db")
        val client = FakeClient(payload)
        seed()
        assertEquals("OK", pipeline(client).generateSlice("MORNING").state)

        val empty = pipeline(client).generateSlice("MANUAL", force = true)
        assertEquals("SKIPPED", empty.state)
        val reason = empty.error.orEmpty()
        assertTrue("要说清是哪一段：" + reason, reason.contains("这一段没有新消息"))
        assertTrue("要给出今日总条数：" + reason, reason.contains("今日共"))
    }

    @Test
    fun `失败或跳过的片段不推进水位`() = runBlocking {
        db = InfoAgentDatabase.build(context, "slice-no-advance.db")
        val bad = FakeClient("这不是 JSON")
        seed()
        assertEquals("OK", pipeline(FakeClient(payload)).generateSlice("MORNING").state)

        // 新消息 + 解析失败 → 该批消息必须留给下一次，不能被判成"已覆盖"
        seed2()
        val failed = pipeline(bad).generateSlice("MANUAL", force = true)
        assertEquals("FAILED", failed.state)

        val good = FakeClient(payload)
        val retry = pipeline(good).generateSlice("MANUAL", force = true)
        assertEquals("OK", retry.state)
        assertTrue("失败片段覆盖过的消息必须还能被读到", good.lastPrompt.contains("报价单已发你"))
    }

    @Test
    fun `设为不采集的会话历史也不进素材`() = runBlocking {
        db = InfoAgentDatabase.build(context, "slice-muted-peer.db")
        val client = FakeClient(payload)
        seed()
        MessageRepository(db).ingest(
            IncomingMessage("wechat", "公众号群", "公众号群", "某号", "IN", "text", "广告：限时秒杀", now - 500_000L, isGroup = true),
        )
        db.messageDao().setConversationMuted("公众号群", 1L)

        val run = pipeline(client).generateSlice("MANUAL", force = true)
        assertEquals("OK", run.state)
        assertTrue("不该采集的会话不能进 prompt", !client.lastPrompt.contains("限时秒杀"))
        assertTrue("其他会话照旧", client.lastPrompt.contains("三点开会"))
    }

    @Test
    fun `外部信息会进 prompt 并落库`() = runBlocking {
        db = InfoAgentDatabase.build(context, "slice-external.db")
        val client = FakeClient(payload)
        seed()
        val fake = object : com.infoagent.enrich.Enricher {
            override suspend fun enrich(
                client: com.infoagent.agent.AgentClient?,
                config: com.infoagent.agent.ApiConfig?,
                wechatLines: String,
                bizDate: String,
            ) = com.infoagent.enrich.ExternalFacts(
                lines = listOf("2026-11-07 杭州：多云转小雨，12~19℃，降水概率 60%"),
                ok = true,
            )
        }
        val p = com.infoagent.agent.BriefingPipeline(
            db, client, { com.infoagent.agent.ApiConfig("https://a/v1", "/chat/completions", "k", "m") },
            clock = { now }, dateProvider = { "2026-11-04" },
        ).withEnricher(fake)

        val run = p.generateSlice("MANUAL", force = true)
        assertEquals("OK", run.state)
        assertTrue("外部事实必须进 prompt", client.lastUserPrompt.contains("12~19℃"))
        assertTrue("要标明来源节", client.lastUserPrompt.contains("【外部信息】"))
        val row = db.briefSliceDao().latestOk("2026-11-04")
        assertTrue("参考了什么要能追溯", row?.contextJson.orEmpty().contains("12~19℃"))
    }

    @Test
    fun `没有外部信息时 prompt 不出现那一节`() = runBlocking {
        db = InfoAgentDatabase.build(context, "slice-no-external.db")
        val client = FakeClient(payload)
        seed()
        val empty = object : com.infoagent.enrich.Enricher {
            override suspend fun enrich(
                client: com.infoagent.agent.AgentClient?,
                config: com.infoagent.agent.ApiConfig?,
                wechatLines: String,
                bizDate: String,
            ) = com.infoagent.enrich.ExternalFacts()
        }
        val p = com.infoagent.agent.BriefingPipeline(
            db, client, { com.infoagent.agent.ApiConfig("https://a/v1", "/chat/completions", "k", "m") },
            clock = { now }, dateProvider = { "2026-11-04" },
        ).withEnricher(empty)
        assertEquals("OK", p.generateSlice("MANUAL", force = true).state)
        assertTrue("查不到就不许出现外部信息节", !client.lastUserPrompt.contains("【外部信息】"))
    }

    // ---------- 截断/不乱答（1.4.2）：用户报的「响应不是 json / 解析失败」 ----------

    /** 按顺序返回不同内容的假客户端，用来验「截断→加长重试」 */
    private class ScriptedClient(
        private val script: List<Triple<String, String?, Long>>, // content, finishReason, maxTokens 记录
    ) : AgentClient {
        var calls = 0
        var lastMaxTokens = -1
        override suspend fun complete(
            config: ApiConfig,
            messages: List<ChatMessage>,
            onDelta: ((String) -> Unit)?,
        ): ChatResult {
            val idx = calls.coerceAtMost(script.lastIndex)
            calls++
            lastMaxTokens = config.maxTokens
            val (content, finish, _) = script[idx]
            return ChatResult(content, tokensIn = 80, tokensOut = 40, finishReason = finish)
        }

        override fun cancel() {}
    }

    @Test
    fun `被截断时自动加长重试一次并采用更完整的结果`() = runBlocking {
        db = InfoAgentDatabase.build(context, "slice-truncate.db")
        // 砍到连 window/generated_at 都补不回来的程度：这才是「补不回来，必须加长重试」
        val cut = payload.substring(0, 30)
        val client = ScriptedClient(
            listOf(Triple(cut, "length", 2048L), Triple(payload, "stop", 2048L)),
        )
        seed()
        val p = com.infoagent.agent.BriefingPipeline(
            db, client,
            { com.infoagent.agent.ApiConfig("https://a/v1", "/chat/completions", "k", "m", maxTokens = 2048) },
            clock = { now }, dateProvider = { "2026-11-04" },
        )
        val run = p.generateSlice("MANUAL", force = true)
        assertEquals("OK", run.state)
        assertEquals("应当重试一次", 2, client.calls)
        assertEquals("重试要把 max_tokens 翻倍", 4096, client.lastMaxTokens)
    }

    @Test
    fun `模型返回空内容时给出可操作的说明`() = runBlocking {
        db = InfoAgentDatabase.build(context, "slice-empty.db")
        val client = ScriptedClient(listOf(Triple("", "stop", 2048L)))
        seed()
        val p = com.infoagent.agent.BriefingPipeline(
            db, client,
            { com.infoagent.agent.ApiConfig("https://a/v1", "/chat/completions", "k", "m") },
            clock = { now }, dateProvider = { "2026-11-04" },
        )
        val run = p.generateSlice("MANUAL", force = true)
        assertEquals("FAILED", run.state)
        assertTrue("要说人话：" + run.error, run.error.orEmpty().contains("空内容"))
    }

    @Test
    fun `模型说了人话时错误信息里带上它开头说了什么`() = runBlocking {
        db = InfoAgentDatabase.build(context, "slice-prose.db")
        val client = ScriptedClient(listOf(Triple("好的，我来帮你整理今天的消息：", "stop", 2048L)))
        seed()
        val p = com.infoagent.agent.BriefingPipeline(
            db, client,
            { com.infoagent.agent.ApiConfig("https://a/v1", "/chat/completions", "k", "m") },
            clock = { now }, dateProvider = { "2026-11-04" },
        )
        val run = p.generateSlice("MANUAL", force = true)
        assertEquals("FAILED", run.state)
        assertTrue("要把模型原话回显：" + run.error, run.error.orEmpty().contains("好的，我来帮你整理"))
    }
}