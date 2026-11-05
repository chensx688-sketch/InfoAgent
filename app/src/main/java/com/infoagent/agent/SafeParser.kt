package com.infoagent.agent

import com.infoagent.agent.model.AutoMemoItem
import com.infoagent.agent.model.Brief
import com.infoagent.agent.model.BriefPlan
import com.infoagent.agent.model.MemoStatus
import com.infoagent.agent.model.MemoryItem
import com.infoagent.agent.model.MemoryPeriod
import com.infoagent.agent.model.PendingMemo
import com.infoagent.agent.model.Recommendation
import com.infoagent.agent.model.TimelineItem
import com.infoagent.agent.model.TomorrowPlan
import com.infoagent.agent.model.WechatPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * 契约安全解析（§8.1）：容错提取 JSON → 必填校验 → 非法枚举/超长字段丢弃并标记；
 * 整体失败由调用方回退 Markdown（原文缓存）。
 */
object SafeParser {

    const val BRIEF_CONTRACT = "brief_v1"
    const val AUTO_MEMO_CONTRACT = "auto_memo_v1"
    const val MEMORY_CONTRACT = "memory_v1"

    sealed interface Result<out T> {
        data class Ok<T>(val value: T, val warnings: List<String> = emptyList()) : Result<T>
        data class Failed(val reason: String) : Result<Nothing>
    }

    /** 去掉 ```json 围栏与前后噪声，取第一个 { 到最后一个 } */
    fun extractJson(raw: String): String? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun maxLen(s: String?, len: Int): String? =
        s?.let { if (it.length > len) it.take(len) else it }

    fun parseBrief(raw: String, expectedWindow: String? = null): Result<Brief> {
        val json = extractJson(raw) ?: return Result.Failed("响应不是 JSON（已缓存原文）")
        val root = runCatching { JSONObject(json) }.getOrElse {
            return Result.Failed("JSON 解析失败（已缓存原文）")
        }
        val warnings = mutableListOf<String>()
        val window = root.optString("window")
        if (window !in setOf("MORNING", "AFTERNOON", "EVENING", "MANUAL")) {
            return Result.Failed("window 字段非法：$window")
        }
        if (expectedWindow != null && window != expectedWindow) {
            warnings.add("window 与请求不一致（请求 $expectedWindow，返回 $window）")
        }
        val generatedAt = root.optString("generated_at")
        if (generatedAt.isEmpty()) return Result.Failed("缺少 generated_at")

        val points = mutableListOf<WechatPoint>()
        root.optJSONArray("wechat_points")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val conv = o.optString("conv").trim()
                val point = o.optString("point").trim()
                if (conv.isEmpty() || point.isEmpty()) {
                    warnings.add("丢弃 1 条要点（缺 conv/point）")
                    continue
                }
                points.add(WechatPoint(maxLen(conv, 60)!!, maxLen(point, 300)!!, maxLen(o.optString("key_time").ifEmpty { null }, 40)))
            }
        }

        val memoObj = root.optJSONObject("memo_status") ?: return Result.Failed("缺少 memo_status")
        val memoStatus = MemoStatus(
            done = memoObj.optInt("done", 0).coerceAtLeast(0),
            pendingDaily = parsePending(memoObj.optJSONArray("pending_daily"), warnings),
            pendingTemp = parsePending(memoObj.optJSONArray("pending_temp"), warnings),
        )

        val planObj = root.optJSONObject("plan") ?: return Result.Failed("缺少 plan")
        val timeline = mutableListOf<TimelineItem>()
        planObj.optJSONArray("timeline")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val time = o.optString("time").trim()
                val title = o.optString("title").trim()
                if (time.isEmpty() || title.isEmpty()) {
                    warnings.add("丢弃 1 条时间线（缺 time/title）")
                    continue
                }
                val kind = o.optString("kind").ifEmpty { "maybe" }
                if (kind !in setOf("must", "maybe")) {
                    warnings.add("丢弃 1 条时间线（kind 非法：$kind）")
                    continue
                }
                val source = o.optString("source").ifEmpty { "other" }
                timeline.add(
                    TimelineItem(
                        time = maxLen(time, 40)!!,
                        title = maxLen(title, 120)!!,
                        source = if (source in setOf("memo", "chat", "memory", "other")) source else "other",
                        detail = maxLen(o.optString("detail").ifEmpty { null }, 300),
                        kind = kind,
                        date = maxLen(o.optString("date").ifEmpty { null }, 20),
                    ),
                )
            }
        }

        val recommendations = mutableListOf<Recommendation>()
        root.optJSONArray("recommendations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text").trim()
                if (text.isEmpty()) {
                    warnings.add("丢弃 1 条推荐（缺 text）")
                    continue
                }
                val refs = mutableListOf<Long>()
                o.optJSONArray("memory_refs")?.let { r ->
                    for (j in 0 until r.length()) refs.add(r.optLong(j))
                }
                recommendations.add(
                    Recommendation(
                        text = maxLen(text, 200)!!,
                        reason = maxLen(o.optString("reason").ifEmpty { null }, 200),
                        memoryRefs = refs,
                    ),
                )
            }
        }

        val tomorrow = root.optJSONObject("tomorrow")?.let { t ->
            val todos = mutableListOf<PendingMemo>()
            t.optJSONArray("todos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val title = o.optString("title").trim()
                    if (title.isEmpty()) continue
                    todos.add(PendingMemo(maxLen(title, 120)!!, maxLen(o.optString("time").ifEmpty { null }, 40)))
                }
            }
            TomorrowPlan(todos)
        }

        val risks = mutableListOf<String>()
        root.optJSONArray("risks")?.let { arr ->
            for (i in 0 until arr.length()) maxLen(arr.optString(i), 200)?.takeIf { it.isNotBlank() }?.let { risks.add(it) }
        }

        return Result.Ok(
            Brief(
                window = window,
                generatedAt = generatedAt,
                wechatPoints = points,
                memoStatus = memoStatus,
                plan = BriefPlan(timeline, maxLen(planObj.optString("note").ifEmpty { null }, 500)),
                recommendations = recommendations,
                tomorrow = tomorrow,
                risks = risks,
                warnings = warnings,
            ),
            warnings,
        )
    }

    private fun parsePending(arr: JSONArray?, warnings: MutableList<String>): List<PendingMemo> {
        if (arr == null) return emptyList()
        val list = mutableListOf<PendingMemo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text").trim()
            if (text.isEmpty()) continue
            list.add(PendingMemo(maxLen(text, 200)!!, maxLen(o.optString("time").ifEmpty { null }, 40)))
        }
        return list
    }

    fun parseAutoMemo(raw: String): Result<List<AutoMemoItem>> {
        val json = extractJson(raw) ?: return Result.Failed("响应不是 JSON")
        val root = runCatching { JSONObject(json) }.getOrElse { return Result.Failed("JSON 解析失败") }
        val warnings = mutableListOf<String>()
        val items = mutableListOf<AutoMemoItem>()
        val arr = root.optJSONArray("items") ?: return Result.Failed("响应缺少 items 字段（按失败处理，不推进水位）")
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            if (title.isEmpty()) {
                warnings.add("丢弃 1 条（缺 title）")
                continue
            }
            val ids = mutableListOf<Long>()
            o.optJSONArray("source_msg_ids")?.let { a -> for (j in 0 until a.length()) ids.add(a.optLong(j)) }
            val urgency = o.optString("urgency").ifEmpty { "normal" }
            items.add(
                AutoMemoItem(
                    title = maxLen(title, 120)!!,
                    due = maxLen(o.optString("due").ifEmpty { null }, 40),
                    urgency = if (urgency in setOf("normal", "urgent")) urgency else "normal",
                    sourceMsgIds = ids,
                ),
            )
        }
        return Result.Ok(items, warnings)
    }

    fun parseMemory(raw: String): Result<List<MemoryItem>> {
        val json = extractJson(raw) ?: return Result.Failed("响应不是 JSON")
        val root = runCatching { JSONObject(json) }.getOrElse { return Result.Failed("JSON 解析失败") }
        val warnings = mutableListOf<String>()
        val facts = mutableListOf<MemoryItem>()
        val arr = root.optJSONArray("facts") ?: return Result.Ok(emptyList(), listOf("缺少 facts"))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val fact = o.optString("fact_text").trim()
            if (fact.isEmpty()) {
                warnings.add("丢弃 1 条事实（缺 fact_text）")
                continue
            }
            val category = o.optString("category").ifEmpty { "PATTERN" }
            if (category !in setOf("HABIT", "PREFERENCE", "PATTERN", "RELATION")) {
                warnings.add("丢弃 1 条事实（category 非法：$category）")
                continue
            }
            val period = o.optJSONObject("period")?.let { p ->
                val freq = p.optString("freq")
                if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY")) {
                    warnings.add("忽略 1 个 period（freq 非法）")
                    null
                } else {
                    MemoryPeriod(
                        freq = freq,
                        weekday = if (p.has("weekday") && !p.isNull("weekday")) p.optInt("weekday") else null,
                        time = maxLen(p.optString("time").ifEmpty { null }, 10),
                    )
                }
            }
            val refs = mutableListOf<String>()
            o.optJSONArray("refs")?.let { r -> for (j in 0 until r.length()) refs.add(r.optString(j)) }
            facts.add(
                MemoryItem(
                    category = category,
                    subject = maxLen(o.optString("subject").ifEmpty { null }, 40),
                    factText = maxLen(fact, 200)!!,
                    confidence = o.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
                    period = period,
                    refs = refs,
                ),
            )
        }
        return Result.Ok(facts, warnings)
    }
}