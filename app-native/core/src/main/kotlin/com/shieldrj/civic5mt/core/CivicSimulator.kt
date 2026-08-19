package com.shieldrj.civic5mt.core

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

enum class SimulatorScenario { MANUAL, CITY_COMMUTE, SPIRITED_PULL, HIGHWAY_CRUISE }

/**
 * A driving bench for this car.
 *
 * Real drivetrain physics rather than a canned waveform: torque against gearing, aerodynamic
 * drag, rolling resistance, engine braking, and RPM locked to wheel speed through whichever
 * gear is selected. That is what makes it useful for developing a gauge - the numbers move
 * the way the car makes them move, so a readout that looks wrong here looks wrong there.
 *
 * **It mirrors the PID set this car actually has, and that is load-bearing.** A scan settled
 * it: no PID 46 (outside air), no PID 14 (pre-catalyst narrowband), no PID 24 - the front
 * sensor is wide-range on PID 34 instead. The bench used to simulate all three of the PIDs
 * the car does not have, which is exactly why it looked healthy while the real drive was
 * displaying seeded constants. A simulator that exercises a different code path than the car
 * is worse than no simulator, because it certifies the path nobody drives.
 */
class CivicSimulatorEngine {

    var scenario: SimulatorScenario = SimulatorScenario.CITY_COMMUTE

    // Driving controls
    var throttlePos: Double = 0.0 // 0 - 100%
    var brakePos: Double = 0.0 // 0 - 100%
    var clutchPressed: Boolean = false

    /** 1-5, or null for neutral. */
    var manualGear: Int? = 1

    // Physical state
    private var rpm: Double = CivicSpecs.IDLE_RPM.toDouble()
    private var speedKmh: Double = 0.0
    private var coolantTempC: Double = 45.0 // Starts cool, to exercise the cold-start tracker
    private var simulationTimeSec: Double = 0.0

    private var batteryVoltage: Double = 12.6 // Resting, before the sim "starts" the engine
    private var fuelLevelPercent: Double = 68.0
    private var ambientC: Double = 18.0

    // Autopilot script state
    private var autoPhase: Int = 0
    private var phaseTimerSec: Double = 0.0

    /** Advances the simulation by [dt] seconds and returns what the adapter would report. */
    fun tick(dt: Double = 0.05): RawObdData {
        simulationTimeSec += dt
        phaseTimerSec += dt

        if (scenario != SimulatorScenario.MANUAL) {
            runAutopilot()
        }

        // Coolant warms with running time, faster under load.
        if (coolantTempC < CivicSpecs.OPTIMAL_OPERATING_TEMP_C) {
            val warmupRate = 0.08 + (rpm / 3000) * 0.12
            coolantTempC = min(CivicSpecs.OPTIMAL_OPERATING_TEMP_C, coolantTempC + warmupRate * dt)
        }

        val gear = manualGear
        val gearRatio = if (gear != null) CivicSpecs.GEAR_RATIOS.getValue(gear) else 0.0
        val totalRatio = gearRatio * CivicSpecs.FINAL_DRIVE_RATIO

        if (clutchPressed || gear == null) {
            // Clutch in or neutral: the flywheel revs free and the car coasts.
            val targetRpm = if (throttlePos > 2) {
                CivicSpecs.IDLE_RPM + (throttlePos / 100) *
                    (CivicSpecs.REDLINE_RPM - CivicSpecs.IDLE_RPM)
            } else {
                CivicSpecs.IDLE_RPM.toDouble()
            }

            val revSpeed = if (throttlePos > 2) 3500 else 1800 // Flywheel spin-up vs drop rate
            rpm = if (rpm < targetRpm) {
                min(targetRpm, rpm + revSpeed * dt)
            } else {
                max(targetRpm, rpm - revSpeed * dt)
            }

            val dragLoss = (0.0005 * speedKmh.pow(2) + 0.5) * dt
            val brakeLoss = (brakePos / 100) * 35 * dt
            speedKmh = max(0.0, speedKmh - dragLoss - brakeLoss)
        } else {
            // Clutch engaged: RPM is locked to wheel speed through the gearing.
            val engineLoad = min(100.0, max(10.0, throttlePos * 1.05))
            val torqueAvailable = (engineLoad / 100) * 174 // 174 Nm peak on the R18Z1

            val tireRadiusMeters = (CivicSpecs.TIRE_CIRCUMFERENCE_KM * 1000) / (2 * PI)
            val tractiveForceN = (torqueAvailable * totalRatio * 0.92) / tireRadiusMeters

            val speedMs = speedKmh / 3.6
            val aeroDragN = 0.5 * 1.2 * CivicSpecs.DRAG_COEFFICIENT_CD *
                CivicSpecs.FRONTAL_AREA_M2 * speedMs.pow(2)
            val rollingResistN = CivicSpecs.CURB_WEIGHT_KG * 9.81 * 0.015
            val brakeForceN = (brakePos / 100) * 7500

            // Engine braking, which is what makes the DFCO demo work.
            val engineBrakingN = if (throttlePos <= 1.0) (rpm / 1000) * totalRatio * 15 else 0.0

            val netForceN = tractiveForceN - aeroDragN - rollingResistN - brakeForceN - engineBrakingN
            val accelMs2 = netForceN / CivicSpecs.CURB_WEIGHT_KG

            speedKmh = max(0.0, speedMs + accelMs2 * dt) * 3.6

            val wheelRpm = (speedKmh / 60) / CivicSpecs.TIRE_CIRCUMFERENCE_KM
            val connectedRpm = (wheelRpm * totalRatio).roundToLong().toDouble()
            rpm = max(
                CivicSpecs.IDLE_RPM.toDouble(),
                min(CivicSpecs.REV_LIMITER_RPM.toDouble(), connectedRpm),
            )
        }

        // MAF: ~2.2-2.8 g/s at idle, ~115-130 g/s at wide-open throttle.
        val baseAirIdle = 2.4
        val volumetricAir = (rpm / 6000) * 115 * (max(15.0, throttlePos) / 100)
        val calculatedMaf = roundTo(baseAirIdle + volumetricAir, 2)

        val isWot = throttlePos > 85
        val stft = if (isWot) 0.0 else roundTo(sin(simulationTimeSec * 1.5) * 2.2 - 0.5, 1)
        val ltft = 1.2

        val calculatedLoad = roundTo(
            min(100.0, max(12.0, throttlePos * 0.9 + (rpm / 6700) * 15)),
            1,
        )
        val timingAdvance = roundTo(if (throttlePos > 50) 24.5 else 12.0 + (rpm / 500), 1)

        // Cranking sag once running, alternator charges back up; drifts down when stopped.
        val isEngineRunning = rpm > CivicSpecs.IDLE_RPM - 50
        batteryVoltage = if (isEngineRunning) {
            min(14.4, batteryVoltage + 0.5 * dt)
        } else {
            max(12.4, batteryVoltage - 0.1 * dt)
        }

        val fuelBurnRate = 0.00006 * (1 + throttlePos / 100) * (rpm / 3000)
        fuelLevelPercent = max(0.0, fuelLevelPercent - fuelBurnRate * dt)

        /*
         * From here down the bench mirrors what this particular car reports. See the class
         * comment: simulating PIDs the car does not have is what let the bench look healthy
         * while the real drive displayed seeded constants.
         */

        // Intake air, which is what PID 0F reports: engine-bay air, so it climbs above the
        // outside temperature as the car sits and warms rather than drifting with the weather.
        ambientC = 18 + min(33.0, simulationTimeSec * 0.05)

        // The wide-range front sensor. In closed loop it hunts narrowly around lambda 1.00;
        // at wide-open throttle the ECU goes open loop and commands enrichment.
        val o2Sensor1Lambda =
            roundTo(if (isWot) 0.88 else 1.0 + sin(simulationTimeSec * 2.2) * 0.02, 3)
        // Sensor current sits near zero at balance and swings negative as the mixture richens.
        val o2Sensor1CurrentMa = roundTo((1.0 - o2Sensor1Lambda) * -8, 2)

        return RawObdData(
            rpm = rpm.roundToLong().toDouble(),
            speedKmh = roundTo(speedKmh, 1),
            maf = calculatedMaf,
            coolantC = coolantTempC.roundToLong().toDouble(),
            engineLoad = calculatedLoad,
            throttlePos = roundTo(throttlePos, 1),
            stft = stft,
            ltft = ltft,
            timingAdvance = timingAdvance,
            // The wideband is the lambda source on this car, so the two agree by construction
            // rather than by coincidence - exactly as the PID 34 parser sets them.
            lambda = o2Sensor1Lambda,
            batteryVoltage = roundTo(batteryVoltage, 2),
            fuelLevelPercent = roundTo(fuelLevelPercent, 1),
            ambientC = ambientC.roundToLong().toDouble(),
            ambientSource = OutsideAirSource.INTAKE,
            // Null, not a number: this car has no PID 14, and inventing one here is precisely
            // the mistake that made the bench certify a code path the car never takes.
            o2Sensor1Voltage = null,
            o2Sensor1Lambda = o2Sensor1Lambda,
            o2Sensor1CurrentMa = o2Sensor1CurrentMa,
            o2Sensor2Voltage = 0.65,
            engineRuntimeSec = simulationTimeSec.roundToLong().toDouble(),
        )
    }

    /** Autopilot scripts, mimicking natural driving. */
    private fun runAutopilot() {
        when (scenario) {
            SimulatorScenario.CITY_COMMUTE -> cityCommute()
            SimulatorScenario.SPIRITED_PULL -> spiritedPull()
            SimulatorScenario.HIGHWAY_CRUISE -> {
                // 5th gear at a steady 65-70 mph, with slight terrain variation.
                manualGear = 5
                brakePos = 0.0
                throttlePos = 20 + sin(simulationTimeSec * 0.3) * 3
            }
            SimulatorScenario.MANUAL -> Unit
        }
    }

    /**
     * Idle at a light, pull through the gears, then coast on a closed throttle.
     *
     * The throttle figures here are higher than the ones carried over from the TypeScript,
     * and it is a fix rather than a preference. Each upshift is meant to happen at its eco
     * shift point - that is what `rpm >= ECO_SHIFT_POINTS[n]` is for, with the elapsed-time
     * check as a safety net. At 25-30% throttle the car accelerated so gently that it never
     * reached a shift point and the safety net became the normal path, so the commute topped
     * out at 34.6 km/h. Phase 5 exists to demonstrate deceleration fuel cut-off, and DFCO
     * needs 1200 rpm, which in 4th gear is 35.2 km/h - so the one phase the script is built
     * around had never once fired, in either implementation.
     *
     * At these figures the shifts land on their shift points and the commute reaches about
     * 45 km/h, which is both what the "city speed" comment always claimed and enough for the
     * coast to actually cut fuel.
     */
    private fun cityCommute() {
        when (autoPhase) {
            0 -> { // Stopped at a red light - exercises the idle fuel counter
                manualGear = null
                throttlePos = 0.0
                brakePos = 30.0
                clutchPressed = false
                if (phaseTimerSec > 6.0) advancePhase(1)
            }
            1 -> {
                manualGear = 1
                brakePos = 0.0
                clutchPressed = false
                throttlePos = 45.0
                if (rpm >= CivicSpecs.ECO_SHIFT_POINTS.getValue(1) || phaseTimerSec > 5.0) advancePhase(2)
            }
            2 -> {
                manualGear = 2
                throttlePos = 45.0
                if (rpm >= CivicSpecs.ECO_SHIFT_POINTS.getValue(2) || phaseTimerSec > 6.0) advancePhase(3)
            }
            3 -> {
                manualGear = 3
                throttlePos = 45.0
                if (rpm >= CivicSpecs.ECO_SHIFT_POINTS.getValue(3) || phaseTimerSec > 7.0) advancePhase(4)
            }
            4 -> { // Cruise in 4th at city speed
                manualGear = 4
                throttlePos = 18.0
                if (phaseTimerSec > 8.0) advancePhase(5)
            }
            5 -> { // Closed throttle still in gear: fuel cuts, MPG hits the 99.9 cap
                manualGear = 4
                throttlePos = 0.0
                brakePos = 15.0
                if (speedKmh < 15 || phaseTimerSec > 5.0) advancePhase(0)
            }
        }
    }

    /** Full power through the first three gears, then a coast down. */
    private fun spiritedPull() {
        when (autoPhase) {
            0 -> {
                manualGear = 1
                brakePos = 0.0
                throttlePos = 95.0
                if (rpm >= 6400 || phaseTimerSec > 3.5) advancePhase(1)
            }
            1 -> {
                manualGear = 2
                throttlePos = 100.0
                if (rpm >= 6450 || phaseTimerSec > 4.0) advancePhase(2)
            }
            2 -> {
                manualGear = 3
                throttlePos = 100.0
                if (rpm >= 6200 || phaseTimerSec > 5.0) advancePhase(3)
            }
            3 -> {
                manualGear = 4
                throttlePos = 0.0
                brakePos = 20.0
                if (speedKmh < 30 || phaseTimerSec > 6.0) advancePhase(0)
            }
        }
    }

    private fun advancePhase(next: Int) {
        autoPhase = next
        phaseTimerSec = 0.0
    }
}
