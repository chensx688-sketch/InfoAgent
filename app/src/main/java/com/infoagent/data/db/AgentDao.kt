package com.infoagent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AgentDao {

    // ---- auto_memo_job ----
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(job: AutoMemoJobEntity): Long

    @Query("SELECT * FROM auto_memo_job WHERE batch_hash = :hash LIMIT 1")
    suspend fun jobByHash(hash: String): AutoMemoJobEntity?

    @Query("SELECT * FROM auto_memo_job WHERE state IN ('PENDING','RUNNING','WAITING_RETRY') ORDER BY id ASC LIMIT 1")
    suspend fun oldestActiveJob(): AutoMemoJobEntity?

    @Query("UPDATE auto_memo_job SET state = :state, result_json = :resultJson, updated_at = :now WHERE id = :id")
    suspend fun updateJob(id: Long, state: String, resultJson: String?, now: Long)

    @Query("SELECT * FROM auto_memo_job WHERE state = :state LIMIT :limit")
    suspend fun jobsByState(state: String, limit: Int): List<AutoMemoJobEntity>

    // ---- agent_run ----
    @Insert
    suspend fun insertRun(run: AgentRunEntity): Long

    @Query("SELECT * FROM agent_run WHERE biz_date = :date AND window = :window ORDER BY id DESC LIMIT 1")
    suspend fun latestRun(date: String, window: String): AgentRunEntity?

    @Query("SELECT * FROM agent_run WHERE biz_date = :date AND brief_json IS NOT NULL ORDER BY id DESC LIMIT 1")
    suspend fun latestBriefAnyWindow(date: String): AgentRunEntity?

    @Query("SELECT * FROM agent_run WHERE cache_key = :cacheKey LIMIT 1")
    suspend fun runByCacheKey(cacheKey: String): AgentRunEntity?

    @Query("UPDATE agent_run SET stale = :stale, updated_at = :now WHERE id = :id")
    suspend fun setStale(id: Long, stale: Long, now: Long)

    @Query("SELECT * FROM agent_run WHERE biz_date = :date ORDER BY id DESC LIMIT 10")
    suspend fun runsOf(date: String): List<AgentRunEntity>

    // ---- followup_chat ----
    @Insert
    suspend fun insertFollowup(chat: FollowupChatEntity)

    @Query("SELECT * FROM followup_chat WHERE run_id = :runId ORDER BY id DESC LIMIT :limit")
    suspend fun followupsOf(runId: Long, limit: Int = 8): List<FollowupChatEntity>

    // ---- agent_trace ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrace(trace: AgentTraceEntity)

    @Query("SELECT * FROM agent_trace ORDER BY created_at DESC LIMIT :limit")
    suspend fun recentTraces(limit: Int): List<AgentTraceEntity>

    @Query("SELECT IFNULL(SUM(tokens_in+tokens_out),0) FROM agent_trace WHERE created_at BETWEEN :from AND :to")
    suspend fun tokensBetween(from: Long, to: Long): Long

    // ---- brief_feedback ----
    @Insert
    suspend fun insertBriefFeedback(feedback: BriefFeedbackEntity)

    @Query("SELECT * FROM brief_feedback WHERE run_id = :runId ORDER BY ts ASC")
    suspend fun feedbackOf(runId: Long): List<BriefFeedbackEntity>

    /** B3：最近反馈（真正的卡片级 👍👎），供记忆合并提示词使用 */
    @Query("SELECT * FROM brief_feedback ORDER BY ts DESC LIMIT :limit")
    suspend fun recentBriefFeedback(limit: Int): List<BriefFeedbackEntity>

    // ---- budget_counter（D18/D59，跨进程持久）----
    @Query("INSERT OR IGNORE INTO budget_counter(biz_date, use_case, count, tokens_in, tokens_out, cost) VALUES(:date, :useCase, 0, 0, 0, 0)")
    suspend fun ensureBudget(date: String, useCase: String)

    @Query(
        "UPDATE budget_counter SET count = count + 1, tokens_in = tokens_in + :tin, " +
            "tokens_out = tokens_out + :tout, cost = cost + :cost WHERE biz_date = :date AND use_case = :useCase"
    )
    suspend fun addBudgetUsage(date: String, useCase: String, tin: Long, tout: Long, cost: Double)

    @Query("SELECT * FROM budget_counter WHERE biz_date = :date AND use_case = :useCase LIMIT 1")
    suspend fun budgetOf(date: String, useCase: String): BudgetCounterEntity?

    @Query("SELECT IFNULL(SUM(cost),0) FROM budget_counter WHERE biz_date = :date")
    suspend fun dailyCost(date: String): Double

    // ---- provider_config / model_route ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProvider(provider: ProviderConfigEntity): Long

    @Query("SELECT * FROM provider_config WHERE is_active = 1 LIMIT 1")
    suspend fun activeProvider(): ProviderConfigEntity?

    @Query("SELECT * FROM provider_config ORDER BY id ASC")
    suspend fun providers(): List<ProviderConfigEntity>

    @Query("UPDATE provider_config SET retry_after_until = :until WHERE id = :id")
    suspend fun setRetryAfter(id: Long, until: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoute(route: ModelRouteEntity)

    @Query("SELECT * FROM model_route WHERE use_case = :useCase ORDER BY priority ASC")
    suspend fun routesOf(useCase: String): List<ModelRouteEntity>
}