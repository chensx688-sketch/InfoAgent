package com.infoagent.agent.model

/** 三段简报的数据模型（与 assets/contracts/brief_v1.schema.json 对齐） */

data class WechatPoint(
    val conv: String,
    val point: String,
    val keyTime: String? = null,
    /** brief_v2：依据的消息编号，点一下就能跳回原文（可为空=模型没给） */
    val refs: List<Long> = emptyList(),
)

/**
 * brief_v2：一条「怎么办」的建议。
 *
 * 简报的价值不在复述消息，而在**替用户把事情想周全**：
 * 对方在等什么回复（[say] 可以直接复制）、为什么这么判（[why]）、
 * 以及人最容易疏忽的点（[watch]：时节/天气/礼数/称呼/时间窗/金额/风险）。
 */
data class Advice(
    val who: String,
    val ask: String,
    val say: String? = null,
    val why: String? = null,
    val watch: List<String> = emptyList(),
    /** reply=要回话 / prepare=要准备 / remind=提醒别忘 / risk=有风险 */
    val kind: String = "reply",
    val due: String? = null,
    /** 依据来源：原文 / 常识 / 推断（模型必须自报，避免把猜测说成事实） */
    val basis: String? = null,
    val refs: List<Long> = emptyList(),
)

data class PendingMemo(val text: String, val time: String? = null)

data class MemoStatus(
    val done: Int,
    val pendingDaily: List<PendingMemo> = emptyList(),
    val pendingTemp: List<PendingMemo> = emptyList(),
)

data class TimelineItem(
    val time: String,
    val title: String,
    val source: String = "memo", // memo/chat/memory/other
    val detail: String? = null,
    val kind: String = "maybe", // must/maybe
    val date: String? = null,   // 跨天必带（§8.3）
)

data class BriefPlan(val timeline: List<TimelineItem> = emptyList(), val note: String? = null)

data class Recommendation(
    val text: String,
    val reason: String? = null,
    val memoryRefs: List<Long> = emptyList(),
)

data class TomorrowPlan(val todos: List<PendingMemo> = emptyList())

data class Brief(
    val window: String, // MORNING/AFTERNOON/EVENING/MANUAL
    val generatedAt: String,
    val wechatPoints: List<WechatPoint> = emptyList(),
    val memoStatus: MemoStatus,
    val plan: BriefPlan,
    val recommendations: List<Recommendation> = emptyList(),
    /** brief_v2：怎么办的建议（回复草稿 + 易疏忽细节），置顶展示 */
    val advice: List<Advice> = emptyList(),
    /** brief_v3：按事项聚合（跨会话），和「按会话」互补 */
    val topics: List<TopicCluster> = emptyList(),
    val tomorrow: TomorrowPlan? = null,
    val risks: List<String> = emptyList(),
    /** 结构解析中丢弃的项（UI 显示「结构解析失败（部分）」时用） */
    val warnings: List<String> = emptyList(),
)

/**
 * brief_v3：按「事项」聚合的簇（合同/报价/招聘/行程…），跨会话。
 *
 * 用户的原话是「希望按主题看，不是按聊天窗口看」：一份合同的进度可以散在 3 个会话里，
 * 按会话排就永远拼不出全貌。refs 保留依据消息编号，点进去能看到原话。
 */
data class TopicCluster(
    val name: String,
    val points: List<String> = emptyList(),
    val convs: List<String> = emptyList(),
    /** open=还没定 / done=已定论 / risk=有风险 */
    val state: String = "open",
    val refs: List<Long> = emptyList(),
)

data class AutoMemoItem(
    val title: String,
    val due: String? = null,
    val urgency: String = "normal",
    val sourceMsgIds: List<Long> = emptyList(),
)

data class MemoryItem(
    val category: String,
    val subject: String? = null,
    val factText: String,
    val confidence: Double = 0.5,
    val period: MemoryPeriod? = null,
    val refs: List<String> = emptyList(),
)

data class MemoryPeriod(
    val freq: String, // DAILY/WEEKLY/MONTHLY
    val weekday: Int? = null,
    val time: String? = null,
)