package com.infoagent.data.export

import android.database.Cursor
import android.util.Base64
import androidx.room.withTransaction
import com.infoagent.data.db.InfoAgentDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地备份导出（§7/§12）：纯本地 JSON，头含 schema_version/app_version/exported_at。
 * 导出数据表 + 非敏感设置表；不含 api_key（§6 D42）、不含审计/运行时状态表。
 */
class ExportService(private val db: InfoAgentDatabase) {

    companion object {
        const val CURRENT_SCHEMA = 2

        /** A10：版本号取构建产物，避免导出文件头永远是 0.1.0 */
        val APP_VERSION: String = com.infoagent.BuildConfig.VERSION_NAME

        /** 导出表 → 列清单（白名单，防新列遗漏） */
        val TABLES: Map<String, List<String>> = mapOf(
            "wechat_message" to listOf("id", "peer_id", "peer_name", "sender", "direction", "msg_type", "content", "ts", "first_ts", "last_ts", "source", "app_source", "dedup_key", "recalled", "archived", "flagged"),
            "conversation" to listOf("peer_id", "name", "is_group", "last_ts", "priority", "muted"),
            "watch_rule" to listOf("id", "rule_type", "value", "action", "created_at"),
            "attach_item" to listOf("id", "msg_id", "type", "content", "ts"),
            "memo_item" to listOf("id", "kind", "text", "sort_order", "done", "cycle_date", "assigned_time", "origin", "source_app", "source_conv", "source_msg_id", "repeat_rule_id", "calendar_event_id", "created_at", "updated_at"),
            "memo_source" to listOf("memo_id", "msg_id"),
            "memo_archive" to listOf("id", "memo_id", "archived_date", "text", "done", "origin", "source_conv"),
            "repeat_rule" to listOf("id", "memo_text", "freq", "weekdays", "active", "created_at"),
            "memory_fact" to listOf("id", "category", "subject", "fact_text", "confidence", "evidence_count", "feedback_sum", "last_seen", "source_refs", "period_hint", "status", "created_at", "updated_at"),
            "reminder" to listOf("id", "kind", "memo_id", "run_id", "title", "fire_at", "fired", "fired_at"),
            "streak" to listOf("date", "plan_total", "done_total", "temp_total", "done_rate", "updated_at"),
            "message_archive" to listOf("day", "conv", "data_blob", "sha", "count"),
            "provider_config" to listOf("id", "name", "base_url", "endpoint", "model", "tier", "capabilities", "is_active"),
            "model_route" to listOf("use_case", "provider_id", "model", "tier", "priority"),
            "brief_slice" to listOf("id", "biz_date", "seq", "trigger", "from_ts", "to_ts", "payload_json", "new_count", "tokens_in", "tokens_out", "cost_est", "state", "error", "created_at", "read_at"),
        )
    }

    @Suppress("DEPRECATION")
    private fun cursorValue(c: Cursor, i: Int): Any {
        return when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
            Cursor.FIELD_TYPE_STRING -> c.getString(i)
            Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP)
            else -> c.getString(i)
        }
    }

    /** A10：流式导出——逐表写文件，避免几万条消息先拼成一个几十 MB 的字符串。
     *  R3：不再包在写事务里（大库导出会长时间占写锁，卡住采集），改为分表读、事务外写。 */
    suspend fun exportTo(out: java.io.OutputStream, exportedAt: Long = System.currentTimeMillis()) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write("{\n  \"schema_version\": $CURRENT_SCHEMA,\n  \"app_version\": \"$APP_VERSION\",\n  \"exported_at\": $exportedAt,\n  \"tables\": {")
        TABLES.entries.forEachIndexed { tableIndex, (table, columns) ->
            if (tableIndex > 0) writer.write(",")
            writer.write("\n    \"$table\": [")
            var first = true
            val sql = "SELECT ${columns.joinToString(",")} FROM $table"
            db.openHelper.readableDatabase.query(sql).use { cursor ->
                while (cursor.moveToNext()) {
                    if (!first) writer.write(",")
                    first = false
                    val row = JSONObject()
                    columns.forEachIndexed { i, col -> row.put(col, cursorValue(cursor, i)) }
                    writer.write("\n      " + row.toString())
                }
            }
            writer.write("\n    ]")
        }
        writer.write("\n  }\n}\n")
        writer.flush()
    }

    suspend fun export(exportedAt: Long = System.currentTimeMillis()): String {
        val buffer = java.io.ByteArrayOutputStream()
        exportTo(buffer, exportedAt)
        return buffer.toString("UTF-8")
    }
}