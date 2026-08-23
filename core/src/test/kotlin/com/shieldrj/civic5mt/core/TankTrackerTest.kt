package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One tank of fuel.
 *
 * Two faults are being fixed here and they are separate. Range swung wildly because it used a
 * 30-second average MPG. A full tank read 93% because percent was being converted to gallons
 * with a number from a specification instead of a number measured on this car.
 */
class TankTrackerTest {

    /**
     * Drives a tank down at a steady rate.
     *
     * @param startPercent sender reading at the start
     * @param endPercent sender reading at the end
     * @param gallonsPerPercent what the car's real sender is worth, which the tracker has to
     *   discover for itself
     * @param mpg economy over the run
     */
    private fun driveDown(
        tracker: TankTracker,
        clock: MutableClock,
        startPercent: Double,
        endPercent: Double,
        gallonsPerPercent: Double,
        mpg: Double,
        stepSec: Double = 1.0,
    ) {
        val totalGallons = (startPercent - endPercent) * gallonsPerPercent
        // Long enough that the 60-second smoothing settles rather than lagging the whole way.
        val steps = 40_000
        val gallonsStep = totalGallons / steps
        val milesStep = gallonsStep * mpg

        repeat(steps) { i ->
            val level = startPercent + (endPercent - startPercent) * (i + 1.0) / steps
            clock.advanceMillis((stepSec * 1000).toLong())
            tracker.record(level, milesStep, gallonsStep, stepSec)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Miles per gallon for the tank")
    inner class PerTank {

        @Test
        fun `A tank reports its own economy, not the last thirty seconds`() {
            val clock = MutableClock(1_700_000_000_000)
            val t = TankTracker(InMemoryTankStore(), clock)

            driveDown(t, clock, startPercent = 93.0, endPercent = 40.0, gallonsPerPercent = 0.132, mpg = 34.0)

            val mpg = t.get().tankMpg
            assertNotNull(mpg)
            assertTrue(abs(mpg - 34.0) < 0.5, "got $mpg")
        }

        @Test
        fun `Half a mile after a fill there is no economy figure yet`() {
            // Distance over fuel on those numbers is arithmetic on rounding error, and it
            // produces a huge and confident-looking number.
            val clock = MutableClock(1_700_000_000_000)
            val t = TankTracker(InMemoryTankStore(), clock)

            t.record(93.0, 0.0, 0.0, 1.0)
            clock.advanceMillis(60_000)
            t.record(93.0, 0.5, 0.01, 60.0)

            assertNull(t.get().tankMpg)
        }

        @Test
        fun `Filling up starts the count again`() {
            val clock = MutableClock(1_700_000_000_000)
            val t = TankTracker(InMemoryTankStore(), clock)

            driveDown(t, clock, 93.0, 25.0, 0.132, mpg = 30.0)
            assertTrue(t.get().milesSinceFill > 200)

            // The pump. The sender climbs back up over a couple of minutes.
            repeat(120) {
                clock.advanceMillis(1_000)
                t.record(93.0, 0.0, 0.0, 1.0)
            }

            assertTrue(t.get().milesSinceFill < 1.0, "miles restarted")
            assertTrue(t.get().gallonsUsedSinceFill < 0.05, "fuel restarted")
            assertNull(t.get().tankMpg, "and there is nothing to report yet")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("How full the tank really is")
    inner class Calibration {

        @Test
        fun `A sender that reads 93 percent when full still gives the right gallons`() {
            // The reported symptom. The nominal conversion says 93% of 13.2 gallons is 12.3.
            // If this car's real full tank is 13.2 at a sender reading of 93, then every
            // gallons figure taken from the specification is 7% low, and so is the range.
            val realGallonsPerPercent = 13.2 / 93.0 // 0.142
            val clock = MutableClock(1_700_000_000_000)
            val t = TankTracker(InMemoryTankStore(), clock)

            // First tank: the tracker is still using the nominal figure and cannot know better.
            driveDown(t, clock, 93.0, 30.0, realGallonsPerPercent, mpg = 32.0)
            assertFalse(t.get().calibrated, "nothing measured yet")

            // Fill up. The measurement is taken at this moment, from the tank just finished.
            repeat(120) {
                clock.advanceMillis(1_000)
                t.record(93.0, 0.0, 0.0, 1.0)
            }

            assertTrue(t.get().calibrated, "measured from a real tank")
            assertTrue(
                abs(t.get().gallonsPerPercent - realGallonsPerPercent) < 0.01,
                "learned ${t.get().gallonsPerPercent}, real $realGallonsPerPercent",
            )
            assertTrue(
                abs(t.get().gallonsRemaining - 13.2) < 0.5,
                "a full tank should read about 13.2 gallons, got ${t.get().gallonsRemaining}",
            )
        }

        @Test
        fun `An impossible measurement is refused rather than adopted`() {
            // The app was closed for half the tank, so the fuel burned does not match the
            // percent dropped. Adopting that would put the error into every later reading.
            val clock = MutableClock(1_700_000_000_000)
            val t = TankTracker(InMemoryTankStore(), clock)
            val before = t.get().gallonsPerPercent

            // 60 points of sender for barely any fuel: only a fraction of the tank was seen.
            driveDown(t, clock, 90.0, 30.0, gallonsPerPercent = 0.01, mpg = 32.0)
            repeat(120) {
                clock.advanceMillis(1_000)
                t.record(90.0, 0.0, 0.0, 1.0)
            }

            assertEquals(before, t.get().gallonsPerPercent, "kept the previous figure")
            assertFalse(t.get().calibrated)
        }

        @Test
        fun `A short tank is not used to measure anything`() {
            val clock = MutableClock(1_700_000_000_000)
            val t = TankTracker(InMemoryTankStore(), clock)

            driveDown(t, clock, 93.0, 88.0, 0.142, mpg = 32.0) // only 5 points
            repeat(120) {
                clock.advanceMillis(1_000)
                t.record(93.0, 0.0, 0.0, 1.0)
            }

            assertFalse(t.get().calibrated, "too short a span to measure from")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Filling up with the app shut")
    inner class FilledWhileClosed {

        @Test
        fun `A fill between drives starts a new tank and measures the last one`() {
            // The normal case, not an edge one. Nobody fills a tank with the engine running,
            // so the app is never watching when the level goes up.
            val realGallonsPerPercent = 13.2 / 93.0
            val clock = MutableClock(1_700_000_000_000)
            val store = InMemoryTankStore()

            TankTracker(store, clock).also {
                driveDown(it, clock, 93.0, 28.0, realGallonsPerPercent, mpg = 31.0)
                it.flush()
            }

            // Engine off. Pump. Two days later, the app starts again and connects.
            clock.advanceMillis(2L * 24 * 60 * 60 * 1000)
            val next = TankTracker(store, clock)
            next.record(93.0, 0.0, 0.0, 1.0)

            assertTrue(next.get().milesSinceFill < 1.0, "the new tank starts at zero miles")
            assertTrue(next.get().calibrated, "and the finished tank was measured")
            assertTrue(
                abs(next.get().gallonsRemaining - 13.2) < 0.5,
                "full reads " + next.get().gallonsRemaining + " gallons",
            )
        }

        @Test
        fun `Stopping and restarting mid-tank carries on where it left off`() {
            // Only a rise means a fill. Parking, going into a shop and driving on must not
            // restart the count.
            val clock = MutableClock(1_700_000_000_000)
            val store = InMemoryTankStore()

            TankTracker(store, clock).also {
                driveDown(it, clock, 90.0, 55.0, 0.142, mpg = 33.0)
                it.flush()
            }
            val milesBefore = store.load()!!.milesSinceFill

            clock.advanceMillis(20 * 60_000)
            val next = TankTracker(store, clock)
            next.record(55.0, 0.0, 0.0, 1.0)

            assertEquals(milesBefore, next.get().milesSinceFill, "same tank, same count")
        }

        @Test
        fun `Saying so by hand starts a new tank`() {
            // For a splash of fuel too small to be seen as a fill, or one the app missed.
            val clock = MutableClock(1_700_000_000_000)
            val t = TankTracker(InMemoryTankStore(), clock)
            driveDown(t, clock, 90.0, 60.0, 0.142, mpg = 33.0)
            assertTrue(t.get().milesSinceFill > 100)

            t.markFilled(75.0)

            assertEquals(0.0, t.get().milesSinceFill)
            assertTrue(abs(t.get().smoothedLevelPercent - 75.0) < 0.001)
        }

        @Test
        fun `Mid-tank fuel sloshing does not trigger a false fill reset`() {
            val clock = MutableClock(1_700_000_000_000)
            val t = TankTracker(InMemoryTankStore(), clock)
            // Drive down to 40% (over 100 miles)
            driveDown(t, clock, 90.0, 40.0, 0.142, mpg = 33.0)
            val milesBefore = t.get().milesSinceFill
            assertTrue(milesBefore > 100)

            // Slosh: sender momentarily bounces from 40% to 46% for 8 seconds
            repeat(8) {
                clock.advanceMillis(1_000)
                t.record(46.0, 0.01, 0.0003, 1.0)
            }

            // Must NOT have wiped the tank!
            assertTrue(t.get().milesSinceFill >= milesBefore, "Miles should continue accumulating, not reset to 0")
            assertNotNull(t.get().tankMpg, "Tank MPG should remain active and valid")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Range")
    inner class Range {

        @Test
        fun `Range does not swing when the driving does`() {
            // The whole complaint. The old figure was tank level times a 30-second average,
            // so a hill halved it and the far side of the hill doubled it.
            val clock = MutableClock(1_700_000_000_000)
            val t = TankTracker(InMemoryTankStore(), clock)
            driveDown(t, clock, 93.0, 60.0, 0.142, mpg = 33.0)

            val steady = rangeMiles(t.get(), lifetimeMpg = 35.0)
            val tankMpgBefore = t.get().tankMpg!!

            // Two minutes up a hill in third: about 4 gal/hr at 20 mph, which is 5 mpg.
            repeat(120) {
                clock.advanceMillis(1_000)
                t.record(59.0, 20.0 / 3600, 4.0 / 3600, 1.0)
            }
            val afterHill = rangeMiles(t.get(), lifetimeMpg = 35.0)

            // The old figure multiplied the tank by a 30-second average, so after two minutes
            // at 5 mpg it would have shown a tank's worth of fuel as about 40 miles.
            val whatTheOldOneWouldSay = (t.get().gallonsRemaining * 5.0).toInt()
            assertTrue(
                whatTheOldOneWouldSay < steady / 3,
                "the old figure really was that unstable: $whatTheOldOneWouldSay vs $steady",
            )

            assertTrue(
                abs(afterHill - steady) < 15,
                "range moved from $steady to $afterHill over two minutes",
            )
            assertTrue(
                tankMpgBefore - t.get().tankMpg!! < 1.0,
                "and the tank average barely moved",
            )
        }

        @Test
        fun `Fuel sloshing in the tank does not move the range`() {
            val clock = MutableClock(1_700_000_000_000)
            val t = TankTracker(InMemoryTankStore(), clock)
            driveDown(t, clock, 93.0, 60.0, 0.142, mpg = 33.0)

            val before = rangeMiles(t.get(), lifetimeMpg = 33.0)

            // A roundabout: the float swings six points either way, twice a second.
            repeat(20) { i ->
                clock.advanceMillis(500)
                t.record(if (i % 2 == 0) 66.0 else 54.0, 0.01, 0.0003, 0.5)
            }

            val after = rangeMiles(t.get(), lifetimeMpg = 33.0)
            assertTrue(abs(after - before) <= 5, "range moved from $before to $after on a roundabout")
        }

        @Test
        fun `Before a tank has a figure of its own, range uses the lifetime average`() {
            val fresh = TankState(
                fillTimestamp = 1,
                levelPercentAtFill = 93.0,
                smoothedLevelPercent = 93.0,
                gallonsPerPercent = 0.142,
            )
            assertNull(fresh.tankMpg)

            // 93 x 0.142 = 13.2 gallons, at the lifetime 35.3 mpg.
            assertEquals(466, rangeMiles(fresh, lifetimeMpg = 35.3))
        }

        @Test
        fun `With no history at all it falls back to the EPA figure, not to zero`() {
            val fresh = TankState(
                fillTimestamp = 1,
                smoothedLevelPercent = 50.0,
                gallonsPerPercent = 0.132,
            )
            val miles = rangeMiles(fresh, lifetimeMpg = 0.0, lifetimeMiles = 0.0)
            assertTrue(miles > 100, "got $miles")
        }

        @Test
        fun `A lifetime figure with four miles behind it is not an average yet`() {
            // It would move by a quarter with every mile driven, which is the swinging this
            // whole change exists to stop. The EPA rating is steadier and closer to the truth.
            val fresh = TankState(
                fillTimestamp = 1,
                smoothedLevelPercent = 50.0,
                gallonsPerPercent = 0.132,
            )
            val onNonsense = rangeMiles(fresh, lifetimeMpg = 64.0, lifetimeMiles = 4.0)
            val onEpa = rangeMiles(fresh, lifetimeMpg = 0.0, lifetimeMiles = 0.0)
            assertEquals(onEpa, onNonsense, "ignored a four-mile lifetime average")

            val onReal = rangeMiles(fresh, lifetimeMpg = 35.3, lifetimeMiles = 900.0)
            assertTrue(onReal != onEpa, "and used a real one")
        }

        @Test
        fun `An empty tank is no range, not a negative one`() {
            val empty = TankState(fillTimestamp = 1, smoothedLevelPercent = 0.0)
            assertEquals(0, rangeMiles(empty, lifetimeMpg = 35.0))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Cars that do not report a tank level")
    inner class NoSender {

        @Test
        fun `Nothing is invented when the car reports no level`() {
            val clock = MutableClock(1_700_000_000_000)
            val t = TankTracker(InMemoryTankStore(), clock)

            repeat(100) {
                clock.advanceMillis(1_000)
                t.record(null, 0.5, 0.015, 1.0)
            }

            assertEquals(0.0, t.get().milesSinceFill)
            assertNull(t.get().tankMpg)
        }
    }
}
