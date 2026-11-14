package org.terra.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.terra.app.Derived
import org.terra.app.domain.GeoFuzzer
import org.terra.app.domain.HabitatEngine
import org.terra.app.model.Habitat
import org.terra.app.model.HabitatType
import org.terra.app.sensing.Fix

@Composable
fun FieldScreen(
    d: Derived, fix: Fix?, locationGranted: Boolean, publicView: Boolean, charting: Boolean, walking: Boolean,
    onGrant: () -> Unit, onScan: () -> Unit, onSweep: () -> Unit, onStillness: () -> Unit,
    onChart: () -> Unit, onMark: (String, HabitatType, Double) -> Unit, onTogglePublic: () -> Unit,
    onHabitat: (Long) -> Unit, onRecenter: () -> Unit, onWalk: () -> Unit, map: @Composable (Modifier) -> Unit
) {
    val here: Habitat? = fix?.let { HabitatEngine.containing(d.world.habitats, it.lat, it.lon) }
    var marking by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        map(Modifier.fillMaxSize())

        Column(Modifier.statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperCard(padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("${d.season.label} · ${d.season.theme}", color = pigments.moss)
                        Text(
                            when {
                                here != null -> "You are in ${here.name}"
                                !locationGranted -> "TERRA needs your location to chart what lives around you."
                                fix == null -> "Finding where you are…"
                                d.world.habitats.isEmpty() -> "No habitats charted here yet."
                                walking -> "Walk mode is on. Pocket the phone — it will pulse when you cross into a habitat."
                                else -> "Between habitats. Start walk mode to be pulsed when you cross into one."
                            }, style = MaterialTheme.typography.bodyMedium
                        )
                        if (here != null) d.health[here.id]?.let {
                            Text("Health ${it.score} · ${it.nativeSpecies} native species logged", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                        }
                    }
                    if (here != null) TextButton(onClick = { onHabitat(here.id) }) { Text("Open") }
                    if (!locationGranted) Button(onClick = onGrant) { Text("Allow") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTogglePublic, colors = paperButton(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(if (publicView) "Public atlas view" else "My precise view", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(onClick = onWalk, colors = if (walking) androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = pigments.moss, contentColor = pigments.card) else paperButton(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(if (walking) "Walk mode on" else "Start walk mode", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (publicView) PaperCard(Modifier.widthIn(max = 280.dp), padding = PaddingValues(10.dp)) {
                Text("This is what the open atlas shows. Dashed pins are sensitive species, moved to a ~20 km grid so they cannot be found.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Column(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = onRecenter) { Icon(Icons.Outlined.MyLocation, "Centre on me") }
            OutlinedButton(onClick = onChart, enabled = !charting, colors = paperButton(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                if (charting) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); GapW(8.dp) }
                Text(if (charting) "Charting…" else "Chart habitats here", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(onClick = { marking = true }, colors = paperButton(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("Mark a micro-habitat", style = MaterialTheme.typography.labelMedium)
            }
        }

        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldAction("Stillness", Icons.Outlined.SelfImprovement, onStillness, false)
            FieldAction("Acoustic sweep", Icons.Outlined.GraphicEq, onSweep, false)
            FieldAction("Bio-scan", Icons.Outlined.CameraAlt, onScan, true)
        }
    }
    if (marking) MarkHabitatDialog(onDismiss = { marking = false }) { n, t, r -> marking = false; onMark(n, t, r) }
}

@Composable
private fun paperButton() = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(containerColor = pigments.card, contentColor = pigments.ink)

@Composable
private fun FieldAction(label: String, icon: ImageVector, onClick: () -> Unit, primary: Boolean) {
    ExtendedFloatingActionButton(
        onClick = onClick, icon = { Icon(icon, null) }, text = { Text(label) },
        containerColor = if (primary) pigments.moss else pigments.card,
        contentColor = if (primary) pigments.card else pigments.ink
    )
}

@Composable
fun MarkHabitatDialog(onDismiss: () -> Unit, onConfirm: (String, HabitatType, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<HabitatType?>(HabitatType.GARDEN) }
    val sizes = listOf("Pocket · 25 m" to 25.0, "Small · 60 m" to 60.0, "Large · 150 m" to 150.0)
    var size by remember { mutableStateOf<Pair<String, Double>?>(sizes[0]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark a micro-habitat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A verge, a pond, a single street tree's shade — any patch worth watching. It is centred where you stand.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChoiceChips(HabitatType.entries, type, { "${it.glyph} ${it.label}" }, { type = it ?: type })
                ChoiceChips(sizes, size, { it.first }, { size = it ?: size })
            }
        },
        confirmButton = { Button(onClick = { onConfirm(name, type ?: HabitatType.GARDEN, size?.second ?: 25.0) }) { Text("Mark it") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Builds what the map draws. In public view, sensitive records are shown where the open atlas would show them. */
fun mapModel(d: Derived, fix: Fix?, publicView: Boolean, healthColor: (Int) -> Int, paper: Int, ink: Int, glyphOf: (String) -> String?): MapModel {
    val pins = d.world.observations.groupBy { it.speciesId }.flatMap { (id, list) ->
        // One pin per species per ~30 m spot keeps a well-watched park legible.
        list.distinctBy { ((it.lat * 3000).toInt()) to ((it.lon * 3000).toInt()) }.map { o ->
            val p = if (publicView) GeoFuzzer.publicPoint(o.lat, o.lon, o.sensitive) else GeoFuzzer.Point(o.lat, o.lon)
            MapPin(id, p.lat, p.lon, glyphOf(id) ?: o.group.glyph, ink, obscured = publicView && o.sensitive)
        }
    }.distinctBy { Triple(it.key, it.lat, it.lon) }
    return MapModel(
        d.world.habitats.map { MapArea(it.id, it.lat, it.lon, it.radiusM, healthColor(d.health[it.id]?.score ?: 15), it.firstVisitedAt != null) },
        pins, fix?.let { Triple(it.lat, it.lon, it.accuracyM) }, paper, ink
    )
}
