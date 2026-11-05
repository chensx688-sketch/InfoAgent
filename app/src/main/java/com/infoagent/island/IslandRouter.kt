package com.infoagent.island

import android.app.Notification
import android.content.Context
import android.os.Build

/**
 * Router：按「厂商匹配 + 是否可用」选一个适配器，只做装饰，不发通知。
 */
object IslandRouter {

    private val candidates = listOf(HyperOsAdapter, ColorOsAdapter, SystemIslandAdapter, NoopAdapter)

    /** manufacturer 参数只为测试注入，业务不传 */
    fun pick(ctx: Context, manufacturer: String = Build.MANUFACTURER): IslandAdapter {
        val m = manufacturer.lowercase()
        return candidates.firstOrNull { it.matches(m) && it.isAvailable(ctx) } ?: NoopAdapter
    }

    /** 装饰已有 builder；异常时原样返回，绝不阻断通知 */
    fun decorate(ctx: Context, b: Notification.Builder, payload: IslandPayload): Notification.Builder =
        runCatching {
            pick(ctx).decorate(ctx, b, payload.normalized())
            b
        }.getOrElse { b }

    fun capabilities(ctx: Context): Set<IslandCapability> =
        runCatching { pick(ctx).capabilities() }.getOrDefault(emptySet())
}