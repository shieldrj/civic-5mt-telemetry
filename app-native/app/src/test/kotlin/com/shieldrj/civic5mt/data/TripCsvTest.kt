package com.shieldrj.civic5mt.data

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The trip log as a spreadsheet.
 *
 * The one piece of real logic in the Android module. It cannot live in :core because it maps
 * Room entities, and it is worth pinning anyway: an export with the wrong value under a
 * heading is a file that opens perfectly, plots cleanly, and is wrong.
 */
class TripCsvTest {

    private fun trip(
        id: Long = 1,
        endedAt: Long? = 1_700_003_600_000,
        simulated: Boolean = false,
    ) = TripEntity(
        id = id,
        startedAt = 1_700_000_000_000,
        endedAt = endedAt,
        distanceMiles = 12.345,
        fuelGallons = 0.4321,
        avgMpg = 28.57,
        durationSec = 3600.0,
        idleSec = 120.5,
        coastingSec = 240.25,
        maxSpeedMph = 68.4,
        maxRpm = 4200.0,
        ecoScore = 87,
        simulated = simulated,
    )

    private fun header(doc: String) = doc.lineSequence().first().split(",")
    private fun dataRow(doc: String, index: Int = 0) =
        doc.lineSequence().drop(1 + index).first().split(",")

    private fun field(doc: String, column: String, rowIndex: Int = 0): String {
        val i = header(doc).indexOf(column)
        assertTrue(i >= 0, "no column named $column in ${header(doc)}")
        return dataRow(doc, rowIndex)[i]
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Drives")
    inner class Drives {

        @Test
        fun `Every value lands under its own heading`() {
            // Transposing two doubles here is invisible afterwards - both columns still hold
            // plausible numbers. This is the test that catches it.
            val doc = TripCsv.trips(listOf(trip()))

            assertEquals("1", field(doc, "trip_id"))
            assertEquals("12.345", field(doc, "distance_miles"))
            assertEquals("0.4321", field(doc, "fuel_gallons"))
            assertEquals("28.57", field(doc, "avg_mpg"))
            assertEquals("3600.0", field(doc, "duration_sec"))
            assertEquals("120.5", field(doc, "idle_sec"))
            assertEquals("240.3", field(doc, "coasting_sec"))
            assertEquals("68.4", field(doc, "max_speed_mph"))
            assertEquals("4200", field(doc, "max_rpm"))
            assertEquals("87", field(doc, "eco_score"))
        }

        @Test
        fun `The header matches the declared column list`() {
            assertEquals(TripCsv.TRIP_COLUMNS, header(TripCsv.trips(listOf(trip()))))
        }

        @Test
        fun `A simulated drive says so in words`() {
            // Not 1 and 0. A spreadsheet will happily sum a column of ones, and "3" is not an
            // answer to the question of whether a drive really happened.
            assertEquals("no", field(TripCsv.trips(listOf(trip())), "simulated"))
            assertEquals(
                "yes",
                field(TripCsv.trips(listOf(trip(simulated = true))), "simulated"),
            )
        }

        @Test
        fun `A drive that never closed has an empty end, not the epoch`() {
            // The adapter was pulled out or the phone died. The row is kept deliberately, and
            // a 0 in that column would read as a drive that ended in 1970.
            val doc = TripCsv.trips(listOf(trip(endedAt = null)))
            assertEquals("", field(doc, "ended"))
            assertEquals("", field(doc, "ended_epoch_ms"))
        }

        @Test
        fun `The epoch column is exact, whatever a spreadsheet does to the readable one`() {
            assertEquals("1700000000000", field(TripCsv.trips(listOf(trip())), "started_epoch_ms"))
        }

        @Test
        fun `No drives still produces a header`() {
            val doc = TripCsv.trips(emptyList())
            assertEquals(TripCsv.TRIP_COLUMNS, header(doc))
            assertEquals(1, doc.lineSequence().count { it.isNotBlank() })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("The second-by-second trace")
    inner class Trace {

        private fun sample(lambda: Double?) = TripSampleEntity(
            id = 1,
            tripId = 7,
            at = 1_700_000_001_000,
            speedMph = 41.2,
            rpm = 2450.0,
            mpg = 33.19,
            coolantC = 88.5,
            throttlePct = 21.4,
            lambda = lambda,
        )

        @Test
        fun `A reading the car does not report stays empty, not zero`() {
            // This Civic has no narrowband front sensor. A 0 in the lambda column is a
            // measurement of zero, which is a different claim from never having one.
            val doc = TripCsv.samples(listOf(sample(lambda = null)))
            assertEquals("", field(doc, "lambda"))
        }

        @Test
        fun `A reading the car does report is written out`() {
            assertEquals("1.002", field(TripCsv.samples(listOf(sample(1.0018))), "lambda"))
        }

        @Test
        fun `Coolant is exported in Fahrenheit, under a column that says so`() {
            /*
             * The database stores Celsius, because that is what PID 05 reports. Everything a
             * person reads is Fahrenheit, and this file is something a person reads.
             *
             * The column name carries the unit, so the name and the conversion have to change
             * together or the file states something untrue. That is what this pins: 88.5 C is
             * 191.3 F, and the heading is coolant_f. Files exported before this change say
             * coolant_c and hold Celsius.
             */
            val doc = TripCsv.samples(listOf(sample(null)))
            assertEquals("191.3", field(doc, "coolant_f"))
            assertTrue(!TripCsv.SAMPLE_COLUMNS.contains("coolant_c"), "the old heading is gone")
        }

        @Test
        fun `Samples carry the trip they belong to`() {
            assertEquals("7", field(TripCsv.samples(listOf(sample(null))), "trip_id"))
        }

        @Test
        fun `The header matches the declared column list`() {
            assertEquals(
                TripCsv.SAMPLE_COLUMNS,
                header(TripCsv.samples(listOf(sample(null)))),
            )
        }
    }
}
