package org.terra.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.terra.app.Derived
import org.terra.app.domain.Catalog
import org.terra.app.domain.Geo
import org.terra.app.domain.HabitatEngine
import org.terra.app.model.ActionKind
import org.terra.app.model.Habitat
import org.terra.app.model.HabitatSource
import org.terra.app.model.StewardAction
import org.terra.app.sensing.Fix

@Composable
fun healthColor(score: Int): Color = when {
    score >= 60 -> pigments.moss
    score >= 35 -> pigments.ochre
    else -> pigments.rust
}

fun distanceLabel(m: Double): String = if (m < 950) "${(m / 10).toInt() * 10} m" else "${"%.1f".format(m / 1000)} km"

@Composable
fun HabitatsScreen(d: Derived, at: Fix, charting: Boolean, onOpen: (Long) -> Unit, onChart: () -> Unit) {
    val sorted = d.world.habitats.map { it to Geo.distanceM(at.lat, at.lon, it.lat, it.lon) }.sortedBy { it.second }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Habitats", "Shared sanctuaries whose health you can raise")
        if (sorted.isEmpty()) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperCard {
                Text("Nothing is charted around you yet.", style = MaterialTheme.typography.titleMedium)
                Gap(6.dp)
                Text("TERRA can chart the parks, woods, wetlands and water within 1.5 km from OpenStreetMap. Or mark a micro-habitat by hand from the Field map — a verge, a pond, one street tree.", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onChart, enabled = !charting, modifier = Modifier.fillMaxWidth()) { Text(if (charting) "Charting…" else "Chart habitats near me") }
        } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sorted, key = { it.first.id }) { (h, dist) ->
                val health = d.health[h.id]
                PaperCard(onClick = { onOpen(h.id) }, padding = PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Stamp(h.type.glyph, 46.dp, known = h.firstVisitedAt != null)
                        GapW()
                        Column(Modifier.weight(1f)) {
                            Text(h.name, style = MaterialTheme.typography.titleMedium)
                            Text("${h.type.label} · ${distanceLabel(dist)}" + if (h.firstVisitedAt == null) " · not yet visited" else "", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                            Gap(6.dp)
                            Meter((health?.score ?: 0) / 100f, color = healthColor(health?.score ?: 0))
                        }
                        GapW()
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${health?.score ?: 0}", style = MaterialTheme.typography.headlineSmall, color = healthColor(health?.score ?: 0))
                            if (health?.isSteward == true) Tag("Steward", pigments.moss)
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = onChart, enabled = !charting, modifier = Modifier.fillMaxWidth()) { Text(if (charting) "Charting…" else "Chart more around me") } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitatDetailScreen(
    d: Derived, h: Habitat, catalog: Catalog, onBack: () -> Unit, onSpecies: (String) -> Unit,
    onNewAction: () -> Unit, onAfterPhoto: (StewardAction) -> Unit
) {
    val health = d.health[h.id] ?: return
    val here = d.world.observations.filter { it.habitatId == h.id }
    val actions = d.world.actions.filter { it.habitatId == h.id }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(h.name, "${h.type.label} · ${if (h.source == HabitatSource.OSM) "charted from OpenStreetMap" else "marked by you"}", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PaperCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${health.score}", style = MaterialTheme.typography.displaySmall, color = healthColor(health.score))
                    GapW()
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Habitat health")
                        Gap(4.dp)
                        Meter(health.score / 100f, color = healthColor(health.score))
                    }
                }
                Gap(10.dp)
                for ((label, pts) in health.breakdown) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(if (pts > 0) "+$pts" else "$pts", style = MaterialTheme.typography.bodyMedium, color = if (pts < 0) pigments.rust else pigments.inkSoft)
                }
                Gap(6.dp)
                Text("Based on your own records. Health rises as native life is logged and stewardship is verified; unaddressed invasives pull it down.", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
            }

            Eyebrow("What lives here · ${here.map { it.speciesId }.distinct().size} species")
            if (here.isEmpty()) Text("Nothing logged yet. Stand inside this habitat and make a bio-scan or acoustic sweep.", style = MaterialTheme.typography.bodyMedium)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (o in here.distinctBy { it.speciesId }) {
                    val s = catalog[o.speciesId]
                    Column(Modifier.width(72.dp).clickable(enabled = s != null) { onSpecies(o.speciesId) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Stamp(s?.glyph ?: o.group.glyph, 44.dp, ring = if (s?.isInvasiveIn(d.region) == true) pigments.rust else pigments.ink)
                        Text(o.displayName, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            if (health.missing.isNotEmpty()) PaperCard(padding = PaddingValues(12.dp)) {
                Eyebrow("What is missing")
                Gap(4.dp)
                Text("No ${health.missing.joinToString(", ") { it.label.lowercase() }} recorded here yet. Absent, or just unobserved?", style = MaterialTheme.typography.bodyMedium)
            }

            Eyebrow("Stewardship · ${health.verifiedActions} verified" + if (health.isSteward) " · you are Steward" else "")
            if (!health.isSteward) Text("${HabitatEngine.STEWARD_ACTIONS} verified actions make you Steward of this habitat.", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
            for (a in actions) ActionCard(a) { onAfterPhoto(a) }
            Button(onClick = onNewAction, modifier = Modifier.fillMaxWidth()) { Text("Log a stewardship action") }
            Gap(24.dp)
        }
    }
}

@Composable
private fun ActionCard(a: StewardAction, onAfter: () -> Unit) {
    val c = HabitatEngine.check(a)
    PaperCard(padding = PaddingValues(12.dp), tint = if (c.verified) pigments.wash else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${a.kind.glyph} ${a.kind.label}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            when {
                c.verified -> Tag("Verified", pigments.moss)
                c.hasAfter -> Tag("Not verified", pigments.rust)
                else -> Tag("In progress", pigments.ochre)
            }
        }
        if (a.note.isNotBlank()) Text(a.note, style = MaterialTheme.typography.bodyMedium)
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column { Photo(a.beforePhoto, Modifier.size(96.dp)); Text("Before · ${day(a.beforeAt)}", style = MaterialTheme.typography.labelMedium) }
            if (a.afterPhoto != null) Column { Photo(a.afterPhoto, Modifier.size(96.dp)); Text("After · ${day(a.afterAt ?: 0)}", style = MaterialTheme.typography.labelMedium) }
        }
        if (c.hasAfter) {
            Gap(6.dp)
            Text((if (c.sameSpot) "● Same place" else "○ After photo taken elsewhere") + "   " + (if (c.timeElapsed) "● Time elapsed" else "○ Under 10 minutes apart"),
                style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
        } else {
            Gap(8.dp)
            OutlinedButton(onClick = onAfter) { Text("Add the after photo") }
        }
    }
}

@Composable
fun NewActionScreen(h: Habitat, onBack: () -> Unit, onBefore: (ActionKind, String) -> Unit) {
    var kind by remember { mutableStateOf<ActionKind?>(ActionKind.REMOVE_INVASIVE) }
    var note by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Stewardship action", h.name, onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Care is recorded as a pair of photographs taken from the same spot: one before the work, one after.", style = MaterialTheme.typography.bodyLarge)
            ChoiceChips(ActionKind.entries, kind, { "${it.glyph} ${it.label}" }, { kind = it ?: kind })
            kind?.let { Text(it.prompt, style = MaterialTheme.typography.bodyMedium, color = pigments.inkSoft) }
            OutlinedTextField(note, { note = it }, label = { Text("What are you going to do?") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            PaperCard(padding = PaddingValues(12.dp)) {
                Eyebrow("Before you start")
                Gap(4.dp)
                Text("Only work where you have permission. Be sure of your identification before removing any plant, and never move invasive material off site unless the land manager asks you to.", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { onBefore(kind ?: ActionKind.CLEANUP, note) }, modifier = Modifier.fillMaxWidth()) { Text("Take the before photo") }
        }
    }
}
