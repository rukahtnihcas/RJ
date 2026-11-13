package earth.ripple.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The palette. Green and red carry meaning (good / harm) and are used for nothing else. */
object Ink {
    val Bg = Color(0xFF0C1512)
    val Surface = Color(0xFF14211C)
    val Raised = Color(0xFF1D2E27)
    val Line = Color(0xFF2B4037)
    val Text = Color(0xFFF2EEE3)
    val Muted = Color(0xFF9DB0A6)
    val Leaf = Color(0xFF45E0A0)
    val Ember = Color(0xFFFF6A4D)
    val Zero = Color(0xFFFFC857)

    /** Colour for a score: ember at −5, through a neutral cream at 0, to leaf at +5. */
    fun score(v: Double): Color = when {
        v < 0 -> lerp(Text, Ember, (-v / 3.0).coerceIn(0.25, 1.0).toFloat())
        v > 0 -> lerp(Text, Leaf, (v / 3.0).coerceIn(0.25, 1.0).toFloat())
        else -> Zero
    }
}

private val Display = FontFamily.Serif

private val type = Typography().let { t ->
    t.copy(
        displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
        headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp),
        titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun RippleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Leaf, onPrimary = Ink.Bg, secondary = Ink.Zero, onSecondary = Ink.Bg,
            background = Ink.Bg, onBackground = Ink.Text, surface = Ink.Surface, onSurface = Ink.Text,
            surfaceVariant = Ink.Raised, onSurfaceVariant = Ink.Muted, outline = Ink.Line, error = Ink.Ember,
        ),
        typography = type,
        content = content,
    )
}
