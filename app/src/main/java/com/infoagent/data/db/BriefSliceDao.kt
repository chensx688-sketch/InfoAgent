package com.infoagent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** v3 简报片段 DAO：一天多条，按 seq 排序；from/to 首尾相接 */
@Dao
interface BriefSliceDao {

    @Insert
    suspend fun insert(slice: BriefSliceEntity): Long

    @Query("SELECT * FROM brief_slice WHERE biz_date = :bizDate ORDER BY seq ASC")
    suspend fun listByDay(bizDate: String): List<BriefSliceEntity>

    @Query("SELECT * FROM brief_slice WHERE biz_date = :bizDate ORDER BY seq ASC")
    fun observeByDay(bizDate: String): Flow<List<BriefSliceEntity>>

    @Query("SELECT * FROM brief_slice WHERE state = 'OK' AND biz_date = :bizDate ORDER BY seq DESC LIMIT 1")
    suspend fun latestOk(bizDate: String): BriefSliceEntity?

    /** 增量起点：上一条 OK 的 to_ts（没有则当天 00:00） */
    @Query("SELECT MAX(to_ts) FROM brief_slice WHERE biz_date = :bizDate AND state = 'OK'")
    suspend fun lastToTs(bizDate: String): Long?

    /** v4 增量水位：本条日期下已覆盖到的最大消息 id（0/null 表示还没有按 id 覆盖过） */
    @Query("SELECT MAX(covered_max_id) FROM brief_slice WHERE biz_date = :bizDate")
    suspend fun maxCoveredId(bizDate: String): Long?

    @Query("SELECT COUNT(*) FROM brief_slice WHERE biz_date = :bizDate")
    suspend fun countByDay(bizDate: String): Int

    @Query("SELECT COUNT(*) FROM brief_slice WHERE biz_date = :bizDate")
    suspend fun seqFor(bizDate: String): Int

    @Query("SELECT * FROM brief_slice WHERE id = :id")
    suspend fun byId(id: Long): BriefSliceEntity?

    @Query("UPDATE brief_slice SET read_at = :readAt WHERE id = :id")
    suspend fun markRead(id: Long, readAt: Long)

    @Query("UPDATE brief_slice SET state = :state, error = :error WHERE id = :id")
    suspend fun updateState(id: Long, state: String, error: String?)

    @Query("SELECT * FROM brief_slice WHERE biz_date = :bizDate ORDER BY seq DESC LIMIT 1")
    suspend fun latest(bizDate: String): BriefSliceEntity?
}