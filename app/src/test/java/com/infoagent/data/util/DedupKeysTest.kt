package com.infoagent.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupKeysTest {

    private val baseTs = 1_020_000L // 对齐到分钟起点（17 分钟整），保证 +30s 仍在同一桶

    @Test
    fun `相同输入同一分钟产生相同去重键`() {
        val a = DedupKeys.messageDedupKey("wechat", "张三", "张三", "明天开会", "tg:1", baseTs)
        val b = DedupKeys.messageDedupKey("wechat", "张三", "张三", "明天开会", "tg:1", baseTs + 30_000L)
        assertEquals(a, b)
    }

    @Test
    fun `同一句话隔一分钟是不同消息（A6）`() {
        val first = DedupKeys.messageDedupKey("wechat", "张三", "张三", "好的", "none", baseTs)
        val second = DedupKeys.messageDedupKey("wechat", "张三", "张三", "好的", "none", baseTs + 61_000L)
        assertNotEquals("跨分钟后同一句话不应被去重", first, second)
    }

    @Test
    fun `不同内容键不同`() {
        assertNotEquals(
            DedupKeys.contentHash("明天开会"),
            DedupKeys.contentHash("明天不开会"),
        )
    }

    @Test
    fun `去重键为 64 位十六进制`() {
        val key = DedupKeys.messageDedupKey("wechat", "张三", "张三", "明天开会", "none", baseTs)
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `空 notification_key 走 none 分支且稳定`() {
        assertEquals(
            DedupKeys.messageDedupKey("wechat", "a", "b", "c", "", baseTs),
            DedupKeys.messageDedupKey("wechat", "a", "b", "c", "none", baseTs),
        )
    }

    @Test
    fun `content_hash 十六进制小写`() {
        val h = DedupKeys.contentHash("x")
        assertEquals(64, h.length)
        assertEquals(h, h.lowercase())
    }
}