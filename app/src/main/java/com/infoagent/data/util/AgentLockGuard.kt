package com.infoagent.data.util

import com.infoagent.data.db.InfoAgentDatabase

/**
 * B2：AI 任务互斥锁（agent_lock 表）——自动整理、简报、记忆合并共用一把锁，
 * 只有「无人持有」或「租约过期」的持有者才能进入，避免三条流水线并发抢配额/互相取消请求。
 */
object AgentLockGuard {

    const val NAME = "agent"
    private const val LEASE_MS = 10 * 60_000L

    /** 拿到锁执行 [block]，拿不到返回 null（调用方按「稍后重试」处理） */
    suspend fun <T> withLock(db: InfoAgentDatabase, owner: String, block: suspend () -> T): T? {
        val dao = db.scheduleDao()
        dao.ensureLockRow(NAME)
        val now = System.currentTimeMillis()
        if (dao.acquireLock(NAME, owner, now, now + LEASE_MS) <= 0) return null
        return try {
            block()
        } finally {
            runCatching { dao.releaseLock(NAME, owner) }
        }
    }
}