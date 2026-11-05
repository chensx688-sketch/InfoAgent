package com.infoagent.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// §7 数据模型：消息域实体（@ColumnInfo 显式蛇形列名，与 docs/PLAN-v6.1.md §7 DDL 一致）

@Entity(
    tableName = "wechat_message",
    indices = [
        Index(value = ["peer_id", "ts"]),
        Index(value = ["ts"]),
        Index(value = ["flagged"]),
        Index(value = ["dedup_key"], unique = true),
    ],
)
data class WechatMessageEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "peer_name") val peerName: String = "",
    @ColumnInfo(name = "sender") val sender: String = "",
    @ColumnInfo(name = "direction") val direction: String, // IN/OUT/OTHER
    @ColumnInfo(name = "msg_type") val msgType: String,
    @ColumnInfo(name = "content") val content: String = "",
    @ColumnInfo(name = "ts") val ts: Long,
    @ColumnInfo(name = "first_ts") val firstTs: Long,
    @ColumnInfo(name = "last_ts") val lastTs: Long,
    @ColumnInfo(name = "source") val source: String = "notification",
    @ColumnInfo(name = "app_source") val appSource: String = "wechat", // wechat/qq/...
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
    @ColumnInfo(name = "recalled") val recalled: Long = 0,
    @ColumnInfo(name = "archived") val archived: Long = 0,
    @ColumnInfo(name = "flagged") val flagged: Long = 0,
)

@Entity(tableName = "conversation")
data class ConversationEntity(
    @PrimaryKey @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "name") val name: String = "",
    @ColumnInfo(name = "is_group") val isGroup: Long = 0,
    @ColumnInfo(name = "last_ts") val lastTs: Long? = null,
    @ColumnInfo(name = "priority") val priority: Long = 0, // watch_rule 缓存列（D50）
    @ColumnInfo(name = "muted") val muted: Long = 0,
)

@Entity(tableName = "watch_rule")
data class WatchRuleEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "rule_type") val ruleType: String, // PEER/KEYWORD
    @ColumnInfo(name = "value") val value: String,
    @ColumnInfo(name = "action") val action: String, // FOCUS/IGNORE
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "attach_item",
    foreignKeys = [
        ForeignKey(
            entity = WechatMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["msg_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["msg_id"])],
)
data class AttachItemEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "msg_id") val msgId: Long,
    @ColumnInfo(name = "type") val type: String, // URL/OCR/FILE
    @ColumnInfo(name = "content") val content: String? = null,
    @ColumnInfo(name = "ts") val ts: Long,
)

// FTS4 影子表不走 @Entity（Room 会建成普通表）；由 MessageFtsStore 用原生 SQL 建
// VIRTUAL TABLE 并在应用层同步写入（P0-1 应用层写入点），触发器方案废弃。