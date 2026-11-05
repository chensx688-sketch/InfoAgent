package com.infoagent.remind

import android.content.Context

/**
 * B4：三段简报时点可调（默认 10:00 / 13:30 / 22:00）。
 * 用 SharedPreferences 同步读取，调度器与 Worker 都能直接拿到，无需协程。
 */
object BriefingPrefs {

    private const val FILE = "brief_times"
    private const val KEY_PREFIX = "time_"

    data class Time(val hour: Int, val minute: Int) {
        fun text(): String = "%02d:%02d".format(hour, minute)
    }

    val defaults = mapOf(
        "MORNING" to Time(10, 0),
        "AFTERNOON" to Time(13, 30),
        "EVENING" to Time(22, 0),
    )

    fun time(context: Context, window: String): Time {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PREFIX + window, null) ?: return defaults[window] ?: Time(10, 0)
        return parse(raw) ?: defaults[window] ?: Time(10, 0)
    }

    fun setTime(context: Context, window: String, text: String) {
        parse(text)?.let {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY_PREFIX + window, it.text()).apply()
        }
    }

    fun parse(text: String): Time? {
        val parts = text.trim().replace('：', ':').split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return Time(h, m)
    }

    /** 供调度器使用：把偏好展开成 Slot（window/label 与默认一致） */
    fun slots(context: Context): List<BriefingScheduler.Slot> = defaults.keys.map { window ->
        val t = time(context, window)
        val label = when (window) {
            "MORNING" -> "上午"
            "AFTERNOON" -> "下午"
            else -> "晚间"
        }
        BriefingScheduler.Slot(window, t.hour, t.minute, label)
    }

    /** A2：上午窗口长度（分钟）= 24h - 晚间点 + 上午点 */
    fun morningWindowMinutes(context: Context): Int {
        val t = times(context)
        return 24 * 60 - t.evening.hour * 60 - t.evening.minute + t.morning.hour * 60 + t.morning.minute
    }

    data class Times(val morning: Time, val afternoon: Time, val evening: Time)

    fun times(context: Context): Times = Times(
        morning = time(context, "MORNING"),
        afternoon = time(context, "AFTERNOON"),
        evening = time(context, "EVENING"),
    )

    private fun toMinutes(t: Time) = t.hour * 60 + t.minute

    /** A2：时点先后 + 窗口长度校验；返回 null 表示合法 */
    fun validate(context: Context): String? {
        val t = times(context)
        val m = toMinutes(t.morning)
        val a = toMinutes(t.afternoon)
        val e = toMinutes(t.evening)
        if (!(m < a && a < e)) return "时间必须满足：上午 < 下午 < 晚间"
        val morningLen = 24 * 60 - e + m
        if (morningLen > 16 * 60) {
            return "上午简报窗口约 ${morningLen / 60} 小时，超过 16 小时上限，请调整时点"
        }
        return null
    }

    /** 给设置页显示：当前窗口覆盖范围与长度 */
    fun coverageText(context: Context): String {
        val t = times(context)
        val len = morningWindowMinutes(context)
        fun fmt(x: Time) = "%02d:%02d".format(x.hour, x.minute)
        return "上午简报覆盖：昨日 ${fmt(t.evening)} – 今日 ${fmt(t.morning)}（${len / 60} 小时 ${len % 60} 分）"
    }
}