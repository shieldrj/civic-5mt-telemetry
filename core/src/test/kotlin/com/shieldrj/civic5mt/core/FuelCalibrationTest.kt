package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checking the gauge, the MAF and the speed sensor against the pump.
 *
 * Two things are defended here. That a receipt can arrive whenever the driver has time for it
 * and still land on the right fill. And that nothing in the calibration depends on the app
 * having watched every drive - which it does not, and which is what made the first version of
 * this file refuse or skew real fills.
 */
class FuelCalibrationTest {

    private val t0 = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L

    /** The gauge on this imaginary car: one percent is an eighth of a gallon. */
    private val trueGpp = 0.125

    private fun engine(clock: MutableClock = MutableClock(t0)) = FuelCalibrationEngine(clock = clock)

    /**
     * A tank closing at a fill: the gauge had fallen to [before], the app watched [watchedDrop]
     * points of that fall, and the MAF (reading [mafReads] of the truth) counted the fuel.
     */
    private fun closed(
        at: Long,
        before: Double? = 20.0,
        miles: Double = 330.0,
        watchedDrop: Double = 60.0,
        mafReads: Double = 0.95,
    ): ClosedTank {
        val watchedTrueGallons = watchedDrop * trueGpp
        return ClosedTank(
            closedAtMillis = at,
            levelBefore = before,
            rawMiles = miles,
            rawGallons = watchedTrueGallons * mafReads,
            observedDropPercent = watchedDrop,
            observedRawGallons = watchedTrueGallons * mafReads,
        )
    }

    /** A fill noticed by the car, settled at [after], with the pump's true figure for the rise. */
    private fun FuelCalibrationEngine.fill(
        at: Long,
        before: Double = 20.0,
        after: Double = 93.0,
        watchedDrop: Double = 60.0,
        mafReads: Double = 0.95,
        miles: Double = 330.0,
    ): Double {
        onFillDetected(closed(at, before, miles, watchedDrop, mafReads))
        updateLevelAfter(after)
        return (after - before) * trueGpp
    }

    @Nested
    @DisplayName("Before any receipt")
    inner class Uncalibrated {

        @Test
        fun `everything starts at one, so the app behaves as it did before receipts`() {
            val state = FuelCalibrationState()
            assertEquals(1.0, state.fuelCorrectionFactor)
            assertEquals(1.0, state.distanceCorrectionFactor)
            assertNull(state.pumpGallonsPerPercent)
            assertNull(state.economyMpg)
            assertFalse(state.calibrated)
            assertNull(state.pendingReceipt(t0))
        }

        @Test
        fun `a fill the car noticed waits for its receipt`() {
            val e = engine()
            e.fill(at = t0)

            val pending = assertNotNull(e.get().pendingReceipt(t0 + hour))
            assertEquals(20.0, pending.levelBefore)
            assertEquals(93.0, pending.levelAfter)
        }
    }

    @Nested
    @DisplayName("A receipt entered later")
    inner class LateReceipt {

        @Test
        fun `lands on the fill the car noticed, hours afterwards`() {
            // The reported problem, the right way round: 35 miles home, then the Costco app.
            val e = engine()
            val pump = e.fill(at = t0)

            val outcome = e.attachReceipt(pumpGallons = pump, filledToShutoff = true)

            val saved = assertIs<ReceiptOutcome.Saved>(outcome)
            assertEquals(t0, saved.record.detectedAtMillis)
            assertEquals(trueGpp, saved.state.pumpGallonsPerPercent!!, 1e-9)
            assertNull(saved.state.pendingReceipt(t0 + 2 * hour), "it stops asking")
        }

        @Test
        fun `measures the gauge from a single receipt, with no previous fill needed`() {
            // The old design needed two fills to the click in a row before it measured
            // anything. The gauge's rise is its own start and end point.
            val e = engine()
            val pump = e.fill(at = t0)
            e.attachReceipt(pump, filledToShutoff = true)

            assertTrue(e.get().calibrated)
            assertEquals(1, e.get().gaugeReceiptCount)
        }

        @Test
        fun `a part fill still measures the gauge`() {
            // Gallons over rise holds for any fill with both ends seen. Only the odometer
            // check needs the tank to finish where it started.
            val e = engine()
            val pump = e.fill(at = t0, before = 30.0, after = 70.0)
            e.attachReceipt(pump, filledToShutoff = false)

            assertEquals(trueGpp, e.get().pumpGallonsPerPercent!!, 1e-9)
        }

        @Test
        fun `stops asking after three days`() {
            val e = engine()
            e.fill(at = t0)

            assertNotNull(e.get().pendingReceipt(t0 + 71 * hour))
            assertNull(e.get().pendingReceipt(t0 + 73 * hour))
        }

        @Test
        fun `stops asking when the driver says there is no receipt`() {
            val e = engine()
            e.fill(at = t0)
            e.skipReceipt()

            assertNull(e.get().pendingReceipt(t0 + hour))
            assertFalse(e.get().calibrated)
        }

        @Test
        fun `a typo can be corrected without adding a fill`() {
            val e = engine()
            val pump = e.fill(at = t0)
            e.attachReceipt(pump * 10, filledToShutoff = true) // refused: 91 gallons
            e.attachReceipt(pump + 1.0, filledToShutoff = true)
            e.attachReceipt(pump, filledToShutoff = true)

            assertEquals(1, e.get().fills.size)
            assertEquals(pump, e.get().fills.single().pumpGallons!!, 1e-9)
        }
    }

    @Nested
    @DisplayName("Receipts that cannot measure the gauge")
    inner class NotMeasured {

        @Test
        fun `a number the tank could not take is refused, not stored`() {
            val e = engine()
            e.fill(at = t0)

            listOf(0.0, -3.0, 114.2).forEach { typo ->
                val outcome = e.attachReceipt(typo, filledToShutoff = true)
                assertEquals(
                    ReceiptRefusal.IMPLAUSIBLE_PUMP_GALLONS,
                    assertIs<ReceiptOutcome.Refused>(outcome).reason,
                )
            }
            assertNotNull(e.get().pendingReceipt(t0 + hour), "still waiting for the real one")
        }

        @Test
        fun `a splash-and-dash is kept but measures nothing`() {
            val e = engine()
            val pump = e.fill(at = t0, before = 60.0, after = 80.0)
            assertIs<ReceiptOutcome.Saved>(e.attachReceipt(pump, filledToShutoff = false))

            assertNull(e.get().pumpGallonsPerPercent)
        }

        @Test
        fun `a fill whose before-level the car never saw is kept but measures nothing`() {
            val e = engine()
            e.onFillDetected(closed(at = t0, before = null))
            e.updateLevelAfter(93.0)
            e.attachReceipt(11.0, filledToShutoff = true)

            assertNull(e.get().pumpGallonsPerPercent)
        }

        @Test
        fun `a receipt logged before the car has seen the rise waits for it`() {
            val e = engine()
            e.onFillDetected(closed(at = t0))
            e.attachReceipt(9.125, filledToShutoff = true)
            assertNull(e.get().pumpGallonsPerPercent, "no after-level yet")

            e.updateLevelAfter(93.0)
            assertEquals(trueGpp, e.get().pumpGallonsPerPercent!!, 1e-9)
        }
    }

    @Nested
    @DisplayName("The same fill, noticed twice")
    inner class Duplicates {

        @Test
        fun `is one record, and keeps the first before-level`() {
            // The level crosses the detection threshold more than once while a pump runs, and
            // a receipt logged at the pump is re-detected when the car wakes.
            val e = engine()
            e.onFillDetected(closed(at = t0, before = 12.0))
            val again = e.onFillDetected(closed(at = t0 + 20 * 60_000, before = 40.0, miles = 0.3))

            assertFalse(again)
            assertEquals(1, e.get().fills.size)
            assertEquals(12.0, e.get().fills.single().levelBefore)
        }

        @Test
        fun `a real second fill after driving is its own record`() {
            val e = engine()
            e.fill(at = t0)
            e.fill(at = t0 + 2 * hour, miles = 90.0)

            assertEquals(2, e.get().fills.size)
        }

        @Test
        fun `the after-level only rises while the float settles`() {
            val e = engine()
            e.onFillDetected(closed(at = t0))
            e.updateLevelAfter(88.0)
            e.updateLevelAfter(93.0)
            e.updateLevelAfter(91.0)

            assertEquals(93.0, e.get().fills.single().levelAfter)
        }
    }

    @Nested
    @DisplayName("Several receipts")
    inner class Pooling {

        @Test
        fun `one stale before-level is shrugged off`() {
            // A drive to the station the app missed leaves the before-level too high, which
            // makes the rise look small and the gallons-per-percent large. A median of three
            // ignores it where an average would carry a third of it.
            val e = engine()
            listOf(20.0, 20.0, 20.0).forEachIndexed { i, before ->
                val at = t0 + i * 100 * hour
                val pump = e.fill(at = at, before = before)
                e.attachReceipt(pump, filledToShutoff = true)
            }
            // The fourth: the gauge really started at 20, but the app last saw it at 35.
            e.onFillDetected(closed(at = t0 + 400 * hour, before = 35.0))
            e.updateLevelAfter(93.0)
            e.attachReceipt((93.0 - 20.0) * trueGpp, filledToShutoff = true)

            assertEquals(trueGpp, e.get().pumpGallonsPerPercent!!, 1e-9)
            assertTrue(e.get().spreadPercent!! > 20.0, "and the disagreement is reported")
        }
    }

    @Nested
    @DisplayName("Checking the MAF against the measured gauge")
    inner class FuelCorrection {

        @Test
        fun `a MAF reading five percent low is found`() {
            val e = engine()
            val pump = e.fill(at = t0, mafReads = 0.95)
            e.attachReceipt(pump, filledToShutoff = true)

            assertEquals(1 / 0.95, e.get().fuelCorrectionFactor, 1e-6)
            assertTrue(e.get().fuelCalibrated)
        }

        @Test
        fun `a tank with missed drives teaches the same answer`() {
            // The fault the old design had. A drive the app missed takes away gauge fall and
            // MAF gallons together, so the ratio survives it.
            val full = engine()
            full.attachReceipt(full.fill(at = t0, watchedDrop = 73.0), true)
            val patchy = engine()
            patchy.attachReceipt(patchy.fill(at = t0, watchedDrop = 25.0, miles = 110.0), true)

            assertEquals(full.get().fuelCorrectionFactor, patchy.get().fuelCorrectionFactor, 1e-9)
        }

        @Test
        fun `a tank watched too little is not used`() {
            val e = engine()
            e.attachReceipt(e.fill(at = t0, watchedDrop = 10.0), true)

            assertEquals(1.0, e.get().fuelCorrectionFactor)
            assertFalse(e.get().fuelCalibrated)
        }

        @Test
        fun `does not learn from its own previous answer`() {
            // Spans are stored raw, so a correction already in force cannot look like fresh
            // evidence for more of the same. Three identical tanks give the same answer as one.
            val e = engine()
            repeat(3) { i ->
                e.attachReceipt(e.fill(at = t0 + i * 100 * hour, mafReads = 0.95), true)
            }
            assertEquals(1 / 0.95, e.get().fuelCorrectionFactor, 1e-6)
        }

        @Test
        fun `an absurd tank is left out rather than applied`() {
            val e = engine()
            e.attachReceipt(e.fill(at = t0, mafReads = 0.95), true)
            e.attachReceipt(e.fill(at = t0 + 100 * hour, mafReads = 0.5), true)

            assertEquals(1 / 0.95, e.get().fuelCorrectionFactor, 1e-6)
        }
    }

    @Nested
    @DisplayName("The odometer")
    inner class Odometer {

        /** Two fills to the click, [miles] apart on the odometer, with [appMiles] watched. */
        private fun twoFills(miles: Double, appMiles: Double, secondFull: Boolean = true): FuelCalibrationEngine {
            val e = engine()
            e.attachReceipt(e.fill(at = t0), true, odometerAtFill = 100_000.0)
            val pump = e.fill(at = t0 + 100 * hour, miles = appMiles, after = if (secondFull) 93.0 else 60.0)
            e.attachReceipt(pump, secondFull, odometerAtFill = 100_000.0 + miles)
            return e
        }

        @Test
        fun `gives miles per gallon that missed drives cannot touch`() {
            val e = twoFills(miles = 330.0, appMiles = 200.0)
            val pump = e.get().fills.last().pumpGallons!!

            assertEquals(330.0 / pump, e.get().verifiedMpg!!, 1e-9)
            assertEquals(e.get().verifiedMpg, e.get().economyMpg)
        }

        @Test
        fun `learns the speed sensor only from a tank the app saw whole`() {
            assertEquals(330.0 / 323.5, twoFills(330.0, 323.5).get().distanceCorrectionFactor, 1e-9)
            // 200 app miles against 330 on the odometer is missed driving, not a slow sensor.
            assertEquals(1.0, twoFills(330.0, 200.0).get().distanceCorrectionFactor)
        }

        @Test
        fun `needs both fills to reach the click`() {
            val e = twoFills(miles = 330.0, appMiles = 323.5, secondFull = false)
            assertNull(e.get().verifiedMpg)
        }

        @Test
        fun `ignores a box ticked on a fill the gauge says was short`() {
            val e = engine()
            e.attachReceipt(e.fill(at = t0), true, odometerAtFill = 100_000.0)
            // Ticked as to the click, but the gauge only reached 70 against a 93 full mark.
            e.attachReceipt(e.fill(at = t0 + 100 * hour, after = 70.0), true, odometerAtFill = 100_330.0)

            assertNull(e.get().verifiedMpg)
        }

        @Test
        fun `is broken by a fill in between that had no receipt`() {
            val e = engine()
            e.attachReceipt(e.fill(at = t0), true, odometerAtFill = 100_000.0)
            e.fill(at = t0 + 50 * hour)
            e.skipReceipt()
            e.attachReceipt(e.fill(at = t0 + 100 * hour), true, odometerAtFill = 100_660.0)

            assertNull(e.get().verifiedMpg)
        }

        @Test
        fun `without an odometer, the watched economy stands in`() {
            val e = engine()
            e.attachReceipt(e.fill(at = t0, mafReads = 0.95, miles = 330.0), true)
            val state = e.get()

            val span = state.fills.single()
            val expected = span.spanRawMiles / (span.spanRawGallons * state.fuelCorrectionFactor)
            assertNull(state.verifiedMpg)
            assertEquals(expected, state.economyMpg!!, 1e-9)
        }
    }

    @Nested
    @DisplayName("Starting again")
    inner class Reset {

        @Test
        fun `clears every fill`() {
            val e = engine()
            e.attachReceipt(e.fill(at = t0), true)
            e.reset()

            assertTrue(e.get().fills.isEmpty())
            assertEquals(1.0, e.fuelFactor())
        }

        @Test
        fun `keeps only the most recent fills`() {
            val e = engine()
            repeat(FuelCalibrationRules.KEEP + 5) { i -> e.fill(at = t0 + i * 100 * hour) }

            assertEquals(FuelCalibrationRules.KEEP, e.get().fills.size)
        }
    }
}
