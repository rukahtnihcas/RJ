package org.terra.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.terra.app.Derived
import org.terra.app.domain.Catalog
import org.terra.app.domain.CodexEntry
import org.terra.app.domain.CodexLevel
import org.terra.app.model.HabitatType
import org.terra.app.model.LifeGroup
import org.terra.app.model.Season
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CodexScreen(d: Derived, onOpen: (String) -> Unit) {
    var group by rememberSaveable { mutableStateOf<LifeGroup?>(null) }
    var local by rememberSaveable { mutableStateOf(true) }
    val shown = d.entries.filter { (group == null || it.species.group == group) && (!local || it.species.occursIn(d.region) || it.level != CodexLevel.UNSEEN) }
        .sortedWith(compareBy({ it.level == CodexLevel.UNSEEN }, { it.species.group.ordinal }, { it.species.common }))
    val found = shown.count { it.level != CodexLevel.UNSEEN }
    val webs = shown.count { it.level == CodexLevel.COMPLETE }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Field Codex", "$found of ${shown.size} found · $webs webs complete")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(local, { local = !local }, label = { Text(if (local) d.region.label else "All regions") })
            FilterChip(group == null, { group = null }, label = { Text("All life") })
            for (g in LifeGroup.entries) FilterChip(group == g, { group = if (group == g) null else g }, label = { Text("${g.glyph} ${g.label}") })
        }
        LazyVerticalGrid(
            GridCells.Adaptive(104.dp), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shown, key = { it.species.id }) { e -> CodexCell(e) { onOpen(e.species.id) } }
        }
    }
}

@Composable
private fun CodexCell(e: CodexEntry, onClick: () -> Unit) {
    val seen = e.level != CodexLevel.UNSEEN
    PaperCard(onClick = onClick, padding = PaddingValues(8.dp), tint = if (e.level == CodexLevel.COMPLETE) pigments.wash else null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Stamp(e.species.glyph, 52.dp, ring = if (e.level == CodexLevel.COMPLETE) pigments.moss else pigments.ink, modifier = Modifier.alpha(if (seen) 1f else 0.35f))
            Gap(6.dp)
            Text(
                e.species.common, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 2, minLines = 2,
                overflow = TextOverflow.Ellipsis, color = if (seen) pigments.ink else pigments.inkSoft
            )
            Gap(4.dp)
            NodeDots(e.doneNodes, e.totalNodes, seen)
        }
    }
}

@Composable
private fun NodeDots(done: Int, total: Int, seen: Boolean) {
    val on = pigments.moss
    val off = pigments.line
    Canvas(Modifier.height(8.dp).width((total * 11).dp)) {
        for (i in 0 until total) {
            val c = Offset(size.height / 2 + i * 11.dp.toPx(), size.height / 2)
            if (seen && i < done) drawCircle(on, size.height / 2, c)
            else drawCircle(off, size.height / 2 - 1, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeciesDetailScreen(d: Derived, e: CodexEntry, catalog: Catalog, onBack: () -> Unit, onOpen: (String) -> Unit, onDelete: (Long) -> Unit) {
    val s = e.species
    val seen = e.level != CodexLevel.UNSEEN
    val habitats = d.world.habitats.associateBy { it.id }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(s.common, s.scientific, onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Stamp(s.glyph, 72.dp, ring = if (e.level == CodexLevel.COMPLETE) pigments.moss else pigments.ink, modifier = Modifier.alpha(if (seen) 1f else 0.4f))
                GapW()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(e.level.label, if (seen) pigments.moss else pigments.inkSoft)
                    Tag(s.group.label)
                    val st = s.standing(d.region)
                    Tag(st, when (st) { "Invasive here" -> pigments.rust; "Native here" -> pigments.moss; else -> pigments.inkSoft })
                    if (s.sensitive) Tag("Location protected", pigments.water)
                }
            }
            Text(s.blurb, style = MaterialTheme.typography.bodyLarge)
            if (s.isInvasiveIn(d.region)) PaperCard(tint = pigments.wash) {
                Eyebrow("Invasive in ${d.region.label}", color = pigments.rust)
                Gap(4.dp)
                Text("Record it wherever you find it. Logging it lowers a habitat's health until a removal action is verified there.", style = MaterialTheme.typography.bodyMedium)
            }
            s.acoustic?.let {
                PaperCard(padding = PaddingValues(12.dp)) {
                    Eyebrow("Voice")
                    Gap(4.dp)
                    Text(it.sounds, style = MaterialTheme.typography.bodyMedium)
                    Text("${it.lowHz}–${it.highHz} Hz", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                }
            }

            Eyebrow("Symbiosis web · ${e.doneNodes} of ${e.totalNodes} connections")
            PaperCard(padding = PaddingValues(8.dp)) { Web(e, catalog) }
            if (!seen) Text("Find and log this species first; then its connections can be documented.", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)

            for (n in e.nodes) PaperCard(padding = PaddingValues(12.dp), tint = if (n.satisfied) pigments.wash else null) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(if (n.satisfied) "●" else "○", color = if (n.satisfied) pigments.moss else pigments.inkSoft, style = MaterialTheme.typography.titleMedium)
                    GapW(8.dp)
                    Column {
                        if (n.satisfied) Text(n.phrase(s.common), style = MaterialTheme.typography.titleMedium)
                        else {
                            Text(n.link.hint, style = MaterialTheme.typography.titleMedium)
                            Text("Log nearby: " + n.link.targets.joinToString(", or ") { label(it, catalog) }, style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                        }
                    }
                }
            }
            if (e.seasonsNeeded > 0) PaperCard(padding = PaddingValues(12.dp), tint = if (e.seasonNodeDone && seen) pigments.wash else null) {
                Text("Seasonal presence", style = MaterialTheme.typography.titleMedium)
                Text("Record it in ${e.seasonsNeeded} different seasons to learn when it is here.", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                Gap(6.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (season in Season.entries) Tag(
                        season.label,
                        when { season in e.seasonsSeen -> pigments.moss; season in s.seasons -> pigments.inkSoft; else -> pigments.line }
                    )
                }
            }

            if (s.stages.isNotEmpty()) {
                Eyebrow("Life stages & signs · ${e.stagesSeen.size} of ${s.stages.size}")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (st in s.stages) Tag((if (st in e.stagesSeen) "● " else "○ ") + st, if (st in e.stagesSeen) pigments.moss else pigments.inkSoft)
                }
            }
            if (s.phenotypes.isNotEmpty()) {
                Eyebrow("Morphs & seasonal forms")
                for (p in s.phenotypes) Row(verticalAlignment = Alignment.Top) {
                    val got = p.name in e.phenotypesSeen
                    Text(if (got) "●" else "○", color = if (got) pigments.ochre else pigments.inkSoft)
                    GapW(8.dp)
                    Column {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(p.hint, style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                    }
                }
            }

            PaperCard(tint = if (e.level == CodexLevel.COMPLETE) pigments.wash else null) {
                Eyebrow("Deeper entry", color = if (e.level == CodexLevel.COMPLETE) pigments.moss else pigments.inkSoft)
                Gap(4.dp)
                if (e.level == CodexLevel.COMPLETE) Text(s.deeper, style = MaterialTheme.typography.bodyLarge)
                else Text("Sealed. Document every connection in its web to open the rest of this entry.", style = MaterialTheme.typography.bodyMedium, color = pigments.inkSoft)
            }

            if (e.observations.isNotEmpty()) {
                Eyebrow("Your records · ${e.observations.size}")
                for (o in e.observations) PaperCard(padding = PaddingValues(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (o.photoPath != null) { Photo(o.photoPath, Modifier.size(56.dp)); GapW(10.dp) }
                        Column(Modifier.weight(1f)) {
                            Text(dayTime(o.timestamp), style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(o.method.label, habitats[o.habitatId ?: -1]?.name, o.lifeStage, o.phenotype, if (o.heritage) "heritage tree" else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                            if (o.note.isNotBlank()) Text(o.note, style = MaterialTheme.typography.bodyMedium)
                        }
                        TextButton(onClick = { onDelete(o.id) }) { Text("Remove") }
                    }
                }
            }
            Gap(24.dp)
        }
    }
}

private fun label(target: String, catalog: Catalog): String = when {
    target.startsWith("habitat:") -> "this species inside a " + HabitatType.valueOf(target.removePrefix("habitat:")).label.lowercase() + " habitat"
    target.startsWith("group:") -> "any of the " + LifeGroup.valueOf(target.removePrefix("group:")).label.lowercase()
    else -> catalog[target]?.common ?: target
}

private fun nodeGlyph(target: String, catalog: Catalog): String = when {
    target.startsWith("habitat:") -> HabitatType.valueOf(target.removePrefix("habitat:")).glyph
    target.startsWith("group:") -> LifeGroup.valueOf(target.removePrefix("group:")).glyph
    else -> catalog[target]?.glyph ?: "?"
}

/** The species at the centre, its connections around it: solid ink when documented, dashed when still to find. */
@Composable
private fun Web(e: CodexEntry, catalog: Catalog) {
    data class N(val glyph: String, val caption: String, val done: Boolean)
    val seen = e.level != CodexLevel.UNSEEN
    val nodes = e.nodes.map { N(nodeGlyph(it.link.targets.first(), catalog), it.link.type.verb, it.satisfied) } +
        if (e.seasonsNeeded > 0) listOf(N("🗓", "through the seasons", e.seasonNodeDone && seen)) else emptyList()
    val on = pigments.moss
    val off = pigments.inkSoft
    BoxWithConstraints(Modifier.fillMaxWidth().height(250.dp)) {
        val cx = maxWidth / 2
        val cy = maxHeight / 2
        val rx = maxWidth / 2 - 52.dp
        val ry = maxHeight / 2 - 44.dp
        val pts = nodes.indices.map { i ->
            val a = -PI / 2 + 2 * PI * i / nodes.size
            (cx + rx * cos(a).toFloat()) to (cy + ry * sin(a).toFloat())
        }
        Canvas(Modifier.fillMaxSize()) {
            pts.forEachIndexed { i, (x, y) ->
                drawLine(
                    if (nodes[i].done) on else off, Offset(cx.toPx(), cy.toPx()), Offset(x.toPx(), y.toPx()),
                    strokeWidth = if (nodes[i].done) 2.dp.toPx() else 1.dp.toPx(),
                    pathEffect = if (nodes[i].done) null else PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                )
            }
        }
        pts.forEachIndexed { i, (x, y) ->
            Column(Modifier.offset(x - 48.dp, y - 22.dp).width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Stamp(nodes[i].glyph, 40.dp, known = nodes[i].done, ring = on)
                Text(nodes[i].caption, style = MaterialTheme.typography.labelMedium, color = if (nodes[i].done) pigments.ink else pigments.inkSoft, textAlign = TextAlign.Center, maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp).background(pigments.card).padding(horizontal = 3.dp))
            }
        }
        Stamp(e.species.glyph, 60.dp, ring = if (seen) pigments.ink else pigments.line, modifier = Modifier.offset(cx - 30.dp, cy - 30.dp).alpha(if (seen) 1f else 0.4f))
    }
}
