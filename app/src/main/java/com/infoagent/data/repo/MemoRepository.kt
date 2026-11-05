package com.infoagent.data.repo

import androidx.room.withTransaction
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.MemoArchiveEntity
import com.infoagent.data.db.MemoItemEntity
import com.infoagent.data.util.DayBoundary
import kotlinx.coroutines.flow.Flow

/**
 * 备忘仓库（§5 备忘页 / FR-04/05）：每日必做(DAILY) + 阶段事件(STAGE) + 临时事件(TEMP)，
 * 单列 sort_order 事务内整体重写。
 * 日界处理（用户 2026-11-05 反馈修正）：
 * - DAILY 不再「隔天删除」：留档一份到 memo_archive，原条**顺延到今天并重置勾选**；
 * - STAGE 是阶段任务，不参与日清，只能由用户编辑/删除；
 * - TEMP 超过 30 天归档清理。
 */
class MemoRepository(private val db: InfoAgentDatabase) {

    private val dao = db.memoDao()

    fun dailyFlow(date: String = DayBoundary.today()): Flow<List<MemoItemEntity>> = dao.observe(date, "DAILY")

    fun tempFlow(date: String = DayBoundary.today()): Flow<List<MemoItemEntity>> = dao.observe(date, "TEMP")

    fun stageFlow(): Flow<List<MemoItemEntity>> = dao.observeKind("STAGE")

    suspend fun daily(date: String = DayBoundary.today()): List<MemoItemEntity> = dao.list(date, "DAILY")

    suspend fun temp(date: String = DayBoundary.today()): List<MemoItemEntity> = dao.list(date, "TEMP")

    suspend fun stage(): List<MemoItemEntity> = dao.listKind("STAGE")

    suspend fun add(kind: String, text: String, assignedTime: Long? = null): Long {
        val today = DayBoundary.today()
        val now = System.currentTimeMillis()
        val order = (if (kind == "STAGE") dao.listKind(kind) else dao.list(today, kind))
            .maxOfOrNull { it.sortOrder }?.plus(1L) ?: 1L
        return dao.insert(
            MemoItemEntity(
                kind = kind,
                text = text.trim(),
                sortOrder = order,
                cycleDate = today,
                assignedTime = assignedTime,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun updateText(id: Long, text: String, assignedTime: Long?) =
        dao.updateTextAndTime(id, text.trim(), assignedTime, System.currentTimeMillis())

    suspend fun toggleDone(id: Long, done: Boolean) =
        dao.setDone(id, if (done) 1 else 0, System.currentTimeMillis())

    /** 删除备忘：先入回收站（B1），再物理删除 */
    suspend fun delete(id: Long) = db.withTransaction {
        val item = dao.byId(id) ?: return@withTransaction
        runCatching {
            com.infoagent.data.export.RecycleController(db).softDelete(
                "memo_item",
                org.json.JSONObject()
                    .put("id", item.id)
                    .put("kind", item.kind)
                    .put("text", item.text)
                    .put("sort_order", item.sortOrder)
                    .put("done", item.done)
                    .put("cycle_date", item.cycleDate)
                    .put("assigned_time", item.assignedTime)
                    .put("origin", item.origin)
                    .put("source_app", item.sourceApp)
                    .put("source_conv", item.sourceConv)
                    .put("source_msg_id", item.sourceMsgId)
                    .put("repeat_rule_id", item.repeatRuleId)
                    .put("calendar_event_id", item.calendarEventId)
                    .put("created_at", item.createdAt)
                    .put("updated_at", item.updatedAt),
            )
        }
        dao.delete(id)
    }

    /** 同 kind 内上/下移：事务内整体重排 sort_order */
    suspend fun move(id: Long, delta: Int = -1) = db.withTransaction {
        val item = dao.byId(id) ?: return@withTransaction
        val siblings = (if (item.kind == "STAGE") dao.listKind(item.kind) else dao.list(item.cycleDate, item.kind))
            .sortedBy { it.sortOrder }.toMutableList()
        val index = siblings.indexOfFirst { it.id == id }
        if (index < 0) return@withTransaction
        val target = (index + delta).coerceIn(0, siblings.size - 1)
        if (target == index) return@withTransaction
        val moved = siblings.removeAt(index)
        siblings.add(target, moved)
        val now = System.currentTimeMillis()
        siblings.forEachIndexed { i, it -> dao.setSortOrder(it.id, i + 1L, now) }
    }

    /** 临时事件升级为每日必做（追加到当日 DAILY 末尾） */
    suspend fun upgradeToDaily(id: Long) = changeKind(id, "DAILY")

    /** 升级为阶段事件（跨天保留，不参与日清） */
    suspend fun upgradeToStage(id: Long) = changeKind(id, "STAGE")

    suspend fun changeKind(id: Long, kind: String) = db.withTransaction {
        val item = dao.byId(id) ?: return@withTransaction
        if (item.kind == kind) return@withTransaction
        val today = DayBoundary.today()
        val siblings = if (kind == "STAGE") dao.listKind(kind) else dao.list(today, kind)
        val order = (siblings.maxOfOrNull { it.sortOrder } ?: 0L) + 1L
        // B9：同步迁移 cycle_date，否则昨天建的条目升级后当天不显示
        dao.updateKindOrder(item.id, kind, order, today, System.currentTimeMillis())
    }

    /**
     * 归档：所有 cycle_date < today 的 DAILY 复制进 memo_archive 后删除（§10 日界 00:00）。
     * 返回归档条数。
     */
    /** 日界：DAILY 顺延（留档 + 重置勾选），TEMP 超 30 天归档；STAGE 永不清 */
    suspend fun archiveBefore(today: String = DayBoundary.today()): Int = db.withTransaction {
        var archived = 0
        val now = System.currentTimeMillis()

        dao.dailyBefore(today).forEach { m ->
            // 历史留档（按原 cycle_date 记一天完成情况）
            dao.insertArchive(
                MemoArchiveEntity(
                    memoId = m.id,
                    archivedDate = m.cycleDate,
                    text = m.text,
                    done = m.done,
                    origin = m.origin,
                    sourceConv = m.sourceConv,
                ),
            )
            // 关键：不删除，顺延到今天并重置勾选
            dao.rolloverDaily(m.id, today, now)
            archived++
        }

        // B8：临时事件超过 30 天也进归档并删除，避免永久孤儿数据
        val cutoff = java.time.LocalDate.parse(today).minusDays(30).toString()
        dao.staleTempBefore(cutoff).forEach { m ->
            dao.insertArchive(
                MemoArchiveEntity(
                    memoId = m.id,
                    archivedDate = m.cycleDate,
                    text = m.text,
                    done = m.done,
                    origin = m.origin,
                    sourceConv = m.sourceConv,
                ),
            )
            dao.delete(m.id)
            archived++
        }
        archived
    }

    suspend fun archiveOf(date: String): List<MemoArchiveEntity> = dao.archiveOf(date)

    suspend fun doneStats(date: String = DayBoundary.today()): Pair<Int, Int> {
        val daily = dao.list(date, "DAILY")
        return daily.count { it.done == 1L } to daily.size
    }
}