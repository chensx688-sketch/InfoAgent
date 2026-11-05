package com.infoagent.agent

import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * 记忆置信度公式（§8.5 定稿）：
 * confidence = clamp(50 + 8×log2(1+evidence_count) + Σfeedback − decay×days_since_last_seen, 0, 100)
 * decay 默认 2；👍 +2、👎 −5；evidence 仅同 subject 新证据 +1；DISMISSED 后冻结。
 */
object MemoryMath {

    const val BASE = 50
    const val DECAY = 2.0
    const val FEEDBACK_UP = 2
    const val FEEDBACK_DOWN = -5

    fun confidence(evidenceCount: Int, feedbackSum: Int, daysSinceLastSeen: Double): Int {
        val raw = BASE + 8.0 * log2(1.0 + evidenceCount.coerceAtLeast(0)) + feedbackSum -
            DECAY * daysSinceLastSeen.coerceAtLeast(0.0)
        return raw.roundToInt().coerceIn(0, 100)
    }

    /** DISMISSED 复活门槛：新证据 ≥2 且重算置信度 > 40 */
    fun canRevive(newEvidence: Int, evidenceCount: Int, feedbackSum: Int, daysSinceLastSeen: Double): Boolean {
        if (newEvidence < 2) return false
        return confidence(evidenceCount + newEvidence, feedbackSum, daysSinceLastSeen) > 40
    }

    fun applyFeedback(current: Int, up: Boolean): Int =
        (current + if (up) FEEDBACK_UP else FEEDBACK_DOWN).coerceIn(0, 100)
}