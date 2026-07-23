package com.orbitstudio.capture.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val OrbitDarkScheme = darkColorScheme(
    primary = OrbitColors.accent,
    onPrimary = Color.White,
    background = OrbitColors.canvas,
    onBackground = OrbitColors.textPrimary,
    surface = OrbitColors.elevated,
    onSurface = OrbitColors.textPrimary,
    surfaceVariant = OrbitColors.elevated,
    outline = OrbitColors.hairline20,
    error = OrbitColors.danger,
)

// Light scheme kept for Home/Review/Bundle/Done per DESIGN.md; Capture always forces dark.
private val OrbitLightScheme = lightColorScheme(
    primary = OrbitColors.accent,
    onPrimary = Color.White,
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1C1C1E),
    error = OrbitColors.danger,
)

@Composable
fun OrbitTheme(forceDark: Boolean = true, content: @Composable () -> Unit) {
    val dark = forceDark || isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) OrbitDarkScheme else OrbitLightScheme,
        typography = OrbitTypography,
        content = content,
    )
}
