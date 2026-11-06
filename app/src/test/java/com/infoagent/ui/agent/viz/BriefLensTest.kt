package com.infoagent.ui.agent.viz

import com.infoagent.agent.model.Brief
import com.infoagent.agent.model.BriefPlan
import com.infoagent.agent.model.MemoStatus
import com.infoagent.agent.model.PendingMemo
import com.infoagent.agent.model.Recommendation
import com.infoagent.agent.model.TimelineItem
import com.infoagent.agent.model.WechatPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 简报可视化视图模型（纯逻辑）：时间解析、树状归属、时间带排序、计数。
 * 这些断言保证「图表画在正确的位置」，不依赖 Compose。
 */
class BriefLensTest {

    @Test
    fun `解析标准时间`() {
        assertEquals(900, BriefLenser.parseClock("15:00"))
        assertEquals(570, BriefLenser.parseClock("09:30"))
        assertEquals(0, BriefLenser.parseClock("00:00"))
    }

    @Test
    fun `解析下午与晚上的中文时间`() {
        assertEquals(900, BriefLenser.parseClock("下午 3:00"))
        assertEquals(1200, BriefLenser.parseClock("晚上8点"))
        assertEquals(555, BriefLenser.parseClock("上午9:15"))
    }

    @Test
    fun `跨天前缀不影响钟点，非法时间返回 null`() {
        assertEquals(570, BriefLenser.parseClock("明天 09:30"))
        assertNull(BriefLenser.parseClock("回头再说"))
        assertNull(BriefLenser.parseClock("25:00"))
        assertNull(BriefLenser.parseClock(null))
    }

    @Test
    fun `按会话聚合成树并把时间点排成时间带`() {
        val brief = Brief(
            window = "MANUAL",
            generatedAt = "2026-11-06T10:00:00+08:00",
            wechatPoints = listOf(
                WechatPoint("王总", "报价已发，等确认", "15:00"),
                WechatPoint("王总", "明天再对一次", "明天 10:00"),
                WechatPoint("小李", "问要合同扫描件", null),
            ),
            memoStatus = MemoStatus(
                done = 1,
                pendingDaily = listOf(PendingMemo("写周报", null)),
                pendingTemp = listOf(PendingMemo("回电话", "14:00")),
            ),
            plan = BriefPlan(
                timeline = listOf(
                    TimelineItem("09:30", "晨会", "memo", null, "must"),
                    TimelineItem("下午 3:00", "客户沟通", "chat", null, "maybe"),
                ),
            ),
            recommendations = listOf(Recommendation("带合同", "昨天也提到")),
            risks = listOf("报价还没确认"),
        )

        val lens = BriefLenser.build(brief, metrics = null)

        // 树：两个会话，王总 2 条要点
        assertEquals(2, lens.topics.size)
        assertEquals("王总", lens.topics[0].conv)
        assertEquals(2, lens.topics[0].count)
        assertTrue(lens.topics[0].hasKeyTime)

        // 时间带：按分钟升序，中文时间也要落位
        assertEquals(listOf(570, 900), lens.schedule.map { it.minutes })
        assertEquals("09:30", lens.schedule[0].clock)

        // 计数：必做 1 + 临时 1，完成 1
        assertEquals(2, lens.counters.todos)
        assertEquals(1, lens.counters.done)
        assertEquals(2, lens.counters.total)
        assertEquals(3, lens.counters.points)

        // 建议与风险各自归位
        assertEquals(1, lens.recommendations.size)
        assertEquals(1, lens.risks.size)
        assertTrue(lens.headline.contains("3 条要点"))
    }

    @Test
    fun `没有要点的简报也能渲染空态而不是崩溃`() {
        val brief = Brief(
            window = "MORNING",
            generatedAt = "2026-11-06T10:00:00+08:00",
            memoStatus = MemoStatus(done = 0),
            plan = BriefPlan(),
        )
        val lens = BriefLenser.build(brief, metrics = null)
        assertTrue(lens.topics.isEmpty())
        assertTrue(lens.schedule.isEmpty())
        assertEquals(0, lens.counters.todos)
        assertEquals(0f, lens.counters.total.toFloat())
        assertTrue(lens.headline.isNotBlank())
        assertTrue(lens.highlights.isEmpty())
    }

    @Test
    fun `先看这3条按判据排序且可解释`() {
        val brief = Brief(
            window = "MANUAL",
            generatedAt = "2026-11-06T10:00:00+08:00",
            wechatPoints = listOf(
                WechatPoint("闲聊群", "今天天气不错", null),                              // 0 分
                WechatPoint("王总", "报价单需要你确认折扣", null),                        // 动作+钱 = 4
                WechatPoint("小李", "合同扫描件还没收到，麻烦尽快发我", "16:00"),          // 时间+动作+风险 = 6
                WechatPoint("项目群", "会议改到下午三点", "15:00"),                        // 时间+定论 = 4
            ),
            memoStatus = MemoStatus(done = 0),
            plan = BriefPlan(),
        )

        val lens = BriefLenser.build(brief, metrics = null)
        val top = lens.highlights

        assertEquals(3, top.size)
        assertEquals("小李", top[0].conv)
        assertEquals("带时间", top[0].tag)
        assertEquals(6, top[0].score)
        assertTrue(top[0].text.contains("尽快"))

        // 4 分的两条稳定按原文顺序（王总在前）
        assertEquals(listOf("王总", "项目群"), top.drop(1).map { it.conv })
        // 0 分的闲聊被挤出前 3
        assertTrue(top.none { it.conv == "闲聊群" })
    }

    @Test
    fun `怎么办最多三条且缺 ask 的被过滤`() {
        val brief = Brief(
            window = "MANUAL",
            generatedAt = "2026-11-06T12:00:00+08:00",
            wechatPoints = listOf(WechatPoint("陈英钿", "明天去西湖")),
            memoStatus = MemoStatus(done = 0),
            plan = BriefPlan(),
            advice = listOf(
                com.infoagent.agent.model.Advice(who = "陈英钿", ask = "要不要带东西", say = "不用带", kind = "reply"),
                com.infoagent.agent.model.Advice(who = "财务", ask = "合同今天盖章", kind = "prepare"),
                com.infoagent.agent.model.Advice(who = "y", ask = "第三条", kind = "remind"),
                com.infoagent.agent.model.Advice(who = "z", ask = "第四条会被截断", kind = "risk"),
                com.infoagent.agent.model.Advice(who = "空", ask = "   ", kind = "reply"),
            ),
        )
        val lens = BriefLenser.build(brief, metrics = null)
        assertEquals(3, lens.advice.size)
        assertEquals("陈英钿", lens.advice[0].who)
        assertTrue(lens.advice.none { it.who == "空" })
    }

    @Test
    fun `旧简报没有怎么办字段时渲染为空`() {
        val brief = Brief(
            window = "MORNING",
            generatedAt = "2026-11-06T10:00:00+08:00",
            wechatPoints = listOf(WechatPoint("王总", "三点开会", "15:00")),
            memoStatus = MemoStatus(done = 0),
            plan = BriefPlan(),
        )
        assertTrue(BriefLenser.build(brief).advice.isEmpty())
    }
}