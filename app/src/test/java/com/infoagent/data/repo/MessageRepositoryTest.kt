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
}