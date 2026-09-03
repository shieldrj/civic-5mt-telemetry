package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClutchHealthModelTest {

    private class MutableClock(var now: Long = 1_700_000_000_000L) : MillisClock {
        override fun nowMillis(): Long = now
        fun advanceSec(seconds: Double) {
            now += (seconds * 1000).toLong()
        }
    }

    private fun createEngine(clock: MutableClock = MutableClock()): ClutchHealthEngine =
        ClutchHealthEngine(store = InMemoryClutchProfileStore(), clock = clock)

    /** Locked engine speed for a gear at a road speed, straight from the geometry. */
    private fun lockedRpm(gear: Int, speedKmh: Double): Double =
        (speedKmh / 60.0) / CivicSpecs.TIRE_CIRCUMFERENCE_KM *
            CivicSpecs.GEAR_RATIOS.getValue(gear) * CivicSpecs.FINAL_DRIVE_RATIO

    @Nested
    @DisplayName("Torque and Kinematics Estimation")
    inner class Kinematics {

        @Test
        fun `Brake torque is what leaves the crankshaft, not what the combustion made`() {
            val engine = createEngine()
            // An idling engine is producing nothing at the flywheel: everything it makes is
            // spent turning itself over. Before pumping and rubbing losses were subtracted
            // this read ~36 Nm, which the clutch model then spent on imaginary slip.
            val idleTorque = engine.estimateBrakeTorqueNm(750.0, 2.4, 1.0, 15.0)
            assertTrue(idleTorque in 0.0..25.0, "Idle torque was $idleTorque")

            val peakTorque = engine.estimateBrakeTorqueNm(4300.0, 75.0, 0.90, 24.0)
            assertTrue(peakTorque in 145.0..CivicSpecs.ENGINE_PEAK_TORQUE_NM, "Peak torque was $peakTorque")
        }

        @Test
        fun `Part-load timing advance is not treated as a fault`() {
            val engine = createEngine()
            // A cruising R18Z1 runs 35-45 degrees of advance because that is efficient. The
            // old symmetric curve about 24 degrees docked it ~19% for doing so.
            val cruising = engine.estimateBrakeTorqueNm(2200.0, 12.0, 1.0, 38.0)
            val reference = engine.estimateBrakeTorqueNm(2200.0, 12.0, 1.0, 24.0)
            assertEquals(reference, cruising, absoluteTolerance = 0.01)
        }

        @Test
        fun `Locked cruise in 5th gear shows zero slip and locked classification`() {
            val engine = createEngine()
            val (status, _) = engine.recordTelemetryStep(
                rpm = lockedRpm(5, 115.0),
                speedKmh = 115.0,
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
            assertTrue(status.slipRpm in -5.0..5.0)
        }
    }

    @Nested
    @DisplayName("A stationary car is not wearing its clutch")
    inner class Stationary {

        /** Five minutes of cold fast idle on the driveway, in neutral, foot nowhere near it. */
        private fun warmUp(engine: ClutchHealthEngine, clock: MutableClock, rpm: Double, seconds: Int) {
            repeat(seconds * 25 / 2) {
                clock.advanceSec(0.08)
                engine.recordTelemetryStep(
                    rpm = rpm,
                    speedKmh = 0.0,
                    throttlePercent = CivicSpecs.CLOSED_THROTTLE_BASELINE_PERCENT,
                    mafGramsPerSec = 5.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 12.0,
                    gearSelection = GearSelection.Neutral,
                    ambientTempC = 20.0,
                    speedMph = 0.0,
                    dtSec = 0.08,
                )
            }
        }

        @Test
        fun `Cold fast idle on the driveway costs the clutch nothing`() {
            val clock = MutableClock()
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            // 1300 RPM is an ordinary cold idle on this engine, and comfortably above the
            // idle+100 that the model used to read as launch slip. Fifteen minutes of it
            // burned 40% of the modelled clutch life and left the disc permanently glazed.
            warmUp(engine, clock, rpm = 1300.0, seconds = 900)

            val p = engine.getProfile()
            assertEquals(0.0, p.accumulatedFrictionEnergyJoules)
            assertEquals(100.0, p.clutchHealthPercent)
            assertEquals(0.0, p.degradationBreakdown.thermalGlazePenaltyPercent)
            assertTrue(p.maxObservedTempC <= 25.0, "Disc heated to ${p.maxObservedTempC}C while parked")
        }

        @Test
        fun `Revving in neutral at a red light costs the clutch nothing`() {
            val clock = MutableClock()
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            repeat(50) {
                clock.advanceSec(0.08)
                engine.recordTelemetryStep(
                    rpm = 3500.0,
                    speedKmh = 0.0,
                    throttlePercent = 45.0,
                    mafGramsPerSec = 25.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 20.0,
                    gearSelection = GearSelection.Neutral,
                    ambientTempC = 20.0,
                    speedMph = 0.0,
                    dtSec = 0.08,
                )
            }

            assertEquals(0.0, engine.getProfile().accumulatedFrictionEnergyJoules)
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
            assertTrue(status.slipPowerWatts > 0.0, "A launch does dissipate energy")
        }

        /**
         * The case the model existed for and could not see.
         *
         * Driven through the real [GearCalculatorEngine] rather than by handing the clutch
         * engine a gear directly, because the gap between them was the bug: at this much
         * slip the ratio no longer matches 5th, so the calculator stops reporting a gear at
         * all and the wear went unrecorded.
         */
        @Test
        fun `Worn clutch slipping in 5th is caught even though the ratio no longer matches`() {
            val clock = MutableClock()
            val gears = GearCalculatorEngine(clock)
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            fun tick(rpm: Double, throttle: Double): ClutchLiveStatus {
                clock.advanceSec(0.08)
                val gear = gears.analyzeGear(rpm, 80.0, throttle)
                return engine.recordTelemetryStep(
                    rpm = rpm,
                    speedKmh = 80.0,
                    throttlePercent = throttle,
                    mafGramsPerSec = 55.0,
                    lambda = 0.92,
                    timingAdvanceDeg = 22.0,
                    gearSelection = gear.currentGear,
                    ambientTempC = 20.0,
                    speedMph = 49.7,
                    dtSec = 0.08,
                ).first
            }

            // Settled in 5th at 80 km/h with the driver's foot in it.
            repeat(30) { tick(lockedRpm(5, 80.0), 40.0) }
            assertEquals(5, tick(lockedRpm(5, 80.0), 40.0).attributedGear)

            // Now it lets go: revs climb, road speed does not. At this much slip the ratio
            // has drifted all the way into 4th's match window, so the calculator does not
            // report an open driveline - it reports a perfectly healthy 4th gear.
            assertEquals(GearSelection.Gear(4), gears.analyzeGear(2600.0, 80.0, 90.0).currentGear)

            var last: ClutchLiveStatus? = null
            repeat(20) { last = tick(2600.0, 90.0) }

            assertNotNull(last)
            assertEquals(5, last.attributedGear, "Slip must stay attributed to the gear it is happening in")
            assertEquals(SlipClassification.MACRO_SLIP, last.classification)
            assertTrue(last.isMacroSlip)
            assertTrue(last.slipPercent > 20.0, "Slip was ${last.slipPercent}%")
            assertTrue(
                engine.getProfile().accumulatedFrictionEnergyJoules > 0.0,
                "Severe slip has to cost the clutch something",
            )
        }

        @Test
        fun `An upshift is not mistaken for slip`() {
            val clock = MutableClock()
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            fun tick(rpm: Double, throttle: Double, gear: GearSelection): ClutchLiveStatus {
                clock.advanceSec(0.08)
                return engine.recordTelemetryStep(
                    rpm = rpm,
                    speedKmh = 80.0,
                    throttlePercent = throttle,
                    mafGramsPerSec = 40.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 24.0,
                    gearSelection = gear,
                    ambientTempC = 20.0,
                    speedMph = 49.7,
                    dtSec = 0.08,
                ).first
            }

            repeat(30) { tick(lockedRpm(4, 80.0), 50.0, GearSelection.Gear(4)) }

            // Foot off to change gear. That lift is the signal that the gear may no longer
            // be 4th, so nothing after it is attributed until a ratio matches again.
            tick(2400.0, 3.0, GearSelection.Clutch)
            val midShift = tick(2400.0, 60.0, GearSelection.Clutch)

            assertNull(midShift.attributedGear)
            assertFalse(midShift.isMacroSlip, "A gear change is not a slipping clutch")
        }

        @Test
        fun `Incidents record the gear the slip started in`() {
            val clock = MutableClock()
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            fun tick(rpm: Double, throttle: Double, gear: GearSelection) {
                clock.advanceSec(0.08)
                engine.recordTelemetryStep(
                    rpm = rpm,
                    speedKmh = 80.0,
                    throttlePercent = throttle,
                    mafGramsPerSec = 55.0,
                    lambda = 0.92,
                    timingAdvanceDeg = 22.0,
                    gearSelection = gear,
                    ambientTempC = 20.0,
                    speedMph = 49.7,
                    dtSec = 0.08,
                )
            }

            repeat(20) { tick(lockedRpm(5, 80.0), 40.0, GearSelection.Gear(5)) }
            repeat(20) { tick(2600.0, 90.0, GearSelection.Clutch) }
            // Driver gives up and drops to 4th, which is what ends the slip.
            repeat(5) { tick(lockedRpm(4, 80.0), 40.0, GearSelection.Gear(4)) }

            val incident = engine.getProfile().recentIncidents.firstOrNull()
            assertNotNull(incident, "A sustained macro-slip should be logged")
            assertEquals(5, incident.gear, "Logged the gear the driver escaped into, not the one that slipped")
        }

        @Test
        fun `Coasting down from 5th to low speed and accelerating in 2nd does not trigger slip`() {
            val clock = MutableClock()
            val gears = GearCalculatorEngine(clock)
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            fun tick(rpm: Double, speedKmh: Double, throttle: Double): ClutchLiveStatus {
                clock.advanceSec(0.08)
                val gear = gears.analyzeGear(rpm, speedKmh, throttle)
                return engine.recordTelemetryStep(
                    rpm = rpm,
                    speedKmh = speedKmh,
                    throttlePercent = throttle,
                    mafGramsPerSec = if (throttle > 20.0) 35.0 else 4.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 20.0,
                    gearSelection = gear.currentGear,
                    ambientTempC = 20.0,
                    speedMph = speedKmh * 0.621371,
                    dtSec = 0.08,
                ).first
            }

            // 1. Cruising in 5th gear at 95 km/h (~60 mph)
            repeat(20) { tick(lockedRpm(5, 95.0), 95.0, 25.0) }

            // 2. Slowing down to 30 km/h (~18 mph) with clutch in, idling at 720 RPM
            repeat(20) { tick(720.0, 30.0, 14.9) }

            // 3. Shift into 2nd gear and accelerate to 45 km/h under 37% throttle
            var lastStatus: ClutchLiveStatus? = null
            repeat(15) {
                lastStatus = tick(lockedRpm(2, 40.0), 40.0, 37.0)
            }

            assertNotNull(lastStatus)
            assertEquals(2, lastStatus.attributedGear)
            assertEquals(SlipClassification.LOCKED, lastStatus.classification)
            assertFalse(lastStatus.isMacroSlip)
            assertFalse(lastStatus.isSlipping)
            assertTrue(engine.getProfile().recentIncidents.isEmpty(), "No slip incidents should be logged")
        }

        @Test
        fun `Rev-matched downshift from 5th to 2nd without throttle lift does not trigger slip`() {
            val clock = MutableClock()
            val gears = GearCalculatorEngine(clock)
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            fun tick(rpm: Double, speedKmh: Double, throttle: Double): ClutchLiveStatus {
                clock.advanceSec(0.08)
                val gear = gears.analyzeGear(rpm, speedKmh, throttle)
                return engine.recordTelemetryStep(
                    rpm = rpm,
                    speedKmh = speedKmh,
                    throttlePercent = throttle,
                    mafGramsPerSec = if (throttle > 20.0) 45.0 else 10.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 22.0,
                    gearSelection = gear.currentGear,
                    ambientTempC = 20.0,
                    speedMph = speedKmh * 0.621371,
                    dtSec = 0.08,
                ).first
            }

            // 1. Cruising in 5th gear at 90 km/h under 30% throttle
            repeat(20) { tick(lockedRpm(5, 90.0), 90.0, 30.0) }

            // 2. Slowing down to 62 km/h and rev-matching into 2nd gear without throttle dropping below 25%
            var lastStatus: ClutchLiveStatus? = null
            repeat(30) {
                lastStatus = tick(lockedRpm(2, 62.0), 62.0, 45.0)
            }

            assertNotNull(lastStatus)
            assertEquals(2, lastStatus.attributedGear, "Multi-gear jump to 2nd must be recognized immediately")
            assertEquals(SlipClassification.LOCKED, lastStatus.classification)
            assertFalse(lastStatus.isMacroSlip)
            assertFalse(lastStatus.isSlipping)
            assertTrue(engine.getProfile().recentIncidents.isEmpty(), "No slip incidents should be logged for 2nd gear downshift")
        }

        @Test
        fun `Downshift from 5th to 4th with clutch disengagement does not trigger slip`() {
            val clock = MutableClock()
            val gears = GearCalculatorEngine(clock)
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            fun tick(rpm: Double, speedKmh: Double, throttle: Double): ClutchLiveStatus {
                clock.advanceSec(0.08)
                val gear = gears.analyzeGear(rpm, speedKmh, throttle)
                return engine.recordTelemetryStep(
                    rpm = rpm,
                    speedKmh = speedKmh,
                    throttlePercent = throttle,
                    mafGramsPerSec = if (throttle > 20.0) 40.0 else 12.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 24.0,
                    gearSelection = gear.currentGear,
                    ambientTempC = 20.0,
                    speedMph = speedKmh * 0.621371,
                    dtSec = 0.08,
                ).first
            }

            // 1. Cruising in 5th gear at 85 km/h
            repeat(20) { tick(lockedRpm(5, 85.0), 85.0, 25.0) }

            // 2. Clutch pedal depressed (open driveline, revs drop or fluctuate around 1800, throttle 22%)
            repeat(4) { tick(1800.0, 80.0, 22.0) }

            // 3. Shift into 4th gear and accelerate under 55% throttle at 68 km/h
            var lastStatus: ClutchLiveStatus? = null
            repeat(25) {
                lastStatus = tick(lockedRpm(4, 68.0), 68.0, 55.0)
            }

            assertNotNull(lastStatus)
            assertEquals(4, lastStatus.attributedGear, "4th gear must be accepted after driveline disengagement")
            assertEquals(SlipClassification.LOCKED, lastStatus.classification)
            assertFalse(lastStatus.isMacroSlip)
            assertFalse(lastStatus.isSlipping)
            assertTrue(engine.getProfile().recentIncidents.isEmpty(), "No slip incidents should be logged for 4th gear downshift")
        }
    }

    @Nested
    @DisplayName("Clutch engagements and gear shifts are counted accurately")
    inner class ShiftCounting {

        @Test
        fun `Normal upshift 1st to 2nd through clutch disengagement increments count by 1`() {
            val clock = MutableClock()
            val gears = GearCalculatorEngine(clock)
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            fun tick(rpm: Double, speedKmh: Double, throttle: Double) {
                clock.advanceSec(0.08)
                val gear = gears.analyzeGear(rpm, speedKmh, throttle)
                engine.recordTelemetryStep(
                    rpm = rpm,
                    speedKmh = speedKmh,
                    throttlePercent = throttle,
                    mafGramsPerSec = 20.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 20.0,
                    gearSelection = gear.currentGear,
                    ambientTempC = 20.0,
                    speedMph = speedKmh * 0.621371,
                    dtSec = 0.08,
                )
            }

            // 1. Accelerating in 1st gear (counts 1 launch engagement)
            repeat(10) { tick(lockedRpm(1, 15.0), 15.0, 30.0) }
            assertEquals(1, engine.getProfile().totalEngagementsCount, "Launch into 1st counts 1 engagement")

            // 2. Driver pushes clutch in to shift (open driveline for 4 ticks, ~320ms)
            repeat(4) { tick(2500.0, 18.0, 10.0) }
            assertEquals(1, engine.getProfile().totalEngagementsCount, "Clutch in does not increment engagement count")

            // 3. Driver re-engages clutch in 2nd gear
            repeat(10) { tick(lockedRpm(2, 22.0), 22.0, 30.0) }
            assertEquals(2, engine.getProfile().totalEngagementsCount, "Upshift to 2nd must increment count to 2")
        }

        @Test
        fun `Launch from standstill into 1st gear increments count by 1`() {
            val clock = MutableClock()
            val gears = GearCalculatorEngine(clock)
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            fun tick(rpm: Double, speedKmh: Double, throttle: Double) {
                clock.advanceSec(0.08)
                val gear = gears.analyzeGear(rpm, speedKmh, throttle)
                engine.recordTelemetryStep(
                    rpm = rpm,
                    speedKmh = speedKmh,
                    throttlePercent = throttle,
                    mafGramsPerSec = 10.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 15.0,
                    gearSelection = gear.currentGear,
                    ambientTempC = 20.0,
                    speedMph = speedKmh * 0.621371,
                    dtSec = 0.08,
                )
            }

            // 1. Standing still at traffic light (0 km/h)
            repeat(20) { tick(750.0, 0.0, 0.0) }
            assertEquals(0, engine.getProfile().totalEngagementsCount, "Stationary idle counts no engagements")

            // 2. Pull away in 1st gear
            repeat(10) { tick(lockedRpm(1, 12.0), 12.0, 25.0) }
            assertEquals(1, engine.getProfile().totalEngagementsCount, "Pulling away in 1st counts as 1 engagement")
        }

        @Test
        fun `Multi-gear downshift 5th to 2nd increments shift count by 1`() {
            val clock = MutableClock()
            val gears = GearCalculatorEngine(clock)
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            fun tick(rpm: Double, speedKmh: Double, throttle: Double) {
                clock.advanceSec(0.08)
                val gear = gears.analyzeGear(rpm, speedKmh, throttle)
                engine.recordTelemetryStep(
                    rpm = rpm,
                    speedKmh = speedKmh,
                    throttlePercent = throttle,
                    mafGramsPerSec = 35.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 24.0,
                    gearSelection = gear.currentGear,
                    ambientTempC = 20.0,
                    speedMph = speedKmh * 0.621371,
                    dtSec = 0.08,
                )
            }

            // 1. Cruising in 5th gear at 90 km/h
            repeat(20) { tick(lockedRpm(5, 90.0), 90.0, 30.0) }
            assertEquals(1, engine.getProfile().totalEngagementsCount, "Initial gear engagement counted")

            // 2. Clutch depressed, slowing down to 50 km/h (idling at 900 RPM)
            repeat(6) { tick(900.0, 60.0, 5.0) }
            assertEquals(1, engine.getProfile().totalEngagementsCount)

            // 3. Shift into 2nd gear at 50 km/h
            repeat(15) { tick(lockedRpm(2, 50.0), 50.0, 40.0) }
            assertEquals(2, engine.getProfile().totalEngagementsCount, "5th to 2nd downshift increments count to 2")
        }

        @Test
        fun `Single sample 80ms noise glitch in 4th gear does not increment shift count`() {
            val clock = MutableClock()
            val gears = GearCalculatorEngine(clock)
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            fun tick(rpm: Double, speedKmh: Double, throttle: Double) {
                clock.advanceSec(0.08)
                val gear = gears.analyzeGear(rpm, speedKmh, throttle)
                engine.recordTelemetryStep(
                    rpm = rpm,
                    speedKmh = speedKmh,
                    throttlePercent = throttle,
                    mafGramsPerSec = 30.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 24.0,
                    gearSelection = gear.currentGear,
                    ambientTempC = 20.0,
                    speedMph = speedKmh * 0.621371,
                    dtSec = 0.08,
                )
            }

            // 1. Cruising in 4th gear at 70 km/h
            repeat(20) { tick(lockedRpm(4, 70.0), 70.0, 35.0) }
            assertEquals(1, engine.getProfile().totalEngagementsCount)

            // 2. 1-sample road bump / noise tick where RPM momentarily spikes or drops (gear = null)
            clock.advanceSec(0.08)
            engine.recordTelemetryStep(
                rpm = 3500.0,
                speedKmh = 70.0,
                throttlePercent = 35.0,
                mafGramsPerSec = 30.0,
                lambda = 1.0,
                timingAdvanceDeg = 24.0,
                gearSelection = GearSelection.Clutch, // 1 sample glitch
                ambientTempC = 20.0,
                speedMph = 70.0 * 0.621371,
                dtSec = 0.08,
            )

            // 3. Resumes 4th gear cruise
            repeat(20) { tick(lockedRpm(4, 70.0), 70.0, 35.0) }
            assertEquals(1, engine.getProfile().totalEngagementsCount, "1-sample glitch must NOT increment shift count")
        }

        @Test
        fun `Same gear clutch-in and re-engagement after 0_5s increments count by 1`() {
            val clock = MutableClock()
            val gears = GearCalculatorEngine(clock)
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            fun tick(rpm: Double, speedKmh: Double, throttle: Double) {
                clock.advanceSec(0.08)
                val gear = gears.analyzeGear(rpm, speedKmh, throttle)
                engine.recordTelemetryStep(
                    rpm = rpm,
                    speedKmh = speedKmh,
                    throttlePercent = throttle,
                    mafGramsPerSec = 25.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 22.0,
                    gearSelection = gear.currentGear,
                    ambientTempC = 20.0,
                    speedMph = speedKmh * 0.621371,
                    dtSec = 0.08,
                )
            }

            // 1. Driving in 3rd gear at 50 km/h
            repeat(20) { tick(lockedRpm(3, 50.0), 50.0, 30.0) }
            assertEquals(1, engine.getProfile().totalEngagementsCount)

            // 2. Driver pushes clutch in to coast for 0.56s (7 ticks at 80ms)
            repeat(7) {
                clock.advanceSec(0.08)
                engine.recordTelemetryStep(
                    rpm = 900.0,
                    speedKmh = 48.0,
                    throttlePercent = 5.0,
                    mafGramsPerSec = 8.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 15.0,
                    gearSelection = GearSelection.Clutch,
                    ambientTempC = 20.0,
                    speedMph = 48.0 * 0.621371,
                    dtSec = 0.08,
                )
            }
            assertEquals(1, engine.getProfile().totalEngagementsCount)

            // 3. Driver re-engages clutch in 3rd gear
            repeat(15) { tick(lockedRpm(3, 47.0), 47.0, 30.0) }
            assertEquals(2, engine.getProfile().totalEngagementsCount, "Re-engaging 3rd after 0.56s must increment count to 2")
        }
    }

    @Nested
    @DisplayName("Tyre geometry is calibrated, not assumed")
    inner class Calibration {

        private fun cruise(engine: ClutchHealthEngine, clock: MutableClock, bias: Double, ticks: Int, throttle: Double):
            ClutchLiveStatus {
            var last: ClutchLiveStatus? = null
            repeat(ticks) {
                clock.advanceSec(0.08)
                last = engine.recordTelemetryStep(
                    rpm = lockedRpm(4, 90.0) * (1 + bias),
                    speedKmh = 90.0,
                    throttlePercent = throttle,
                    mafGramsPerSec = 22.0,
                    lambda = 1.0,
                    timingAdvanceDeg = 30.0,
                    gearSelection = GearSelection.Gear(4),
                    ambientTempC = 20.0,
                    speedMph = 55.9,
                    dtSec = 0.08,
                ).first
            }
            return last!!
        }

        @Test
        fun `A worn set of tyres does not read as a slipping clutch`() {
            val clock = MutableClock()
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            // Rolling circumference 3% under the geometric figure: ordinary loaded, worn
            // tyres. This used to manufacture 92 RPM of slip and report micro-slip.
            val status = cruise(engine, clock, bias = 0.03, ticks = 200, throttle = 45.0)

            assertEquals(SlipClassification.LOCKED, status.classification)
            assertFalse(status.isSlipping)
            assertEquals(0.0, engine.getProfile().accumulatedFrictionEnergyJoules)
        }

        @Test
        fun `Light-throttle cruise teaches the model the real rolling circumference`() {
            val clock = MutableClock()
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            cruise(engine, clock, bias = 0.03, ticks = 4000, throttle = 25.0)

            val learned = engine.getProfile().ratioCalibration
            assertTrue(learned > 1.02, "Calibration only reached $learned")
            assertTrue(learned <= CivicSpecs.CLUTCH_CALIBRATION_MAX)
        }

        @Test
        fun `A slipping clutch cannot teach the model to call itself normal`() {
            val clock = MutableClock()
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            // Heavy throttle is excluded from the reference, so this never becomes calibration.
            cruise(engine, clock, bias = 0.20, ticks = 4000, throttle = 70.0)

            assertEquals(1.0, engine.getProfile().ratioCalibration)
        }
    }

    @Nested
    @DisplayName("Prognostics, Archard Wear and RUL")
    inner class Prognostics {

        private fun engineAt(depletionFraction: Double, clock: MutableClock): ClutchHealthEngine {
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)
            engine.saveProfile(
                engine.getProfile().copy(
                    accumulatedFrictionEnergyJoules =
                        CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES * depletionFraction,
                    currentOdometer = 100_000.0 + 150_000.0 * depletionFraction,
                ),
            )
            engine.recalculateClutchHealth()
            return engine
        }

        @Test
        fun `A fresh install invents nothing about a clutch it has never seen`() {
            val profile = createEngine().getProfile()

            assertFalse(profile.baselineKnown)
            assertEquals(0.0, profile.accumulatedFrictionEnergyJoules)
            assertEquals(0.0, profile.currentOdometer)
            assertEquals(0, profile.totalEngagementsCount)
            assertEquals(0, profile.abnormalSlipCount)
            assertNull(profile.estimatedMilesRemaining, "Miles remaining is not knowable yet")
            assertTrue(profile.recentIncidents.isEmpty())
        }

        @Test
        fun `Resetting marks the disc as watched from new`() {
            val profile = createEngine().resetClutchProfile(115_000.0)

            assertTrue(profile.baselineKnown)
            assertEquals(100.0, profile.clutchHealthPercent)
            assertEquals(ClutchConditionGrade.EXCELLENT, profile.conditionGrade)
            assertEquals(CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM, profile.estimatedTorqueCapacityNm)
            assertNull(profile.estimatedMilesRemaining, "No miles watched yet, so no projection")
        }

        @Test
        fun `Miles remaining stays unstated until there are miles to project from`() {
            val clock = MutableClock()
            val engine = createEngine(clock)
            engine.resetClutchProfile(100_000.0)

            engine.saveProfile(
                engine.getProfile().copy(
                    accumulatedFrictionEnergyJoules = 1_000_000.0,
                    currentOdometer = 100_020.0, // a drive to the shops
                ),
            )
            engine.recalculateClutchHealth()

            assertNull(engine.getProfile().estimatedMilesRemaining)
        }

        @Test
        fun `Health tracks the friction budget down to zero`() {
            val clock = MutableClock()

            // Capacity fades with the facing rather than holding at "as new" until the day
            // it slips, so a spent clutch scores zero instead of bottoming out at 40%.
            assertEquals(100.0, engineAt(0.0, clock).getProfile().clutchHealthPercent)

            val half = engineAt(0.5, clock).getProfile()
            assertTrue(half.clutchHealthPercent in 40.0..55.0, "Half spent scored ${half.clutchHealthPercent}")
            assertEquals(ClutchConditionGrade.MODERATE_WEAR, half.conditionGrade)

            val spent = engineAt(1.0, clock).getProfile()
            assertEquals(0.0, spent.clutchHealthPercent)
            assertEquals(ClutchConditionGrade.CRITICAL, spent.conditionGrade)
            assertEquals(0, spent.estimatedMilesRemaining)
        }

        @Test
        fun `The lifetime budget is a service life, not a season`() {
            // 500 MJ at ~3.6 kJ per mile of mixed driving. The old 42 MJ figure worked out
            // at roughly 12,000 miles, so the screen called a healthy clutch dead in a year.
            val milesOfLife = CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES / 3_600.0
            assertTrue(milesOfLife > 100_000, "Modelled clutch life is only ${milesOfLife.toInt()} miles")
        }

        @Test
        fun `Shifts remaining is drawn from the shift share of the budget`() {
            val profile = createEngine().resetClutchProfile(100_000.0)
            // The whole budget divided by one shift would spend every launch as a shift.
            val whole = CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES / CivicSpecs.CLUTCH_SHIFT_ENERGY_J
            assertTrue(profile.estimatedShiftsRemaining < whole)
            assertTrue(profile.estimatedShiftsRemaining > 100_000)
        }
    }
}
