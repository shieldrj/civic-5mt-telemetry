package com.shieldrj.civic5mt.core

import kotlin.math.abs
import kotlin.math.max

/**
 * Which gear the car is in, as far as this app can tell.
 *
 * A sealed type rather than the TypeScript's `1 | 2 | 3 | 4 | 5 | 'N' | 'CLUTCH'`, which
 * mixed numbers and strings in one value and forced a `typeof` check at every use. The
 * distinction that matters - "in a numbered gear" versus "not" - is now something the
 * compiler enforces instead of something each caller remembers to test for.
 */
sealed interface GearSelection {
    data class Gear(val number: Int) : GearSelection {
        override fun toString(): String = number.toString()
    }

    /** Rolling or stopped with the engine disconnected from the wheels. */
    data object Neutral : GearSelection {
        override fun toString(): String = "N"
    }

    /** Clutch in: shifting, rev-matching, or revving while stationary. */
    data object Clutch : GearSelection {
        override fun toString(): String = "CLUTCH"
    }
}

enum class ShiftMode { ECO, POWER }

data class GearAnalysisResult(
    val currentGear: GearSelection,
    val calculatedRatio: Double,
    val expectedRatio: Double,
    val ratioToleranceDelta: Double,
    val isClutchSlipping: Boolean,
    val optimalShiftRpm: Int,
    val shouldShiftUp: Boolean,
    /** 0 to 5 (0: normal, 1-3: approaching, 4: optimal shift, 5: flashing redline). */
    val shiftLightStage: Int,
)

class GearCalculatorEngine(private val clock: MillisClock = SystemMillisClock) {

    private var previousRpm: Double = 0.0
    private var previousSpeedKmh: Double = 0.0
    private var previousTimestamp: Long = clock.nowMillis()
    private var slipConfirmationCounter: Int = 0

    /**
     * Expected overall transmission gear ratio (engine RPM / wheel RPM).
     * Wheel RPM = (speed km/h / 60) / tyre circumference km.
     * Total ratio = gear ratio * final drive ratio.
     */
    private val targetOverallRatios: Map<Int, Double> =
        CivicSpecs.GEAR_RATIOS.mapValues { (_, ratio) -> ratio * CivicSpecs.FINAL_DRIVE_RATIO }

    /**
     * Evaluates the active gear based on real-time RPM and vehicle speed.
     */
    fun analyzeGear(
        rpm: Double,
        speedKmh: Double,
        throttlePercent: Double,
        shiftMode: ShiftMode = ShiftMode.ECO,
    ): GearAnalysisResult {
        val now = clock.nowMillis()
        val dt = max(0.05, (now - previousTimestamp) / 1000.0)

        // Stationary or barely moving (< 3 km/h).
        if (speedKmh < 3.0) {
            previousRpm = rpm
            previousSpeedKmh = speedKmh
            previousTimestamp = now
            return GearAnalysisResult(
                currentGear = if (rpm > 1100) GearSelection.Clutch else GearSelection.Neutral,
                calculatedRatio = 0.0,
                expectedRatio = 0.0,
                ratioToleranceDelta = 0.0,
                isClutchSlipping = false,
                optimalShiftRpm = CivicSpecs.ECO_SHIFT_POINTS.getValue(1),
                shouldShiftUp = false,
                shiftLightStage = 0,
            )
        }

        // Wheel RPM from speed and the Civic's tyre circumference.
        val wheelRpm = (speedKmh / 60.0) / CivicSpecs.TIRE_CIRCUMFERENCE_KM
        val currentOverallRatio = if (wheelRpm > 0) rpm / wheelRpm else 0.0

        // Match against the 5-speed ratios within an 8% tolerance window.
        var detectedGearNumber: Int? = null
        var bestDelta = 999.0
        var expectedRatio = 0.0

        for (g in 1..5) {
            val target = targetOverallRatios.getValue(g)
            val deltaPercent = abs(currentOverallRatio - target) / target

            if (deltaPercent <= 0.08 && deltaPercent < bestDelta) {
                bestDelta = deltaPercent
                detectedGearNumber = g
                expectedRatio = target
            }
        }

        // If the ratio matched no gear: idling while rolling is neutral, anything else is
        // the clutch being in - shifting, rev-matching, or a disengaged drivetrain.
        val detectedGear: GearSelection = when {
            detectedGearNumber != null -> GearSelection.Gear(detectedGearNumber)
            rpm <= 1000 && speedKmh > 10 -> GearSelection.Neutral
            else -> GearSelection.Clutch
        }

        // Clutch slip: in gear, throttle open, RPM flaring, but the car is not accelerating
        // with it. Confirmed over three consecutive ticks so one noisy sample cannot raise it.
        var isClutchSlipping = false
        if (detectedGear is GearSelection.Gear && throttlePercent > 35 && rpm > 2500) {
            val rpmRate = (rpm - previousRpm) / dt
            val speedRate = (speedKmh - previousSpeedKmh) / dt

            if (rpmRate > 1200 && speedRate < 1.0) {
                slipConfirmationCounter++
                if (slipConfirmationCounter >= 3) {
                    isClutchSlipping = true
                }
            } else {
                slipConfirmationCounter = max(0, slipConfirmationCounter - 1)
            }
        } else {
            slipConfirmationCounter = 0
        }

        // Shift light and shift point.
        var optimalShiftRpm = 6500
        var shouldShiftUp = false
        var shiftLightStage = 0

        if (detectedGear is GearSelection.Gear) {
            val gear = detectedGear.number
            if (shiftMode == ShiftMode.ECO) {
                optimalShiftRpm = CivicSpecs.ECO_SHIFT_POINTS[gear] ?: 2500

                if (gear < 5 && rpm >= optimalShiftRpm) {
                    shouldShiftUp = true
                }

                shiftLightStage = when {
                    rpm < 1800 -> 0
                    rpm < 2000 -> 1
                    rpm < 2150 -> 2
                    rpm < optimalShiftRpm + 100 -> 3
                    rpm < 3000 -> 4 // Shift now
                    else -> 5 // Past the eco threshold
                }
            } else {
                optimalShiftRpm = CivicSpecs.POWER_SHIFT_POINT_RPM
                shouldShiftUp = gear < 5 && rpm >= 6300

                shiftLightStage = when {
                    rpm < 3500 -> 0
                    rpm < 4500 -> 1 // Green 1
                    rpm < 5200 -> 2 // Green 2 / VTEC window
                    rpm < 5900 -> 3 // Yellow
                    rpm < 6400 -> 4 // Orange / peak HP
                    else -> 5 // Flashing red, redline
                }
            }
        }

        previousRpm = rpm
        previousSpeedKmh = speedKmh
        previousTimestamp = now

        return GearAnalysisResult(
            currentGear = detectedGear,
            calculatedRatio = currentOverallRatio,
            expectedRatio = expectedRatio,
            ratioToleranceDelta = if (bestDelta != 999.0) bestDelta else 0.0,
            isClutchSlipping = isClutchSlipping,
            optimalShiftRpm = optimalShiftRpm,
            shouldShiftUp = shouldShiftUp,
            shiftLightStage = shiftLightStage,
        )
    }
}
