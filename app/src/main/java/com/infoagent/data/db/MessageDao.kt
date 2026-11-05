package com.infoagent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: WechatMessageEntity): Long

    @Query("DELETE FROM wechat_message WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM wechat_message WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT EXISTS(SELECT 1 FROM wechat_message WHERE dedup_key = :key)")
    suspend fun existsByDedupKey(key: String): Boolean

    @Query("SELECT COUNT(*) FROM wechat_message")
    suspend fun countAll(): Long

    @Query("SELECT COUNT(*) FROM wechat_message WHERE ts >= :from")
    suspend fun countSince(from: Long): Long

    @Query("SELECT * FROM wechat_message WHERE dedup_key = :key LIMIT 1")
    suspend fun findByDedupKey(key: String): WechatMessageEntity?

    @Query("SELECT * FROM wechat_message WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): WechatMessageEntity?

    // 重复通知：只刷新 last_ts（D06，不新增）
    @Query("UPDATE wechat_message SET last_ts = :ts WHERE dedup_key = :key")
    suspend fun touchLastTs(key: String, ts: Long)

    @Query("UPDATE wechat_message SET recalled = :recalled WHERE id = :id")
    suspend fun updateRecalled(id: Long, recalled: Long)

    @Query("UPDATE wechat_message SET flagged = :flag WHERE id = :id")
    suspend fun updateFlagged(id: Long, flag: Long)

    @Query("UPDATE wechat_message SET archived = 1 WHERE id = :id")
    suspend fun markArchived(id: Long)

    @Query("SELECT * FROM wechat_message WHERE peer_id = :peerId AND ts >= :since ORDER BY ts ASC LIMIT :limit")
    fun observeMessages(peerId: String, since: Long, limit: Int = 200): Flow<List<WechatMessageEntity>>

    @Query("SELECT * FROM wechat_message ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<WechatMessageEntity>>

    @Query("SELECT * FROM wechat_message WHERE ts BETWEEN :from AND :to ORDER BY ts ASC")
    suspend fun messagesBetween(from: Long, to: Long): List<WechatMessageEntity>

    @Query("SELECT * FROM wechat_message WHERE ts >= :since ORDER BY ts ASC LIMIT :limit")
    suspend fun messagesSince(since: Long, limit: Int): List<WechatMessageEntity>

    /** 自动整理的水位查询：按**消息 id** 推进（A3），不要与按时间的 messagesSince 混用 */
    @Query("SELECT * FROM wechat_message WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun messagesAfterId(afterId: Long, limit: Int): List<WechatMessageEntity>

    /** 当前窗口内的消息条数（切换简报栏目时显示差异） */
    @Query("SELECT COUNT(*) FROM wechat_message WHERE ts >= :from AND ts < :to")
    suspend fun countBetween(from: Long, to: Long): Int

    /** C10：中文单字/跨词命中不了 FTS(bigram) 时的 LIKE 兜底 */
    @Query("SELECT * FROM wechat_message WHERE content LIKE '%' || :q || '%' ORDER BY ts DESC LIMIT :limit")
    suspend fun searchLike(q: String, limit: Int): List<WechatMessageEntity>

    data class PeerCount(
        @androidx.room.ColumnInfo(name = "peerId") val peerId: String,
        @androidx.room.ColumnInfo(name = "cnt") val cnt: Long,
    )

    /** C11：每个会话的总条数（SQL 聚合，不再只统计最近 400 条） */
    @Query("SELECT peer_id AS peerId, COUNT(*) AS cnt FROM wechat_message GROUP BY peer_id")
    suspend fun countsByPeer(): List<PeerCount>

    @Query("SELECT peer_id AS peerId, COUNT(*) AS cnt FROM wechat_message GROUP BY peer_id")
    fun observeCountsByPeer(): kotlinx.coroutines.flow.Flow<List<PeerCount>>

    @Query("SELECT * FROM wechat_message WHERE id IN (:ids) ORDER BY ts ASC")
    suspend fun byIds(ids: List<Long>): List<WechatMessageEntity>

    // 撤回匹配候选（§8.8：同会话、同 sender、方向 IN、时间窗 2 分钟）
    @Query(
        "SELECT * FROM wechat_message WHERE peer_id = :peerId AND sender = :sender AND direction = 'IN' " +
            "AND ts BETWEEN :from AND :to AND recalled = 0 ORDER BY ts DESC LIMIT 3"
    )
    suspend fun withdrawCandidates(peerId: String, sender: String, from: Long, to: Long): List<WechatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    /** B5：插入沿用 IGNORE，避免每条消息 REPLACE 掉 priority/muted */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversationIgnore(conversation: ConversationEntity): Long

    @Query("UPDATE conversation SET name = :name, is_group = :isGroup, last_ts = :lastTs WHERE peer_id = :peerId")
    suspend fun updateConversationFromMessage(peerId: String, name: String, isGroup: Long, lastTs: Long)

    /** B2：历史发送者（sender 列，过滤掉等于会话名的） */
    @Query("SELECT DISTINCT sender FROM wechat_message WHERE peer_id = :peer AND sender != :peer AND sender != '' LIMIT 30")
    suspend fun senderNames(peer: String): List<String>

    @Query("UPDATE conversation SET is_group = :isGroup WHERE peer_id = :peerId")
    suspend fun setConversationGroup(peerId: String, isGroup: Long)

    /** B2 纠正为单聊后：把历史 sender 归并回会话名，避免前缀继续把它判成群 */
    @Query("UPDATE wechat_message SET sender = :peerName WHERE peer_id = :peerId")
    suspend fun resetSenders(peerId: String, peerName: String)

    @Query("UPDATE conversation SET last_ts = :ts WHERE peer_id = :peerId")
    suspend fun touchConversation(peerId: String, ts: Long)

    @Query("UPDATE conversation SET priority = :priority WHERE peer_id = :peerId")
    suspend fun setConversationPriority(peerId: String, priority: Long)

    @Query("SELECT * FROM conversation ORDER BY COALESCE(last_ts,0) DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE peer_id = :peerId LIMIT 1")
    suspend fun conversation(peerId: String): ConversationEntity?

    @Query("SELECT * FROM watch_rule")
    fun observeWatchRules(): Flow<List<WatchRuleEntity>>

    @Query("SELECT value FROM watch_rule WHERE rule_type = 'PEER' AND action = 'FOCUS'")
    suspend fun focusPeers(): List<String>

    /** R5：关注会话真正落库（此前 watch_rule 只有读没有写） */
    @Insert
    suspend fun insertWatchRule(rule: WatchRuleEntity): Long

    @Query("DELETE FROM watch_rule WHERE rule_type = 'PEER' AND value = :peerId")
    suspend fun clearFocus(peerId: String)

    @Query("SELECT * FROM attach_item WHERE msg_id = :msgId")
    suspend fun attachesOf(msgId: Long): List<AttachItemEntity>
}