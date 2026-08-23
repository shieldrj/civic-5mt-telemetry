package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The driving bench.
 *
 * The point of testing a simulator is not that its numbers are right - they are invented. It
 * is that it exercises the same code path the car does. A bench that reports PIDs this Civic
 * does not have will certify a path nobody drives, which is exactly what happened before:
 * the bench looked healthy while the real drive displayed seeded constants.
 */
class CivicSimulatorTest {

    private fun run(sim: CivicSimulatorEngine, seconds: Double, dt: Double = 0.08): RawObdData {
        var last = sim.tick(dt)
        val steps = (seconds / dt).toInt()
        repeat(steps) { last = sim.tick(dt) }
        return last
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("It reports the PID set this car actually has")
    inner class PidFidelity {

        @Test
        fun `The pre-catalyst narrowband is absent, because PID 14 is`() {
            // The single most important property here. Inventing this reading is what let the
            // bench certify a code path the car never takes.
            val sim = CivicSimulatorEngine()
            repeat(200) { assertNull(sim.tick(0.08).o2Sensor1Voltage) }
        }

        @Test
        fun `Lambda comes from the wide-range sensor, and the two agree by construction`() {
            // On this car PID 34 is both the pre-catalyst trace and the fuel model's lambda,
            // so they are the same number rather than two that happen to match.
            val data = run(CivicSimulatorEngine(), 10.0)
            assertNotNull(data.lambda)
            assertEquals(data.lambda, data.o2Sensor1Lambda)
        }

        @Test
        fun `Outside air is labelled as intake air, because that is what it is`() {
            // PID 0F reads engine-bay heat, not weather. Reporting it as ambient is what put
            // a fabricated outside temperature on the screen.
            val data = run(CivicSimulatorEngine(), 10.0)
            assertEquals(OutsideAirSource.INTAKE, data.ambientSource)

        @Test
        fun `Every bench sample is stamped as freshly measured`() {
            /*
             * Without the stamp the freshness guard refuses every sample and a simulated
             * drive silently reports zero miles - which looks like broken physics rather than
             * a missing field, and is exactly the kind of failure that costs an afternoon.
             *
             * The bench measures every field on every step, so "just now" is the truth here.
             */
            val clock = MutableClock(1_700_000_000_000)
            val data = run(CivicSimulatorEngine(clock), 10.0)
            assertEquals(clock.nowMillis(), data.motionSampledAtMillis)
            assertTrue(
                IntegrationRules.isFreshEnoughToIntegrate(data.motionSampledAtMillis, clock.nowMillis()),
            )
        }
        }

        @Test
        fun `Intake air climbs as the car sits, rather than drifting with the weather`() {
            val sim = CivicSimulatorEngine()
            val early = sim.tick(0.08).ambientC!!
            val late = run(sim, 300.0).ambientC!!
            assertTrue(late > early, "intake soak should warm: $early -> $late")
        }

        @Test
        fun `Sensor current sits near zero in closed loop and goes negative when rich`() {
            val sim = CivicSimulatorEngine()
            sim.scenario = SimulatorScenario.MANUAL
            sim.throttlePos = 20.0
            val closedLoop = run(sim, 5.0).o2Sensor1CurrentMa!!
            assertTrue(kotlin.math.abs(closedLoop) < 1.0, "near balance, got $closedLoop")

            sim.throttlePos = 95.0 // Wide open: the ECU goes open loop and enriches
            val enriched = run(sim, 2.0).o2Sensor1CurrentMa!!
            assertTrue(enriched < -0.5, "should swing negative when rich, got $enriched")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Drivetrain physics")
    inner class Physics {

        @Test
        fun `The car accelerates from rest in first gear`() {
            val sim = CivicSimulatorEngine()
            sim.scenario = SimulatorScenario.MANUAL
            sim.manualGear = 1
            sim.throttlePos = 60.0

            val data = run(sim, 4.0)
            assertTrue(data.speedKmh > 10, "got ${data.speedKmh} km/h after 4s at 60% throttle")
            assertTrue(data.rpm > CivicSpecs.IDLE_RPM, "got ${data.rpm} rpm")
        }

        @Test
        fun `RPM follows wheel speed through the gearing`() {
            // In gear the engine is locked to the wheels, so the ratio has to come out at the
            // gear's own ratio. This is what makes the bench useful for a gear readout.
            val sim = CivicSimulatorEngine()
            sim.scenario = SimulatorScenario.MANUAL
            sim.manualGear = 3
            sim.throttlePos = 40.0

            val data = run(sim, 8.0)
            val wheelRpm = (data.speedKmh / 60) / CivicSpecs.TIRE_CIRCUMFERENCE_KM
            val observedRatio = data.rpm / wheelRpm
            val expected = CivicSpecs.GEAR_RATIOS.getValue(3) * CivicSpecs.FINAL_DRIVE_RATIO

            assertEquals(expected, observedRatio, 0.05, "3rd gear should read ~$expected")
        }

        @Test
        fun `A closed throttle in gear slows the car through engine braking`() {
            val sim = CivicSimulatorEngine()
            sim.scenario = SimulatorScenario.MANUAL
            sim.manualGear = 4
            sim.throttlePos = 50.0
            val cruising = run(sim, 12.0).speedKmh

            sim.throttlePos = 0.0
            val coasting = run(sim, 6.0).speedKmh

            assertTrue(coasting < cruising, "should decelerate: $cruising -> $coasting")
        }

        @Test
        fun `Neutral lets the engine rev free while the car coasts down`() {
            val sim = CivicSimulatorEngine()
            sim.scenario = SimulatorScenario.MANUAL
            sim.manualGear = 4
            sim.throttlePos = 60.0
            val rolling = run(sim, 10.0)

            sim.manualGear = null // Neutral
            sim.throttlePos = 80.0
            val revving = run(sim, 2.0)

            assertTrue(revving.rpm > rolling.rpm, "free revving should climb")
            assertTrue(revving.speedKmh < rolling.speedKmh, "and the car should be coasting down")
        }

        @Test
        fun `The rev limiter is respected`() {
            val sim = CivicSimulatorEngine()
            sim.scenario = SimulatorScenario.SPIRITED_PULL
            repeat(2000) {
                val data = sim.tick(0.08)
                assertTrue(
                    data.rpm <= CivicSpecs.REV_LIMITER_RPM,
                    "went past the limiter: ${data.rpm}",
                )
            }
        }

        @Test
        fun `Coolant warms toward operating temperature and stops there`() {
            val data = run(CivicSimulatorEngine(), 600.0)
            assertEquals(CivicSpecs.OPTIMAL_OPERATING_TEMP_C, data.coolantC, 1.0)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Driven through the models")
    inner class ThroughTheModels {

        // The bench shares the manager's clock on purpose. It stamps every snapshot as freshly
        // measured, and the freshness guard compares that stamp against the manager's idea of
        // now - so two different clocks make every simulated sample look stale and the bench
        // silently reports zero miles.

        @Test
        fun `A city commute produces a plausible economy figure`() {
            // End to end: the bench feeds the same manager the car does.
            val clock = MutableClock(1_700_000_000_000)
            val manager = TelemetryManager(
                clock = clock,
                lifetimeStore = InMemoryLifetimeStore(),
                oilLife = OilLifeEngine(InMemoryOilProfileStore(), clock),
            )
            val sim = CivicSimulatorEngine(clock)

            repeat(1500) { // Two minutes of city driving at 80ms
                clock.advanceMillis(80)
                manager.tick(sim.tick(0.08), 0.08, ConnectionStatus.SIMULATING)
            }

            val trip = manager.getTrip()
            assertTrue(trip.distanceMiles > 0.1, "got ${trip.distanceMiles} mi")
            assertTrue(trip.avgMpg in 5.0..80.0, "implausible economy: ${trip.avgMpg} mpg")
        }

        @Test
        fun `However far the bench drives, the permanent record stays untouched`() {
            // The bench runs whenever nothing is connected. This is the guard that stops it
            // filling the lifetime figure with driving that never happened.
            val clock = MutableClock(1_700_000_000_000)
            val manager = TelemetryManager(
                clock = clock,
                lifetimeStore = InMemoryLifetimeStore(),
                oilLife = OilLifeEngine(InMemoryOilProfileStore(), clock),
            )
            val sim = CivicSimulatorEngine(clock)
            sim.scenario = SimulatorScenario.HIGHWAY_CRUISE

            repeat(3000) {
                clock.advanceMillis(80)
                manager.tick(sim.tick(0.08), 0.08, ConnectionStatus.SIMULATING)
            }

            assertTrue(manager.getTrip().distanceMiles > 1.0, "the bench did drive")
            assertEquals(0.0, manager.getLifetimeStats().totalMiles, "but the record is untouched")
        }

        @Test
        fun `The commute reaches a coast where the injectors shut off`() {
            // Phase 5 of the script exists to demonstrate DFCO, so it should actually get there.
            val clock = MutableClock(1_700_000_000_000)
            val manager = TelemetryManager(
                clock = clock,
                lifetimeStore = InMemoryLifetimeStore(),
                oilLife = OilLifeEngine(InMemoryOilProfileStore(), clock),
            )
            val sim = CivicSimulatorEngine(clock)

            var sawDfco = false
            repeat(1500) {
                clock.advanceMillis(80)
                val snapshot = manager.tick(sim.tick(0.08), 0.08, ConnectionStatus.SIMULATING)
                if (snapshot.metrics.isDfcoActive) sawDfco = true
            }

            assertTrue(sawDfco, "the city commute script never reached its DFCO phase")
        }
    }
}
