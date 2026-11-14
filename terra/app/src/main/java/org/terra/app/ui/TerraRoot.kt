package org.terra.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.HistoryEdu
import androidx.compose.material.icons.outlined.Park
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.terra.app.Reward
import org.terra.app.Screen
import org.terra.app.Tab
import org.terra.app.TerraViewModel
import org.terra.app.sensing.StillnessService
import org.terra.app.sensing.WalkService
import org.terra.app.sensing.granted

@Composable
fun TerraRoot(vm: TerraViewModel = viewModel()) {
    val context = LocalContext.current
    val d by vm.derived.collectAsStateWithLifecycle()
    val fix by vm.fix.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val analyzing by vm.analyzing.collectAsStateWithLifecycle()
    val sweep by vm.sweep.collectAsStateWithLifecycle()
    val reward by vm.reward.collectAsStateWithLifecycle()
    val charting by vm.charting.collectAsStateWithLifecycle()
    val publicView by vm.publicView.collectAsStateWithLifecycle()
    val stillness by StillnessService.status.collectAsStateWithLifecycle()
    val walking by WalkService.running.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var locationGranted by remember { mutableStateOf(context.granted(Manifest.permission.ACCESS_FINE_LOCATION) || context.granted(Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var recenter by remember { mutableIntStateOf(0) }
    var haptics by remember { mutableStateOf(vm.cues.haptics) }
    var chimes by remember { mutableStateOf(vm.cues.chimes) }
    var afterMic by remember { mutableStateOf<(() -> Unit)?>(null) }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        locationGranted = r.values.any { it }
        if (locationGranted) vm.location.start() else vm.say("Without location, records are pinned to the centre of the map.")
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true || context.granted(Manifest.permission.RECORD_AUDIO)) afterMic?.invoke()
        else vm.say("TERRA cannot listen without the microphone.")
        afterMic = null
    }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { WalkService.start(context) }
    fun withMic(alsoNotify: Boolean, action: () -> Unit) {
        val need = buildList {
            if (!context.granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (alsoNotify && Build.VERSION.SDK_INT >= 33 && !context.granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) action() else { afterMic = action; askMic.launch(need.toTypedArray()) }
    }
    val grantLocation = { askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    val taker = rememberPhotoTaker(vm.photos) { vm.say(it) }
    val savePublic = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/geo+json")) { it?.let { u -> vm.exportGeoJson(u, false) } }
    val saveResearch = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/geo+json")) { it?.let { u -> vm.exportGeoJson(u, true) } }
    val saveArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { it?.let(vm::exportBackup) }
    val openArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::restoreBackup) }

    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.message.value = null } }
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }

    val top = vm.stack.last()
    val p = pigments
    Scaffold(
        containerColor = p.paper,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (top == Screen.Home) NavigationBar(containerColor = p.card) {
                val icons = mapOf(Tab.FIELD to Icons.Outlined.Explore, Tab.CODEX to Icons.AutoMirrored.Outlined.MenuBook, Tab.HABITATS to Icons.Outlined.Park, Tab.QUESTS to Icons.Outlined.Flag, Tab.JOURNAL to Icons.Outlined.HistoryEdu)
                for (t in Tab.entries) NavigationBarItem(
                    selected = tab == t, onClick = { vm.tab.value = t }, icon = { Icon(icons.getValue(t), null) }, label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = p.wash, selectedIconColor = p.moss, selectedTextColor = p.moss, unselectedIconColor = p.inkSoft, unselectedTextColor = p.inkSoft)
                )
            }
        }
    ) { inner ->
        val isMap = top == Screen.Home && tab == Tab.FIELD
        Box(Modifier.fillMaxSize().padding(inner).let { if (isMap) it else it.statusBarsPadding() }) {
            when (top) {
                Screen.Home -> when (tab) {
                    Tab.FIELD -> {
                        val moss = p.moss.toArgb(); val ochre = p.ochre.toArgb(); val rust = p.rust.toArgb()
                        val model = remember(d, fix, publicView, p) {
                            mapModel(d, fix, publicView, { s -> if (s >= 60) moss else if (s >= 35) ochre else rust }, p.card.toArgb(), p.ink.toArgb()) { vm.catalog[it]?.glyph }
                        }
                        val start = remember { vm.here.let { it.lat to it.lon } }
                        FieldScreen(
                            d, fix, locationGranted, publicView, charting, walking,
                            onGrant = grantLocation,
                            onScan = { vm.open(Screen.Scan) }, onSweep = { vm.open(Screen.Sweep) }, onStillness = { vm.open(Screen.Stillness) },
                            onChart = vm::chart, onMark = vm::markHabitat, onTogglePublic = { vm.publicView.value = !publicView },
                            onHabitat = { vm.open(Screen.HabitatDetail(it)) }, onRecenter = { if (fix == null) vm.say("Still waiting for a GPS fix.") else recenter++ },
                            onWalk = {
                                when {
                                    walking -> WalkService.stop(context)
                                    !locationGranted -> grantLocation()
                                    Build.VERSION.SDK_INT >= 33 && !context.granted(Manifest.permission.POST_NOTIFICATIONS) -> askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else -> WalkService.start(context)
                                }
                            }
                        ) { m ->
                            OsmMap(model, start, recenter, m, onArea = { vm.open(Screen.HabitatDetail(it)) },
                                onPin = { if (vm.catalog[it] != null) vm.open(Screen.SpeciesDetail(it)) }, onCenter = { la, lo -> vm.mapCenter = la to lo })
                        }
                    }
                    Tab.CODEX -> CodexScreen(d) { vm.open(Screen.SpeciesDetail(it)) }
                    Tab.HABITATS -> HabitatsScreen(d, vm.here, charting, { vm.open(Screen.HabitatDetail(it)) }, vm::chart)
                    Tab.QUESTS -> QuestScreen(d)
                    Tab.JOURNAL -> JournalScreen(d, vm.catalog, { vm.open(Screen.Stillness) }, { vm.open(Screen.NewLandmark) }, { vm.open(Screen.LandmarkDetail(it)) },
                        { vm.open(Screen.Data) }, vm::reviewDetection, { vm.dismissDetection(it) }, { vm.open(Screen.SpeciesDetail(it)) })
                }
                is Screen.SpeciesDetail -> d.entry(top.id)?.let { e ->
                    SpeciesDetailScreen(d, e, vm.catalog, { vm.back() }, { vm.open(Screen.SpeciesDetail(it)) }, { vm.deleteObservation(it) })
                }
                is Screen.HabitatDetail -> d.world.habitats.firstOrNull { it.id == top.id }?.let { h ->
                    HabitatDetailScreen(d, h, vm.catalog, { vm.back() }, { vm.open(Screen.SpeciesDetail(it)) }, { vm.open(Screen.NewAction(h.id)) },
                        { a -> taker.take("after") { f -> vm.completeAction(a, f) } })
                }
                is Screen.NewAction -> d.world.habitats.firstOrNull { it.id == top.habitatId }?.let { h ->
                    NewActionScreen(h, { vm.back() }) { kind, note -> taker.take("before") { f -> vm.beginAction(h.id, kind, note, f) } }
                }
                Screen.Scan -> ScanScreen(draft, analyzing, vm.catalog, d.region, fix != null, { vm.back() },
                    { taker.take("obs") { vm.analyzePhoto(it) } }, { taker.pick("obs") { vm.analyzePhoto(it) } }, vm::save)
                Screen.Sweep -> SweepScreen(draft, sweep, vm.catalog, d.region, fix != null, { vm.back() }, { withMic(false) { vm.startSweep() } }, vm::cancelSweep, vm::save)
                Screen.Stillness -> StillnessScreen(stillness, d, vm.catalog, { vm.back() },
                    onBegin = { withMic(true) { StillnessService.start(context) } }, onEnd = { StillnessService.stop(context) },
                    onDone = { StillnessService.acknowledge(); vm.back(); vm.tab.value = Tab.JOURNAL })
                Screen.NewLandmark -> NewLandmarkScreen({ vm.back() }, { done -> taker.take("landmark", done) }, fix != null, vm::adopt)
                is Screen.LandmarkDetail -> d.world.landmarks.firstOrNull { it.id == top.id }?.let { lm ->
                    LandmarkDetailScreen(d, lm, { vm.back() }, { done -> taker.take("landmark", done) }) { note, girth, photo -> vm.addEntry(lm.id, note, girth, photo) }
                }
                Screen.Data -> DataScreen(haptics, chimes, { haptics = it; vm.cues.haptics = it }, { chimes = it; vm.cues.chimes = it }, { vm.back() },
                    { savePublic.launch("terra-public-atlas.geojson") }, { saveResearch.launch("terra-research-precise.geojson") },
                    { saveArchive.launch("terra-archive.zip") }, { openArchive.launch(arrayOf("application/zip", "application/octet-stream")) })
            }
        }
    }
    reward?.let { RewardDialog(it) { vm.reward.value = null } }
}

@Composable
fun RewardDialog(r: Reward, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(r.title) },
        text = { Column { for (line in r.lines) Text("· $line", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) } },
        confirmButton = { Button(onClick = onDismiss) { Text("Eyes up") } }
    )
}
