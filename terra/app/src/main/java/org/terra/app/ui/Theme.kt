package org.terra.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Field-journal palette: paper, ink and a few botanical pigments. */
data class Pigments(
    val paper: Color, val card: Color, val wash: Color, val ink: Color, val inkSoft: Color, val line: Color,
    val moss: Color, val ochre: Color, val rust: Color, val water: Color
)

private val Day = Pigments(
    paper = Color(0xFFF4ECD8), card = Color(0xFFFBF6E9), wash = Color(0xFFEADFC3), ink = Color(0xFF2C2A25),
    inkSoft = Color(0xFF6A6455), line = Color(0xFFCBBD9A), moss = Color(0xFF4F6B3A), ochre = Color(0xFF9A6B14),
    rust = Color(0xFFA4512E), water = Color(0xFF3F6F82)
)
private val Lamp = Pigments(
    paper = Color(0xFF1D1B17), card = Color(0xFF292621), wash = Color(0xFF34302A), ink = Color(0xFFEDE4CF),
    inkSoft = Color(0xFFB0A792), line = Color(0xFF4A453B), moss = Color(0xFFA3C086), ochre = Color(0xFFE0B45C),
    rust = Color(0xFFE08A66), water = Color(0xFF8DBDCF)
)

val LocalPigments = staticCompositionLocalOf { Day }
val pigments: Pigments @Composable get() = LocalPigments.current

private val serif = FontFamily.Serif
private val type = Typography(
    displaySmall = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = serif, fontStyle = FontStyle.Italic, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = serif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = serif, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = serif, fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp)
)

@Composable
fun TerraTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) Lamp else Day
    val scheme = if (dark) darkColorScheme(
        primary = p.moss, onPrimary = p.paper, secondary = p.ochre, onSecondary = p.paper, tertiary = p.rust,
        background = p.paper, onBackground = p.ink, surface = p.card, onSurface = p.ink, surfaceVariant = p.wash,
        onSurfaceVariant = p.inkSoft, outline = p.line, outlineVariant = p.line, error = p.rust,
        primaryContainer = p.wash, onPrimaryContainer = p.ink, secondaryContainer = p.wash, onSecondaryContainer = p.ink,
        surfaceContainer = p.card, surfaceContainerHigh = p.card, surfaceContainerHighest = p.wash, surfaceContainerLow = p.card
    ) else lightColorScheme(
        primary = p.moss, onPrimary = p.card, secondary = p.ochre, onSecondary = p.card, tertiary = p.rust,
        background = p.paper, onBackground = p.ink, surface = p.card, onSurface = p.ink, surfaceVariant = p.wash,
        onSurfaceVariant = p.inkSoft, outline = p.line, outlineVariant = p.line, error = p.rust,
        primaryContainer = p.wash, onPrimaryContainer = p.ink, secondaryContainer = p.wash, onSecondaryContainer = p.ink,
        surfaceContainer = p.card, surfaceContainerHigh = p.card, surfaceContainerHighest = p.wash, surfaceContainerLow = p.card
    )
    CompositionLocalProvider(LocalPigments provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
