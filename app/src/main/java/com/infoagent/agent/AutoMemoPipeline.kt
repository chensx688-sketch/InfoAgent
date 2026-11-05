package com.infoagent.agent

import com.infoagent.agent.model.AutoMemoItem
import com.infoagent.data.db.AutoMemoJobEntity
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.MemoItemEntity
import com.infoagent.data.db.MemoSourceEntity
import com.infoagent.data.util.DayBoundary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 自动整理入备忘（FR-06 / §8.2）：
 * - 取未处理的新消息（id > 上次 DONE 批次的最大 id），批 ≤20 条；
 * - 提示词要求输出 items + source_msg_ids；落库为 TEMP 备忘（origin=AI，来源会话/App，memo_source 全量关联）；
 * - 标题规范化 hash + 7 天内未完成备忘去重；
 * - batch_hash 幂等，重复批次不再调用。
 */
class AutoMemoPipeline(
    private val db: InfoAgentDatabase,
    private val client: AgentClient,
    private val configProvider: suspend () -> ApiConfig?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dateProvider: () -> String = { DayBoundary.today() },
) {

    sealed interface Outcome {
        data class Created(val count: Int, val memoIds: List<Long>) : Outcome
        data object Nothing : Outcome
        data class Skipped(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    // 预算日期跟着业务日期走（R4/预算测试稳定性）
    private val budget = BudgetGuard(db.agentDao(), dateProvider)

    companion object {
        /** 同一批失败超过该次数即进入死信并推进水位，避免队头阻塞（A3） */
        const val MAX_ATTEMPTS = 3
    }

    suspend fun run(batchLimit: Int = 20): Outcome = withContext(Dispatchers.Default) {
        val config = configProvider() ?: return@withContext Outcome.Skipped("未配置 API")
        val messages = pendingMessages(batchLimit)
        if (messages.isEmpty()) return@withContext Outcome.Nothing

        val refs = messages.map {
            BriefingMessageRef(it.id, it.dedupKey, com.infoagent.data.util.DedupKeys.contentHash(it.content), it.recalled, it.lastTs)
        }
        val batchHash = CanonicalHasher.batchHash(refs, config.model)
        val existingJob = db.agentDao().jobByHash(batchHash)
        if (existingJob?.state == "DONE") return@withContext Outcome.Skipped("该批次已处理")
        val previousAttempts = existingJob?.resultJson?.let {
            runCatching { org.json.JSONObject(it).optInt("attempts", 0) }.getOrDefault(0)
        } ?: 0
        val jobId = existingJob?.id ?: db.agentDao().insertJob(
            AutoMemoJobEntity(
                batchHash = batchHash,
                contractVersion = SafeParser.AUTO_MEMO_CONTRACT,
                promptVersion = Prompts.VERSION,
                model = config.model,
                state = "RUNNING",
                createdAt = clock(),
                updatedAt = clock(),
            ),
        )
        val batchMaxId = messages.maxOf { it.id }
        val attemptNow = previousAttempts + 1

        suspend fun fail(reason: String, detail: String): Outcome {
            val payload = org.json.JSONObject().put("reason", detail).put("attempts", attemptNow)
            if (attemptNow >= MAX_ATTEMPTS) {
                // 死信：推进水位，后续消息不再被这一批堵住
                payload.put("dead", true).put("maxMsgId", batchMaxId)
            }
            db.agentDao().updateJob(jobId, "FAILED", payload.toString(), clock())
            return Outcome.Failed(reason)
        }

        if (!budget.canSpend(BudgetGuard.USE_AUTO_MEMO)) {
            db.agentDao().updateJob(
                jobId,
                "FAILED",
                org.json.JSONObject().put("reason", "budget").put("attempts", previousAttempts).toString(),
                clock(),
            )
            return@withContext Outcome.Skipped("今日自动整理额度已用完")
        }

        val lines = messages.map { m ->
            "[#${m.id}] ${clockText(m.ts)} ${m.sender.ifEmpty { m.peerName }}: " +
                "${m.content.replace("\n", " ").take(160)}（${m.peerName}）"
        }
        val prompt = listOf(
            ChatMessage("system", Prompts.AUTO_MEMO_SYSTEM),
            ChatMessage("user", Prompts.autoMemoUserPrompt(lines)),
        )

        val result = try {
            val started = clock()
            client.complete(config, prompt).also { r ->
                runCatching {
                    db.agentDao().insertTrace(
                        com.infoagent.data.db.AgentTraceEntity(
                            callId = java.util.UUID.randomUUID().toString(),
                            useCase = com.infoagent.agent.BudgetGuard.USE_AUTO_MEMO,
                            model = config.model,
                            promptVersion = Prompts.VERSION,
                            contractVersion = SafeParser.AUTO_MEMO_CONTRACT,
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
            return@withContext fail("网络异常：${e.message?.take(80)}", "io")
        } catch (e: Exception) {
            return@withContext fail("调用失败：${e.message?.take(80)}", "error")
        }

        budget.record(BudgetGuard.USE_AUTO_MEMO, result.tokensIn, result.tokensOut)

        when (val parsed = SafeParser.parseAutoMemo(result.content)) {
            is SafeParser.Result.Failed -> fail(parsed.reason, "parse")
            is SafeParser.Result.Ok -> {
                val created = withContext(Dispatchers.IO) {
                    val ids = insertItems(parsed.value, messages, config)
                    db.agentDao().updateJob(
                        jobId,
                        "DONE",
                        org.json.JSONObject()
                            .put("count", ids.size)
                            .put("maxMsgId", batchMaxId)
                            .put("attempts", attemptNow)
                            .toString(),
                        clock(),
                    )
                    ids
                }
                if (created.isEmpty()) Outcome.Nothing else Outcome.Created(created.size, created)
            }
        }
    }

    private suspend fun pendingMessages(limit: Int): List<com.infoagent.data.db.WechatMessageEntity> {
        // 水位取 DONE 与「死信 FAILED」批次的 maxMsgId（A3：死信也要推进，否则队头永久阻塞）
        val lastMax = (db.agentDao().jobsByState("DONE", 500) + db.agentDao().jobsByState("FAILED", 500))
            .mapNotNull { job ->
                job.resultJson?.let { j ->
                    runCatching { org.json.JSONObject(j).optLong("maxMsgId", 0L) }.getOrDefault(0L)
                }
            }
            .maxOrNull() ?: 0L
        return db.messageDao().messagesAfterId(lastMax, limit).filter {
            it.msgType !in setOf("folded", "system") && it.recalled != 1L
        }
    }

    private suspend fun insertItems(
        items: List<AutoMemoItem>,
        messages: List<com.infoagent.data.db.WechatMessageEntity>,
        config: ApiConfig,
    ): List<Long> {
        if (items.isEmpty()) return emptyList()
        val today = dateProvider()
        val byId = messages.associateBy { it.id }
        val recentOpen = db.memoDao().list(today, "TEMP").filter { it.done == 0L }
        val existingTitles = (db.memoDao().list(today, "DAILY") + recentOpen)
            .map { normalize(it.text) }
            .toMutableSet()
        var order = ((db.memoDao().list(today, "TEMP").maxOfOrNull { it.sortOrder }) ?: 0L) + 1L
        val ids = mutableListOf<Long>()
        for (item in items) {
            val key = normalize(item.title)
            if (key.isBlank() || key in existingTitles) continue
            val sourceMsg = item.sourceMsgIds.firstOrNull()?.let { byId[it] }
            val memoId = db.memoDao().insert(
                MemoItemEntity(
                    kind = "TEMP",
                    text = item.title,
                    sortOrder = order++,
                    cycleDate = today,
                    assignedTime = parseIso(item.due),
                    origin = "AI",
                    sourceApp = sourceMsg?.appSource ?: "wechat",
                    sourceConv = sourceMsg?.peerName ?: "",
                    sourceMsgId = sourceMsg?.id,
                    createdAt = clock(),
                    updatedAt = clock(),
                ),
            )
            item.sourceMsgIds.mapNotNull { byId[it] }.forEach { m ->
                db.memoDao().insertSource(MemoSourceEntity(memoId, m.id))
            }
            existingTitles.add(key)
            ids.add(memoId)
        }
        return ids
    }

    /** 标题规范化：去空白/标点、小写，用于 7 天窗口内的近似去重 */
    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("""[\s，。,.!！？?、:："'“”‘’()（）\[\]【】-]"""), "")

    private fun parseIso(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            .recoverCatching { java.time.LocalDateTime.parse(text).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private fun clockText(ts: Long): String =
        java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
            .toLocalTime().toString().take(5)
}