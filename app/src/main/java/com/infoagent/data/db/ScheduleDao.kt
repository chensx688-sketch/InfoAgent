package com.infoagent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScheduleDao {

    @Insert
    suspend fun insertScheduledRun(run: ScheduledRunEntity): Long

    @Query("SELECT * FROM scheduled_run WHERE kind = :kind AND state = 'PENDING' AND expected_at <= :now ORDER BY expected_at DESC LIMIT 1")
    suspend fun latestUnfired(kind: String, now: Long = System.currentTimeMillis()): ScheduledRunEntity?

    @Query("SELECT * FROM scheduled_run WHERE state = 'PENDING' AND expected_at <= :now")
    suspend fun pendingOverdue(now: Long): List<ScheduledRunEntity>

    @Query("UPDATE scheduled_run SET state = :state, fired_at = :firedAt, reason = :reason, run_id = :runId, updated_at = :now WHERE id = :id")
    suspend fun updateRun(id: Long, state: String, firedAt: Long?, reason: String?, runId: Long?, now: Long)

    // ---- reminder ----
    @Insert
    suspend fun insertReminder(reminder: ReminderEntity): Long

    @Query("SELECT * FROM reminder WHERE fired = 0 AND fire_at > :now ORDER BY fire_at ASC")
    suspend fun pendingReminders(now: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE memo_id = :memoId LIMIT 1")
    suspend fun reminderOfMemo(memoId: Long): ReminderEntity?

    @Query("DELETE FROM reminder WHERE memo_id = :memoId")
    suspend fun deleteRemindersOfMemo(memoId: Long)

    @Query("SELECT * FROM reminder WHERE fired = 0 AND fire_at <= :now ORDER BY fire_at ASC")
    suspend fun dueReminders(now: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE fired = 0 AND fire_at > :now ORDER BY fire_at ASC LIMIT 1")
    suspend fun nextReminder(now: Long): ReminderEntity?

    @Query("UPDATE reminder SET fired = 1, fired_at = :now WHERE id = :id")
    suspend fun markFired(id: Long, now: Long)

    @Query("DELETE FROM reminder WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    @Query("SELECT * FROM reminder WHERE id = :id LIMIT 1")
    suspend fun reminder(id: Long): ReminderEntity?

    // ---- agent_lock（§10.4：执行中每 2 分钟续租；看门狗只清租约过期且心跳停的）----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLock(lock: AgentLockEntity)

    @Query("SELECT * FROM agent_lock WHERE name = :name LIMIT 1")
    suspend fun lock(name: String): AgentLockEntity?

    @Query("UPDATE agent_lock SET heartbeat_at = :now, lease_until = :lease WHERE name = :name AND owner = :owner")
    suspend fun heartbeatLock(name: String, owner: String, now: Long, lease: Long)

    /** B2：只有「无人持有」或「租约已过期」才能拿到锁；返回受影响行数 */
    @Query(
        "UPDATE agent_lock SET owner = :owner, heartbeat_at = :now, lease_until = :lease " +
            "WHERE name = :name AND (owner IS NULL OR lease_until IS NULL OR lease_until < :now)",
    )
    suspend fun acquireLock(name: String, owner: String, now: Long, lease: Long): Int

    @Query("INSERT OR IGNORE INTO agent_lock(name) VALUES(:name)")
    suspend fun ensureLockRow(name: String)

    @Query("UPDATE agent_lock SET owner = NULL, heartbeat_at = NULL, lease_until = NULL WHERE name = :name AND owner = :owner")
    suspend fun releaseLock(name: String, owner: String)

    @Query("SELECT * FROM agent_lock WHERE lease_until < :now AND owner IS NOT NULL")
    suspend fun expiredLocks(now: Long): List<AgentLockEntity>

    // ---- notification_id_map（§9 D08 持久 ID 分配）----
    @Query("INSERT OR REPLACE INTO notification_id_map(entity_key, id) VALUES(:entityKey, :id)")
    suspend fun putNotificationId(entityKey: String, id: Long)

    @Query("SELECT id FROM notification_id_map WHERE entity_key = :key LIMIT 1")
    suspend fun notificationId(key: String): Long?

    @Query("SELECT IFNULL(MAX(id), 0) FROM notification_id_map")
    suspend fun maxNotificationId(): Long
}