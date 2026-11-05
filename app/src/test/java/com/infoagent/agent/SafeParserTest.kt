package com.infoagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json 需要 Robolectric 提供的真实现（纯 JVM 会命中 android.jar 桩） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeParserTest {

    private val validBrief = """
{
  "window": "MORNING",
  "generated_at": "2026-11-04T10:00:00+08:00",
  "wechat_points": [
    {"conv": "王总", "point": "下午三点开会，要带合同", "key_time": "15:00"},
    {"conv": "妈妈", "point": "周末回家吃饭", "key_time": null}
  ],
  "memo_status": {
    "done": 1,
    "pending_daily": [{"text": "健身", "time": "18:30"}],
    "pending_temp": [{"text": "买猫粮", "time": null}]
  },
  "plan": {
    "timeline": [
      {"time": "10:30", "title": "处理邮件", "source": "memo", "detail": null, "kind": "must", "date": null},
      {"time": "15:00", "title": "开会（带合同）", "source": "chat", "detail": "王总", "kind": "must", "date": null}
    ],
    "note": "上午先清简单事项"
  },
  "recommendations": [
    {"text": "开会前 10 分钟整理合同", "reason": "上周开会忘带材料", "memory_refs": [3]}
  ],
  "tomorrow": {"todos": [{"title": "交周报", "time": "18:00"}]},
  "risks": ["下午会议与健身时间接近"]
}
""".trimIndent()

    @Test
    fun `完整合法简报解析成功`() {
        val r = SafeParser.parseBrief(validBrief, "MORNING")
        assertTrue(r is SafeParser.Result.Ok)
        val brief = (r as SafeParser.Result.Ok).value
        assertEquals(2, brief.wechatPoints.size)
        assertEquals("must", brief.plan.timeline.first().kind)
        assertEquals(1, brief.recommendations.size)
        assertEquals(listOf(3L), brief.recommendations.first().memoryRefs)
        assertEquals(1, brief.tomorrow?.todos?.size)
        assertTrue(brief.warnings.isEmpty())
    }

    @Test
    fun `Markdown 围栏与前后噪声可剥离`() {
        val raw = "好的，以下是简报：\n```json\n$validBrief\n```\n希望有帮助"
        val r = SafeParser.parseBrief(raw)
        assertTrue(r is SafeParser.Result.Ok)
    }

    @Test
    fun `非 JSON 直接失败（回退 Markdown）`() {
        val r = SafeParser.parseBrief("抱歉我无法完成")
        assertTrue(r is SafeParser.Result.Failed)
    }

    @Test
    fun `缺少任何必填块即失败`() {
        val noMemo = validBrief.replace(Regex("\"memo_status\"[\\s\\S]*?\\},\\s*\"plan\""), "\"plan\"")
        assertTrue(SafeParser.parseBrief(noMemo) is SafeParser.Result.Failed)

        val noGenerated = validBrief.replace(Regex("\"generated_at\": \"[^\"]+\","), "")
        assertTrue(SafeParser.parseBrief(noGenerated) is SafeParser.Result.Failed)
    }

    @Test
    fun `非法枚举的时间线条目被丢弃并记录警告`() {
        val bad = validBrief.replace("\"kind\": \"must\", \"date\": null},\n      {\"time\": \"15:00\"", "\"kind\": \"CRITICAL\", \"date\": null},\n      {\"time\": \"15:00\"")
        val r = SafeParser.parseBrief(bad)
        assertTrue(r is SafeParser.Result.Ok)
        val brief = (r as SafeParser.Result.Ok).value
        assertEquals(1, brief.plan.timeline.size)
        assertTrue(brief.warnings.any { it.contains("kind") })
    }

    @Test
    fun `缺失 title 的时间线条目被丢弃`() {
        val raw = """
        {"window":"MORNING","generated_at":"x","wechat_points":[],"memo_status":{"done":0},"plan":{"timeline":[{"time":"10:00"}]}}
        """.trimIndent()
        val r = SafeParser.parseBrief(raw)
        assertTrue(r is SafeParser.Result.Ok)
        assertTrue((r as SafeParser.Result.Ok).value.plan.timeline.isEmpty())
    }

    @Test
    fun `自动整理解析与 source_msg_ids`() {
        val raw = """
        {"items":[{"title":"明天下午三点带合同开会","due":"2026-11-05T15:00:00+08:00","urgency":"urgent","source_msg_ids":[12,13]},{"title":""}],"skip":false}
        """.trimIndent()
        val r = SafeParser.parseAutoMemo(raw)
        assertTrue(r is SafeParser.Result.Ok)
        val items = (r as SafeParser.Result.Ok).value
        assertEquals(1, items.size)
        assertEquals(listOf(12L, 13L), items.first().sourceMsgIds)
        assertEquals("urgent", items.first().urgency)
    }

    @Test
    fun `记忆解析与非法 period 忽略`() {
        val raw = """
        {"facts":[
          {"category":"HABIT","subject":"健身","fact_text":"每周三晚上健身","confidence":0.8,"period":{"freq":"WEEKLY","weekday":3,"time":"20:00"},"refs":["memo:1"]},
          {"category":"PATTERN","subject":"x","fact_text":"描述","confidence":2.0,"period":{"freq":"HOURLY"}}
        ]}
        """.trimIndent()
        val r = SafeParser.parseMemory(raw)
        assertTrue(r is SafeParser.Result.Ok)
        val facts = (r as SafeParser.Result.Ok).value
        assertEquals(2, facts.size)
        assertEquals(3, facts.first().period?.weekday)
        assertEquals(null, facts[1].period) // 非法 freq 忽略 period，但事实保留
        assertEquals(1.0, facts[1].confidence, 0.0001) // 置信度夹紧
    }
}