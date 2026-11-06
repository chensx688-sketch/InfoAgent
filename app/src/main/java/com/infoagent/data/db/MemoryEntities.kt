package com.infoagent.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// §7 数据模型：记忆/留存域实体（蛇形列名与 DDL 一致）

@Entity(
    tableName = "memory_fact",
    // v6：按 subject 找「仍然有效的那条」是最热的查询，索引跟迁移里建的一致
    indices = [Index(value = ["subject", "valid_to"], name = "index_memory_fact_subject_valid_to")],
)
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "category") val category: String, // HABIT/PREFERENCE/PATTERN/RELATION
    @ColumnInfo(name = "subject") val subject: String? = null,
    @ColumnInfo(name = "fact_text") val factText: String? = null,
    @ColumnInfo(name = "confidence") val confidence: Long,
    @ColumnInfo(name = "evidence_count") val evidenceCount: Long,
    /** A7：反馈累加值（👍+2 / 👎−5），持久化后合并时才不会把用户反馈抹掉 */
    @ColumnInfo(name = "feedback_sum") val feedbackSum: Long = 0,
    @ColumnInfo(name = "last_seen") val lastSeen: Long? = null,
    @ColumnInfo(name = "source_refs") val sourceRefs: String? = null,
    @ColumnInfo(name = "period_hint") val periodHint: String? = null,

    /** 这条事实从什么时候开始为真（毫秒）。0 = 迁移前的老数据，按 created_at 兜底 */
    @ColumnInfo(name = "valid_from") val validFrom: Long = 0L,

    /**
     * 什么时候失效（毫秒）；null = 现在仍然有效。
     *
     * 为什么只标记不删除：事实变化时新旧两条都可能是真的（"以前住城西，现在住滨江"），
     * 直接覆盖会把历史弄丢；判断挪到取用的时候做，只挑 validTo 为空的那条。
     */
    @ColumnInfo(name = "valid_to") val validTo: Long? = null,
    @ColumnInfo(name = "status") val status: String, // ACTIVE/DISMISSED/EDITED
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "streak")
data class StreakEntity(
    @PrimaryKey @ColumnInfo(name = "date") val date: String,
    @ColumnInfo(name = "plan_total") val planTotal: Long = 0,
    @ColumnInfo(name = "done_total") val doneTotal: Long = 0,
    @ColumnInfo(name = "temp_total") val tempTotal: Long = 0,
    @ColumnInfo(name = "done_rate") val doneRate: Double = 0.0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
)

// payload = {"table": "...", "row_json": {...}, "schema_version": 1}；恢复按主键 upsert（D40）
@Entity(tableName = "recycle")
data class RecycleEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "kind") val kind: String? = null,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long,
    @ColumnInfo(name = "expire_at") val expireAt: Long,
)

@Entity(tableName = "message_archive", primaryKeys = ["day", "conv"])
data class MessageArchiveEntity(
    @ColumnInfo(name = "day") val day: String,
    @ColumnInfo(name = "conv") val conv: String,
    @ColumnInfo(name = "data_blob") val dataBlob: ByteArray? = null,
    @ColumnInfo(name = "sha") val sha: String? = null,
    @ColumnInfo(name = "count") val count: Long = 0,
)