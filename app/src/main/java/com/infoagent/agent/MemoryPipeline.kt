package com.infoagent.agent

import android.content.Context
import com.infoagent.agent.model.MemoryItem
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.MemoryFactEntity
import com.infoagent.data.util.DayBoundary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * 记忆提炼流水线（FR-08 / §8.5）：读近期备忘与反馈 → 模型提炼事实 → 幂等合并进 memory_fact。
 * - 同 subject 合并证据（evidence+1，更新 last_seen）；
 * - 置信度按固定公式重算；DISMISSED 冻结，需新证据 ≥2 且 >40 才复活。
 */
class MemoryPipeline(
    private val context: Context,
    private val db: InfoAgentDatabase,
    private val client: AgentClient,
    private val configProvider: suspend () -> ApiConfig?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dateProvider: () -> String = { DayBoundary.today() },
) {

    private val normalizer = MemoryNormalizer(context)
    private val budget = BudgetGuard(db.agentDao())

    sealed interface Outcome {
        data class Created(val newFacts: Int, val updated: Int) : Outcome
        data object Nothing : Outcome
        data class Skipped(val reason: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    suspend fun run(): Outcome = withContext(Dispatchers.Default) {
        val config = configProvider() ?: return@withContext Outcome.Skipped("未配置 API")
        val today = dateProvider()
        val from = DayBoundary.shift(today, -7)
        // B4：阶段事件也纳入记忆素材（跨天任务往往是长期习惯/偏好线索）
        val memos = (db.memoDao().unarchivedDailyBefore(today) +
            db.memoDao().list(today, "DAILY") +
            db.memoDao().list(today, "TEMP") +
            db.memoDao().listKind("STAGE")).distinctBy { it.id }
        val archived = db.memoDao().archiveOf(today) + db.memoDao().archiveOf(DayBoundary.shift(today, -1))
        // B3：用真实的卡片级反馈（brief_feedback），不再把 useCase 名当成"反馈"喂给模型
        val feedbackLines = db.agentDao().recentBriefFeedback(20).map { f ->
            val vote = if ((f.vote ?: 0L) >= 1L) "有用" else "没用"
            "${f.section ?: "brief"}#${f.itemIndex ?: 0}: $vote"
        }
        if (memos.isEmpty() && archived.isEmpty()) return@withContext Outcome.Nothing

        if (!budget.canSpend(BudgetGuard.USE_MEMORY)) return@withContext Outcome.Skipped("今日记忆提炼额度已用完")

        val memoLines = (memos.map { "memo:${it.id} ${it.text}${if (it.done == 1L) " [已完成]" else ""}" } +
            archived.map { "archive:${it.id} ${it.text}${if (it.done == 1L) " [已完成]" else ""}" })
            .take(120)
        val prompt = listOf(
            ChatMessage("system", Prompts.MEMORY_SYSTEM),
            ChatMessage("user", Prompts.memoryUserPrompt(memoLines, feedbackLines)),
        )

        val result = try {
            client.complete(config, prompt)
        } catch (e: IOException) {
            return@withContext Outcome.Failed("网络异常：${e.message?.take(80)}")
        } catch (e: Exception) {
            return@withContext Outcome.Failed("调用失败：${e.message?.take(80)}")
        }

        budget.record(BudgetGuard.USE_MEMORY, result.tokensIn, result.tokensOut)

        when (val parsed = SafeParser.parseMemory(result.content)) {
            is SafeParser.Result.Failed -> Outcome.Failed(parsed.reason)
            is SafeParser.Result.Ok -> {
                var created = 0
                var updated = 0
                for (fact in parsed.value) {
                    when (merge(fact)) {
                        MergeResult.CREATED -> created++
                        MergeResult.UPDATED -> updated++
                        MergeResult.SKIPPED -> Unit
                    }
                }
                if (created == 0 && updated == 0) Outcome.Nothing else Outcome.Created(created, updated)
            }
        }
    }

    private enum class MergeResult { CREATED, UPDATED, SKIPPED }

    private suspend fun merge(item: MemoryItem): MergeResult {
        val subject = normalizer.normalize(item.subject ?: item.factText)
        val now = clock()
        val existing = if (subject.isNotBlank()) db.memoryDao().factBySubject(subject) else null
        val refs = item.refs.joinToString(",")
        if (existing == null) {
            db.memoryDao().insertFact(
                MemoryFactEntity(
                    category = item.category,
                    subject = subject.ifBlank { item.factText.take(20) },
                    factText = item.factText,
                    confidence = MemoryMath.confidence(1, 0, 0.0).toLong(),
                    evidenceCount = 1,
                    lastSeen = now,
                    sourceRefs = refs,
                    periodHint = item.period?.let { JSONObject().put("freq", it.freq).put("weekday", it.weekday).put("time", it.time).toString() },
                    status = "ACTIVE",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            return MergeResult.CREATED
        }

        val days = ((now - (existing.lastSeen ?: now)).toDouble() / 86_400_000.0).coerceAtLeast(0.0)
        val newEvidence = countNewEvidence(existing.sourceRefs, refs)
        if (existing.status == "DISMISSED") {
            return if (MemoryMath.canRevive(newEvidence, existing.evidenceCount.toInt(), feedbackSum(existing), days)) {
                db.memoryDao().updateEvidence(
                    existing.id,
                    MemoryMath.confidence(
                        existing.evidenceCount.toInt() + newEvidence,
                        existing.feedbackSum.toInt(),
                        days,
                    ).toLong(),
                    existing.evidenceCount + newEvidence,
                    existing.feedbackSum,
                    now,
                )
                MergeResult.UPDATED
            } else {
                MergeResult.SKIPPED
            }
        }
        if (newEvidence <= 0) return MergeResult.SKIPPED
        val evidence = existing.evidenceCount + newEvidence
        db.memoryDao().updateEvidence(
            existing.id,
            MemoryMath.confidence(evidence.toInt(), existing.feedbackSum.toInt(), days).toLong(),
            evidence,
            existing.feedbackSum,
            now,
        )
        return MergeResult.UPDATED
    }

    private fun countNewEvidence(oldRefs: String?, newRefs: String): Int {
        if (newRefs.isBlank()) return 0
        val old = oldRefs.orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        val incoming = newRefs.split(',').filter { it.isNotBlank() }
        return incoming.count { it !in old }
    }

    /** A7：反馈累加值从库里读，合并时用真实值重算，用户 👍👎 不再被抹掉 */
    private fun feedbackSum(existing: MemoryFactEntity): Int = existing.feedbackSum.toInt()

    suspend fun applyFeedback(factId: Long, up: Boolean) {
        val fact = db.memoryDao().let { dao ->
            dao.topActiveFacts(50).firstOrNull { it.id == factId } ?: return
        }
        val nextFeedback = fact.feedbackSum + if (up) 2 else -5
        val days = fact.lastSeen?.let { ((clock() - it) / 86_400_000L).toDouble() } ?: 0.0
        val next = MemoryMath.confidence(fact.evidenceCount.toInt(), nextFeedback.toInt(), days)
            .coerceAtLeast(0)
            .toLong()
        db.memoryDao().updateConfidence(
            factId,
            next,
            nextFeedback,
            if (next <= 0) "DISMISSED" else "ACTIVE",
            clock(),
        )
    }
}