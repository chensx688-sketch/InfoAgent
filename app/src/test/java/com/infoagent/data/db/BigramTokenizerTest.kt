package com.infoagent.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BigramTokenizerTest {

    @Test
    fun `纯中文按二字组切分`() {
        assertEquals(listOf("明天", "天带", "带合", "合同"), BigramTokenizer.queryTokens("明天带合同"))
    }

    @Test
    fun `单字与两字中文`() {
        assertEquals(listOf("好"), BigramTokenizer.queryTokens("好"))
        assertEquals(listOf("开会"), BigramTokenizer.queryTokens("开会"))
    }

    @Test
    fun `混合中英文切分`() {
        val tokens = BigramTokenizer.queryTokens("看这个 https://example.com")
        assertTrue(tokens.contains("看这"))
        assertTrue(tokens.contains("这个"))
        assertTrue(tokens.contains("https://example.com"))
    }

    @Test
    fun `空输入与空白输入`() {
        assertTrue(BigramTokenizer.queryTokens("").isEmpty())
        assertTrue(BigramTokenizer.queryTokens("   ").isEmpty())
    }

    @Test
    fun `tokenString 可落盘且与 queryTokens 一致`() {
        val tokens = BigramTokenizer.queryTokens("明天带合同")
        assertEquals(tokens.joinToString(" "), BigramTokenizer.tokenString("明天带合同"))
    }

    @Test
    fun `matchExpression AND 组合`() {
        assertEquals("\"明天\" \"天带\" \"带合\" \"合同\"", BigramTokenizer.matchExpression("明天带合同"))
        assertEquals(null, BigramTokenizer.matchExpression(" "))
    }

    @Test
    fun `搜索合同应命中明天带合同的 token 集`() {
        // 查询词自己的 bigram 必须出现在文档 token 集里（子串重算规则）
        val docTokens = BigramTokenizer.tokenString("明天带合同")
        for (t in BigramTokenizer.queryTokens("合同")) {
            assertTrue("token[$t] should be in doc", t in docTokens.split(" "))
        }
    }
}