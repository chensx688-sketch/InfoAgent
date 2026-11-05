package com.infoagent.ui.messages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.infoagent.data.db.WechatMessageEntity
import com.infoagent.ui.theme.WeChatGreen
import com.infoagent.ui.util.TimeFormat
import kotlinx.coroutines.launch

/**
 * 消息页（FR-03）：采集状态卡 + 会话列表（头像色块/今日条数/今日要点）→ 消息流
 * （日期分隔、收发气泡、撤回虚化、长按转备忘/复制/删除）+ 搜索 + 手动导入。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MessagesScreen(vm: MessagesViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val conversations by vm.conversations.collectAsState(initial = emptyList())
    val capture by vm.capture.collectAsState()
    val convPoints by vm.convPoints.collectAsState()
    val focusPeers by vm.focusPeers.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.refreshCaptureState()
        vm.refreshBriefPoints()
        vm.ensureCaptureBound()
        // 页面可见时每 5 秒刷新一次真实绑定状态与今日入库数
        while (true) {
            kotlinx.coroutines.delay(5_000)
            vm.refreshCaptureState()
        }
    }

    var granted by remember { mutableStateOf(vm.permissionGranted()) }
    var selectedPeer by rememberSaveable { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<WechatMessageEntity>>(emptyList()) }
    var importOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    val selectedName = conversations.firstOrNull { it.peerId == selectedPeer }?.name ?: selectedPeer.orEmpty()

    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = !granted, enter = fadeIn(), exit = fadeOut()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("还没开启通知使用权", fontWeight = FontWeight.SemiBold)
                    Text(
                        "开启后微信/QQ 新消息会自动静默入库，不会打扰你。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { com.infoagent.capture.NotificationAccess.openSettings(context) }) {
                            Text("去开启")
                        }
                        TextButton(onClick = { granted = vm.permissionGranted() }) { Text("已开启，刷新") }
                    }
                }
            }
        }

        CaptureStatusCard(
            permission = capture.permission,
            connected = capture.serviceBound,
            rebinding = capture.rebinding,
            todayCount = capture.todayCount,
            lastIngestAt = capture.lastIngestAt,
            lastOutcome = capture.lastOutcome,
            onRebind = { vm.rebindCapture() },
            onOpenSettings = { com.infoagent.capture.NotificationAccess.openSettings(context) },
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (selectedPeer != null) selectedName else "全部会话",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = {
                searchOpen = !searchOpen
                if (!searchOpen) {
                    query = ""
                    searchResults = emptyList()
                }
            }) { Icon(Icons.Outlined.Search, contentDescription = "搜索") }
            IconButton(onClick = { importOpen = true }) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = "手动导入")
            }
        }

        AnimatedVisibility(visible = searchOpen, enter = fadeIn() + slideInVertically { -it / 3 }) {
            OutlinedTextField(
                value = query,
                onValueChange = { q ->
                    query = q
                    scope.launch { searchResults = vm.search(q) }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                singleLine = true,
                placeholder = { Text("搜索消息内容（支持中文）") },
            )
        }

        toast?.let {
            Surface(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(it, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        when {
            searchOpen && query.isNotBlank() -> SearchResults(
                results = searchResults,
                onOpen = { m ->
                    selectedPeer = m.peerId
                    searchOpen = false
                    query = ""
                    searchResults = emptyList()
                },
            )
            selectedPeer != null -> MessageDetail(
                vm = vm,
                peerId = selectedPeer!!,
                peerName = selectedName,
                point = convPoints[selectedPeer!!],
                onBack = { selectedPeer = null },
                onToast = { toast = it },
                onCopy = { clipboard.setText(AnnotatedString(it)) },
            )
            conversations.isEmpty() -> EmptyConversations()
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Text(
                        "共 ${conversations.size} 个会话",
                        Modifier.padding(20.dp, 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(conversations, key = { it.peerId }) { conv ->
                    ConversationRow(
                        conv = conv,
                        point = convPoints[conv.peerId],
                        focused = conv.peerId in focusPeers,
                        modifier = Modifier.animateItem(),
                        onToggleFocus = { vm.toggleFocus(conv.peerId) },
                        onToggleGroup = { vm.setGroup(conv.peerId, !conv.isGroup) },
                    ) { selectedPeer = conv.peerId }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (importOpen) {
        ImportDialog(
            onDismiss = { importOpen = false },
            onImport = { text ->
                vm.importText(text) { n ->
                    toast = if (n > 0) "已导入 $n 条消息" else "没有解析到有效消息（格式：名字: 内容）"
                    importOpen = false
                }
            },
        )
    }
}

@Composable
private fun CaptureStatusCard(
    permission: Boolean,
    connected: Boolean,
    rebinding: Boolean,
    todayCount: Long,
    lastIngestAt: Long,
    lastOutcome: String,
    onRebind: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(
                            when {
                                !permission -> MaterialTheme.colorScheme.error
                                connected -> Color(0xFF2FBE6B)
                                else -> MaterialTheme.colorScheme.error
                            },
                            RoundedCornerShape(50),
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !permission -> "采集：未授权"
                        connected -> "采集：监听在线"
                        else -> "采集：监听已断开"
                    },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "今日入库 $todayCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                buildString {
                    append("最近入库 ")
                    append(if (lastIngestAt > 0L) TimeFormat.listTime(lastIngestAt) else "—")
                    append("　事件 ")
                    append(lastOutcome.ifBlank { "—" })
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = onRebind, enabled = !rebinding) {
                    Text(if (rebinding) "重连中…" else "立即重连")
                }
                TextButton(onClick = onOpenSettings) { Text("通知使用权设置") }
            }
            if (permission && !connected && !rebinding) {
                Text(
                    "若点「立即重连」仍无效：到「通知使用权设置」里把本应用关掉再打开即可恢复。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EmptyConversations() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.ContentPaste,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("还没有消息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "开启通知使用权后，微信/QQ 新消息会自动静默进来；\n也可以点右上角「导入」粘贴一段聊天记录。",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 头像色块：按名字哈希取稳定的六个色之一 */
private fun avatarColor(name: String): Color {
    val palette = listOf(
        Color(0xFF4C5BFF), Color(0xFF7C6DFF), Color(0xFF07C160),
        Color(0xFFF5A623), Color(0xFFE5484D), Color(0xFF2FBE6B),
    )
    val idx = (name.fold(0) { acc, c -> acc * 31 + c.code } and Int.MAX_VALUE) % palette.size
    return palette[idx]
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conv: ConversationUi,
    point: String?,
    focused: Boolean,
    modifier: Modifier = Modifier,
    onToggleFocus: () -> Unit,
    onToggleGroup: () -> Unit,
    onClick: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val color = avatarColor(conv.name)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(conv.name.take(1), color = color, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        conv.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (conv.isGroup) {
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.14f)) {
                            Text(
                                "群",
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (conv.count > 0) {
                        Text(
                            "${conv.count} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    TimeFormat.listTime(conv.lastTs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!point.isNullOrBlank()) {
                    Text(
                        "要点：$point",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (focused) "取消关注" else "关注此会话（简报优先）") },
                    onClick = { menu = false; onToggleFocus() },
                )
                DropdownMenuItem(
                    text = { Text(if (conv.isGroup) "标记为单聊" else "标记为群聊") },
                    onClick = { menu = false; onToggleGroup() },
                )
            }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageDetail(
    vm: MessagesViewModel,
    peerId: String,
    peerName: String,
    point: String?,
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val messages by remember(peerId) { vm.messages(peerId) }.collectAsState(initial = emptyList())
    val color = avatarColor(peerName)

    // 反向列表：在消息与更早消息跨天时插入日期分隔
    val rows: List<Any> = remember(messages) {
        val reversed = messages.asReversed()
        val list = mutableListOf<Any>()
        reversed.forEachIndexed { i, m ->
            list.add(m)
            val older = reversed.getOrNull(i + 1)
            if (older == null || TimeFormat.dayLabel(older.ts) != TimeFormat.dayLabel(m.ts)) {
                list.add(TimeFormat.dayLabel(m.ts))
            }
        }
        list
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Box(
                Modifier.size(28.dp).background(color.copy(alpha = 0.2f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(peerName.take(1), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.width(8.dp))
            Text(peerName, style = MaterialTheme.typography.titleSmall)
        }
        if (!point.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(
                    "今日要点：$point",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize(), reverseLayout = true) {
            items(rows) { row ->
                when (row) {
                    is String -> DaySeparator(row)
                    is WechatMessageEntity -> MessageBubble(
                        message = row,
                        onMemo = {
                            vm.turnIntoMemo(row)
                            onToast("已转为临时事件备忘")
                        },
                        onCopy = { onCopy(row.content); onToast("已复制") },
                        onDelete = { vm.deleteMessage(row.id); onToast("已删除（回收站可恢复）") },
                    )
                }
            }
            // C11：分页加载更早的消息（反向列表里放在末尾 = 视觉最上方）
            if (messages.size >= vm.pageSize(peerId) && vm.pageSize(peerId) < MessagesViewModel.MAX_PAGE_SIZE) {
                item {
                    TextButton(
                        onClick = { vm.loadMore(peerId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("加载更早的消息") }
                }
            }
        }
    }
}

@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: WechatMessageEntity,
    onMemo: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val recalled = message.recalled == 1L
    val isOut = message.direction == "OUT"
    val bubbleColor = if (isOut) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val shape = if (isOut) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (isOut) Alignment.End else Alignment.Start) {
            Text(
                buildString {
                    if (message.sender.isNotEmpty() && message.sender != message.peerName) append("${message.sender} · ")
                    append(TimeFormat.clock(message.ts))
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                Surface(
                    shape = shape,
                    color = if (recalled) bubbleColor.copy(alpha = 0.4f) else bubbleColor,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .combinedClickable(onClick = {}, onLongClick = { menu = true }),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (recalled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (recalled) {
                            Text(
                                "（已撤回，不进简报）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (message.msgType != "text") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                ) {
                                    Text(
                                        message.msgType,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (message.appSource == "qq") "QQ" else "微信",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WeChatGreen,
                                )
                            }
                        }
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("转备忘录") }, onClick = { menu = false; onMemo() })
                    DropdownMenuItem(text = { Text("复制") }, onClick = { menu = false; onCopy() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResults(results: List<WechatMessageEntity>, onOpen: (WechatMessageEntity) -> Unit) {
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text("没有匹配的消息", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "试试更短的关键词，中文按两字一组检索",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { it.id }) { m ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 3.dp)
                    .animateItem()
                    .combinedClickable(onClick = { onOpen(m) }, onLongClick = {}),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${m.peerName} · ${TimeFormat.listTime(m.ts)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(m.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动导入消息") },
        text = {
            Column {
                Text(
                    "每行一条，格式「名字: 内容」；没有名字前缀的行会归到上一条的会话。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    placeholder = { Text("王总: 明天下午三点开会\n妈妈: 周末回家吃饭") },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onImport(text) }) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}