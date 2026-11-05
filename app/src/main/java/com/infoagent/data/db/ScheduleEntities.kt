package com.infoagent.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// §7 数据模型：调度与通知域实体（蛇形列名与 DDL 一致）

@Entity(tableName = "scheduled_run", indices = [Index(value = ["expected_at"])])
data class ScheduledRunEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "kind") val kind: String? = null,
    @ColumnInfo(name = "expected_at") val expectedAt: Long,
    @ColumnInfo(name = "fired_at") val firedAt: Long? = null,
    @ColumnInfo(name = "reason") val reason: String? = null,
    @ColumnInfo(name = "state") val state: String, // PENDING/FIRED/MISSED/RESENT/DEGRADED
    @ColumnInfo(name = "run_id") val runId: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "reminder", indices = [Index(value = ["fire_at"])])
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "kind") val kind: String, // MEMO/BRIEF_KEYTIME/REPEAT
    @ColumnInfo(name = "memo_id") val memoId: Long? = null,
    @ColumnInfo(name = "run_id") val runId: Long? = null,
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "fire_at") val fireAt: Long,
    @ColumnInfo(name = "fired") val fired: Long = 0,
    @ColumnInfo(name = "fired_at") val firedAt: Long? = null,
)

// 全局 Agent 串行锁（§10：执行中每 2 分钟续租；看门狗只清租约过期且心跳停的）
@Entity(tableName = "agent_lock")
data class AgentLockEntity(
    @PrimaryKey @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "owner") val owner: String? = null,
    @ColumnInfo(name = "heartbeat_at") val heartbeatAt: Long? = null,
    @ColumnInfo(name = "lease_until") val leaseUntil: Long? = null,
)

// 通知 ID 持久分配（§9 D08：hash 碰撞重新分配，同实体复用不堆叠）
@Entity(tableName = "notification_id_map")
data class NotificationIdMapEntity(
    @PrimaryKey @ColumnInfo(name = "entity_key") val entityKey: String,
    @ColumnInfo(name = "id") val id: Long,
)