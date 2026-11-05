package com.infoagent.island

import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Bundle

/** 是否是三家已尝试适配的厂商（用于 SystemIslandAdapter 的判断与 Noop 兜底） */
private fun isVendorManufacturer(manufacturer: String = Build.MANUFACTURER): Boolean {
    val m = manufacturer.lowercase()
    return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
        m.contains("oppo") || m.contains("realme") || m.contains("oneplus")
}

/**
 * E3：HyperOS 焦点通知 —— 只装饰业务通知，不自己发。
 * 键名以真机抓取为准（这台 HyperOS 3 上 dumpsys 显示 focusType=PARAMS，说明 extras 被识别）。
 */
object HyperOsAdapter : IslandAdapter {

    override val id = "hyperos"

    override fun matches(manufacturer: String): Boolean {
        val m = manufacturer.lowercase()
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    override fun isAvailable(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 33

    override fun capabilities(): Set<IslandCapability> =
        setOf(IslandCapability.PROGRESS, IslandCapability.OPEN_APP)

    override fun decorate(ctx: Context, b: Notification.Builder, payload: IslandPayload) {
        runCatching {
            b.addExtras(
                Bundle().apply {
                    putString("miui.focus.param", payload.content)
                    putString("miui.focus.ticker", payload.title)
                    putBoolean("miui.focus.notification", true)
                    putInt("miui.focus.progress", payload.progress ?: -1)
                },
            )
        }
    }
}

/**
 * E4：ColorOS 灵动胶囊 —— 同样只装饰；ColorOS 对第三方基本关门，失败即普通通知。
 */
object ColorOsAdapter : IslandAdapter {

    override val id = "coloros"

    override fun matches(manufacturer: String): Boolean {
        val m = manufacturer.lowercase()
        return m.contains("oppo") || m.contains("realme") || m.contains("oneplus")
    }

    override fun isAvailable(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 33

    override fun capabilities(): Set<IslandCapability> =
        setOf(IslandCapability.PROGRESS, IslandCapability.OPEN_APP)

    override fun decorate(ctx: Context, b: Notification.Builder, payload: IslandPayload) {
        runCatching {
            b.addExtras(
                Bundle().apply {
                    putBoolean("com.oplus.capsule.enable", true)
                    putBoolean("oplus.capsule.enable", true)
                    putString("com.oplus.capsule.title", payload.title)
                    putString("com.oplus.capsule.content", payload.content)
                    putInt("com.oplus.capsule.progress", payload.progress ?: -1)
                },
            )
        }
    }
}

/** 非三厂商设备（或厂商适配器不可用时的兜底）：只保持普通系统通知，绝不加超时 */
object SystemIslandAdapter : IslandAdapter {

    override val id = "system"

    override fun matches(manufacturer: String): Boolean = true

    /** P2-1：三家厂商交给各自适配器；只有其余设备才轮到它，Noop 才是最终兜底 */
    override fun isAvailable(ctx: Context): Boolean = !isVendorManufacturer()

    override fun capabilities(): Set<IslandCapability> =
        setOf(IslandCapability.PROGRESS, IslandCapability.OPEN_APP)

    override fun decorate(ctx: Context, b: Notification.Builder, payload: IslandPayload) = Unit
}

/** 最终兜底：什么都不做 */
object NoopAdapter : IslandAdapter {
    override val id = "noop"
    override fun matches(manufacturer: String): Boolean = true
    override fun isAvailable(ctx: Context): Boolean = true
    override fun capabilities(): Set<IslandCapability> = emptySet()
    override fun decorate(ctx: Context, b: Notification.Builder, payload: IslandPayload) = Unit
}