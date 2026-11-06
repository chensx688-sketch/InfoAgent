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

    /**
     * v1.4.3 改的：window / generated_at 由客户端注入，模型不必回显。
     * 原来看模型回显再校验，等于把「模型不知道时间」的锅算到解析头上，纯属自找失败。
     */
    @Test
    fun `模型不回 window 和 generated_at 也能解析，取值以请求为准`() {
        val noWindow = validBrief.replace("\"window\": \"MORNING\",", "")
        val r1 = SafeParser.parseBrief(noWindow, expectedWindow = "MANUAL")
        assertTrue("应当照样可用：" + r1, r1 is SafeParser.Result.Ok)
        assertEquals("MANUAL", (r1 as SafeParser.Result.Ok).value.window)

        val noGenerated = validBrief.replace(Regex("\"generated_at\": \"[^\"]+\","), "")
        val r2 = SafeParser.parseBrief(noGenerated, expectedWindow = "MORNING")
        assertTrue(r2 is SafeParser.Result.Ok)
        assertTrue("要补一个真实时间戳", (r2 as SafeParser.Result.Ok).value.generatedAt.isNotBlank())
    }

    @Test
    fun `模型回的 window 与请求不符时以请求为准`() {
        val r = SafeParser.parseBrief(validBrief, expectedWindow = "EVENING")
        assertTrue(r is SafeParser.Result.Ok)
        val brief = (r as SafeParser.Result.Ok).value
        assertEquals("EVENING", brief.window)
        assertTrue("要留痕说明模型写错了", brief.warnings.any { it.contains("window") })
    }

    /**
     * 这条规则是 1.4.2 改的：memo_status / plan 在 JSON 里排在后面，被 max_tokens 截断时最先丢，
     * 但它们只是「已有备忘快照」和「时间线」——为了它们丢掉已经算好的怎么办/要点是不划算的。
     */
    @Test
    fun `缺备忘快照和时间线时降级为空而不是整份丢弃`() {
        val noMemo = validBrief.replace(Regex("\"memo_status\"[\\s\\S]*?\\},\\s*\"plan\""), "\"plan\"")
        val r = SafeParser.parseBrief(noMemo)
        assertTrue("应当仍然可用：" + r, r is SafeParser.Result.Ok)
        r as SafeParser.Result.Ok
        assertEquals(0, r.value.memoStatus.done)
        assertTrue("要留痕告诉用户缺了什么", r.warnings.any { it.contains("备忘快照") })
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

    @Test
    fun `brief_v2 解析怎么办与消息编号`() {
        val raw = """
        {"window":"MANUAL","generated_at":"2026-11-06T12:00:00+08:00",
         "advice":[
           {"who":"陈英钿","ask":"要不要从西湖带东西","say":"不用带啦，我这边都有。你路上注意安全，到了发个定位。",
            "why":"她只是顺口问一句，不必让她绕路","watch":["11 月西湖边风大湿冷，提醒她带外套","问清下午几点出发，别让她赶返程高峰"],
            "kind":"reply","due":"今天中午前","basis":"常识","refs":[11,12]},
           {"who":"财务","ask":"合同要今天盖章","kind":"prepare","basis":"原文"},
           {"who":"","kind":"reply"}
         ],
         "wechat_points":[{"conv":"陈英钿","point":"明天去西湖，问要不要带东西","key_time":"09:20","refs":[11]}],
         "memo_status":{"done":0,"pending_daily":[],"pending_temp":[]},
         "plan":{"timeline":[]},"recommendations":[],"risks":[]}
        """.trimIndent()
        val r = SafeParser.parseBrief(raw, "MANUAL")
        assertTrue(r is SafeParser.Result.Ok)
        val brief = (r as SafeParser.Result.Ok).value

        // 缺 ask 的那条被丢弃，剩下 2 条
        assertEquals(2, brief.advice.size)
        val first = brief.advice[0]
        assertEquals("陈英钿", first.who)
        assertEquals("reply", first.kind)
        assertEquals("今天中午前", first.due)
        assertEquals("常识", first.basis)
        assertEquals(2, first.watch.size)
        assertEquals(listOf(11L, 12L), first.refs)
        assertTrue("回复草稿要能直接发", first.say!!.startsWith("不用带"))
        // 老字段不受影响
        assertEquals(listOf(11L), brief.wechatPoints.first().refs)
    }

    @Test
    fun `没有 advice 字段的旧简报仍能解析`() {
        val raw = """
        {"window":"MORNING","generated_at":"2026-11-06T10:00:00+08:00",
         "wechat_points":[{"conv":"王总","point":"三点开会"}],
         "memo_status":{"done":0,"pending_daily":[],"pending_temp":[]},
         "plan":{"timeline":[]},"recommendations":[],"risks":[]}
        """.trimIndent()
        val r = SafeParser.parseBrief(raw, "MORNING")
        assertTrue(r is SafeParser.Result.Ok)
        assertTrue((r as SafeParser.Result.Ok).value.advice.isEmpty())
    }

    // ---------- 截断自愈（1.4.2）：模型把最后一句写到一半就没了 ----------

    /** 真实截断形态：字符串中途断掉，后面的括号全没有 */
    private val truncated = """
        {"window":"MANUAL","generated_at":"2026-11-04T10:00:00+08:00",
         "headline":"今天要回王总的报价确认",
         "wechat_points":[{"conv":"王总","point":"报价单已发，等确认折扣","key_time":"15:00","refs":[1]}],
         "advice":[{"who":"王总","ask":"确认折扣并回签","say":"折扣按 92 折走，我下午回签",
                    "why":"合同周五前要归档","basis":"原文","watch":["周五前扫描件要一起给
    """.trimIndent()

    @Test
    fun `被截断的简报能补全并给出提醒`() {
        // 严格解析确实失败（这才是用户看到的「JSON 解析失败」）
        assertTrue(SafeParser.parseBrief(truncated, "MANUAL") is SafeParser.Result.Failed)

        val r = SafeParser.parseBriefLenient(truncated, "MANUAL")
        assertTrue("补全后应当能解析", r is SafeParser.Result.Ok)
        r as SafeParser.Result.Ok
        assertEquals(1, r.value.wechatPoints.size)
        assertEquals("确认折扣并回签", r.value.advice.single().ask)
        assertTrue("要告诉用户这是补出来的", r.warnings.any { it.contains("截断") })
    }

    @Test
    fun `本来合法的 JSON 不走补全`() {
        assertTrue(SafeParser.repairTruncated(validBrief) == null)
    }

    @Test
    fun `完全不是 JSON 时补全也无能为力`() {
        assertTrue(SafeParser.repairTruncated("模型今天不想干活，直接说人话了") == null)
        assertTrue(
            SafeParser.parseBriefLenient("模型今天不想干活", "MANUAL") is SafeParser.Result.Failed,
        )
    }

    @Test
    fun `空内容给的是「空内容」而不是 JSON 错误`() {
        val r = SafeParser.parseBriefLenient("   ", "MANUAL")
        assertTrue(r is SafeParser.Result.Failed)
        assertTrue((r as SafeParser.Result.Failed).reason.contains("空内容"))
    }
}