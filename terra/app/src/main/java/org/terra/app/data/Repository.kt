package org.terra.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.terra.app.domain.Catalog
import org.terra.app.domain.Exporter
import org.terra.app.domain.HabitatEngine
import org.terra.app.domain.Overpass
import org.terra.app.domain.World
import org.terra.app.model.Detection
import org.terra.app.model.Habitat
import org.terra.app.model.HabitatSource
import org.terra.app.model.HabitatType
import org.terra.app.model.Landmark
import org.terra.app.model.LandmarkEntry
import org.terra.app.model.Observation
import org.terra.app.model.StewardAction
import org.terra.app.model.StillnessSession
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class Repository(private val context: Context, val dao: TerraDao, val catalog: Catalog, val photos: PhotoStore) {

    val world: Flow<World> = combine(
        combine(dao.observations(), dao.habitats(), dao.actions()) { o, h, a -> Triple(o, h, a) },
        combine(dao.landmarks(), dao.entries()) { l, e -> l to e },
        combine(dao.sessions(), dao.detections()) { s, d -> s to d }
    ) { (o, h, a), (l, e), (s, d) -> World(o, h, a, l, e, s, d) }

    /** Saves an observation, attaching the habitat it falls in and its sensitivity flag. */
    suspend fun addObservation(o: Observation): Observation {
        val habitat = HabitatEngine.containing(dao.habitatsNow(), o.lat, o.lon)
        val full = o.copy(habitatId = habitat?.id, sensitive = catalog[o.speciesId]?.sensitive == true)
        return full.copy(id = dao.insert(full))
    }

    /** Fetches parks, woods, wetlands and water around a point from OpenStreetMap. Returns how many were new. */
    suspend fun chartHabitats(lat: Double, lon: Double, radiusM: Int = 1500): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(Overpass.ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 40_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "TERRA-field-guide/0.1 (Android)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(Overpass.query(lat, lon, radiusM), "UTF-8")).toByteArray()) }
            if (conn.responseCode != 200) error("Map service answered ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            dao.insertHabitats(Overpass.parse(text, System.currentTimeMillis())).count { it != -1L }
        }
    }

    suspend fun addPlayerHabitat(name: String, type: HabitatType, lat: Double, lon: Double, radiusM: Double): Long {
        val now = System.currentTimeMillis()
        return dao.insert(Habitat(name = name, type = type, lat = lat, lon = lon, radiusM = radiusM,
            source = HabitatSource.PLAYER, firstVisitedAt = now, createdAt = now))
    }

    suspend fun markVisited(id: Long) = dao.markVisited(id, System.currentTimeMillis())
    suspend fun addAction(a: StewardAction) = dao.insert(a)
    suspend fun updateAction(a: StewardAction) = dao.update(a)
    suspend fun addLandmark(l: Landmark, first: LandmarkEntry): Long {
        val id = dao.insert(l)
        dao.insert(first.copy(landmarkId = id))
        return id
    }
    suspend fun addEntry(e: LandmarkEntry) = dao.insert(e)
    suspend fun startSession(s: StillnessSession) = dao.insert(s)
    suspend fun updateSession(s: StillnessSession) = dao.update(s)
    suspend fun addDetection(d: Detection) = dao.insert(d)
    suspend fun resolveDetection(id: Long, state: Int) = dao.resolve(id, state)
    suspend fun deleteObservation(id: Long) = dao.deleteObservation(id)

    suspend fun writeText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
    }

    /** A complete, portable archive: every record as JSON plus every photo. */
    suspend fun writeBackup(uri: Uri, world: World) = withContext(Dispatchers.IO) {
        ZipOutputStream(context.contentResolver.openOutputStream(uri)!!).use { zip ->
            zip.putNextEntry(ZipEntry("terra.json"))
            zip.write(Exporter.backup(world, System.currentTimeMillis()).toByteArray())
            zip.closeEntry()
            photos.dir.listFiles()?.forEach { f ->
                zip.putNextEntry(ZipEntry("photos/${f.name}"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** Restores an archive, merging by record id. Photo paths are re-pointed at this device's storage. */
    suspend fun restoreBackup(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var json: String? = null
            ZipInputStream(context.contentResolver.openInputStream(uri)!!).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = File(e.name).name
                    if (e.name == "terra.json") json = zip.readBytes().decodeToString()
                    else if (e.name.startsWith("photos/") && name.endsWith(".jpg")) File(photos.dir, name).outputStream().use { zip.copyTo(it) }
                }
            }
            val b = Exporter.parseBackup(json ?: error("Not a TERRA archive"))
            fun local(p: String?) = p?.let { File(photos.dir, File(it).name).path }
            dao.restoreHabitats(b.habitats)
            dao.restoreObservations(b.observations.map { it.copy(photoPath = local(it.photoPath)) })
            dao.restoreActions(b.actions.map { it.copy(beforePhoto = local(it.beforePhoto)!!, afterPhoto = local(it.afterPhoto)) })
            dao.restoreLandmarks(b.landmarks)
            dao.restoreEntries(b.entries.map { it.copy(photoPath = local(it.photoPath)) })
            dao.restoreSessions(b.sessions)
            dao.restoreDetections(b.detections)
            b.observations.size
        }
    }
}
