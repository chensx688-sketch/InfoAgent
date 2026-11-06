package com.infoagent.agent

import com.infoagent.data.db.AgentDao
import com.infoagent.data.util.DayBoundary

/**
 * 日预算闸门（§8.6）：自动整理 8 / 简报 3 / 记忆 2 / 追问 8k token；手动每日 10 次。
 * 计数落 budget_counter（跨进程持久，D18/D59）。
 */
class BudgetGuard(
    private val dao: AgentDao,
    private val todayProvider: () -> String = { DayBoundary.today() },
) {

    companion object {
        const val USE_AUTO_MEMO = "auto_memo"
        const val USE_BRIEF = "brief"
        const val USE_MEMORY = "memory"
        const val USE_FOLLOWUP = "followup"
        const val USE_MANUAL = "manual"

        /** 外部信息「查什么」的规划请求：独立额度，不与简报额度抢 */
        const val USE_ENRICH = "enrich"

        val DAILY_LIMITS = mapOf(
            USE_AUTO_MEMO to 8L,
            USE_BRIEF to 3L,
            USE_MEMORY to 2L,
            USE_ENRICH to 8L,
        )
        const val MANUAL_DAILY_LIMIT = 10L
        const val FOLLOWUP_TOKEN_LIMIT = 8_000L
    }

    private suspend fun counter(useCase: String) =
        dao.budgetOf(todayProvider(), useCase) ?: dao.let {
            it.ensureBudget(todayProvider(), useCase)
            it.budgetOf(todayProvider(), useCase)
        }

    suspend fun remaining(useCase: String, manual: Boolean = false): Long {
        val c = counter(useCase) ?: return if (manual) MANUAL_DAILY_LIMIT else (DAILY_LIMITS[useCase] ?: 0L)
        return if (manual) {
            (MANUAL_DAILY_LIMIT - c.count).coerceAtLeast(0)
        } else {
            ((DAILY_LIMITS[useCase] ?: 0L) - c.count).coerceAtLeast(0)
        }
    }

    suspend fun canSpend(useCase: String, manual: Boolean = false): Boolean {
        if (useCase == USE_FOLLOWUP) {
            val c = counter(USE_FOLLOWUP)
            return (c?.tokensIn ?: 0L) + (c?.tokensOut ?: 0L) < FOLLOWUP_TOKEN_LIMIT
        }
        return remaining(useCase, manual) > 0
    }

    suspend fun record(useCase: String, tokensIn: Long, tokensOut: Long, cost: Double = 0.0) {
        val date = todayProvider()
        dao.ensureBudget(date, useCase)
        dao.addBudgetUsage(date, useCase, tokensIn, tokensOut, cost)
    }

    suspend fun dailyCost(): Double = dao.dailyCost(todayProvider())
}