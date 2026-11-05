package com.infoagent.capture

import com.infoagent.data.repo.IncomingMessage

/** 通知的原始快照（由 NotificationListenerService 组装，便于纯逻辑测试） */
data class RawNotification(
    val packageName: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val tag: String? = null,
    val category: String? = null,
    val postTime: Long,
    val isOngoing: Boolean = false,
    /** A5：会话是否已知为群聊（由会话表查询后传入）；单聊不再按冒号切分 */
    val knownGroup: Boolean = false,
    /** N1：历史出现过的发送者前缀（用于新群首条消息也能正确切分） */
    val knownSenders: Set<String> = emptySet(),
)

sealed interface ParseOutcome {
    /** 正常解析出的一条或多条入站消息 */
    data class Messages(val messages: List<IncomingMessage>) : ParseOutcome

    /** 撤回通知（§8.8：不猜，只给候选会话与发送者） */
    data class Withdraw(val peerName: String, val sender: String, val isGroup: Boolean) : ParseOutcome

    /** 折叠通知「N 条新消息」：占位行，不生成 AI 备忘（D48） */
    data class Folded(val peerName: String, val count: Int) : ParseOutcome

    /** 无正文或系统类通知：忽略 */
    data object Ignored : ParseOutcome
}

/**
 * 微信/QQ 通知解析（§8.8 规则表）：
 * - 标题=会话名；群聊正文「发送者: 内容」；单聊正文即内容；
 * - 媒体占位符映射 msg_type；折叠「N条新消息」→ 占位；撤回 → Withdraw 候选；
 * - 多行 bigText 逐行拆解；连续重复行去重。
 */
object NotificationParser {

    const val WECHAT = "com.tencent.mm"
    const val QQ = "com.tencent.mobileqq"

    private val SUPPORTED = setOf(WECHAT, QQ)

    private val FOLDED = Regex("""(?:你?收到)?(?:\[)?(\d+)\s*条(?:新)?消息""")

    // 撤回：可能是「张三」撤回了一条消息 / 张三撤回了一条消息 / 你撤回了一条消息
    private val WITHDRAW = Regex("""(?:[「（(]?([^」）)]{1,40}?)[」）)]?\s*)?撤回了一条消息""")

    // 群聊发送者前缀："张三: 内容"、张三：内容、[回复李四] xxx 保留原文
    private val SENDER_PREFIX = Regex("""^\s*([^:：\n]{1,30}?)[:：]\s?(.*)$""")

    /** 冒号前的「标签词」不是发送者（A5 的反例集合） */
    private val LABELS = setOf(
        "会议地点", "时间", "地点", "地址", "主题", "内容", "备注", "价格", "日程",
        "提醒", "注意", "标题", "说明", "电话", "链接", "预算", "工期", "费用",
    )

    /** B2：供 Service 过滤历史发送者用 */
    fun isLabelWord(word: String): Boolean = word.trim() in LABELS

    /** 同一批多行里出现的不同前缀（≥2 视为群聊证据，N1 修复的一部分） */
    private fun distinctPrefixes(lines: List<String>): Set<String> = lines.mapNotNull { line ->
        val m = SENDER_PREFIX.matchEntire(line) ?: return@mapNotNull null
        val p = m.groupValues[1].trim()
        if (p.length in 1..12 && p.none { it.isDigit() } && p !in LABELS) p else null
    }.toSet()

    private val SYSTEM_HINTS = listOf(
        "你已成为", "邀请你", "修改群名", "加入了群聊", "退出了群聊",
        "被移出", "群公告", "你有一条新消息", "对方正在输入",
    )

    private val TYPE_PLACEHOLDERS = mapOf(
        "[图片]" to "image",
        "[语音]" to "voice",
        "[视频]" to "video",
        "[文件]" to "file",
        "[表情]" to "emoji",
        "[动画表情]" to "emoji",
        "[链接]" to "link",
        "[红包]" to "redpacket",
        "[转账]" to "transfer",
        "[位置]" to "location",
        "[名片]" to "card",
        "[视频通话]" to "call",
        "[语音通话]" to "call",
    )

    fun isSupported(packageName: String) = packageName in SUPPORTED

    fun parse(raw: RawNotification): ParseOutcome {
        if (!isSupported(raw.packageName)) return ParseOutcome.Ignored
        var peerName = raw.title?.trim().orEmpty()
        if (peerName.isEmpty()) return ParseOutcome.Ignored

        var body = (raw.bigText?.takeIf { it.isNotBlank() } ?: raw.text?.takeIf { it.isNotBlank() }).orEmpty()

        // 少数版本把「发送者: 内容」塞进标题：拆开，避免把会话名当正文
        SENDER_PREFIX.matchEntire(peerName)?.let { m ->
            val head = m.groupValues[1].trim()
            val tail = m.groupValues[2].trim()
            if (head.isNotEmpty() && tail.isNotEmpty() && body.isEmpty()) {
                peerName = head
                body = tail
            }
        }
        if (body.isEmpty()) return ParseOutcome.Folded(peerName, 0)

        FOLDED.find(body)?.let { m ->
            if (body.trim().length <= 20) return ParseOutcome.Folded(peerName, m.groupValues[1].toIntOrNull() ?: 0)
        }

        WITHDRAW.find(body)?.let { m ->
            val who = m.groupValues[1].trim().ifEmpty { "" }.ifEmpty { peerName }
            val looksGroup = who.isNotEmpty() && who != peerName
            return ParseOutcome.Withdraw(peerName = peerName, sender = who, isGroup = looksGroup)
        }

        if (SYSTEM_HINTS.any { body.contains(it) }) return ParseOutcome.Ignored

        val isQQ = raw.packageName == QQ
        val notificationKey = buildString {
            if (!raw.tag.isNullOrBlank()) append(raw.tag)
            append(':').append(raw.category.orEmpty())
        }

        val lines = body.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (lines.isEmpty()) return ParseOutcome.Ignored

        val prefixes = distinctPrefixes(lines)
        // B2（收紧后可纠错）：群聊证据 =
        //  1) 系统/会话表已判群；
        //  2) 历史发送者与本次前缀有交集，且并集 ≥2；
        //  3) 并集累计 ≥3 个不同人名。
        // 仅"本批多行两个前缀"只算疑似：会切 sender 但不写 isGroup，等下一次命中再升级，避免单聊里
        // 「预算：5万 / 工期：2周」这种两行被永久标成群。
        val history = raw.knownSenders.filterNot { it.isBlank() || it.length > 12 }
        val union = (history + prefixes).toSet()
        val historyHits = prefixes.intersect(history.toSet())
        val groupEvidence = raw.knownGroup ||
            (historyHits.isNotEmpty() && union.size >= 2) ||
            union.size >= 3
        val suspectedMultiSender = prefixes.size >= 2

        val messages = lines.mapNotNull { rawLine ->
            // 去掉「群名: 内容」里与标题重复的会话名前缀，避免群名被当成正文
            var line = rawLine
            if (line.startsWith("$peerName:") || line.startsWith("$peerName：")) {
                line = line.substring(peerName.length + 1).trim()
                if (line.isEmpty()) return@mapNotNull null
            }
            // 整行就是会话名（典型误读）→ 丢弃
            if (line == peerName) return@mapNotNull null

            val m = SENDER_PREFIX.matchEntire(line)
            val prefix = m?.groupValues?.get(1)?.trim()
            val looksLikeSender = m != null && prefix != null &&
                prefix != peerName &&
                prefix.length in 1..12 &&
                prefix.none { it.isDigit() } &&
                !prefix.contains("://") &&
                !prefix.lowercase().startsWith("http") &&
                prefix !in LABELS
            // 单聊正文里的「会议地点：A 座」「https://…」不切分；
            // 有群聊证据或"疑似多发送者"时切 sender，但只有 groupEvidence 才把会话标成群。
            val split = looksLikeSender && (groupEvidence || suspectedMultiSender)
            val sender: String
            val content: String
            val group: Boolean
            if (split && m != null && prefix != null) {
                sender = prefix
                content = m.groupValues[2].trim()
                // B2：疑似多发送者只切 sender，不把会话标成群（等下一次命中再升级）
                group = groupEvidence
            } else if (m != null && prefix == peerName) {
                sender = peerName
                content = m.groupValues[2].trim()
                group = groupEvidence
            } else {
                sender = peerName
                content = line
                group = groupEvidence
            }
            if (content.isEmpty() || content == peerName) return@mapNotNull null
            // QQ 群@前缀保留原文（规则见 §13 FR-28）
            val msgType = TYPE_PLACEHOLDERS.entries.firstOrNull { content.startsWith(it.key) }?.value ?: "text"
            IncomingMessage(
                appSource = if (isQQ) "qq" else "wechat",
                peerId = peerName,
                peerName = peerName,
                sender = sender,
                direction = "IN",
                msgType = msgType,
                content = content,
                ts = raw.postTime,
                notificationKey = notificationKey,
                isGroup = group,
            )
        }
        return if (messages.isEmpty()) ParseOutcome.Ignored else ParseOutcome.Messages(messages)
    }
}