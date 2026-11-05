package com.infoagent.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * D01 验收（运行时版）：在真实 SQLite（Robolectric NATIVE）上核对 §7 DDL 的
 * 表集合与关键表列名，防 Room 生成偏离文档。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SchemaContractTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()

    private val expectedTables = setOf(
        "wechat_message", "conversation", "watch_rule", "attach_item",
        "memo_item", "memo_source", "memo_archive", "repeat_rule",
        "auto_memo_job", "agent_run", "followup_chat", "agent_trace", "brief_feedback",
        "budget_counter", "provider_config", "model_route", "scheduled_run", "reminder",
        "memory_fact", "streak", "recycle", "message_archive", "agent_lock", "notification_id_map",
    )

    private lateinit var db: InfoAgentDatabase

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    private fun tableNames(): Set<String> {
        val names = mutableSetOf<String>()
        db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type IN ('table','view')").use {
            while (it.moveToNext()) names.add(it.getString(0))
        }
        return names
    }

    private fun columnNames(table: String): Set<String> {
        val cols = mutableSetOf<String>()
        db.openHelper.readableDatabase.query("PRAGMA table_info($table)").use {
            while (it.moveToNext()) cols.add(it.getString(1))
        }
        return cols
    }

    @Test
    fun `全部 24 张业务表与 FTS 虚表就位`() {
        db = InfoAgentDatabase.build(context, "schema-contract.db")
        val names = tableNames()
        for (t in expectedTables) assertTrue("缺少表 $t", t in names)
        assertTrue("缺少 FTS 虚表", "wechat_message_fts" in names)
    }

    @Test
    fun `wechat_message 列名与 DDL 一致（蛇形）`() {
        db = InfoAgentDatabase.build(context, "schema-msg.db")
        assertEquals(
            setOf(
                "id", "peer_id", "peer_name", "sender", "direction", "msg_type", "content",
                "ts", "first_ts", "last_ts", "source", "app_source", "dedup_key", "recalled",
                "archived", "flagged",
            ),
            columnNames("wechat_message"),
        )
    }

    @Test
    fun `memo_item 列名与 DDL 一致`() {
        db = InfoAgentDatabase.build(context, "schema-memo.db")
        assertEquals(
            setOf(
                "id", "kind", "text", "sort_order", "done", "cycle_date", "assigned_time",
                "origin", "source_app", "source_conv", "source_msg_id", "repeat_rule_id",
                "calendar_event_id", "created_at", "updated_at",
            ),
            columnNames("memo_item"),
        )
    }

    @Test
    fun `关键索引存在`() {
        db = InfoAgentDatabase.build(context, "schema-idx.db")
        val sql = mutableListOf<String>()
        db.openHelper.readableDatabase.query(
            "SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name IN ('wechat_message','memo_item','scheduled_run','attach_item')",
        ).use {
            while (it.moveToNext()) sql.add(it.getString(0) ?: "")
        }
        val joined = sql.joinToString("\n")
        assertTrue("缺 idx peer_id,ts", "peer_id" in joined && "ts" in joined)
        assertTrue("缺 attach msg_id 索引", Regex("attach_item.*msg_id", RegexOption.DOT_MATCHES_ALL).containsMatchIn(joined))
        assertTrue("缺 scheduled_run expected_at 索引", Regex("scheduled_run.*expected_at", RegexOption.DOT_MATCHES_ALL).containsMatchIn(joined))
        assertTrue("缺 memo_item cycle_date,kind 索引", Regex("memo_item.*cycle_date.*kind", RegexOption.DOT_MATCHES_ALL).containsMatchIn(joined))
    }

    @Test
    fun `唯一约束与 DDL 一致（dedup_key、batch_hash）`() {
        db = InfoAgentDatabase.build(context, "schema-unique.db")
        fun indexColumns(table: String, index: String): Set<String> {
            val cols = mutableSetOf<String>()
            db.openHelper.readableDatabase.query("PRAGMA index_info($index)").use {
                while (it.moveToNext()) cols.add(it.getString(2))
            }
            val info = mutableListOf<String>()
            db.openHelper.readableDatabase.query("PRAGMA index_list($table)").use {
                while (it.moveToNext()) {
                    val name = it.getString(1)
                    if (name == index) info.add(it.getString(2)) // unique 标志
                }
            }
            assertTrue("表 $table 缺索引 $index ($cols)", info.isNotEmpty())
            assertTrue("索引 $index 应为 UNIQUE", info.first() == "1")
            return cols
        }
        assertEquals(setOf("dedup_key"), indexColumns("wechat_message", "index_wechat_message_dedup_key"))
        assertEquals(setOf("batch_hash"), indexColumns("auto_memo_job", "index_auto_memo_job_batch_hash"))
    }
}