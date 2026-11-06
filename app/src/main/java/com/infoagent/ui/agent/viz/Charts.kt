package com.infoagent.ui.agent.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infoagent.data.repo.HourPoint
import com.infoagent.data.repo.PeerVolume
import com.infoagent.ui.theme.AiGradientEnd
import com.infoagent.ui.theme.AiGradientStart
import com.infoagent.ui.theme.SuccessGreen
import com.infoagent.ui.theme.WarnAmber

/* ------------------------------------------------------------------------------------------
 * 简报可视化图表集（纯 Compose Canvas 手绘，不引入第三方图表库）
 *  - TrendChart   曲线图：今日 vs 昨日 24 小时消息密度
 *  - DonutRing    环形图：今日必做完成率
 *  - ScheduleBand 时间带：关键时间点在一天中的位置
 *  - PeerBars     条形：会话热度 Top N
 * 所有文字用 TextMeasurer 绘制，颜色全部取主题色，深浅色主题都能看。
 * ------------------------------------------------------------------------------------------ */

private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        val midX = (prev.x + cur.x) / 2f
        val midY = (prev.y + cur.y) / 2f
        path.quadraticTo(prev.x, prev.y, midX, midY)
    }
    path.lineTo(points.last().x, points.last().y)
    return path
}

/** 今日 / 昨日消息密度曲线（面积 + 平滑折线 + 峰值标注） */
@Composable
fun TrendChart(
    today: List<HourPoint>,
    yesterday: List<HourPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
) {
    val cs = MaterialTheme.colorScheme
    val lineColor = cs.primary
    val prevColor = cs.onSurfaceVariant.copy(alpha = 0.40f)
    val gridColor = cs.outline.copy(alpha = 0.55f)
    val labelStyle = TextStyle(fontSize = 9.sp, color = cs.onSurfaceVariant)
    val peakStyle = TextStyle(fontSize = 10.sp, color = lineColor, fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()

    if (today.isEmpty()) {
        Text("暂无数据", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        return
    }

    Canvas(modifier.fillMaxWidth().height(height)) {
        val top = 12.dp.toPx()
        val bottom = size.height - 14.dp.toPx()
        val w = size.width
        val h = (bottom - top).coerceAtLeast(1f)
        val maxV = maxOf(
            today.maxOfOrNull { it.count } ?: 0,
            yesterday.maxOfOrNull { it.count } ?: 0,
            1,
        )
        fun x(i: Int) = w * i / 23f
        fun y(v: Int) = bottom - h * (v.toFloat() / maxV.toFloat())

        // 背景网格（3 条）
        for (k in 0..3) {
            val yy = top + h * k / 3f
            drawLine(gridColor, Offset(0f, yy), Offset(w, yy), 1f)
        }

        // 昨日：虚线
        val prevPts = yesterday.mapIndexed { i, p -> Offset(x(i), y(p.count)) }
        if (prevPts.size > 1) {
            drawPath(
                smoothPath(prevPts),
                prevColor,
                style = Stroke(
                    width = 1.4.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 7f)),
                ),
            )
        }

        // 今日：面积 + 曲线
        val pts = today.mapIndexed { i, p -> Offset(x(i), y(p.count)) }
        if (pts.size > 1) {
            val line = smoothPath(pts)
            val area = Path().apply {
                addPath(line)
                lineTo(w, bottom)
                lineTo(0f, bottom)
                close()
            }
            drawPath(
                area,
                Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.30f), lineColor.copy(alpha = 0.02f)),
                    startY = top,
                    endY = bottom,
                ),
            )
            drawPath(line, lineColor, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
        }

        // 峰值：点 + 光晕 + 文案
        val peak = today.maxByOrNull { it.count }
        if (peak != null && peak.count > 0) {
            val c = Offset(x(peak.hour), y(peak.count))
            drawCircle(lineColor.copy(alpha = 0.16f), 9.dp.toPx(), c)
            drawCircle(lineColor, 3.4.dp.toPx(), c)
            val label = "%02d:00 · %d".format(peak.hour, peak.count)
            val layout = measurer.measure(
                label,
                style = peakStyle,
                maxLines = 1,
                constraints = Constraints(maxWidth = w.toInt().coerceAtLeast(1)),
            )
            val tx = (c.x - layout.size.width / 2f).coerceIn(0f, (w - layout.size.width).coerceAtLeast(0f))
            val ty = (c.y - layout.size.height - 8.dp.toPx()).coerceAtLeast(0f)
            drawText(layout, topLeft = Offset(tx, ty))
        }

        // 横轴整点刻度
        listOf(0, 6, 12, 18, 23).forEach { hour ->
            val layout = measurer.measure("$hour", style = labelStyle, maxLines = 1)
            val tx = (x(hour) - layout.size.width / 2f).coerceIn(0f, (w - layout.size.width).coerceAtLeast(0f))
            drawText(layout, topLeft = Offset(tx, bottom + 2.dp.toPx()))
        }
    }
}

/** 今日必做完成率环形图 */
@Composable
fun DonutRing(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    diameter: Dp = 62.dp,
) {
    val cs = MaterialTheme.colorScheme
    val track = cs.outline.copy(alpha = 0.6f)
    val color = when {
        total <= 0 -> cs.onSurfaceVariant
        done >= total -> SuccessGreen
        done == 0 -> WarnAmber
        else -> cs.primary
    }
    val frac = if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
            val stroke = 6.5.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (frac > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * frac,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (total > 0) "$done/$total" else "—",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
            )
            Text(
                text = "必做",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一天的时间带：0–24 时轴 + 关键时间点（must=主色，maybe=次级色）+ 「现在」指示线。
 * 只画位置，文字列表在下方单独渲染，避免手机上标签互相压字。
 */
@Composable
fun ScheduleBand(
    slots: List<ScheduleSlot>,
    nowMinutes: Int?,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
) {
    val cs = MaterialTheme.colorScheme
    val axisColor = cs.outline.copy(alpha = 0.7f)
    val labelStyle = TextStyle(fontSize = 9.sp, color = cs.onSurfaceVariant)
    val mustColor = cs.primary
    val maybeColor = cs.secondary.copy(alpha = 0.75f)
    val nowColor = cs.error
    val measurer = rememberTextMeasurer()

    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val axisY = size.height - 14.dp.toPx()
        val laneGap = 9.dp.toPx()

        // 轴
        drawLine(axisColor, Offset(0f, axisY), Offset(w, axisY), 1.2.dp.toPx())

        // 刻度（每 3 小时）
        for (hour in 0..24 step 3) {
            val xx = w * hour / 24f
            drawLine(axisColor, Offset(xx, axisY), Offset(xx, axisY + 3.dp.toPx()), 1f)
            if (hour % 6 == 0) {
                val layout = measurer.measure("%02d".format(hour), style = labelStyle, maxLines = 1)
                val tx = (xx - layout.size.width / 2f).coerceIn(0f, (w - layout.size.width).coerceAtLeast(0f))
                drawText(layout, topLeft = Offset(tx, axisY + 4.dp.toPx()))
            }
        }

        // 事件点（同一小时内的点向上错层，避免重叠）
        slots.sortedBy { it.minutes }.forEachIndexed { index, slot ->
            val xx = w * slot.minutes / 1440f
            val lane = index % 3
            val yy = axisY - 6.dp.toPx() - laneGap * lane
            val color = if (slot.kind == "must") mustColor else maybeColor
            drawLine(color.copy(alpha = 0.35f), Offset(xx, axisY), Offset(xx, yy + 3.dp.toPx()), 1.2.dp.toPx())
            drawCircle(color, 3.2.dp.toPx(), Offset(xx, yy))
        }

        // 现在
        if (nowMinutes != null && nowMinutes in 0..1440) {
            val xx = w * nowMinutes / 1440f
            drawLine(
                nowColor.copy(alpha = 0.85f),
                Offset(xx, 2.dp.toPx()),
                Offset(xx, axisY),
                1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)),
            )
            drawCircle(nowColor, 2.6.dp.toPx(), Offset(xx, 2.dp.toPx()))
        }
    }
}

/** 会话热度条（Top N） */
@Composable
fun PeerBars(peers: List<PeerVolume>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    if (peers.isEmpty()) {
        Text("今天还没有消息", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        return
    }
    val max = (peers.maxOfOrNull { it.count } ?: 1).coerceAtLeast(1)
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        peers.forEach { peer ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = peer.peer.ifBlank { "未标注" },
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(76.dp),
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(cs.outline.copy(alpha = 0.45f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(peer.count.toFloat() / max.toFloat())
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(Brush.horizontalGradient(listOf(AiGradientStart, AiGradientEnd))),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${peer.count}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.width(26.dp),
                )
            }
        }
    }
}

/** 曲线图图例（今日实线 / 昨日虚线） */
@Composable
fun TrendLegend(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        LegendDot(cs.primary, "今天")
        Spacer(Modifier.width(12.dp))
        LegendDot(cs.onSurfaceVariant.copy(alpha = 0.45f), "昨天")
        Spacer(Modifier.weight(1f))
        Text(
            "条/小时",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(end = 2.dp),
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}