package earth.ripple.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import earth.ripple.core.Game
import earth.ripple.core.Profile

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(profile: Profile, journal: List<Entry>, today: String, onBack: () -> Unit, onOpen: (Entry) -> Unit, onTogglePledge: (Int) -> Unit) {
    val level = Game.level(profile.points)
    Column(Modifier.fillMaxSize().background(Ink.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Pill(onBack, "Back to camera") { Text("←  Camera", color = Ink.Text, style = MaterialTheme.typography.bodyMedium) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).background(Ink.Raised, CircleShape), contentAlignment = Alignment.Center) { Text(level.glyph, fontSize = 30.sp, color = Ink.Leaf) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(level.name, style = MaterialTheme.typography.displaySmall, color = Ink.Text)
                    val next = level.next
                    Text(if (next != null) "${profile.points} points · ${next - profile.points} to the next stage" else "${profile.points} points", color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    val frac = if (next != null) (profile.points - level.floor).toFloat() / (next - level.floor) else 1f
                    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                        val r = CornerRadius(size.height / 2)
                        drawRoundRect(Ink.Raised, size = size, cornerRadius = r)
                        drawRoundRect(Ink.Leaf, size = Size(size.width * frac.coerceIn(0.02f, 1f), size.height), cornerRadius = r)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("${profile.scans}", "scans", Modifier.weight(1f))
                Stat("${profile.seen.size}", "kinds of thing", Modifier.weight(1f))
                Stat("${profile.streak}", if (profile.streak == 1) "day streak" else "day streak", Modifier.weight(1f))
                Stat(if (profile.guesses == 0) "–" else "${profile.correct}/${profile.guesses}", "good guesses", Modifier.weight(1f))
            }

            val quest = Game.quest(today)
            val done = profile.questDay == today
            Section("Today's quest")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Ink.Surface).border(1.dp, if (done) Ink.Leaf else Ink.Zero.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (done) "✓" else "◎", color = if (done) Ink.Leaf else Ink.Zero, fontSize = 22.sp, modifier = Modifier.width(34.dp))
                Column {
                    Text(quest.text, color = Ink.Text, style = MaterialTheme.typography.bodyLarge)
                    Text(if (done) "Done. A new one arrives tomorrow." else "+40 points. Everyone gets the same quest today.", color = Ink.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("Your ripple", "What your commitments add up to in a year")
            if (profile.pledges.isEmpty()) {
                Text("Nothing yet. Open any scan, pick a better choice and tap “I'll do this”. This is where it adds up.", color = Ink.Muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Impact("≈ ${big(profile.co2PledgedKg)} kg", "CO₂e a year", Game.co2Equivalent(profile.co2PledgedKg), Modifier.weight(1f))
                    Impact("≈ ${big(profile.waterPledgedL)} L", "water a year", Game.waterEquivalent(profile.waterPledgedL), Modifier.weight(1f))
                }
                Text("Rough estimates for a typical person. They count once you actually do them.", color = Ink.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(6.dp))
                profile.pledges.forEachIndexed { i, p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Checkbox, onClickLabel = if (p.done) "Mark as not done" else "Mark as done") { onTogglePledge(i) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(26.dp).border(2.dp, if (p.done) Ink.Leaf else Ink.Muted, CircleShape).background(if (p.done) Ink.Leaf else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                            if (p.done) Text("✓", color = Ink.Bg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(p.action, color = Ink.Text, style = MaterialTheme.typography.bodyLarge, textDecoration = if (p.done) TextDecoration.LineThrough else null)
                            Text("from ${p.objectName}", color = Ink.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Section("Badges", "${profile.badges.size} of ${Game.badges.size}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
                Game.badges.forEach { b ->
                    val have = b.id in profile.badges
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (have) Ink.Raised else Ink.Surface).padding(horizontal = 8.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(b.glyph, fontSize = 24.sp, color = if (have) Ink.Zero else Ink.Muted.copy(alpha = 0.45f))
                        Text(b.name, color = if (have) Ink.Text else Ink.Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text(b.how, color = Ink.Muted, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                    }
                }
            }

            Section("Everything you've looked at", if (journal.isEmpty()) null else "${journal.size} scans")
            if (journal.isEmpty()) Text("Your scans will collect here.", color = Ink.Muted)
            journal.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { e -> JournalTile(e, Modifier.weight(1f)) { onOpen(e) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Ink.Surface).padding(vertical = 12.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Ink.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Ink.Muted, fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 13.sp)
    }
}

@Composable
private fun Impact(value: String, label: String, equivalent: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Ink.Surface).padding(14.dp)) {
        Text(value, color = Ink.Leaf, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Ink.Text, style = MaterialTheme.typography.bodyMedium)
        if (equivalent.isNotEmpty()) Text(equivalent, color = Ink.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun JournalTile(e: Entry, modifier: Modifier, onClick: () -> Unit) {
    val thumb = rememberThumb(e.thumb)
    Box(modifier.aspectRatio(0.86f).clip(RoundedCornerShape(20.dp)).background(Ink.Surface).clickable(role = Role.Button, onClickLabel = "Open ${e.assessment.name}", onClick = onClick)) {
        if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink.Bg.copy(alpha = 0.35f), Ink.Bg.copy(alpha = 0.92f)))))
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.15f).padding(10.dp)) { drawRadar(e.assessment.values, null, false, null) }
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text("${e.assessment.emoji} ${e.assessment.name}", color = Ink.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(earth.ripple.core.Rubric.verdict(e.assessment.average), color = Ink.score(e.assessment.average), style = MaterialTheme.typography.labelSmall)
        }
    }
}
