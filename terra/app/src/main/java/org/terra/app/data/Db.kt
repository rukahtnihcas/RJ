package org.terra.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.terra.app.model.Detection
import org.terra.app.model.Habitat
import org.terra.app.model.Landmark
import org.terra.app.model.LandmarkEntry
import org.terra.app.model.Observation
import org.terra.app.model.StewardAction
import org.terra.app.model.StillnessSession

@Dao
interface TerraDao {
    @Query("SELECT * FROM observations ORDER BY timestamp DESC") fun observations(): Flow<List<Observation>>
    @Query("SELECT * FROM habitats") fun habitats(): Flow<List<Habitat>>
    @Query("SELECT * FROM actions ORDER BY beforeAt DESC") fun actions(): Flow<List<StewardAction>>
    @Query("SELECT * FROM landmarks ORDER BY adoptedAt DESC") fun landmarks(): Flow<List<Landmark>>
    @Query("SELECT * FROM landmark_entries ORDER BY timestamp DESC") fun entries(): Flow<List<LandmarkEntry>>
    @Query("SELECT * FROM stillness_sessions ORDER BY startedAt DESC") fun sessions(): Flow<List<StillnessSession>>
    @Query("SELECT * FROM detections ORDER BY timestamp DESC") fun detections(): Flow<List<Detection>>

    @Query("SELECT * FROM habitats") suspend fun habitatsNow(): List<Habitat>

    @Insert suspend fun insert(o: Observation): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertHabitats(h: List<Habitat>): List<Long>
    @Insert suspend fun insert(h: Habitat): Long
    @Insert suspend fun insert(a: StewardAction): Long
    @Insert suspend fun insert(l: Landmark): Long
    @Insert suspend fun insert(e: LandmarkEntry): Long
    @Insert suspend fun insert(s: StillnessSession): Long
    @Insert suspend fun insert(d: Detection): Long
    @Update suspend fun update(a: StewardAction)
    @Update suspend fun update(s: StillnessSession)

    @Query("UPDATE habitats SET firstVisitedAt = :at WHERE id = :id AND firstVisitedAt IS NULL")
    suspend fun markVisited(id: Long, at: Long)
    @Query("UPDATE detections SET resolved = :state WHERE id = :id") suspend fun resolve(id: Long, state: Int)
    @Query("DELETE FROM observations WHERE id = :id") suspend fun deleteObservation(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreObservations(x: List<Observation>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreHabitats(x: List<Habitat>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreActions(x: List<StewardAction>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreLandmarks(x: List<Landmark>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreEntries(x: List<LandmarkEntry>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreSessions(x: List<StillnessSession>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreDetections(x: List<Detection>)
}

@Database(
    entities = [Observation::class, Habitat::class, StewardAction::class, Landmark::class,
        LandmarkEntry::class, StillnessSession::class, Detection::class],
    version = 1, exportSchema = true
)
abstract class TerraDb : RoomDatabase() {
    abstract fun dao(): TerraDao

    companion object {
        fun open(context: Context): TerraDb =
            Room.databaseBuilder(context, TerraDb::class.java, "terra.db").build()
    }
}
