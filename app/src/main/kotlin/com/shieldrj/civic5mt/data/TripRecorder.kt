package com.shieldrj.civic5mt.data

import android.util.Log
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.core.TripAnalytics

/**
 * Writes a drive to the database as it happens.
 *
 * The row goes in when the drive starts and is updated as it runs, rather than assembled at
 * the end. A drive that ends because the battery died, the phone was unplugged or the adapter
 * was pulled out is still a drive that happened - and a recorder that only wrote on a clean
 * shutdown would lose precisely the drives worth looking at afterwards.
 *
 * Everything here is rate-limited on purpose. The models tick at 80ms because the fuel
 * integration and the shift light need it; a database does not, and writing at that rate would
 * be twelve times the rows and twelve times the wakeups for a trace that would look identical.
 */
class TripRecorder(private val dao: TripDao) {

    private var tripId: Long? = null
    private var simulated: Boolean = false

    private val pendingSamples = mutableListOf<TripSampleEntity>()
    private var lastSampleAt: Long = 0
    private var lastTripWriteAt: Long = 0

    val isRecording: Boolean get() = tripId != null

    suspend fun start(startedAt: Long, simulated: Boolean) {
        if (tripId != null) return
        this.simulated = simulated
        tripId = runCatching {
            dao.insertTrip(TripEntity(startedAt = startedAt, simulated = simulated))
        }.onFailure { Log.e(TAG, "Could not open a trip row", it) }.getOrNull()
        lastSampleAt = 0
        lastTripWriteAt = startedAt
    }

    /**
     * Called on every tick. Samples at 1 Hz and updates the trip totals every 15 seconds.
     *
     * Samples are batched rather than written one at a time: sixty individual inserts a minute
     * keeps the storage awake for no benefit when one insert of sixty rows says the same thing.
     */
    suspend fun record(now: Long, metrics: LiveMetrics, trip: TripAnalytics) {
        val id = tripId ?: return

        if (now - lastSampleAt >= SAMPLE_INTERVAL_MS) {
            lastSampleAt = now
            pendingSamples += TripSampleEntity(
                tripId = id,
                at = now,
                speedMph = metrics.speedMph,
                rpm = metrics.rpm,
                mpg = metrics.instantMpg,
                coolantC = metrics.coolantTempC,
                throttlePct = metrics.throttlePosPercent,
                lambda = metrics.equivalenceRatio,
            )
        }

        if (pendingSamples.size >= SAMPLE_BATCH) flushSamples()

        if (now - lastTripWriteAt >= TRIP_WRITE_INTERVAL_MS) {
            lastTripWriteAt = now
            writeTotals(id, trip, endedAt = null)
        }
    }

    /**
     * Closes the drive off.
     *
     * Writes the totals one last time and stamps the end, then drops the row entirely if the
     * drive went nowhere - connecting to check something is not a trip, and a list full of
     * zero-mile entries is a list nobody scrolls.
     */
    suspend fun finish(endedAt: Long, trip: TripAnalytics) {
        val id = tripId ?: return
        tripId = null

        flushSamples()
        writeTotals(id, trip, endedAt = endedAt)

        if (trip.distanceMiles < MIN_TRIP_MILES) {
            runCatching {
                dao.deleteSamples(id)
                dao.deleteTrip(id)
            }.onFailure { Log.w(TAG, "Could not drop an empty trip", it) }
        }
    }

    private suspend fun writeTotals(id: Long, trip: TripAnalytics, endedAt: Long?) {
        val existing = runCatching { dao.getTrip(id) }.getOrNull() ?: return
        runCatching {
            dao.updateTrip(
                existing.copy(
                    endedAt = endedAt,
                    distanceMiles = trip.distanceMiles,
                    fuelGallons = trip.totalFuelUsedGallons,
                    avgMpg = trip.avgMpg,
                    durationSec = trip.tripDurationSec,
                    idleSec = trip.idleTimeSec,
                    coastingSec = trip.coastingDfcoTimeSec,
                    maxSpeedMph = trip.maxSpeedMph,
                    maxRpm = trip.maxRpm,
                    ecoScore = trip.ecoScore,
                    simulated = simulated,
                )
            )
        }.onFailure { Log.w(TAG, "Could not update trip totals", it) }
    }

    private suspend fun flushSamples() {
        if (pendingSamples.isEmpty()) return
        val batch = pendingSamples.toList()
        pendingSamples.clear()
        runCatching { dao.insertSamples(batch) }
            .onFailure { Log.w(TAG, "Dropped ${batch.size} samples", it) }
    }

    private companion object {
        const val TAG = "TripRecorder"
        const val SAMPLE_INTERVAL_MS = 1000L
        const val SAMPLE_BATCH = 30
        const val TRIP_WRITE_INTERVAL_MS = 15_000L

        /** Below this a "drive" is a connection that went nowhere. */
        const val MIN_TRIP_MILES = 0.05
    }
}
