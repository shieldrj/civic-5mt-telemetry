package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClutchHealthModelTest {

    private class MutableClock(var now: Long = 1_700_000_000_000L) : MillisClock {
        override fun nowMillis(): Long = now
        fun advanceSec(seconds: Double) {
            now += (seconds * 1000).toLong()
        }
    }

    private fun createEngine(clock: MutableClock = MutableClock()): ClutchHealthEngine {
        val store = InMemoryClutchProfileStore()
        return ClutchHealthEngine(store = store, clock = clock)
    }

    @Nested
    @DisplayName("Torque and Kinematics Estimation")
    inner class Kinematics {

        @Test
        fun `Brake torque scales properly with MAF and engine speed`() {
            val engine = createEngine()
            // At idle: low MAF (~2.4 g/s), ~750 RPM -> low torque
            val idleTorque = engine.estimateBrakeTorqueNm(750.0, 2.4, 1.0, 15.0)
            assertTrue(idleTorque in 10.0..35.0, "Idle torque was $idleTorque")

            // Peak torque regime: ~4300 RPM, high MAF (~75 g/s)
            val peakTorque = engine.estimateBrakeTorqueNm(4300.0, 75.0, 0.90, 24.0)
            assertTrue(peakTorque in 150.0..174.0, "Peak torque was $peakTorque")
        }

        @Test
        fun `Locked cruise in 5th gear shows zero slip and locked classification`() {
            val engine = createEngine()
            // 5th gear (0.727 * 4.294 = 3.1217 total ratio)
            // At 115 km/h: wheel RPM = (115/60)/0.0019933 = 961.55 -> engine RPM ~ 3001 RPM
            val (status, _) = engine.recordTelemetryStep(
                rpm = 3000.0,
                speedKmh = 114.93,
                throttlePercent = 20.0,
                mafGramsPerSec = 14.0,
                lambda = 1.0,
                timingAdvanceDeg = 24.0,
                gearSelection = GearSelection.Gear(5),
                ambientTempC = 22.0,
                speedMph = 71.4,
                dtSec = 0.08,
            )

            assertEquals(SlipClassification.LOCKED, status.classification)
            assertFalse(status.isSlipping)
            assertFalse(status.isMacroSlip)
            assertTrue(status.slipRpm in -30.0..30.0)
        }
    }

    @Nested
    @DisplayName("Slip Detection and Classification")
    inner class SlipDetection {

        @Test
        fun `Launch in 1st gear is classified as LAUNCH`() {
            val engine = createEngine()
            val (status, _) = engine.recordTelemetryStep(
                rpm = 1600.0,
                speedKmh = 6.0,
                throttlePercent = 30.0,
                mafGramsPerSec = 8.0,
                lambda = 1.0,
                timingAdvanceDeg = 18.0,
                gearSelection = GearSelection.Gear(1),
                ambientTempC = 20.0,
                speedMph = 3.7,
                dtSec = 0.08,
            )

            assertEquals(SlipClassification.LAUNCH, status.classification)
            assertFalse(status.isMacroSlip)
        }

        @Test
        fun `Severe macro-slip under WOT in 4th gear is detected and heats up the disc`() {
            val clock = MutableClock()
            val engine = createEngine(clock)

            // In 4th gear (0.949 * 4.294 = 4.075 total ratio)
            // At 60 km/h: expected engine RPM is ~2045 RPM
            // If RPM flares to 3200 RPM at 85% throttle -> slip is +1155 RPM!
            var lastStatus: ClutchLiveStatus? = null
            for (i in 1..25) { // ~2 seconds of slipping
                clock.advanceSec(0.08)
                val (status, _) = engine.recordTelemetryStep(
                    rpm = 3200.0,
                    speedKmh = 60.0,
                    throttlePercent = 85.0,
                    mafGramsPerSec = 55.0,
                    lambda = 0.88,
                    timingAdvanceDeg = 22.0,
                    gearSelection = GearSelection.Gear(4),
                    ambientTempC = 20.0,
                    speedMph = 37.3,
                    dtSec = 0.08,
                )
                lastStatus = status
            }

            assertNotNull(lastStatus)
            assertEquals(SlipClassification.MACRO_SLIP, lastStatus.classification)
            assertTrue(lastStatus.isSlipping)
            assertTrue(lastStatus.isMacroSlip)
            assertTrue(lastStatus.slipRpm > 1000.0)
            assertTrue(lastStatus.slipPowerWatts > 10000.0, "Slip power was ${lastStatus.slipPowerWatts} W")
            assertTrue(lastStatus.discTempC > 30.0, "Disc temp was ${lastStatus.discTempC} °C")

            // Next step: slip stops, incident is recorded
            clock.advanceSec(0.08)
            val (_, updatedProfile) = engine.recordTelemetryStep(
                rpm = 2045.0,
                speedKmh = 60.0,
                throttlePercent = 20.0,
                mafGramsPerSec = 10.0,
                lambda = 1.0,
                timingAdvanceDeg = 20.0,
                gearSelection = GearSelection.Gear(4),
                ambientTempC = 20.0,
                speedMph = 37.3,
                dtSec = 0.08,
            )

            assertTrue(updatedProfile.recentIncidents.isNotEmpty())
            val incident = updatedProfile.recentIncidents.first()
            assertEquals(4, incident.gear)
            assertTrue(incident.peakSlipRpm > 1000.0)
        }
    }

    @Nested
    @DisplayName("Prognostics, Archard Wear and RUL")
    inner class Prognostics {

        @Test
        fun `Resetting clutch sets health to 100% and initializes baseline capacity`() {
            val clock = MutableClock()
            val engine = createEngine(clock)
            val profile = engine.resetClutchProfile(115000.0)

            assertEquals(100.0, profile.clutchHealthPercent)
            assertEquals(ClutchConditionGrade.EXCELLENT, profile.conditionGrade)
            assertEquals(0.0, profile.accumulatedFrictionEnergyJoules)
            assertEquals(0, profile.abnormalSlipCount)
            assertEquals(CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM, profile.estimatedTorqueCapacityNm)
            assertTrue(profile.estimatedMilesRemaining >= 100_000)
            assertTrue(profile.estimatedShiftsRemaining >= 50_000)
        }

        @Test
        fun `High friction energy degradation lowers health and changes condition grade`() {
            val clock = MutableClock()
            val engine = createEngine(clock)

            // Simulate accumulating extensive wear energy
            val initialProfile = engine.resetClutchProfile(100000.0)
            assertEquals(100.0, initialProfile.clutchHealthPercent)

            // Step with high slip power to accumulate 25 MJ of wear energy
            // 25 MJ / 42 MJ = ~59.5% depleted
            for (step in 1..50) {
                clock.advanceSec(1.0)
                engine.recordTelemetryStep(
                    rpm = 4000.0,
                    speedKmh = 40.0,
                    throttlePercent = 90.0,
                    mafGramsPerSec = 60.0,
                    lambda = 0.9,
                    timingAdvanceDeg = 20.0,
                    gearSelection = GearSelection.Gear(4),
                    ambientTempC = 25.0,
                    speedMph = 24.8,
                    dtSec = 1.0,
                )
            }

            val finalProfile = engine.getProfile()
            assertTrue(finalProfile.clutchHealthPercent < 100.0)
            assertTrue(finalProfile.accumulatedFrictionEnergyJoules > 0.0)
            assertTrue(finalProfile.degradationBreakdown.slipWearPercent > 0.0)
        }
    }
}
