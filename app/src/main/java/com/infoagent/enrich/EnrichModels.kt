package com.infoagent.enrich

/**
 * 外部信息（天气/生活指数/搜索结果）——简报的「查证层」。
 *
 * 为什么要有这一层：模型不联网，只靠常识写「11 月西湖风大」这种话，对用户来说是**没用的空话**。
 * 有了这一层，才能写出「明天杭州 12~19℃、北风 3 级、有阵雨，带伞」这种能直接用的判断。
 *
 * 设计原则：
 * 1) 只用用户自己的 key（和风天气 / 搜索服务），配额归用户，不经过我们的服务器；
 * 2) 查不到就什么都不说，绝不伪造（[ExternalFacts.ok] 为 false 时 prompt 里不出现外部信息）；
 * 3) 结果落库缓存，避免「连点几次补一份」把免费额度烧掉。
 */

/** 查询计划：要查哪些地点/日期、要搜什么 */
data class EnrichPlan(
    val places: List<PlaceQuery> = emptyList(),
    val queries: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = places.isEmpty() && queries.isEmpty()
}

data class PlaceQuery(val city: String, val date: String, val why: String = "")

/**
 * 查到的外部事实。
 *
 * [lines] 里每一行都是**可以直接写进简报的句子**（已包含地点与时间），
 * 这样模型只需要引用、不需要再加工数字，减少编造空间。
 */
data class ExternalFacts(
    val lines: List<String> = emptyList(),
    val ok: Boolean = false,
    /** 出问题的原因（给 UI/日志用，不进 prompt） */
    val error: String? = null,
    val fromCache: Boolean = false,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    fun toPromptBlock(): String = if (lines.isEmpty()) "" else lines.joinToString("\n") { "- $it" }
}

/** 外部接口配置（key 存加密区，其余存 DataStore） */
data class EnrichConfig(
    val weatherKey: String = "",
    /** 和风天气的 API Host，新账号是 xxxx.re.qweatherapi.com；老接口是 devapi.qweather.com */
    val weatherHost: String = "devapi.qweather.com",
    val searchKey: String = "",
    val searchEndpoint: String = "https://api.tavily.com/search",
    /** 消息里没提城市时的兜底（用户常住地） */
    val homeCity: String = "",
) {
    val weatherEnabled: Boolean get() = weatherKey.isNotBlank() && weatherHost.isNotBlank()
    val searchEnabled: Boolean get() = searchKey.isNotBlank() && searchEndpoint.isNotBlank()
    val anyEnabled: Boolean get() = weatherEnabled || searchEnabled
}