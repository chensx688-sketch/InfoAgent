package com.infoagent.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// §7 数据模型：Agent 流水线域实体（蛇形列名与 DDL 一致）

@Entity(
    tableName = "auto_memo_job",
    indices = [Index(value = ["batch_hash"], unique = true)],
)
data class AutoMemoJobEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "batch_hash") val batchHash: String,
    @ColumnInfo(name = "contract_version") val contractVersion: String? = null,
    @ColumnInfo(name = "prompt_version") val promptVersion: String? = null,
    @ColumnInfo(name = "model") val model: String? = null,
    @ColumnInfo(name = "state") val state: String, // PENDING/RUNNING/DONE/FAILED/WAITING_RETRY
    @ColumnInfo(name = "result_json") val resultJson: String? = null,
    @ColumnInfo(name = "tokens") val tokens: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "agent_run",
    indices = [Index(value = ["biz_date", "window"])],
)
data class AgentRunEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "biz_date") val bizDate: String? = null,
    @ColumnInfo(name = "window") val window: String? = null, // MORNING/AFTERNOON/EVENING/MANUAL/FOLLOWUP
    @ColumnInfo(name = "input_hash") val inputHash: String? = null,
    @ColumnInfo(name = "cache_key") val cacheKey: String? = null,
    @ColumnInfo(name = "contract_version") val contractVersion: String? = null,
    @ColumnInfo(name = "prompt_version") val promptVersion: String? = null,
    @ColumnInfo(name = "model") val model: String? = null,
    @ColumnInfo(name = "temperature") val temperature: Double? = null,
    @ColumnInfo(name = "max_tokens") val maxTokens: Long? = null,
    @ColumnInfo(name = "brief_json") val briefJson: String? = null,
    @ColumnInfo(name = "stale") val stale: Long = 0,
    @ColumnInfo(name = "tokens_used") val tokensUsed: Long? = null,
    @ColumnInfo(name = "cost_est") val costEst: Double? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** v3：简报片段（一天一条时间轴，每条片段覆盖「上一条到现在」的增量） */
@Entity(
    tableName = "brief_slice",
    indices = [
        Index(value = ["biz_date", "seq"], unique = true),
        Index(value = ["state", "to_ts"]),
    ],
)
data class BriefSliceEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "biz_date") val bizDate: String,
    @ColumnInfo(name = "seq") val seq: Int,
    @ColumnInfo(name = "trigger") val trigger: String, // MORNING/AFTERNOON/EVENING/MANUAL
    @ColumnInfo(name = "from_ts") val fromTs: Long,
    @ColumnInfo(name = "to_ts") val toTs: Long,
    @ColumnInfo(name = "payload_json") val payloadJson: String? = null,
    @ColumnInfo(name = "new_count") val newCount: Int = 0,
    @ColumnInfo(name = "tokens_in") val tokensIn: Long = 0,
    @ColumnInfo(name = "tokens_out") val tokensOut: Long = 0,
    @ColumnInfo(name = "cost_est") val costEst: Double = 0.0,
    @ColumnInfo(name = "state") val state: String, // RUNNING/OK/FAILED/SKIPPED
    @ColumnInfo(name = "error") val error: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "read_at") val readAt: Long? = null,
)

@Entity(tableName = "followup_chat")
data class FollowupChatEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "run_id") val runId: Long? = null,
    @ColumnInfo(name = "role") val role: String, // user/assistant
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "ts") val ts: Long,
)

@Entity(tableName = "agent_trace", indices = [Index(value = ["created_at"])])
data class AgentTraceEntity(
    @PrimaryKey @ColumnInfo(name = "call_id") val callId: String,
    @ColumnInfo(name = "use_case") val useCase: String? = null,
    @ColumnInfo(name = "provider_id") val providerId: Long? = null,
    @ColumnInfo(name = "model") val model: String? = null,
    @ColumnInfo(name = "prompt_version") val promptVersion: String? = null,
    @ColumnInfo(name = "contract_version") val contractVersion: String? = null,
    @ColumnInfo(name = "tokens_in") val tokensIn: Long? = null,
    @ColumnInfo(name = "tokens_out") val tokensOut: Long? = null,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long? = null,
    @ColumnInfo(name = "parse_result") val parseResult: String? = null,
    @ColumnInfo(name = "fallback_reason") val fallbackReason: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "brief_feedback")
data class BriefFeedbackEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "run_id") val runId: Long? = null,
    @ColumnInfo(name = "section") val section: String? = null,
    @ColumnInfo(name = "item_index") val itemIndex: Long? = null,
    @ColumnInfo(name = "vote") val vote: Long? = null,
    @ColumnInfo(name = "reason") val reason: String? = null,
    @ColumnInfo(name = "ts") val ts: Long,
)

@Entity(tableName = "budget_counter", primaryKeys = ["biz_date", "use_case"])
data class BudgetCounterEntity(
    @ColumnInfo(name = "biz_date") val bizDate: String,
    @ColumnInfo(name = "use_case") val useCase: String,
    @ColumnInfo(name = "count") val count: Long = 0,
    @ColumnInfo(name = "tokens_in") val tokensIn: Long = 0,
    @ColumnInfo(name = "tokens_out") val tokensOut: Long = 0,
    @ColumnInfo(name = "cost") val cost: Double = 0.0,
)

@Entity(tableName = "provider_config")
data class ProviderConfigEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "base_url") val baseUrl: String,
    @ColumnInfo(name = "endpoint") val endpoint: String = "/chat/completions",
    @ColumnInfo(name = "model") val model: String? = null,
    @ColumnInfo(name = "tier") val tier: Long = 2,
    @ColumnInfo(name = "capabilities") val capabilities: String = "{}", // 能力位 json
    @ColumnInfo(name = "retry_after_until") val retryAfterUntil: Long = 0,
    @ColumnInfo(name = "is_active") val isActive: Long = 0,
)

@Entity(tableName = "model_route", primaryKeys = ["use_case", "priority"])
data class ModelRouteEntity(
    @ColumnInfo(name = "use_case") val useCase: String, // auto_memo/brief/memory/followup
    @ColumnInfo(name = "provider_id") val providerId: Long,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "tier") val tier: Long,
    @ColumnInfo(name = "priority") val priority: Long,
)