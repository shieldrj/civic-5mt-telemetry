package com.shieldrj.civic5mt.data

import com.shieldrj.civic5mt.core.Csv
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The trip log as a spreadsheet.
 *
 * Each column is declared once, as a name paired with its value, and [Csv.document] takes the
 * header from those names. A column added to one and not the other cannot happen - which is
 * the mistake worth designing out here, because its symptom is a file that opens fine and is
 * wrong from that column rightwards.
 */
object TripCsv {

    /**
     * Both a readable timestamp and the raw epoch.
     *
     * The readable one is what a person sorts and filters by; the epoch is what survives
     * being opened in a spreadsheet that decides to reinterpret dates, and it is the only
     * form that says exactly when without depending on the reader's timezone.
     */
    private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)

    private fun iso(millis: Long?): String? = millis?.let { isoFormat().format(Date(it)) }

    private fun num(value: Double, digits: Int): String = "%.${digits}f".format(Locale.ROOT, value)

    fun trips(trips: List<TripEntity>): String {
        val rows = trips.map { t ->
            listOf(
                "trip_id" to t.id.toString(),
                "started" to iso(t.startedAt),
                "started_epoch_ms" to t.startedAt.toString(),
                "ended" to iso(t.endedAt),
                // Null rather than 0 for a drive that never closed - the adapter was pulled
                // out or the phone died, and the row is deliberately kept. A zero here would
                // read as a drive that ended at the epoch.
                "ended_epoch_ms" to t.endedAt?.toString(),
                "distance_miles" to num(t.distanceMiles, 3),
                "fuel_gallons" to num(t.fuelGallons, 4),
                "avg_mpg" to num(t.avgMpg, 2),
                "duration_sec" to num(t.durationSec, 1),
                "idle_sec" to num(t.idleSec, 1),
                "coasting_sec" to num(t.coastingSec, 1),
                "max_speed_mph" to num(t.maxSpeedMph, 1),
                "max_rpm" to num(t.maxRpm, 0),
                "eco_score" to t.ecoScore.toString(),
                // Spelled out rather than 1/0. A spreadsheet will happily sum a column of
                // ones, and "3" is not an answer to whether a drive was real.
                "simulated" to if (t.simulated) "yes" else "no",
            )
        }
        return Csv.document(TRIP_COLUMNS, rows)
    }

    fun samples(samples: List<TripSampleEntity>): String {
        val rows = samples.map { s ->
            listOf(
                "trip_id" to s.tripId.toString(),
                "at" to iso(s.at),
                "at_epoch_ms" to s.at.toString(),
                "speed_mph" to num(s.speedMph, 1),
                "rpm" to num(s.rpm, 0),
                "mpg" to num(s.mpg, 2),
                // Fahrenheit, like every temperature the app shows. The database still
                // stores Celsius, which is what the PID reports; the conversion is here.
                "coolant_f" to num(s.coolantC * 9 / 5 + 32, 1),
                "throttle_pct" to num(s.throttlePct, 1),
                // Empty on a car with no wideband PID, all the way out to the file.
                "lambda" to s.lambda?.let { num(it, 3) },
            )
        }
        return Csv.document(SAMPLE_COLUMNS, rows)
    }

    val TRIP_COLUMNS = listOf(
        "trip_id", "started", "started_epoch_ms", "ended", "ended_epoch_ms",
        "distance_miles", "fuel_gallons", "avg_mpg", "duration_sec", "idle_sec",
        "coasting_sec", "max_speed_mph", "max_rpm", "eco_score", "simulated",
    )

    val SAMPLE_COLUMNS = listOf(
        "trip_id", "at", "at_epoch_ms", "speed_mph", "rpm", "mpg", "coolant_f",
        "throttle_pct", "lambda",
    )
}
