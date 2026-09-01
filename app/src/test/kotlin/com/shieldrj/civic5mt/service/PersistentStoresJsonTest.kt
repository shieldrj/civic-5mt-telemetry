package com.shieldrj.civic5mt.service

import com.shieldrj.civic5mt.core.ClutchConditionGrade
import com.shieldrj.civic5mt.core.ClutchProfile
import com.shieldrj.civic5mt.core.ClutchSlipIncident
import com.shieldrj.civic5mt.core.ClutchWearBreakdown
import com.shieldrj.civic5mt.core.DegradationBreakdown
import com.shieldrj.civic5mt.core.FillSample
import com.shieldrj.civic5mt.core.FuelCalibrationState
import com.shieldrj.civic5mt.core.LifetimeStats
import com.shieldrj.civic5mt.core.OilConditionGrade
import com.shieldrj.civic5mt.core.OilLifeProfile
import com.shieldrj.civic5mt.core.TankState
import org.json.JSONObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The encoders and parsers that stand between the records and the disk.
 *
 * Worth pinning because of what they guard rather than because of how clever they are. The
 * lifetime record accumulated over real driving, was extracted out of a Snappy-compressed
 * leveldb table with `adb run-as`, and cannot be regenerated; the tank's gallons-per-percent
 * is measured once per tank. A key that an encoder writes and its parser does not read loses
 * one of those silently - the app starts, the record is there, and one field inside it has
 * quietly gone back to its default. Nothing throws and no screen says so.
 *
 * These run against a real org.json rather than the android.jar stub - see the dependency
 * comment in app/build.gradle.kts for why that is not a detail.
 */
class PersistentStoresJsonTest {

    /**
     * That the stubs are not what is being tested.
     *
     * If `org.json` ever falls off the test classpath, every assertion below starts comparing
     * defaults against defaults and passing while testing nothing. This is the canary: the
     * stubbed JSONObject returns null from toString() and 0.0 from optDouble.
     */
    @Test
    @DisplayName("org.json on the test classpath is the real one, not Android's stub")
    fun realJsonImplementation() {
        val round = JSONObject(JSONObject().put("x", 1.5).toString())
        assertEquals(1.5, round.optDouble("x", 0.0), "org.json is stubbed; these tests are void")
    }

    @Nested
    @DisplayName("The lifetime record")
    inner class Lifetime {

        @Test
        @DisplayName("survives a round-trip through JSON")
        fun roundTrip() {
            val original = LifetimeStats(
                totalMiles = 14_237.6,
                totalFuelGallons = 486.21,
                firstTrackedTimestamp = 1_700_000_000_000,
            )

            val restored = parseLifetime(JSONObject(lifetimeToJson(original).toString()))

            assertEquals(original.totalMiles, restored.totalMiles)
            assertEquals(original.totalFuelGallons, restored.totalFuelGallons)
            assertEquals(original.firstTrackedTimestamp, restored.firstTrackedTimestamp)
        }

        /**
         * MPG is written to the file and deliberately not read back out of it. Miles and
         * gallons are the measurements; the ratio is a view of them, and a stored ratio that
         * disagreed with the two numbers it came from would be a third opinion.
         */
        @Test
        @DisplayName("recomputes MPG from miles and gallons, ignoring the stored ratio")
        fun mpgIsDerivedNotRead() {
            val json = lifetimeToJson(
                LifetimeStats(totalMiles = 1000.0, totalFuelGallons = 40.0)
            ).put("lifetimeMpg", 3.7)

            val restored = parseLifetime(JSONObject(json.toString()))

            assertEquals(25.0, restored.lifetimeMpg, "MPG should come from the measurements")
        }

        /**
         * A file that has lost a key must read as a zero rather than as a crash. The store
         * catches throwables and returns null, which means "keep what is in memory" - but a
         * parse that threw on a missing key would discard a record that was mostly intact.
         */
        @Test
        @DisplayName("reads an empty document as zeroes rather than throwing")
        fun missingKeysAreZero() {
            val restored = parseLifetime(JSONObject("{}"))

            assertEquals(0.0, restored.totalMiles)
            assertEquals(0.0, restored.totalFuelGallons)
            assertEquals(0L, restored.firstTrackedTimestamp)
            assertEquals(0.0, restored.lifetimeMpg)
        }

        /**
         * Negative miles are not a small error, they are a corrupt file - and the record is
         * cumulative, so a negative would subtract from every figure after it forever.
         */
        @Test
        @DisplayName("clamps a negative total to zero rather than carrying it forward")
        fun negativesAreClamped() {
            val restored = parseLifetime(
                JSONObject().put("totalMiles", -500.0).put("totalFuelGallons", -20.0)
            )

            assertEquals(0.0, restored.totalMiles)
            assertEquals(0.0, restored.totalFuelGallons)
        }
    }

    @Nested
    @DisplayName("The oil profile")
    inner class Oil {

        private fun profile() = OilLifeProfile(
            lastResetTimestamp = 1_699_000_000_000,
            lastResetOdometer = 120_450.0,
            currentOdometer = 124_780.5,
            oilLifePercent = 42.5,
            accumulatedRevolutions = 9.87e7,
            coldStartsCount = 63,
            timeBelowOperatingTempSec = 14_320.5,
            shortTripsCount = 21,
            highThermalStressSec = 880.25,
            estimatedMilesRemaining = 2_670,
            estimatedDaysRemaining = 94,
            oilConditionGrade = OilConditionGrade.FAIR,
            degradationBreakdown = DegradationBreakdown(
                revWearFactor = 0.41,
                coldStartPenalty = 0.12,
                shortTripPenalty = 0.07,
                thermalShearPenalty = 0.03,
            ),
        )

        @Test
        @DisplayName("survives a round-trip through JSON, every field")
        fun roundTrip() {
            val original = profile()

            val restored = parseOilProfile(JSONObject(oilProfileToJson(original).toString()))

            assertEquals(original, restored)
        }

        /**
         * The nested breakdown is the part most likely to be lost quietly: it is an object
         * inside an object, so a parser that missed it would still produce a valid-looking
         * profile with the wear factors silently at zero.
         */
        @Test
        @DisplayName("carries the nested degradation breakdown")
        fun nestedBreakdown() {
            val restored = parseOilProfile(JSONObject(oilProfileToJson(profile()).toString()))

            assertEquals(0.41, restored.degradationBreakdown.revWearFactor)
            assertEquals(0.12, restored.degradationBreakdown.coldStartPenalty)
            assertEquals(0.07, restored.degradationBreakdown.shortTripPenalty)
            assertEquals(0.03, restored.degradationBreakdown.thermalShearPenalty)
        }

        /**
         * The grade is stored as its human label rather than its enum name, because that is
         * the shape the WebView wrote and the migration reads the same parser. "Service Due"
         * with a space has to survive, and an unrecognised label must land somewhere sane
         * rather than throw.
         */
        @Test
        @DisplayName("stores the grade by its label, including the one with a space")
        fun gradeByLabel() {
            OilConditionGrade.entries.forEach { grade ->
                val json = oilProfileToJson(profile().copy(oilConditionGrade = grade))
                assertEquals(grade.label, json.getString("oilConditionGrade"))
                assertEquals(grade, parseOilProfile(JSONObject(json.toString())).oilConditionGrade)
            }
        }

        @Test
        @DisplayName("falls back to Good on a grade it does not recognise")
        fun unknownGrade() {
            val json = oilProfileToJson(profile()).put("oilConditionGrade", "Catastrophic")

            assertEquals(
                OilConditionGrade.GOOD,
                parseOilProfile(JSONObject(json.toString())).oilConditionGrade,
            )
        }

        /**
         * Days remaining is null when there is not enough history to project one, and null is
         * a different statement from zero - the screen draws a dash for one and "0 days" for
         * the other. JSON has no null in a numeric field unless it is written deliberately.
         */
        @Test
        @DisplayName("keeps an unknown days-remaining as null rather than zero")
        fun nullDaysRemaining() {
            val json = oilProfileToJson(profile().copy(estimatedDaysRemaining = null))

            assertTrue(json.isNull("estimatedDaysRemaining"))
            assertNull(parseOilProfile(JSONObject(json.toString())).estimatedDaysRemaining)
        }

        @Test
        @DisplayName("reads an empty document as a fresh 100% profile")
        fun missingKeysAreFresh() {
            val restored = parseOilProfile(JSONObject("{}"))

            assertEquals(100.0, restored.oilLifePercent)
            assertEquals(0, restored.coldStartsCount)
            assertNull(restored.estimatedDaysRemaining)
        }
    }

    @Nested
    @DisplayName("The tank")
    inner class Tank {

        private fun tank() = TankState(
            fillTimestamp = 1_700_100_000_000,
            levelPercentAtFill = 92.5,
            milesSinceFill = 187.4,
            gallonsUsedSinceFill = 6.28,
            gallonsPerPercent = 0.1408,
            calibrated = true,
            smoothedLevelPercent = 47.9,
            lowestLevelPercent = 41.2,
            fullMarkPercent = 93.0,
        )

        @Test
        @DisplayName("survives a round-trip through JSON, every field")
        fun roundTrip() {
            val original = tank()

            val restored = parseTank(JSONObject(tankToJson(original).toString()))

            assertEquals(original, restored)
        }

        /**
         * The measured figure is the expensive one - it takes a whole tank run down far enough
         * to establish, so losing it costs a fortnight of driving to get back. Pinned
         * separately from the round-trip because it is the field whose loss would be least
         * visible: range keeps working off the nominal number and merely gets worse.
         */
        @Test
        @DisplayName("keeps a measured gallons-per-percent, and that it was measured")
        fun calibrationSurvives() {
            val restored = parseTank(JSONObject(tankToJson(tank()).toString()))

            assertEquals(0.1408, restored.gallonsPerPercent)
            assertTrue(restored.calibrated)
        }

        /**
         * A record written before the full mark existed has no such key, and zero is the right
         * reading of that: "not seen yet", which falls the percentage back to the sender's own
         * scale until the next fill teaches it where full is. A nominal default here would
         * instead assert a full mark nobody measured.
         */
        @Test
        @DisplayName("reads a record from before the full mark existed as not-yet-seen")
        fun legacyRecordHasNoFullMark() {
            val legacy = JSONObject()
                .put("fillTimestamp", 1_699_000_000_000)
                .put("levelPercentAtFill", 88.0)
                .put("milesSinceFill", 40.0)
                .put("gallonsUsedSinceFill", 1.4)
                .put("gallonsPerPercent", 0.132)
                .put("calibrated", false)
                .put("smoothedLevelPercent", 80.0)
                .put("lowestLevelPercent", 80.0)

            val restored = parseTank(legacy)

            assertEquals(0.0, restored.fullMarkPercent, "zero means the full mark is unknown")
            assertEquals(88.0, restored.levelPercentAtFill, "the rest of the record still reads")
        }

        /**
         * An empty document must not read as an empty tank that has never been calibrated but
         * claims a lowest level of zero - that would look like a car about to run dry.
         */
        @Test
        @DisplayName("reads an empty document as an uncalibrated tank at a full lowest mark")
        fun missingKeysAreSane() {
            val restored = parseTank(JSONObject("{}"))

            assertFalse(restored.calibrated)
            assertEquals(100.0, restored.lowestLevelPercent)
            assertEquals(0.132, restored.gallonsPerPercent)
        }
    }

    @Nested
    @DisplayName("The clutch profile")
    inner class Clutch {

        private fun profile() = ClutchProfile(
            lastResetTimestamp = 1_700_000_000_000,
            lastResetOdometer = 112_000.0,
            currentOdometer = 118_430.5,
            clutchHealthPercent = 74.3,
            baselineKnown = true,
            accumulatedFrictionEnergyJoules = 96_400_000.0,
            totalEngagementsCount = 41_200,
            abnormalSlipCount = 3,
            maxObservedTempC = 168.2,
            estimatedTorqueCapacityNm = 249.1,
            observedCapacityFloorNm = 262.0,
            ratioCalibration = 1.028,
            estimatedMilesRemaining = 61_500,
            estimatedDaysRemaining = 740,
            estimatedShiftsRemaining = 169_512,
            conditionGrade = ClutchConditionGrade.GOOD,
            degradationBreakdown = ClutchWearBreakdown(
                shiftWearPercent = 8.2,
                launchWearPercent = 6.1,
                slipWearPercent = 4.9,
                thermalGlazePenaltyPercent = 0.0,
            ),
            recentIncidents = listOf(
                ClutchSlipIncident(
                    timestamp = 1_700_000_500_000,
                    gear = 5,
                    peakSlipRpm = 512.4,
                    peakTorqueNm = 151.0,
                    speedKmh = 80.0,
                    durationSec = 1.6,
                ),
            ),
        )

        @Test
        @DisplayName("survives a round-trip through JSON")
        fun roundTrip() {
            val original = profile()
            val restored = parseClutchProfile(JSONObject(clutchProfileToJson(original).toString()))

            assertEquals(original, restored)
        }

        /**
         * The learned tyre correction is the one field here that is expensive to reacquire -
         * it takes miles of steady cruise to settle. Losing it silently sends every expected
         * RPM back out by the rolling-radius error the calibration existed to remove.
         */
        @Test
        @DisplayName("keeps the learned rolling-radius correction")
        fun keepsCalibration() {
            val restored = parseClutchProfile(JSONObject(clutchProfileToJson(profile()).toString()))

            assertEquals(1.028, restored.ratioCalibration)
        }

        /**
         * An empty document is a clutch nobody has watched, and it has to read that way. The
         * screen keys off both of these: a stored `true` here would present wear the app
         * happened to see as though it were the disc's whole history, and a restored mileage
         * would put a projection on the gauge that no drive ever supported.
         */
        @Test
        @DisplayName("reads an empty document as a clutch with no known history")
        fun missingKeysAreSane() {
            val restored = parseClutchProfile(JSONObject("{}"))

            assertFalse(restored.baselineKnown)
            assertNull(restored.estimatedMilesRemaining)
            assertNull(restored.estimatedDaysRemaining)
            assertEquals(0.0, restored.accumulatedFrictionEnergyJoules)
            assertEquals(1.0, restored.ratioCalibration)
        }
    }

    /**
     * The fill history, which is the record that cost the most to produce.
     *
     * Every other stored figure can be measured again in a tank or two of driving. The
     * corrections here are pooled over six fills - three months of them - and two fields are
     * load-bearing in a way that is easy to miss: the factors that were in effect when each
     * sample was taken. Lose those and every stored sample starts being read as though it had
     * been measured with no correction applied, which is exactly the compounding the core
     * model goes out of its way to avoid.
     */
    @Nested
    @DisplayName("The fill history")
    inner class FuelCalibrationRecord {

        private val sample = FillSample(
            timestampMillis = 1_770_000_000_000L,
            pumpGallons = 11.42,
            measuredGallons = 10.88,
            measuredMiles = 402.7,
            fuelFactorInEffect = 1.031,
            distanceFactorInEffect = 0.978,
            odometerMiles = 396.0,
        )

        @Test
        @DisplayName("survives a round trip with every field intact")
        fun roundTrips() {
            val state = FuelCalibrationState(
                samples = listOf(sample, sample.copy(pumpGallons = 9.9, odometerMiles = null)),
                lastFillWasFull = true,
                lastOdometerMiles = 142_380.0,
            )

            val restored = parseFuelCalibration(JSONObject(fuelCalibrationToJson(state).toString()))

            assertEquals(2, restored.samples.size)
            assertEquals(sample.pumpGallons, restored.samples[0].pumpGallons)
            assertEquals(sample.measuredGallons, restored.samples[0].measuredGallons)
            assertEquals(sample.measuredMiles, restored.samples[0].measuredMiles)
            assertEquals(sample.fuelFactorInEffect, restored.samples[0].fuelFactorInEffect)
            assertEquals(sample.distanceFactorInEffect, restored.samples[0].distanceFactorInEffect)
            assertEquals(sample.odometerMiles, restored.samples[0].odometerMiles)
            assertEquals(sample.timestampMillis, restored.samples[0].timestampMillis)
            assertTrue(restored.lastFillWasFull)
            assertEquals(142_380.0, restored.lastOdometerMiles)
            assertEquals(state.fuelCorrectionFactor, restored.fuelCorrectionFactor, 1e-9)
        }

        /**
         * A skipped odometer must come back as skipped, not as zero.
         *
         * Zero odometer miles is a claim that the car did not move, and the pooled distance
         * correction is a sum over samples - a zero dragged into that numerator would make the
         * app conclude the speed sensor reads high by however much the missing tank was worth.
         */
        @Test
        @DisplayName("keeps a skipped odometer reading absent rather than turning it into zero")
        fun absentOdometerStaysAbsent() {
            val state = FuelCalibrationState(samples = listOf(sample.copy(odometerMiles = null)))

            val restored = parseFuelCalibration(JSONObject(fuelCalibrationToJson(state).toString()))

            assertNull(restored.samples[0].odometerMiles)
            assertEquals(1.0, restored.distanceCorrectionFactor)
        }

        @Test
        @DisplayName("reads an empty document as a car whose sensors have never been checked")
        fun missingKeysAreSane() {
            val restored = parseFuelCalibration(JSONObject("{}"))

            assertTrue(restored.samples.isEmpty())
            assertFalse(restored.calibrated)
            assertFalse(restored.lastFillWasFull)
            assertNull(restored.lastOdometerMiles)
            assertEquals(1.0, restored.fuelCorrectionFactor)
            assertEquals(1.0, restored.distanceCorrectionFactor)
        }

        /**
         * A stored factor of zero would make rawGallons infinite and take the whole correction
         * with it. Damaged records read as uncorrected rather than as catastrophic.
         */
        @Test
        @DisplayName("reads a damaged correction factor as no correction at all")
        fun zeroFactorIsRepaired() {
            val doc = JSONObject(
                """{"samples":[{"pumpGallons":11.0,"measuredGallons":10.0,"measuredMiles":350.0,
                   "fuelFactorInEffect":0.0,"distanceFactorInEffect":0.0}]}""",
            )

            val restored = parseFuelCalibration(doc)

            assertEquals(1.0, restored.samples[0].fuelFactorInEffect)
            assertEquals(1.0, restored.samples[0].distanceFactorInEffect)
            assertEquals(1.1, restored.fuelCorrectionFactor, 1e-9)
        }
    }
}
