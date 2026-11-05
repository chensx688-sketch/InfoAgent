package com.infoagent.data.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.MemoItemEntity
import com.infoagent.data.repo.IncomingMessage
import com.infoagent.data.repo.IngestResult
import com.infoagent.data.repo.MessageRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** A04 验收：导出 → 新库导入 → 数据与检索恢复一致；schema 版本守卫；回收站恢复（D40） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ExportImportTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private val dbs = mutableListOf<InfoAgentDatabase>()

    @After
    fun closeAll() {
        dbs.forEach { it.close() }
        dbs.clear()
    }

    private fun db(name: String): InfoAgentDatabase = InfoAgentDatabase.build(context, name).also { dbs.add(it) }

    @Test
    fun `导出后导入新库 数据与中文检索一致（A04）`() = runBlocking {
        val src = db("exp-src.db")
        val repo = MessageRepository(src)
        repo.ingest(
            IncomingMessage("wechat", "p1", "王总", "王总", "IN", "text", "明天下午三点开会", 100L),
        )
        repo.ingest(
            IncomingMessage("wechat", "p2", "家人群", "妈妈", "IN", "text", "周末回家吃饭", 200L, isGroup = true),
        )
        src.memoDao().insert(
            MemoItemEntity(
                kind = "TEMP", text = "买猫粮", sortOrder = 1, cycleDate = "2026-11-04",
                createdAt = 1L, updatedAt = 1L,
            ),
        )

        val backup = ExportService(src).export()
        assertTrue(backup.contains("\"wechat_message\""))
        assertTrue("导出不得含 api_key", !backup.contains("api_key"))

        val dst = db("exp-dst.db")
        val report = ImportService(dst).restore(backup)
        assertEquals(2, report.messagesImported)
        assertEquals(0, report.messagesSkipped)
        assertEquals(1, report.memosImported)

        assertEquals(2L, dst.messageDao().countAll())
        assertTrue("导入后中文检索可用（FTS 重建）", dst.ftsStore.search("开会").isNotEmpty())
        assertTrue(dst.ftsStore.search("回家").isNotEmpty())
        assertEquals(1, dst.memoDao().list("2026-11-04", "TEMP").size)
    }

    @Test
    fun `重复导入幂等（dedup_key SKIP）`() = runBlocking {
        val src = db("exp-dup-src.db")
        MessageRepository(src).ingest(
            IncomingMessage("wechat", "p1", "王总", "王总", "IN", "text", "明天下午三点开会", 100L),
        )
        val backup = ExportService(src).export()

        val dst = db("exp-dup-dst.db")
        val first = ImportService(dst).restore(backup)
        val second = ImportService(dst).restore(backup)
        assertEquals(1, first.messagesImported)
        assertEquals(1, second.messagesSkipped)
        assertEquals(1L, dst.messageDao().countAll())
    }

    @Test
    fun `高于当前版本的导入包被拒绝`() = runBlocking {
        val dst = db("exp-ver.db")
        val bad = JSONObject().put("schema_version", 99).put("tables", JSONObject()).toString()
        var failed = false
        try {
            ImportService(dst).restore(bad)
        } catch (e: ImportService.UnsupportedSchema) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `回收站恢复备忘与消息（D40）`() = runBlocking {
        val d = db("recycle.db")
        val recycle = RecycleController(d)

        val memoId = d.memoDao().insert(
            MemoItemEntity(kind = "TEMP", text = "临时备忘", sortOrder = 1, cycleDate = "2026-11-04", createdAt = 1L, updatedAt = 1L),
        )
        recycle.softDelete(
            "memo_item",
            JSONObject()
                .put("id", memoId).put("kind", "TEMP").put("text", "临时备忘")
                .put("sort_order", 1).put("done", 0).put("cycle_date", "2026-11-04")
                .put("created_at", 1L).put("updated_at", 1L),
        )
        d.memoDao().delete(memoId)

        val rec = d.memoryDao().recycleList(System.currentTimeMillis(), 10).first()
        assertTrue(recycle.restore(rec.id))
        assertEquals("临时备忘", d.memoDao().list("2026-11-04", "TEMP").first().text)

        // 消息恢复（含 FTS 补写）
        val repo = MessageRepository(d)
        val mid = (repo.ingest(IncomingMessage("wechat", "p1", "王总", "王总", "IN", "text", "被删的消息", 100L)) as IngestResult.Created).id
        val rowJson = JSONObject()
            .put("peer_id", "p1").put("peer_name", "王总").put("sender", "王总")
            .put("direction", "IN").put("msg_type", "text").put("content", "被删的消息")
            .put("ts", 100L).put("first_ts", 100L).put("last_ts", 100L)
            .put("source", "notification").put("app_source", "wechat")
            .put("dedup_key", "recycle-dedup-1").put("recalled", 0).put("archived", 0).put("flagged", 0)
        recycle.softDelete("wechat_message", rowJson)
        repo.deleteMessage(mid)
        val rec2 = d.memoryDao().recycleList(System.currentTimeMillis(), 10).first()
        assertTrue(recycle.restore(rec2.id))
        assertEquals(1L, d.messageDao().countAll())
        assertTrue(d.ftsStore.search("被删").isNotEmpty())
    }

    @Test
    fun `导入后备忘与消息来源指向新 id（A4）`() = runBlocking {
        val src = db("remap-src.db")
        val repo = MessageRepository(src)
        val oldMsgId = (repo.ingest(
            IncomingMessage("wechat", "p1", "王总", "王总", "IN", "text", "明天带合同", 100L),
        ) as IngestResult.Created).id
        val memoId = src.memoDao().insert(
            MemoItemEntity(
                kind = "TEMP", text = "带合同", sortOrder = 1, cycleDate = "2026-11-04",
                origin = "AI", sourceApp = "wechat", sourceConv = "王总", sourceMsgId = oldMsgId,
                createdAt = 1L, updatedAt = 1L,
            ),
        )
        src.memoDao().insertSource(
            com.infoagent.data.db.MemoSourceEntity(memoId = memoId, msgId = oldMsgId),
        )

        val backup = ExportService(src).export()
        val dst = db("remap-dst.db")
        // 先占一个 id，确保导入后的消息 id 与导出时不同，才能真正验证映射
        MessageRepository(dst).ingest(
            IncomingMessage("wechat", "p0", "占位", "占位", "IN", "text", "本地已有消息", 50L),
        )
        ImportService(dst).restore(backup)

        val newMsg = dst.messageDao().messagesSince(0L, 10).first { it.content == "明天带合同" }
        val newMemo = dst.memoDao().list("2026-11-04", "TEMP").first()
        assertTrue("导入后消息 id 应重新分配", newMsg.id != oldMsgId)
        assertEquals("备忘的来源消息必须指向新 id", newMsg.id, newMemo.sourceMsgId)
        assertEquals(
            "memo_source 也必须指向新 id",
            newMsg.id,
            dst.memoDao().sourcesOfMemo(newMemo.id).first().msgId,
        )
    }
}