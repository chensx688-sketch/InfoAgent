package com.infoagent.data.export

import androidx.room.withTransaction
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.RecycleEntity
import com.infoagent.data.db.WechatMessageEntity
import com.infoagent.data.util.DedupKeys
import org.json.JSONObject

/**
 * 回收站（D40）：payload={"table","row_json","schema_version"}；默认 30 天自动清（§12）；
 * 恢复按主键 upsert；消息恢复同步补 FTS。
 */
class RecycleController(private val db: InfoAgentDatabase) {

    companion object {
        const val RETENTION_DAYS = 30L
    }

    suspend fun softDelete(table: String, row: JSONObject) {
        val payload = JSONObject()
            .put("table", table)
            .put("row_json", row)
            .put("schema_version", ExportService.CURRENT_SCHEMA)
            .toString()
        val now = System.currentTimeMillis()
        db.memoryDao().insertRecycle(
            RecycleEntity(kind = table, payload = payload, deletedAt = now, expireAt = now + RETENTION_DAYS * 86_400_000L),
        )
    }

    suspend fun restore(recycleId: Long): Boolean = db.withTransaction {
        val rec = db.memoryDao().recycle(recycleId) ?: return@withTransaction false
        val root = JSONObject(rec.payload)
        val table = root.optString("table")
        val row = root.optJSONObject("row_json") ?: return@withTransaction false
        val sqldb = db.openHelper.writableDatabase

        suspend fun restoreMessage(): Boolean {
            val dedup = row.optString("dedup_key")
            // 已存在同键消息 → 视为已恢复，直接回收此条目
            if (dedup.isNotEmpty() && db.messageDao().existsByDedupKey(dedup)) return true
            val entity = WechatMessageEntity(
                peerId = row.optString("peer_id"),
                peerName = row.optString("peer_name"),
                sender = row.optString("sender"),
                direction = row.optString("direction").ifEmpty { "IN" },
                msgType = row.optString("msg_type").ifEmpty { "text" },
                content = row.optString("content"),
                ts = row.optLong("ts"),
                firstTs = row.optLong("first_ts").takeIf { it > 0 } ?: row.optLong("ts"),
                lastTs = row.optLong("last_ts").takeIf { it > 0 } ?: row.optLong("ts"),
                source = row.optString("source").ifEmpty { "notification" },
                appSource = row.optString("app_source").ifEmpty { "wechat" },
                dedupKey = dedup.ifEmpty {
                    DedupKeys.messageDedupKey(
                        row.optString("app_source").ifEmpty { "wechat" },
                        row.optString("peer_id"),
                        row.optString("sender"),
                        row.optString("content"),
                        "",
                        row.optLong("ts"),
                    )
                },
                recalled = row.optLong("recalled"),
                archived = row.optLong("archived"),
                flagged = row.optLong("flagged"),
            )
            val id = db.messageDao().insert(entity)
            db.ftsStore.writeRow(sqldb, id, entity.content, entity.sender, entity.peerName)
            return true
        }

        val ok = when (table) {
            "memo_item" -> {
                sqldb.execSQL(
                    "INSERT OR REPLACE INTO memo_item(id,kind,text,sort_order,done,cycle_date,assigned_time,origin,source_app,source_conv,source_msg_id,repeat_rule_id,calendar_event_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(
                        row.optLong("id"), row.optString("kind").ifEmpty { "TEMP" }, row.optString("text"), row.optLong("sort_order"),
                        row.optLong("done"), row.optString("cycle_date"),
                        if (row.isNull("assigned_time")) null else row.optLong("assigned_time"),
                        row.optString("origin").ifEmpty { "MANUAL" },
                        if (row.isNull("source_app")) null else row.optString("source_app"),
                        if (row.isNull("source_conv")) null else row.optString("source_conv"),
                        if (row.isNull("source_msg_id")) null else row.optLong("source_msg_id"),
                        if (row.isNull("repeat_rule_id")) null else row.optLong("repeat_rule_id"),
                        if (row.isNull("calendar_event_id")) null else row.optLong("calendar_event_id"),
                        row.optLong("created_at"), row.optLong("updated_at"),
                    ),
                )
                true
            }
            "wechat_message" -> restoreMessage()
            "conversation" -> {
                sqldb.execSQL(
                    "INSERT OR REPLACE INTO conversation(peer_id,name,is_group,last_ts,priority,muted) VALUES(?,?,?,?,?,?)",
                    arrayOf(row.optString("peer_id"), row.optString("name"), row.optLong("is_group"), row.optLong("last_ts"), row.optLong("priority"), row.optLong("muted")),
                )
                true
            }
            "watch_rule" -> {
                sqldb.execSQL(
                    "INSERT OR REPLACE INTO watch_rule(id,rule_type,value,action,created_at) VALUES(?,?,?,?,?)",
                    arrayOf(row.optLong("id"), row.optString("rule_type"), row.optString("value"), row.optString("action"), row.optLong("created_at")),
                )
                true
            }
            else -> false
        }
        if (ok) db.memoryDao().deleteRecycle(recycleId)
        ok
    }

    suspend fun purgeExpired(now: Long = System.currentTimeMillis()) = db.memoryDao().purgeExpiredRecycle(now)
}