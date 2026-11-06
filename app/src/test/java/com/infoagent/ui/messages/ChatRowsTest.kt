package com.infoagent.ui.messages

import com.infoagent.data.db.WechatMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话行分组的规则测试：这段逻辑原来没有测试，改哪都得靠肉眼 */
class ChatRowsTest {

    private var seq = 0L

    private fun msg(
        ts: Long,
        direction: String = "IN",
        sender: String = "王总",
        text: String = "内容",
        type: String = "text",
        recalled: Long = 0,
    ) = WechatMessageEntity(
        id = ++seq,
        peerId = "p1",
        peerName = "王总",
        sender = sender,
        direction = direction,
        msgType = type,
        content = text,
        ts = ts,
        firstTs = ts,
        lastTs = ts,
        dedupKey = "k$seq",
        recalled = recalled,
    )

    private fun rowsOf(list: List<WechatMessageEntity>, isGroup: Boolean = false) =
        ChatRows.build(list, isGroup)

    @Test
    fun `连续三条在两分钟内合成一簇只显示一次时间`() {
        val t = 1_800_000_000_000L
        val rows = rowsOf(listOf(msg(t), msg(t + 30_000), msg(t + 60_000)))
        assertEquals("一条分隔 + 三条消息", 4, rows.size)
        assertTrue(rows[0] is ChatRow.Separator)
        val msgs = rows.filterIsInstance<ChatRow.Msg>()
        assertEquals(listOf(ChatRow.Pos.FIRST, ChatRow.Pos.MIDDLE, ChatRow.Pos.LAST), msgs.map { it.pos })
    }

    @Test
    fun `单独一条是 SINGLE 而不是 FIRST`() {
        val rows = rowsOf(listOf(msg(1_800_000_000_000L)))
        val single = rows.filterIsInstance<ChatRow.Msg>().single()
        assertEquals(ChatRow.Pos.SINGLE, single.pos)
    }

    @Test
    fun `隔了十分钟才插一行时间`() {
        val t = 1_800_000_000_000L
        val rows = rowsOf(listOf(msg(t), msg(t + 10 * 60_000)))
        val separators = rows.filterIsInstance<ChatRow.Separator>()
        assertEquals("第一簇带日期，第二簇只带时间", 2, separators.size)
        assertNull("第二条不该再显示日期", separators[1].day)
    }

    @Test
    fun `隔了四分钟算两簇但不插时间`() {
        val t = 1_800_000_000_000L
        val rows = rowsOf(listOf(msg(t), msg(t + 4 * 60_000)))
        assertEquals("同一天内间隔不足 5 分钟不出新分隔", 1, rows.filterIsInstance<ChatRow.Separator>().size)
        assertEquals(2, rows.filterIsInstance<ChatRow.Msg>().size)
    }

    @Test
    fun `换天一定插日期分隔`() {
        val day1 = 1_800_000_000_000L
        val day2 = day1 + 24 * 60 * 60 * 1000L
        val rows = rowsOf(listOf(msg(day1), msg(day2)))
        val separators = rows.filterIsInstance<ChatRow.Separator>()
        assertEquals(2, separators.size)
        assertTrue("两条都应有日期", separators.all { it.day != null })
    }

    @Test
    fun `群聊里换人立刻换簇并显示名字`() {
        val t = 1_800_000_000_000L
        val rows = rowsOf(
            listOf(msg(t, sender = "王总"), msg(t + 10_000, sender = "李经理")),
            isGroup = true,
        )
        val msgs = rows.filterIsInstance<ChatRow.Msg>()
        assertEquals(2, msgs.size)
        assertTrue("第二条是新的人，应显示名字", msgs[1].showSender)
        assertEquals("簇内换人也算两条独立消息", ChatRow.Pos.SINGLE, msgs[1].pos)
    }

    @Test
    fun `单聊不显示发送者名字`() {
        val rows = rowsOf(listOf(msg(1_800_000_000_000L)), isGroup = false)
        assertTrue(rows.filterIsInstance<ChatRow.Msg>().none { it.showSender })
    }

    @Test
    fun `自己发的消息不显示名字`() {
        val rows = rowsOf(listOf(msg(1_800_000_000_000L, direction = "OUT", sender = "我")), isGroup = true)
        assertTrue(rows.filterIsInstance<ChatRow.Msg>().none { it.showSender })
    }

    @Test
    fun `一问一答不会并成一簇`() {
        val t = 1_800_000_000_000L
        val rows = rowsOf(
            listOf(
                msg(t, direction = "IN", sender = "王总"),
                msg(t + 30_000, direction = "OUT", sender = "我"),
            ),
        )
        val msgs = rows.filterIsInstance<ChatRow.Msg>()
        assertEquals(listOf(ChatRow.Pos.SINGLE, ChatRow.Pos.SINGLE), msgs.map { it.pos })
    }

    @Test
    fun `乱序输入也按时间排好`() {
        val t = 1_800_000_000_000L
        val rows = rowsOf(listOf(msg(t + 60_000), msg(t), msg(t + 30_000)))
        val msgs = rows.filterIsInstance<ChatRow.Msg>()
        assertEquals(3, msgs.size)
        assertEquals(listOf(t, t + 30_000, t + 60_000), msgs.map { it.message.ts })
    }

    @Test
    fun `类型名翻译成人话`() {
        assertEquals("图片", ChatRows.typeLabel("image"))
        assertEquals("语音", ChatRows.typeLabel("voice"))
        assertEquals("文件", ChatRows.typeLabel("file"))
        assertNull("文字消息不该有标签", ChatRows.typeLabel("text"))
        assertEquals("非文字", ChatRows.typeLabel("weird_type"))
    }

    @Test
    fun `空列表不会崩`() {
        assertTrue(ChatRows.build(emptyList(), isGroup = true).isEmpty())
    }
}