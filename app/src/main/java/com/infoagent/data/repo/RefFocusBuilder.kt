package com.infoagent.data.repo

import com.infoagent.data.db.WechatMessageEntity

/**
 * 简报要点 → 原文的一条「定位结果」。
 *
 * 为什么单独抽出来：这是纯数据拼装（排序/标记/凑上下文），
 * 放在 Composable 里就只能靠肉眼看，抽出来才能用单测守住三条：
 * 顺序必须是时间顺序、被引用的那几条必须被标记、消息被清理时要给出可读原因而不是白屏。
 */
data class RefRow(
    val msg: WechatMessageEntity,
    /** 这条就是简报导语的依据（高亮） */
    val focused: Boolean,
)

data class RefFocus(
    /** 会话名，如「陈英钿」 */
    val title: String,
    val rows: List<RefRow>,
    val focusedCount: Int,
    /** 找不到时的说明（例如消息已被清理） */
    val note: String? = null,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

object RefFocusBuilder {

    /** 上下文窗口：目标消息前后各 3 小时，够看清「他前面说了什么」 */
    const val CONTEXT_WINDOW_MS = 3L * 60 * 60 * 1000

    /**
     * @param focused 简报道语直接引用的消息（可跨会话，但只取第一条所在会话做上下文）
     * @param context 该会话在时间窗内的消息
     */
    fun build(focused: List<WechatMessageEntity>, context: List<WechatMessageEntity>): RefFocus {
        if (focused.isEmpty() && context.isEmpty()) {
            return RefFocus(title = "", rows = emptyList(), focusedCount = 0, note = "这些消息已经被清理，看不到了")
        }
        val ids = focused.map { it.id }.toSet()
        val merged = (focused + context)
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.ts }, { it.id }))
            .map { RefRow(it, it.id in ids) }

        val title = (focused.firstOrNull() ?: context.firstOrNull())?.let {
            it.peerName.ifBlank { it.peerId }
        }.orEmpty()

        return RefFocus(
            title = title,
            rows = merged,
            focusedCount = merged.count { it.focused },
            note = if (focused.isEmpty()) "找不到被引用的原文，下面是同一时段的会话记录" else null,
        )
    }

    /** 上下文时间窗（以最早的那条引用为准） */
    fun window(focused: List<WechatMessageEntity>): LongRange {
        val anchor = focused.minOfOrNull { it.ts } ?: 0L
        return (anchor - CONTEXT_WINDOW_MS)..(anchor + CONTEXT_WINDOW_MS)
    }
}