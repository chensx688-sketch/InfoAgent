package com.infoagent.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.infoagent.agent.model.Brief
import com.infoagent.data.repo.DayMetrics
import com.infoagent.ui.agent.viz.BriefLens
import com.infoagent.ui.agent.viz.BriefLenser
import com.infoagent.ui.agent.viz.DonutRing
import com.infoagent.ui.agent.viz.LensHighlight
import com.infoagent.ui.agent.viz.LensTopic
import com.infoagent.ui.agent.viz.MindTree
import com.infoagent.ui.agent.viz.PeerBars
import com.infoagent.ui.agent.viz.ScheduleBand
import com.infoagent.ui.agent.viz.ScheduleSlot
import com.infoagent.ui.agent.viz.TrendChart
import com.infoagent.ui.agent.viz.TrendLegend

/**
 * 简报可视化主体（v5）。
 *
 * 阅读顺序刻意做成「结论 → 人 → 时间 → 数量 → 行动」：
 *   ⓿ 先看这 3 条  决策优先：没有时间就只读这一段（每条都带排序依据，不是黑箱）
 *   ① 谁在说      会话卡片（默认）/ 树状图（切换），一眼看出哪几条是同一个人的
 *   ② 什么时候    24h 时间带 + 垂直故事线（含 detail）
 *   ③ 消息节奏    曲线（今天 vs 昨天）+ 完成率环形 + 会话热度
 *   ④ 我要做什么  必做 / 临时 / 建议（可反馈）/ 风险
 *
 * 设计依据（吸收自成熟 digest 产品）：
 *   - Readwise Daily Review：先给「今天这几条」，再给全集，有进度感
 *   - Readwise/Feedly：分组 + 密度 + 折叠，避免一屏塞满
 *   - Arc Browse for Me / Perplexity：每条结论都挂来源，读者才知道凭什么
 *   - iOS/Android 通知摘要：默认折叠成一句，需要时展开
 */
@Composable
fun BriefRichView(
    brief: Brief,
    modifier: Modifier = Modifier,
    metrics: DayMetrics? = null,
    nowMinutes: Int? = null,
    onVote: ((Int, List<Long>, Boolean) -> Unit)? = null,
    /** 点「看原文」跳到那条消息（null=不支持跳转，例如 Preview） */
    onOpenRefs: ((List<Long>) -> Unit)? = null,
) {
    val lens: BriefLens = BriefLenser.build(brief, metrics)
    var treeMode by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OverviewBlock(lens, metrics)

        // ⚑ 怎么办：整份简报最该先看的一段（要回什么、什么时候、别漏什么）
        if (lens.advice.isNotEmpty()) {
            AdviceSection(lens.advice, onOpenRefs)
        }

        // ⚑ 按事项：合同/报价/招聘这类事可能散在几个会话里，按会话看会漏
        if (brief.topics.isNotEmpty()) {
            MatterBlock(brief.topics, onOpenRefs)
        }

        if (lens.highlights.isNotEmpty()) {
            HighlightBlock(lens.highlights)
        }

        if (lens.topics.isNotEmpty()) {
            SectionCard(
                index = "①",
                title = "谁在说",
                subtitle = if (treeMode) "会话 ${lens.topics.size} 个" else "${lens.counters.points} 条要点",
                action = {
                    TextButton(onClick = { treeMode = !treeMode }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Text(
                            if (treeMode) "看卡片" else "看关系图",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            ) {
                if (treeMode) {
                    MindTree(topics = lens.topics, maxPointsPerTopic = 3)
                } else {
                    TopicCards(lens.topics, maxTopics = 6, maxPoints = 3)
                    if (lens.topics.size > 6) {
                        Text(
                            "仅展示前 6 个会话，共 ${lens.topics.size} 个（切到关系图可看全部）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }

        if (lens.schedule.isNotEmpty() || brief.plan.note != null) {
            SectionCard(
                index = "②",
                title = "什么时候",
                subtitle = if (lens.schedule.isEmpty()) "本窗口没有明确时间" else "一天里的 ${lens.schedule.size} 个点",
            ) {
                ScheduleBand(slots = lens.schedule, nowMinutes = nowMinutes)
                if (lens.schedule.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    StoryLine(lens.schedule.take(8))
                }
                brief.plan.note?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        "备注：$it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        if (metrics != null) {
            SectionCard(
                index = "③",
                title = "消息节奏",
                subtitle = lens.notes.densityText,
            ) {
                TrendLegend()
                Spacer(Modifier.height(4.dp))
                TrendChart(today = metrics.todayHours, yesterday = metrics.yesterdayHours)
                Spacer(Modifier.height(10.dp))
                Text(
                    "会话热度",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                PeerBars(peers = metrics.topPeers)
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    MetaPill("今天 ${metrics.todayTotal} 条")
                    MetaPill(if (metrics.delta >= 0) "比昨天 +${metrics.delta}" else "比昨天 ${metrics.delta}")
                    MetaPill("必做 ${lens.notes.completionText.replace("必做完成 ", "")}")
                }
            }
        }

        SectionCard(
            index = "④",
            title = "我要做什么",
            subtitle = if (lens.counters.todos > 0) "${lens.counters.todos} 项待办" else "没有待办",
        ) {
            if (lens.counters.done + lens.counters.todos == 0) {
                Text(
                    "今天还没有建立备忘",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            lens.todos.take(10).forEach { TodoRow(it) }
            if (lens.notes.tomorrow.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("明天", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                lens.notes.tomorrow.take(5).forEach { text ->
                    Text(
                        "· $text",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (lens.recommendations.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("建议（可反馈）", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                lens.recommendations.take(5).forEachIndexed { index, rec ->
                    Column(Modifier.padding(top = 4.dp)) {
                        Text("· ${rec.text}", style = MaterialTheme.typography.bodySmall)
                        rec.time?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                "依据：$it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (onVote != null) {
                            Row {
                                TextButton(onClick = { onVote(index, rec.refs, true) }) {
                                    Text("👍 准", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { onVote(index, rec.refs, false) }) {
                                    Text("👎 不准", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (lens.risks.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "风险 / 缺口",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                )
                lens.risks.forEach { risk ->
                    Text(
                        "· $risk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (brief.warnings.isNotEmpty()) {
                Text(
                    "结构解析告警：${brief.warnings.joinToString("；")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * ⚑ 怎么办（brief_v2 的核心）：替用户把事情想周全。
 *
 * 每条三行信息：
 *   1) 对方在等你什么（ask）+ 什么时候得给（due）
 *   2) 可以直接复制发出去的回复草稿（say）——不做"建议你回复……"，直接给能发的话
 *   3) 容易被忽略的细节（watch）：时节/天气/礼数/称呼/时间窗/金额/风险
 * 颜色只表达一件事：这条的紧急性质（红=风险、蓝=要回话、黄=要准备、灰=别忘），
 * 不做装饰，避免"五颜六色但看不懂重点"。
 */
@Composable
private fun AdviceSection(advice: List<com.infoagent.agent.model.Advice>, onOpenRefs: ((List<Long>) -> Unit)?) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copiedIndex by remember { mutableStateOf(-1) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("怎么办", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                "回复草稿可直接复制 · 附带容易漏掉的细节",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        advice.forEachIndexed { index, item ->
            val accent = adviceAccent(item.kind)
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row {
                    // 左侧色条：一眼分类型
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(IntrinsicSize.Min)
                            .background(accent),
                    )
                    Column(Modifier.padding(12.dp).fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.who,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(6.dp))
                            SourceChip(kindLabel(item.kind), accent)
                            item.due?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.width(6.dp))
                                SourceChip(it, MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(item.ask, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)

                        item.say?.takeIf { it.isNotBlank() }?.let { say ->
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                color = accent.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        "可以这样回",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = accent,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(say, style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (copiedIndex == index) "已复制" else "复制",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = accent,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable {
                                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(say))
                                                    copiedIndex = index
                                                }
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }

                        item.why?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "为什么：" + it + (item.basis?.let { b -> "（依据：$b）" } ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (item.watch.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "容易被忽略",
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                fontWeight = FontWeight.SemiBold,
                            )
                            item.watch.forEach { w ->
                                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.Top) {
                                    Box(
                                        Modifier
                                            .padding(top = 5.dp)
                                            .size(5.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(accent),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(w, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        if (onOpenRefs != null && item.refs.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            RefChip(item.refs, onOpenRefs)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun adviceAccent(kind: String): Color = when (kind) {
    "risk" -> MaterialTheme.colorScheme.error
    "prepare" -> MaterialTheme.colorScheme.tertiary
    "remind" -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.primary
}

private fun kindLabel(kind: String): String = when (kind) {
    "risk" -> "有风险"
    "prepare" -> "要准备"
    "remind" -> "别忘"
    else -> "要回话"
}

/** ⓿ 先看这 3 条：带排序判据，扫一眼就能判断值不值得读 */
@Composable
private fun HighlightBlock(highlights: List<LensHighlight>) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("先看这 ${highlights.size} 条", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    "按「时间/动作/金额」排序",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            highlights.forEachIndexed { index, item ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .size(17.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            SourceChip(item.tag, MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                buildString {
                                    append(item.conv)
                                    item.keyTime?.let { append(" · $it") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** ① 卡片视图：一个会话一张卡，比树状图更容易逐条读 */
@Composable
private fun TopicCards(topics: List<LensTopic>, maxTopics: Int, maxPoints: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        topics.take(maxTopics).forEach { topic ->
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            topic.conv,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        SourceChip("${topic.count} 条", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                    topic.points.take(maxPoints).forEach { point ->
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                            Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                point.text,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            point.keyTime?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.width(6.dp))
                                SourceChip(it, MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (topic.points.size > maxPoints) {
                        Text(
                            "还有 ${topic.points.size - maxPoints} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** ② 垂直故事线：把时间点串成「今天的一天」，比横向独占一条轴更好读 */
@Composable
private fun StoryLine(slots: List<ScheduleSlot>) {
    val lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    Column {
        slots.forEachIndexed { index, slot ->
            Row(verticalAlignment = Alignment.Top) {
                // 左侧：时间 + 竖线与圆点
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(46.dp)) {
                    Text(
                        slot.clock,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (slot.kind == "must") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(10.dp)) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (slot.kind == "must") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                            ),
                    )
                    if (index != slots.lastIndex) {
                        Box(
                            Modifier
                                .width(1.5.dp)
                                .height(if (slot.detail.isNullOrBlank()) 16.dp else 30.dp)
                                .background(lineColor),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.padding(bottom = if (index == slots.lastIndex) 0.dp else 8.dp)) {
                    Text(slot.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    slot.detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewBlock(lens: BriefLens, metrics: DayMetrics?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        DonutRing(done = metrics?.dailyDone ?: lens.counters.done, total = metrics?.dailyTotal ?: lens.counters.total)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                lens.headline,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetaPill("${lens.counters.convs} 会话")
                MetaPill("${lens.counters.points} 要点")
                if (lens.counters.slots > 0) MetaPill("${lens.counters.slots} 时间点")
                if (lens.counters.todos > 0) MetaPill("${lens.counters.todos} 待办")
            }
        }
    }
}

@Composable
private fun SectionCard(
    index: String,
    title: String,
    subtitle: String?,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        index,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (action != null) {
                    action()
                } else {
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(150.dp),
                        )
                    }
                }
            }
            if (action != null && subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun MetaPill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun TodoRow(line: com.infoagent.ui.agent.viz.TodoLine) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            Modifier
                .size(13.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        line.time?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            if (line.origin == "DAILY") "必做" else "临时",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
/**
 * 「看原文」小胶囊：把结论和原话连起来。
 *
 * 为什么值得做：AI 说的话对不对，唯一能自证的方式就是让用户一眼点回原话。
 * 这里不做长列表，只一个入口 + 条数，点开才加载上下文（不预读、不占内存）。
 */
@Composable
private fun RefChip(refs: List<Long>, onOpenRefs: (List<Long>) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onOpenRefs(refs) }
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            "看原文",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "${refs.size} 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * ⚑ 按事项：合同 / 报价 / 招聘这类事往往散在几个会话里。
 *
 * 「谁在说」按人分，「按事项」按事分——两种切法都得有，
 * 否则用户要自己在脑子里把三条会话拼成一份合同的来龙去脉。
 */
@Composable
private fun MatterBlock(
    topics: List<com.infoagent.agent.model.TopicCluster>,
    onOpenRefs: ((List<Long>) -> Unit)?,
) {
    SectionCard(
        index = "⚑",
        title = "按事项",
        subtitle = "${topics.size} 件事 · 跨 ${topics.sumOf { it.convs.size }} 个会话",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            topics.take(6).forEach { t ->
                val accent = when (t.state) {
                    "risk" -> MaterialTheme.colorScheme.error
                    "done" -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.primary
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row {
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(IntrinsicSize.Min)
                                .background(accent),
                        )
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    t.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(6.dp))
                                SourceChip(
                                    when (t.state) {
                                        "risk" -> "有风险"
                                        "done" -> "已了结"
                                        else -> "进行中"
                                    },
                                    accent,
                                )
                                if (t.convs.isNotEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        t.convs.joinToString(" / "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            t.points.take(4).forEach { p ->
                                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.Top) {
                                    Box(
                                        Modifier
                                            .padding(top = 5.dp)
                                            .size(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(accent.copy(alpha = 0.7f)),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(p, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (onOpenRefs != null && t.refs.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                RefChip(t.refs, onOpenRefs)
                            }
                        }
                    }
                }
            }
            if (topics.size > 6) {
                Text(
                    "仅展示前 6 件事，共 ${topics.size} 件",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
