package com.infoagent.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// §7 数据模型：备忘域实体（蛇形列名与 DDL 一致）

@Entity(
    tableName = "memo_item",
    indices = [Index(value = ["cycle_date", "kind"])],
)
data class MemoItemEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "kind") val kind: String, // DAILY/TEMP
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Long,
    @ColumnInfo(name = "done") val done: Long = 0,
    @ColumnInfo(name = "cycle_date") val cycleDate: String, // 'yyyy-MM-dd' 本地日
    @ColumnInfo(name = "assigned_time") val assignedTime: Long? = null,
    @ColumnInfo(name = "origin") val origin: String = "MANUAL", // MANUAL/AI/SHARE
    @ColumnInfo(name = "source_app") val sourceApp: String? = null,
    @ColumnInfo(name = "source_conv") val sourceConv: String? = null,
    @ColumnInfo(name = "source_msg_id") val sourceMsgId: Long? = null, // 首来源缓存，事实源=memo_source（P1-10）
    @ColumnInfo(name = "repeat_rule_id") val repeatRuleId: Long? = null,
    @ColumnInfo(name = "calendar_event_id") val calendarEventId: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

// AI 备忘多来源回跳（D17，唯一事实源）
@Entity(tableName = "memo_source", primaryKeys = ["memo_id", "msg_id"])
data class MemoSourceEntity(
    @ColumnInfo(name = "memo_id") val memoId: Long,
    @ColumnInfo(name = "msg_id") val msgId: Long,
)

@Entity(
    tableName = "memo_archive",
    indices = [Index(value = ["archived_date"])],
)
data class MemoArchiveEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "memo_id") val memoId: Long? = null,
    @ColumnInfo(name = "archived_date") val archivedDate: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "done") val done: Long = 0,
    @ColumnInfo(name = "origin") val origin: String? = null,
    @ColumnInfo(name = "source_conv") val sourceConv: String? = null,
)

@Entity(tableName = "repeat_rule")
data class RepeatRuleEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "memo_text") val memoText: String,
    @ColumnInfo(name = "freq") val freq: String, // DAILY/WEEKLY/MONTHLY
    @ColumnInfo(name = "weekdays") val weekdays: String? = null,
    @ColumnInfo(name = "active") val active: Long = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)