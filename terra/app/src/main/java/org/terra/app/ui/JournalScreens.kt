package org.terra.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.terra.app.Derived
import org.terra.app.domain.Catalog
import org.terra.app.model.Detection
import org.terra.app.model.Landmark
import org.terra.app.model.LandmarkKind
import org.terra.app.sensing.StillnessStatus
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(
    d: Derived, catalog: Catalog, onStillness: () -> Unit, onNewLandmark: () -> Unit, onLandmark: (Long) -> Unit,
    onData: () -> Unit, onConfirm: (Detection) -> Unit, onDismiss: (Detection) -> Unit, onSpecies: (String) -> Unit
) {
    val pending = d.world.detections.filter { it.resolved == 0 }
    val sits = d.world.sessions.count { it.completed }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Journal", "${d.world.observations.size} records · ${d.badges.count { it.earned }} badges", trailing = { TextButton(onClick = onData) { Text("Data & ethics") } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Legacy landmarks")
            if (d.world.landmarks.isEmpty()) Text("Adopt a sapling, an old oak or a pond, and add to its story over the years. One day someone can stand here and scroll back through decades.", style = MaterialTheme.typography.bodyMedium)
            for (lm in d.world.landmarks) {
                val entries = d.world.entries.filter { it.landmarkId == lm.id }
                PaperCard(onClick = { onLandmark(lm.id) }, padding = PaddingValues(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val photo = entries.firstOrNull { it.photoPath != null }?.photoPath
                        if (photo != null) Photo(photo, Modifier.size(60.dp)) else Stamp(lm.kind.glyph, 60.dp)
                        GapW()
                        Column(Modifier.weight(1f)) {
                            Text(lm.name, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(lm.kind.label, lm.speciesName).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                            Text("Adopted ${ago(lm.adoptedAt)} · ${entries.size} ${if (entries.size == 1) "entry" else "entries"}", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                        }
                    }
                }
            }
            OutlinedButton(onClick = onNewLandmark, modifier = Modifier.fillMaxWidth()) { Text("Adopt a landmark") }

            Eyebrow("Stillness")
            PaperCard {
                Text(if (sits == 0) "No sits yet" else "$sits ${if (sits == 1) "sit" else "sits"} completed", style = MaterialTheme.typography.titleMedium)
                Text("Ten quiet minutes with the screen locked. TERRA listens for the shy things that only come out when you stop.", style = MaterialTheme.typography.bodyMedium)
                Gap(8.dp)
                Button(onClick = onStillness) { Text("Begin a sit") }
            }
            if (pending.isNotEmpty()) {
                Eyebrow("Heard while you were still · confirm by ear")
                for (det in pending.take(6)) {
                    val s = catalog[det.speciesId] ?: continue
                    PaperCard(padding = PaddingValues(10.dp)) {
                        SpeciesRow(s, null, "${dayTime(det.timestamp)} · possible match — ${s.acoustic?.sounds.orEmpty()}")
                        Row {
                            TextButton(onClick = { onConfirm(det) }) { Text("That's what I heard") }
                            TextButton(onClick = { onDismiss(det) }) { Text("Dismiss") }
                        }
                    }
                }
            }

            Eyebrow("Badges · kinds of attention, never volume")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (b in d.badges) Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Stamp(b.glyph, 50.dp, ring = pigments.ochre, modifier = Modifier.alpha(if (b.earned) 1f else 0.3f))
                    Text(b.name, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, color = if (b.earned) pigments.ink else pigments.inkSoft)
                    Text(b.how, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, color = pigments.inkSoft)
                }
            }

            if (d.world.observations.isNotEmpty()) {
                Eyebrow("Latest field notes")
                for (o in d.world.observations.take(6)) PaperCard(onClick = { if (catalog[o.speciesId] != null) onSpecies(o.speciesId) }, padding = PaddingValues(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (o.photoPath != null) Photo(o.photoPath, Modifier.size(44.dp)) else Stamp(catalog[o.speciesId]?.glyph ?: o.group.glyph, 44.dp)
                        GapW(10.dp)
                        Column {
                            Text(o.displayName, style = MaterialTheme.typography.titleMedium)
                            Text("${o.method.label} · ${dayTime(o.timestamp)}", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                        }
                    }
                }
            }
            Gap(24.dp)
        }
    }
}

@Composable
fun NewLandmarkScreen(onBack: () -> Unit, takePhoto: ((File) -> Unit) -> Unit, hasFix: Boolean, onAdopt: (String, LandmarkKind, String, String, Float?, File?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<LandmarkKind?>(LandmarkKind.SAPLING) }
    var species by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var girth by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<File?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Adopt a landmark", "Stand beside it", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Its name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceChips(LandmarkKind.entries, kind, { "${it.glyph} ${it.label}" }, { kind = it ?: kind })
            OutlinedTextField(species, { species = it }, label = { Text("Species, if you know it") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(girth, { girth = it }, label = { Text("Trunk girth at chest height, cm (optional)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("First field note") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Photo(photo?.path, Modifier.fillMaxWidth().height(180.dp))
            OutlinedButton(onClick = { takePhoto { photo = it } }, modifier = Modifier.fillMaxWidth()) { Text(if (photo == null) "Take its first portrait" else "Retake portrait") }
            if (!hasFix) Text("No GPS fix yet — it will be pinned at the centre of the map.", style = MaterialTheme.typography.bodySmall, color = pigments.rust)
            Button(onClick = { onAdopt(name, kind ?: LandmarkKind.OTHER, species, note, girth.toFloatOrNull(), photo) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Adopt") }
        }
    }
}

@Composable
fun LandmarkDetailScreen(d: Derived, lm: Landmark, onBack: () -> Unit, takePhoto: ((File) -> Unit) -> Unit, onAdd: (String, Float?, File?) -> Unit) {
    val entries = d.world.entries.filter { it.landmarkId == lm.id }.sortedByDescending { it.timestamp }
    var note by remember { mutableStateOf("") }
    var girth by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<File?>(null) }
    val line = pigments.line
    val dot = pigments.moss
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(lm.name, listOf(lm.kind.label, lm.speciesName).filter { it.isNotBlank() }.joinToString(" · "), onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Adopted ${day(lm.adoptedAt)} · ${"%.5f".format(lm.lat)}, ${"%.5f".format(lm.lon)}", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
            PaperCard {
                Eyebrow("Add to its story")
                Gap(6.dp)
                OutlinedTextField(note, { note = it }, label = { Text("Today's note") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Gap(6.dp)
                OutlinedTextField(girth, { girth = it }, label = { Text("Girth, cm (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Gap(6.dp)
                Photo(photo?.path, Modifier.fillMaxWidth().height(160.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { takePhoto { photo = it } }) { Text(if (photo == null) "Photograph" else "Retake") }
                    Button(onClick = { onAdd(note, girth.toFloatOrNull(), photo); note = ""; girth = ""; photo = null }, enabled = note.isNotBlank() || photo != null || girth.isNotBlank()) { Text("Add entry") }
                }
            }
            Eyebrow("Timeline · ${entries.size} ${if (entries.size == 1) "entry" else "entries"}")
            for (e in entries) Row {
                Canvas(Modifier.width(20.dp).height(64.dp)) {
                    drawLine(line, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 2.dp.toPx())
                    drawCircle(dot, 5.dp.toPx(), Offset(size.width / 2, 12.dp.toPx()))
                }
                GapW(8.dp)
                Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
                    Text(day(e.timestamp), style = MaterialTheme.typography.titleMedium)
                    val since = e.timestamp - lm.adoptedAt
                    Text(if (since < 86_400_000L) "The day it was adopted" else "${ago(lm.adoptedAt, e.timestamp).removeSuffix(" ago")} after adoption", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                    if (e.girthCm != null) Text("Girth ${e.girthCm} cm", style = MaterialTheme.typography.bodyMedium, color = pigments.moss)
                    if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodyMedium)
                    if (e.photoPath != null) { Gap(6.dp); Photo(e.photoPath, Modifier.fillMaxWidth().height(200.dp)) }
                }
            }
            Gap(24.dp)
        }
    }
}

@Composable
fun StillnessScreen(status: StillnessStatus, d: Derived, catalog: Catalog, onBack: () -> Unit, onBegin: () -> Unit, onEnd: () -> Unit, onDone: () -> Unit) {
    val ring = pigments.moss
    val track = pigments.wash
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Stillness", "Sit. Lock the screen. Listen.", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                status.running -> {
                    val left = (status.totalSec - status.elapsedSec).coerceAtLeast(0)
                    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                            drawArc(track, 0f, 360f, false, style = Stroke(10.dp.toPx()))
                            drawArc(ring, -90f, 360f * status.elapsedSec / status.totalSec, false, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round), size = Size(size.width, size.height))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("%d:%02d".format(left / 60, left % 60), style = MaterialTheme.typography.displaySmall)
                            Text(if (status.quiet) "quiet" else "noisy just now", style = MaterialTheme.typography.titleSmall, color = pigments.inkSoft)
                        }
                    }
                    Text("Lock your screen now and put the phone down beside you. A triple pulse will mark the end.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Text(if (status.heard == 0) "Nothing distinct heard yet." else "${status.heard} possible ${if (status.heard == 1) "voice" else "voices"} noted.", style = MaterialTheme.typography.bodyMedium, color = pigments.inkSoft)
                    OutlinedButton(onClick = onEnd) { Text("End early") }
                }
                status.finishedSessionId != null -> {
                    val complete = status.elapsedSec >= status.totalSec
                    val heard = d.world.detections.filter { it.sessionId == status.finishedSessionId }
                    PaperCard(tint = pigments.wash) {
                        Eyebrow(if (complete) "Sit complete" else "Sit ended early", color = pigments.moss)
                        Gap(4.dp)
                        Text(if (complete) "Ten minutes of attention, given freely." else "You sat for ${status.elapsedSec / 60} min ${status.elapsedSec % 60} s. Only full sits count toward bounties.", style = MaterialTheme.typography.bodyLarge)
                        Gap(6.dp)
                        Text(if (heard.isEmpty()) "No distinct voices stood out this time. That is a result too."
                        else "Possible voices: " + heard.mapNotNull { catalog[it.speciesId]?.common }.distinct().joinToString(", ") + ". Confirm them by ear in your Journal.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(onClick = onDone) { Text("Return to the journal") }
                }
                else -> {
                    PaperCard {
                        Eyebrow("How a sit works")
                        Gap(6.dp)
                        Text("Find a place to sit in or near green space. Start the sit, lock your screen, and stay quiet for ten minutes.", style = MaterialTheme.typography.bodyLarge)
                        Gap(6.dp)
                        Text("While you are still, TERRA listens in three-second windows and notes any sound that stands clear of the background and resembles a local bird, frog or insect. Those become candidates for you to confirm by ear afterwards.", style = MaterialTheme.typography.bodyMedium)
                        Gap(6.dp)
                        Text("No audio is saved or sent anywhere — only the candidate names. A quiet notification stays visible while the microphone is in use.", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                    }
                    Button(onClick = onBegin, modifier = Modifier.fillMaxWidth()) { Text("Begin a 10-minute sit") }
                }
            }
        }
    }
}

@Composable
fun DataScreen(
    haptics: Boolean, chimes: Boolean, onHaptics: (Boolean) -> Unit, onChimes: (Boolean) -> Unit, onBack: () -> Unit,
    onPublic: () -> Unit, onResearch: () -> Unit, onArchive: () -> Unit, onRestore: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Data & ethics", "Your records, your terms", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperCard {
                Eyebrow("The TERRA pledge", color = pigments.moss)
                Gap(6.dp)
                for (line in listOf(
                    "No advertising, loot boxes, streaks or countdown pressure.",
                    "No push notifications. The only notification is the one shown while a Stillness sit is listening.",
                    "Photos and sound are analysed on this phone. Audio is never stored.",
                    "Sensitive species are never published at their true location.",
                    "Everything you record can be exported in open formats and restored on another phone."
                )) Text("· $line", style = MaterialTheme.typography.bodyMedium)
            }
            Eyebrow("Eyes-up cues")
            PaperCard(padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Haptic pulse on entering a habitat", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(haptics, onHaptics) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Directional chime for new habitats", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(chimes, onChimes) }
            }
            Eyebrow("Open data")
            PaperCard {
                Text("Public atlas layer", style = MaterialTheme.typography.titleMedium)
                Text("GeoJSON with Darwin Core fields, CC-BY. Sensitive species are generalised to a 0.2° grid and flagged.", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                OutlinedButton(onClick = onPublic) { Text("Export public layer") }
                Gap(8.dp)
                Rule()
                Gap(8.dp)
                Text("Research-grade layer", style = MaterialTheme.typography.titleMedium)
                Text("Exact coordinates, including sensitive species. Hand this only to a vetted conservation researcher.", style = MaterialTheme.typography.bodySmall, color = pigments.rust)
                OutlinedButton(onClick = onResearch) { Text("Export precise layer") }
            }
            Eyebrow("Heirloom archive")
            PaperCard {
                Text("A single file holding every record, landmark timeline and photograph, so the journal can outlive this phone.", style = MaterialTheme.typography.bodyMedium)
                Gap(6.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onArchive) { Text("Save archive") }
                    OutlinedButton(onClick = onRestore) { Text("Restore") }
                }
            }
            Text("Base map and habitat outlines © OpenStreetMap contributors (ODbL).", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
            Gap(24.dp)
        }
    }
}
