package com.infoagent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoDao {

    @Insert
    suspend fun insert(memo: MemoItemEntity): Long

    @Update
    suspend fun update(memo: MemoItemEntity)

    @Query("DELETE FROM memo_item WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE memo_item SET done = :done, updated_at = :now WHERE id = :id")
    suspend fun setDone(id: Long, done: Long, now: Long)

    @Query("UPDATE memo_item SET sort_order = :order, updated_at = :now WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Long, now: Long)

    @Query("SELECT * FROM memo_item WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): MemoItemEntity?

    @Query("UPDATE memo_item SET text = :text, assigned_time = :time, updated_at = :now WHERE id = :id")
    suspend fun updateTextAndTime(id: Long, text: String, time: Long?, now: Long)

    @Query("UPDATE memo_item SET kind = :kind, sort_order = :order, cycle_date = :cycleDate, updated_at = :now WHERE id = :id")
    suspend fun updateKindOrder(id: Long, kind: String, order: Long, cycleDate: String, now: Long)

    /** B8：超过保留期的临时事件（按 cycle_date 判定） */
    @Query("SELECT * FROM memo_item WHERE kind = 'TEMP' AND cycle_date < :cutoff ORDER BY cycle_date ASC")
    suspend fun staleTempBefore(cutoff: String): List<MemoItemEntity>

    @Query("SELECT * FROM memo_item WHERE cycle_date = :date AND kind = :kind ORDER BY sort_order ASC")
    fun observe(date: String, kind: String): Flow<List<MemoItemEntity>>

    @Query("SELECT * FROM memo_item WHERE cycle_date = :date AND kind = :kind ORDER BY sort_order ASC")
    suspend fun list(date: String, kind: String): List<MemoItemEntity>

    /** 阶段事件：跟随每日必做展示但不参与日清（按 kind 查全部，不看 cycle_date） */
    @Query("SELECT * FROM memo_item WHERE kind = :kind ORDER BY sort_order ASC")
    fun observeKind(kind: String): Flow<List<MemoItemEntity>>

    @Query("SELECT * FROM memo_item WHERE kind = :kind ORDER BY sort_order ASC")
    suspend fun listKind(kind: String): List<MemoItemEntity>

    @Query("SELECT * FROM memo_item WHERE kind = 'DAILY' AND cycle_date < :today ORDER BY cycle_date ASC")
    suspend fun dailyBefore(today: String): List<MemoItemEntity>

    /** 顺延：把昨天的每日必做移到今天并重置勾选（不是删除） */
    @Query("UPDATE memo_item SET cycle_date = :cycleDate, done = 0, updated_at = :now WHERE id = :id")
    suspend fun rolloverDaily(id: Long, cycleDate: String, now: Long)

    @Query("SELECT * FROM memo_item WHERE kind = 'DAILY' AND cycle_date < :today AND done = 0")
    suspend fun unarchivedDailyBefore(today: String): List<MemoItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: MemoSourceEntity)

    @Query("SELECT * FROM memo_source WHERE memo_id = :memoId ORDER BY msg_id ASC")
    suspend fun sourcesOfMemo(memoId: Long): List<MemoSourceEntity>

    @Query("DELETE FROM memo_source WHERE memo_id = :memoId AND msg_id = :msgId")
    suspend fun deleteSource(memoId: Long, msgId: Long)

    @Insert
    suspend fun insertArchive(archive: MemoArchiveEntity)

    @Query("SELECT * FROM memo_archive WHERE archived_date = :date ORDER BY id ASC")
    fun observeArchive(date: String): Flow<List<MemoArchiveEntity>>

    @Query("SELECT * FROM memo_archive WHERE archived_date = :date ORDER BY id ASC")
    suspend fun archiveOf(date: String): List<MemoArchiveEntity>

    @Insert
    suspend fun insertRepeatRule(rule: RepeatRuleEntity): Long

    @Query("SELECT * FROM repeat_rule WHERE active = 1")
    fun observeRepeatRules(): Flow<List<RepeatRuleEntity>>
}