package earth.ripple.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import earth.ripple.core.Rubric
import java.io.File

/**
 * The image people post. It has to carry the whole idea on its own, to someone who has never
 * heard of the app: the thing, its shape, the one fact, and what to do instead.
 */
object ShareCard {
    private const val W = 1080
    private const val H = 1350

    fun render(context: Context, entry: Entry, versus: Entry? = null): Bitmap {
        val image = ImageBitmap(W, H)
        val density = Density(1f)
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
        val a = entry.assessment
        val photo = entry.thumb.takeIf { it.isNotEmpty() && File(it).exists() }?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() }

        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(W.toFloat(), H.toFloat())) {
            drawRect(Ink.Bg)
            if (photo != null) {
                // Centre-crop the photo across the top, then melt it into the background.
                val bandH = 470
                val scale = maxOf(W / photo.width.toFloat(), bandH / photo.height.toFloat())
                val sw = (W / scale).toInt().coerceAtMost(photo.width); val sh = (bandH / scale).toInt().coerceAtMost(photo.height)
                drawImage(photo, IntOffset((photo.width - sw) / 2, (photo.height - sh) / 2), IntSize(sw, sh), IntOffset.Zero, IntSize(W, bandH))
                drawRect(Brush.verticalGradient(listOf(Ink.Bg.copy(alpha = 0.15f), Ink.Bg), 0f, bandH.toFloat()), size = Size(W.toFloat(), bandH + 2f))
            }
            fun text(s: String, x: Float, y: Float, style: TextStyle, width: Int = W - 140, lines: Int = 1): Int {
                val layout = measurer.measure(s, style, overflow = TextOverflow.Ellipsis, maxLines = lines, constraints = Constraints(maxWidth = width))
                drawText(layout, topLeft = Offset(x, y))
                return layout.size.height
            }
            val title = if (versus != null) "${a.emoji} ${a.name}  vs  ${versus.assessment.emoji} ${versus.assessment.name}" else "${a.emoji}  ${a.name}"
            var y = if (photo != null) 300f else 90f
            y += text(title, 70f, y, TextStyle(color = Ink.Text, fontSize = 64.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, lineHeight = 72.sp), lines = 2)
            y += 8f + text(Rubric.verdict(a.average).uppercase(), 74f, y + 8f, TextStyle(color = Ink.score(a.average), fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp))

            val chart = if (photo != null) 520f else 620f
            translate((W - chart) / 2f, y + 6f) {
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, drawContext.canvas, Size(chart, chart)) {
                    drawRadar(a.values, versus?.assessment?.values, true, measurer)
                }
            }
            y += chart + 14f

            y += 18f + text(a.headline, 70f, y, TextStyle(color = Ink.Text, fontSize = 38.sp, lineHeight = 50.sp, fontFamily = FontFamily.Serif), lines = 4)
            val swap = if (versus == null) a.alternatives.firstOrNull() else null
            if (swap != null && y < H - 200) {
                text((if (a.average > 0.4) "Look after it:  " else "Better:  ") + swap.name, 70f, y + 4f, TextStyle(color = Ink.Leaf, fontSize = 32.sp, fontWeight = FontWeight.SemiBold), lines = 1)
            }

            drawRoundRect(Ink.Line, Offset(70f, H - 112f), Size(W - 140f, 2f), CornerRadius(1f))
            text("Ripple", 70f, H - 92f, TextStyle(color = Ink.Text, fontSize = 36.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold))
            text("Point your camera at anything. See what it does to the world.", 200f, H - 82f, TextStyle(color = Ink.Muted, fontSize = 25.sp), width = W - 270)
        }
        return image.asAndroidBitmap()
    }

    fun share(context: Context, entry: Entry, versus: Entry? = null) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "ripple.png")
        file.outputStream().use { render(context, entry, versus).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val a = entry.assessment
        val caption = if (versus != null) "${a.name} vs ${versus.assessment.name}: which one costs the world more? Seen through Ripple."
        else "${a.headline}\n\nSeen through Ripple."
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share this discovery").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
