package com.yanfei.r90alarm

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题。
 *
 * 暗色下刻意不用纯黑 / 纯白：
 *   - 背景 #000000 在 OLED 上滚动会有拖影感，用 #121212
 *   - 主时间用纯白太刺眼（这是睡前用的 App），用 #E6E1E5
 */
private val R90DarkColors = darkColorScheme(
    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1B1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
)

private val R90LightColors = lightColorScheme()

@Composable
fun R90Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) R90DarkColors else R90LightColors,
        content = content,
    )
}
