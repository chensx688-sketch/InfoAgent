package com.infoagent.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseAndGroupNameTest {

    private fun wechat(title: String?, text: String?, big: String? = null, ts: Long = 1_000L, group: Boolean = false) =
        RawNotification(NotificationParser.WECHAT, title, text, big, "tag", "msg", ts, knownGroup = group)

    @Test
    fun `标题带发送者冒号时拆成会话与正文`() {
        val out = NotificationParser.parse(wechat("王总: 明天下午三点开会", ""))
        val m = (out as ParseOutcome.Messages).messages.single()
        assertEquals("王总", m.peerName)
        assertEquals("明天下午三点开会", m.content)
    }

    @Test
    fun `正文行以会话名开头时剥离前缀`() {
        val out = NotificationParser.parse(wechat("产品交流群", "产品交流群: 李四: 周五交周报", group = true))
        val m = (out as ParseOutcome.Messages).messages.single()
        assertEquals("产品交流群", m.peerName)
        assertEquals("李四", m.sender)
        assertEquals("周五交周报", m.content)
    }

    @Test
    fun `单聊正文里的冒号不当作发送者（A5）`() {
        val out = NotificationParser.parse(wechat("王总", "会议地点：A 座 3 楼"))
        val m = (out as ParseOutcome.Messages).messages.single()
        assertEquals("王总", m.sender)
        assertEquals("会议地点：A 座 3 楼", m.content)
        assertFalse(m.isGroup)
    }

    @Test
    fun `单聊正文里的 URL 不被当成发送者（A5）`() {
        val out = NotificationParser.parse(wechat("妈妈", "https://example.com/a?b=1 你看看"))
        val m = (out as ParseOutcome.Messages).messages.single()
        assertEquals("妈妈", m.sender)
        assertTrue(m.content.startsWith("https://"))
    }

    @Test
    fun `整行等于会话名时丢弃该行`() {
        val out = NotificationParser.parse(wechat("产品交流群", "产品交流群"))
        assertTrue(out is ParseOutcome.Ignored)
    }

    @Test
    fun `单个历史前缀不再直接判群（避免误判）`() {
        val out = NotificationParser.parse(
            wechat("王总", "李四: 明天见").copy(knownSenders = setOf("李四")),
        )
        val m = (out as ParseOutcome.Messages).messages.single()
        assertFalse("单证据不应判群", m.isGroup)
        assertEquals("王总", m.sender)
        assertEquals("李四: 明天见", m.content)
    }

    @Test
    fun `两个历史前缀才判群（N1）`() {
        val out = NotificationParser.parse(
            wechat("项目群", "李四: 周五交周报").copy(knownSenders = setOf("李四", "王五")),
        )
        val m = (out as ParseOutcome.Messages).messages.single()
        assertEquals("李四", m.sender)
        assertEquals("周五交周报", m.content)
        assertTrue(m.isGroup)
    }

    @Test
    fun `多行两个前缀先切 sender 但不标群（B2 可纠错）`() {
        val out = NotificationParser.parse(wechat("项目群", "李四: 文档放群文件了\n王五: 收到"))
        val msgs = (out as ParseOutcome.Messages).messages
        assertEquals(2, msgs.size)
        assertEquals(listOf("李四", "王五"), msgs.map { it.sender })
        assertFalse("单批多前缀只是疑似，不应直接写 isGroup", msgs.any { it.isGroup })
    }

    @Test
    fun `历史发送者与本次前缀交叉时升级为群（B2）`() {
        val out = NotificationParser.parse(
            wechat("项目群", "李四: 文档放群文件了\n王五: 收到").copy(knownSenders = setOf("李四")),
        )
        val msgs = (out as ParseOutcome.Messages).messages
        assertTrue("命中历史 + 并集≥2 应判群", msgs.all { it.isGroup })
    }

    @Test
    fun `垃圾内容被过滤`() {
        assertTrue(NoiseFilter.isNoise("产品交流群", "帮忙砍一刀谢谢"))
        assertTrue(NoiseFilter.isNoise("福利优惠群", "今天下午有活动"))
        assertTrue(NoiseFilter.isNoise("王总", "[图片]"))
        assertTrue(NoiseFilter.isNoise("王总", "https://example.com/a"))
        assertTrue(NoiseFilter.isNoise("王总", "。。"))
        assertTrue(NoiseFilter.isNoise("王总", "😂😂😂"))
    }

    @Test
    fun `正常待办内容不被过滤`() {
        assertFalse(NoiseFilter.isNoise("王总", "明天下午三点开会，记得带合同"))
        assertFalse(NoiseFilter.isNoise("家人群", "周末回家吃饭"))
        assertFalse(NoiseFilter.isNoise("产品交流群", "周五下班前交周报"))
    }
}