package com.infoagent.enrich

import com.infoagent.agent.AgentClient
import com.infoagent.agent.ApiConfig
import com.infoagent.agent.BudgetGuard
import com.infoagent.agent.ChatMessage
import com.infoagent.data.db.EnrichCacheEntity
import com.infoagent.data.db.InfoAgentDatabase
import org.json.JSONObject
import java.time.LocalDate

/**
 * 外部信息编排：先让模型判断「该查什么」，再按用户自己的 key 去查，最后把结果拼成事实句。
 *
 * 一次生成的顺序：
 *   聊天素材 →（模型，便宜小请求）查询计划 → 天气/搜索（带缓存）→ 事实行 → 进主 prompt
 *
 * 三条硬规则：
 * 1) 查不到就不写（返回空 facts，主 prompt 里连【外部信息】这一节都不出现）——不能装联网；
 * 2) 缓存优先（同城市同日期 3 小时、同查询 12 小时），避免连点「补一份」烧额度；
 * 3) 规划请求走独立预算（USE_ENRICH，8 次/日），主简报额度不受影响。
 */
class ContextEnricher(
    private val db: InfoAgentDatabase,
    private val settings: EnrichConfigSource,
    private val budget: BudgetGuard,
    /** 天气取数（默认真实客户端；测试里可替换，避免打网络） */
    private val weatherFacts: (EnrichConfig, PlaceQuery) -> List<String> = { cfg, place ->
        QWeatherClient().facts(cfg, place)
    },
    /** 搜索取数（同上） */
    private val searchOne: (EnrichConfig, String) -> String? = { cfg, q -> SearchClient().search(cfg, q) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Enricher {

    companion object {
        /** 应用内的标准构造：设置来自 EnrichSettings，额度与 pipeline 共用当天计数 */
        fun forApp(db: InfoAgentDatabase, context: android.content.Context): ContextEnricher =
            ContextEnricher(
                db = db,
                settings = EnrichSettings(context.applicationContext),
                budget = BudgetGuard(db.agentDao()),
            )

        private const val TTL_PLAN = 40 * 60_000L
        private const val TTL_WEATHER = 3 * 3_600_000L
        private const val TTL_SEARCH = 12 * 3_600_000L
        private const val MAX_PLACES = 2
        private const val MAX_QUERIES = 2
        private const val MAX_LINES = 8
        private const val CACHE_PRUNE_AFTER = 7 * 24 * 3_600_000L
    }

    override suspend fun enrich(
        client: AgentClient?,
        config: ApiConfig?,
        wechatLines: String,
        bizDate: String,
    ): ExternalFacts {
        if (!settings.enabled()) return ExternalFacts(error = "外部信息未开启")
        val cfg = settings.load()
        if (!cfg.anyEnabled) return ExternalFacts(error = "没有配置天气/搜索的 key")

        val plan = planFor(client, config, wechatLines, bizDate, cfg)
        if (plan.isEmpty) return ExternalFacts(error = "本次没有需要查证的事")

        val lines = mutableListOf<String>()
        var error: String? = null
        var cached = false

        plan.places.take(MAX_PLACES).forEach { place ->
            if (!cfg.weatherEnabled) return@forEach
            val key = "w:${place.city}:${place.date}"
            val hit = cache(key, TTL_WEATHER)
            if (hit != null) {
                cached = true
                lines.addAll(hit)
            } else {
                val got = runCatching { weatherFacts(cfg, place) }.getOrElse { e ->
                    error = "天气查询失败：${e.message?.take(60)}"
                    emptyList()
                }
                if (got.isNotEmpty()) {
                    putCache(key, got)
                    lines.addAll(got)
                } else if (error == null) {
                    error = "没查到 ${place.city} 的天气（城市名或 key 不对？）"
                }
            }
        }

        plan.queries.take(MAX_QUERIES).forEach { q ->
            if (!cfg.searchEnabled) return@forEach
            val key = "s:$q"
            val hit = cache(key, TTL_SEARCH)
            if (hit != null) {
                cached = true
                lines.addAll(hit)
            } else {
                val got = runCatching { searchOne(cfg, q) }.getOrNull()
                if (!got.isNullOrBlank()) {
                    putCache(key, listOf(got))
                    lines.add(got)
                }
            }
        }

        val facts = lines.distinct().take(MAX_LINES)
        if (facts.isEmpty()) return ExternalFacts(error = error ?: "没有查到可用信息")
        runCatching { db.enrichDao().prune(clock() - CACHE_PRUNE_AFTER) }
        return ExternalFacts(lines = facts, ok = true, error = error, fromCache = cached)
    }

    /**
     * 查询计划：模型判断该查什么。
     * 失败了也不要紧——有常住城市就退化成「查一下本地天气」，总比不查强。
     */
    private suspend fun planFor(
        client: AgentClient?,
        config: ApiConfig?,
        wechatLines: String,
        bizDate: String,
        cfg: EnrichConfig,
    ): EnrichPlan {
        if (client == null || config == null) return fallbackPlan(cfg, bizDate)
        if (!budget.canSpend(BudgetGuard.USE_ENRICH)) return fallbackPlan(cfg, bizDate)

        val cacheKey = "plan:$bizDate:${wechatLines.take(200).hashCode()}"
        cache(cacheKey, TTL_PLAN)?.firstOrNull()?.let { cachedPlan ->
            decodePlan(cachedPlan)?.let { return it }
        }

        val prompt = buildString {
            appendLine("今天日期：$bizDate（周${"一二三四五六日"[LocalDate.parse(bizDate).dayOfWeek.value - 1]}）")
            if (cfg.homeCity.isNotBlank()) appendLine("用户常住城市：${cfg.homeCity}")
            appendLine("聊天素材：")
            appendLine(wechatLines.take(3000))
            appendLine()
            appendLine("现在输出 JSON。")
        }
        val result = runCatching {
            client.complete(
                config.copy(temperature = 0.0, maxTokens = 300),
                listOf(
                    ChatMessage("system", com.infoagent.agent.Prompts.ENRICH_PLAN_SYSTEM),
                    ChatMessage("user", prompt),
                ),
            )
        }.getOrNull() ?: return fallbackPlan(cfg, bizDate)

        runCatching { budget.record(BudgetGuard.USE_ENRICH, result.tokensIn, result.tokensOut) }
        val plan = decodePlan(result.content) ?: return fallbackPlan(cfg, bizDate)
        if (!plan.isEmpty) putCache(cacheKey, listOf(encodePlan(plan)))
        return if (plan.isEmpty) fallbackPlan(cfg, bizDate) else plan
    }

    private fun fallbackPlan(cfg: EnrichConfig, bizDate: String): EnrichPlan {
        if (cfg.homeCity.isBlank()) return EnrichPlan()
        val tomorrow = runCatching { LocalDate.parse(bizDate).plusDays(1).toString() }.getOrDefault(bizDate)
        return EnrichPlan(places = listOf(PlaceQuery(cfg.homeCity, tomorrow, "兜底：本地近期天气")))
    }

    internal fun decodePlan(raw: String): EnrichPlan? {
        val json = extractJson(raw) ?: return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val places = mutableListOf<PlaceQuery>()
        root.optJSONArray("places")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val city = o.optString("city").trim()
                val date = o.optString("date").trim()
                // 没有具体日期就不查天气（「最近天气」对决策没用，还会误导）
                if (city.isEmpty() || date.isEmpty()) continue
                places.add(PlaceQuery(city.take(20), date.take(10), o.optString("why").take(40)))
            }
        }
        val queries = mutableListOf<String>()
        root.optJSONArray("queries")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).trim().takeIf { it.isNotBlank() }?.let { queries.add(it.take(40)) }
            }
        }
        return EnrichPlan(places.take(MAX_PLACES), queries.take(MAX_QUERIES))
    }

    private fun encodePlan(plan: EnrichPlan): String = JSONObject()
        .put("places", org.json.JSONArray().apply {
            plan.places.forEach { p ->
                put(JSONObject().put("city", p.city).put("date", p.date).put("why", p.why))
            }
        })
        .put("queries", org.json.JSONArray(plan.queries))
        .toString()

    private fun extractJson(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }

    private suspend fun cache(key: String, ttl: Long): List<String>? {
        val hit = runCatching { db.enrichDao().get(key) }.getOrNull() ?: return null
        if (clock() - hit.ts > ttl) return null
        return hit.payload.split("\n").filter { it.isNotBlank() }
    }

    private suspend fun putCache(key: String, lines: List<String>) {
        runCatching {
            db.enrichDao().put(EnrichCacheEntity(key, lines.joinToString("\n"), clock()))
        }
    }
}