package com.infoagent.data.repo

import androidx.room.withTransaction
import com.infoagent.data.db.ConversationEntity
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.WechatMessageEntity
import com.infoagent.data.util.DedupKeys

/** 采集层通知解析后的标准化入站消息 */
data class IncomingMessage(
    val appSource: String, // wechat/qq/...
    val peerId: String,
    val peerName: String,
    val sender: String,
    val direction: String, // IN/OUT/OTHER
    val msgType: String,
    val content: String,
    val ts: Long,
    val notificationKey: String = "", // tag:category，空按 none
    val isGroup: Boolean = false,
)

sealed interface IngestResult {
    data class Created(val id: Long) : IngestResult
    data class Duplicate(val id: Long) : IngestResult
}

/**
 * 消息写入链路（§7 D06 / §8.8 / P0-1）：
 * - 去重：dedup_key 命中 → 只刷新 last_ts 与会话时间，不新增；
 * - 新建：主表 + FTS（同连接事务内） + 会话 upsert，三者原子；
 * - 删除：主表 + FTS 同步删。
 */
class MessageRepository(private val db: InfoAgentDatabase) {

    private val dao = db.messageDao()

    suspend fun ingest(msg: IncomingMessage): IngestResult = db.withTransaction {
        val dedupKey = DedupKeys.messageDedupKey(
            appSource = msg.appSource,
            peerId = msg.peerId,
            sender = msg.sender,
            content = msg.content,
            notificationKey = msg.notificationKey,
            tsMillis = msg.ts,
        )
        val existing = dao.findByDedupKey(dedupKey)
        if (existing != null) {
            // D06：重复通知只刷新 last_ts，不新增
            dao.touchLastTs(dedupKey, msg.ts)
            dao.touchConversation(msg.peerId, msg.ts)
            return@withTransaction IngestResult.Duplicate(existing.id)
        }
        val id = dao.insert(
            WechatMessageEntity(
                peerId = msg.peerId,
                peerName = msg.peerName,
                sender = msg.sender,
                direction = msg.direction,
                msgType = msg.msgType,
                content = msg.content,
                ts = msg.ts,
                firstTs = msg.ts,
                lastTs = msg.ts,
                source = "notification",
                appSource = msg.appSource,
                dedupKey = dedupKey,
            ),
        )
        // 同事务同连接写 FTS（P0-1 应用层写入点）
        db.ftsStore.writeRow(db.openHelper.writableDatabase, id, msg.content, msg.sender, msg.peerName)
        // B5：不整行 REPLACE（否则每条消息都会重置 priority/muted）
        val alreadyGroup = dao.conversation(msg.peerId)?.isGroup == 1L
        val groupFlag = if (msg.isGroup || alreadyGroup) 1L else 0L
        dao.insertConversationIgnore(
            ConversationEntity(
                peerId = msg.peerId,
                name = msg.peerName,
                isGroup = groupFlag,
                lastTs = msg.ts,
            ),
        )
        dao.updateConversationFromMessage(msg.peerId, msg.peerName, groupFlag, msg.ts)
        IngestResult.Created(id)
    }

    /** 删除消息：先入回收站（B1 接线），再物理删除主表与 FTS */
    suspend fun deleteMessage(message: WechatMessageEntity) = db.withTransaction {
        runCatching {
            com.infoagent.data.export.RecycleController(db).softDelete(
                "wechat_message",
                org.json.JSONObject()
                    .put("peer_id", message.peerId)
                    .put("peer_name", message.peerName)
                    .put("sender", message.sender)
                    .put("direction", message.direction)
                    .put("msg_type", message.msgType)
                    .put("content", message.content)
                    .put("ts", message.ts)
                    .put("first_ts", message.firstTs)
                    .put("last_ts", message.lastTs)
                    .put("source", message.source)
                    .put("app_source", message.appSource)
                    .put("dedup_key", message.dedupKey)
                    .put("recalled", message.recalled)
                    .put("archived", message.archived)
                    .put("flagged", message.flagged),
            )
        }
        dao.deleteById(message.id)
        db.ftsStore.deleteRow(db.openHelper.writableDatabase, message.id)
    }

    /** 兼容旧调用（按 id 查不到就不写回收站） */
    suspend fun deleteMessage(id: Long) {
        val message = dao.findById(id) ?: return
        deleteMessage(message)
    }

    suspend fun markRecalled(id: Long, recalled: Long = 1) = dao.updateRecalled(id, recalled)

    suspend fun search(query: String, limit: Int = 100): List<WechatMessageEntity> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // C10：FTS（bigram）与 LIKE 合并，单字查询/跨词查询也能命中
        val ids = runCatching { db.ftsStore.search(q, limit) }.getOrDefault(emptyList())
        val ftsRows = if (ids.isEmpty()) emptyList() else dao.byIds(ids)
        if (ftsRows.size >= limit) return ftsRows
        val likeRows = dao.searchLike(q, limit).filterNot { row -> ftsRows.any { it.id == row.id } }
        return (ftsRows + likeRows).take(limit)
    }
}