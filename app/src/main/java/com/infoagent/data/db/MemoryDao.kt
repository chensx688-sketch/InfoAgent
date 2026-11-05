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

    @Query("SELECT * FROM memory_fact WHERE status = 'ACTIVE' ORDER BY confidence DESC, last_seen DESC LIMIT :limit")
    suspend fun topActiveFacts(limit: Int): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_fact ORDER BY updated_at DESC")
    fun observeFacts(): Flow<List<MemoryFactEntity>>

    @Query("UPDATE memory_fact SET confidence = :confidence, evidence_count = :evidence, feedback_sum = :feedback, last_seen = :now, updated_at = :now WHERE id = :id")
    suspend fun updateEvidence(id: Long, confidence: Long, evidence: Long, feedback: Long, now: Long)

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