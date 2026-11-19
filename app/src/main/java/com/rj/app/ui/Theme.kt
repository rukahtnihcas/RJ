package com.rj.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Amber = Color(0xFFFFB000)
val Pink = Color(0xFFFF4F81)
val Night = Color(0xFF0E0B16)
val Panel = Color(0xFF1A1326)

private val scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1B1200),
    secondary = Pink,
    onSecondary = Color.White,
    background = Night,
    onBackground = Color(0xFFF2ECF8),
    surface = Panel,
    onSurface = Color(0xFFF2ECF8),
    surfaceVariant = Color(0xFF261D36),
    onSurfaceVariant = Color(0xFFBDB2CC),
)

@Composable
fun RjTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
