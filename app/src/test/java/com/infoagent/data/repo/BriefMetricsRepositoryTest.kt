package com.infoagent.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.WechatMessageEntity
import com.infoagent.data.util.DayBoundary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * 真 SQLite 上验证可视化的取数 SQL（小时分桶 / 会话 Top N / 完成率）。
 * 这段 SQL 用了 strftime(..., 'localtime')，必须用真库跑一遍才能确认时区按本机小时切。
 *
 * 兼容性：同时在 minSdk(26) / 常见版本(34) / targetSdk(35) 上各跑一遍，
 * 哪个系统版本上 strftime、java.time 或 Room 行为不同，这里会直接变红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BriefMetricsRepositoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: InfoAgentDatabase

    @After
    fun close() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun seed(peer: String, ts: Long, key: String) {
        db.messageDao().insert(
            WechatMessageEntity(
                peerId = peer,
                peerName = peer,
                sender = peer,
                direction = "IN",
                msgType = "text",
                content = "内容$key",
                ts = ts,
                firstTs = ts,
                lastTs = ts,
                dedupKey = key,
            ),
        )
    }

    @Test
    fun `按小时分桶并按会话聚合`() = runBlocking {
        db = InfoAgentDatabase.build(context, "metrics.db")
        val day = DayBoundary.today()
        val start = DayBoundary.dayRange(day)[0]
        val hour = 3_600_000L

        seed("王总", start + 9 * hour + 5 * 60_000L, "k1")
        seed("王总", start + 9 * hour + 40 * 60_000L, "k2")
        seed("小李", start + 14 * hour, "k3")

        val m = BriefMetricsRepository(db).dayMetrics(day)

        assertEquals(3, m.todayTotal)
        assertEquals(2, m.todayHours[9].count)
        assertEquals(1, m.todayHours[14].count)
        assertEquals(0, m.todayHours[2].count)

        assertEquals(2, m.topPeers.size)
        assertEquals("王总", m.topPeers[0].peer)
        assertEquals(2, m.topPeers[0].count)

        assertEquals(9, m.peak?.hour)
        assertEquals(3, m.todayHours.sumOf { it.count })
        assertEquals(24, m.todayHours.size)
    }

    @Test
    fun `必做完成率来自当天的备忘`() = runBlocking {
        db = InfoAgentDatabase.build(context, "metrics-memo.db")
        val day = DayBoundary.today()
        val now = System.currentTimeMillis()
        db.memoDao().insert(
            com.infoagent.data.db.MemoItemEntity(
                kind = "DAILY", text = "写周报", sortOrder = 0, done = 1,
                cycleDate = day, createdAt = now, updatedAt = now,
            ),
        )
        db.memoDao().insert(
            com.infoagent.data.db.MemoItemEntity(
                kind = "DAILY", text = "对报价", sortOrder = 1, done = 0,
                cycleDate = day, createdAt = now, updatedAt = now,
            ),
        )

        val m = BriefMetricsRepository(db).dayMetrics(day)
        assertEquals(1, m.dailyDone)
        assertEquals(2, m.dailyTotal)
        assertTrue(m.completion > 0.49f && m.completion < 0.51f)
    }
}