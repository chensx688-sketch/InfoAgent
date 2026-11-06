package com.infoagent.ui.agent.viz

import com.infoagent.agent.model.Brief
import com.infoagent.data.repo.DayMetrics

/** 树状图的一个叶子：一条要点 */
data class LensPoint(val text: String, val keyTime: String?)

/** 树状图的一个分支：一个会话 */
data class LensTopic(val conv: String, val points: List<LensPoint>) {
    val count: Int get() = points.size
    val hasKeyTime: Boolean get() = points.any { !it.keyTime.isNullOrBlank() }
}

/** 时间带上的一个点（分钟数 = 当天 0:00 起） */
data class ScheduleSlot(
    val minutes: Int,
    val label: String,
    val kind: String, // must / maybe
    val source: String, // memo / chat / memory / other
    val raw: String,
    val detail: String? = null,
) {
    val clock: String
        get() = "%02d:%02d".format(minutes / 60, minutes % 60)
}

/** 「要做的事」一行 */
data class TodoLine(val text: String, val time: String?, val origin: String, val refs: List<Long> = emptyList())

/**
 * 「先看这 3 条」里的一条。
 * tag 是命中的判据（带时间 / 要回复 / 涉及钱 / 已定论 / 有风险），用来解释「为什么它排在前面」，
 * 用户扫一眼就知道该不该看，而不是被动接受一个黑箱排序。
 */
data class LensHighlight(
    val text: String,
    val conv: String,
    val keyTime: String?,
    val tag: String,
    val score: Int,
)

data class LensCounters(
    val convs: Int,
    val points: Int,
    val slots: Int,
    val todos: Int,
    val done: Int,
    val total: Int,
)

/**
 * 简报的可视化视图模型：**只做结构化与排序，不做推断**。
 *
 * 逻辑主线（为什么这样排版）：
 *   ⓿ 先看这 3 条（决策优先——没时间就只读这一段）
 *   ① 谁在说（会话 → 要点树 / 卡片）
 *   ② 什么时候（时间带 + 故事线）
 *   ③ 消息节奏（曲线 / 环形 / 条形）
 *   ④ 我要做什么（必做 / 临时 / 建议 / 风险）
 * 整体是「结论 → 信息 → 时间 → 数量 → 行动」的收敛关系。
 */
data class BriefLens(
    val headline: String,
    val highlights: List<LensHighlight>,
    /** brief_v2：怎么办的建议（回复草稿 + 易疏忽细节），v1 老简报为空 */
    val advice: List<com.infoagent.agent.model.Advice>,
    val topics: List<LensTopic>,
    val schedule: List<ScheduleSlot>,
    val todos: List<TodoLine>,
    val recommendations: List<TodoLine>,
    val risks: List<String>,
    val counters: LensCounters,
    val notes: BriefNotes,
)

/** 摘要里「不变的事实」与「需要留意的事实」分开，避免通读时错过重点 */
data class BriefNotes(
    val completionText: String,
    val densityText: String,
    val tomorrow: List<String>,
)

object BriefLenser {

    private val HHMM = Regex("""(\d{1,2})\s*[:：]\s*(\d{2})""")
    private val POINT_HINT = Regex("""(\d{1,2})\s*[点時时]""")

    private const val MAX_TOPICS = 12
    private const val MAX_POINTS_PER_TOPIC = 4
    private const val MAX_HIGHLIGHTS = 3

    // 打分词表：只做「找关键词」，不做语义理解，宁可漏判也不误判
    private val ACTION_WORDS = listOf(
        "需要", "麻烦", "请你", "请", "确认", "回复", "回签", "答复", "发我", "发一下", "发给",
        "提交", "交", "付", "约", "回电话", "尽快", "截止", "deadline", "等你", "要你",
    )
    private val MONEY_WORDS = listOf("元", "万", "报价", "付款", "转账", "发票", "折扣", "金额", "¥", "￥", "费用")
    private val DECISION_WORDS = listOf("定了", "确定", "同意", "取消", "改到", "改成", "达成", "签了", "通过")
    private val RISK_WORDS = listOf("还没", "未收到", "延期", "风险", "投诉", "欠", "催", "对不上", "来不及")

    /**
     * 解析时间文本 → 当天分钟数；解析不出来返回 null。
     * 支持 "15:00"、"下午 3:00"、"晚上8点"、"明天 09:30"（跨天信息保留在原文里，这里只取钟点）。
     */
    fun parseClock(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val t = text.trim()
        var hour: Int
        var minute: Int
        val m = HHMM.find(t)
        if (m != null) {
            hour = m.groupValues[1].toIntOrNull() ?: return null
            minute = m.groupValues[2].toIntOrNull() ?: 0
        } else {
            val p = POINT_HINT.find(t) ?: return null
            hour = p.groupValues[1].toIntOrNull() ?: return null
            minute = 0
        }
        val afternoon = t.contains("下午") || t.contains("晚上") || t.contains("傍晚") || t.contains("午后")
        val morning = t.contains("上午") || t.contains("早上") || t.contains("凌晨")
        if (afternoon && hour in 1..11) hour += 12
        if (morning && hour == 12) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun build(brief: Brief, metrics: DayMetrics? = null): BriefLens {
        val topics = brief.wechatPoints
            .filter { it.conv.isNotBlank() || it.point.isNotBlank() }
            .groupBy { it.conv.ifBlank { "未标注会话" } }
            .entries
            .take(MAX_TOPICS)
            .map { (conv, list) ->
                LensTopic(
                    conv = conv,
                    points = list.take(MAX_POINTS_PER_TOPIC).map {
                        LensPoint(text = it.point.trim(), keyTime = it.keyTime?.takeIf { k -> k.isNotBlank() })
                    },
                )
            }

        val schedule = brief.plan.timeline
            .mapNotNull { item ->
                val minutes = parseClock(item.time) ?: return@mapNotNull null
                ScheduleSlot(
                    minutes = minutes,
                    label = item.title,
                    kind = item.kind,
                    source = item.source,
                    raw = item.time,
                    detail = item.detail?.takeIf { it.isNotBlank() },
                )
            }
            .sortedBy { it.minutes }

        val daily = brief.memoStatus.pendingDaily.map { TodoLine(it.text, it.time, "DAILY") }
        val temp = brief.memoStatus.pendingTemp.map { TodoLine(it.text, it.time, "TEMP") }
        val recs = brief.recommendations.map { TodoLine(it.text, it.reason, "REC", it.memoryRefs) }

        val done = brief.memoStatus.done
        val total = done + daily.size
        val counters = LensCounters(
            convs = topics.size,
            points = topics.sumOf { it.count },
            slots = schedule.size,
            todos = daily.size + temp.size,
            done = done,
            total = total,
        )

        val completionText = if (total <= 0) "今日必做还没建立" else "必做完成 $done/$total"

        val densityText = buildString {
            append("本窗口 $counters.convs 个会话 · $counters.points 条要点")
            metrics?.peak?.let { append(" · 今日高峰 ${"%02d:00".format(it.hour)}（${it.count} 条）") }
            if (counters.slots > 0) append(" · ${counters.slots} 个时间点")
        }

        val headline = buildString {
            append(if (counters.points > 0) "${counters.convs} 个会话、${counters.points} 条要点" else "本窗口没有新的消息要点")
            if (metrics != null) append("，收到 ${metrics.todayTotal} 条消息")
            if (counters.slots > 0) append("，${counters.slots} 个时间点已排进时间带")
            if (counters.todos > 0) append("，还有 ${counters.todos} 项待办")
        }

        return BriefLens(
            headline = headline,
            highlights = pickHighlights(topics),
            advice = brief.advice.filter { it.ask.isNotBlank() }.take(3),
            topics = topics,
            schedule = schedule,
            todos = daily + temp,
            recommendations = recs,
            risks = brief.risks.filter { it.isNotBlank() },
            counters = counters,
            notes = BriefNotes(
                completionText = completionText,
                densityText = densityText,
                tomorrow = brief.tomorrow?.todos?.map { it.text } ?: emptyList(),
            ),
        )
    }

    /**
     * 「先看这 3 条」：把全部要点按「信息价值」排一次序。
     *
     * 判据是公开、可解释的加权，不是模型排序：
     *   带具体钟点 +3（时间会过期，优先看）
     *   含动作词   +2（需要我做点什么）
     *   含金额     +2（钱的事出错成本高）
     *   含定论词   +1（已经定了，避免重复沟通）
     *   含风险词   +1（还没到/被催/对不上）
     * 同分按原文顺序，保证结果稳定可复现。
     */
    fun pickHighlights(topics: List<LensTopic>, limit: Int = MAX_HIGHLIGHTS): List<LensHighlight> {
        val scored = mutableListOf<LensHighlight>()
        topics.forEach { topic ->
            topic.points.forEach { point ->
                val text = point.text
                if (text.isBlank()) return@forEach
                var score = 0
                var tag = "信息"
                val hasTime = !point.keyTime.isNullOrBlank()
                if (hasTime) {
                    score += 3
                    tag = "带时间"
                }
                if (ACTION_WORDS.any { text.contains(it) }) {
                    score += 2
                    if (tag == "信息") tag = "要回复"
                }
                if (MONEY_WORDS.any { text.contains(it) }) {
                    score += 2
                    if (tag == "信息") tag = "涉及钱"
                }
                if (DECISION_WORDS.any { text.contains(it) }) {
                    score += 1
                    if (tag == "信息") tag = "已定论"
                }
                if (RISK_WORDS.any { text.contains(it) }) {
                    score += 1
                    if (tag == "信息") tag = "有风险"
                }
                scored += LensHighlight(
                    text = text,
                    conv = topic.conv,
                    keyTime = point.keyTime,
                    tag = tag,
                    score = score,
                )
            }
        }
        return scored
            .sortedWith(compareByDescending<LensHighlight> { it.score })
            .take(limit)
    }
}