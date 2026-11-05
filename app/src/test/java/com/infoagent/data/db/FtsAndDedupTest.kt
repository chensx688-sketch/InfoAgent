package com.infoagent.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infoagent.data.util.DedupKeys
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** P0-1 / D06 验收：FTS 中文检索、重启不归零、去重键更新 last_ts（JVM 级，Robolectric） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class FtsAndDedupTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()

    // 走生产统一工厂（挂 FTS 建表 Callback，P0-1）
    private fun build(name: String = "test-fts.db"): InfoAgentDatabase =
        InfoAgentDatabase.build(context, name)

    @After
    fun cleanup() {
        // 每个测试用独立文件名，避免相互影响
    }

    @Test
    fun `中文 bigram 检索命中与重建`() = runBlocking {
        val db = build("fts-hit.db")
        val id = db.messageDao().insert(
            WechatMessageEntity(
                peerId = "p1", peerName = "产品群", sender = "王总", direction = "IN",
                msgType = "text", content = "明天带合同来公司", ts = 1L, firstTs = 1L, lastTs = 1L,
                dedupKey = DedupKeys.messageDedupKey("wechat", "p1", "王总", "明天带合同来公司", "none", 1L),
            ),
        )
        db.ftsStore.insert(id, "明天带合同来公司", "王总", "产品群")

        val hits = db.ftsStore.search("合同")
        assertTrue("搜索「合同」应命中", hits.contains(id))

        // 挂 where（Room 缓存内对象）；as long 查询重算
        val hits2 = db.ftsStore.search("公司")
        assertTrue(hits2.contains(id))
        db.close()
    }

    @Test
    fun `重启后 FTS 行数不归零（onOpen 只建表不 DROP）`() = runBlocking {
        val db1 = build("fts-reopen.db")
        val id = db1.messageDao().insert(
            WechatMessageEntity(
                peerId = "p1", peerName = "产品群", sender = "王总", direction = "IN",
                msgType = "text", content = "明天带合同来公司", ts = 1L, firstTs = 1L, lastTs = 1L,
                dedupKey = "open-test-key",
            ),
        )
        db1.ftsStore.insert(id, "明天带合同来公司", "王总", "产品群")
        db1.close()

        val db2 = build("fts-reopen.db")
        assertEquals(1L, db2.ftsStore.count())
        assertTrue(db2.ftsStore.search("合同").contains(id))
        db2.close()
    }

    @Test
    fun `同 dedup_key 重复插入不新增且刷新 last_ts（D06）`() = runBlocking {
        val db = build("dedup.db")
        val key = "dedup-key-1"
        val msg = WechatMessageEntity(
            peerId = "p1", peerName = "张三", sender = "张三", direction = "IN",
            msgType = "text", content = "重复消息", ts = 1L, firstTs = 1L, lastTs = 1L, dedupKey = key,
        )
        val id = db.messageDao().insert(msg)
        // 重复通知：更新 last_ts，不新增
        db.messageDao().touchLastTs(key, 999L)
        val found = db.messageDao().findByDedupKey(key)
        assertEquals(id, found?.id)
        assertEquals(999L, found?.lastTs)

        // 重复插入被唯一约束拒绝：总数必须仍为 1（D06 验收，模式无关断言）
        runCatching { db.messageDao().insert(msg.copy(ts = 2L, lastTs = 2L)) }
        assertEquals(1L, db.messageDao().countAll())
        db.close()
    }
}