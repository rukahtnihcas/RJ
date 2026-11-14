package org.terra.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.terra.app.domain.AcousticMatcher
import org.terra.app.domain.Badge
import org.terra.app.domain.BadgeEngine
import org.terra.app.domain.BountyEngine
import org.terra.app.domain.BountyProgress
import org.terra.app.domain.Catalog
import org.terra.app.domain.CodexEngine
import org.terra.app.domain.CodexEntry
import org.terra.app.domain.CodexLevel
import org.terra.app.domain.Exporter
import org.terra.app.domain.HabitatEngine
import org.terra.app.domain.HabitatHealth
import org.terra.app.domain.Phenology
import org.terra.app.domain.World
import org.terra.app.model.ActionKind
import org.terra.app.model.Detection
import org.terra.app.model.Habitat
import org.terra.app.model.HabitatType
import org.terra.app.model.Landmark
import org.terra.app.model.LandmarkEntry
import org.terra.app.model.LandmarkKind
import org.terra.app.model.LifeGroup
import org.terra.app.model.ObsMethod
import org.terra.app.model.Observation
import org.terra.app.model.Region
import org.terra.app.model.Season
import org.terra.app.model.Species
import org.terra.app.model.StewardAction
import org.terra.app.sensing.Fix
import org.terra.app.sensing.Listener
import org.terra.app.sensing.Vision
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class SpeciesDetail(val id: String) : Screen
    data class HabitatDetail(val id: Long) : Screen
    data object Scan : Screen
    data object Sweep : Screen
    data object Stillness : Screen
    data object NewLandmark : Screen
    data class LandmarkDetail(val id: Long) : Screen
    data class NewAction(val habitatId: Long) : Screen
    data object Data : Screen
}

enum class Tab(val label: String) { FIELD("Field"), CODEX("Codex"), HABITATS("Habitats"), QUESTS("Quests"), JOURNAL("Journal") }

/** Everything the screens show, derived from the raw records. */
data class Derived(
    val world: World = World(),
    val entries: List<CodexEntry> = emptyList(),
    val health: Map<Long, HabitatHealth> = emptyMap(),
    val badges: List<Badge> = emptyList(),
    val bounties: List<BountyProgress> = emptyList(),
    val region: Region = Region.NA,
    val season: Season = Season.SPRING,
    val loaded: Boolean = false
) {
    val discovered: Set<String> get() = entries.filter { it.level != CodexLevel.UNSEEN }.map { it.species.id }.toSet()
    fun entry(id: String) = entries.firstOrNull { it.species.id == id }
}

data class Candidate(val species: Species, val score: Float, val why: String)

/** An observation waiting for the observer's decision. */
data class Draft(
    val method: ObsMethod,
    val photoPath: String? = null,
    val candidates: List<Candidate> = emptyList(),
    val evidence: String = "",
    val detectionId: Long? = null
)

data class Reward(val title: String, val lines: List<String>)

sealed interface SweepState {
    data object Idle : SweepState
    data class Listening(val progress: Float, val spectrum: FloatArray?) : SweepState
    data object Failed : SweepState
}

class TerraViewModel(app: Application) : AndroidViewModel(app) {
    private val terra = app as TerraApp
    val catalog: Catalog = terra.catalog
    val repo = terra.repo
    val photos = terra.photos
    val cues = terra.cues
    val location = terra.location
    val fix: StateFlow<Fix?> = location.fix

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val tab = MutableStateFlow(Tab.FIELD)
    val message = MutableStateFlow<String?>(null)
    val draft = MutableStateFlow<Draft?>(null)
    val analyzing = MutableStateFlow(false)
    val sweep = MutableStateFlow<SweepState>(SweepState.Idle)
    val reward = MutableStateFlow<Reward?>(null)
    val charting = MutableStateFlow(false)
    val publicView = MutableStateFlow(false)
    /** Where the map is looking — the fallback position when there is no GPS fix. */
    var mapCenter: Pair<Double, Double>? = null
    private var sweepJob: Job? = null

    private val place = fix.map { it ?: location.home }.map { Region.of(it.lat, it.lon) to (it.lat >= 0) }.distinctUntilChanged()

    val derived: StateFlow<Derived> = combine(repo.world, place) { w, _ -> derive(w, catalog, location.home, System.currentTimeMillis()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Derived())

    init {
        viewModelScope.launch { terra.notices.collect { say(it) } }
    }

    val here: Fix get() = fix.value ?: mapCenter?.let { Fix(it.first, it.second, null, System.currentTimeMillis()) } ?: location.home
    val hasFix get() = fix.value != null

    // ── navigation ──
    fun open(s: Screen) { stack.add(s) }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        val top = stack.removeAt(stack.lastIndex)
        if (top == Screen.Scan || top == Screen.Sweep || top == Screen.Stillness) { draft.value = null; cancelSweep() }
        return true
    }
    private fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun say(text: String) { message.value = text }

    // ── bio-scan ──
    fun analyzePhoto(file: File) = viewModelScope.launch {
        analyzing.value = true
        val ok = withContext(Dispatchers.IO) { photos.normalize(file) }
        if (!ok) { analyzing.value = false; say("That photo could not be read."); return@launch }
        val labels = Vision.labels(getApplication(), Uri.fromFile(file))
        val f = here
        val d = derived.value
        val habitat = HabitatEngine.containing(d.world.habitats, f.lat, f.lon)
        val groups = Catalog.groupsSeen(labels)
        val suggestions = catalog.suggest(labels, d.region, d.season, habitat?.type)
        val evidence = if (groups.isEmpty()) "The camera could not place this. It can tell birds, plants, trees and insects apart; for fungi, frogs, reptiles and mammals, choose the group below."
        else "The camera sees: " + groups.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.label.lowercase()} (${(it.value * 100).toInt()}%)" }
        draft.value = Draft(ObsMethod.PHOTO, file.path, if (groups.isEmpty()) emptyList() else suggestions.map { Candidate(it.species, it.score, it.why) }, evidence)
        analyzing.value = false
    }

    // ── acoustic sweep ──
    fun startSweep(seconds: Int = 10) {
        if (sweepJob?.isActive == true) return
        sweepJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            sweep.value = SweepState.Listening(0f, null)
            val pcm = Listener.record(seconds) { spec ->
                sweep.value = SweepState.Listening(((System.currentTimeMillis() - started) / (seconds * 1000f)).coerceIn(0f, 1f), spec)
            }
            if (pcm == null || pcm.size < Listener.SAMPLE_RATE) { sweep.value = SweepState.Failed; return@launch }
            val f = withContext(Dispatchers.Default) { AcousticMatcher.analyze(AcousticMatcher.toFloat(pcm), Listener.SAMPLE_RATE) }
            val d = derived.value
            draft.value = if (f == null) Draft(ObsMethod.ACOUSTIC, evidence = "Too quiet to analyse. Move closer or try again; you can still pick a species by ear.")
            else Draft(ObsMethod.ACOUSTIC,
                candidates = AcousticMatcher.rank(f, catalog.inRegion(d.region)).map {
                    Candidate(it.species, it.score, it.species.acoustic?.sounds.orEmpty())
                }, evidence = AcousticMatcher.describe(f))
            sweep.value = SweepState.Idle
        }
    }
    fun cancelSweep() { sweepJob?.cancel(); sweep.value = SweepState.Idle }

    fun reviewDetection(d: Detection) {
        val s = catalog[d.speciesId] ?: return
        draft.value = Draft(ObsMethod.STILLNESS, candidates = listOf(Candidate(s, d.score, s.acoustic?.sounds.orEmpty())),
            evidence = "Heard during a Stillness sit. Confirm only if it matches what you heard.", detectionId = d.id)
        open(Screen.Sweep)
    }
    fun dismissDetection(d: Detection) = viewModelScope.launch { repo.resolveDetection(d.id, 2) }

    // ── saving ──
    fun save(
        species: Species?, customName: String, group: LifeGroup, stage: String?, phenotype: String?,
        heritage: Boolean, note: String
    ) = viewModelScope.launch {
        val d = draft.value ?: return@launch
        val f = here
        val before = derived.value
        val saved = repo.addObservation(Observation(
            speciesId = species?.id ?: "custom:${customName.trim().lowercase()}",
            displayName = species?.common ?: customName.trim(), group = species?.group ?: group,
            lat = f.lat, lon = f.lon, accuracyM = f.accuracyM, timestamp = System.currentTimeMillis(),
            method = d.method, photoPath = d.photoPath, lifeStage = stage, phenotype = phenotype,
            heritage = heritage, note = note.trim()))
        d.detectionId?.let { repo.resolveDetection(it, 1) }
        val after = withContext(Dispatchers.Default) {
            derive(before.world.copy(observations = listOf(saved) + before.world.observations), catalog, f, saved.timestamp)
        }
        reward.value = rewardFor(saved, species, before, after)
        cues.pulse(1)
        draft.value = null
        home()
    }

    private fun rewardFor(o: Observation, species: Species?, before: Derived, after: Derived): Reward {
        val lines = ArrayList<String>()
        val habitat = after.world.habitats.firstOrNull { it.id == o.habitatId }
        if (species != null && before.entry(species.id)?.level == CodexLevel.UNSEEN) lines += "New Codex entry: ${species.common}"
        for (e in after.entries) {
            val old = before.entry(e.species.id) ?: continue
            if (old.level == CodexLevel.UNSEEN && e.species.id != species?.id) continue
            val gained = e.nodes.filter { n -> n.satisfied && old.nodes.none { it.link == n.link && it.satisfied } }
            for (n in gained) lines += "Connection found: ${n.phrase(e.species.common)}"
            if (e.seasonNodeDone && !old.seasonNodeDone && e.seasonsNeeded > 0) lines += "Seasonal presence documented for ${e.species.common}"
            if (e.level == CodexLevel.COMPLETE && old.level != CodexLevel.COMPLETE) lines += "Web complete — the deeper entry for ${e.species.common} is unlocked"
        }
        after.badges.filter { b -> b.earned && before.badges.none { it.id == b.id && it.earned } }.forEach { lines += "Badge earned: ${it.glyph} ${it.name}" }
        if (habitat != null) {
            val was = before.health[habitat.id]?.score ?: 0
            val now = after.health[habitat.id]?.score ?: was
            if (now != was) lines += "${habitat.name} health ${if (now > was) "rose" else "fell"} to $now"
            if (species?.isInvasiveIn(after.region) == true) lines += "Invasive recorded — a removal action here would restore the habitat's health"
        }
        if (o.sensitive) lines += "Sensitive species: its public location is obscured to a ~20 km grid"
        if (lines.isEmpty()) lines += "Added to your field journal and the atlas."
        return Reward("${o.displayName} logged", lines)
    }

    // ── habitats & stewardship ──
    fun chart() = viewModelScope.launch {
        if (charting.value) return@launch
        charting.value = true
        val f = here
        repo.chartHabitats(f.lat, f.lon).fold(
            { say(if (it == 0) "No new habitats found within 1.5 km." else "Charted $it habitats nearby.") },
            { say("Could not reach the map service. You can still mark a habitat by hand.") })
        charting.value = false
    }

    fun markHabitat(name: String, type: HabitatType, radiusM: Double) = viewModelScope.launch {
        val f = here
        repo.addPlayerHabitat(name.ifBlank { "My ${type.label.lowercase()}" }, type, f.lat, f.lon, radiusM)
        say("Micro-habitat marked.")
    }

    fun beginAction(habitatId: Long, kind: ActionKind, note: String, photo: File) = viewModelScope.launch {
        withContext(Dispatchers.IO) { photos.normalize(photo) }
        val f = here
        repo.addAction(StewardAction(habitatId = habitatId, kind = kind, note = note.trim(), beforePhoto = photo.path,
            beforeLat = f.lat, beforeLon = f.lon, beforeAt = System.currentTimeMillis()))
        say("Before photo saved. Come back with an after photo when the work is done.")
        back()
    }

    fun completeAction(a: StewardAction, photo: File) = viewModelScope.launch {
        withContext(Dispatchers.IO) { photos.normalize(photo) }
        val f = here
        val done = a.copy(afterPhoto = photo.path, afterLat = f.lat, afterLon = f.lon, afterAt = System.currentTimeMillis())
        repo.updateAction(done)
        val c = HabitatEngine.check(done)
        say(when {
            c.verified -> "Action verified: same place, and time has passed. Thank you."
            !c.sameSpot -> "Saved, but the after photo is more than ${HabitatEngine.SAME_SPOT_M.toInt()} m from the before photo, so it is not verified."
            else -> "Saved, but before and after are less than 10 minutes apart, so it is not verified."
        })
    }

    // ── legacy landmarks ──
    fun adopt(name: String, kind: LandmarkKind, speciesName: String, note: String, girth: Float?, photo: File?) = viewModelScope.launch {
        photo?.let { withContext(Dispatchers.IO) { photos.normalize(it) } }
        val f = here
        val now = System.currentTimeMillis()
        val id = repo.addLandmark(Landmark(name = name.trim(), kind = kind, speciesName = speciesName.trim(), lat = f.lat, lon = f.lon, adoptedAt = now),
            LandmarkEntry(landmarkId = 0, timestamp = now, photoPath = photo?.path, note = note.trim(), girthCm = girth))
        back()
        open(Screen.LandmarkDetail(id))
    }

    fun addEntry(landmarkId: Long, note: String, girth: Float?, photo: File?) = viewModelScope.launch {
        photo?.let { withContext(Dispatchers.IO) { photos.normalize(it) } }
        repo.addEntry(LandmarkEntry(landmarkId = landmarkId, timestamp = System.currentTimeMillis(), photoPath = photo?.path, note = note.trim(), girthCm = girth))
    }

    fun deleteObservation(id: Long) = viewModelScope.launch { repo.deleteObservation(id) }

    // ── open data ──
    fun exportGeoJson(uri: Uri, research: Boolean) = viewModelScope.launch {
        runCatching {
            val obj = Exporter.geoJson(derived.value.world.observations, catalog, research)
            repo.writeText(uri, Exporter.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj))
        }.fold({ say(if (research) "Research-grade file saved. Share it only with vetted researchers." else "Public atlas file saved.") }, { say("Export failed.") })
    }
    fun exportBackup(uri: Uri) = viewModelScope.launch {
        runCatching { repo.writeBackup(uri, derived.value.world) }.fold({ say("Archive saved.") }, { say("Archive failed.") })
    }
    fun restoreBackup(uri: Uri) = viewModelScope.launch {
        repo.restoreBackup(uri).fold({ say("Restored $it observations.") }, { say("That file is not a TERRA archive.") })
    }

    companion object {
        fun derive(w: World, catalog: Catalog, at: Fix, now: Long): Derived {
            val entries = CodexEngine.entries(catalog, w.observations, w.habitats)
            return Derived(
                w, entries,
                w.habitats.associate { it.id to HabitatEngine.health(it, w.observations, w.actions, catalog, now) },
                BadgeEngine.badges(w, catalog, entries),
                BountyEngine.current(w, catalog, at.lat, at.lon, now),
                Region.of(at.lat, at.lon), Phenology.season(now, at.lat), loaded = true
            )
        }
    }
}
