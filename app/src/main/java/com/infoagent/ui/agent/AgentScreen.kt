package com.infoagent.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infoagent.agent.BriefRun
import com.infoagent.agent.model.Brief
import com.infoagent.agent.model.TimelineItem
import com.infoagent.data.db.MemoryFactEntity
import com.infoagent.ui.util.TimeFormat
import kotlinx.coroutines.launch

/** Agent 页（FR-07/08）：简报 / 记忆 / 设置 */
@OptIn(ExperimentalMaterial3Api::class)
/** 设置独立成页，抽屉直达；AgentTabs 仅保留给后续深链使用 */
object AgentTabs {
    val request = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
}

@Composable
fun AgentScreen(
    onOpenSettings: () -> Unit = {},
    vm: AgentViewModel = viewModel(),
) {
    val draft by vm.draft.collectAsState()
    val toast by vm.toast.collectAsState()
    val facts by vm.facts.collectAsState()
    val memoryMessage by vm.memoryMessage.collectAsState()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val refFocus by vm.refFocus.collectAsState()
    val tabRequest by AgentTabs.request.collectAsState()
    androidx.compose.runtime.LaunchedEffect(tabRequest) {
        tabRequest?.let {
            tab = it
            AgentTabs.request.value = null
        }
    }

    // 要点 → 原话：整页接管，返回即回简报（不弹窗，避免打断阅读）
    refFocus?.let { focus ->
        RefFocusScreen(focus = focus, onClose = vm::closeRefs)
        return
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
            listOf("简报", "记忆").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
            }
        }
        when (tab) {
            0 -> TimelineTab(
                vm = vm,
                initialSliceId = AgentDeepLink.consume(),
                onOpenRefs = vm::openRefs,
            )
            1 -> MemoryTab(
                facts = facts,
                message = memoryMessage,
                onDismiss = vm::dismissFact,
                onEdit = vm::editFact,
                onClearMessage = vm::clearMemoryMessage,
            )
        }
    }
}

/** 设置独立成页（从抽屉直接进入），不再挂在 Agent 的第三个 Tab 下 */
@Composable
fun SettingsScreen(vm: AgentViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val draft by vm.draft.collectAsState()
    val toast by vm.toast.collectAsState()
    SettingsTab(
        draft = draft,
        toast = toast,
        onUpdate = vm::updateDraft,
        onSave = vm::saveSettings,
        onToastClear = vm::clearToast,
    )
}

@Composable
private fun MemoryTab(
    facts: List<MemoryFactEntity>,
    message: String?,
    onDismiss: (Long) -> Unit,
    onEdit: (Long, String) -> Unit,
    onClearMessage: () -> Unit,
) {
    var editing by remember { mutableStateOf<MemoryFactEntity?>(null) }
    Column(Modifier.fillMaxSize()) {
        message?.let {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearMessage) { Text("知道了") }
                }
            }
        }
        if (facts.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("还没有记忆事实", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "晚间简报后会从你的备忘与完成情况里提炼习惯与偏好；\n这里可以停用或改写不想被记住的事。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(facts, key = { it.id }) { fact ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (fact.category) {
                                    "HABIT" -> "习惯"
                                    "PREFERENCE" -> "偏好"
                                    "RELATION" -> "关系"
                                    else -> "规律"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(fact.factText.orEmpty(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { fact.confidence / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "置信 ${fact.confidence} · 证据 ${fact.evidenceCount} 次 · ${if (fact.status == "ACTIVE") "生效中" else fact.status}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { editing = fact }) { Text("编辑") }
                            if (fact.status == "ACTIVE") {
                                TextButton(onClick = { onDismiss(fact.id) }) { Text("停用") }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    editing?.let { fact ->
        var text by remember(fact.id) { mutableStateOf(fact.factText.orEmpty()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑记忆") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("事实描述") },
                )
            },
            confirmButton = {
                TextButton(onClick = { onEdit(fact.id, text); editing = null }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingsTab(
    draft: AgentSettingsDraft,
    toast: String?,
    onUpdate: (String?, String?, String?, Double?, Int?) -> Unit,
    onSave: (String) -> Unit,
    onToastClear: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var ioMessage by remember { mutableStateOf<String?>(null) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    // A10：流式写文件（不再先把整库拼成一个大字符串）
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        com.infoagent.data.export.ExportService(
                            com.infoagent.data.db.InfoAgentDatabase.get(context),
                        ).exportTo(out)
                    }
                }.onSuccess {
                    ioMessage = "已导出备份（不含 API Key）"
                }.onFailure {
                    ioMessage = "导出失败：${it.message?.take(100)}"
                }
            }
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                // A10：先看大小，超大备份直接拦下，避免全量读入内存 OOM
                val size = runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                }.getOrNull() ?: -1L
                if (size > 12L * 1024 * 1024) {
                    ioMessage = "备份文件过大（${size / 1024 / 1024} MB），暂不支持导入"
                    return@launch
                }
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    com.infoagent.data.export.ImportService(
                        com.infoagent.data.db.InfoAgentDatabase.get(context),
                    ).restore(text)
                }.onSuccess { report ->
                    ioMessage = "导入完成：消息 ${report.messagesImported} 条、跳过 ${report.messagesSkipped} 条、备忘 ${report.memosImported} 条"
                }.onFailure {
                    ioMessage = "导入失败：${it.message?.take(100)}"
                }
            }
        }
    }
    var baseUrl by remember { mutableStateOf(draft.baseUrl) }
    var endpoint by remember { mutableStateOf(draft.endpoint) }
    var model by remember { mutableStateOf(draft.model) }
    var apiKey by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf(draft.temperature.toFloat()) }
    var maxTokens by remember { mutableStateOf(draft.maxTokens.toString()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("接口（OpenAI 兼容）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("接口地址（https）") },
            placeholder = { Text("https://api.deepseek.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text("路径") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名") },
            placeholder = { Text("deepseek-chat") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(if (draft.apiKeyPresent) "API Key（已保存，留空则不修改）" else "API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Text(
            "Key 只保存在本机 Keystore 加密存储中，不会被导出。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text("温度：${"%.2f".format(temperature)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
        Slider(
            value = temperature,
            onValueChange = { temperature = it },
            valueRange = 0f..1f,
        )
        OutlinedTextField(
            value = maxTokens,
            onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
            label = { Text("max_tokens") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                onUpdate(baseUrl, model, endpoint, temperature.toDouble(), maxTokens.toIntOrNull() ?: 2048)
                onSave(apiKey)
            }) { Text("保存设置") }
            Spacer(Modifier.width(12.dp))
            Text(
                if (draft.apiKeyPresent) "Key 已保存" else "Key 未保存",
                style = MaterialTheme.typography.labelSmall,
                color = if (draft.apiKeyPresent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        toast?.let {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onToastClear) { Text("知道了") }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        EnrichSettingsSection(context)

        Spacer(Modifier.height(20.dp))
        Text("权限状态", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val granted = remember { com.infoagent.capture.NotificationAccess.isGranted(context) }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (granted) "通知使用权已开启" else "通知使用权未开启",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            if (!granted) {
                TextButton(onClick = { com.infoagent.capture.NotificationAccess.openSettings(context) }) { Text("去开启") }
            }
        }
        Spacer(Modifier.height(16.dp))
        CaptureScopeSection(context = context)

        Spacer(Modifier.height(16.dp))
        Text("系统岛提示", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "只发系统通知：Android 16 会尝试 Live Updates（状态栏胶囊/进度条），" +
                "是否显示成岛由系统决定；不会再画悬浮窗贴图。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                scope.launch {
                    // 装饰一条真实通知再发：验证「只 +1 条」且岛能力生效
                    val db = com.infoagent.data.db.InfoAgentDatabase.get(context.applicationContext)
                    val factory = com.infoagent.island.NotificationFactory(context.applicationContext, db.scheduleDao())
                    val (id, builder) = factory.buildReminder(0L, "系统岛测试：由系统通知展示", "测试")
                    com.infoagent.island.IslandRouter.decorate(
                        context.applicationContext,
                        builder,
                        com.infoagent.island.IslandPayload(
                            key = "island-test",
                            title = "☀ 信息小agent",
                            content = "系统岛测试：由系统通知展示",
                            progress = 40,
                            deepLink = com.infoagent.island.IslandIntents.openApp(context, "agent"),
                        ),
                    )
                    com.infoagent.island.NotificationPoster.notify(context.applicationContext, id, builder)
                }
            }) { Text("测试系统岛") }
        }

        Spacer(Modifier.height(16.dp))
        Text("简报时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "到点自动生成并推送；改完点「保存并重排」立即生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val morningTime = remember { androidx.compose.runtime.mutableStateOf(com.infoagent.remind.BriefingPrefs.time(context, "MORNING").text()) }
        val afternoonTime = remember { androidx.compose.runtime.mutableStateOf(com.infoagent.remind.BriefingPrefs.time(context, "AFTERNOON").text()) }
        val eveningTime = remember { androidx.compose.runtime.mutableStateOf(com.infoagent.remind.BriefingPrefs.time(context, "EVENING").text()) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            com.infoagent.ui.util.TimeField(
                label = "上午",
                value = morningTime.value,
                onChange = { morningTime.value = it },
                modifier = Modifier.weight(1f),
            )
            com.infoagent.ui.util.TimeField(
                label = "下午",
                value = afternoonTime.value,
                onChange = { afternoonTime.value = it },
                modifier = Modifier.weight(1f),
            )
            com.infoagent.ui.util.TimeField(
                label = "晚间",
                value = eveningTime.value,
                onChange = { eveningTime.value = it },
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(onClick = {
            val ok = listOf(
                "MORNING" to morningTime.value,
                "AFTERNOON" to afternoonTime.value,
                "EVENING" to eveningTime.value,
            ).all { (_, t) -> com.infoagent.remind.BriefingPrefs.parse(t) != null }
            if (!ok) {
                android.widget.Toast.makeText(context, "时间格式应为 HH:mm", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                com.infoagent.remind.BriefingPrefs.setTime(context, "MORNING", morningTime.value)
                com.infoagent.remind.BriefingPrefs.setTime(context, "AFTERNOON", afternoonTime.value)
                com.infoagent.remind.BriefingPrefs.setTime(context, "EVENING", eveningTime.value)
                // A2：时点先后 + 窗口长度校验（不合法则回滚提示，不重排）
                val invalid = com.infoagent.remind.BriefingPrefs.validate(context)
                if (invalid != null) {
                    android.widget.Toast.makeText(context, invalid, android.widget.Toast.LENGTH_LONG).show()
                } else {
                    com.infoagent.remind.BriefingScheduler.scheduleAll(context)
                    android.widget.Toast.makeText(context, "已保存，定时已重排", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }) { Text("保存并重排") }
        // A2：把窗口覆盖范围直接写出来（按当前输入实时计算，不必先保存）
        val coverage = remember(
            morningTime.value, afternoonTime.value, eveningTime.value,
        ) {
            val m = com.infoagent.remind.BriefingPrefs.parse(morningTime.value)
            val e = com.infoagent.remind.BriefingPrefs.parse(eveningTime.value)
            if (m == null || e == null) {
                "时间格式应为 HH:mm"
            } else {
                val mMin = m.hour * 60 + m.minute
                val eMin = e.hour * 60 + e.minute
                val len = (24 * 60 - eMin + mMin + 24 * 60) % (24 * 60)
                "上午简报覆盖：昨日 ${"%02d:%02d".format(e.hour, e.minute)} – 今日 ${"%02d:%02d".format(m.hour, m.minute)}（${len / 60} 小时 ${len % 60} 分）"
            }
        }
        Text(
            coverage,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        Spacer(Modifier.height(16.dp))
        Text("提醒", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "到点用系统通知横幅提醒（高优先级渠道），不再自绘悬浮窗。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val exactOk = remember { com.infoagent.remind.ReminderScheduler.hasExactAlarmPermission(context) }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (exactOk) "精确提醒权限：已授予" else "精确提醒权限：未授予（提醒可能延迟）",
                style = MaterialTheme.typography.labelSmall,
                color = if (exactOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            if (!exactOk) {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                android.net.Uri.parse("package:${context.packageName}"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text("去开启") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "导入是「合并」：同 dedup_key 的消息会跳过，本地已有数据不会被清空。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { exportLauncher.launch("infoagent-backup.json") },
                modifier = Modifier.weight(1f),
            ) { Text("导出备份") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                modifier = Modifier.weight(1f),
            ) { Text("导入恢复") }
        }
        Text(
            "备份为本地 JSON（不含 API Key）；导入会做 schema 版本校验，重复消息按 dedup_key 跳过。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        ioMessage?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = { ioMessage = null }) { Text("知道了") }
        }

        Spacer(Modifier.height(20.dp))
        Text("回收站", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "删除的消息/备忘会先放这里，30 天后自动清理。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val db = remember { com.infoagent.data.db.InfoAgentDatabase.get(context) }
        var recycleOpen by remember { mutableStateOf(false) }
        var recycleItems by remember {
            mutableStateOf<List<com.infoagent.data.db.RecycleEntity>>(emptyList())
        }
        androidx.compose.runtime.LaunchedEffect(recycleOpen) {
            if (recycleOpen) {
                recycleItems = db.memoryDao().recycleList(System.currentTimeMillis(), 50)
            }
        }
        TextButton(onClick = { recycleOpen = true }) {
            Text(if (recycleItems.isEmpty()) "打开回收站" else "打开回收站（${recycleItems.size} 条）")
        }
        if (recycleOpen) {
            AlertDialog(
                onDismissRequest = { recycleOpen = false },
                title = { Text("回收站") },
                text = {
                    Column(Modifier.height(240.dp).verticalScroll(rememberScrollState())) {
                        if (recycleItems.isEmpty()) {
                            Text("回收站是空的", style = MaterialTheme.typography.bodySmall)
                        } else {
                            recycleItems.forEach { item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (item.kind == "wechat_message") "消息" else "备忘",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        TimeFormat.listTime(item.deletedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = {
                                        scope.launch {
                                            com.infoagent.data.export.RecycleController(db).restore(item.id)
                                            recycleItems = db.memoryDao().recycleList(System.currentTimeMillis(), 50)
                                        }
                                    }) { Text("恢复") }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            db.memoryDao().clearRecycle()
                            recycleItems = emptyList()
                            ioMessage = "回收站已清空"
                        }
                    }) { Text("清空") }
                },
                dismissButton = { TextButton(onClick = { recycleOpen = false }) { Text("关闭") } },
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("关于", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "信息小agent ${com.infoagent.BuildConfig.VERSION_NAME} · 数据只存本机",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var privacyOpen by remember { mutableStateOf(false) }
        TextButton(onClick = { privacyOpen = true }) { Text("隐私说明") }
        if (privacyOpen) {
            AlertDialog(
                onDismissRequest = { privacyOpen = false },
                title = { Text("隐私说明") },
                text = {
                    Text(
                        "1. 消息来自系统通知，只在本机 SQLite 保存；\n" +
                            "2. API Key 存在 EncryptedSharedPreferences（Keystore 加密）；\n" +
                            "3. 生成简报时，消息与备忘会发送到你自己配置的接口地址；\n" +
                            "4. 导出备份不含 API Key，且默认关闭系统云备份；\n" +
                            "5. 删除的消息进入回收站，过期自动清理。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                confirmButton = { TextButton(onClick = { privacyOpen = false }) { Text("知道了") } },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 采集范围：逐会话开关（写 conversation.muted）。
 *
 * 为什么要这个：现在通知是实时收的，但并不是每个群都值得进简报——
 * 小区群、家长群、广告群一天几百条，会把真正重要的事淹掉。
 * 关掉之后 NotificationListenerService 直接丢弃该会话的通知（不入库、不进 prompt、不花钱）。
 */
@Composable
private fun CaptureScopeSection(context: android.content.Context) {
    val db = remember { com.infoagent.data.db.InfoAgentDatabase.get(context.applicationContext) }
    val conversations by db.messageDao().observeConversations()
        .collectAsState(initial = emptyList())
    var expanded by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val active = conversations.filter { it.muted != 1L }
    val mutedCount = conversations.size - active.size

    Text("采集范围", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
        "实时采集现在开着。关掉某个群/个人，它的通知就不会再进简报（已存的历史保留）。" +
            "已选 ${active.size} 个" + if (mutedCount > 0) "，关闭 $mutedCount 个" else "",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (conversations.isEmpty()) {
        Text(
            "还没有会话记录，等收到通知后再来设置。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 2.dp)) {
        Text(if (expanded) "收起列表" else "选择要采集的群聊 / 个人（${conversations.size}）")
    }
    if (!expanded) return

    conversations.take(60).forEach { conv ->
        val on = conv.muted != 1L
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    conv.name.ifBlank { conv.peerId },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    (if (conv.isGroup == 1L) "群聊" else "个人"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.Switch(
                checked = on,
                onCheckedChange = { checked ->
                    scope.launch {
                        db.messageDao().setConversationMuted(conv.peerId, if (checked) 0L else 1L)
                    }
                },
            )
        }
    }
    if (conversations.size > 60) {
        Text(
            "只列出最近 60 个会话（按最近消息时间），其余的默认采集。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 外部信息：用户自己的天气 / 搜索 key。
 *
 * 为什么值得多填两个 key：模型不联网，靠常识写「11 月西湖风大」对你没用；
 * 有了这一层，简报才能写「明天杭州 12~19℃、有阵雨，带伞」这种真能用的判断。
 * key 存在本机 Keystore 加密区，只在本机生成简报时调用，不经过任何第三方服务器。
 */
@Composable
private fun EnrichSettingsSection(context: android.content.Context) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val settings = remember { com.infoagent.enrich.EnrichSettings(context.applicationContext) }
    var enabled by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var weatherKey by remember { mutableStateOf("") }
    var weatherHost by remember { mutableStateOf("devapi.qweather.com") }
    var searchKey by remember { mutableStateOf("") }
    var searchEndpoint by remember { mutableStateOf("https://api.tavily.com/search") }
    var homeCity by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!loaded) {
            val cfg = settings.load()
            enabled = settings.enabled()
            weatherHost = cfg.weatherHost
            searchEndpoint = cfg.searchEndpoint
            homeCity = cfg.homeCity
            weatherKey = if (cfg.weatherKey.isBlank()) "" else "已保存"
            searchKey = if (cfg.searchKey.isBlank()) "" else "已保存"
            loaded = true
        }
    }

    Text("外部信息（让建议落地）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
        "填了之后，生成简报前会按你消息里提到的地点/日期去查天气与必要的事实，" +
            "回复建议里就能出现「明天杭州 12~19℃、有阵雨」这种具体判断。key 存本机加密区，不导出。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Switch(checked = enabled, onCheckedChange = {
            enabled = it
            scope.launch {
                settings.setEnabled(it)
                hint = if (it) "已开启：下次生成简报时会先查证" else "已关闭：简报只用消息与常识"
            }
        })
        Spacer(Modifier.width(8.dp))
        Text(if (enabled) "已开启" else "未开启", style = MaterialTheme.typography.bodySmall)
    }

    OutlinedTextField(
        value = homeCity,
        onValueChange = { homeCity = it },
        label = { Text("常住城市（消息没提城市时兜底查它的天气）") },
        placeholder = { Text("杭州") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        value = weatherKey,
        onValueChange = { weatherKey = it },
        label = { Text("和风天气 Key") },
        placeholder = { Text("和风天气控制台 → Key 管理") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        value = weatherHost,
        onValueChange = { weatherHost = it },
        label = { Text("天气 API Host") },
        placeholder = { Text("devapi.qweather.com 或 xxxx.re.qweatherapi.com") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        value = searchKey,
        onValueChange = { searchKey = it },
        label = { Text("搜索服务 Key（可选，用于查限行/营业时间/机构背景）") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        value = searchEndpoint,
        onValueChange = { searchEndpoint = it },
        label = { Text("搜索接口地址") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
            scope.launch {
                val wKey = weatherKey.takeIf { it != "已保存" }
                val sKey = searchKey.takeIf { it != "已保存" }
                settings.save(wKey, weatherHost, sKey, searchEndpoint, homeCity)
                settings.setEnabled(enabled)
                val cfg = settings.load()
                weatherKey = if (cfg.weatherKey.isBlank()) "" else "已保存"
                searchKey = if (cfg.searchKey.isBlank()) "" else "已保存"
                hint = when {
                    !cfg.anyEnabled -> "已保存，但还没有可用的 key"
                    cfg.weatherEnabled && cfg.searchEnabled -> "已保存：天气 + 搜索都可用"
                    cfg.weatherEnabled -> "已保存：只有天气可用"
                    else -> "已保存：只有搜索可用"
                }
            }
        }) { Text("保存外部接口") }
        Spacer(Modifier.width(10.dp))
        Text(
            if (enabled) "开启时会消耗你自己的接口额度" else "关闭状态不产生任何外部请求",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    hint?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
