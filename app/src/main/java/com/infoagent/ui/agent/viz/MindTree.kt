package com.infoagent.ui.agent.viz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 树状图：今日（根） → 会话（分支） → 要点（叶子）。
 *
 * 为什么用树：简报原本是平铺的要点列表，读不出「哪几条是同一个会话说的」。
 * 树把「会话」作为一级归属，人能按「谁在说」回忆，而不是按句子回忆。
 *
 * 全部用 Canvas + TextMeasurer 绘制，避免 Compose 布局与连线坐标不同步。
 */
@Composable
fun MindTree(
    topics: List<LensTopic>,
    modifier: Modifier = Modifier,
    maxPointsPerTopic: Int = 3,
) {
    if (topics.isEmpty()) {
        Text(
            text = "本窗口没有可归纳的消息要点",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val cs = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val convStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
    val leafStyle = TextStyle(fontSize = 10.5.sp, color = cs.onSurfaceVariant)
    val rootStyle = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = cs.onPrimary)

    val convBg = cs.primary.copy(alpha = 0.12f)
    val connector = cs.outline
    val rootBg = cs.primary
    val keyDot = cs.secondary
    val plainDot = cs.outline

    // 几何常量（dp 值，绘制时转 px）
    val rootW = 56f
    val convX = 66f
    val convW = 86f
    val convH = 21f
    val leafGap = 17f
    val blockGap = 7f
    val leafX = convX + convW + 12f

    val rows = topics.sumOf { it.points.size.coerceAtMost(maxPointsPerTopic).coerceAtLeast(1) }
    val totalH = topics.size * (convH + blockGap) + rows * leafGap + 4f

    Canvas(modifier.fillMaxWidth().height(totalH.dp)) {
        fun dp(v: Float) = v.dp.toPx()
        val w = size.width
        val leafW = (w - dp(leafX)).coerceAtLeast(dp(40f))

        var y = 0f
        val convAnchors = mutableListOf<Offset>()

        topics.forEach { topic ->
            val shown = topic.points.take(maxPointsPerTopic).ifEmpty { listOf(LensPoint("（无要点）", null)) }

            // 会话节点
            drawRoundRect(
                color = convBg,
                topLeft = Offset(dp(convX), y),
                size = Size(dp(convW), dp(convH)),
                cornerRadius = CornerRadius(dp(7f), dp(7f)),
            )
            val name = measurer.measure(
                topic.conv,
                style = convStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(
                    maxWidth = (dp(convW) - dp(if (topic.count > 1) 20f else 8f)).toInt().coerceAtLeast(1),
                ),
            )
            drawText(
                name,
                topLeft = Offset(dp(convX) + dp(4f), y + (dp(convH) - name.size.height) / 2f),
            )
            // 会话条数徽标（右侧小字）
            if (topic.count > 1) {
                val badge = measurer.measure(
                    "+${topic.count}",
                    style = TextStyle(fontSize = 8.5.sp, color = cs.primary),
                    maxLines = 1,
                )
                drawText(badge, topLeft = Offset(dp(convX + convW) - badge.size.width - dp(3f), y + dp(3f)))
            }

            convAnchors += Offset(dp(convX), y + dp(convH) / 2f)

            // 叶子
            var ly = y + dp(convH)
            shown.forEach { point ->
                val cy = ly + dp(leafGap) / 2f
                val from = Offset(dp(convX + convW), y + dp(convH) / 2f)
                val to = Offset(dp(leafX) - dp(5f), cy)
                val path = Path().apply {
                    moveTo(from.x, from.y)
                    val mx = (from.x + to.x) / 2f
                    cubicTo(mx, from.y, mx, to.y, to.x, to.y)
                }
                drawPath(path, connector, style = Stroke(width = dp(0.9f), cap = StrokeCap.Round))
                drawCircle(if (!point.keyTime.isNullOrBlank()) keyDot else plainDot, dp(1.9f), to)

                val text = if (!point.keyTime.isNullOrBlank()) "⏰ ${point.text}" else point.text
                val layout = measurer.measure(
                    text,
                    style = leafStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = leafW.toInt().coerceAtLeast(1)),
                )
                drawText(layout, topLeft = Offset(dp(leafX) + dp(6f), cy - layout.size.height / 2f))
                ly += dp(leafGap)
            }

            y += dp(convH) + dp(leafGap) * shown.size.toFloat() + dp(blockGap)
        }

        // 根节点：画在所有会话的中间位置，再连到每个会话
        val rootCenterY = (convAnchors.firstOrNull()?.y ?: 0f).let { first ->
            val last = convAnchors.lastOrNull()?.y ?: first
            (first + last) / 2f
        }
        drawRoundRect(
            color = rootBg,
            topLeft = Offset(0f, rootCenterY - dp(11f)),
            size = Size(dp(rootW), dp(22f)),
            cornerRadius = CornerRadius(dp(11f), dp(11f)),
        )
        val rootLabel = measurer.measure("今日 ${topics.size}", style = rootStyle, maxLines = 1)
        if (rootLabel.size.width <= dp(rootW) - dp(8f)) {
            drawText(rootLabel, topLeft = Offset(dp(4f), rootCenterY - rootLabel.size.height / 2f))
        }
        convAnchors.forEach { anchor ->
            val path = Path().apply {
                moveTo(dp(rootW), rootCenterY)
                val mx = (dp(rootW) + anchor.x) / 2f
                cubicTo(mx, rootCenterY, mx, anchor.y, anchor.x, anchor.y)
            }
            drawPath(path, connector.copy(alpha = 0.55f), style = Stroke(width = dp(0.9f), cap = StrokeCap.Round))
        }
    }
}