package com.infoagent.data.export

import androidx.room.withTransaction
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.WechatMessageEntity
import org.json.JSONObject

/**
 * 导入恢复（§7 A04）：校验头 schema_version → 事务内按依赖序写入 → FTS 全量重建。
 * 冲突策略：消息按 dedup_key SKIP（重复即跳过）；其余表按主键 REPLACE。
 */
class ImportService(private val db: InfoAgentDatabase) {

    class UnsupportedSchema(version: Int) :
        Exception("导入包 schema_version=$version 不受支持（当前 ${ExportService.CURRENT_SCHEMA}）")

    data class Report(
        val messagesImported: Int = 0,
        val messagesSkipped: Int = 0,
        val memosImported: Int = 0,
        val tablesImported: Int = 0,
    )

    suspend fun restore(jsonText: String): Report = db.withTransaction {
        val root = JSONObject(jsonText)
        val version = root.optInt("schema_version", -1)
        if (version > ExportService.CURRENT_SCHEMA || version < 1) throw UnsupportedSchema(version)
        val tables = root.optJSONObject("tables") ?: JSONObject()
        val sqldb = db.openHelper.writableDatabase
        val report = Report()

        fun rows(table: String): List<JSONObject> {
            val arr = tables.optJSONArray(table) ?: return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
        }

        // 1) 设置与规则（无外键约束）
        rows("provider_config").forEach { r ->
            if (!r.has("id")) return@forEach
            sqldb.execSQL(
                "INSERT OR REPLACE INTO provider_config(id,name,base_url,endpoint,model,tier,capabilities,retry_after_until,is_active) VALUES(?,?,?,?,?,?,?,0,0)",
                arrayOf(r.optLong("id"), r.optString("name"), r.optString("base_url"), r.optString("endpoint"), r.optString("model"), r.optLong("tier"), r.optString("capabilities")),
            )
        }
        rows("model_route").forEach { r ->
            sqldb.execSQL(
                "INSERT OR REPLACE INTO model_route(use_case,provider_id,model,tier,priority) VALUES(?,?,?,?,?)",
                arrayOf(r.optString("use_case"), r.optLong("provider_id"), r.optString("model"), r.optLong("tier"), r.optLong("priority")),
            )
        }
        rows("watch_rule").forEach { r ->
            sqldb.execSQL(
                "INSERT OR REPLACE INTO watch_rule(id,rule_type,value,action,created_at) VALUES(?,?,?,?,?)",
                arrayOf(r.optLong("id"), r.optString("rule_type"), r.optString("value"), r.optString("action"), r.optLong("created_at")),
            )
        }
        rows("conversation").forEach { r ->
            sqldb.execSQL(
                "INSERT OR REPLACE INTO conversation(peer_id,name,is_group,last_ts,priority,muted) VALUES(?,?,?,?,?,?)",
                arrayOf(r.optString("peer_id"), r.optString("name"), r.optLong("is_group"), r.optLong("last_ts"), r.optLong("priority"), r.optLong("muted")),
            )
        }
        // v3：时间轴片段（按 id 覆盖）
        rows("brief_slice").forEach { r ->
            sqldb.execSQL(
                "INSERT OR REPLACE INTO brief_slice(id,biz_date,seq,\"trigger\",from_ts,to_ts,payload_json,new_count,tokens_in,tokens_out,cost_est,state,error,created_at,read_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(
                    r.optLong("id"),
                    r.optString("biz_date"),
                    r.optLong("seq"),
                    r.optString("trigger"),
                    r.optLong("from_ts"),
                    r.optLong("to_ts"),
                    if (r.has("payload_json") && !r.isNull("payload_json")) r.getString("payload_json") else null,
                    r.optLong("new_count"),
                    r.optLong("tokens_in"),
                    r.optLong("tokens_out"),
                    r.optDouble("cost_est"),
                    r.optString("state"),
                    if (r.has("error") && !r.isNull("error")) r.getString("error") else null,
                    r.optLong("created_at"),
                    if (r.has("read_at") && !r.isNull("read_at")) r.getLong("read_at") else null,
                ),
            )
        }

        // 2) 消息（dedup_key 冲突跳过；记录 旧id→新id 映射，供子表回填，A4）
        var imported = 0
        var skipped = 0
        val messageIdMap = mutableMapOf<Long, Long>()
        rows("wechat_message").forEach { r ->
            val dedup = r.optString("dedup_key")
            if (dedup.isEmpty() || db.messageDao().existsByDedupKey(dedup)) {
                skipped++
            } else {
                val newId = db.messageDao().insert(
                    WechatMessageEntity(
                        id = 0, // 让库自增，避免 id 冲突
                        peerId = r.optString("peer_id"),
                        peerName = r.optString("peer_name"),
                        sender = r.optString("sender"),
                        direction = r.optString("direction"),
                        msgType = r.optString("msg_type"),
                        content = r.optString("content"),
                        ts = r.optLong("ts"),
                        firstTs = r.optLong("first_ts"),
                        lastTs = r.optLong("last_ts"),
                        source = r.optString("source"),
                        appSource = r.optString("app_source"),
                        dedupKey = dedup,
                        recalled = r.optLong("recalled"),
                        archived = r.optLong("archived"),
                        flagged = r.optLong("flagged"),
                    ),
                )
                messageIdMap[r.optLong("id")] = newId
                imported++
            }
        }
        rows("attach_item").forEach { r ->
            val oldMsgId = r.optLong("msg_id")
            val newMsgId = messageIdMap[oldMsgId]
            if (newMsgId != null) {
                sqldb.execSQL(
                    "INSERT OR REPLACE INTO attach_item(msg_id,type,content,ts) VALUES(?,?,?,?)",
                    arrayOf(newMsgId, r.optString("type"), r.optString("content"), r.optLong("ts")),
                )
            }
        }

        // 3) 备忘族
        var memoCount = 0
        rows("repeat_rule").forEach { r ->
            sqldb.execSQL(
                "INSERT OR REPLACE INTO repeat_rule(id,memo_text,freq,weekdays,active,created_at) VALUES(?,?,?,?,?,?)",
                arrayOf(r.optLong("id"), r.optString("memo_text"), r.optString("freq"), r.optString("weekdays"), r.optLong("active"), r.optLong("created_at")),
            )
        }
        rows("memo_item").forEach { r ->
            sqldb.execSQL(
                "INSERT OR REPLACE INTO memo_item(id,kind,text,sort_order,done,cycle_date,assigned_time,origin,source_app,source_conv,source_msg_id,repeat_rule_id,calendar_event_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(
                    r.optLong("id"), r.optString("kind"), r.optString("text"), r.optLong("sort_order"),
                    r.optLong("done"), r.optString("cycle_date"), r.optLongOrNull("assigned_time"),
                    r.optString("origin"), r.optStringOrNull("source_app"), r.optStringOrNull("source_conv"),
                    r.optLongOrNull("source_msg_id")?.let { messageIdMap[it] ?: it }, r.optLongOrNull("repeat_rule_id"),
                    r.optLongOrNull("calendar_event_id"), r.optLong("created_at"), r.optLong("updated_at"),
                ),
            )
            memoCount++
        }
        rows("memo_source").forEach { r ->
            val oldMsg = r.optLong("msg_id")
            val newMsg = messageIdMap[oldMsg]
            if (newMsg != null) {
                sqldb.execSQL("INSERT OR REPLACE INTO memo_source(memo_id,msg_id) VALUES(?,?)", arrayOf(r.optLong("memo_id"), newMsg))
            }
        }
        rows("memo_archive").forEach { r ->
            sqldb.execSQL(
                "INSERT OR REPLACE INTO memo_archive(id,memo_id,archived_date,text,done,origin,source_conv) VALUES(?,?,?,?,?,?,?)",
                arrayOf(r.optLong("id"), r.optLongOrNull("memo_id"), r.optString("archived_date"), r.optString("text"), r.optLong("done"), r.optStringOrNull("origin"), r.optStringOrNull("source_conv")),
            )
        }

        // 4) 记忆/提醒/统计/归档
        rows("memory_fact").forEach { r ->
            sqldb.execSQL(
                "INSERT OR REPLACE INTO memory_fact(id,category,subject,fact_text,confidence,evidence_count,feedback_sum,last_seen,source_refs,period_hint,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf(
                    r.optLong("id"), r.optString("category"), r.optStringOrNull("subject"), r.optStringOrNull("fact_text"),
                    r.optLong("confidence"), r.optLong("evidence_count"), r.optLong("feedback_sum", 0L),
                    r.optLongOrNull("last_seen"),
                    r.optStringOrNull("source_refs"), r.optStringOrNull("period_hint"), r.optString("status"),
                    r.optLong("created_at"), r.optLong("updated_at"),
                ),
            )
        }
        rows("reminder").forEach { r ->
            sqldb.execSQL(
                "INSERT OR REPLACE INTO reminder(id,kind,memo_id,run_id,title,fire_at,fired,fired_at) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf(
                    r.optLong("id"), r.optString("kind"), r.optLongOrNull("memo_id"), r.optLongOrNull("run_id"),
                    r.optStringOrNull("title"), r.optLong("fire_at"), r.optLong("fired"), r.optLongOrNull("fired_at"),
                ),
            )
        }
        rows("streak").forEach { r ->
            sqldb.execSQL(
                "INSERT OR REPLACE INTO streak(date,plan_total,done_total,temp_total,done_rate,updated_at) VALUES(?,?,?,?,?,?)",
                arrayOf(r.optString("date"), r.optLong("plan_total"), r.optLong("done_total"), r.optLong("temp_total"), r.optDouble("done_rate"), r.optLong("updated_at")),
            )
        }
        rows("message_archive").forEach { r ->
            sqldb.execSQL(
                "INSERT OR REPLACE INTO message_archive(day,conv,data_blob,sha,count) VALUES(?,?,?,?,?)",
                arrayOf(
                    r.optString("day"), r.optString("conv"),
                    r.optStringOrNull("data_blob")?.let { b ->
                        try { android.util.Base64.decode(b, android.util.Base64.NO_WRAP) } catch (e: Exception) { null }
                    },
                    r.optStringOrNull("sha"), r.optLong("count"),
                ),
            )
        }

        // 5) FTS 全量重建（P0-1 迁移/导入后）：同事务同连接内清空重写
        sqldb.execSQL("DELETE FROM wechat_message_fts")
        db.messageDao().messagesSince(0, Int.MAX_VALUE).forEach { m ->
            db.ftsStore.writeRow(sqldb, m.id, m.content, m.sender, m.peerName)
        }

        Report(
            messagesImported = imported,
            messagesSkipped = skipped,
            memosImported = memoCount,
            tablesImported = ExportService.TABLES.size,
        )
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (isNull(key)) null else optLong(key)
    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
}