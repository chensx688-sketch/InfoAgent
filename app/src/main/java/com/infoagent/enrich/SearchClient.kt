package com.infoagent.enrich

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 网页搜索客户端（默认 Tavily；接口是「POST JSON 带 api_key」这一族的服务都能配）。
 *
 * 用途不是「让模型显得渊博」，而是查那些**模型不可能知道、但会直接影响决策**的事实：
 * 某地周末限行规则、某景区/场馆开放时间、某公司是做什么的、某条政策有没有生效。
 */
class SearchClient(private val http: OkHttpClient = QWeatherClient.shared()) {

    /** 一条查询 → 一句话摘要（带来源域名，方便用户自己核） */
    fun search(cfg: EnrichConfig, query: String): String? {
        if (query.isBlank()) return null
        val payload = JSONObject()
            .put("api_key", cfg.searchKey)
            .put("query", query)
            .put("search_depth", "basic")
            .put("max_results", 3)
            .put("include_answer", true)
        val req = Request.Builder()
            .url(cfg.searchEndpoint)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return null
                val json = JSONObject(body)
                val answer = json.optString("answer").take(200)
                val results = json.optJSONArray("results")
                val top = results?.optJSONObject(0)
                val title = top?.optString("title").orEmpty().take(60)
                val snippet = top?.optString("content").orEmpty().replace("\n", " ").take(140)
                val domain = top?.optString("url").orEmpty()
                    .substringAfter("://").substringBefore("/").removePrefix("www.")
                val core = answer.ifBlank { listOf(title, snippet).filter { it.isNotBlank() }.joinToString("：") }
                if (core.isBlank()) null else "搜「$query」：$core" + if (domain.isNotBlank()) "（来源 $domain）" else ""
            }
        }.getOrNull()
    }
}