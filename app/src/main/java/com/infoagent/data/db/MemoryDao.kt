package com.infoagent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Insert
    suspend fun insertFact(fact: MemoryFactEntity): Long

    @Query("SELECT * FROM memory_fact WHERE subject = :subject LIMIT 1")
    suspend fun factBySubject(subject: String): MemoryFactEntity?

    /** 同 subject 当前仍然有效的那条（valid_to 为空） */
    @Query("SELECT * FROM memory_fact WHERE subject = :subject AND valid_to IS NULL AND status != 'DISMISSED' ORDER BY created_at DESC LIMIT 1")
    suspend fun activeBySubject(subject: String): MemoryFactEntity?

    /** 同 subject 最近的一条（含已失效/已忽略），用于「要不要复活」的判断 */
    @Query("SELECT * FROM memory_fact WHERE subject = :subject ORDER BY created_at DESC, id DESC LIMIT 1")
    suspend fun latestBySubject(subject: String): MemoryFactEntity?

    /** 简报注入只取仍然有效的；失效的留着查历史，不再进 prompt */
    @Query("SELECT * FROM memory_fact WHERE status = 'ACTIVE' AND valid_to IS NULL ORDER BY confidence DESC, last_seen DESC LIMIT :limit")
    suspend fun topActiveFacts(limit: Int): List<MemoryFactEntity>

    /** 记忆页：当前有效 */
    @Query("SELECT * FROM memory_fact WHERE status = 'ACTIVE' AND valid_to IS NULL ORDER BY confidence DESC, last_seen DESC LIMIT :limit")
    suspend fun activeFacts(limit: Int): List<MemoryFactEntity>

    /** 记忆页：已失效的旧值（折叠展示，可恢复） */
    @Query("SELECT * FROM memory_fact WHERE valid_to IS NOT NULL ORDER BY valid_to DESC LIMIT :limit")
    suspend fun expiredFacts(limit: Int): List<MemoryFactEntity>

    /** 标记失效（不删行）：事实变了，或者用户点「这条过期了」 */
    @Query("UPDATE memory_fact SET valid_to = :now, updated_at = :now WHERE id = :id")
    suspend fun supersede(id: Long, now: Long)

    /** 恢复一条被标记失效的记忆（用户点错了、或者旧值其实还有效） */
    @Query("UPDATE memory_fact SET valid_to = NULL, updated_at = :now WHERE id = :id")
    suspend fun restore(id: Long, now: Long)

    @Query("SELECT * FROM memory_fact ORDER BY updated_at DESC")
    fun observeFacts(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_fact WHERE id = :id LIMIT 1")
    suspend fun factById(id: Long): MemoryFactEntity?

    /** 全量（含失效），测试与导出用 */
    @Query("SELECT * FROM memory_fact ORDER BY id")
    suspend fun allFacts(): List<MemoryFactEntity>

    @Query("UPDATE memory_fact SET confidence = :confidence, evidence_count = :evidence, feedback_sum = :feedback, last_seen = :now, updated_at = :now WHERE id = :id")
    suspend fun updateEvidence(id: Long, confidence: Long, evidence: Long, feedback: Long, now: Long)

    /** 累加证据时把新的来源消息 id 并进 source_refs（拼好的串由调用方给） */
    @Query("UPDATE memory_fact SET source_refs = :refs, updated_at = :now WHERE id = :id")
    suspend fun addRefs(id: Long, refs: String, now: Long)

    @Query("UPDATE memory_fact SET confidence = :confidence, feedback_sum = :feedback, status = :status, updated_at = :now WHERE id = :id")
    suspend fun updateConfidence(id: Long, confidence: Long, feedback: Long, status: String, now: Long)

    @Query("UPDATE memory_fact SET fact_text = :text, status = 'EDITED', updated_at = :now WHERE id = :id")
    suspend fun editFact(id: Long, text: String, now: Long)

    @Query("UPDATE memory_fact SET status = 'DISMISSED', updated_at = :now WHERE id = :id")
    suspend fun dismissFact(id: Long, now: Long)

    // ---- streak ----
    @Query("SELECT * FROM streak WHERE date = :date LIMIT 1")
    suspend fun streakOf(date: String): StreakEntity?

    // ---- recycle（D40，payload={table,row_json,schema_version}）----
    @Insert
    suspend fun insertRecycle(recycle: RecycleEntity): Long

    @Query("DELETE FROM recycle WHERE expire_at <= :now")
    suspend fun purgeExpiredRecycle(now: Long)

    @Query("SELECT * FROM recycle WHERE expire_at > :now ORDER BY deleted_at DESC LIMIT :limit")
    suspend fun recycleList(now: Long, limit: Int): List<RecycleEntity>

    @Query("SELECT * FROM recycle WHERE id = :id LIMIT 1")
    suspend fun recycle(id: Long): RecycleEntity?

    @Query("DELETE FROM recycle WHERE id = :id")
    suspend fun deleteRecycle(id: Long)

    @Query("DELETE FROM recycle")
    suspend fun clearRecycle()

    // ---- message_archive ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArchive(archive: MessageArchiveEntity)

    @Query("SELECT * FROM message_archive WHERE day = :day AND conv = :conv LIMIT 1")
    suspend fun archive(day: String, conv: String): MessageArchiveEntity?

    @Query("SELECT * FROM message_archive WHERE day BETWEEN :from AND :to ORDER BY day DESC")
    suspend fun archivesBetween(from: String, to: String): List<MessageArchiveEntity>

    @Query("DELETE FROM message_archive WHERE day BETWEEN :from AND :to")
    suspend fun clearArchives(from: String, to: String)

    @Query("DELETE FROM message_archive")
    suspend fun clearAllArchives()
}