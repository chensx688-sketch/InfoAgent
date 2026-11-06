package com.infoagent.enrich

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 天气/搜索客户端：用 MockWebServer 打真实 HTTP 解析路径。
 *
 * 为什么要这么测：这两层是最容易「静默错」的地方——字段名写错、日期对不上、code 不是 200，
 * 都会让简报里出现「查到了但其实是空的」，用户完全看不出来。断言必须落在**拼出来的那句话**上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalClientsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun cfg() = EnrichConfig(
        weatherKey = "wk",
        weatherHost = "http://${server.hostName}:${server.port}",
        searchKey = "sk",
        searchEndpoint = "http://${server.hostName}:${server.port}/search",
    )

    @Test
    fun `实况与目标日预报只取当天那条`() {
        // geo
        server.enqueue(json("""{"code":"200","location":[{"id":"101210101","name":"杭州"}]}"""))
        // now
        server.enqueue(
            json(
                """{"code":"200","now":{"text":"多云","temp":"15","feelsLike":"13","windDir":"北风","windScale":"3"}}""",
            ),
        )
        // 3d：只有 11-07 那条应该被采用
        server.enqueue(
            json(
                """{"code":"200","daily":[
                    {"fxDate":"2026-11-06","textDay":"晴","textNight":"晴","tempMin":"10","tempMax":"20","windDirDay":"北风","windScaleDay":"2","pop":"0","precip":"0.0"},
                    {"fxDate":"2026-11-07","textDay":"多云","textNight":"小雨","tempMin":"12","tempMax":"19","windDirDay":"东北风","windScaleDay":"3","pop":"60","precip":"1.2"}
                ]}""",
            ),
        )
        // indices
        server.enqueue(
            json(
                """{"code":"200","daily":[
                    {"date":"2026-11-07","name":"穿衣","category":"较冷","text":"建议穿薄外套"},
                    {"date":"2026-11-07","name":"紫外线","category":"强","text":"注意防晒"},
                    {"date":"2026-11-07","name":"空气污染扩散","category":"良","text":"无关项"}
                ]}""",
            ),
        )

        val facts = QWeatherClient().facts(cfg(), PlaceQuery("杭州", "2026-11-07"))
        assertEquals(4, facts.size)
        assertTrue("要带实况温度" + facts, facts[0].contains("多云 15℃"))
        assertTrue("预报要取 11-07 而不是 11-06：" + facts[1], facts[1].contains("12~19℃"))
        assertTrue("降水概率要能读到：" + facts[1], facts[1].contains("60%"))
        assertTrue("生活指数只留相关的：" + facts.toString(), facts.any { it.contains("穿衣") })
        assertTrue("不相关指数要滤掉", facts.none { it.contains("空气污染") })
    }

    @Test
    fun `城市查不到时返回空而不抛异常`() {
        server.enqueue(json("""{"code":"404","location":[]}"""))
        val facts = QWeatherClient().facts(cfg(), PlaceQuery("不存在", "2026-11-07"))
        assertTrue(facts.isEmpty())
    }

    @Test
    fun `搜索命中时带来源域名`() {
        server.enqueue(
            json(
                """{"answer":"西湖景区周末不限行，但周边停车位紧张，建议地铁 1 号线龙翔桥站。",
                    "results":[{"title":"杭州西湖出行提示","url":"https://example.gov.cn/tip","content":"..."}]}""",
            ),
        )
        val line = SearchClient().search(cfg(), "杭州西湖 周末 限行")
        assertTrue(line!!.contains("不限行"))
        assertTrue("要标来源，用户才能自己核：" + line, line.contains("example.gov.cn"))
    }

    @Test
    fun `搜索 429 时返回 null 而不是半截数据`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate limited"}"""))
        assertNull(SearchClient().search(cfg(), "任意查询"))
    }

    @Test
    fun `外部事实拼成 prompt 块时逐行带 -`() {
        assertEquals("", ExternalFacts().toPromptBlock())
        val block = ExternalFacts(lines = listOf("明天杭州 12~19℃", "周末不限行")).toPromptBlock()
        assertEquals("- 明天杭州 12~19℃\n- 周末不限行", block)
    }

    private fun json(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}