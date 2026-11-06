package com.infoagent.ui.messages

import com.infoagent.data.db.WechatMessageEntity
import com.infoagent.ui.util.TimeFormat

/**
 * 会话详情页的「行」模型。
 *
 * 为什么单独抽出来：一眼看去是排版问题，实际是**分组规则**问题——谁和谁算一段、
 * 什么时候该插时间。规则写成纯函数，才能用测试钉住（现在这些 UI 逻辑否则只能靠肉眼）。
 */
sealed interface ChatRow {

    /** 时间分隔：day 非空 = 换天（显示「今天 15:20」），day 为空 = 只是隔得久了（显示「15:20」） */
    data class Separator(val day: String?, val time: String) : ChatRow

    /** 一条消息。pos 决定圆角：只有一簇的最后一条带"小尾巴" */
    data class Msg(
        val message: WechatMessageEntity,
        val pos: Pos,
        val showSender: Boolean,
    ) : ChatRow

    enum class Pos { SINGLE, FIRST, MIDDLE, LAST }
}

object ChatRows {

    /** 同一簇内最多容忍的间隔：超过就当"新的一段"，重新插时间 */
    const val CLUSTER_GAP_MS = 3 * 60_000L

    /** 多大的间隔才值得单独插一行时间（太密会变成噪音） */
    const val SEPARATOR_GAP_MS = 5 * 60_000L

    /**
     * 按时间升序切成「行」。
     *
     * 规则：
     * 1. 同方向、同发送者（仅群聊比较）、间隔 ≤3 分钟 → 同一簇，只第一条显示名字；
     * 2. 换天 → 日期分隔；同一天但距上一簇 >5 分钟 → 时间分隔；
     * 3. 第一簇永远带日期分隔（不然不知道是哪天的）。
     */
    fun build(messages: List<WechatMessageEntity>, isGroup: Boolean): List<ChatRow> {
        if (messages.isEmpty()) return emptyList()
        val ascending = messages.sortedWith(compareBy({ it.ts }, { it.id }))

        val clusters = mutableListOf<MutableList<WechatMessageEntity>>()
        for (m in ascending) {
            val current = clusters.lastOrNull()
            val prev = current?.lastOrNull()
            val sameCluster = prev != null &&
                prev.direction == m.direction &&
                (!isGroup || prev.sender == m.sender) &&
                m.ts - prev.ts <= CLUSTER_GAP_MS
            if (sameCluster) current!!.add(m) else clusters.add(mutableListOf(m))
        }

        val rows = mutableListOf<ChatRow>()
        var lastDay: String? = null
        var lastTs = 0L
        clusters.forEachIndexed { index, cluster ->
            val first = cluster.first()
            val day = TimeFormat.dayLabel(first.ts)
            val time = TimeFormat.clock(first.ts)
            if (day != lastDay) {
                rows.add(ChatRow.Separator(day, time))
            } else if (first.ts - lastTs > SEPARATOR_GAP_MS) {
                rows.add(ChatRow.Separator(null, time))
            }
            cluster.forEachIndexed { i, message ->
                val pos = when {
                    cluster.size == 1 -> ChatRow.Pos.SINGLE
                    i == 0 -> ChatRow.Pos.FIRST
                    i == cluster.lastIndex -> ChatRow.Pos.LAST
                    else -> ChatRow.Pos.MIDDLE
                }
                rows.add(
                    ChatRow.Msg(
                        message = message,
                        pos = pos,
                        // 名字只在群的「别人说话」簇首显示：自己的消息和单聊都不需要
                        showSender = isGroup && i == 0 &&
                            message.direction == "IN" && message.sender.isNotBlank(),
                    ),
                )
            }
            lastDay = day
            lastTs = cluster.last().ts
        }
        return rows
    }

    /** 非文字消息的翻译：界面里不许再出现 `image`、`file` 这种原始类型名 */
    fun typeLabel(msgType: String): String? = when (msgType.lowercase()) {
        "text", "" -> null
        "image", "img", "picture", "photo" -> "图片"
        "voice", "audio", "sound" -> "语音"
        "video", "movie" -> "视频"
        "file", "doc", "document" -> "文件"
        "link", "url" -> "链接"
        "location", "map", "place" -> "位置"
        "emoji", "sticker", "gif" -> "表情"
        "redpacket", "red_packet" -> "红包"
        "card" -> "名片"
        "call" -> "通话"
        else -> "非文字"
    }
}