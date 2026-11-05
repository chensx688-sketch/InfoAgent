package com.infoagent.capture

/**
 * 垃圾消息过滤（简报“全是垃圾消息”问题的第一道闸）：
 * 广告/砍价/接龙/红包/纯链接/纯表情/群公告营销等一律不进入 AI 素材；
 * 会话名命中营销词（如“优惠群”“福利群”）时整会话降权。
 */
object NoiseFilter {

    private val CONTENT_PATTERNS = listOf(
        "砍一刀", "拼多多", "帮我助力", "助力一下", "领红包", "领取红包", "点击领取",
        "优惠券", "限时特价", "秒杀", "代购", "刷单", "兼职", "招聘", "贷款", "加微信",
        "接龙", "拉票", "投票", "群公告", "关注公众号", "小程序", "点击链接", "复制口令",
        "免费领", "0元", "包邮", "返现", "开户", "荐股", "牛股", "健身卡", "办卡",
    )

    private val PEER_PATTERNS = listOf(
        "优惠", "福利", "红包", "砍价", "助力", "拼单", "团购", "广告", "推广",
        "闲置", "二手", "招聘", "求职", "兼职", "贷款", "贷款", "粉丝",
    )

    private val PURE_MEDIA = Regex("""^(\[[^\]]{1,6}\])+$""")
    private val PURE_URL = Regex("""^(https?://|www\.)\S+$""")
    private val SYMBOL_ONLY = Regex("""^[\p{P}\p{S}\s]+$""")

    fun isNoise(peerName: String, content: String): Boolean {
        val text = content.trim()
        if (text.isEmpty()) return true
        if (text.length <= 1) return true
        if (PURE_MEDIA.matches(text)) return true
        if (PURE_URL.matches(text)) return true
        if (SYMBOL_ONLY.matches(text)) return true
        // 纯表情/颜文字（无中文无数字）
        if (text.none { it.isDigit() } && text.none { it.code in 0x4E00..0x9FFF }) return true
        val hay = "$peerName $text"
        if (PEER_PATTERNS.any { peerName.contains(it) }) return true
        if (CONTENT_PATTERNS.any { text.contains(it) }) return true
        return false
    }
}