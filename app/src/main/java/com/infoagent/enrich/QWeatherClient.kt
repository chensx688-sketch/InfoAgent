package com.infoagent.enrich

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 和风天气（QWeather）客户端：城市解析 → 实况 → 逐日预报 → 生活指数。
 *
 * 兼容两种 API Host：
 * - 老接口：devapi.qweather.com / api.qweather.com（另配 geoapi.qweather.com 做城市查询）
 * - 2024 后的账号专属 Host：xxxxxx.re.qweatherapi.com（城市查询与数据同一 Host）
 *
 * 只取「能直接影响今天决策」的字段：温度区间、天气现象、风、降水概率、穿衣/紫外线/雨伞指数。
 * 湿度气压这些不取——简报里多一行没用信息，就多一行噪音。
 */
class QWeatherClient(private val http: OkHttpClient = shared()) {

    companion object {
        fun shared(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()

        private val HOSTILE = setOf("穿衣", "紫外线", "雨伞", "舒适度", "交通", "旅游", "运动", "过敏")
    }

    /** 城市名 → 和风 LocationID（失败返回 null，不抛给上层） */
    fun cityId(cfg: EnrichConfig, city: String): String? {
        val url = "${geoBase(cfg.weatherHost)}/geo/v2/city/lookup?location=${enc(city)}&key=${enc(cfg.weatherKey)}&lang=zh"
        val json = getJson(url) ?: return null
        if (json.optString("code") != "200") return null
        val arr = json.optJSONArray("location") ?: return null
        if (arr.length() == 0) return null
        val first = arr.optJSONObject(0) ?: return null
        return first.optString("id").takeIf { it.isNotBlank() }
    }

    /** 实况 + 目标日预报 + 生活指数，全部拼成可直接引用的句子 */
    fun facts(cfg: EnrichConfig, place: PlaceQuery): List<String> {
        val id = cityId(cfg, place.city) ?: return emptyList()
        val base = base(cfg.weatherHost)
        val out = mutableListOf<String>()
        val label = place.city.ifBlank { "当地" }

        // 实况
        getJson("$base/v7/weather/now?location=$id&key=${enc(cfg.weatherKey)}&lang=zh&unit=m")
            ?.optJSONObject("now")
            ?.let { now ->
                val text = now.optString("text")
                val temp = now.optString("temp")
                val feel = now.optString("feelsLike")
                val wind = now.optString("windDir") + " " + now.optString("windScale") + " 级"
                if (temp.isNotBlank()) {
                    out.add("$label 实况：$text ${temp}℃" + (if (feel.isNotBlank() && feel != temp) "（体感 $feel℃）" else "") + "，$wind")
                }
            }

        // 目标日预报（3 天窗内才取得到）
        getJson("$base/v7/weather/3d?location=$id&key=${enc(cfg.weatherKey)}&lang=zh&unit=m")
            ?.optJSONArray("daily")
            ?.let { daily ->
                val target = place.date
                for (i in 0 until daily.length()) {
                    val d = daily.optJSONObject(i) ?: continue
                    if (d.optString("fxDate") != target) continue
                    val sb = StringBuilder()
                    sb.append("$target $label：")
                    sb.append(d.optString("textDay"))
                    if (d.optString("textNight").isNotBlank() && d.optString("textNight") != d.optString("textDay")) {
                        sb.append("转").append(d.optString("textNight"))
                    }
                    sb.append("，").append(d.optString("tempMin")).append("~").append(d.optString("tempMax")).append("℃")
                    val wind = d.optString("windDirDay") + " " + d.optString("windScaleDay") + " 级"
                    if (wind.isNotBlank()) sb.append("，").append(wind)
                    val pop = d.optString("precip")
                    if (d.optString("pop").isNotBlank()) sb.append("，降水概率 ").append(d.optString("pop")).append("%")
                    if (pop.isNotBlank() && pop != "0.0" && pop != "0") sb.append("（预计降水 ").append(pop).append("mm）")
                    out.add(sb.toString())
                    break
                }
            }

        // 生活指数：只留与出行/穿衣相关的几项
        getJson("$base/v7/indices/1d?type=0&location=$id&key=${enc(cfg.weatherKey)}&lang=zh")
            ?.optJSONArray("daily")
            ?.let { arr ->
                for (i in 0 until arr.length()) {
                    val it2 = arr.optJSONObject(i) ?: continue
                    val name = it2.optString("name")
                    if (name !in HOSTILE) continue
                    val category = it2.optString("category")
                    val text = it2.optString("text").take(60)
                    if (category.isBlank()) continue
                    out.add("$label $name 指数：$category" + if (text.isNotBlank()) "（$text）" else "")
                }
            }
        return out
    }

    /** 允许直接写完整地址（自建代理/MockWebServer）；否则按 https 拼 */
    private fun base(host: String): String {
        val h = host.trim().trimEnd('/')
        return if (h.startsWith("http://") || h.startsWith("https://")) h else "https://$h"
    }

    /**
     * 城市查询的 host：新账号的专属 Host（xxx.re.qweatherapi.com）同一个域名即可；
     * 老的 devapi/api 需要换成 geoapi。给了完整地址时原样使用。
     */
    private fun geoBase(host: String): String {
        val h = host.trim().trimEnd('/')
        if (h.startsWith("http://") || h.startsWith("https://")) return h
        val bare = h
        return when {
            bare.contains("re.qweatherapi.com") -> "https://$bare"
            bare.contains("devapi") || bare.contains("api.qweather.com") -> "https://geoapi.qweather.com"
            else -> "https://$bare"
        }
    }

    private fun getJson(url: String): JSONObject? = runCatching {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            JSONObject(body)
        }
    }.getOrNull()

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}