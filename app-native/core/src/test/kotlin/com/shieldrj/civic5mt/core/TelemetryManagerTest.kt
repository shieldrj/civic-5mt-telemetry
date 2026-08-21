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

    /**
     * These tests never advance the clock, so a fixture stamped here is "measured just now"
     * and passes the freshness guard. A fixture WITHOUT the stamp reads as never-measured and
     * integrates nothing - which is the guard doing its job, not a broken test.
     */
    private val T0 = 1_700_000_000_000L

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
        motionSampledAtMillis = T0,
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
        fun `A snapshot nobody measured recently books no gallons, not just no miles`() {
            /*
             * The ignition-off leak, and the reason the freshness rule exists.
             *
             * Every field on RawObdData carries forward on a non-answer, so switching off at
             * idle leaves rpm at 750, speed at 0 and MAF at 2.8 - and the rpm >= 350 gate
             * reads that as a running engine, because it tests the value and not its age.
             * Step miles came out zero (speed was zero) and step gallons did not, and
             * updateLifetime returns early only when BOTH are zero. So lifetime gallons grew
             * while lifetime miles stood still, diluting the MPG figure a little on every
             * single drive, in a direction that never corrects itself.
             *
             * The gallons assertion is the one that matters here. Miles were already zero for
             * an unrelated reason, which is exactly why this went unnoticed for so long.
             */
            val m = manager()
            val stale = cruising().copy(
                rpm = 750.0,
                speedKmh = 0.0,
                motionSampledAtMillis = T0 - IntegrationRules.MAX_READING_AGE_MS - 1,
            )
            repeat(100) { m.tick(stale, 0.08, ConnectionStatus.CONNECTED) }

            assertEquals(0.0, m.getLifetimeStats().totalFuelGallons, "fuel that was never burned")
            assertEquals(0.0, m.getLifetimeStats().totalMiles)
        }

        @Test
        fun `A stale speed books no distance either`() {
            // The other half, and the unbounded one: 010C keeps answering while 010D times
            // out repeatedly on a marginal link, so rpm stays fresh and above the gate while
            // speed carries forward at a cruise. That accrued distance from a speed nobody
            // measured, for as long as the asymmetry lasted.
            val m = manager()
            val stale = cruising().copy(
                motionSampledAtMillis = T0 - IntegrationRules.MAX_READING_AGE_MS - 1,
            )
            repeat(100) { m.tick(stale, 0.08, ConnectionStatus.CONNECTED) }

            assertEquals(0.0, m.getLifetimeStats().totalMiles)
        }

        @Test
        fun `A reading that was never measured at all is refused`() {
            // Null is not "measured long ago" - it is a snapshot that has never had a PID
            // answered into it, which is what the first moments of a connection look like.
            val m = manager()
            repeat(50) {
                m.tick(cruising().copy(motionSampledAtMillis = null), 0.5, ConnectionStatus.CONNECTED)
            }

            assertEquals(0.0, m.getLifetimeStats().totalMiles)
            assertEquals(0.0, m.getLifetimeStats().totalFuelGallons)
        }

        @Test
        fun `A reading at the freshness boundary still counts`() {
            // The guard must not shave legitimate samples off a slow but working bus. Erring
            // this way would undercount a real drive, which is quieter but still wrong.
            val m = manager()
            val atBoundary = cruising().copy(
                motionSampledAtMillis = T0 - IntegrationRules.MAX_READING_AGE_MS,
            )
            repeat(20) { m.tick(atBoundary, 0.5, ConnectionStatus.CONNECTED) }

            assertTrue(
                m.getLifetimeStats().totalMiles > 0,
                "got ${m.getLifetimeStats().totalMiles}",
            )
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
        fun `A standstill on stale readings costs no idle fuel`() {
            // The same leak as the lifetime one, seen from the trip side. Switch off at a
            // standstill and idleFuelGallons kept accruing from a MAF reading the car had
            // stopped sending, so a parked car quietly ran up an idle bill.
            val m = manager()
            val stale = cruising().copy(
                rpm = 750.0,
                speedKmh = 0.0,
                motionSampledAtMillis = T0 - IntegrationRules.MAX_READING_AGE_MS - 1,
            )
            repeat(200) { m.tick(stale, 0.08, ConnectionStatus.CONNECTED) }

            val trip = m.getTrip()
            assertEquals(0.0, trip.idleFuelGallons)
            assertEquals(0.0, trip.idleTimeSec)
            assertEquals(0.0, trip.idleCostDollars)
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

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("The tank")
    inner class Tank {

        private fun withFuel(level: Double?) = cruising().copy(fuelLevelPercent = level)

        @Test
        fun `A bench run reports no range rather than zero miles to empty`() {
            // Zero reads as an alarm. The simulator reports a tank level, but nothing has been
            // tracked against it, so there is no answer to give.
            val m = manager()
            val snapshot = m.tick(withFuel(68.0), 0.08, ConnectionStatus.SIMULATING)

            assertNull(snapshot.metrics.fuelRangeMiles)
            assertNull(snapshot.metrics.tankMpg)
            assertNull(snapshot.metrics.tankGallonsRemaining)
        }

        @Test
        fun `A bench run does not consume anyone's tank`() {
            val m = manager()
            repeat(200) { m.tick(withFuel(68.0), 0.5, ConnectionStatus.SIMULATING) }

            assertEquals(0.0, m.tank.get().milesSinceFill)
            assertEquals(0.0, m.tank.get().gallonsUsedSinceFill)
        }

        @Test
        fun `A real drive fills in the range straight away`() {
            val m = manager()
            val snapshot = m.tick(withFuel(68.0), 0.5, ConnectionStatus.CONNECTED)

            assertNotNull(snapshot.metrics.fuelRangeMiles)
            assertTrue(snapshot.metrics.fuelRangeMiles!! > 100, "got ${snapshot.metrics.fuelRangeMiles}")
        }

        @Test
        fun `A car with no tank level gets no range at all`() {
            val m = manager()
            repeat(50) { m.tick(withFuel(null), 0.5, ConnectionStatus.CONNECTED) }

            val snapshot = m.tick(withFuel(null), 0.5, ConnectionStatus.CONNECTED)
            assertNull(snapshot.metrics.fuelRangeMiles)
            assertNull(snapshot.metrics.tankGallonsRemaining)
        }

        @Test
        fun `Range holds steady while economy moves`() {
            // The complaint. Range used to be the tank level times a 30-second average, so it
            // tracked every hill.
            val m = manager()
            // Long enough that this tank has an economy figure of its own to stand on.
            repeat(20_000) { m.tick(withFuel(68.0), 0.5, ConnectionStatus.CONNECTED) }
            val steady = m.tick(withFuel(68.0), 0.5, ConnectionStatus.CONNECTED).metrics.fuelRangeMiles!!
            assertNotNull(m.tank.get().tankMpg)

            // Two minutes crawling in first with the throttle open: economy collapses.
            val labouring = RawObdData(
                rpm = 3200.0, speedKmh = 20.0, maf = 22.0, coolantC = 88.0,
                engineLoad = 85.0, throttlePos = 60.0, lambda = 0.88, fuelLevelPercent = 68.0,
                motionSampledAtMillis = T0,
            )
            repeat(240) { m.tick(labouring, 0.5, ConnectionStatus.CONNECTED) }
            val after = m.tick(labouring, 0.5, ConnectionStatus.CONNECTED).metrics.fuelRangeMiles!!

            assertTrue(
                abs(after - steady) < steady * 0.2,
                "range moved from $steady to $after",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Oil life")
    inner class Oil {

        /** Warmed through, engine turning. */
        private fun hot() = cruising()

        /** A genuinely cold engine, idling on the driveway. */
        private fun coldIdle() = RawObdData(
            rpm = 1100.0,
            speedKmh = 0.0,
            maf = 3.0,
            coolantC = 21.0,
            engineLoad = 22.0,
            throttlePos = 14.0,
            lambda = 1.0,
            motionSampledAtMillis = T0,
        )

        /** Key on, engine not running: every PID answers, the crank is not turning. */
        private fun ignitionOnly() = RawObdData(rpm = 0.0, coolantC = 21.0, motionSampledAtMillis = T0)

        @Test
        fun `A simulated drive leaves the oil record exactly where it was`() {
            // The same rule the lifetime figure has, applied to the other permanent record.
            // Oil life is the number someone changes their oil on, and a bench run is not
            // wear on anyone's engine.
            val m = manager()
            val before = m.oilLife.getProfile()

            repeat(200) { m.tick(hot(), 0.5, ConnectionStatus.SIMULATING) }

            val after = m.oilLife.getProfile()
            assertEquals(before.accumulatedRevolutions, after.accumulatedRevolutions)
            assertEquals(before.currentOdometer, after.currentOdometer)
            assertEquals(before.oilLifePercent, after.oilLifePercent)
        }

        @Test
        fun `A real drive accrues revolutions and miles`() {
            val m = manager()
            val before = m.oilLife.getProfile()

            repeat(200) { m.tick(hot(), 0.5, ConnectionStatus.CONNECTED) }

            val after = m.oilLife.getProfile()
            assertTrue(after.accumulatedRevolutions > before.accumulatedRevolutions)
            assertTrue(after.currentOdometer > before.currentOdometer)
        }

        @Test
        fun `A twenty-minute stall adds no revolutions to the oil record`() {
            /*
             * This is a fix, not a guard against a hypothetical.
             *
             * The oil model was handed the DISPLAY step rather than the integration step,
             * while the trip and the tank both got the integration step. So the stalled-timer
             * gap that IntegrationRules correctly throws away for distance was accepted here
             * in full: park with the app open, let the phone lock for twenty minutes, and the
             * next tick added twenty minutes of running at 3000 rpm - about sixty thousand
             * crank revolutions and twelve hundred seconds of cold-running time - to a
             * maintenance figure someone changes their oil on.
             *
             * The existing test for this stall only asserted on totalMiles, so it passed the
             * whole time.
             */
            val m = manager()
            val before = m.oilLife.getProfile()

            m.tick(hot(), 1200.0, ConnectionStatus.CONNECTED)

            val after = m.oilLife.getProfile()
            assertEquals(before.accumulatedRevolutions, after.accumulatedRevolutions)
            assertEquals(before.currentOdometer, after.currentOdometer)
        }

        @Test
        fun `Stale readings add no revolutions either`() {
            // The oil model reads rpm straight out of the snapshot, so a carried-forward 3000
            // rpm from an ECU that went to sleep looks exactly like an engine at a cruise.
            val m = manager()
            val before = m.oilLife.getProfile()

            val stale = hot().copy(motionSampledAtMillis = T0 - IntegrationRules.MAX_READING_AGE_MS - 1)
            repeat(200) { m.tick(stale, 0.5, ConnectionStatus.CONNECTED) }

            assertEquals(before.accumulatedRevolutions, m.oilLife.getProfile().accumulatedRevolutions)
        }

        @Test
        fun `Reconnecting to a hot engine is not a cold start`() {
            // This is the bug being closed. The TypeScript read the coolant temperature at
            // the moment the socket opened - before any PID had answered - so it read the
            // zero-initialised default and logged a cold start on every single connection.
            val m = manager()
            val before = m.oilLife.getProfile().coldStartsCount

            m.tick(ignitionOnly(), 0.5, ConnectionStatus.CONNECTED)
            repeat(20) { m.tick(hot(), 0.5, ConnectionStatus.CONNECTED) }

            assertEquals(before, m.oilLife.getProfile().coldStartsCount)
        }

        @Test
        fun `A genuinely cold engine is counted once, not once per tick`() {
            val m = manager()
            val before = m.oilLife.getProfile().coldStartsCount

            repeat(50) { m.tick(coldIdle(), 0.5, ConnectionStatus.CONNECTED) }

            assertEquals(before + 1, m.oilLife.getProfile().coldStartsCount)
        }

        @Test
        fun `A short cold trip is counted against the oil when the drive ends`() {
            // Under fifteen minutes and never hot enough to boil the condensation out. The
            // whole reason the counter exists, and it could not fire at all until now:
            // nothing in the native build was calling registerEngineStop.
            val m = manager()
            val before = m.oilLife.getProfile().shortTripsCount

            repeat(300) { m.tick(coldIdle(), 0.5, ConnectionStatus.CONNECTED) } // 150s
            m.endDrive()

            assertEquals(before + 1, m.oilLife.getProfile().shortTripsCount)
        }

        @Test
        fun `Ending a simulated drive counts nothing`() {
            val m = manager()
            val before = m.oilLife.getProfile()

            repeat(300) { m.tick(coldIdle(), 0.5, ConnectionStatus.SIMULATING) }
            m.endDrive()

            assertEquals(before.shortTripsCount, m.oilLife.getProfile().shortTripsCount)
            assertEquals(before.coldStartsCount, m.oilLife.getProfile().coldStartsCount)
        }

        @Test
        fun `Loading a record recomputes what is derived from it`() {
            // The stored percentage, grade and miles-remaining are views of the stored
            // measurements, exactly as lifetime mpg is a view of miles over gallons. A record
            // written by an older calculation must not keep showing that calculation's answer.
            val store = InMemoryOilProfileStore(
                OilLifeProfile(
                    lastResetTimestamp = 1_700_000_000_000 - 45L * 24 * 60 * 60 * 1000,
                    lastResetOdometer = 114_254.0,
                    currentOdometer = 115_583.0,
                    oilLifePercent = 12.0,          // nonsense, next to the measurements
                    accumulatedRevolutions = 665_008.0,
                    coldStartsCount = 0,
                    timeBelowOperatingTempSec = 2294.0,
                    shortTripsCount = 0,
                    highThermalStressSec = 0.0,
                    estimatedMilesRemaining = 26_122,
                    estimatedDaysRemaining = 45,
                    oilConditionGrade = OilConditionGrade.DEGRADED,
                    degradationBreakdown = DegradationBreakdown(0.0, 0.0, 0.0, 0.0),
                )
            )

            // No tick, no drive - construction alone.
            val p = OilLifeEngine(store, MutableClock(1_700_000_000_000)).getProfile()

            assertTrue(p.oilLifePercent > 90, "got ${p.oilLifePercent}%")
            assertEquals(OilConditionGrade.EXCELLENT, p.oilConditionGrade)
            assertTrue(
                p.estimatedMilesRemaining <= CivicSpecs.BASELINE_OIL_LIFE_MILES.toInt(),
                "got ${p.estimatedMilesRemaining} mi",
            )
        }

        @Test
        fun `The interval estimate never projects past the service interval`() {
            // Light wear over a short distance extrapolates enormously: 1,300 miles at 5%
            // degradation projects to 26,000. That is what the rescued record was showing,
            // and 26,000 miles is not an oil change interval anybody should be offered.
            val store = InMemoryOilProfileStore(
                OilLifeProfile(
                    lastResetTimestamp = 1_700_000_000_000 - 45L * 24 * 60 * 60 * 1000,
                    lastResetOdometer = 114_254.0,
                    currentOdometer = 115_583.0,
                    oilLifePercent = 95.2,
                    accumulatedRevolutions = 665_008.0,
                    coldStartsCount = 0,
                    timeBelowOperatingTempSec = 2294.0,
                    shortTripsCount = 0,
                    highThermalStressSec = 0.0,
                    estimatedMilesRemaining = 26_122,
                    estimatedDaysRemaining = 45,
                    oilConditionGrade = OilConditionGrade.EXCELLENT,
                    degradationBreakdown = DegradationBreakdown(4.6, 0.3, 0.0, 0.0),
                )
            )
            val clock = MutableClock(1_700_000_000_000)
            val engine = OilLifeEngine(store, clock)
            engine.recordTelemetryStep(2500.0, 88.0, 35.0, 60.0, 0.5)

            val p = engine.getProfile()
            assertTrue(
                p.estimatedMilesRemaining <= CivicSpecs.BASELINE_OIL_LIFE_MILES.toInt(),
                "projected ${p.estimatedMilesRemaining} mi remaining",
            )
            // Still proportional to what is left, rather than pinned to the cap.
            assertTrue(p.estimatedMilesRemaining > 6_000, "got ${p.estimatedMilesRemaining}")
        }

        @Test
        fun `A days-remaining figure waits until there is a rate behind it`() {
            // Miles remaining over miles per day. Measured across three days of a record that
            // happens to include a long trip, that divisor is noise - it read "about 12 days"
            // on oil at 95%, which would need thirteen hundred miles a day to be true.
            fun profileAgedDays(days: Long) = OilLifeProfile(
                lastResetTimestamp = 1_700_000_000_000 - days * 24 * 60 * 60 * 1000,
                lastResetOdometer = 114_254.0,
                currentOdometer = 115_583.0,
                oilLifePercent = 95.2,
                accumulatedRevolutions = 665_008.0,
                coldStartsCount = 0,
                timeBelowOperatingTempSec = 2294.0,
                shortTripsCount = 0,
                highThermalStressSec = 0.0,
                estimatedMilesRemaining = 7_137,
                estimatedDaysRemaining = 12,
                oilConditionGrade = OilConditionGrade.EXCELLENT,
                degradationBreakdown = DegradationBreakdown(4.6, 0.3, 0.0, 0.0),
            )

            val fresh = OilLifeEngine(
                InMemoryOilProfileStore(profileAgedDays(3)),
                MutableClock(1_700_000_000_000),
            ).getProfile()
            assertNull(fresh.estimatedDaysRemaining, "three days is not a driving habit")

            val settled = OilLifeEngine(
                InMemoryOilProfileStore(profileAgedDays(90)),
                MutableClock(1_700_000_000_000),
            ).getProfile()
            assertNotNull(settled.estimatedDaysRemaining)
            assertTrue(settled.estimatedDaysRemaining!! > 0)
        }

        @Test
        fun `A fresh reset has no days estimate to give`() {
            val engine = OilLifeEngine(InMemoryOilProfileStore(), MutableClock(1_700_000_000_000))
            assertNull(engine.resetOilLife(120_000.0).estimatedDaysRemaining)
        }

        @Test
        fun `Harsh use still shortens the interval below the baseline`() {
            // The cap is one-sided on purpose: it clips optimism, not the warning.
            val store = InMemoryOilProfileStore(
                OilLifeProfile(
                    lastResetTimestamp = 1_700_000_000_000 - 200L * 24 * 60 * 60 * 1000,
                    lastResetOdometer = 100_000.0,
                    currentOdometer = 103_000.0,
                    oilLifePercent = 40.0,
                    accumulatedRevolutions = 8_000_000.0,
                    coldStartsCount = 120,
                    timeBelowOperatingTempSec = 90_000.0,
                    shortTripsCount = 60,
                    highThermalStressSec = 4_000.0,
                    estimatedMilesRemaining = 0,
                    estimatedDaysRemaining = 0,
                    oilConditionGrade = OilConditionGrade.FAIR,
                    degradationBreakdown = DegradationBreakdown(0.0, 0.0, 0.0, 0.0),
                )
            )
            val engine = OilLifeEngine(store, MutableClock(1_700_000_000_000))
            engine.recordTelemetryStep(2500.0, 88.0, 35.0, 60.0, 0.5)

            assertTrue(
                engine.getProfile().estimatedMilesRemaining < 3_000,
                "got ${engine.getProfile().estimatedMilesRemaining}",
            )
        }

        @Test
        fun `Ending the same drive twice does not count it twice`() {
            val m = manager()
            repeat(300) { m.tick(coldIdle(), 0.5, ConnectionStatus.CONNECTED) }

            m.endDrive()
            val afterFirst = m.oilLife.getProfile().shortTripsCount
            m.endDrive()

            assertEquals(afterFirst, m.oilLife.getProfile().shortTripsCount)
        }
    }
}
