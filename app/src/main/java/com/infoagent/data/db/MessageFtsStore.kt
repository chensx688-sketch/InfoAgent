package com.infoagent.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FTS4 中文检索表存取（P0-1：应用层写入点，非 Room @Entity、非触发器）。
 * rowid 与 wechat_message.id 一一对应；查询侧 bigram 化后 MATCH。
 *
 * 事务约定：withTransaction 内必须用 [writeRow]/[deleteRow]（同连接同步写）；
 * 独立场景用 suspend [insert]/[delete]（自切 IO）。
 */
class MessageFtsStore(private val db: RoomDatabase) {

    companion object {
        const val TABLE = "wechat_message_fts"

        fun ensureTable(sqldb: SupportSQLiteDatabase) {
            // onOpen 仅「不存在则建」，每次启动禁止 DROP（P0-1）
            sqldb.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts4(" +
                    "content_tokens, sender_tokens, peer_name_tokens)"
            )
        }

        fun rowArgs(rowid: Long, content: String, sender: String, peerName: String): Array<Any?> =
            arrayOf(
                rowid,
                BigramTokenizer.tokenString(content),
                BigramTokenizer.tokenString(sender),
                BigramTokenizer.tokenString(peerName),
            )
    }

    /** 同连接事务内写入（在 withTransaction 块中调用，不切线程） */
    fun writeRow(sqldb: SupportSQLiteDatabase, rowid: Long, content: String, sender: String, peerName: String) {
        sqldb.execSQL(
            "INSERT INTO $TABLE(rowid, content_tokens, sender_tokens, peer_name_tokens) VALUES (?,?,?,?)",
            rowArgs(rowid, content, sender, peerName),
        )
    }

    /** 同连接事务内删除 */
    fun deleteRow(sqldb: SupportSQLiteDatabase, rowid: Long) {
        sqldb.execSQL("DELETE FROM $TABLE WHERE rowid = ?", arrayOf(rowid))
    }

    suspend fun insert(rowid: Long, content: String, sender: String, peerName: String) = withContext(Dispatchers.IO) {
        writeRow(db.openHelper.writableDatabase, rowid, content, sender, peerName)
    }

    suspend fun delete(rowid: Long) = withContext(Dispatchers.IO) {
        deleteRow(db.openHelper.writableDatabase, rowid)
    }

    /** 返回命中消息 id（按 rowid 排序） */
    suspend fun search(query: String, limit: Int = 100): List<Long> = withContext(Dispatchers.IO) {
        val expr = BigramTokenizer.matchExpression(query) ?: return@withContext emptyList()
        val cursor = db.openHelper.readableDatabase.query(
            "SELECT rowid FROM $TABLE WHERE $TABLE MATCH ? ORDER BY rowid DESC LIMIT $limit",
            arrayOf(expr),
        )
        cursor.use {
            val ids = mutableListOf<Long>()
            while (it.moveToNext()) ids.add(it.getLong(0))
            ids
        }
    }

    suspend fun count(): Long = withContext(Dispatchers.IO) {
        var n = 0L
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $TABLE").use {
            if (it.moveToFirst()) n = it.getLong(0)
        }
        n
    }

    /** 迁移后全量重建（P0-1：读主表 → bigram → 重写 FTS） */
    suspend fun rebuild(messages: List<WechatMessageEntity>) = withContext(Dispatchers.IO) {
        val sqldb = db.openHelper.writableDatabase
        sqldb.beginTransaction()
        try {
            sqldb.execSQL("DELETE FROM $TABLE")
            messages.forEach { m -> writeRow(sqldb, m.id, m.content, m.sender, m.peerName) }
            sqldb.setTransactionSuccessful()
        } finally {
            sqldb.endTransaction()
        }
    }
}