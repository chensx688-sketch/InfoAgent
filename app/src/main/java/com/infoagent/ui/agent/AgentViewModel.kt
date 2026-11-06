package com.infoagent.ui.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.infoagent.agent.BriefRun
import com.infoagent.agent.BriefingPipeline
import com.infoagent.agent.OpenAiCompatibleClient
import com.infoagent.data.db.InfoAgentDatabase
import com.infoagent.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AgentSettingsDraft(
    val baseUrl: String = "",
    val endpoint: String = "/chat/completions",
    val model: String = "",
    val temperature: Double = 0.3,
    val maxTokens: Int = 2048,
    val apiKeyPresent: Boolean = false,
)

data class UsageUi(
    val manualRemaining: Long = 0,
    val briefRemaining: Long = 0,
    val todayCost: Double = 0.0,
)

/** 每个栏目的窗口信息：区间 + 素材条数，保证切换真的有区别 */
data class WindowInfoUi(
    val window: String,
    val start: Long,
    val end: Long,
    val messageCount: Int,
    val hasBrief: Boolean,
)

/** v3 时间轴：当前选中日期 + 该日片段 + 展开/生成状态 */
class AgentViewModel(app: Application) : AndroidViewModel(app) {

    private val db = InfoAgentDatabase.get(app)
    private val settings = SettingsRepository(app)
    private val client = OpenAiCompatibleClient()
    private val pipeline = BriefingPipeline(
        db,
        client,
        { settings.config() },
        boundsProvider = { bounds() },
    ).withEnricher(com.infoagent.enrich.ContextEnricher.forApp(db, app))
    private val _selectedDate = MutableStateFlow(com.infoagent.data.util.DayBoundary.today())
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    /** 要点跳回原话：null=不显示详情页 */
    private val _refFocus = MutableStateFlow<com.infoagent.data.repo.RefFocus?>(null)
    val refFocus: StateFlow<com.infoagent.data.repo.RefFocus?> = _refFocus.asStateFlow()

    /** 正在加载占位（避免点了没反应） */
    private val RefFocusLoading = com.infoagent.data.repo.RefFocus(
        title = "",
        rows = emptyList(),
        focusedCount = 0,
        note = "正在找原文…",
    )

    /** 当前日期的片段（按 seq 升序，随 DB 变化自动刷新） */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val slices: StateFlow<List<com.infoagent.data.db.BriefSliceEntity>> =
        _selectedDate.flatMapLatest { db.briefSliceDao().observeByDay(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 简报可视化用的真实数据（小时曲线/会话热度/完成率），换成别的日期要重新取 */
    private val metricsRepo = com.infoagent.data.repo.BriefMetricsRepository(db)
    private val _metrics = MutableStateFlow<com.infoagent.data.repo.DayMetrics?>(null)
    val metrics: StateFlow<com.infoagent.data.repo.DayMetrics?> = _metrics.asStateFlow()

    /** 数据新鲜度：简报为什么没跟上最新消息，直接给结论 */
    private val freshnessRepo = com.infoagent.data.repo.FreshnessRepository(db, app)
    private val _freshness = MutableStateFlow<com.infoagent.data.repo.Freshness?>(null)
    val freshness: StateFlow<com.infoagent.data.repo.Freshness?> = _freshness.asStateFlow()

    fun loadFreshness() {
        viewModelScope.launch {
            _freshness.value = runCatching { freshnessRepo.load(_selectedDate.value) }.getOrNull()
        }
    }

    fun loadMetrics(date: String = _selectedDate.value) {
        viewModelScope.launch {
            _metrics.value = runCatching { metricsRepo.dayMetrics(date) }.getOrNull()
        }
    }

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _sliceError = MutableStateFlow<String?>(null)
    val sliceError: StateFlow<String?> = _sliceError.asStateFlow()

    fun dismissSliceError() { _sliceError.value = null }

    fun setDate(date: String) {
        if (_selectedDate.value == date) return
        _selectedDate.value = date
        _sliceError.value = null
        _metrics.value = null
        loadMetrics(date)
    }

    fun shiftDate(days: Long) {
        val d = java.time.LocalDate.parse(_selectedDate.value).plusDays(days).toString()
        setDate(d)
    }

    /** 立即补一份（增量：从上一条 OK 的 to_ts 到现在） */
    fun appendSlice() {
        if (_generating.value) return
        _generating.value = true
        _sliceError.value = null
        viewModelScope.launch {
            try {
                // 手动是显式动作：force=true 才能绕过「5 分钟防抖」，否则刚补过再点会毫无反应
                val result = pipeline.generateSlice("MANUAL", force = true)
                if (result.state != "OK") {
                    _sliceError.value = result.error ?: "生成失败"
                }
                // 生成的是今天：确保视图切到今天，新片段才会出现在列表里
                val today = com.infoagent.data.util.DayBoundary.today()
                if (_selectedDate.value != today && result.state == "OK") _selectedDate.value = today
                refreshUsage()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _sliceError.value = "生成失败：${e.message?.take(120)}"
            } finally {
                _generating.value = false
                loadMetrics()
                loadFreshness()
            }
        }
    }

    /**
     * 要点 → 原话：加载被引用的消息 + 同会话上下文。
     *
     * 为什么要带上下文：只有一句「报价单已发你」看不出所以然，
     * 前面那句「预算 8 万能不能做」才是判断依据——用户点进来看的是「凭什么这么说」。
     */
    fun openRefs(ids: List<Long>) {
        if (ids.isEmpty()) return
        _refFocus.value = RefFocusLoading
        viewModelScope.launch {
            val focused = runCatching { db.messageDao().byIds(ids) }.getOrDefault(emptyList())
            val context = if (focused.isEmpty()) {
                emptyList()
            } else {
                val w = com.infoagent.data.repo.RefFocusBuilder.window(focused)
                runCatching {
                    db.messageDao().aroundPeer(focused.first().peerId, w.first, w.last)
                }.getOrDefault(emptyList())
            }
            _refFocus.value = com.infoagent.data.repo.RefFocusBuilder.build(focused, context)
        }
    }

    fun closeRefs() {
        _refFocus.value = null
    }

    /** 失败片段重试（仍按增量规则补） */
    fun retrySlice(id: Long) {
        if (_generating.value) return
        _generating.value = true
        _sliceError.value = null
        viewModelScope.launch {
            try {
                val result = pipeline.generateSlice("MANUAL", force = true)
                if (result.state != "OK") _sliceError.value = result.error ?: "重试失败"
                refreshUsage()
            } finally {
                _generating.value = false
                loadMetrics()
                loadFreshness()
            }
        }
    }

    fun markRead(id: Long) {
        viewModelScope.launch {
            runCatching { db.briefSliceDao().markRead(id, System.currentTimeMillis()) }
        }
    }

    /** 解析片段 payload（历史脏数据同样过一遍清洗） */
    fun briefOf(slice: com.infoagent.data.db.BriefSliceEntity): com.infoagent.agent.model.Brief? {
        val json = slice.payloadJson ?: return null
        return (com.infoagent.agent.SafeParser.parseBrief(json, slice.trigger)
            as? com.infoagent.agent.SafeParser.Result.Ok)?.value?.let { pipeline.sanitizeBrief(it) }
    }

    private fun bounds(): com.infoagent.agent.BriefingPipeline.WindowBounds {
        val slots = com.infoagent.remind.BriefingPrefs.slots(getApplication())
        return com.infoagent.agent.BriefingPipeline.WindowBounds(
            morningEnd = slots.first { it.window == "MORNING" }.let { it.hour * 60 + it.minute },
            afternoonEnd = slots.first { it.window == "AFTERNOON" }.let { it.hour * 60 + it.minute },
            eveningEnd = slots.first { it.window == "EVENING" }.let { it.hour * 60 + it.minute },
        )
    }

    private val _window = MutableStateFlow("MANUAL")
    val window: StateFlow<String> = _window.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _streaming = MutableStateFlow("")
    val streaming: StateFlow<String> = _streaming.asStateFlow()

    private val _run = MutableStateFlow<BriefRun?>(null)
    val run: StateFlow<BriefRun?> = _run.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _draft = MutableStateFlow(AgentSettingsDraft())
    val draft: StateFlow<AgentSettingsDraft> = _draft.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _generatedAt = MutableStateFlow<Long?>(null)
    val generatedAt: StateFlow<Long?> = _generatedAt.asStateFlow()

    /** 当前栏目对应的窗口（区间/条数/是否已有简报），切换栏目时刷新 */
    private val _windowInfo = MutableStateFlow<WindowInfoUi?>(null)
    val windowInfo: StateFlow<WindowInfoUi?> = _windowInfo.asStateFlow()

    /** 记忆事实（FR-08）：ACTIVE 优先，按置信度排序 */
    val facts: StateFlow<List<com.infoagent.data.db.MemoryFactEntity>> =
        db.memoryDao().observeFacts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _memoryMessage = MutableStateFlow<String?>(null)
    val memoryMessage: StateFlow<String?> = _memoryMessage.asStateFlow()

    fun dismissFact(id: Long) {
        viewModelScope.launch {
            db.memoryDao().dismissFact(id, System.currentTimeMillis())
            _memoryMessage.value = "已停用该记忆"
        }
    }

    fun editFact(id: Long, text: String) {
        viewModelScope.launch {
            db.memoryDao().editFact(id, text.trim(), System.currentTimeMillis())
            _memoryMessage.value = "已更新记忆"
        }
    }

    /** 简报推荐 👍👎 → 写反馈表并回写引用的事实（§8.5 card 级反馈） */
    fun voteRecommendation(itemIndex: Int, memoryRefs: List<Long>, up: Boolean) {
        viewModelScope.launch {
            // B3：先把卡片级反馈落库，记忆合并时才能真正用上
            runCatching {
                db.agentDao().insertBriefFeedback(
                    com.infoagent.data.db.BriefFeedbackEntity(
                        runId = _run.value?.runId,
                        section = "recommendation",
                        itemIndex = itemIndex.toLong(),
                        vote = if (up) 1L else 0L,
                        ts = System.currentTimeMillis(),
                    ),
                )
            }
            memoryRefs.forEach { factId ->
                val fact = db.memoryDao().topActiveFacts(100).firstOrNull { it.id == factId } ?: return@forEach
                val now = System.currentTimeMillis()
                // A7：把 👍👎 累加进 feedback_sum 并据此重算，下一次记忆合并不再抹掉
                val nextFeedback = fact.feedbackSum + if (up) 2L else -5L
                val days = fact.lastSeen?.let { (now - it).toDouble() / 86_400_000.0 } ?: 0.0
                val next = com.infoagent.agent.MemoryMath
                    .confidence(fact.evidenceCount.toInt(), nextFeedback.toInt(), days)
                    .coerceAtLeast(0)
                    .toLong()
                db.memoryDao().updateConfidence(
                    factId,
                    next,
                    nextFeedback,
                    if (next <= 0) "DISMISSED" else "ACTIVE",
                    now,
                )
            }
            _memoryMessage.value = if (up) "已采纳：记忆置信度 +2" else "已否定：记忆置信度 -5"
        }
    }

    fun clearMemoryMessage() {
        _memoryMessage.value = null
    }

    private val _usage = MutableStateFlow(UsageUi())
    val usage: StateFlow<UsageUi> = _usage.asStateFlow()

    fun refreshUsage() = viewModelScope.launch {
        runCatching {
            val guard = com.infoagent.agent.BudgetGuard(
                db.agentDao(),
                { com.infoagent.data.util.DayBoundary.today() },
            )
            _usage.value = UsageUi(
                manualRemaining = guard.remaining(com.infoagent.agent.BudgetGuard.USE_MANUAL, manual = true),
                briefRemaining = guard.remaining(com.infoagent.agent.BudgetGuard.USE_BRIEF),
                todayCost = guard.dailyCost(),
            )
        }
    }

    init {
        viewModelScope.launch {
            val snap = settings.snapshot()
            _draft.value = AgentSettingsDraft(
                baseUrl = snap.baseUrl,
                endpoint = snap.endpoint,
                model = snap.model,
                temperature = snap.temperature,
                maxTokens = snap.maxTokens,
                apiKeyPresent = snap.apiKeyPresent,
            )
            // C1：按「当前时间所属栏目」加载，而不是随便取一份最新简报
            val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
            val b = bounds()
            val defaultWindow = when {
                nowMin < b.morningEnd -> "MORNING"
                nowMin < b.afternoonEnd -> "AFTERNOON"
                nowMin < b.eveningEnd -> "EVENING"
                else -> "EVENING"
            }
            _window.value = defaultWindow
            loadWindow(defaultWindow)
            refreshUsage()
            loadMetrics()
            loadFreshness()
        }
    }

    fun selectWindow(window: String) {
        if (_window.value == window) return
        _window.value = window
        _run.value = null
        _error.value = null
        loadWindow(window)
    }

    /** 切换栏目时真正换内容：该窗口的区间、素材条数、以及该窗口今天的简报（若有） */
    private fun loadWindow(window: String, markCached: Boolean = true) = viewModelScope.launch {
        runCatching {
            val today = com.infoagent.data.util.DayBoundary.today()
            val now = System.currentTimeMillis()
            val range = com.infoagent.agent.BriefingPipeline.range(window, today, now, bounds())
            val count = db.messageDao().countBetween(range[0], range[1])
            // P1-2：素材条数/token 估算用管线实际会送进 prompt 的结果，不再用「窗口全部消息 × 40」
            val preview = runCatching { pipeline.previewStats(window) }.getOrNull()
            val existing = if (window == "MANUAL") {
                db.agentDao().latestBriefAnyWindow(today)
            } else {
                db.agentDao().latestRun(today, window)
            }
            val parsed = existing?.briefJson?.let { json ->
                (com.infoagent.agent.SafeParser.parseBrief(json, window)
                    as? com.infoagent.agent.SafeParser.Result.Ok)?.value?.let { pipeline.sanitizeBrief(it) }
            }
            _run.value = if (parsed != null && existing != null) {
                com.infoagent.agent.BriefRun(
                    brief = parsed,
                    runId = existing.id,
                    fromCache = markCached,
                    stats = preview?.copy(tokensIn = existing.tokensUsed ?: 0L)
                        ?: com.infoagent.agent.BriefStats(
                            windowStart = range[0],
                            windowEnd = range[1],
                            tokensIn = existing.tokensUsed ?: 0L,
                        ),
                )
            } else {
                null
            }
            _generatedAt.value = existing?.createdAt
            _windowInfo.value = WindowInfoUi(window, range[0], range[1], count, parsed != null)
        }
    }

    fun generate(force: Boolean = false) {
        if (_running.value) return
        generateJob = viewModelScope.launch {
            _running.value = true
            _streaming.value = ""
            _error.value = null
            try {
                // C2：不再边生成边刷原始 JSON（界面不显示、还 O(n²) 拼接）；只在完成后渲染
                val result = pipeline.generate(_window.value, manual = true, force = force)
                _run.value = result
                _error.value = result.error
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // N3：用户主动停止不是失败
            } catch (e: Exception) {
                // C3：任何异常都要解除按钮禁用状态
                _error.value = "生成失败：${e.message?.take(120)}"
                _streaming.value = ""
            } finally {
                _generatedAt.value = System.currentTimeMillis()
                _running.value = false
                refreshUsage()
                // 生成后刷新该窗口的条数/是否有简报；markCached=false —— 刚生成的结果不能标成"缓存结果"
                loadWindow(_window.value, markCached = false)
            }
        }
    }

    private var generateJob: kotlinx.coroutines.Job? = null

    fun cancel() {
        generateJob?.cancel()
        _running.value = false
        _streaming.value = ""
    }

    fun updateDraft(
        baseUrl: String? = null,
        model: String? = null,
        endpoint: String? = null,
        temperature: Double? = null,
        maxTokens: Int? = null,
    ) {
        _draft.value = _draft.value.copy(
            baseUrl = baseUrl ?: _draft.value.baseUrl,
            model = model ?: _draft.value.model,
            endpoint = endpoint ?: _draft.value.endpoint,
            temperature = temperature ?: _draft.value.temperature,
            maxTokens = maxTokens ?: _draft.value.maxTokens,
        )
    }

    fun saveSettings(apiKey: String) {
        viewModelScope.launch {
            val d = _draft.value
            val err = settings.saveApi(d.baseUrl, d.endpoint, d.model, d.temperature, d.maxTokens, apiKey)
            _toast.value = err ?: "已保存"
            if (err == null) {
                _draft.value = _draft.value.copy(apiKeyPresent = !settings.apiKey().isNullOrBlank())
            }
        }
    }

    fun clearToast() {
        _toast.value = null
    }
}