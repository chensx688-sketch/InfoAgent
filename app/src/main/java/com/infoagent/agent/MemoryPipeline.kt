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
        val key = subject.ifBlank { item.factText.take(20) }
        val refs = item.refs.joinToString(",")
        val dao = db.memoryDao()

        // ① 同 subject 还有效的那条：要么是同一件事再被提到（累加证据），要么事情变了（旧行失效 + 新增）
        val active = if (subject.isNotBlank()) dao.activeBySubject(subject) else null
        if (active != null) {
            if (sameFact(active.category, active.factText.orEmpty(), item.factText)) {
                return accumulate(active, refs, now)
            }
            dao.supersede(active.id, now)
            insertNew(item, subject, key, refs, now)
            return MergeResult.CREATED
        }

        // ② 没有有效行，但可能有一条被用户忽略过的：仍按「需要新证据才复活」处理
        val latest = if (subject.isNotBlank()) dao.latestBySubject(subject) else null
        if (latest != null && latest.status == "DISMISSED") {
            val days = ((now - (latest.lastSeen ?: now)).toDouble() / 86_400_000.0).coerceAtLeast(0.0)
            val newEvidence = countNewEvidence(latest.sourceRefs, refs)
            return if (MemoryMath.canRevive(newEvidence, latest.evidenceCount.toInt(), feedbackSum(latest), days)) {
                dao.updateEvidence(
                    latest.id,
                    MemoryMath.confidence(
                        latest.evidenceCount.toInt() + newEvidence,
                        latest.feedbackSum.toInt(),
                        days,
                    ).toLong(),
                    latest.evidenceCount + newEvidence,
                    latest.feedbackSum,
                    now,
                )
                MergeResult.UPDATED
            } else {
                MergeResult.SKIPPED
            }
        }
        if (latest != null && sameFact(latest.category, latest.factText.orEmpty(), item.factText) &&
            countNewEvidence(latest.sourceRefs, refs) <= 0
        ) {
            return MergeResult.SKIPPED
        }

        insertNew(item, subject, key, refs, now)
        return MergeResult.CREATED
    }

    /** 同一件事又出现一次：只累加证据，不新增行（这不是"新事实"） */
    private suspend fun accumulate(existing: MemoryFactEntity, refs: String, now: Long): MergeResult {
        val newEvidence = countNewEvidence(existing.sourceRefs, refs)
        if (newEvidence <= 0) return MergeResult.SKIPPED
        val days = ((now - (existing.lastSeen ?: now)).toDouble() / 86_400_000.0).coerceAtLeast(0.0)
        val evidence = existing.evidenceCount + newEvidence
        val mergedRefs = listOf(existing.sourceRefs.orEmpty(), refs)
            .filter { it.isNotBlank() }.joinToString(",")
        db.memoryDao().updateEvidence(
            existing.id,
            MemoryMath.confidence(evidence.toInt(), existing.feedbackSum.toInt(), days).toLong(),
            evidence,
            existing.feedbackSum,
            now,
        )
        db.memoryDao().addRefs(existing.id, mergedRefs, now)
        return MergeResult.UPDATED
    }

    private suspend fun insertNew(item: MemoryItem, subject: String, key: String, refs: String, now: Long) {
        db.memoryDao().insertFact(
            MemoryFactEntity(
                category = item.category,
                subject = key,
                factText = item.factText,
                confidence = MemoryMath.confidence(1, 0, 0.0).toLong(),
                evidenceCount = 1,
                lastSeen = now,
                sourceRefs = refs,
                periodHint = item.period?.let {
                    JSONObject().put("freq", it.freq).put("weekday", it.weekday).put("time", it.time).toString()
                },
                status = "ACTIVE",
                createdAt = now,
                updatedAt = now,
                validFrom = now,
                validTo = null,
            ),
        )
    }

    /**
     * 是不是「同一件事」。
     *
     * 分类型的理由：偏好/习惯变了就是变了（"不吃香菜" → "现在能吃了"），必须换新值；
     * 关系/规律常常只是同一件事被说得更细（"他喜欢咖啡" → "他喜欢手冲咖啡"），
     * 一律当新事实会让记忆表里塞满近义条目。
     */
    private fun sameFact(category: String, old: String, incoming: String): Boolean {
        val a = old.trim()
        val b = incoming.trim()
        if (a.isEmpty() || b.isEmpty()) return true
        if (a == b) return true
        return when (category) {
            "PREFERENCE", "HABIT" -> false
            else -> overlap(a, b) >= 0.6
        }
    }

    /** 二元组重合度：用作「说得更细」的近似判断，比编辑距离更能容忍中文改写 */
    private fun overlap(a: String, b: String): Double {
        fun grams(s: String) = if (s.length < 2) setOf(s) else s.windowed(2).toSet()
        val ga = grams(a)
        val gb = grams(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        return ga.intersect(gb).size.toDouble() / minOf(ga.size, gb.size)
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
