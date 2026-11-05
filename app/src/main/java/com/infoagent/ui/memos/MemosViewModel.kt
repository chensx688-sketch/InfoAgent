package com.infoagent.ui.memos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.MemoItemEntity
import com.infoagent.data.repo.MemoRepository
import com.infoagent.data.util.DayBoundary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class MemosViewModel(app: Application) : AndroidViewModel(app) {

    private val db = InfoAgentDatabase.get(app)
    private val repo = MemoRepository(db)

    private val _today = kotlinx.coroutines.flow.MutableStateFlow(DayBoundary.today())
    val today: String get() = _today.value

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val daily: Flow<List<MemoItemEntity>> = _today.flatMapLatest { repo.dailyFlow(it) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val temp: Flow<List<MemoItemEntity>> = _today.flatMapLatest { repo.tempFlow(it) }

    /** 阶段事件：跨天保留，不参与日清 */
    val stage: Flow<List<MemoItemEntity>> = repo.stageFlow()

    init {
        // 开屏惰性归档 + 监听回前台信号（跨 0 点/时区变化）
        viewModelScope.launch {
            repo.archiveBefore(DayBoundary.today())
            com.infoagent.data.util.DayTicker.tick.collect { refreshDay() }
        }
    }

    /** A9：日期变了就切流并归档，避免「App 过夜后仍是昨天列表」 */
    fun refreshDay() {
        val now = DayBoundary.today()
        if (now != _today.value) {
            _today.value = now
        }
        viewModelScope.launch { repo.archiveBefore(now) }
    }

    fun add(kind: String, text: String, time: Long?) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val id = repo.add(kind, text, time)
            if (time != null) {
                com.infoagent.remind.ReminderScheduler.schedule(getApplication(), id, text.trim(), time)
            }
        }
    }

    fun update(id: Long, text: String, time: Long?) {
        if (text.isBlank()) return
        viewModelScope.launch {
            repo.updateText(id, text, time)
            com.infoagent.remind.ReminderScheduler.cancel(getApplication(), id)
            if (time != null) {
                com.infoagent.remind.ReminderScheduler.schedule(getApplication(), id, text.trim(), time)
            }
        }
    }

    fun toggle(id: Long, done: Boolean) = viewModelScope.launch { repo.toggleDone(id, done) }

    fun delete(id: Long) = viewModelScope.launch {
        com.infoagent.remind.ReminderScheduler.cancel(getApplication(), id)
        db.scheduleDao().deleteRemindersOfMemo(id)
        repo.delete(id)
    }

    fun moveUp(id: Long) = viewModelScope.launch { repo.move(id, -1) }

    fun moveDown(id: Long) = viewModelScope.launch { repo.move(id, +1) }

    fun upgrade(id: Long) = viewModelScope.launch { repo.upgradeToDaily(id) }

    /** 临时事件 → 阶段事件（跨天保留） */
    fun promoteToStage(id: Long) = viewModelScope.launch { repo.upgradeToStage(id) }

    /** "HH:mm" / "H:mm" → 当日 epoch ms；空串返回 null，非法返回 null */
    fun parseTime(input: String, day: String = _today.value): Long? {
        val text = input.trim().replace('：', ':')
        if (text.isEmpty()) return null
        val parts = text.split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        val zone: ZoneId = ZoneId.systemDefault()
        val date = LocalDate.parse(day)
        return date.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
    }

    fun timeText(ts: Long?): String =
        if (ts == null) "" else Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
            .let { "%02d:%02d".format(it.hour, it.minute) }
}