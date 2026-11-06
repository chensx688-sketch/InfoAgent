package com.infoagent.agent

import com.infoagent.agent.model.Advice
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
import com.infoagent.agent.model.TopicCluster
import com.infoagent.agent.model.WechatPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * 契约安全解析（§8.1）：容错提取 JSON → 必填校验 → 非法枚举/超长字段丢弃并标记；
 * 整体失败由调用方回退 Markdown（原文缓存）。
 */
object SafeParser {

    const val BRIEF_CONTRACT = "brief_v3"
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

    /** 客户端自己的时间戳（模型不回显 generated_at 时用它） */
    private fun nowIso(): String = runCatching {
        java.time.OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
    }.getOrElse { "" }

    private fun maxLen(s: String?, len: Int): String? =
        s?.let { if (it.length > len) it.take(len) else it }

    /**
     * 宽松解析：先按标准解析，失败且看着像「被 max_tokens 切断」就尝试补全再解析。
     *
     * 为什么值得救：模型经常在最后一个字段中途被截断（`"watch": ["记得带伞` 就没了），
     * 已经算出来的 advice / points 全都是好的，整份丢掉让用户重来是最差的选择。
     * 补全后仍然解析不出来，就返回**原始失败原因**（更有诊断价值）。
     */
    fun parseBriefLenient(raw: String, expectedWindow: String? = null): Result<Brief> {
        val first = parseBrief(raw, expectedWindow)
        if (first is Result.Ok) return first
        if (raw.isBlank()) return Result.Failed("模型返回空内容（已缓存原文）")
        val repaired = repairTruncated(raw) ?: return first
        return when (val second = parseBrief(repaired, expectedWindow)) {
            is Result.Ok -> Result.Ok(
                second.value,
                listOf("模型输出到一半被截断，已自动补全后解析（建议把 max_tokens 调大）") + second.warnings,
            )
            is Result.Failed -> first
        }
    }

    /**
     * 把被截断的 JSON 补齐成一个合法对象；补不出来返回 null。
     *
     * 做法是「闭合 → 校验 → 砍掉最后一个不完整元素」的循环：
     * 截断点往往落在某个字符串中间或某个键一半上，直接闭合括号会得到非法 JSON，
     * 所以每次失败就丢掉最后一个不完整成员再试，最多 20 轮。
     */
    fun repairTruncated(raw: String): String? {
        // 注意：这里**不能**复用 extractJson——它要求存在结尾的 }，
        // 而真正的截断往往连一个 } 都没有（整份 JSON 断在第一个对象里）。
        val start = raw.indexOf('{')
        if (start < 0) return null
        var json = raw.substring(start).trim()
        if (json.isEmpty()) return null
        if (runCatching { JSONObject(json) }.isSuccess) return null // 本来就合法，不必修
        repeat(20) {
            val closed = closeJson(json) ?: return null
            if (runCatching { JSONObject(closed) }.isSuccess) return closed
            val cut = json.lastIndexOfAny(charArrayOf(',', '{', '['))
            if (cut <= 0) return null
            json = json.substring(0, cut).trimEnd().trimEnd(',')
        }
        return null
    }

    /** 补上未闭合的引号与括号（不做内容判断，交给上面的校验循环） */
    private fun closeJson(s: String): String? {
        val sb = StringBuilder(s)
        var inString = false
        var escaped = false
        val stack = ArrayDeque<Char>()
        for (c in s) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> if (stack.isNotEmpty()) stack.removeLast()
            }
        }
        // 字符串没结束：先补引号（反斜杠悬空时去掉它，否则补上引号仍是非法转义）
        if (inString) {
            if (escaped && sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
            sb.append('"')
        }
        // 末尾是逗号（如 `{"a":1,` ）：去掉，否则闭合后是非法 JSON
        var tail = sb.toString().trimEnd()
        if (tail.endsWith(",")) {
            sb.setLength(0)
            sb.append(tail.dropLast(1))
        }
        while (stack.isNotEmpty()) sb.append(stack.removeLast())
        return sb.toString()
    }

    /** 消息编号数组（非法项直接跳过，不因此丢掉整条建议） */
    private fun longArray(arr: JSONArray?): List<Long> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Long>()
        for (i in 0 until arr.length()) {
            val v = arr.optLong(i, -1L)
            if (v > 0) out.add(v)
        }
        return out
    }

    fun parseBrief(raw: String, expectedWindow: String? = null): Result<Brief> {
        val json = extractJson(raw) ?: return Result.Failed("响应不是 JSON（已缓存原文）")
        val root = runCatching { JSONObject(json) }.getOrElse {
            return Result.Failed("JSON 解析失败（已缓存原文）")
        }
        val warnings = mutableListOf<String>()
        // window / generated_at 由**客户端**决定，不让模型回显：
        // 模型不知道真实时间，回显只是白烧 token，写错了还会让整条简报失败。
        val WINDOWS = setOf("MORNING", "AFTERNOON", "EVENING", "MANUAL")
        val echoed = root.optString("window").trim()
        // 请求方给的窗口优先：这条简报属于哪一段由客户端决定，模型无权改
        val window = expectedWindow?.takeIf { it in WINDOWS } ?: echoed.takeIf { it in WINDOWS } ?: "MANUAL"
        if (expectedWindow != null && echoed.isNotBlank() && echoed != expectedWindow) {
            warnings.add("模型回的 window（$echoed）与请求不符，已按请求记为 $expectedWindow")
            // 以请求为准：窗口决定了这条简报属于哪一段，模型无权改
        }
        val generatedAt = root.optString("generated_at").trim().ifEmpty { nowIso() }

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
                points.add(
                    WechatPoint(
                        conv = maxLen(conv, 60)!!,
                        point = maxLen(point, 300)!!,
                        keyTime = maxLen(o.optString("key_time").ifEmpty { null }, 40),
                        refs = longArray(o.optJSONArray("refs")),
                    ),
                )
            }
        }

        val advice = mutableListOf<Advice>()
        root.optJSONArray("advice")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val who = o.optString("who").trim()
                val ask = o.optString("ask").trim()
                if (ask.isEmpty()) {
                    warnings.add("丢弃 1 条建议（缺 ask）")
                    continue
                }
                val kind = o.optString("kind").ifEmpty { "reply" }
                val watch = mutableListOf<String>()
                o.optJSONArray("watch")?.let { w ->
                    for (j in 0 until w.length()) {
                        maxLen(w.optString(j), 160)?.takeIf { it.isNotBlank() }?.let { watch.add(it) }
                    }
                }
                advice.add(
                    Advice(
                        who = maxLen(who.ifEmpty { "未标注" }, 60)!!,
                        ask = maxLen(ask, 160)!!,
                        say = maxLen(o.optString("say").ifEmpty { null }, 400),
                        why = maxLen(o.optString("why").ifEmpty { null }, 300),
                        watch = watch.take(5),
                        kind = if (kind in setOf("reply", "prepare", "remind", "risk")) kind else "reply",
                        due = maxLen(o.optString("due").ifEmpty { null }, 40),
                        basis = maxLen(o.optString("basis").ifEmpty { null }, 20),
                        refs = longArray(o.optJSONArray("refs")),
                    ),
                )
            }
        }

        // memo_status / plan 在 JSON 里排在后面：一旦被 max_tokens 截断，这两节最先丢。
        // 但它们只是「已有的备忘快照」和「时间线」，缺了不该牵连前面的怎么办/要点——
        // 降级为空 + 留一条 warning，用户至少能看到已经算出来的部分。
        val memoObj = root.optJSONObject("memo_status")
        if (memoObj == null) warnings.add("这次没返回备忘快照（按规定为空处理）")
        val memoStatus = MemoStatus(
            done = memoObj?.optInt("done", 0)?.coerceAtLeast(0) ?: 0,
            pendingDaily = parsePending(memoObj?.optJSONArray("pending_daily"), warnings),
            pendingTemp = parsePending(memoObj?.optJSONArray("pending_temp"), warnings),
        )

        // 显式声明成可空类型：optJSONObject 返回平台类型，写 `?.` 才算数，
        // 否则会被编译成非空调用，运行时直接 NPE（这条真的踩过一次）
        val planObj: JSONObject? = root.optJSONObject("plan")
        if (planObj == null) warnings.add("这次没返回时间线（按规定为空处理）")
        val timeline = mutableListOf<TimelineItem>()
        planObj?.optJSONArray("timeline")?.let { arr ->
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

        val topics = mutableListOf<TopicCluster>()
        root.optJSONArray("topics")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                if (name.isEmpty()) {
                    warnings.add("丢弃 1 个主题（缺 name）")
                    continue
                }
                val pts = mutableListOf<String>()
                o.optJSONArray("points")?.let { p ->
                    for (j in 0 until p.length()) {
                        maxLen(p.optString(j), 200)?.takeIf { it.isNotBlank() }?.let { pts.add(it) }
                    }
                }
                val convs = mutableListOf<String>()
                o.optJSONArray("convs")?.let { c ->
                    for (j in 0 until c.length()) {
                        maxLen(c.optString(j), 60)?.takeIf { it.isNotBlank() }?.let { convs.add(it) }
                    }
                }
                val state = o.optString("state").ifEmpty { "open" }
                topics.add(
                    TopicCluster(
                        name = maxLen(name, 40)!!,
                        points = pts.take(5),
                        convs = convs.distinct().take(6),
                        state = if (state in setOf("open", "done", "risk")) state else "open",
                        refs = longArray(o.optJSONArray("refs")),
                    ),
                )
            }
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
                plan = BriefPlan(timeline, maxLen(planObj?.optString("note")?.ifEmpty { null }, 500)),
                recommendations = recommendations,
                advice = advice,
                topics = topics,
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