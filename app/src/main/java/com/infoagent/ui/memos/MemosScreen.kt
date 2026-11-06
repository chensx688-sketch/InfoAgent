package com.infoagent.ui.memos

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infoagent.data.db.MemoItemEntity
import com.infoagent.ui.util.TimeFormat
import com.infoagent.ui.theme.AiGradientEnd
import com.infoagent.ui.theme.AiGradientStart
import com.infoagent.ui.theme.WeChatGreen

/**
 * 备忘页（FR-04/05）：今日进度卡 + 每日必做 + 临时事件。
 * - 拖动手柄实时排序（越过阈值立即换位）；
 * - 勾选有颜色/缩放动效；
 * - AI 条目带渐变徽标与来源芯片。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MemosScreen(vm: MemosViewModel = viewModel()) {
    val daily by vm.daily.collectAsState(initial = emptyList())
    val stage by vm.stage.collectAsState(initial = emptyList())
    val temp by vm.temp.collectAsState(initial = emptyList())
    val facts by vm.facts.collectAsState(initial = emptyList())
    var showExpired by remember { mutableStateOf(false) }

    var editorVisible by remember { mutableStateOf(false) }
    var editingKind by remember { mutableStateOf("TEMP") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editingText by remember { mutableStateOf("") }
    var editingTime by remember { mutableStateOf("") }

    fun openEditor(kind: String, item: MemoItemEntity? = null) {
        editingKind = kind
        editingId = item?.id
        editingText = item?.text.orEmpty()
        editingTime = vm.timeText(item?.assignedTime)
        editorVisible = true
    }

    val doneCount = daily.count { it.done == 1L }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                TodayProgressCard(done = doneCount, total = daily.size, temp = temp.size)
            }
            item {
                SectionHeader(
                    title = "每日必做",
                    subtitle = if (daily.isEmpty()) "今天还没有安排" else "$doneCount / ${daily.size} 完成",
                    onAdd = { openEditor("DAILY") },
                )
            }
            if (daily.isEmpty()) {
                item { SectionEmpty("加一条每天都要做的事；没做完也不要紧，隔天会自动顺延并重置勾选") }
            }
            itemsIndexed(daily, key = { _, item -> item.id }) { index, item ->
                MemoRow(
                    item = item,
                    modifier = Modifier.animateItem(),
                    onToggle = { vm.toggle(item.id, it) },
                    onEdit = { openEditor("DAILY", item) },
                    onDelete = { vm.delete(item.id) },
                    onMoveUp = { vm.moveUp(item.id) },
                    onMoveDown = { vm.moveDown(item.id) },
                    onUpgrade = null,
                    timeLabel = vm.timeText(item.assignedTime),
                    onDragStep = { delta ->
                        if (delta < 0 && index > 0) vm.moveUp(item.id)
                        if (delta > 0 && index < daily.lastIndex) vm.moveDown(item.id)
                    },
                )
            }
            item {
                SectionHeader(
                    title = "阶段事件",
                    subtitle = if (stage.isEmpty()) "跨天保留，只能手动编辑/删除" else "共 ${stage.size} 条 · 不日清",
                    onAdd = { openEditor("STAGE") },
                )
            }
            if (stage.isEmpty()) {
                item { SectionEmpty("适合放「这周搬家」「项目上线前」这类跨天的事，不会被零点清掉") }
            }
            itemsIndexed(stage, key = { _, item -> item.id }) { index, item ->
                MemoRow(
                    item = item,
                    modifier = Modifier.animateItem(),
                    onToggle = { vm.toggle(item.id, it) },
                    onEdit = { openEditor("STAGE", item) },
                    onDelete = { vm.delete(item.id) },
                    onMoveUp = { vm.moveUp(item.id) },
                    onMoveDown = { vm.moveDown(item.id) },
                    onUpgrade = null,
                    timeLabel = vm.timeText(item.assignedTime),
                    onDragStep = { delta ->
                        if (delta < 0 && index > 0) vm.moveUp(item.id)
                        if (delta > 0 && index < stage.lastIndex) vm.moveDown(item.id)
                    },
                )
            }
            item {
                SectionHeader(
                    title = "临时事件",
                    subtitle = if (temp.isEmpty()) "随时记一笔" else "共 ${temp.size} 条",
                    onAdd = { openEditor("TEMP") },
                )
            }
            if (temp.isEmpty()) {
                item { SectionEmpty("临时事件不用排序，记下就行；也可以升级成每日必做") }
            }
            itemsIndexed(temp, key = { _, item -> item.id }) { index, item ->
                MemoRow(
                    item = item,
                    modifier = Modifier.animateItem(),
                    onToggle = { vm.toggle(item.id, it) },
                    onEdit = { openEditor("TEMP", item) },
                    onDelete = { vm.delete(item.id) },
                    onMoveUp = { vm.moveUp(item.id) },
                    onMoveDown = { vm.moveDown(item.id) },
                    onUpgrade = { vm.promoteToStage(item.id) },
                    timeLabel = vm.timeText(item.assignedTime),
                    onDragStep = { delta ->
                        if (delta < 0 && index > 0) vm.moveUp(item.id)
                        if (delta > 0 && index < temp.lastIndex) vm.moveDown(item.id)
                    },
                )
            }
            // ---- 长期记忆（DB v6：只增 + 时间有效性）----
            item {
                val active = facts.count { it.validTo == null && it.status != "DISMISSED" }
                SectionHeaderPlain(
                    title = "长期记忆",
                    subtitle = if (active == 0) "AI 从消息里记下的稳定事实会出现在这里" else "$active 条有效 · 生成简报时会带上",
                )
            }
            run {
                val active = facts.filter { it.validTo == null && it.status != "DISMISSED" }
                if (active.isEmpty()) {
                    item { SectionEmpty("还没攒出记忆。多用几天，并对简报里的建议点几次 👍/👎，这里就会长出你的偏好和规律") }
                }
                items(active, key = { "m-${it.id}" }) { fact ->
                    MemoryRow(
                        fact = fact,
                        onExpire = { vm.expireFact(fact.id) },
                        onUp = { vm.feedbackFact(fact.id, true) },
                        onDown = { vm.feedbackFact(fact.id, false) },
                    )
                }
            }
            run {
                val expired = facts.filter { it.validTo != null }
                if (expired.isNotEmpty()) {
                    item {
                        Text(
                            if (showExpired) "已过期 ${expired.size} 条 · 收起" else "已过期 ${expired.size} 条 · 展开",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showExpired = !showExpired }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                    if (showExpired) {
                        items(expired, key = { "e-${it.id}" }) { fact ->
                            MemoryRow(
                                fact = fact,
                                expired = true,
                                onExpire = { vm.restoreFact(fact.id) },
                                onUp = { vm.feedbackFact(fact.id, true) },
                                onDown = { vm.feedbackFact(fact.id, false) },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }

    if (editorVisible) {
        MemoEditorDialog(
            title = when {
                editingId == null && editingKind == "DAILY" -> "新增每日必做"
                editingId == null && editingKind == "STAGE" -> "新增阶段事件"
                editingId == null -> "新增临时事件"
                else -> "编辑"
            },
            text = editingText,
            time = editingTime,
            onDismiss = { editorVisible = false },
            onConfirm = { text, timeText ->
                val ts = vm.parseTime(timeText)
                if (editingId == null) vm.add(editingKind, text, ts) else vm.update(editingId!!, text, ts)
                editorVisible = false
            },
        )
    }
}

@Composable
private fun TodayProgressCard(done: Int, total: Int, temp: Int) {
    val ratio = if (total == 0) 0f else done.toFloat() / total
    val animatedRatio by animateFloatAsState(ratio, spring(stiffness = 300f), label = "ratio")
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            Modifier
                .background(
                    Brush.linearGradient(listOf(AiGradientStart, AiGradientEnd)),
                    RoundedCornerShape(20.dp),
                )
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${(animatedRatio * 100).toInt()}",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                )
                Text("%", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.padding(bottom = 6.dp)) {
                    Text("今日完成", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.9f))
                    Text(
                        "$done / $total 件必做 · $temp 条临时",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { animatedRatio },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.28f),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onAdd) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "添加",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(6.dp).size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionEmpty(hint: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoRow(
    item: MemoItemEntity,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onUpgrade: (() -> Unit)?,
    onPromoteToStage: (() -> Unit)? = null,
    timeLabel: String,
    onDragStep: (Int) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val stepPx = with(density) { 64.dp.toPx() }
    val done = item.done == 1L
    val textColor by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        label = "textColor",
    )
    val scale by animateFloatAsState(
        targetValue = if (done) 0.985f else 1f,
        animationSpec = spring(stiffness = 500f, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "scale",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .graphicsLayer {
                translationY = dragOffset
                scaleX = scale
                scaleY = scale
                alpha = if (dragging) 0.92f else 1f
            }
            .combinedClickable(onClick = { onEdit() }, onLongClick = { menu = true }),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 8.dp else 0.dp),
    ) {
        Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = done, onCheckedChange = onToggle)
            Column(Modifier.weight(1f)) {
                Text(
                    item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                    color = textColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (timeLabel.isNotEmpty()) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(timeLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (item.origin == "AI") AiBadge()
                    if (item.sourceConv != null) {
                        Spacer(Modifier.width(6.dp))
                        SourceChip(item.sourceApp ?: "wechat", item.sourceConv)
                    }
                }
            }
            // 拖动手柄：向上/向下越过阈值即换位
            Box(
                Modifier
                    .size(40.dp)
                    .pointerInput(item.id) {
                        detectDragGestures(
                            onDragStart = { dragging = true; dragOffset = 0f },
                            onDragEnd = { dragging = false; dragOffset = 0f },
                            onDragCancel = { dragging = false; dragOffset = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                if (dragOffset > stepPx) {
                                    onDragStep(1)
                                    dragOffset -= stepPx
                                } else if (dragOffset < -stepPx) {
                                    onDragStep(-1)
                                    dragOffset += stepPx
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DragIndicator,
                    contentDescription = "拖动排序",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(22.dp),
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleMedium) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("上移") }, onClick = { menu = false; onMoveUp() })
                    DropdownMenuItem(text = { Text("下移") }, onClick = { menu = false; onMoveDown() })
                    if (onUpgrade != null) {
                        DropdownMenuItem(text = { Text("升级为每日必做") }, onClick = { menu = false; onUpgrade() })
                    }
                    if (onPromoteToStage != null) {
                        DropdownMenuItem(text = { Text("升级为阶段事件") }, onClick = { menu = false; onPromoteToStage() })
                    }
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun AiBadge() {
    Row(
        Modifier
            .background(
                Brush.linearGradient(listOf(AiGradientStart, AiGradientEnd)),
                RoundedCornerShape(18.dp),
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
        Text("AI", style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Composable
private fun SourceChip(app: String, conv: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = WeChatGreen.copy(alpha = 0.14f)) {
        Text(
            "${if (app == "qq") "QQ" else "微信"}·$conv",
            style = MaterialTheme.typography.labelSmall,
            color = WeChatGreen,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MemoEditorDialog(
    title: String,
    text: String,
    time: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var textValue by remember { mutableStateOf(text) }
    var timeValue by remember { mutableStateOf(time) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("内容") },
                    placeholder = { Text("例如：健身 45 分钟") },
                    maxLines = 3,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.infoagent.ui.util.TimeField(
                        label = "提醒时间",
                        value = timeValue,
                        onChange = { timeValue = it },
                        modifier = Modifier.weight(1f),
                    )
                    if (timeValue.isNotBlank()) {
                        TextButton(onClick = { timeValue = "" }) { Text("清除") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(textValue, timeValue) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 不带「+」的段标题：长期记忆是 AI 生成的，不该让用户手动加 */
@Composable
private fun SectionHeaderPlain(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 一条长期记忆。
 *
 * 简洁的取舍：只显示「分类 + 事实 + 从哪天起」，证据条数放在第二行的浅灰里，
 * 三个动作（过期/有用/不对）收进 ⋮ 菜单，不占正文视线。
 */
@Composable
private fun MemoryRow(
    fact: com.infoagent.data.db.MemoryFactEntity,
    expired: Boolean = false,
    onExpire: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            memoryCategoryLabel(fact.category),
            style = MaterialTheme.typography.labelSmall,
            color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                fact.factText.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (expired) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (expired) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            )
            Text(
                buildString {
                    append(if (expired) "已失效" else "自 ${TimeFormat.dayLabel(fact.validFrom)} 起")
                    if (fact.evidenceCount > 1) append(" · 出现 ${fact.evidenceCount} 次")
                    if (fact.confidence in 1..100) append(" · 可信 ${fact.confidence}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                Text("⋯", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (expired) "恢复（其实还有效）" else "这条过期了") },
                    onClick = { menu = false; onExpire() },
                )
                DropdownMenuItem(text = { Text("有用") }, onClick = { menu = false; onUp() })
                DropdownMenuItem(text = { Text("不太对") }, onClick = { menu = false; onDown() })
            }
        }
    }
}

private fun memoryCategoryLabel(category: String): String = when (category) {
    "PREFERENCE" -> "偏好"
    "HABIT" -> "习惯"
    "PATTERN" -> "规律"
    "RELATION" -> "关系"
    else -> "记忆"
}