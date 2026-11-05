package com.infoagent.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.infoagent.agent.model.Brief
import com.infoagent.data.db.BriefSliceEntity

/**
 * v3 简报时间轴：一天一条轴，每条是「上一次到现在」的增量片段。
 * 默认只展开最近一条；点行切换；滚到可见即标记已读。
 */
@Composable
fun TimelineTab(vm: AgentViewModel, initialSliceId: Long? = null) {
    val date by vm.selectedDate.collectAsState()
    val slices by vm.slices.collectAsState()
    val generating by vm.generating.collectAsState()
    val error by vm.sliceError.collectAsState()
    val usage by vm.usage.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var expandedId by remember { mutableStateOf<Long?>(null) }
    // 默认展开最近一条 OK（或最新一条）
    LaunchedEffect(slices) {
        if (expandedId == null || slices.none { it.id == expandedId }) {
            expandedId = slices.lastOrNull { it.state == "OK" }?.id ?: slices.lastOrNull()?.id
        }
    }
    // 通知点击：滚到指定片段并展开（取一次即清空）
    LaunchedEffect(slices) {
        val target = initialSliceId ?: return@LaunchedEffect
        val index = slices.indexOfFirst { it.id == target }
        if (index >= 0) {
            expandedId = target
            listState.animateScrollToItem(index)
        }
    }

    Column(Modifier.fillMaxSize()) {
        DateHeader(
            date = date,
            summary = summaryOf(slices),
            onPrev = { vm.shiftDate(-1) },
            onNext = { vm.shiftDate(1) },
            nextAuto = if (date == com.infoagent.data.util.DayBoundary.today()) {
                "下次自动：${nextAutoText()}"
            } else {
                null
            },
        )
        if (error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error!!, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.dismissSliceError() }) { Text("知道了") }
                }
            }
        }

        if (slices.isEmpty() && !generating) {
            EmptyDay(
                isToday = date == com.infoagent.data.util.DayBoundary.today(),
                onAppend = vm::appendSlice,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(slices, key = { it.id }) { slice ->
                    SliceCard(
                        slice = slice,
                        expanded = expandedId == slice.id,
                        brief = if (expandedId == slice.id) vm.briefOf(slice) else null,
                        onToggle = {
                            expandedId = if (expandedId == slice.id) null else slice.id
                            if (expandedId == slice.id) vm.markRead(slice.id)
                        },
                        onRetry = { vm.retrySlice(slice.id) },
                    )
                }
                if (generating) {
                    item("generating") {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("正在生成本次增量…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item("append") {
                    OutlinedButton(
                        onClick = vm::appendSlice,
                        enabled = !generating,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) { Text(if (generating) "生成中…" else "现在补一份") }
                }
                item("usage") {
                    Text(
                        "今日剩余 手动 ${usage.manualRemaining} 次 / 自动 ${usage.briefRemaining} 次" +
                            " · 今日成本 ≈ ${"%.4f".format(usage.todayCost)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DateHeader(
    date: String,
    summary: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    nextAuto: String?,
) {
    val today = com.infoagent.data.util.DayBoundary.today()
    val label = when (date) {
        today -> "今天"
        java.time.LocalDate.parse(today).minusDays(1).toString() -> "昨天"
        else -> date
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.Filled.ArrowBack, contentDescription = "前一天") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$label · ${date.substring(5).replace("-", "月")}日",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (summary.isNotBlank()) {
                Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            nextAuto?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(
            onClick = onNext,
            enabled = date < today,
        ) { Icon(Icons.Filled.ArrowForward, contentDescription = "后一天") }
    }
}

@Composable
private fun EmptyDay(isToday: Boolean, onAppend: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (isToday) "今天还没有简报" else "这一天没有记录",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (isToday) "10:00 / 13:30 / 22:00 会自动生成；也可以现在补一份。" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isToday) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAppend) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("现在补一份")
            }
        }
    }
}

@Composable
private fun SliceCard(
    slice: BriefSliceEntity,
    expanded: Boolean,
    brief: Brief?,
    onToggle: () -> Unit,
    onRetry: () -> Unit,
) {
    val failed = slice.state == "FAILED"
    val skipped = slice.state == "SKIPPED"
    val ok = slice.state == "OK"
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                ok -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            },
        ),
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).background(
                        color = when {
                            failed -> MaterialTheme.colorScheme.error
                            skipped -> MaterialTheme.colorScheme.outline
                            slice.readAt == null -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    clock(slice.toTs),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    triggerLabel(slice.trigger),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (ok && slice.newCount > 0) {
                    Text("新增 ${slice.newCount}", style = MaterialTheme.typography.labelSmall)
                }
                if (failed) Text("失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                if (skipped) Text("跳过", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                "${clock(slice.fromTs)}–${clock(slice.toTs)} · ${slice.tokensIn + slice.tokensOut} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
            if (failed) {
                Text(
                    slice.error ?: "生成失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
                TextButton(onClick = onRetry, modifier = Modifier.padding(start = 8.dp)) { Text("重试") }
            }
            if (expanded && brief != null) {
                Spacer(Modifier.height(8.dp))
                SliceBrief(brief)
            }
            if (expanded && ok && brief == null) {
                Text(
                    "这一条的内容解析失败，可重试或查看通知里的纯文本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SliceBrief(brief: Brief) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Stat("要点", brief.wechatPoints.size)
            Stat("必做", brief.memoStatus.pendingDaily.size)
            Stat("待办", brief.memoStatus.pendingTemp.size)
            Stat("推荐", brief.recommendations.size)
        }
        if (brief.wechatPoints.isNotEmpty()) {
            Text("消息要点", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            brief.wechatPoints.forEach { p ->
                Text(
                    "· ${p.conv.ifBlank { "未标注会话" }}：${p.point}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (brief.plan.timeline.isNotEmpty()) {
            Text("时间线", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            brief.plan.timeline.forEach { t ->
                Text("${t.time} · ${t.title}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (brief.recommendations.isNotEmpty()) {
            Text("智能推荐", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            brief.recommendations.forEach { r ->
                Text("· ${r.text}（${r.reason}）", style = MaterialTheme.typography.bodySmall)
            }
        }
        brief.tomorrow?.let { tm ->
            if (tm.todos.isNotEmpty()) {
                Text("明天", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                tm.todos.forEach { Text("· ${it.text}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (brief.risks.isNotEmpty()) {
            Text("风险", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            brief.risks.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun summaryOf(slices: List<BriefSliceEntity>): String {
    val ok = slices.filter { it.state == "OK" }
    if (ok.isEmpty()) return ""
    val points = ok.sumOf { it.newCount }
    return "共 ${ok.size} 条片段 · 新增 $points 项"
}

private fun triggerLabel(trigger: String): String = when (trigger) {
    "MORNING" -> "自动 · 上午"
    "AFTERNOON" -> "自动 · 下午"
    "EVENING" -> "自动 · 晚间"
    else -> "手动"
}

private fun clock(ts: Long): String =
    java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
        .toLocalTime().toString().take(5)

private fun nextAutoText(): String {
    val now = java.time.LocalTime.now()
    val slots = listOf(10 to 0, 13 to 30, 22 to 0).map { java.time.LocalTime.of(it.first, it.second) }
    val next = slots.firstOrNull { it.isAfter(now) } ?: slots.first()
    val prefix = if (next.isAfter(now)) "" else "明天 "
    return "$prefix${"%02d:%02d".format(next.hour, next.minute)}"
}