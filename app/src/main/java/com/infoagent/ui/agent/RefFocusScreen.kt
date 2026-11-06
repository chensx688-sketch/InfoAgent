package com.infoagent.ui.agent

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.infoagent.data.repo.RefFocus
import com.infoagent.data.repo.RefRow
import java.time.Instant
import java.time.ZoneId

/**
 * 要点 → 原话：把被引用的消息连同前后文一起摊开，并把「就是这几句」高亮出来。
 *
 * 设计取向：
 * - 不做「消息详情弹窗」，直接一页，按时间顺读——上下文关系比单条消息更重要；
 * - 被引用的消息用左侧色条 + 淡底 + 「依据」标签三重标记，扫一眼就能找到；
 * - 首屏自动滚到第一条依据，不用用户自己翻。
 */
@Composable
fun RefFocusScreen(
    focus: RefFocus,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回简报")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    focus.title.ifBlank { "原文" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    if (focus.focusedCount > 0) {
                        "这段简报引用了其中 ${focus.focusedCount} 条"
                    } else {
                        "同一时段的会话记录"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()

        focus.note?.let {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        if (focus.isEmpty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "没有可显示的消息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        val state = rememberLazyListState()
        val firstFocus = focus.rows.indexOfFirst { it.focused }
        LaunchedEffect(focus.rows.size, firstFocus) {
            if (firstFocus > 0) state.scrollToItem((firstFocus - 1).coerceAtLeast(0))
        }

        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(focus.rows, key = { it.msg.id }) { row -> RefMessageRow(row) }
        }
    }
}

@Composable
private fun RefMessageRow(row: RefRow) {
    val msg = row.msg
    val accent = MaterialTheme.colorScheme.primary
    val bg = if (row.focused) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(if (row.focused) 3.dp else 1.dp)
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .background(if (row.focused) accent else MaterialTheme.colorScheme.outlineVariant),
        )
        Surface(
            color = bg,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        msg.sender.ifBlank { if (msg.direction == "OUT") "我" else "对方" },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (row.focused) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        timeText(msg.ts),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (msg.recalled == 1L) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "已撤回",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (row.focused) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            "简报依据",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    msg.content.ifBlank { "（非文本消息）" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun timeText(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        .let { "%02d-%02d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }