package com.infoagent.ui.messages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infoagent.capture.ImportParser
import com.infoagent.capture.NotificationAccess
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.db.MemoItemEntity
import com.infoagent.data.db.MemoSourceEntity
import com.infoagent.data.db.WechatMessageEntity
import com.infoagent.data.db.MessageDao
import com.infoagent.data.repo.MessageRepository
import com.infoagent.data.util.DayBoundary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationUi(
    val peerId: String,
    val name: String,
    val isGroup: Boolean,
    val lastTs: Long,
    val count: Int = 0,
)

data class CaptureUiState(
    val permission: Boolean = false,
    val connectedAt: Long = 0L,
    val lastIngestAt: Long = 0L,
    val lastOutcome: String = "",
    val todayCount: Long = 0L,
)

class MessagesViewModel(app: Application) : AndroidViewModel(app) {

    private val db = InfoAgentDatabase.get(app)
    private val repo = MessageRepository(db)

    private val _capture = kotlinx.coroutines.flow.MutableStateFlow(CaptureUiState())
    val capture: kotlinx.coroutines.flow.StateFlow<CaptureUiState> = _capture

    fun refreshCaptureState() = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val prefs = com.infoagent.capture.NotifyCaptureService.state(ctx)
        val startOfDay = com.infoagent.data.util.DayBoundary.dayRange(
            com.infoagent.data.util.DayBoundary.today(),
        )[0]
        _capture.value = CaptureUiState(
            permission = NotificationAccess.isGranted(ctx),
            connectedAt = prefs.getLong(com.infoagent.capture.NotifyCaptureService.KEY_CONNECTED_AT, 0L),
            lastIngestAt = prefs.getLong(com.infoagent.capture.NotifyCaptureService.KEY_LAST_INGEST_AT, 0L),
            lastOutcome = prefs.getString(com.infoagent.capture.NotifyCaptureService.KEY_LAST_OUTCOME, "").orEmpty(),
            todayCount = db.messageDao().countSince(startOfDay),
        )
    }

    fun rebindCapture() {
        com.infoagent.capture.NotifyCaptureService.requestRebind(getApplication())
        refreshCaptureState()
    }

    private val _convPoints = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val convPoints: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = _convPoints

    /** 会话级「今日要点」：取当日最新一份简报，按会话映射 */
    fun refreshBriefPoints() = viewModelScope.launch {
        runCatching {
            val run = db.agentDao().latestBriefAnyWindow(com.infoagent.data.util.DayBoundary.today())
                ?: return@launch
            val json = run.briefJson ?: return@launch
            val brief = (com.infoagent.agent.SafeParser.parseBrief(json) as? com.infoagent.agent.SafeParser.Result.Ok)?.value
                ?: return@launch
            _convPoints.value = brief.wechatPoints.associate { it.conv to it.point }
        }
    }

    val conversations: Flow<List<ConversationUi>> = kotlinx.coroutines.flow.combine(
        db.messageDao().observeConversations(),
        // C11：条数用 SQL 聚合（会随入库自动刷新），避免「只统计最近 400 条」失真
        db.messageDao().observeCountsByPeer(),
    ) { list, peerCounts ->
        val counts = peerCounts.associate { it.peerId to it.cnt }
        list.map {
            ConversationUi(
                peerId = it.peerId,
                name = it.name.ifEmpty { it.peerId },
                isGroup = it.isGroup == 1L,
                lastTs = it.lastTs ?: 0L,
                count = (counts[it.peerId] ?: 0L).toInt(),
            )
        }
    }

    private val pageSizes = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())

    /** C11：消息流分页——首次 300 条，点「加载更早」每次 +300（上限 3000） */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val _focusPeers = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val focusPeers: kotlinx.coroutines.flow.StateFlow<Set<String>> = _focusPeers

    init {
        viewModelScope.launch {
            _focusPeers.value = runCatching { db.messageDao().focusPeers().toSet() }.getOrDefault(emptySet())
        }
    }

    /** B2：手动纠正群/单聊（自动判定误伤时的出口） */
    fun setGroup(peerId: String, group: Boolean) = viewModelScope.launch {
        runCatching {
            db.messageDao().setConversationGroup(peerId, if (group) 1L else 0L)
            // 纠正为单聊时清掉历史 sender，否则下一批消息的前缀又会把它判成群
            if (!group) {
                val name = db.messageDao().conversation(peerId)?.name?.takeIf { it.isNotBlank() } ?: peerId
                db.messageDao().resetSenders(peerId, name)
            }
        }
    }

    /** R5：关注/取消关注会话（简报里优先展示） */
    fun toggleFocus(peerId: String) = viewModelScope.launch {
        if (peerId in _focusPeers.value) {
            db.messageDao().clearFocus(peerId)
            _focusPeers.value = _focusPeers.value - peerId
        } else {
            db.messageDao().insertWatchRule(
                com.infoagent.data.db.WatchRuleEntity(
                    ruleType = "PEER",
                    value = peerId,
                    action = "FOCUS",
                    createdAt = System.currentTimeMillis(),
                ),
            )
            _focusPeers.value = _focusPeers.value + peerId
        }
    }

    fun messages(peerId: String): Flow<List<WechatMessageEntity>> =
        pageSizes.map { it[peerId] ?: PAGE_SIZE }
            .distinctUntilChanged()
            .flatMapLatest { limit -> db.messageDao().observeMessages(peerId, 0L, limit) }

    fun pageSize(peerId: String): Int = pageSizes.value[peerId] ?: PAGE_SIZE

    fun loadMore(peerId: String) {
        pageSizes.update { current ->
            val next = ((current[peerId] ?: PAGE_SIZE) + PAGE_SIZE).coerceAtMost(MAX_PAGE_SIZE)
            current + (peerId to next)
        }
    }

    companion object {
        const val PAGE_SIZE = 300
        const val MAX_PAGE_SIZE = 3000
    }

    fun permissionGranted(): Boolean = NotificationAccess.isGranted(getApplication())

    suspend fun search(query: String): List<WechatMessageEntity> =
        if (query.isBlank()) emptyList() else repo.search(query.trim(), 100)

    fun deleteMessage(id: Long) = viewModelScope.launch { repo.deleteMessage(id) }

    fun importText(text: String, onResult: (Int) -> Unit) = viewModelScope.launch {
        val parsed = ImportParser.parse(text)
        parsed.forEach { repo.ingest(it) }
        onResult(parsed.size)
    }

    /** 长按「转备忘」：写临时事件 + 来源标注（source_msg_id 首来源 + memo_source 事实源） */
    fun turnIntoMemo(message: WechatMessageEntity) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val today = DayBoundary.today()
        val order = (db.memoDao().list(today, "TEMP").maxOfOrNull { it.sortOrder } ?: 0L) + 1L
        val memoId = db.memoDao().insert(
            MemoItemEntity(
                kind = "TEMP",
                text = message.content.take(200),
                sortOrder = order,
                cycleDate = today,
                origin = "MANUAL",
                sourceApp = message.appSource,
                sourceConv = message.peerName,
                sourceMsgId = message.id,
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.memoDao().insertSource(MemoSourceEntity(memoId, message.id))
    }

    fun recall(messageId: Long) = viewModelScope.launch {
        db.messageDao().updateRecalled(messageId, 1)
    }
}