package com.infoagent.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 外部信息缓存（v5）。
 *
 * 为什么要落库：和风天气与搜索服务都是**按次计额**的用户自费 key，
 * 而「现在补一份」可能在 5 分钟内被点三次；同样的城市+日期查一次就够了。
 */
@Entity(tableName = "enrich_cache")
data class EnrichCacheEntity(
    @PrimaryKey @ColumnInfo(name = "cache_key") val key: String,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "ts") val ts: Long,
)

@Dao
interface EnrichDao {

    @Query("SELECT * FROM enrich_cache WHERE cache_key = :key LIMIT 1")
    suspend fun get(key: String): EnrichCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: EnrichCacheEntity)

    @Query("DELETE FROM enrich_cache WHERE ts < :before")
    suspend fun prune(before: Long)

    @Query("SELECT COUNT(*) FROM enrich_cache")
    suspend fun count(): Int
}