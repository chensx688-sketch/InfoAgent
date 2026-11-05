package com.infoagent.data.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DayBoundaryTest {

    @Test
    fun `today 格式与合法性`() {
        val t = DayBoundary.today()
        assertTrue(DayBoundary.isValid(t))
        assertTrue(t.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun `dayRange 为左闭右开且跨度一天`() {
        val (start, end) = DayBoundary.dayRange("2026-11-04")
        assertTrue(start < end)
        assertEquals(24 * 3600_000L, end - start)
    }

    @Test
    fun `shift 跨月与跨年`() {
        assertEquals("2026-12-01", DayBoundary.shift("2026-11-30", 1))
        assertEquals("2027-01-01", DayBoundary.shift("2026-12-31", 1))
        assertEquals("2026-12-31", DayBoundary.shift("2027-01-01", -1))
    }

    @Test
    fun `非法日期不被接受`() {
        assertFalse(DayBoundary.isValid("2026-13-01"))
        assertFalse(DayBoundary.isValid("abc"))
    }

    @Test
    fun `dayOf 落在当日内`() {
        val (start, end) = DayBoundary.dayRange(DayBoundary.today())
        val mid = start + (end - start) / 2
        assertEquals(DayBoundary.today(), DayBoundary.dayOf(mid))
    }
}