package earth.ripple.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.ripple.core.Dimension
import earth.ripple.core.Radar
import earth.ripple.core.Rubric

/**
 * The five-axis chart. The dashed amber ring is zero: a shape that stays inside it is taking from
 * the world, a shape that pushes outside it is giving back.
 *
 * @param ghost a second shape drawn faintly underneath, for comparing an alternative with the original.
 * @param labels draw the dimension glyph and signed score at each corner.
 */
@Composable
fun RadarChart(
    scores: List<Double>,
    modifier: Modifier = Modifier,
    ghost: List<Double>? = null,
    labels: Boolean = true,
    animateKey: Any? = scores,
) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(animateKey) {
        grow.snapTo(0f)
        grow.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
    }
    val measurer = rememberTextMeasurer()
    val description = "Impact chart. " + Dimension.entries.joinToString(", ") { "${it.label} ${Rubric.format(scores[it.ordinal])}" }
    Canvas(modifier.semantics { contentDescription = description }) {
        // Start every axis at zero and spring out to its score, so harm visibly caves in and benefit blooms.
        drawRadar(scores.map { it * grow.value }, ghost, labels, measurer, scores)
    }
}

fun DrawScope.drawRadar(
    shown: List<Double>,
    ghost: List<Double>?,
    labels: Boolean,
    measurer: TextMeasurer?,
    labelScores: List<Double> = shown,
) {
    val pad = if (labels) size.minDimension * 0.19f else size.minDimension * 0.04f
    val r = size.minDimension / 2f - pad
    val c = Offset(size.width / 2f, size.height / 2f + if (labels) r * 0.04f else 0f)
    fun at(axis: Int, radius: Double): Offset {
        val (x, y) = Radar.point(axis, radius)
        return Offset(c.x + (x * r).toFloat(), c.y + (y * r).toFloat())
    }
    fun ring(radius: Double) = Path().apply {
        for (i in 0 until 5) { val p = at(i, radius); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }
    fun shape(values: List<Double>) = Path().apply {
        values.forEachIndexed { i, v -> val p = at(i, Radar.radius(v)); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }

    val hair = (size.minDimension / 220f).coerceAtLeast(1f)
    // Field: benefit zone outside the zero ring, harm zone inside it.
    drawPath(ring(1.0), Ink.Leaf.copy(alpha = 0.07f))
    drawPath(ring(Radar.ZERO), Ink.Bg.copy(alpha = 0.55f))
    drawPath(ring(Radar.ZERO), Ink.Ember.copy(alpha = 0.10f))
    drawPath(ring(1.0), Ink.Text.copy(alpha = 0.22f), style = Stroke(hair))
    drawPath(ring(Radar.radius(-5.0)), Ink.Text.copy(alpha = 0.16f), style = Stroke(hair))
    for (i in 0 until 5) drawLine(Ink.Text.copy(alpha = 0.14f), c, at(i, 1.0), hair)
    drawPath(ring(Radar.ZERO), Ink.Zero.copy(alpha = 0.9f), style = Stroke(hair * 1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(hair * 5, hair * 4))))

    if (ghost != null) {
        drawPath(shape(ghost), Ink.Text.copy(alpha = 0.10f))
        drawPath(shape(ghost), Ink.Text.copy(alpha = 0.55f), style = Stroke(hair * 1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(hair * 3, hair * 3))))
    }

    val avg = shown.average()
    val tone = lerp(Ink.Ember, Ink.Leaf, ((avg + 3.0) / 6.0).coerceIn(0.0, 1.0).toFloat())
    val path = shape(shown)
    drawPath(path, tone.copy(alpha = 0.38f))
    drawPath(path, tone, style = Stroke(hair * 2.6f))
    shown.forEachIndexed { i, v ->
        val p = at(i, Radar.radius(v))
        drawCircle(Ink.Bg, hair * 5.2f, p)
        drawCircle(Ink.score(labelScores[i]), hair * 3.8f, p)
    }

    if (labels && measurer != null) {
        val glyphSize = (size.minDimension / 15f).coerceIn(11f, 54f)
        Dimension.entries.forEach { d ->
            val i = d.ordinal
            val (ux, uy) = Radar.point(i, 1.0)
            val text = measurer.measure(
                d.short, TextStyle(color = Ink.Muted, fontSize = (glyphSize * 0.62f).toSp(), fontWeight = FontWeight.Medium),
            )
            val num = measurer.measure(
                Rubric.format(labelScores[i]),
                TextStyle(color = Ink.score(labelScores[i]), fontSize = glyphSize.toSp(), fontWeight = FontWeight.Bold),
            )
            val w = maxOf(text.size.width, num.size.width).toFloat()
            val h = (text.size.height + num.size.height).toFloat()
            val anchor = at(i, 1.0)
            val cx = anchor.x + ux.toFloat() * (w / 2f + hair * 5)
            val cy = anchor.y + uy.toFloat() * (h / 2f + hair * 4)
            drawText(num, topLeft = Offset(cx - num.size.width / 2f, cy - h / 2f))
            drawText(text, topLeft = Offset(cx - text.size.width / 2f, cy - h / 2f + num.size.height * 0.92f))
        }
    }
}
