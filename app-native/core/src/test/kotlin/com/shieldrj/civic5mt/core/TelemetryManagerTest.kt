package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tick loop: what the models add up to once they are wired together.
 *
 * The individual models are pinned by PrimetimeValidationTest. What is tested here is the
 * wiring between them, which is where the two costly mistakes in this app's history actually
 * happened - the simulator writing into the permanent record, and a missing lambda arriving
 * at the fuel model as a real one.
 *
 * All of this is reachable only because the manager has no timer in it. The TypeScript
 * version started an interval from its constructor, and importing it to reach any of this
 * hung the test runner.
 */
class TelemetryManagerTest {

    private fun manager(clock: MutableClock = MutableClock(1_700_000_000_000)) =
        TelemetryManager(
            clock = clock,
            lifetimeStore = InMemoryLifetimeStore(),
            oilLife = OilLifeEngine(InMemoryOilProfileStore(), clock),
        )

    /** Cruising in 5th: 3000 rpm at 114.93 km/h, light throttle, wideband reading lambda 1.0. */
    private fun cruising() = RawObdData(
        rpm = 3000.0,
        speedKmh = 114.93,
        maf = 12.0,
        coolantC = 88.0,
        engineLoad = 35.0,
        throttlePos = 22.0,
        lambda = 1.0,
        fuelLevelPercent = 60.0,
    )

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("The permanent record")
    inner class Lifetime {

        @Test
        fun `A connected adapter accumulates miles and gallons`() {
            val m = manager()
            repeat(20) { m.tick(cruising(), 0.5, ConnectionStatus.CONNECTED) }

            val life = m.getLifetimeStats()
            assertTrue(life.totalMiles > 0, "got ${life.totalMiles}")
            assertTrue(life.totalFuelGallons > 0, "got ${life.totalFuelGallons}")
        }

        @Test
        fun `The simulator is refused, however long it runs`() {
            // This is the one that actually happened: the simulator runs whenever nothing is
            // connected, and it was filling the lifetime figure with invented driving.
            val m = manager()
            repeat(200) { m.tick(cruising(), 0.5, ConnectionStatus.SIMULATING) }

            assertEquals(0.0, m.getLifetimeStats().totalMiles)
            assertEquals(0.0, m.getLifetimeStats().totalFuelGallons)
        }

        @Test
        fun `A twenty-minute stall is discarded rather than integrated`() {
            // Park with the app open and let the phone lock. The next tick arrives with a
            // gap of twenty minutes against one stale sample, and booking that as driving
            // would put hundreds of imaginary miles into a record that cannot be corrected.
            val m = manager()
            m.tick(cruising(), 1200.0, ConnectionStatus.CONNECTED)

            assertEquals(0.0, m.getLifetimeStats().totalMiles)
            assertEquals(0.0, m.getTrip().distanceMiles)
        }

        @Test
        fun `Lifetime MPG is cumulative miles over cumulative gallons`() {
            val m = manager()
            m.importLifetimeStats(LifetimeStats(totalMiles = 1000.0, totalFuelGallons = 31.25))
            assertEquals(32.0, m.getLifetimeStats().lifetimeMpg)
        }

        @Test
        fun `An imported record survives and is what the gauges read`() {
            // The path the rescued WebView values take: 35.8 mpg over 65.3 mi.
            val m = manager()
            m.importLifetimeStats(
                LifetimeStats(
                    totalMiles = 65.32675216793974,
                    totalFuelGallons = 1.82266559097169,
                    firstTrackedTimestamp = 1786916087877,
                )
            )

            val snapshot = m.tick(cruising(), 0.08, ConnectionStatus.CONNECTED)
            assertEquals(35.8, snapshot.metrics.lifetimeMpg)
            assertTrue(snapshot.metrics.lifetimeMiles > 65.0)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Wiring the models together")
    inner class Wiring {

        @Test
        fun `A car with no wideband reaches the fuel model as no reading`() {
            // The regression that mattered most, at the level where it actually travelled:
            // the manager passes lambda straight through, null included. Defaulting it
            // anywhere along the way is what made a missing PID look stoichiometric.
            val m = manager()
            val noWideband = cruising().copy(lambda = null, stft = 3.91, ltft = 2.34)

            val snapshot = m.tick(noWideband, 0.08, ConnectionStatus.CONNECTED)

            assertNull(snapshot.metrics.equivalenceRatio, "the absence has to survive to the gauge")
            val stoich = fuelBlend(FuelBlendId.E10).stoichAfr
            assertTrue(
                abs(snapshot.metrics.airFuelRatio - stoich) > 0.1,
                "AFR ${snapshot.metrics.airFuelRatio} should have come from the trims, not bare stoichiometry",
            )
        }

        @Test
        fun `Fifth gear at a steady cruise is detected and reported`() {
            val m = manager()
            val snapshot = m.tick(cruising(), 0.08, ConnectionStatus.CONNECTED)
            assertEquals(GearSelection.Gear(5), snapshot.metrics.currentGear)
        }

        @Test
        fun `Closed throttle in gear cuts fuel and reads as coasting, not as economy`() {
            // 2400 rpm at 92 km/h is 5th gear: the overall ratio works out at 3.12, which is
            // what 0.727 x 4.294 comes to. DFCO requires a numbered gear, so an rpm/speed
            // pair that matches no gear is a clutch-in coast and correctly does not qualify.
            val m = manager()
            val coasting = cruising().copy(throttlePos = 0.0, rpm = 2400.0, speedKmh = 92.0)

            val snapshot = m.tick(coasting, 0.08, ConnectionStatus.CONNECTED)

            assertEquals(GearSelection.Gear(5), snapshot.metrics.currentGear)
            assertTrue(snapshot.metrics.isDfcoActive)
            assertEquals(0.0, snapshot.metrics.fuelFlowGalPerHour)
            assertEquals(MpgDisplayState.COASTING, snapshot.metrics.mpgDisplayState)
        }

        @Test
        fun `Standing still reads as idle rather than zero economy`() {
            val m = manager()
            val stopped = cruising().copy(rpm = 750.0, speedKmh = 0.0, throttlePos = 14.0)

            val snapshot = m.tick(stopped, 0.08, ConnectionStatus.CONNECTED)

            assertEquals(MpgDisplayState.IDLE, snapshot.metrics.mpgDisplayState)
            assertEquals(GearSelection.Neutral, snapshot.metrics.currentGear)
        }

        @Test
        fun `Absent readings stay absent all the way to the gauges`() {
            val m = manager()
            val snapshot = m.tick(cruising(), 0.08, ConnectionStatus.CONNECTED)

            assertNull(snapshot.metrics.outsideAirTempC)
            assertNull(snapshot.metrics.outsideAirTempF)
            assertNull(snapshot.metrics.outsideAirSource)
            assertNull(snapshot.metrics.o2Sensor1Voltage)
        }

        @Test
        fun `Outside air from intake air is labelled as intake air`() {
            // 0F reads engine-bay heat after a few minutes of idling, not weather. It must
            // never appear under an "Outside" heading without saying which it is.
            val m = manager()
            val withIntake = cruising().copy(
                ambientC = 31.0,
                ambientSource = OutsideAirSource.INTAKE,
            )

            val snapshot = m.tick(withIntake, 0.08, ConnectionStatus.CONNECTED)

            assertEquals(31.0, snapshot.metrics.outsideAirTempC)
            assertEquals(88, snapshot.metrics.outsideAirTempF)
            assertEquals(OutsideAirSource.INTAKE, snapshot.metrics.outsideAirSource)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Trip analytics")
    inner class Trip {

        @Test
        fun `An engine that is not running contributes nothing`() {
            val m = manager()
            val off = cruising().copy(rpm = 0.0, speedKmh = 0.0)
            repeat(50) { m.tick(off, 0.5, ConnectionStatus.CONNECTED) }

            assertEquals(0.0, m.getTrip().tripDurationSec)
            assertEquals(0.0, m.getTrip().distanceMiles)
        }

        @Test
        fun `Distance and average speed accumulate over a cruise`() {
            val m = manager()
            repeat(120) { m.tick(cruising(), 0.5, ConnectionStatus.CONNECTED) }

            val trip = m.getTrip()
            assertEquals(60.0, trip.tripDurationSec, 0.001)
            assertTrue(trip.distanceMiles > 1.0, "got ${trip.distanceMiles} mi in a minute at 71 mph")
            assertTrue(trip.avgSpeedMph in 70.0..72.0, "got ${trip.avgSpeedMph} mph")
            assertTrue(trip.maxRpm >= 3000.0)
        }

        @Test
        fun `Time at a standstill counts as idle and costs money`() {
            val m = manager()
            val idling = cruising().copy(rpm = 750.0, speedKmh = 0.0)
            repeat(120) { m.tick(idling, 0.5, ConnectionStatus.CONNECTED) }

            val trip = m.getTrip()
            assertEquals(60.0, trip.idleTimeSec, 0.001)
            assertTrue(trip.idleFuelGallons > 0)
            assertTrue(trip.idleCostDollars > 0, "got ${trip.idleCostDollars}")
        }

        @Test
        fun `Resetting the trip does not touch the permanent record`() {
            val m = manager()
            repeat(40) { m.tick(cruising(), 0.5, ConnectionStatus.CONNECTED) }
            val lifetimeMilesBefore = m.getLifetimeStats().totalMiles

            m.resetTrip()

            assertEquals(0.0, m.getTrip().distanceMiles)
            assertEquals(lifetimeMilesBefore, m.getLifetimeStats().totalMiles)
            assertTrue(lifetimeMilesBefore > 0, "the record should have had something in it to keep")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Persistence")
    inner class Persistence {

        @Test
        fun `The record is written out on flush rather than waiting for the debounce`() {
            // Saves are debounced to once per 30 seconds because this runs on every tick. The
            // first integration step writes immediately, then the next 30 seconds of driving
            // accumulate in memory only - and thirty seconds of a record that cannot be
            // regenerated is worth one write when a drive ends.
            val store = InMemoryLifetimeStore()
            val clock = MutableClock(1_700_000_000_000)
            val m = TelemetryManager(
                clock = clock,
                lifetimeStore = store,
                oilLife = OilLifeEngine(InMemoryOilProfileStore(), clock),
            )

            m.tick(cruising(), 0.5, ConnectionStatus.CONNECTED)
            val firstSave = store.load()?.totalMiles ?: 0.0
            assertTrue(firstSave > 0, "the first step writes immediately")

            // More driving, all inside the debounce window.
            repeat(20) { m.tick(cruising(), 0.5, ConnectionStatus.CONNECTED) }
            assertEquals(firstSave, store.load()?.totalMiles, "still the first write - debounced")
            assertTrue(m.getLifetimeStats().totalMiles > firstSave, "but it is accruing in memory")

            m.flush()
            assertEquals(
                m.getLifetimeStats().totalMiles,
                store.load()?.totalMiles,
                "flush writes what is actually held",
            )
        }

        @Test
        fun `A stored record is picked up on construction`() {
            val store = InMemoryLifetimeStore(
                LifetimeStats(totalMiles = 65.3, totalFuelGallons = 1.82)
            )
            val m = TelemetryManager(lifetimeStore = store)

            assertEquals(65.3, m.getLifetimeStats().totalMiles)
        }

        @Test
        fun `Oil wear accrues from the same tick that drives the gauges`() {
            val m = manager()
            val before = m.oilLife.getProfile().accumulatedRevolutions
            repeat(120) { m.tick(cruising(), 0.5, ConnectionStatus.CONNECTED) }

            assertTrue(
                m.oilLife.getProfile().accumulatedRevolutions > before,
                "60 seconds at 3000 rpm should be about 3000 revolutions",
            )
        }

        @Test
        fun `Oil wear does not accrue with the engine off`() {
            val m = manager()
            val before = m.oilLife.getProfile().accumulatedRevolutions
            repeat(60) { m.tick(cruising().copy(rpm = 0.0), 0.5, ConnectionStatus.CONNECTED) }

            assertEquals(before, m.oilLife.getProfile().accumulatedRevolutions)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Fuel blend")
    inner class Blend {

        @Test
        fun `Changing the blend changes what the same airflow burns`() {
            val m = manager()
            m.setFuelBlend(FuelBlendId.E0)
            val onE0 = m.tick(cruising(), 0.08, ConnectionStatus.CONNECTED).metrics.fuelFlowGalPerHour

            m.setFuelBlend(FuelBlendId.E10)
            val onE10 = m.tick(cruising(), 0.08, ConnectionStatus.CONNECTED).metrics.fuelFlowGalPerHour

            assertTrue(onE10 > onE0, "E10 burns more volume for the same air: $onE10 vs $onE0")
        }

        @Test
        fun `Lambda 1_0 reports the blend ratio, not gasoline's 14_7`() {
            val m = manager()
            m.setFuelBlend(FuelBlendId.E10)
            val snapshot = m.tick(cruising(), 0.08, ConnectionStatus.CONNECTED)

            assertEquals(13.78, snapshot.metrics.airFuelRatio, 0.01)
            assertFalse(abs(snapshot.metrics.airFuelRatio - 14.7) < 0.01)
        }
    }
}
