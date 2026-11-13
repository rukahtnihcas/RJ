package earth.ripple.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.ripple.core.Alternative
import earth.ripple.core.Assessment
import earth.ripple.core.Confidence
import earth.ripple.core.Dimension
import earth.ripple.core.DimensionScore
import earth.ripple.core.Profile
import earth.ripple.core.Rubric
import earth.ripple.core.Tier
import java.io.File

@Composable
fun rememberThumb(path: String) = remember(path) {
    if (path.isEmpty() || !File(path).exists()) null else runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    state: DetailState,
    profile: Profile,
    others: List<Entry>,
    aiEnabled: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onPledge: (Alternative) -> Unit,
    onAsk: (String) -> Unit,
    onCompare: (Entry?) -> Unit,
    onWrong: () -> Unit = {},
) {
    val a = state.entry.assessment
    var preview by remember(state.entry.key) { mutableStateOf<Alternative?>(null) }
    val ghost = preview?.scores ?: state.compareWith?.assessment?.values

    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(onBack, "Back to camera") { Text("←  Camera", color = Ink.Text, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.weight(1f))
            Pill(onShare, "Share this discovery") { Text("Share  ↗", color = Ink.Leaf, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val thumb = rememberThumb(state.entry.thumb)
                if (thumb != null) {
                    Image(thumb, null, Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(14.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("${a.emoji}  ${a.name}", style = MaterialTheme.typography.displaySmall, color = Ink.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Rubric.verdict(a.average).uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.score(a.average))
                        Spacer(Modifier.width(10.dp)); TierMark(a.tier)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(a.headline, style = MaterialTheme.typography.headlineSmall, color = Ink.Text)
            Spacer(Modifier.height(6.dp))
            Text("Scored for: ${a.unit}", style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
            Text(
                "Not ${article(a.name)}?  Correct it", color = Ink.Zero, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onWrong).padding(vertical = 10.dp, horizontal = 2.dp),
            )

            RadarChart(a.values, Modifier.fillMaxWidth().height(330.dp), ghost = ghost, animateKey = state.entry.key + a.tier)
            Legend(
                when {
                    preview != null -> "Dashed white: ${preview!!.name}"
                    state.compareWith != null -> "Dashed white: ${state.compareWith.assessment.name}"
                    else -> null
                }
            )
            if (a.generic) Note("Ripple could not tell exactly what this is, so these are typical values for the category. Move closer or turn on AI analysis in Settings for a specific answer.")

            if (a.critical.isNotEmpty()) {
                Section("What to be mindful of", "The material or process inside it that matters most")
                a.critical.forEach { c ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp)).background(Ink.Surface).border(1.dp, Ink.Zero.copy(alpha = 0.55f), RoundedCornerShape(16.dp)).padding(14.dp)) {
                        Text("◆  ${c.name}", color = Ink.Zero, style = MaterialTheme.typography.titleMedium)
                        if (c.why.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(c.why, color = Ink.Text.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            val trusted = a.lookFor.filter { it.trusted }
            val wary = a.lookFor.filter { !it.trusted }
            if (trusted.isNotEmpty()) {
                Section("What to look for", "Certifications, ratings and signs that hold up")
                trusted.forEach { SignalRow("✓", Ink.Leaf, it.name, it.meaning) }
            }
            if (wary.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("BE WARY OF", color = Ink.Ember, style = MaterialTheme.typography.labelSmall)
                wary.forEach { SignalRow("!", Ink.Ember, it.name, it.meaning) }
            }

            Section("The five impacts")
            Dimension.entries.forEach { d -> DimensionRow(d, a.score(d)) }

            if (a.stages.isNotEmpty()) {
                Section("Its journey", "Where in its life the impact happens")
                a.stages.forEachIndexed { i, s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(28.dp)) {
                            Box(Modifier.size((8 + s.weight * 5).dp).background(if (s.weight >= 3) Ink.Ember else if (s.weight == 2) Ink.Zero else Ink.Muted.copy(alpha = 0.6f), CircleShape))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name + if (s.weight >= 3) "   ● hotspot" else "", color = if (s.weight >= 3) Ink.Text else Ink.Text.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge, fontWeight = if (s.weight >= 3) FontWeight.SemiBold else FontWeight.Normal)
                            if (s.note.isNotBlank()) Text(s.note, color = Ink.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${i + 1}", color = Ink.Line, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            if (a.dependencies.isNotEmpty()) {
                Section("What it quietly depends on")
                a.dependencies.forEach { dep ->
                    Row(Modifier.padding(vertical = 7.dp)) {
                        Text("◦", color = Ink.Leaf, fontSize = 18.sp, modifier = Modifier.width(22.dp))
                        Column {
                            Text(dep.name, color = Ink.Text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            if (dep.note.isNotBlank()) Text(dep.note, color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (a.alternatives.isNotEmpty()) {
                Section(if (a.average > 0.4) "How to look after it" else "Better choices", "Tap one to compare it on the chart above")
                a.alternatives.forEach { alt ->
                    val pledged = profile.pledges.any { it.objectName == a.name && it.action == alt.name }
                    AlternativeCard(a, alt, selected = preview == alt, pledged = pledged, onPreview = { preview = if (preview == alt) null else alt }, onPledge = { onPledge(alt) })
                }
            }

            Section("Go deeper", if (aiEnabled) "Tap a question or ask your own" else "Tap a question")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                a.questions.filter { q -> state.chat.none { it.question == q.q } }.forEach { q ->
                    Text(q.q, color = Ink.Text, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, Ink.Line, RoundedCornerShape(18.dp))
                            .clickable(role = Role.Button) { onAsk(q.q) }.padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
            state.chat.forEach { c ->
                Spacer(Modifier.height(14.dp))
                Text(c.question, color = Ink.Leaf, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                if (c.answer == null) Row(verticalAlignment = Alignment.CenterVertically) { PulseDot(); Spacer(Modifier.width(8.dp)); Text("Thinking…", color = Ink.Muted) }
                else Text(c.answer, color = if (c.failed) Ink.Muted else Ink.Text, style = MaterialTheme.typography.bodyLarge)
            }
            if (aiEnabled) {
                var draft by remember { mutableStateOf("") }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it.take(300) }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ask anything about this ${a.name.lowercase()}", color = Ink.Muted) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onAsk(draft); draft = "" }),
                    shape = RoundedCornerShape(18.dp), maxLines = 3,
                )
            }

            if (others.isNotEmpty()) {
                Section("Compare with something you scanned")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    others.take(12).forEach { o ->
                        val on = state.compareWith?.key == o.key
                        Column(
                            Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Ink.Raised else Ink.Surface)
                                .border(1.dp, if (on) Ink.Leaf else Ink.Line, RoundedCornerShape(16.dp))
                                .clickable(role = Role.Button) { preview = null; onCompare(if (on) null else o) }.padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Canvas(Modifier.size(64.dp)) { drawRadar(o.assessment.values, null, false, null) }
                            Text(o.assessment.name, color = Ink.Text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Section("How sure is this?")
            Text(
                when (a.tier) {
                    Tier.OFFLINE -> "This is an on-device estimate for a typical ${a.name.lowercase()}, built from published life-cycle studies. It does not know this exact item, its brand or where it was made."
                    Tier.PRODUCT_DATA -> "Carbon, health and nature scores come from this product's public database record. Freshwater and social scores are estimated from its category."
                    Tier.AI -> "An AI model identified this from your photo and scored it against Ripple's rubric using published research it knows. It can be wrong, especially about brands and exact figures."
                } + " Scores run from −5 to +5 in half steps; treat a half-point difference as a tie.",
                color = Ink.Muted, style = MaterialTheme.typography.bodyMedium,
            )
            if (a.sources.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Sources: " + a.sources.joinToString(" · "), color = Ink.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SignalRow(mark: String, color: androidx.compose.ui.graphics.Color, name: String, meaning: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(Modifier.size(24.dp).background(color.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Text(mark, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Ink.Text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (meaning.isNotBlank()) Text(meaning, color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Legend(ghostLabel: String?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(22.dp, 10.dp)) {
                drawLine(Ink.Zero, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
            }
            Spacer(Modifier.width(8.dp))
            Text("Zero line. Inside it takes from the world, outside it gives back.", color = Ink.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (ghostLabel != null) Text(ghostLabel, color = Ink.Text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun Section(title: String, sub: String? = null) {
    Spacer(Modifier.height(30.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, color = Ink.Text, modifier = Modifier.semantics { heading() })
    if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Ink.Muted)
    Spacer(Modifier.height(10.dp))
}

@Composable
fun Note(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, color = Ink.Text, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Raised).padding(14.dp))
}

@Composable
private fun DimensionRow(d: Dimension, s: DimensionScore) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.glyph, fontSize = 18.sp, color = Ink.Text, modifier = Modifier.width(28.dp))
            Column(Modifier.weight(1f)) {
                Text(d.label, color = Ink.Text, style = MaterialTheme.typography.titleMedium)
                Text(Rubric.word(s.score) + if (s.metric.isNotBlank()) "  ·  ${s.metric}" else "", color = Ink.score(s.score), style = MaterialTheme.typography.bodySmall)
            }
            Text(Rubric.format(s.score), color = Ink.score(s.score), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        ScoreBar(s.score)
        Spacer(Modifier.height(8.dp))
        Text(s.why, color = Ink.Text.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium)
        Text(
            when (s.confidence) { Confidence.HIGH -> "Confidence: high"; Confidence.MEDIUM -> "Confidence: medium"; Confidence.LOW -> "Confidence: low — a judgement, not a measurement" },
            color = Ink.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** A bar that grows left of centre for harm and right of centre for benefit. */
@Composable
fun ScoreBar(score: Double) {
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val mid = size.width / 2
        drawRoundRect(Ink.Raised, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        val len = (score / 5.0).toFloat() * mid
        drawRoundRect(Ink.score(score), topLeft = Offset(if (len < 0) mid + len else mid, 0f), size = Size(kotlin.math.abs(len), size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        drawRect(Ink.Zero, topLeft = Offset(mid - 1.dp.toPx(), -2.dp.toPx()), size = Size(2.dp.toPx(), size.height + 4.dp.toPx()))
    }
}

@Composable
private fun AlternativeCard(a: Assessment, alt: Alternative, selected: Boolean, pledged: Boolean, onPreview: () -> Unit, onPledge: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClickLabel = "Compare on chart", onClick = onPreview),
        color = if (selected) Ink.Raised else Ink.Surface, shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (selected) Ink.Leaf else Ink.Line),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(78.dp)) { drawRadar(alt.scores, a.values, false, null) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(alt.name, color = Ink.Text, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(3.dp))
                    Text(alt.why, color = Ink.Text.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(alt.effort.label.uppercase(), color = Ink.Zero, style = MaterialTheme.typography.labelSmall)
                    val saving = listOfNotNull(
                        alt.co2KgPerYear.takeIf { it > 0 }?.let { "≈ ${big(it)} kg CO₂e" },
                        alt.waterLPerYear.takeIf { it > 0 }?.let { "≈ ${big(it)} L water" },
                    ).joinToString("  ·  ")
                    if (saving.isNotEmpty()) Text("$saving a year", color = Ink.Leaf, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (pledged) "Committed ✓" else "I'll do this",
                    color = if (pledged) Ink.Leaf else Ink.Bg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(if (pledged) Ink.Raised else Ink.Leaf)
                        .clickable(enabled = !pledged, role = Role.Button, onClick = onPledge).padding(horizontal = 18.dp, vertical = 13.dp),
                )
            }
        }
    }
}

fun big(v: Double): String = when {
    v >= 1_000_000 -> "%.1f million".format(v / 1_000_000)
    v >= 1000 -> "%,d".format(v.toLong())
    else -> v.toLong().toString()
}
