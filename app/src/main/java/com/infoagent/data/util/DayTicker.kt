package com.infoagent.data.util

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A9：跨零点刷新信号。
 * App 常驻后台过 0 点再切回时，Activity.onResume 调用 bump()，备忘页据此重算 today 并归档。
 */
object DayTicker {
    val tick = MutableStateFlow(0L)
    fun bump() {
        tick.value = System.currentTimeMillis()
    }
}