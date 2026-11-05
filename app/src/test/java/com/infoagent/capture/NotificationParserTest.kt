package com.infoagent.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {

    private fun wechat(title: String?, text: String?, big: String? = null, ts: Long = 1000L, group: Boolean = false) =
        RawNotification(NotificationParser.WECHAT, title, text, big, "tag", "msg", ts, knownGroup = group)

    @Test
    fun `单聊消息解析`() {
        val out = NotificationParser.parse(wechat("王总", "明天下午三点开会"))
        assertTrue(out is ParseOutcome.Messages)
        val m = (out as ParseOutcome.Messages).messages.single()
        assertEquals("王总", m.peerName)
        assertEquals("明天下午三点开会", m.content)
        assertEquals("text", m.msgType)
        assertEquals(false, m.isGroup)
        assertEquals("wechat", m.appSource)
    }

    @Test
    fun `群聊发送者前缀解析`() {
        val out = NotificationParser.parse(wechat("产品交流群", "李四: 周五前交周报", group = true))
        val m = (out as ParseOutcome.Messages).messages.single()
        assertEquals("产品交流群", m.peerName)
        assertEquals("李四", m.sender)
        assertEquals("周五前交周报", m.content)
        assertTrue(m.isGroup)
    }

    @Test
    fun `多行 bigText 逐行拆解`() {
        val out = NotificationParser.parse(wechat("产品交流群", null, "李四: 文档放群文件了\n王五: 收到", group = true))
        val msgs = (out as ParseOutcome.Messages).messages
        assertEquals(2, msgs.size)
        assertEquals("李四", msgs[0].sender)
        assertEquals("王五", msgs[1].sender)
    }

    @Test
    fun `折叠通知生成占位`() {
        val out = NotificationParser.parse(wechat("产品交流群", "10条新消息"))
        assertTrue(out is ParseOutcome.Folded)
        assertEquals(10, (out as ParseOutcome.Folded).count)
    }

    @Test
    fun `撤回通知进入 Withdraw 候选`() {
        val out = NotificationParser.parse(wechat("产品交流群", "李四撤回了一条消息"))
        assertTrue(out is ParseOutcome.Withdraw)
        assertEquals("李四", (out as ParseOutcome.Withdraw).sender)
    }

    @Test
    fun `媒体占位符映射类型`() {
        val out = NotificationParser.parse(wechat("王总", "[图片]"))
        val m = (out as ParseOutcome.Messages).messages.single()
        assertEquals("image", m.msgType)
        assertEquals("[图片]", m.content)
    }

    @Test
    fun `系统与空正文忽略`() {
        assertTrue(NotificationParser.parse(wechat("产品交流群", "你已成为新管理员")) is ParseOutcome.Ignored)
        assertTrue(NotificationParser.parse(wechat("王总", "")) is ParseOutcome.Folded)
        assertTrue(NotificationParser.parse(wechat(null, "x")) is ParseOutcome.Ignored)
    }

    @Test
    fun `非支持应用忽略`() {
        assertTrue(NotificationParser.parse(RawNotification("com.other", "a", "b", null, null, null, 1L)) is ParseOutcome.Ignored)
    }

    @Test
    fun `QQ 群@保留原文并标注来源`() {
        val out = NotificationParser.parse(
            RawNotification(
                NotificationParser.QQ, "产品交流群", "李四: @全体成员 周五下班前交周报", null, null, "msg", 1000L,
                knownGroup = true,
            ),
        )
        val m = (out as ParseOutcome.Messages).messages.single()
        assertEquals("qq", m.appSource)
        assertTrue(m.content.contains("@全体成员"))
    }
}