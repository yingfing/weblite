package com.yingfing.weblite.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * TV 场景：不用动态取色/模糊，用固定的深色方案（默认）或浅色方案。
 * 颜色与“拾步”同系：主色 1B6EF3。
 */
private val Dark = darkColorScheme(
    primary = Color(0xFF6EA6FF),
    background = Color(0xFF10131A),
    surface = Color(0xFF171B24),
    surfaceVariant = Color(0xFF232836),
    onBackground = Color(0xFFE3E6EC),
    onSurface = Color(0xFFE3E6EC),
    onSurfaceVariant = Color(0xFFA9B1C2),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1B6EF3),
)

@Composable
fun WebLiteTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        content = content,
    )
}
