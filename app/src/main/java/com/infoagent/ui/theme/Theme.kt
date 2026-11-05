package com.infoagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 计划 §5.1 设计系统色彩
val Primary = Color(0xFF4C5BFF)
val PrimaryDark = Color(0xFFAEB6FF)
val Secondary = Color(0xFF7C6DFF)
val SurfaceLight = Color(0xFFF7F8FC)
val SurfaceDark = Color(0xFF1A1B22)
val OutlineLight = Color(0xFFE7E9F2)
val OutlineDark = Color(0xFF2A2C36)
val WeChatGreen = Color(0xFF07C160)
val AiGradientStart = Color(0xFF4C5BFF)
val AiGradientEnd = Color(0xFF8A5CFF)
val ErrorRed = Color(0xFFE5484D)
val SuccessGreen = Color(0xFF2FBE6B)
val WarnAmber = Color(0xFFF5A623)

private val LightColors = lightColorScheme(
    primary = Primary,
    secondary = Secondary,
    surface = SurfaceLight,
    outline = OutlineLight,
    error = ErrorRed,
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    secondary = Secondary,
    surface = SurfaceDark,
    outline = OutlineDark,
    error = ErrorRed,
)

@Composable
fun InfoAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}