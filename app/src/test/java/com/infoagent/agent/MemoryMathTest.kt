package com.infoagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMathTest {

    @Test
    fun `初始置信度 50`() {
        assertEquals(50, MemoryMath.confidence(0, 0, 0.0))
    }

    @Test
    fun `证据增加带来提升但不会一步到 100`() {
        val c10 = MemoryMath.confidence(10, 0, 0.0)
        assertTrue(c10 in 70..90)
        assertTrue(MemoryMath.confidence(100, 0, 0.0) <= 100)
    }

    @Test
    fun `时间衰减降低置信度`() {
        val fresh = MemoryMath.confidence(3, 0, 0.0)
        val old = MemoryMath.confidence(3, 0, 10.0)
        assertTrue(old < fresh)
    }

    @Test
    fun `反馈上下限夹紧`() {
        assertEquals(100, MemoryMath.applyFeedback(99, true))
        assertEquals(0, MemoryMath.applyFeedback(3, false))
    }

    @Test
    fun `DISMISSED 复活需要至少两条新证据`() {
        assertFalse(MemoryMath.canRevive(1, 5, 0, 0.0))
        assertTrue(MemoryMath.canRevive(2, 5, 0, 0.0))
    }

    @Test
    fun `衰减过久即使有新证据也不能复活`() {
        assertFalse(MemoryMath.canRevive(2, 1, 0, 60.0))
    }
}