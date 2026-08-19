package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 2013 Civic 5MT telemetry engine's specification, carried over from
 * `src/tests/primetime_validation.ts`.
 *
 * Every assertion in the TypeScript suite has one test here, and the comments explaining
 * *why* a value is pinned came across with them - they are the part that stops a future
 * change quietly reverting a fix. A Kotlin rewrite of the fuel model that computes
 * something different from the TypeScript one is exactly what this file exists to catch,
 * so it was written before the models it tests.
 *
 * Where the port changed a signature - a nullable lambda, a sealed gear type, an injected
 * clock - the assertion is the same claim expressed against the new type. Nothing here was
 * relaxed to make a port pass.
 */
class PrimetimeValidationTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("1. Gear deduction & manual transmission dynamics")
    inner class GearDeduction {

        /** A fixed clock: gear detection is a ratio, and must not depend on tick timing. */
        private fun engine() = GearCalculatorEngine(MutableClock(0))

        @Test
        fun `1st gear detected correctly`() {
            // 1st gear (13.50 overall ratio) -> 3000 RPM at 26.58 km/h (16.5 mph)
            val g = engine().analyzeGear(3000.0, 26.58, 20.0)
            assertEquals(GearSelection.Gear(1), g.currentGear, "ratio ${g.calculatedRatio}")
        }

        @Test
        fun `2nd gear detected correctly`() {
            // 2nd gear (8.03 overall ratio) -> 3000 RPM at 44.67 km/h (27.8 mph)
            val g = engine().analyzeGear(3000.0, 44.67, 20.0)
            assertEquals(GearSelection.Gear(2), g.currentGear, "ratio ${g.calculatedRatio}")
        }

        @Test
        fun `3rd gear detected correctly`() {
            // 3rd gear (5.30 overall ratio) -> 3000 RPM at 67.66 km/h (42.0 mph)
            val g = engine().analyzeGear(3000.0, 67.66, 20.0)
            assertEquals(GearSelection.Gear(3), g.currentGear, "ratio ${g.calculatedRatio}")
        }

        @Test
        fun `4th gear detected correctly`() {
            // 4th gear (4.07 overall ratio) -> 3000 RPM at 88.04 km/h (54.7 mph)
            val g = engine().analyzeGear(3000.0, 88.04, 20.0)
            assertEquals(GearSelection.Gear(4), g.currentGear, "ratio ${g.calculatedRatio}")
        }

        @Test
        fun `5th gear detected correctly`() {
            // 5th gear (3.12 overall ratio) -> 3000 RPM at 114.93 km/h (71.4 mph)
            val g = engine().analyzeGear(3000.0, 114.93, 20.0)
            assertEquals(GearSelection.Gear(5), g.currentGear, "ratio ${g.calculatedRatio}")
        }

        @Test
        fun `Neutral detected when stationary`() {
            val g = engine().analyzeGear(750.0, 0.0, 0.0)
            assertEquals(GearSelection.Neutral, g.currentGear)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("2. Physics fuel model & deceleration fuel cut-off (DFCO)")
    inner class FuelPhysics {

        private val stoichE10 = fuelBlend(FuelBlendId.E10).stoichAfr

        private fun model(blend: FuelBlendId) = FuelModelEngine().apply { setFuelBlend(blend) }

        // Air-fuel ratio computation. Stoichiometry is a property of the fuel, not a
        // constant: the ECU targets lambda 1.0 for whatever is in the tank, so both
        // directions are pinned.

        @Test
        fun `AFR at stoichiometry on E0 is 14_7 to 1`() {
            val afr = model(FuelBlendId.E0).calculateAirFuelRatio(1.0, 0.0, 0.0)
            assertTrue(abs(afr - 14.7) < 0.05, "got $afr")
        }

        @Test
        fun `AFR at stoichiometry on E10 is 13_78 to 1`() {
            val afr = model(FuelBlendId.E10).calculateAirFuelRatio(1.0, 0.0, 0.0)
            assertTrue(abs(afr - 13.78) < 0.05, "got $afr")
        }

        @Test
        fun `Rich AFR properly calculated for power pull`() {
            val afr = model(FuelBlendId.E10).calculateAirFuelRatio(0.85, 0.0, 0.0)
            assertTrue(afr < 13.0, "got $afr")
        }

        /*
         * A car with no wideband PID must fall through to the fuel trims.
         *
         * This is the regression that mattered most. The lambda argument used to default to
         * 1.0, so a car that reports neither PID 24 nor 34 - which is this Civic - arrived
         * here with an apparently valid stoichiometric reading on every tick. It passed the
         * validity range, took the wideband branch, and returned bare stoichiometry forever
         * while the real fuel trims sitting in the next two arguments were discarded. Both
         * halves are pinned below: null must reach the trims, and a genuine 1.0 must still
         * suppress them.
         */

        @Test
        fun `No wideband PID falls through to fuel trims instead of bare stoichiometry`() {
            val afr = model(FuelBlendId.E10).calculateAirFuelRatio(null, 3.91, 2.34)
            assertTrue(abs(afr - stoichE10) > 0.1, "got $afr vs stoich $stoichE10")
        }

        @Test
        fun `Positive trims mean the ECU is adding fuel, so AFR lands below stoichiometry`() {
            val afr = model(FuelBlendId.E10).calculateAirFuelRatio(null, 3.91, 2.34)
            assertTrue(afr < stoichE10, "got $afr")
        }

        @Test
        fun `Negative trims mean the ECU is pulling fuel, so AFR lands above stoichiometry`() {
            val afr = model(FuelBlendId.E10).calculateAirFuelRatio(null, -4.0, -2.0)
            assertTrue(afr > stoichE10, "got $afr")
        }

        // A real wideband reading of 1.0 already accounts for the trims; applying them again
        // would double-count. So the same trims must be ignored when lambda was measured.

        @Test
        fun `A measured lambda of 1_0 overrides the trims rather than compounding them`() {
            val afr = model(FuelBlendId.E10).calculateAirFuelRatio(1.0, 3.91, 2.34)
            assertTrue(abs(afr - stoichE10) < 0.001, "got $afr")
        }

        @Test
        fun `A measured stoichiometric reading and an inferred one are distinguishable`() {
            val m = model(FuelBlendId.E10)
            val measured = m.calculateAirFuelRatio(1.0, 3.91, 2.34)
            val inferred = m.calculateAirFuelRatio(null, 3.91, 2.34)
            assertNotEquals(measured, inferred)
        }

        @Test
        fun `Idle fuel burn rate realistic`() {
            // 2.8 g/s MAF at 14.7 AFR
            val flow = model(FuelBlendId.E10).calculateFuelFlow(2.8, 14.7, false)
            assertTrue(
                flow.fuelFlowLitersPerHour > 0.8 && flow.fuelFlowLitersPerHour < 1.8,
                "got ${flow.fuelFlowLitersPerHour} L/hr",
            )
        }

        @Test
        fun `Highway 5th gear cruising MPG realistic`() {
            // 65 mph at 1.45 gal/hr
            val mpg = model(FuelBlendId.E10).calculateInstantMpg(65.0, 1.45, false)
            assertTrue(mpg > 40 && mpg < 48, "got $mpg MPG")
        }

        @Test
        fun `DFCO triggers when coasting in gear with closed throttle`() {
            val dfco = model(FuelBlendId.E10)
                .checkDfco(0.0, 2400.0, 50.0, GearSelection.Gear(4))
            assertTrue(dfco)
        }

        @Test
        fun `DFCO cuts fuel burn rate to 0_00 GPH`() {
            val m = model(FuelBlendId.E10)
            val dfco = m.checkDfco(0.0, 2400.0, 50.0, GearSelection.Gear(4))
            val flow = m.calculateFuelFlow(2.0, 14.7, dfco)
            assertEquals(0.0, flow.fuelFlowGalPerHour)
        }

        @Test
        fun `DFCO returns 99_9 plus MPG`() {
            val m = model(FuelBlendId.E10)
            val dfco = m.checkDfco(0.0, 2400.0, 50.0, GearSelection.Gear(4))
            val flow = m.calculateFuelFlow(2.0, 14.7, dfco)
            val mpg = m.calculateInstantMpg(45.0, flow.fuelFlowGalPerHour, dfco)
            assertTrue(mpg >= 99.9, "got $mpg")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("3. 4-factor deep oil life algorithm")
    inner class OilLife {

        private fun engine() = OilLifeEngine(InMemoryOilProfileStore(), MutableClock(1_700_000_000_000))

        @Test
        fun `Initial oil life in valid range`() {
            val p = engine().getProfile()
            assertTrue(p.oilLifePercent in 0.0..100.0, "got ${p.oilLifePercent}%")
        }

        @Test
        fun `Cold start counter incremented`() {
            val e = engine()
            val before = e.getProfile().coldStartsCount
            e.registerEngineStart(30.0) // 30°C cold start
            assertEquals(before + 1, e.getProfile().coldStartsCount)
        }

        @Test
        fun `Mechanical revolutions accumulated`() {
            val e = engine()
            e.registerEngineStart(30.0)
            val before = e.getProfile().accumulatedRevolutions
            // 60 seconds at 3,500 RPM
            e.recordTelemetryStep(3500.0, 85.0, 45.0, 60.0, 60.0)
            assertTrue(
                e.getProfile().accumulatedRevolutions > before,
                "gained ${e.getProfile().accumulatedRevolutions - before} revs",
            )
        }

        @Test
        fun `Oil life successfully reset to 100 percent`() {
            val e = engine()
            e.recordTelemetryStep(3500.0, 85.0, 45.0, 60.0, 60.0)
            assertEquals(100.0, e.resetOilLife(115000.0).oilLifePercent)
        }

        @Test
        fun `Accumulated revolutions reset to 0`() {
            val e = engine()
            e.recordTelemetryStep(3500.0, 85.0, 45.0, 60.0, 60.0)
            assertEquals(0.0, e.resetOilLife(115000.0).accumulatedRevolutions)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("4. Diagnostic trouble code (DTC) database")
    inner class DtcDatabase {

        @Test
        fun `Honda DTC database loaded`() {
            assertTrue(HONDA_DTC_DATABASE.size >= 10, "got ${HONDA_DTC_DATABASE.size} codes")
        }

        @Test
        fun `P0133 Upstream O2 Sensor slow response code verified`() {
            assertTrue(HONDA_DTC_DATABASE.containsKey("P0133"))
        }

        @Test
        fun `P0420 Catalyst System Efficiency code verified`() {
            assertTrue(HONDA_DTC_DATABASE.containsKey("P0420"))
        }

        @Test
        fun `P0301 Cylinder 1 Misfire code verified`() {
            assertTrue(HONDA_DTC_DATABASE.containsKey("P0301"))
        }

        @Test
        fun `P0171 Fuel System Too Lean code verified`() {
            assertTrue(HONDA_DTC_DATABASE.containsKey("P0171"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("5. Battery / fuel level / O2 sensor PIDs & range-to-empty")
    inner class DefaultsAndRange {

        private val defaults = RawObdData()

        @Test
        fun `Battery voltage default in plausible range`() {
            assertTrue(defaults.batteryVoltage > 9 && defaults.batteryVoltage < 16, "got ${defaults.batteryVoltage}V")
        }

        @Test
        fun `An unreported tank level is absent, not a five-eighths tank`() {
            // It used to default to 65.0, which on a car with no PID 2F rendered as a tank
            // level and a range to empty that had never been measured.
            assertNull(defaults.fuelLevelPercent)
        }

        @Test
        fun `No tank level means no range to empty`() {
            val m = TelemetryManager(lifetimeStore = InMemoryLifetimeStore())
            val snapshot = m.tick(RawObdData(rpm = 2500.0, speedKmh = 90.0), 0.08, ConnectionStatus.CONNECTED)
            assertNull(snapshot.metrics.fuelRangeMiles)
        }

        @Test
        fun `O2 Sensor 2 post-cat default within PID full scale`() {
            // PID 0115 byte A is A/200, so full scale is 0 - 1.275V (not 1.0V - a narrowband
            // sensor only *uses* roughly 0.1-0.9V of that range in practice). Every car
            // answers 0115.
            assertTrue(defaults.o2Sensor2Voltage in 0.0..1.275, "got ${defaults.o2Sensor2Voltage}V")
        }

        @Test
        fun `Engine runtime default non-negative`() {
            assertTrue(defaults.engineRuntimeSec >= 0, "got ${defaults.engineRuntimeSec}s")
        }

        /*
         * Readings that may not exist start as null - strictly null, not "a plausible
         * number". These three were 1.0, 22 and 0.45. On a car lacking the PID behind them,
         * that seed is what the gauge displayed indefinitely, indistinguishable on screen
         * from a measurement. Asserted as null deliberately: the range checks these replace
         * would both still pass against a null coerced to 0, so a range check here is a
         * check that has quietly stopped running.
         */

        @Test
        fun `Lambda starts as no-reading, not a stoichiometric-looking 1_0`() {
            assertNull(defaults.lambda)
        }

        @Test
        fun `Outside air starts as no-reading, not a room-temperature 22`() {
            assertNull(defaults.ambientC)
        }

        @Test
        fun `No outside-air reading means no source to attribute it to`() {
            assertNull(defaults.ambientSource)
        }

        @Test
        fun `Pre-catalyst voltage starts as no-reading, not a switch-point 0_45`() {
            assertNull(defaults.o2Sensor1Voltage)
        }

        @Test
        fun `Pre-catalyst lambda starts as no-reading`() {
            assertNull(defaults.o2Sensor1Lambda)
        }

        @Test
        fun `Wide-range sensor current starts as no-reading`() {
            assertNull(defaults.o2Sensor1CurrentMa)
        }

        @Test
        fun `Fuel range calculated correctly`() {
            // 50% of a 13.2 gal tank at 30 MPG -> 6.6 gal * 30 mpg = 198 miles
            val range = FuelModelEngine()
                .calculateFuelRange(50.0, CivicSpecs.FUEL_TANK_CAPACITY_GALLONS, 30.0)
            assertTrue(abs(range - 198) < 1, "got $range mi, expected ~198 mi")
        }

        @Test
        fun `Fuel range falls back to EPA combined MPG before a rolling sample exists`() {
            val range = FuelModelEngine()
                .calculateFuelRange(100.0, CivicSpecs.FUEL_TANK_CAPACITY_GALLONS, 0.0)
            val expected = CivicSpecs.FUEL_TANK_CAPACITY_GALLONS * CivicSpecs.EPA_COMBINED_MPG_DEFAULT
            assertTrue(abs(range - expected) < 1, "got $range mi")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("6. Emissions readiness monitor decoding")
    inner class Readiness {

        // Byte B: bits 0-2 supported (misfire, fuel, comprehensive), bits 4-6 incomplete.
        // Byte C: supported spark-ignition monitors. Byte D: the matching incomplete bits.
        // 0x07 = all three common tests supported, none incomplete -> all Ready.
        private val allReady = decodeReadinessMonitors(0x07, 0xe5, 0x00)

        // Same supported set, but every spark-ignition test still incomplete.
        private val notReady = decodeReadinessMonitors(0x77, 0xe5, 0xe5)

        // Secondary air is commonly absent on this engine: C bit3 clear -> N/A even though
        // the neighbouring catalyst monitor is supported and complete.
        private val mixed = decodeReadinessMonitors(0x07, 0x05, 0x00)

        @Test
        fun `Misfire reads Ready when supported and complete`() {
            assertEquals(MonitorState.READY, allReady.misfire)
        }

        @Test
        fun `Fuel system reads Ready when supported and complete`() {
            assertEquals(MonitorState.READY, allReady.fuelSystem)
        }

        @Test
        fun `Catalyst reads Ready when supported and complete`() {
            assertEquals(MonitorState.READY, allReady.catalyst)
        }

        @Test
        fun `Misfire reads Not Ready while its test is incomplete`() {
            assertEquals(MonitorState.NOT_READY, notReady.misfire)
        }

        @Test
        fun `Catalyst reads Not Ready while its test is incomplete`() {
            assertEquals(MonitorState.NOT_READY, notReady.catalyst)
        }

        @Test
        fun `O2 sensor reads Not Ready while its test is incomplete`() {
            assertEquals(MonitorState.NOT_READY, notReady.o2Sensor)
        }

        @Test
        fun `Unsupported monitors report N-A rather than Ready`() {
            // An engine that does not support a monitor must report N/A, never Ready - the
            // whole point of the fix. 0x00 supported means nothing is available to test.
            val unsupported = decodeReadinessMonitors(0x00, 0x00, 0x00)
            assertTrue(unsupported.labelled().all { it.second == MonitorState.NOT_AVAILABLE })
        }

        @Test
        fun `Supported catalyst still reads Ready in a mixed set`() {
            assertEquals(MonitorState.READY, mixed.catalyst)
        }

        @Test
        fun `Supported evap still reads Ready in a mixed set`() {
            assertEquals(MonitorState.READY, mixed.evap)
        }

        @Test
        fun `Unsupported O2 monitor reads N-A in a mixed set`() {
            assertEquals(MonitorState.NOT_AVAILABLE, mixed.o2Sensor)
        }

        @Test
        fun `Unreadable ECU reply falls back to all-N-A, never all-Ready`() {
            assertTrue(UNKNOWN_MONITORS.labelled().all { it.second == MonitorState.NOT_AVAILABLE })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("7. Fuel blend chemistry (E0 / E10 / E15)")
    inner class BlendChemistry {

        private val e0 = fuelBlend(FuelBlendId.E0)
        private val e10 = fuelBlend(FuelBlendId.E10)
        private val e15 = fuelBlend(FuelBlendId.E15)

        @Test
        fun `E0 stoichiometric AFR is the gasoline reference 14_7`() {
            assertTrue(abs(e0.stoichAfr - 14.7) < 0.001, "got ${e0.stoichAfr}")
        }

        @Test
        fun `E0 contains no ethanol by mass`() {
            assertTrue(abs(e0.ethanolByMass) < 1e-9, "got ${e0.ethanolByMass}")
        }

        @Test
        fun `E10 mass fraction exceeds its volume fraction because ethanol is denser`() {
            // E10 is 10% ethanol BY VOLUME; ethanol is denser, so its mass share is higher.
            assertTrue(
                e10.ethanolByMass > 0.10 && e10.ethanolByMass < 0.11,
                "got ${e10.ethanolByMass * 100}% by mass",
            )
        }

        @Test
        fun `E10 stoichiometric AFR lands near 13_8 by mass-correct blending`() {
            assertTrue(e10.stoichAfr > 13.6 && e10.stoichAfr < 13.9, "got ${e10.stoichAfr}")
        }

        @Test
        fun `Adding ethanol lowers the stoichiometric ratio`() {
            assertTrue(e10.stoichAfr < e0.stoichAfr, "${e10.stoichAfr} < ${e0.stoichAfr}")
        }

        @Test
        fun `Adding ethanol raises blend density`() {
            assertTrue(
                e10.densityGramsPerLiter > e0.densityGramsPerLiter,
                "${e10.densityGramsPerLiter} > ${e0.densityGramsPerLiter} g/L",
            )
        }

        @Test
        fun `E15 sits below E10`() {
            assertTrue(e15.stoichAfr < e10.stoichAfr, "${e15.stoichAfr} < ${e10.stoichAfr}")
        }

        @Test
        fun `Mass-correct E10 AFR differs materially from a naive volume average`() {
            // Reciprocal blending is the point: a naive average of the two AFRs would give
            // ~14.1 for E10, which is the figure that made the old pure-gasoline assumption
            // look defensible.
            val naiveAverage = 0.9 * 14.7 + 0.1 * 9.0
            assertTrue(
                abs(e10.stoichAfr - naiveAverage) > 0.2,
                "${e10.stoichAfr} vs $naiveAverage",
            )
        }

        @Test
        fun `Blend density converts between litres and US gallons consistently`() {
            assertTrue(
                abs(e10.densityGramsPerGallon - e10.densityGramsPerLiter * LITERS_PER_US_GALLON) < 0.001,
            )
        }

        @Test
        fun `E10 burns more volume than E0 for identical airflow`() {
            // The blend actually changes computed fuel flow: same air mass, more fuel on E10.
            val m = FuelModelEngine()
            m.setFuelBlend(FuelBlendId.E0)
            val flowE0 = m.calculateFuelFlow(10.0, m.getFuelBlend().stoichAfr, false)
            m.setFuelBlend(FuelBlendId.E10)
            val flowE10 = m.calculateFuelFlow(10.0, m.getFuelBlend().stoichAfr, false)
            assertTrue(
                flowE10.fuelFlowGalPerHour > flowE0.fuelFlowGalPerHour,
                "${flowE10.fuelFlowGalPerHour} > ${flowE0.fuelFlowGalPerHour} gal/hr",
            )
        }

        @Test
        fun `Lambda 1_0 on E10 yields the blend's ratio rather than gasoline's`() {
            val m = FuelModelEngine().apply { setFuelBlend(FuelBlendId.E10) }
            val afr = m.calculateAirFuelRatio(1.0, 0.0, 0.0)
            assertTrue(abs(afr - e10.stoichAfr) < 0.001, "got $afr")
        }

        @Test
        fun `Lifetime MPG is cumulative miles over cumulative gallons`() {
            // The definition, not an average of averages.
            val stats = LifetimeStats(totalMiles = 1000.0, totalFuelGallons = 31.25)
            assertTrue(abs(stats.lifetimeMpg - 32) < 0.001, "got ${stats.lifetimeMpg}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("8. Lifetime record integrity")
    inner class LifetimeIntegrity {

        // Only a real adapter may write to the permanent record.

        @Test
        fun `A connected adapter records to the lifetime figure`() {
            assertTrue(IntegrationRules.shouldRecordLifetime(ConnectionStatus.CONNECTED))
        }

        @Test
        fun `Simulated driving is refused by the lifetime figure`() {
            assertFalse(IntegrationRules.shouldRecordLifetime(ConnectionStatus.SIMULATING))
        }

        @Test
        fun `Disconnected state records nothing`() {
            assertFalse(IntegrationRules.shouldRecordLifetime(ConnectionStatus.DISCONNECTED))
        }

        @Test
        fun `Mid-connection state records nothing`() {
            assertFalse(IntegrationRules.shouldRecordLifetime(ConnectionStatus.CONNECTING))
        }

        @Test
        fun `Error state records nothing`() {
            assertFalse(IntegrationRules.shouldRecordLifetime(ConnectionStatus.ERROR))
        }

        // Normal 80ms ticks integrate; a stalled timer does not.

        @Test
        fun `A normal 80ms tick integrates in full`() {
            assertEquals(0.08, IntegrationRules.resolveIntegrationStep(0.08))
        }

        @Test
        fun `A slow but plausible tick still integrates`() {
            assertEquals(0.95, IntegrationRules.resolveIntegrationStep(0.95))
        }

        @Test
        fun `The boundary step integrates`() {
            assertEquals(
                IntegrationRules.MAX_INTEGRATION_STEP_SEC,
                IntegrationRules.resolveIntegrationStep(IntegrationRules.MAX_INTEGRATION_STEP_SEC),
            )
        }

        @Test
        fun `A 20-minute stall from a locked phone is discarded, not integrated`() {
            assertEquals(0.0, IntegrationRules.resolveIntegrationStep(1200.0))
        }

        @Test
        fun `A 5-second gap already exceeds the trusted window`() {
            assertEquals(0.0, IntegrationRules.resolveIntegrationStep(5.0))
        }

        @Test
        fun `A zero step contributes nothing`() {
            assertEquals(0.0, IntegrationRules.resolveIntegrationStep(0.0))
        }

        @Test
        fun `A negative step from a clock adjustment contributes nothing`() {
            assertEquals(0.0, IntegrationRules.resolveIntegrationStep(-3.0))
        }

        @Test
        fun `A non-finite step contributes nothing`() {
            assertEquals(0.0, IntegrationRules.resolveIntegrationStep(Double.NaN))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("9. PID selection & decoding (measured against a real scan)")
    inner class PidSelection {

        /*
         * The support set below is a real scan of the car this app is built for, taken at a
         * warm idle: 38 PIDs, and crucially none of 0x24, 0x46 or 0x14 - the three the
         * gauges used to ask for unconditionally. Hardcoding it here is the point. A
         * synthetic set would have been written to match whatever the code already did,
         * which is exactly how the original bug survived: nothing anywhere asserted that
         * the PIDs being polled were PIDs this car has.
         */
        private val civicSupportedPids: Set<Int> = setOf(
            0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x13, 0x15,
            0x1c, 0x1f, 0x21, 0x2c, 0x2d, 0x2e, 0x2f, 0x30, 0x31, 0x32, 0x33, 0x34, 0x3c, 0x41, 0x42,
            0x43, 0x44, 0x45, 0x47, 0x49, 0x4a, 0x4c, 0x51,
        )

        /** A car that has the first choice must still get it. */
        private val richCar: Set<Int> = setOf(0x14, 0x24, 0x46, 0x34, 0x0f)

        @Test
        fun `The scanned car reports none of PIDs 24, 46 or 14`() {
            // The premise the rest of this section rests on.
            assertTrue(
                !civicSupportedPids.contains(0x24) &&
                    !civicSupportedPids.contains(0x46) &&
                    !civicSupportedPids.contains(0x14),
            )
        }

        @Test
        fun `Lambda resolves to PID 34 on a car with no PID 24`() {
            assertEquals(0x34, choosePid(LAMBDA_PID_CANDIDATES, civicSupportedPids))
        }

        @Test
        fun `The pre-catalyst sensor resolves to PID 34 on a car with no narrowband PID 14`() {
            assertEquals(0x34, choosePid(PRE_CAT_PID_CANDIDATES, civicSupportedPids))
        }

        @Test
        fun `Outside air falls back to intake air PID 0F on a car with no PID 46`() {
            assertEquals(0x0f, choosePid(OUTSIDE_AIR_PID_CANDIDATES, civicSupportedPids))
        }

        @Test
        fun `PID 24 is preferred for lambda where it exists`() {
            assertEquals(0x24, choosePid(LAMBDA_PID_CANDIDATES, richCar))
        }

        @Test
        fun `The narrowband PID 14 is preferred for the pre-catalyst trace where it exists`() {
            assertEquals(0x14, choosePid(PRE_CAT_PID_CANDIDATES, richCar))
        }

        @Test
        fun `Real outside air PID 46 is preferred over intake air where it exists`() {
            assertEquals(0x46, choosePid(OUTSIDE_AIR_PID_CANDIDATES, richCar))
        }

        @Test
        fun `A car with no wideband PID at all resolves to null rather than a guess`() {
            assertNull(choosePid(LAMBDA_PID_CANDIDATES, setOf(0x0c)))
        }

        @Test
        fun `Unreadable support bitmaps fall back to the first candidate rather than giving up`() {
            // An empty set means the bitmaps could not be read - which is not the same as
            // "the car has nothing", so it falls back to asking and letting the reply decide.
            assertEquals(0x24, choosePid(LAMBDA_PID_CANDIDATES, emptySet()))
        }

        // The discovery screen's tick and the poll loop must agree, because they share this.

        @Test
        fun `PID 34 is marked as driving a gauge on this car`() {
            assertTrue(pidsInUseFor(civicSupportedPids).contains(0x34))
        }

        @Test
        fun `PID 0F is marked as driving a gauge on this car`() {
            assertTrue(pidsInUseFor(civicSupportedPids).contains(0x0f))
        }

        @Test
        fun `PIDs this car does not have are never marked as driving a gauge`() {
            val inUse = pidsInUseFor(civicSupportedPids)
            assertTrue(!inUse.contains(0x24) && !inUse.contains(0x46) && !inUse.contains(0x14))
        }

        @Test
        fun `Every PID claimed to drive a gauge is one the car actually reports`() {
            assertTrue(pidsInUseFor(civicSupportedPids).all { civicSupportedPids.contains(it) })
        }

        /*
         * The six replies this car gave that had no formula, decoded. These are the literal
         * bytes off the adapter, so each assertion is a round trip from a real reply to a
         * real reading.
         */

        @Test
        fun `PID 2C commanded EGR decodes`() {
            assertEquals("0 %", decodePidValue(0x2c, "00"))
        }

        @Test
        fun `PID 2D EGR error decodes its saturated reading`() {
            assertEquals("99.22 %", decodePidValue(0x2d, "FF"))
        }

        @Test
        fun `PID 32 evap pressure decodes as signed`() {
            // 0xFF96 is -106 quarter-pascals, a slight vacuum. Unsigned it would read
            // +16742 Pa.
            assertEquals("-26.5 Pa", decodePidValue(0x32, "FF96"))
        }

        @Test
        fun `PID 51 fuel type decodes`() {
            assertEquals("Gasoline", decodePidValue(0x51, "01"))
        }

        @Test
        fun `PID 34 decodes to lambda and sensor current`() {
            val wideRange = decodePidValue(0x34, "843D7FF5")
            assertTrue(
                wideRange != null && wideRange.contains("1.033") && wideRange.contains("-0.04"),
                "got $wideRange",
            )
        }

        @Test
        fun `PID 41 summarises this drive cycle's monitors`() {
            // PID 41 is the same readiness bitmap as PID 01, scoped to this drive cycle.
            // Byte A is reserved, so the decoder must read B, C and D - offset by one from
            // the payload start.
            assertEquals("5 monitors, all complete", decodePidValue(0x41, "0005E000"))
        }

        @Test
        fun `PID 41 names the monitor still running rather than claiming completion`() {
            // Same bytes, one monitor forced incomplete, to prove the summary is reading D
            // and not simply reporting "all complete" for anything it is handed.
            val busy = decodePidValue(0x41, "0005E020")
            assertTrue(
                busy != null && busy.contains("1 of 5") && busy.contains("O2 sensor"),
                "got $busy",
            )
        }
    }
}
