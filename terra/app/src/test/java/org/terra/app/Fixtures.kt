package org.terra.app

import org.terra.app.domain.Catalog
import org.terra.app.domain.World
import org.terra.app.model.ActionKind
import org.terra.app.model.Detection
import org.terra.app.model.Habitat
import org.terra.app.model.HabitatSource
import org.terra.app.model.HabitatType
import org.terra.app.model.Landmark
import org.terra.app.model.LandmarkEntry
import org.terra.app.model.LandmarkKind
import org.terra.app.model.ObsMethod
import org.terra.app.model.Observation
import org.terra.app.model.StewardAction
import org.terra.app.model.StillnessSession
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

object Fixtures {
    val catalog: Catalog by lazy {
        val f = listOf("src/main/assets/catalog.json", "app/src/main/assets/catalog.json").map(::File).first { it.exists() }
        Catalog.parse(f.readText())
    }

    // Toronto-ish: North America, northern hemisphere.
    const val LAT = 43.65
    const val LON = -79.38
    const val DAY = 86_400_000L

    fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val meadow = Habitat(1, "Riverside Meadow", HabitatType.MEADOW, LAT, LON, 200.0, HabitatSource.OSM, 11, at(2026, 5, 1), at(2026, 5, 1))
    val wood = Habitat(2, "Cedarvale Ravine", HabitatType.WOODLAND, LAT + 0.01, LON, 300.0, HabitatSource.OSM, 12, null, at(2026, 5, 1))
    val pond = Habitat(3, "Mill Pond", HabitatType.WATER, LAT - 0.004, LON + 0.003, 60.0, HabitatSource.PLAYER, null, at(2026, 6, 1), at(2026, 6, 1))

    private var nextId = 1L
    fun obs(
        species: String, time: Long, lat: Double = LAT, lon: Double = LON, habitat: Long? = 1,
        method: ObsMethod = ObsMethod.PHOTO, stage: String? = null, pheno: String? = null, heritage: Boolean = false
    ): Observation {
        val s = catalog[species]!!
        return Observation(nextId++, species, s.common, s.group, lat, lon, 8f, time, method, null, stage, pheno, heritage, "", habitat, s.sensitive)
    }

    /** A lived-in journal: a monarch web completed, an invasive logged, stewardship under way. */
    fun world(now: Long = at(2026, 10, 14)): World {
        val observations = listOf(
            obs("monarch", at(2026, 7, 2), stage = "caterpillar"),
            obs("monarch", at(2026, 9, 20), stage = "adult"),
            obs("common_milkweed", at(2026, 7, 2), stage = "flowering"),
            obs("purple_coneflower", at(2026, 7, 10)),
            obs("common_eastern_bumblebee", at(2026, 7, 10), stage = "worker"),
            obs("american_goldfinch", at(2026, 9, 21), pheno = "Breeding plumage"),
            obs("red_tailed_hawk", at(2026, 9, 28)),
            obs("eastern_gray_squirrel", at(2026, 10, 2), pheno = "Black morph"),
            obs("white_oak", at(2026, 10, 2), heritage = true, stage = "veteran"),
            obs("turkey_tail", at(2026, 10, 9), lat = LAT + 0.01, habitat = 2),
            obs("garlic_mustard", at(2026, 5, 20), lat = LAT + 0.01, habitat = 2),
            obs("peregrine_falcon", at(2026, 10, 11), lat = LAT - 0.0043, lon = LON + 0.003, habitat = 3),
            obs("spring_peeper", at(2026, 4, 18, 21), lat = LAT - 0.004, lon = LON + 0.003, habitat = 3, method = ObsMethod.ACOUSTIC)
        ).sortedByDescending { it.timestamp }
        val actions = listOf(
            StewardAction(1, 2, ActionKind.REMOVE_INVASIVE, "Pulled garlic mustard along the east path", "/none/b.jpg", LAT + 0.01, LON, at(2026, 5, 21, 9),
                "/none/a.jpg", LAT + 0.01001, LON, at(2026, 5, 21, 11)),
            StewardAction(2, 1, ActionKind.PLANT_NATIVE, "Milkweed plugs by the fence", "/none/b2.jpg", LAT, LON, at(2026, 10, 12, 9))
        )
        val landmarks = listOf(Landmark(1, "The Fence-line Oak", LandmarkKind.OLD_GROWTH, "White Oak", LAT, LON, at(2024, 10, 1)))
        val entries = listOf(
            LandmarkEntry(1, 1, at(2024, 10, 1), null, "Adopted on a wet morning. Lowest limb is head height.", 310f),
            LandmarkEntry(2, 1, at(2025, 10, 4), null, "A big mast year. Squirrels everywhere.", 312.5f),
            LandmarkEntry(3, 1, at(2026, 10, 2), null, "Chicken of the woods on the north buttress.", 315f)
        )
        val sessions = listOf(StillnessSession(1, at(2026, 10, 10, 7), 600, LAT, LON, true, 0.9f))
        val detections = listOf(Detection(1, 1, at(2026, 10, 10, 7), "black_capped_chickadee", 0.71f))
        return World(observations, listOf(meadow, wood, pond), actions, landmarks, entries, sessions, detections)
    }
}
