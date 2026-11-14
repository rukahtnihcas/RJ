package org.terra.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import org.terra.app.Candidate
import org.terra.app.Draft
import org.terra.app.SweepState
import org.terra.app.domain.Catalog
import org.terra.app.model.LifeGroup
import org.terra.app.model.ObsMethod
import org.terra.app.model.Phenotype
import org.terra.app.model.Region
import org.terra.app.model.Species
import kotlin.math.ln

@Composable
fun ScanScreen(
    draft: Draft?, analyzing: Boolean, catalog: Catalog, region: Region, hasFix: Boolean,
    onBack: () -> Unit, onTake: () -> Unit, onPick: () -> Unit,
    onSave: (Species?, String, LifeGroup, String?, String?, Boolean, String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Bio-scan", "Photograph it; the guide narrows it; you decide", onBack)
        when {
            analyzing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Gap()
                    Text("Reading the photograph…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            draft != null -> IdentifyPanel(draft, catalog, region, hasFix, onSave)
            else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PaperCard {
                    Eyebrow("How to scan")
                    Gap(6.dp)
                    Text("Fill the frame with one organism. For plants, include a leaf and a flower. For fungi, get the cap and the stem. Keep your distance from nests and dens.", style = MaterialTheme.typography.bodyMedium)
                    Gap(6.dp)
                    Text("Recognition runs on this phone. Your photo stays in your journal.", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                }
                Button(onClick = onTake, modifier = Modifier.fillMaxWidth()) { Text("Take a photograph") }
                OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Choose one from the gallery") }
            }
        }
    }
}

@Composable
fun SweepScreen(
    draft: Draft?, state: SweepState, catalog: Catalog, region: Region, hasFix: Boolean,
    onBack: () -> Unit, onStart: () -> Unit, onCancel: () -> Unit,
    onSave: (Species?, String, LifeGroup, String?, String?, Boolean, String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(if (draft?.method == ObsMethod.STILLNESS) "Heard in stillness" else "Acoustic sweep", "Ten seconds of listening", onBack)
        when {
            draft != null -> IdentifyPanel(draft, catalog, region, hasFix, onSave)
            state is SweepState.Listening -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PaperCard {
                    Eyebrow("Listening", color = pigments.moss)
                    Gap(8.dp)
                    Spectrum(state.spectrum, Modifier.fillMaxWidth().height(140.dp))
                    Gap(8.dp)
                    Meter(state.progress)
                    Gap(8.dp)
                    Text("Hold still. Point the bottom of the phone toward the sound.", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            }
            else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PaperCard {
                    Eyebrow("How it listens")
                    Gap(6.dp)
                    Text("TERRA measures the pitch, tempo and tone of the loudest natural sound and compares it with the calls of birds, frogs and insects found in your region.", style = MaterialTheme.typography.bodyMedium)
                    Gap(6.dp)
                    Text("It offers candidates, not verdicts — match them by ear. The recording is analysed on this phone and discarded.", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                    if (state is SweepState.Failed) { Gap(6.dp); Text("The microphone could not be opened.", color = pigments.rust, style = MaterialTheme.typography.bodyMedium) }
                }
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Begin a 10-second sweep") }
            }
        }
    }
}

/** A live spectrum on a log-ish scale from 100 Hz to 11 kHz, drawn as ink bars. */
@Composable
fun Spectrum(power: FloatArray?, modifier: Modifier = Modifier) {
    val ink = pigments.moss
    val line = pigments.line
    Canvas(modifier) {
        drawLine(line, Offset(0f, size.height - 1), Offset(size.width, size.height - 1))
        if (power == null) return@Canvas
        val bars = 48
        val n = power.size
        val w = size.width / bars
        for (i in 0 until bars) {
            val lo = (3 + (n - 3) * Math.pow(i / bars.toDouble(), 2.0)).toInt()
            val hi = (3 + (n - 3) * Math.pow((i + 1) / bars.toDouble(), 2.0)).toInt().coerceAtLeast(lo + 1).coerceAtMost(n)
            var m = 0f
            for (b in lo until hi) if (power[b] > m) m = power[b]
            val db = (10 * ln(m + 1e-9f) / ln(10f))
            val h = ((db + 50f) / 70f).coerceIn(0.02f, 1f) * size.height
            drawRect(ink, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h))
        }
    }
}

@Composable
fun IdentifyPanel(
    draft: Draft, catalog: Catalog, region: Region, hasFix: Boolean,
    onSave: (Species?, String, LifeGroup, String?, String?, Boolean, String) -> Unit
) {
    var chosen by remember(draft) { mutableStateOf(draft.candidates.singleOrNull()?.species) }
    var query by remember(draft) { mutableStateOf("") }
    var custom by remember(draft) { mutableStateOf(false) }
    var customName by remember(draft) { mutableStateOf("") }
    var customGroup by remember(draft) { mutableStateOf<LifeGroup?>(LifeGroup.PLANT) }
    var stage by remember(chosen) { mutableStateOf<String?>(null) }
    var pheno by remember(chosen) { mutableStateOf<Phenotype?>(null) }
    var heritage by remember(chosen) { mutableStateOf(false) }
    var note by remember(draft) { mutableStateOf("") }
    var browse by remember(draft) { mutableStateOf<LifeGroup?>(null) }
    val results = remember(query, browse) {
        if (query.isNotBlank()) catalog.search(query, region).take(8)
        else browse?.let { g -> catalog.inRegion(region).filter { it.group == g }.sortedBy { it.common } } ?: emptyList()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Photo(draft.photoPath, Modifier.fillMaxWidth().height(200.dp))
        if (draft.evidence.isNotBlank()) Text(draft.evidence, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)

        val picked = chosen
        if (picked != null) {
            PaperCard(tint = pigments.wash) {
                Eyebrow("Your identification", color = pigments.moss)
                Gap(6.dp)
                SpeciesRow(picked, picked.standing(region), null)
                Gap(4.dp)
                Text(picked.blurb, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { chosen = null }, contentPadding = PaddingValues(0.dp)) { Text("Choose something else") }
            }
        } else if (!custom) {
            if (draft.candidates.isNotEmpty()) {
                Eyebrow(if (draft.method == ObsMethod.PHOTO) "Likely here, now — pick the match" else "Candidates — match by ear")
                for (c in draft.candidates) PaperCard(onClick = { chosen = c.species }, padding = PaddingValues(10.dp)) {
                    SpeciesRow(c.species, c.species.standing(region), c.why)
                }
            }
            Eyebrow("Or browse what lives in ${region.label}")
            ChoiceChips(LifeGroup.entries.filter { it != LifeGroup.OTHER }, browse, { "${it.glyph} ${it.label}" }, { browse = it })
            OutlinedTextField(query, { query = it }, label = { Text("Search the whole guide") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            for (s in results) PaperCard(onClick = { chosen = s; query = "" }, padding = PaddingValues(10.dp)) { SpeciesRow(s, s.standing(region), null) }
            TextButton(onClick = { custom = true }) { Text("It is not in the guide — record it anyway") }
        } else {
            PaperCard {
                Eyebrow("Not in the guide")
                Gap(6.dp)
                OutlinedTextField(customName, { customName = it }, label = { Text("What do you call it?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Gap(8.dp)
                ChoiceChips(LifeGroup.entries, customGroup, { "${it.glyph} ${it.label}" }, { customGroup = it ?: customGroup })
                TextButton(onClick = { custom = false }, contentPadding = PaddingValues(0.dp)) { Text("Back to the guide") }
            }
        }

        if (picked != null) {
            if (picked.stages.isNotEmpty()) {
                Eyebrow("Life stage or sign")
                ChoiceChips(picked.stages, stage, { it }, { stage = it })
            }
            if (picked.phenotypes.isNotEmpty()) {
                Eyebrow("Morph or seasonal form")
                ChoiceChips(picked.phenotypes, pheno, { it.name }, { pheno = it })
                pheno?.let { Text(it.hint, style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft) }
            }
            if (picked.group == LifeGroup.TREE) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ancient or heritage tree", style = MaterialTheme.typography.titleMedium)
                    Text("Hollow, huge, or older than the street around it", style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
                }
                Switch(heritage, { heritage = it })
            }
        }
        if (picked != null || custom) {
            OutlinedTextField(note, { note = it }, label = { Text("Field note (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Text(
                if (hasFix) "Recorded at your current position." else "No GPS fix yet — this will be recorded at the centre of the map.",
                style = MaterialTheme.typography.bodySmall, color = if (hasFix) pigments.inkSoft else pigments.rust
            )
            if (picked?.sensitive == true) Text("Sensitive species: the open atlas will only ever show this to the nearest ~20 km.", style = MaterialTheme.typography.bodySmall, color = pigments.water)
            Button(
                onClick = { onSave(picked, customName, customGroup ?: LifeGroup.OTHER, stage, pheno?.name, heritage, note) },
                enabled = picked != null || customName.isNotBlank(), modifier = Modifier.fillMaxWidth()
            ) { Text("Add to field journal") }
        }
        Gap(24.dp)
    }
}

@Composable
fun SpeciesRow(s: Species, standing: String?, why: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Stamp(s.glyph, 44.dp)
        GapW(10.dp)
        Column(Modifier.weight(1f)) {
            Text(s.common, style = MaterialTheme.typography.titleMedium)
            Text(s.scientific, style = MaterialTheme.typography.titleSmall, color = pigments.inkSoft)
            if (!why.isNullOrBlank()) Text(why, style = MaterialTheme.typography.bodySmall, color = pigments.inkSoft)
        }
        if (standing != null) Tag(standing, when (standing) { "Invasive here" -> pigments.rust; "Native here" -> pigments.moss; else -> pigments.inkSoft })
    }
}
