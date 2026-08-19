package com.shieldrj.civic5mt.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * One drive.
 *
 * A row is written when a drive starts and updated as it goes, rather than assembled at the
 * end - a drive that ends because the phone died or the adapter was yanked out is still a
 * drive that happened, and the version of this that only wrote on a clean shutdown would have
 * lost exactly the drives worth looking at afterwards.
 */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "distance_miles") val distanceMiles: Double = 0.0,
    @ColumnInfo(name = "fuel_gallons") val fuelGallons: Double = 0.0,
    @ColumnInfo(name = "avg_mpg") val avgMpg: Double = 0.0,
    @ColumnInfo(name = "duration_sec") val durationSec: Double = 0.0,
    @ColumnInfo(name = "idle_sec") val idleSec: Double = 0.0,
    @ColumnInfo(name = "coasting_sec") val coastingSec: Double = 0.0,
    @ColumnInfo(name = "max_speed_mph") val maxSpeedMph: Double = 0.0,
    @ColumnInfo(name = "max_rpm") val maxRpm: Double = 0.0,
    @ColumnInfo(name = "eco_score") val ecoScore: Int = 0,
    /**
     * Whether this drive came from the bench.
     *
     * Stored rather than filtered out at write time, because a simulated drive is worth
     * keeping while developing and worth never confusing with a real one. Every query that
     * totals anything says which it wants.
     */
    @ColumnInfo(name = "simulated") val simulated: Boolean = false,
)

/**
 * A sample from within a drive, for looking at afterwards.
 *
 * Written at 1 Hz, not at the 12.5 Hz the models run at. The tick rate exists because the
 * fuel integration and the shift light need it; a trace you scroll through a week later does
 * not, and the difference is twelve times the rows for a line that would look identical.
 */
@Entity(tableName = "trip_samples")
data class TripSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "trip_id", index = true) val tripId: Long,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "speed_mph") val speedMph: Double,
    @ColumnInfo(name = "rpm") val rpm: Double,
    @ColumnInfo(name = "mpg") val mpg: Double,
    @ColumnInfo(name = "coolant_c") val coolantC: Double,
    @ColumnInfo(name = "throttle_pct") val throttlePct: Double,
    /** Null on a car with no wideband PID. Absent readings stay absent in the log too. */
    @ColumnInfo(name = "lambda") val lambda: Double?,
)

@Dao
interface TripDao {

    @Insert
    suspend fun insertTrip(trip: TripEntity): Long

    @Update
    suspend fun updateTrip(trip: TripEntity)

    @Insert
    suspend fun insertSamples(samples: List<TripSampleEntity>)

    /** Newest first, which is the order anyone opens a trip list in. */
    @Query("SELECT * FROM trips ORDER BY started_at DESC LIMIT :limit")
    fun observeRecentTrips(limit: Int = 50): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun getTrip(id: Long): TripEntity?

    @Query("SELECT * FROM trip_samples WHERE trip_id = :tripId ORDER BY at ASC")
    suspend fun getSamples(tripId: Long): List<TripSampleEntity>

    /**
     * Real drives only.
     *
     * The lifetime figure in SharedPreferences is the permanent record and this does not
     * replace it; this is the same question asked of the trip log, and it has to exclude the
     * bench for the same reason.
     */
    @Query("SELECT COUNT(*) FROM trips WHERE simulated = 0")
    fun observeRealTripCount(): Flow<Int>

    /** Drops a drive and, by the foreign key, everything sampled during it. */
    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteTrip(id: Long)

    @Query("DELETE FROM trip_samples WHERE trip_id = :tripId")
    suspend fun deleteSamples(tripId: Long)

    /** Housekeeping: a drive with no distance is a connection that went nowhere. */
    @Query("DELETE FROM trips WHERE ended_at IS NOT NULL AND distance_miles < 0.05")
    suspend fun pruneEmptyTrips()
}

@Database(
    entities = [TripEntity::class, TripSampleEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TripDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        @Volatile
        private var instance: TripDatabase? = null

        fun get(context: Context): TripDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TripDatabase::class.java,
                "civic_trips.db",
            )
                // No fallbackToDestructiveMigration. This holds drives that cannot be
                // recreated, and a destructive fallback is a schema mistake quietly deleting
                // them on the next launch. A missing migration should fail loudly instead.
                .build()
                .also { instance = it }
        }
    }
}
