package com.infoagent.enrich

import com.infoagent.agent.AgentClient
import com.infoagent.agent.ApiConfig

/**
 * 「查证层」抽象：pipeline 只依赖这个接口，测试里可以塞假实现，
 * 真机上则是 [ContextEnricher]（天气 + 搜索 + 缓存）。
 */
interface Enricher {
    suspend fun enrich(
        client: AgentClient?,
        config: ApiConfig?,
        wechatLines: String,
        bizDate: String,
    ): ExternalFacts
}

/** 配置来源抽象：真机是 DataStore + Keystore，测试里给内存实现 */
interface EnrichConfigSource {
    suspend fun enabled(): Boolean
    suspend fun load(): EnrichConfig
}