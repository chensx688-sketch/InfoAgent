package com.infoagent.data.repo

import com.infoagent.data.db.WechatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「要点 → 原话」的拼装规则。
 *
 * 这里守的是三件容易做错但肉眼看不出的事：
 * 顺序必须是时间顺序（而不是查询顺序）、被引用的那几条必须被标出来、消息被清理时要能说人话。
 */
class RefFocusBuilderTest {

    private fun msg(id: Long, ts: Long, content: String = "内容$id", peer: String = "p1"): WechatMessageEntity =
        WechatMessageEntity(
            id = id,
            peerId = peer,
            peerName = "陈英钿",
            sender = "陈英钿",
            direction = "IN",
            msgType = "text",
            content = content,
            ts = ts,
            firstTs = ts,
            lastTs = ts,
            dedupKey = "k$id",
        )

    private val base = 1_762_400_000_000L

    @Test
    fun `引用消息排在时间顺序里并被标记`() {
        val focused = listOf(msg(5, base + 60_000))
        val context = listOf(msg(3, base), msg(4, base + 30_000), msg(6, base + 120_000))
        val focus = RefFocusBuilder.build(focused, context)

        assertEquals(listOf(3L, 4L, 5L, 6L), focus.rows.map { it.msg.id })
        assertEquals(1, focus.focusedCount)
        assertTrue(focus.rows.first { it.msg.id == 5L }.focused)
        assertFalse(focus.rows.first { it.msg.id == 4L }.focused)
        assertEquals("陈英钿", focus.title)
        assertNull(focus.note)
    }

    @Test
    fun `重复消息只出现一次`() {
        val m = msg(5, base)
        val focus = RefFocusBuilder.build(listOf(m), listOf(m, msg(6, base + 1)))
        assertEquals(2, focus.rows.size)
        assertEquals(1, focus.focusedCount)
    }

    @Test
    fun `消息被清理时给出可读说明而不是空白`() {
        val focus = RefFocusBuilder.build(emptyList(), emptyList())
        assertTrue(focus.isEmpty)
        assertEquals("这些消息已经被清理，看不到了", focus.note)
    }

    @Test
    fun `引用丢失但有上下文时提示找不到原文`() {
        val focus = RefFocusBuilder.build(emptyList(), listOf(msg(1, base), msg(2, base + 10)))
        assertEquals(2, focus.rows.size)
        assertEquals(0, focus.focusedCount)
        assertTrue(focus.note.orEmpty().contains("找不到被引用的原文"))
    }

    @Test
    fun `上下文窗口以最早引用为中心前后各三小时`() {
        val w = RefFocusBuilder.window(listOf(msg(1, base + 5_000_000), msg(2, base)))
        assertEquals(base - RefFocusBuilder.CONTEXT_WINDOW_MS, w.first)
        assertEquals(base + RefFocusBuilder.CONTEXT_WINDOW_MS, w.last)
    }

    @Test
    fun `没有引用时窗口为原点附近不崩`() {
        val w = RefFocusBuilder.window(emptyList())
        assertEquals(-RefFocusBuilder.CONTEXT_WINDOW_MS, w.first)
    }
}