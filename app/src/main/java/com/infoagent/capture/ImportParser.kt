package com.infoagent.capture

import com.infoagent.data.repo.IncomingMessage

/**
 * 手动导入解析（§5 消息页「导入」）：逐行「名字: 内容」；
 * 无前缀的行归入上一条前缀的会话（连续聊天记录粘贴友好）。
 */
object ImportParser {

    private val PREFIX = Regex("""^\s*([^:：\n]{1,30}?)[:：]\s?(.*)$""")

    fun parse(text: String, baseTs: Long = System.currentTimeMillis()): List<IncomingMessage> {
        val lines = text.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val result = mutableListOf<IncomingMessage>()
        var currentPeer: String? = null
        var step = 0
        for (line in lines) {
            val m = PREFIX.matchEntire(line)
            val peer: String
            val content: String
            if (m != null) {
                peer = m.groupValues[1].trim()
                content = m.groupValues[2].trim()
                currentPeer = peer
            } else {
                peer = currentPeer ?: return emptyList() // 无任何前缀且无上下文：视为无效输入
                content = line
            }
            if (content.isEmpty()) continue
            result.add(
                IncomingMessage(
                    appSource = "wechat",
                    peerId = peer,
                    peerName = peer,
                    sender = peer,
                    direction = "IN",
                    msgType = "text",
                    content = content,
                    ts = baseTs - (lines.size - step) * 1000L, // 保持行序
                    notificationKey = "",
                ),
            )
            step++
        }
        return result
    }
}