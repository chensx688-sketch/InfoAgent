package com.infoagent.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportParserTest {

    @Test
    fun `按名字冒号逐行解析`() {
        val list = ImportParser.parse("王总: 明天下午三点开会\n王总: 记得带合同\n妈妈: 周末回家吃饭", baseTs = 10_000L)
        assertEquals(3, list.size)
        assertEquals("王总", list[0].peerName)
        assertEquals("明天下午三点开会", list[0].content)
        assertEquals("妈妈", list[2].peerName)
        // 时间递增保持行序
        assertTrue(list[0].ts < list[1].ts && list[1].ts < list[2].ts)
    }

    @Test
    fun `无前缀行归入上一个会话`() {
        val list = ImportParser.parse("王总: 第一条\n第二条继续\n妈妈: 换人了")
        assertEquals(3, list.size)
        assertEquals("王总", list[1].peerName)
        assertEquals("第二条继续", list[1].content)
        assertEquals("妈妈", list[2].peerName)
    }

    @Test
    fun `空输入与全空白返回空`() {
        assertTrue(ImportParser.parse("").isEmpty())
        assertTrue(ImportParser.parse("  \n \n").isEmpty())
    }
}