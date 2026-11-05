package com.infoagent.agent

import com.infoagent.agent.model.Brief
import com.infoagent.data.db.AgentRunEntity
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.WechatMessageEntity
import com.infoagent.data.util.DayBoundary
import com.infoagent.data.util.DedupKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 本次简报的素材/用量元信息（A5：让 token 与窗口可见） */
data class BriefStats(
    val materialCount: Int = 0,
    val windowStart: Long = 0,
    val windowEnd: Long = 0,
    val estInputTokens: Int = 0,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val droppedNoise: Int = 0,
)

/** 一次简报结果（含缓存/降级元信息，UI 直接消费） */
data class BriefRun(
    val brief: Brief?,
    val runId: Long,
    val fromCache: Boolean = false,
    val stale: Boolean = false,
    val rawText: String? = null,
    val error: String? = null,
    val stats: BriefStats? = null,
    /** D3：素材不足/额度/未配置/锁占用——给 Worker 判断「不必重试」 */
    val permanent: Boolean = false,
)

/** v3：一次增量片段的结果 */
data class SliceRun(
    val sliceId: Long,
    val seq: Int,
    val trigger: String,
    val fromTs: Long,
    val toTs: Long,
    val brief: Brief?,
    val state: String, // OK/FAILED/SKIPPED
    val newCount: Int = 0,
    val error: String? = null,
    val stats: BriefStats? = null,
    val permanent: Boolean = false,
)

/**
 * 三段简报流水线（§8.3）：窗口取数 → 缓存键 → 预算 → 调用 → SafeParser → 落库。
 * - 窗口：MORNING 前日22:00–当日10:00；AFTERNOON 10:00–13:30；EVENING 13:30–22:00；MANUAL 0:00–now
 * - 缓存：input_hash 命中直接返回（§8.7 D04）
 * - 失败：返回 error + 标记 stale（UI 显示上次成功简报）
 */
class BriefingPipeline(
    private val db: InfoAgentDatabase,
    private val client: AgentClient,
    private val configProvider: suspend () -> ApiConfig?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dateProvider: () -> String = { DayBoundary.today() },
    private val boundsProvider: () -> WindowBounds = { WindowBounds() },
) {

    // 预算日期必须与 pipeline 的业务日期一致（否则跨零点/测试固定日期时会绕过额度）
    private val budget = BudgetGuard(db.agentDao(), dateProvider)

    private data class Gathered(
        val input: BriefingInput,
        val wechatLines: String,
        val dailyLines: String,
        val tempLines: String,
        val stageLines: String,
        val factsLines: String,
        val done: Int,
        val dailyCount: Int,
        val tempCount: Int,
        val stageCount: Int,
        val droppedNoise: Int,
        val materialCount: Int,
        val windowStart: Long,
        val windowEnd: Long,
    )

    suspend fun generate(
        window: String,
        manual: Boolean = false,
        force: Boolean = false,
        onDelta: ((String) -> Unit)? = null,
    ): BriefRun = withContext(Dispatchers.Default) {
        val now = clock()
        val bizDate = dateProvider()
        val config = configProvider()
            ?: return@withContext BriefRun(null, 0, error = "还没有配置 API（设置里填写接口地址与 Key）", permanent = true)

        // C3：读库异常必须落到 BriefRun.error，否则 UI 的 running 会永久卡住
        val gathered = try {
            gather(window, bizDate, now, config, boundsProvider())
        } catch (e: Exception) {
            return@withContext BriefRun(null, 0, error = "读取素材失败：${e.message?.take(80)}")
        }
        if (gathered.input.messages.isEmpty() && gathered.dailyCount == 0 && gathered.tempCount == 0 && gathered.stageCount == 0) {
            return@withContext BriefRun(null, 0, error = "今天还没有消息和备忘，先攒点素材吧", permanent = true)
        }
        val inputHash = CanonicalHasher.inputHash(gathered.input)
        val cacheKey = "brief_v1:$inputHash"

        val baseStats = BriefStats(
            materialCount = gathered.materialCount,
            windowStart = gathered.windowStart,
            windowEnd = gathered.windowEnd,
            estInputTokens = gathered.wechatLines.length * 2 / 3,
            droppedNoise = gathered.droppedNoise,
        )

        db.agentDao().runByCacheKey(cacheKey)?.let { cached ->
            if (force) return@let
            val brief = cached.briefJson?.let { json ->
                (SafeParser.parseBrief(json, window) as? SafeParser.Result.Ok)?.let { sanitize(it.value) }
            }
            if (brief != null) {
                return@withContext BriefRun(
                    brief,
                    cached.id,
                    fromCache = true,
                    stale = cached.stale == 1L,
                    stats = baseStats.copy(
                        tokensIn = cached.tokensUsed ?: 0L,
                    ),
                )
            }
        }

        val useCase = if (manual) BudgetGuard.USE_MANUAL else BudgetGuard.USE_BRIEF
        if (!budget.canSpend(useCase, manual = manual)) {
            return@withContext BriefRun(
                null,
                0,
                error = "今日调用额度已用完（手动 10 次/日，自动简报 3 次/日）",
                permanent = true,
                stats = baseStats,
            )
        }

        val prompt = listOf(
            ChatMessage("system", Prompts.BRIEF_SYSTEM),
            ChatMessage(
                "user",
                Prompts.briefUserPrompt(
                    window = window,
                    bizDate = bizDate,
                    wechatLines = gathered.wechatLines,
                    memoDaily = gathered.dailyLines,
                    memoTemp = gathered.tempLines,
                    memoStage = gathered.stageLines,
                    doneCount = gathered.done,
                    dailyCount = gathered.dailyCount,
                    topFacts = gathered.factsLines,
                ),
            ),
        )

        val result = try {
            val started = clock()
            client.complete(config, prompt, onDelta).also { r ->
                // R5：此前 agent_trace 只有导出导入读写，补上真实调用审计（成本/延迟/模型）
                runCatching {
                    db.agentDao().insertTrace(
                        com.infoagent.data.db.AgentTraceEntity(
                            callId = java.util.UUID.randomUUID().toString(),
                            useCase = useCase,
                            model = config.model,
                            promptVersion = Prompts.VERSION,
                            contractVersion = SafeParser.BRIEF_CONTRACT,
                            tokensIn = r.tokensIn,
                            tokensOut = r.tokensOut,
                            latencyMs = clock() - started,
                            parseResult = "ok",
                            createdAt = clock(),
                        ),
                    )
                }
            }
        } catch (e: IOException) {
            val fallback = latestRun(window, bizDate)
            val msg = e.message.orEmpty()
            val permanent = msg.contains("401") || msg.contains("403") ||
                msg.contains("素材过长") || msg.contains("上下文")
            return@withContext BriefRun(
                null,
                fallback?.id ?: 0,
                error = mapError(e),
                stale = fallback != null,
                permanent = permanent,
                stats = baseStats,
            )
        } catch (e: Exception) {
            val fallback = latestRun(window, bizDate)
            return@withContext BriefRun(
                null,
                fallback?.id ?: 0,
                error = "调用失败：${e.message?.take(120)}",
                stale = fallback != null,
                stats = baseStats,
            )
        }

        budget.record(useCase, result.tokensIn, result.tokensOut, costEstimate(result.tokensIn, result.tokensOut))

        when (val parsed = SafeParser.parseBrief(result.content, window)) {
            is SafeParser.Result.Ok -> {
                val runId = db.agentDao().insertRun(
                    AgentRunEntity(
                        bizDate = bizDate,
                        window = window,
                        inputHash = inputHash,
                        cacheKey = cacheKey,
                        contractVersion = SafeParser.BRIEF_CONTRACT,
                        promptVersion = Prompts.VERSION,
                        model = config.model,
                        temperature = config.temperature,
                        maxTokens = config.maxTokens.toLong(),
                        briefJson = result.content, // 原文保留：复算/回退渲染
                        tokensUsed = result.tokensIn + result.tokensOut,
                        costEst = costEstimate(result.tokensIn, result.tokensOut),
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                BriefRun(
                    sanitize(parsed.value),
                    runId,
                    fromCache = false,
                    rawText = result.content,
                    stats = baseStats.copy(tokensIn = result.tokensIn, tokensOut = result.tokensOut),
                )
            }
            is SafeParser.Result.Failed -> {
                val fallback = latestRun(window, bizDate)
                BriefRun(
                    brief = null,
                    runId = fallback?.id ?: 0,
                    stale = fallback != null,
                    rawText = result.content,
                    error = parsed.reason,
                    stats = baseStats.copy(tokensIn = result.tokensIn, tokensOut = result.tokensOut),
                )
            }
        }
    }

    /**
     * v3 增量片段：区间 = [上一条 OK 的 to_ts, now)，起点缺失则当天 00:00。
     * 结果无论是 OK/FAILED/SKIPPED 都会落一条 brief_slice，时间轴才完整。
     */
    suspend fun generateSlice(trigger: String, force: Boolean = false): SliceRun = withContext(Dispatchers.Default) {
        val now = clock()
        val bizDate = dateProvider()
        val prev = db.briefSliceDao().latestOk(bizDate)
        val from = prev?.toTs ?: startOfDay(bizDate)
        val to = now
        val rangeText = "${clockText(from)}–${clockText(to)}"

        suspend fun record(
            state: String,
            brief: Brief?,
            payload: String?,
            newCount: Int,
            tokensIn: Long,
            tokensOut: Long,
            error: String?,
            permanent: Boolean,
            materialCount: Int,
        ): SliceRun {
            val seq = runCatching { db.briefSliceDao().countByDay(bizDate) }.getOrDefault(0) + 1
            val id = runCatching {
                db.briefSliceDao().insert(
                    com.infoagent.data.db.BriefSliceEntity(
                        bizDate = bizDate,
                        seq = seq,
                        trigger = trigger,
                        fromTs = from,
                        toTs = to,
                        payloadJson = payload,
                        newCount = newCount,
                        tokensIn = tokensIn,
                        tokensOut = tokensOut,
                        costEst = costEstimate(tokensIn, tokensOut),
                        state = state,
                        error = error,
                        createdAt = now,
                        readAt = null,
                    ),
                )
            }.getOrDefault(0L)
            return SliceRun(
                sliceId = id,
                seq = seq,
                trigger = trigger,
                fromTs = from,
                toTs = to,
                brief = brief,
                state = state,
                newCount = newCount,
                error = error,
                permanent = permanent,
                stats = BriefStats(
                    materialCount = materialCount,
                    windowStart = from,
                    windowEnd = to,
                    estInputTokens = 0,
                    tokensIn = tokensIn,
                    tokensOut = tokensOut,
                ),
            )
        }

        if (!force && to - from < 5 * 60_000L) {
            return@withContext record("SKIPPED", null, null, 0, 0, 0, "间隔不足 5 分钟，已跳过", true, 0)
        }
        val config = configProvider()
            ?: return@withContext record("SKIPPED", null, null, 0, 0, 0, "还没有配置 API（设置里填写接口地址与 Key）", true, 0)

        val gathered = try {
            gather(trigger, bizDate, now, config, boundsProvider(), longArrayOf(from, to))
        } catch (e: Exception) {
            return@withContext record("FAILED", null, null, 0, 0, 0, "读取素材失败：${e.message?.take(80)}", false, 0)
        }
        if (gathered.input.messages.isEmpty() && gathered.dailyCount == 0 &&
            gathered.tempCount == 0 && gathered.stageCount == 0
        ) {
            return@withContext record("SKIPPED", null, null, 0, 0, 0, "这一段没有新消息和备忘", true, 0)
        }
        val useCase = if (trigger == "MANUAL") BudgetGuard.USE_MANUAL else BudgetGuard.USE_BRIEF
        if (!budget.canSpend(useCase, manual = trigger == "MANUAL")) {
            return@withContext record(
                "SKIPPED", null, null, 0, 0, 0,
                "今日调用额度已用完（手动 10 次/日，自动简报 3 次/日）", true, gathered.materialCount,
            )
        }

        val prevSummary = prev?.payloadJson?.let { json ->
            (SafeParser.parseBrief(json, prev.trigger) as? SafeParser.Result.Ok)?.value?.let { b ->
                sanitize(b).wechatPoints.take(20).joinToString("\n") { "· ${it.conv}：${it.point}" }
            }
        }.orEmpty()

        val prompt = listOf(
            ChatMessage("system", Prompts.BRIEF_SYSTEM),
            ChatMessage(
                "user",
                Prompts.briefUserPrompt(
                    window = trigger,
                    bizDate = bizDate,
                    wechatLines = gathered.wechatLines,
                    memoDaily = gathered.dailyLines,
                    memoTemp = gathered.tempLines,
                    memoStage = gathered.stageLines,
                    doneCount = gathered.done,
                    dailyCount = gathered.dailyCount,
                    topFacts = gathered.factsLines,
                    prevSummary = prevSummary,
                    rangeText = rangeText,
                    extraInstruction = "已在上一条简报中提过的事项，若本次无变化，写进 continued 字段，不要重复叙述。",
                ),
            ),
        )

        val result = try {
            val started = clock()
            client.complete(config, prompt).also { r ->
                runCatching {
                    db.agentDao().insertTrace(
                        com.infoagent.data.db.AgentTraceEntity(
                            callId = java.util.UUID.randomUUID().toString(),
                            useCase = useCase,
                            model = config.model,
                            promptVersion = Prompts.VERSION,
                            contractVersion = SafeParser.BRIEF_CONTRACT,
                            tokensIn = r.tokensIn,
                            tokensOut = r.tokensOut,
                            latencyMs = clock() - started,
                            parseResult = "ok",
                            createdAt = clock(),
                        ),
                    )
                }
            }
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            val permanent = msg.contains("401") || msg.contains("403") ||
                msg.contains("素材过长") || msg.contains("上下文")
            return@withContext record(
                if (permanent) "SKIPPED" else "FAILED", null, null, 0, 0, 0,
                mapError(e), permanent, gathered.materialCount,
            )
        } catch (e: Exception) {
            return@withContext record(
                "FAILED", null, null, 0, 0, 0,
                "调用失败：${e.message?.take(120)}", false, gathered.materialCount,
            )
        }

        budget.record(useCase, result.tokensIn, result.tokensOut, costEstimate(result.tokensIn, result.tokensOut))

        return@withContext when (val parsed = SafeParser.parseBrief(result.content, trigger)) {
            is SafeParser.Result.Ok -> {
                val brief = sanitize(parsed.value)
                val newCount = brief.wechatPoints.size +
                    brief.memoStatus.pendingDaily.size +
                    brief.memoStatus.pendingTemp.size +
                    brief.recommendations.size
                record(
                    "OK", brief, result.content, newCount,
                    result.tokensIn, result.tokensOut, null, false, gathered.materialCount,
                )
            }
            is SafeParser.Result.Failed -> record(
                "FAILED", null, result.content, 0,
                result.tokensIn, result.tokensOut, parsed.reason, false, gathered.materialCount,
            )
        }
    }

    /** 当天 00:00（业务日界） */
    private fun startOfDay(bizDate: String): Long = runCatching {
        java.time.LocalDate.parse(bizDate)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    }.getOrDefault(clock())

    /** 最近一次成功简报（失败降级用） */
    suspend fun latestRun(window: String, bizDate: String = dateProvider()): AgentRunEntity? =
        db.agentDao().latestRun(bizDate, window)

    private fun costEstimate(tokensIn: Long, tokensOut: Long): Double =
        tokensIn / 1000.0 * 0.0005 + tokensOut / 1000.0 * 0.002

    private fun mapError(e: IOException): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("401") || msg.contains("403") -> "API Key 无效，请到设置检查"
            msg.contains("429") -> "请求过于频繁，稍后自动重试"
            msg.contains("HTTP 5") -> "服务暂时不可用，已保留上一次简报"
            else -> "网络异常：${msg.take(120)}"
        }
    }

    private suspend fun gather(
        window: String,
        bizDate: String,
        now: Long,
        config: ApiConfig,
        bounds: WindowBounds,
        rangeOverride: LongArray? = null,
    ): Gathered {
        val range = rangeOverride ?: windowRange(window, bizDate, now, bounds)
        val all = db.messageDao().messagesBetween(range[0], range[1])
        val focus = runCatching { db.messageDao().focusPeers().toSet() }.getOrElse { emptySet() }
        val refs = all.map {
            BriefingMessageRef(
                id = it.id,
                dedupKey = it.dedupKey,
                contentHash = DedupKeys.contentHash(it.content),
                recalled = it.recalled,
                lastTs = it.lastTs,
            )
        }
        val daily = db.memoDao().list(bizDate, "DAILY")
        val temp = db.memoDao().list(bizDate, "TEMP")
        val stage = db.memoDao().listKind("STAGE")
        val snapshots = buildString {
            append("daily:")
            daily.joinTo(this, "|") { "${it.text}${if (it.done == 1L) "[x]" else ""}" }
            append(";temp:")
            temp.joinTo(this, "|") { it.text }
            append(";stage:")
            stage.joinTo(this, "|") { it.text }
        }
        val facts = db.memoryDao().topActiveFacts(10)
        val input = BriefingInput(
            bizDate = bizDate,
            window = window,
            model = config.model,
            temperature = config.temperature,
            maxTokens = config.maxTokens,
            messages = refs,
            memoSnapshot = snapshots,
            topFacts = facts.map { it.id to it.confidence },
            focus = focus,
        )
        val noise = all.count { com.infoagent.capture.NoiseFilter.isNoise(it.peerName, it.content) }
        val lines = promptLines(all, focus)
        return Gathered(
            input = input,
            wechatLines = lines.lines,
            dailyLines = daily.joinToString("\n") {
                "- ${it.text}${it.assignedTime?.let { t -> " @${clockText(t)}" } ?: ""}${if (it.done == 1L) "（已完成）" else ""}"
            },
            tempLines = temp.joinToString("\n") {
                "- ${it.text}${it.assignedTime?.let { t -> " @${clockText(t)}" } ?: ""}${if (it.origin == "AI") "（AI 自动整理）" else ""}"
            },
            stageLines = stage.joinToString("\n") {
                "- ${it.text}${it.assignedTime?.let { t -> " @${clockText(t)}" } ?: ""}${if (it.done == 1L) "（已完成）" else ""}"
            },
            factsLines = facts.joinToString("\n") { "${it.id} | ${it.confidence} | ${it.factText.orEmpty()}" },
            done = daily.count { it.done == 1L },
            dailyCount = daily.size,
            tempCount = temp.size,
            stageCount = stage.size,
            droppedNoise = noise,
            materialCount = lines.count,
            windowStart = range[0],
            windowEnd = range[1],
        )
    }

    private data class PickedLines(val lines: String, val count: Int, val chars: Int)

    /**
     * A1/A3：**从最新往回取**，关注会话优先；每会话最多 6 条；全局 12,000 字符上限。
     * 之前的实现按时间升序拼到上限就 break —— 保住的是最旧的一段，最新消息全丢。
     */
    private fun promptLines(messages: List<WechatMessageEntity>, focus: Set<String>): PickedLines {
        val filtered = messages.filter {
            it.recalled != 1L &&
                it.msgType !in setOf("folded", "system") &&
                !com.infoagent.capture.NoiseFilter.isNoise(it.peerName, it.content)
        }
        if (filtered.isEmpty()) return PickedLines("", 0, 0)

        val candidates = filtered.sortedWith(
            compareByDescending<WechatMessageEntity> { if (it.peerName in focus) 1 else 0 }
                .thenByDescending { it.ts },
        )
        val perConv = mutableMapOf<String, Int>()
        val picked = mutableListOf<WechatMessageEntity>()
        var chars = 0
        for (m in candidates) {
            val used = perConv[m.peerName] ?: 0
            if (used >= 6) continue
            val sender = m.sender.ifEmpty { m.peerName }
            val line = "[#${m.id}] {conv=${m.peerName}|sender=$sender|time=${clockText(m.ts)}} " +
                m.content.replace("\n", " ").take(140)
            if (chars + line.length > 12_000) break
            perConv[m.peerName] = used + 1
            picked += m
            chars += line.length + 1
        }
        // 选中的是最新一批；输出时按时间升序，便于模型按时间线理解
        val text = picked.sortedBy { it.ts }.joinToString("\n") { m ->
            val sender = m.sender.ifEmpty { m.peerName }
            "[#${m.id}] {conv=${m.peerName}|sender=$sender|time=${clockText(m.ts)}} " +
                m.content.replace("\n", " ").take(140)
        }
        return PickedLines(text, picked.size, chars)
    }

    /** 去掉零宽字符/不可见字符后再判断（模型常输出 \u200B，导致 isBlank 判不出来、界面显示空会话） */
    private fun visible(s: String): String = s
        .replace("\u200B", "")
        .replace("\u200C", "")
        .replace("\u200D", "")
        .replace("\uFEFF", "")
        .trim()

    /** 预览：只算「实际会被送进 prompt 的素材」条数/字符，供 UI 展示（P1-2） */
    suspend fun previewStats(window: String): BriefStats = withContext(Dispatchers.Default) {
        val biz = dateProvider()
        val range = windowRange(window, biz, clock(), boundsProvider())
        val all = db.messageDao().messagesBetween(range[0], range[1])
        val focus = runCatching { db.messageDao().focusPeers().toSet() }.getOrElse { emptySet() }
        val picked = promptLines(all, focus)
        BriefStats(
            materialCount = picked.count,
            windowStart = range[0],
            windowEnd = range[1],
            estInputTokens = picked.chars * 2 / 3,
            droppedNoise = all.count { com.infoagent.capture.NoiseFilter.isNoise(it.peerName, it.content) },
        )
    }

    /** 对外：UI 加载历史/缓存简报时也要过同一套清洗 */
    fun sanitizeBrief(brief: Brief): Brief = sanitize(brief)

    /** 产出后置清洗：丢掉「要点==会话名」「会话为空」「过短」「复述会话名」「含 null 字面量」的垃圾要点 */
    private fun sanitize(brief: Brief): Brief {
        val points = brief.wechatPoints.mapNotNull { p ->
            val pt = visible(p.point).replace("（null）", "").replace("(null)", "").trim()
            val conv = visible(p.conv).ifEmpty { "未标注会话" }
            when {
                pt.length < 4 -> null
                pt == conv || pt.startsWith(conv) -> null
                pt.contains("null", ignoreCase = true) -> null
                else -> p.copy(conv = conv, point = pt)
            }
        }
        return if (points.size == brief.wechatPoints.size) brief
        else brief.copy(
            wechatPoints = points,
            warnings = brief.warnings + "已过滤 ${brief.wechatPoints.size - points.size} 条重复/垃圾要点",
        )
    }

    private fun clockText(ts: Long): String =
        java.time.Instant.ofEpochMilli(ts)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalTime()
            .toString()
            .take(5)

    /** 简报窗口边界（分钟）：与设置里的三段时点一致，避免"定时改了、统计窗口没改" */
    data class WindowBounds(
        val morningEnd: Int = 10 * 60,
        val afternoonEnd: Int = 13 * 60 + 30,
        val eveningEnd: Int = 22 * 60,
    )

    /** §8.3 窗口定义（左闭右开）：上午=昨晚收盘~今天上午点；下午=上午点~下午点；晚间=下午点~晚间点 */
    fun windowRange(
        window: String,
        bizDate: String,
        now: Long,
        bounds: WindowBounds = WindowBounds(),
    ): LongArray = range(window, bizDate, now, bounds)

    companion object {
        /** 与实例无关的窗口计算，供 UI 在"还没生成"时展示区间与条数 */
        fun range(
            window: String,
            bizDate: String,
            now: Long,
            bounds: WindowBounds = WindowBounds(),
        ): LongArray {
            val startOfToday = DayBoundary.dayRange(bizDate)[0]
            val day = 86_400_000L
            fun at(minutes: Int): Long = startOfToday + minutes * 60_000L
            return when (window) {
                "MORNING" -> longArrayOf(at(bounds.eveningEnd) - day, at(bounds.morningEnd))
                "AFTERNOON" -> longArrayOf(at(bounds.morningEnd), at(bounds.afternoonEnd))
                "EVENING" -> longArrayOf(at(bounds.afternoonEnd), at(bounds.eveningEnd))
                else -> longArrayOf(startOfToday, minOf(now, startOfToday + day))
            }
        }
    }
}