package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checking the sensors against the pump.
 *
 * The thing being defended here is the one that made the range figure wrong in the first
 * place: an error that arrives twice. A MAF reading low understates fuel, which raises MPG and
 * lengthens range, and the same understated fuel is what calibrates gallons-per-percent, which
 * inflates the reserve and lengthens range again. These tests pin the correction that removes
 * it, and - more importantly - pin the correction against learning from itself.
 */
class FuelCalibrationTest {

    private fun engine(clock: MutableClock = MutableClock(1_700_000_000_000L)) =
        FuelCalibrationEngine(clock = clock)

    /** Establishes the first fill-to-shutoff, which nothing can be measured against yet. */
    private fun FuelCalibrationEngine.baseline(odometerMiles: Double? = null) = recordFill(
        pumpGallons = 11.0,
        filledToShutoff = true,
        measuredGallons = 0.0,
        measuredMiles = 0.0,
        odometerMiles = odometerMiles,
    )

    @Nested
    @DisplayName("Before anything has been measured")
    inner class Uncalibrated {

        @Test
        fun `corrections start at one, so an app with no receipts behaves as it always did`() {
            val state = FuelCalibrationState()
            assertEquals(1.0, state.fuelCorrectionFactor)
            assertEquals(1.0, state.distanceCorrectionFactor)
            assertFalse(state.calibrated)
            assertNull(state.verifiedMpg)
        }

        @Test
        fun `the first fill cannot be measured, because there is no start point`() {
            val e = engine()
            val outcome = e.recordFill(
                pumpGallons = 11.0,
                filledToShutoff = true,
                measuredGallons = 10.0,
                measuredMiles = 350.0,
            )
            val rejected = assertIs<FillOutcome.Rejected>(outcome)
            assertEquals(FillRejection.NO_FULL_FILL_BASELINE, rejected.reason)
            assertEquals(1.0, e.fuelFactor())
        }

        @Test
        fun `but it becomes the start point the next one is measured against`() {
            val e = engine()
            e.baseline()
            val outcome = e.recordFill(
                pumpGallons = 10.5,
                filledToShutoff = true,
                measuredGallons = 10.0,
                measuredMiles = 350.0,
            )
            assertIs<FillOutcome.Accepted>(outcome)
        }
    }

    @Nested
    @DisplayName("Learning the fuel correction")
    inner class FuelCorrection {

        @Test
        fun `a MAF reading five percent low is corrected by five percent`() {
            val e = engine()
            e.baseline()
            e.recordFill(
                pumpGallons = 10.5,
                filledToShutoff = true,
                measuredGallons = 10.0,
                measuredMiles = 350.0,
            )
            assertEquals(1.05, e.fuelFactor(), 1e-9)
        }

        /**
         * The regression this whole file exists for.
         *
         * Once a correction is applied, the next tank's measured gallons arrive already
         * corrected. An estimator that compared the receipt against that corrected figure would
         * read its own previous answer as fresh evidence and apply it again, compounding every
         * fill until the guard rails caught it. Dividing [FillSample.fuelFactorInEffect] back
         * out is what makes each sample describe the sensor instead.
         *
         * Same car, same five percent error, three tanks: the answer has to stay at 1.05.
         */
        @Test
        fun `the correction does not compound by learning from its own output`() {
            val e = engine()
            e.baseline()

            val trueGallons = 10.5
            val sensorReadsLow = 0.95
            repeat(4) {
                // What the app would report for this tank: the sensor's raw answer with
                // whatever correction is currently live already applied to it.
                val reported = trueGallons * sensorReadsLow * e.fuelFactor()
                e.recordFill(
                    pumpGallons = trueGallons,
                    filledToShutoff = true,
                    measuredGallons = reported,
                    measuredMiles = 350.0,
                )
            }

            assertEquals(1.0 / sensorReadsLow, e.fuelFactor(), 1e-6)
        }

        @Test
        fun `fills are pooled by volume, so a big tank counts for more than a splash`() {
            val e = engine()
            e.baseline()
            e.recordFill(
                pumpGallons = 12.0,
                filledToShutoff = true,
                measuredGallons = 12.0,
                measuredMiles = 400.0,
            )
            e.recordFill(
                pumpGallons = 4.5,
                filledToShutoff = true,
                measuredGallons = 4.0,
                measuredMiles = 150.0,
            )
            // Pooled: 16.5 pumped against 16.0 measured. Averaging the two ratios instead
            // (1.0 and 1.125) would answer 1.0625 and let the small fill speak too loudly.
            assertEquals(16.5 / 16.0, e.fuelFactor(), 1e-9)
        }

        @Test
        fun `only the last six fills count, because a MAF drifts as it ages`() {
            val e = engine()
            e.baseline()
            repeat(FuelCalibrationRules.WINDOW + 3) {
                e.recordFill(
                    pumpGallons = 10.0,
                    filledToShutoff = true,
                    measuredGallons = 10.0,
                    measuredMiles = 350.0,
                )
            }
            assertEquals(FuelCalibrationRules.WINDOW, e.get().samples.size)
        }
    }

    @Nested
    @DisplayName("Fills that teach nothing")
    inner class Rejections {

        @Test
        fun `a partial fill is not measured, because the tank did not end where it started`() {
            val e = engine()
            e.baseline()
            val outcome = e.recordFill(
                pumpGallons = 6.0,
                filledToShutoff = false,
                measuredGallons = 5.5,
                measuredMiles = 200.0,
            )
            val rejected = assertIs<FillOutcome.Rejected>(outcome)
            assertEquals(FillRejection.NOT_FILLED_TO_SHUTOFF, rejected.reason)
            assertEquals(1.0, e.fuelFactor())
        }

        @Test
        fun `and the fill after a partial one cannot be measured either`() {
            val e = engine()
            e.baseline()
            e.recordFill(
                pumpGallons = 6.0,
                filledToShutoff = false,
                measuredGallons = 5.5,
                measuredMiles = 200.0,
            )
            val outcome = e.recordFill(
                pumpGallons = 10.0,
                filledToShutoff = true,
                measuredGallons = 9.5,
                measuredMiles = 340.0,
            )
            assertEquals(
                FillRejection.NO_FULL_FILL_BASELINE,
                assertIs<FillOutcome.Rejected>(outcome).reason,
            )
        }

        @Test
        fun `a splash is too small to divide, so it is refused rather than believed`() {
            val e = engine()
            e.baseline()
            val outcome = e.recordFill(
                pumpGallons = 2.0,
                filledToShutoff = true,
                measuredGallons = 1.9,
                measuredMiles = 65.0,
            )
            assertEquals(
                FillRejection.SPAN_TOO_SHORT,
                assertIs<FillOutcome.Rejected>(outcome).reason,
            )
        }

        @Test
        fun `a disagreement too large to be a sensor is a missed fill, and is discarded`() {
            val e = engine()
            e.baseline()
            // Half the fuel the app thinks was burned. No MAF is wrong by that much; a tank
            // driven with the phone at home looks exactly like this.
            val outcome = e.recordFill(
                pumpGallons = 11.0,
                filledToShutoff = true,
                measuredGallons = 5.0,
                measuredMiles = 350.0,
            )
            assertEquals(
                FillRejection.IMPLAUSIBLE_RATIO,
                assertIs<FillOutcome.Rejected>(outcome).reason,
            )
            assertEquals(1.0, e.fuelFactor())
        }

        @Test
        fun `more gallons than the tank holds is a typo, not a measurement`() {
            val e = engine()
            e.baseline()
            val outcome = e.recordFill(
                pumpGallons = 110.0,
                filledToShutoff = true,
                measuredGallons = 10.0,
                measuredMiles = 350.0,
            )
            assertEquals(
                FillRejection.IMPLAUSIBLE_PUMP_GALLONS,
                assertIs<FillOutcome.Rejected>(outcome).reason,
            )
        }

        @Test
        fun `a tank the app never watched teaches nothing`() {
            val e = engine()
            e.baseline()
            val outcome = e.recordFill(
                pumpGallons = 10.0,
                filledToShutoff = true,
                measuredGallons = 0.0,
                measuredMiles = 0.0,
            )
            assertEquals(
                FillRejection.NO_MEASUREMENT,
                assertIs<FillOutcome.Rejected>(outcome).reason,
            )
        }

        @Test
        fun `a rejected fill still becomes the next one's start point`() {
            val e = engine()
            e.baseline()
            // Rejected for being outside the plausible, but it was still a fill to shutoff.
            e.recordFill(
                pumpGallons = 11.0,
                filledToShutoff = true,
                measuredGallons = 5.0,
                measuredMiles = 350.0,
            )
            val outcome = e.recordFill(
                pumpGallons = 10.5,
                filledToShutoff = true,
                measuredGallons = 10.0,
                measuredMiles = 350.0,
            )
            assertIs<FillOutcome.Accepted>(outcome)
        }
    }

    @Nested
    @DisplayName("Learning the distance correction")
    inner class DistanceCorrection {

        @Test
        fun `a speed PID reading two and a half percent fast is pulled back to the odometer`() {
            val e = engine()
            e.baseline(odometerMiles = 100_000.0)
            e.recordFill(
                pumpGallons = 10.5,
                filledToShutoff = true,
                measuredGallons = 10.5,
                measuredMiles = 400.0,
                odometerMiles = 100_390.0,
            )
            assertEquals(390.0 / 400.0, e.distanceFactor(), 1e-9)
            assertTrue(e.get().distanceCalibrated)
        }

        @Test
        fun `no odometer means no distance correction, and the fuel side still calibrates`() {
            val e = engine()
            e.baseline()
            e.recordFill(
                pumpGallons = 10.5,
                filledToShutoff = true,
                measuredGallons = 10.0,
                measuredMiles = 350.0,
            )
            assertEquals(1.0, e.distanceFactor())
            assertFalse(e.get().distanceCalibrated)
            assertEquals(1.05, e.fuelFactor(), 1e-9)
        }

        @Test
        fun `one odometer reading is not a distance, so it is not treated as one`() {
            val e = engine()
            // The baseline fill carried no odometer, so this reading has nothing to subtract.
            e.baseline()
            e.recordFill(
                pumpGallons = 10.5,
                filledToShutoff = true,
                measuredGallons = 10.5,
                measuredMiles = 400.0,
                odometerMiles = 100_390.0,
            )
            assertEquals(1.0, e.distanceFactor())
        }

        @Test
        fun `the distance correction does not compound either`() {
            val e = engine()
            e.baseline(odometerMiles = 100_000.0)

            val trueMiles = 390.0
            val speedReadsFast = 400.0 / 390.0
            var odo = 100_000.0
            repeat(4) {
                odo += trueMiles
                e.recordFill(
                    pumpGallons = 10.5,
                    filledToShutoff = true,
                    measuredGallons = 10.5,
                    measuredMiles = trueMiles * speedReadsFast * e.distanceFactor(),
                    odometerMiles = odo,
                )
            }
            assertEquals(1.0 / speedReadsFast, e.distanceFactor(), 1e-6)
        }
    }

    @Nested
    @DisplayName("What the fills say about economy")
    inner class VerifiedEconomy {

        @Test
        fun `verified MPG is the odometer over the pump, touching none of the app's sensors`() {
            val e = engine()
            e.baseline(odometerMiles = 100_000.0)
            e.recordFill(
                pumpGallons = 10.0,
                filledToShutoff = true,
                // Both of the app's own figures are deliberately wrong here. Neither should
                // reach the answer.
                measuredGallons = 9.0,
                measuredMiles = 420.0,
                odometerMiles = 100_400.0,
            )
            assertEquals(40.0, e.get().verifiedMpg!!, 1e-9)
        }

        @Test
        fun `one fill is not enough to build range on`() {
            val e = engine()
            e.baseline()
            e.recordFill(
                pumpGallons = 10.0,
                filledToShutoff = true,
                measuredGallons = 10.0,
                measuredMiles = 380.0,
            )
            assertFalse(e.get().verifiedMpgUsable)
            assertNull(e.get().spreadPercent)
        }

        @Test
        fun `two are, and the spread says how wide the answer is`() {
            val e = engine()
            e.baseline()
            e.recordFill(
                pumpGallons = 10.0,
                filledToShutoff = true,
                measuredGallons = 10.0,
                measuredMiles = 380.0,
            )
            e.recordFill(
                pumpGallons = 10.2,
                filledToShutoff = true,
                measuredGallons = 10.0,
                measuredMiles = 380.0,
            )
            val state = e.get()
            assertTrue(state.verifiedMpgUsable)
            val spread = assertNotNull(state.spreadPercent)
            // Fills implying 1.00 and 1.02 against a pooled 1.01: one percent either side.
            assertTrue(abs(spread - 1.0) < 0.05, "spread was $spread")
        }
    }

    @Nested
    @DisplayName("Starting again")
    inner class Reset {

        @Test
        fun `a reset forgets every fill, for a car whose sensors were replaced`() {
            val e = engine()
            e.baseline()
            e.recordFill(
                pumpGallons = 10.5,
                filledToShutoff = true,
                measuredGallons = 10.0,
                measuredMiles = 350.0,
            )
            assertTrue(e.get().calibrated)
            e.reset()
            assertFalse(e.get().calibrated)
            assertEquals(1.0, e.fuelFactor())
        }

        @Test
        fun `the history survives a restart`() {
            val store = InMemoryFuelCalibrationStore()
            val clock = MutableClock(1_700_000_000_000L)
            FuelCalibrationEngine(store, clock).apply {
                baseline()
                recordFill(
                    pumpGallons = 10.5,
                    filledToShutoff = true,
                    measuredGallons = 10.0,
                    measuredMiles = 350.0,
                )
            }
            assertEquals(1.05, FuelCalibrationEngine(store, clock).fuelFactor(), 1e-9)
        }
    }
}
