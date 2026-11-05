package com.infoagent.agent

import com.infoagent.data.util.DedupKeys

/** 一次简报调用所依赖的全部输入指纹（§8.7，D04：含内容哈希，撤回/编辑即失效缓存） */
data class BriefingInput(
    val bizDate: String,
    val window: String,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    val messages: List<BriefingMessageRef>,
    val memoSnapshot: String,
    val topFacts: List<Pair<Long, Long>>, // fact id → confidence
    /** A3/A5：关注会话参与排序与缓存键——关注/取消关注后应重新生成 */
    val focus: Set<String> = emptySet(),
    val contractVersion: String = SafeParser.BRIEF_CONTRACT,
    val promptVersion: String = Prompts.VERSION,
)

data class BriefingMessageRef(
    val id: Long,
    val dedupKey: String,
    val contentHash: String,
    val recalled: Long,
    val lastTs: Long,
)

object CanonicalHasher {

    fun inputHash(input: BriefingInput): String {
        // A4：lastTs 不再进缓存键——重复通知只刷新 last_ts 时不应击穿缓存；内容/撤回仍生效
        val messagePart = input.messages.sortedBy { it.id }
            .joinToString(",") { "${it.id}:${it.dedupKey}:${it.contentHash}:${it.recalled}" }
        val factsPart = input.topFacts.sortedBy { it.first }
            .joinToString(",") { "${it.first}:${it.second}" }
        return DedupKeys.sha256Hex(
            listOf(
                input.bizDate,
                input.window,
                input.contractVersion,
                input.promptVersion,
                input.model,
                input.temperature.toString(),
                input.maxTokens.toString(),
                messagePart,
                input.memoSnapshot,
                factsPart,
                input.focus.sorted().joinToString(","),
            ),
        )
    }

    fun batchHash(messageRefs: List<BriefingMessageRef>, model: String, promptVersion: String = Prompts.VERSION): String {
        val part = messageRefs.sortedBy { it.id }
            .joinToString(",") { "${it.id}:${it.dedupKey}:${it.contentHash}:${it.recalled}" }
        return DedupKeys.sha256Hex(listOf("auto_memo", SafeParser.AUTO_MEMO_CONTRACT, promptVersion, model, part))
    }
}