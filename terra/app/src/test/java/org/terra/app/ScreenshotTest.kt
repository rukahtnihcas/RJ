package org.terra.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.terra.app.Fixtures.LAT
import org.terra.app.Fixtures.LON
import org.terra.app.Fixtures.at
import org.terra.app.Fixtures.catalog
import org.terra.app.model.ObsMethod
import org.terra.app.model.Region
import org.terra.app.sensing.Fix
import org.terra.app.sensing.StillnessStatus
import org.terra.app.ui.CodexScreen
import org.terra.app.ui.DataScreen
import org.terra.app.ui.FieldScreen
import org.terra.app.ui.HabitatDetailScreen
import org.terra.app.ui.HabitatsScreen
import org.terra.app.ui.IdentifyPanel
import org.terra.app.ui.JournalScreen
import org.terra.app.ui.LandmarkDetailScreen
import org.terra.app.ui.QuestScreen
import org.terra.app.ui.RewardDialog
import org.terra.app.ui.SpeciesDetailScreen
import org.terra.app.ui.StillnessScreen
import org.terra.app.ui.TerraTheme
import org.terra.app.ui.pigments

/** Renders every main screen with a lived-in journal so the layouts can be reviewed without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ScreenshotTest {
    private val now = at(2026, 10, 14)
    private val fix = Fix(LAT, LON, 6f, now)
    private val d = TerraViewModel.derive(Fixtures.world(), catalog, fix, now)

    private fun shot(name: String, dark: Boolean = false, tall: Boolean = false, content: @Composable () -> Unit) {
        captureRoboImage("build/shots/$name.png") {
            TerraTheme(dark = dark) { Box(Modifier.fillMaxSize().background(pigments.paper)) { content() } }
        }
    }

    @Test fun field() = shot("field") {
        FieldScreen(d, fix, true, false, false, false, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}) { m -> Box(m.background(pigments.wash)) }
    }
    @Test fun codex() = shot("codex") { CodexScreen(d) {} }
    @Test fun codexDark() = shot("codex_dark", dark = true) { CodexScreen(d) {} }
    @Test fun species() = shot("species") { SpeciesDetailScreen(d, d.entry("monarch")!!, catalog, {}, {}, {}) }
    @Test fun speciesLocked() = shot("species_locked") { SpeciesDetailScreen(d, d.entry("red_tailed_hawk")!!, catalog, {}, {}, {}) }
    @Test fun habitats() = shot("habitats") { HabitatsScreen(d, fix, false, {}, {}) }
    @Test fun habitat() = shot("habitat") { HabitatDetailScreen(d, Fixtures.wood, catalog, {}, {}, {}, {}) }
    @Test fun quests() = shot("quests") { QuestScreen(d) }
    @Test fun journal() = shot("journal") { JournalScreen(d, catalog, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun landmark() = shot("landmark") { LandmarkDetailScreen(d, d.world.landmarks[0], {}, {}, { _, _, _ -> }) }
    @Test fun stillness() = shot("stillness") { StillnessScreen(StillnessStatus(true, 252, 600, 1, true), d, catalog, {}, {}, {}, {}) }
    @Test fun data() = shot("data") { DataScreen(true, true, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun identify() = shot("identify") {
        val c = catalog.suggest(listOf("Bird" to 0.86f), Region.NA, d.season, null).take(4).map { Candidate(it.species, it.score, it.why) }
        IdentifyPanel(Draft(ObsMethod.PHOTO, null, c, "The camera sees: birds (86%)"), catalog, Region.NA, true) { _, _, _, _, _, _, _ -> }
    }
    @Test fun reward() = shot("reward") {
        RewardDialog(Reward("Common Milkweed logged", listOf("New Codex entry: Common Milkweed", "Connection found: Monarch raises its young on Common Milkweed", "Riverside Meadow health rose to 58"))) {}
    }
}
