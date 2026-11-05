package com.infoagent.remind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class BriefingSchedulerTest {

    private val zone = java.time.ZoneId.of("Asia/Shanghai")

    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone)

    @Test
    fun `当天未到点则当天触发`() {
        val next = BriefingScheduler.nextTrigger(at(4, 9, 0), 10, 0)
        assertEquals(at(4, 10, 0), next)
    }

    @Test
    fun `已过点顺延到次日`() {
        val next = BriefingScheduler.nextTrigger(at(4, 10, 0), 10, 0)
        assertEquals(at(5, 10, 0), next)
        val next2 = BriefingScheduler.nextTrigger(at(4, 23, 59), 10, 0)
        assertEquals(at(5, 10, 0), next2)
    }

    @Test
    fun `三段时点固定为 10点 13点30 22点`() {
        val slots = BriefingScheduler.SLOTS
        assertEquals(3, slots.size)
        assertEquals(Triple("MORNING", 10, 0), Triple(slots[0].window, slots[0].hour, slots[0].minute))
        assertEquals(Triple("AFTERNOON", 13, 30), Triple(slots[1].window, slots[1].hour, slots[1].minute))
        assertEquals(Triple("EVENING", 22, 0), Triple(slots[2].window, slots[2].hour, slots[2].minute))
    }

    @Test
    fun `触发时间必须晚于当前时间`() {
        val now = at(4, 12, 0)
        for (slot in BriefingScheduler.SLOTS) {
            assertTrue(BriefingScheduler.nextTrigger(now, slot.hour, slot.minute).isAfter(now))
        }
    }
}