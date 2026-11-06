package com.infoagent.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infoagent.data.db.InfoAgentDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** §8.8 入库链路验收：事务内 主表+FTS+会话 原子写入、去重刷新、删除同步 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MessageRepositoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: InfoAgentDatabase

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    private fun msg(ts: Long = 1L, content: String = "明天下午三点开会") = IncomingMessage(
        appSource = "wechat",
        peerId = "peer-1",
        peerName = "王总",
        sender = "王总",
        direction = "IN",
        msgType = "text",
        content = content,
        ts = ts,
    )

    @Test
    fun `新建消息写入主表 FTS 与会话`() = runBlocking {
        db = InfoAgentDatabase.build(context, "repo-new.db")
        val repo = MessageRepository(db)
        val result = repo.ingest(msg())
        assertTrue(result is IngestResult.Created)
        val id = (result as IngestResult.Created).id
        assertEquals(1L, db.messageDao().countAll())
        assertTrue(db.ftsStore.search("开会").contains(id))
        assertEquals("王总", db.messageDao().conversation("peer-1")?.name)
    }

    @Test
    fun `重复通知只刷新时间不新增（D06）`() = runBlocking {
        db = InfoAgentDatabase.build(context, "repo-dup.db")
        val repo = MessageRepository(db)
        val first = repo.ingest(msg(ts = 100L)) as IngestResult.Created
        val second = repo.ingest(msg(ts = 999L)) // 同内容不同时间，同 notificationKey 空→none
        assertTrue(second is IngestResult.Duplicate)
        assertEquals(first.id, (second as IngestResult.Duplicate).id)
        assertEquals(1L, db.messageDao().countAll())
        assertEquals(999L, db.messageDao().findById(first.id)?.lastTs)
        assertEquals(1L, db.ftsStore.count())
    }

    @Test
    fun `不同 notification_key 视为不同消息`() = runBlocking {
        db = InfoAgentDatabase.build(context, "repo-key.db")
        val repo = MessageRepository(db)
        repo.ingest(msg(content = "好的"))
        repo.ingest(msg(content = "好的").copy(notificationKey = "other:tag", ts = 2L))
        assertEquals(2L, db.messageDao().countAll())
    }

    @Test
    fun `删除消息同步清 FTS`() = runBlocking {
        db = InfoAgentDatabase.build(context, "repo-del.db")
        val repo = MessageRepository(db)
        val id = (repo.ingest(msg()) as IngestResult.Created).id
        repo.deleteMessage(id)
        assertEquals(0L, db.messageDao().countAll())
        assertEquals(0L, db.ftsStore.count())
    }

    @Test
    fun `关闭采集的会话直接丢弃不入库`() = runBlocking {
        db = InfoAgentDatabase.build(context, "repo-muted.db")
        val repo = MessageRepository(db)
        repo.ingest(msg(content = "先建立会话"))
        db.messageDao().setConversationMuted(msg().peerId, 1L)
        val before = db.messageDao().countAll()

        val r = repo.ingest(msg(content = "这条不该进来").copy(ts = msg().ts + 60_000L))
        assertTrue(r is IngestResult.Muted)
        assertEquals("不采集的会话不能占库", before, db.messageDao().countAll())

        // 打开后立即恢复采集
        db.messageDao().setConversationMuted(msg().peerId, 0L)
        val again = repo.ingest(msg(content = "恢复了").copy(ts = msg().ts + 120_000L))
        assertTrue(again is IngestResult.Created)
    }

    @Test
    fun `按消息 id 取回原文并能拿到同会话上下文`() = runBlocking {
        db = InfoAgentDatabase.build(context, "ref-focus-test.db")
        val repo = MessageRepository(db)
        val t0 = 1_762_400_000_000L
        repo.ingest(msg(ts = t0, content = "预算 8 万能不能做"))
        repo.ingest(msg(ts = t0 + 60_000, content = "报价单已发你"))
        repo.ingest(msg(ts = t0 + 120_000, content = "收到，我看下"))
        repo.ingest(msg(ts = t0 + 7 * 3_600_000L, content = "明天再说")) // 窗口外

        val focused = db.messageDao().byIds(
            listOfNotNull(db.messageDao().findByDedupKey("")?.id).ifEmpty {
                // 用内容定位目标消息（去重键由 repo 生成，这里按内容查）
                db.messageDao().searchLike("报价单", 5).map { it.id }
            },
        )
        assertEquals(1, focused.size)

        val w = RefFocusBuilder.window(focused)
        val context = db.messageDao().aroundPeer("peer-1", w.first, w.last)
        val focus = RefFocusBuilder.build(focused, context)

        assertEquals(3, focus.rows.size)
        assertEquals(1, focus.focusedCount)
        assertEquals("报价单已发你", focus.rows.first { it.focused }.msg.content)
        assertTrue("窗口外的消息不该混进来", focus.rows.none { it.msg.content == "明天再说" })
    }
}