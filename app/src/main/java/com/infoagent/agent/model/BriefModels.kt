package com.infoagent.agent.model

/** 三段简报的数据模型（与 assets/contracts/brief_v1.schema.json 对齐） */

data class WechatPoint(val conv: String, val point: String, val keyTime: String? = null)

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
    val tomorrow: TomorrowPlan? = null,
    val risks: List<String> = emptyList(),
    /** 结构解析中丢弃的项（UI 显示「结构解析失败（部分）」时用） */
    val warnings: List<String> = emptyList(),
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