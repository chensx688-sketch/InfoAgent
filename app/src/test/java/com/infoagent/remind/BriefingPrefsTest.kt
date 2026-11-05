package com.infoagent.remind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B4：简报时点解析/默认值（纯 JVM） */
class BriefingPrefsTest {

    @Test
    fun `解析合法时间`() {
        assertEquals(BriefingPrefs.Time(10, 0), BriefingPrefs.parse("10:00"))
        assertEquals(BriefingPrefs.Time(9, 5), BriefingPrefs.parse("9:05"))
        assertEquals(BriefingPrefs.Time(22, 30), BriefingPrefs.parse("22：30"))
    }

    @Test
    fun `非法时间返回 null`() {
        assertNull(BriefingPrefs.parse("24:00"))
        assertNull(BriefingPrefs.parse("10:60"))
        assertNull(BriefingPrefs.parse("abc"))
        assertNull(BriefingPrefs.parse(""))
        assertNull(BriefingPrefs.parse("10"))
    }

    @Test
    fun `默认时点仍是 10点 13点半 22点`() {
        assertEquals(BriefingPrefs.Time(10, 0), BriefingPrefs.defaults["MORNING"])
        assertEquals(BriefingPrefs.Time(13, 30), BriefingPrefs.defaults["AFTERNOON"])
        assertEquals(BriefingPrefs.Time(22, 0), BriefingPrefs.defaults["EVENING"])
    }
}