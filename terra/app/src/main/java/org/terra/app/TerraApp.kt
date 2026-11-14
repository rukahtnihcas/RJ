package org.terra.app

import android.app.Application
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.terra.app.data.PhotoStore
import org.terra.app.data.Repository
import org.terra.app.data.TerraDb
import org.terra.app.domain.Catalog
import org.terra.app.domain.Geo
import org.terra.app.domain.HabitatEngine
import org.terra.app.sensing.Cues
import org.terra.app.sensing.LocationTracker
import kotlin.math.sin

class TerraApp : Application() {
    val catalog: Catalog by lazy { Catalog.parse(assets.open("catalog.json").bufferedReader().use { it.readText() }) }
    val photos by lazy { PhotoStore(this) }
    val repo by lazy { Repository(this, TerraDb.open(this).dao(), catalog, photos) }
    val location by lazy { LocationTracker(this) }
    val cues by lazy { Cues(this) }
    val scope = MainScope()
    /** Short messages for whichever screen is showing. */
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private var lastHabitatId: Long? = null

    override fun onCreate() {
        super.onCreate()
        // Eyes-up: notice when the walker crosses into a habitat, whether or not the screen is on.
        scope.launch {
            combine(location.fix.filterNotNull(), repo.dao.habitats()) { f, h -> f to h }.collect { (f, habitats) ->
                val inside = HabitatEngine.containing(habitats, f.lat, f.lon)
                if (inside?.id == lastHabitatId) return@collect
                lastHabitatId = inside?.id
                if (inside == null) return@collect
                val first = inside.firstVisitedAt == null
                cues.pulse(if (first) 3 else 1)
                if (first) {
                    val heading = f.bearing
                    cues.chime(if (heading == null) 0f else sin(Math.toRadians(Geo.bearing(f.lat, f.lon, inside.lat, inside.lon) - heading)).toFloat())
                    repo.markVisited(inside.id)
                    notices.tryEmit("New habitat discovered: ${inside.name}")
                }
            }
        }
    }
}
