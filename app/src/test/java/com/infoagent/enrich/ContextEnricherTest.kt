package com.infoagent.enrich

import com.infoagent.agent.ApiConfig
import com.infoagent.agent.ChatMessage
import com.infoagent.agent.ChatResult
import com.infoagent.agent.AgentClient
import com.infoagent.data.db.InfoAgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 外部信息层的行为约束。
 *
 * 这些断言守的是「不能假装联网」这条底线：
 * 没 key 不查、查不到不留痕、同样的城市+日期只查一次（不烧用户额度）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContextEnricherTest {

    private lateinit var db: InfoAgentDatabase
    private val now = 1762400000000L // 2026-11-06 附近的固定时刻
    private val bizDate = "2026-11-06"

    private val apiConfig = ApiConfig(
        baseUrl = "https://api.example.com/v1",
        endpoint = "/chat/completions",
        apiKey = "k",
        model = "m",
    )

    @Before
    fun setUp() {
        db = InfoAgentDatabase.build(RuntimeEnvironment.getApplication(), "enrich-test.db")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun plannerClient(planJson: String): AgentClient = object : AgentClient {
        var calls = 0
        override suspend fun complete(
            config: ApiConfig,
            messages: List<ChatMessage>,
            onDelta: ((String) -> Unit)?,
        ): ChatResult {
            calls++
            return ChatResult(planJson, tokensIn = 100, tokensOut = 30)
        }

        override fun cancel() {}
    }

    private val weatherCfg = EnrichConfig(weatherKey = "wk", weatherHost = "devapi.qweather.com")

    @Test
    fun `未开启时一次外部请求都不发`() = runBlocking {
        var weatherCalled = 0
        val e = ContextEnricher(
            db = db,
            settings = settings(enabled = false, wx = weatherCfg),
            budget = com.infoagent.agent.BudgetGuard(db.agentDao()) { bizDate },
            weatherFacts = { _, _ -> weatherCalled++; listOf("不该出现") },
            clock = { now },
        )
        val facts = e.enrich(plannerClient("""{"places":[{"city":"杭州","date":"2026-11-07"}],"queries":[]}"""), apiConfig, "素材", bizDate)
        assertEquals(0, weatherCalled)
        assertTrue(facts.isEmpty)
        assertEquals("外部信息未开启", facts.error)
    }

    @Test
    fun `查到天气后同城同日第二次走缓存不重复请求`() = runBlocking {
        var weatherCalled = 0
        val e = ContextEnricher(
            db = db,
            settings = settings(true, weatherCfg),
            budget = com.infoagent.agent.BudgetGuard(db.agentDao()) { bizDate },
            weatherFacts = { _, place ->
                weatherCalled++
                listOf("${place.date} 杭州：多云，12~19℃，北风 3 级")
            },
            clock = { now },
        )
        val client = plannerClient("""{"places":[{"city":"杭州","date":"2026-11-07"}],"queries":[]}""")
        val first = e.enrich(client, apiConfig, "陈英钿明天去西湖", bizDate)
        assertTrue(first.ok)
        assertEquals(1, first.lines.size)
        assertTrue(first.lines[0].contains("12~19℃"))

        val second = e.enrich(client, apiConfig, "陈英钿明天去西湖", bizDate)
        assertEquals("缓存命中不应再调接口", 1, weatherCalled)
        assertTrue(second.ok)
        assertTrue(second.fromCache)
    }

    @Test
    fun `查询计划是垃圾时退回常住城市兜底`() = runBlocking {
        var asked: PlaceQuery? = null
        val e = ContextEnricher(
            db = db,
            settings = settings(true, weatherCfg.copy(homeCity = "杭州")),
            budget = com.infoagent.agent.BudgetGuard(db.agentDao()) { bizDate },
            weatherFacts = { _, p -> asked = p; listOf("兜底天气") },
            clock = { now },
        )
        val facts = e.enrich(plannerClient("模型今天不太聪明"), apiConfig, "素材", bizDate)
        assertEquals("杭州", asked?.city)
        assertEquals("2026-11-07", asked?.date)
        assertTrue(facts.ok)
    }

    @Test
    fun `城市名写错导致查不到时给出可读原因且不留假数据`() = runBlocking {
        val e = ContextEnricher(
            db = db,
            settings = settings(true, weatherCfg),
            budget = com.infoagent.agent.BudgetGuard(db.agentDao()) { bizDate },
            weatherFacts = { _, _ -> emptyList() },
            clock = { now },
        )
        val facts = e.enrich(
            plannerClient("""{"places":[{"city":"不存在的城市","date":"2026-11-07"}],"queries":[]}"""),
            apiConfig, "素材", bizDate,
        )
        assertTrue(facts.isEmpty)
        assertTrue(facts.error.orEmpty().contains("没查到"))
    }

    @Test
    fun `缺日期的地点不查天气`() {
        val e = ContextEnricher(
            db = db,
            settings = settings(true, weatherCfg),
            budget = com.infoagent.agent.BudgetGuard(db.agentDao()) { bizDate },
        )
        val plan = e.decodePlan("""{"places":[{"city":"杭州"}],"queries":["杭州 限行"]}""")
        assertEquals(0, plan!!.places.size)
        assertEquals(1, plan.queries.size)
    }

    @Test
    fun `非法 JSON 计划返回 null`() {
        val e = ContextEnricher(
            db = db,
            settings = settings(true, weatherCfg),
            budget = com.infoagent.agent.BudgetGuard(db.agentDao()) { bizDate },
        )
        assertNull(e.decodePlan("完全不是 JSON"))
    }

    private fun settings(enabled: Boolean, wx: EnrichConfig) = object : EnrichConfigSource {
        override suspend fun enabled() = enabled
        override suspend fun load() = wx
    }
}