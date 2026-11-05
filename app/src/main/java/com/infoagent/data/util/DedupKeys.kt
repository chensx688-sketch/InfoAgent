package com.infoagent.data.util

import java.security.MessageDigest

/**
 * 去重键与内容哈希。
 * A6 修复：dedup_key 加入**分钟级时间桶**——同一句「好的/收到」隔一分钟再发不会被吞，
 * 而同一条通知被系统重复投递（同一分钟内）仍然去重。
 */
object DedupKeys {

    fun sha256Hex(parts: Iterable<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun timeBucket(tsMillis: Long): Long = tsMillis / 60_000L

    /** dedup_key = sha256(app_source|peer_id|sender|content|notification_key|minuteBucket) */
    fun messageDedupKey(
        appSource: String,
        peerId: String,
        sender: String,
        content: String,
        notificationKey: String,
        tsMillis: Long,
    ): String = sha256Hex(
        listOf(
            appSource,
            peerId,
            sender,
            content,
            notificationKey.ifEmpty { "none" },
            timeBucket(tsMillis).toString(),
        ),
    )

    /** content_hash = sha256(content)，供 §8.7 缓存键使用 */
    fun contentHash(content: String): String = sha256Hex(listOf("content", content))
}