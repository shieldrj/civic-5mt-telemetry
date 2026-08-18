package com.shieldrj.civic5mt.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the instant-MPG readout is actually showing. Two of these are not economy figures
 * at all, and rendering them as numbers is what made the old readout untrustworthy: at a
 * red light [calculateInstantMpg] returns 0 because the car is not moving, and on a closed
 * throttle it returns the 99.9 cap because the injectors are off. Neither is "your car is
 * getting N mpg", so neither should be drawn as a value.
 */
enum class MpgDisplayState { IDLE, COASTING, DRIVING }

data class MpgDisplayReading(
    val value: Double,
    val state: MpgDisplayState,
)

data class FuelFlow(
    val fuelFlowGramsPerSec: Double,
    val fuelFlowGalPerHour: Double,
    val fuelFlowLitersPerHour: Double,
)

class FuelModelEngine {

    private val recentMpgHistory: ArrayDeque<Double> = ArrayDeque()

    /**
     * 30 seconds of samples at the loop's real rate. Derived rather than written down,
     * because the two drifted apart last time: this was 600 with a comment claiming 20Hz,
     * while the loop runs at 80ms, so the "30 second" average covered 48 seconds.
     */
    private val maxHistorySamples: Int = (30_000.0 / CivicSpecs.TELEMETRY_TICK_MS).roundToInt()

    // ── Instant-MPG display damping ──────────────────────────────────────────────
    // The loop recomputes instant MPG 12.5 times a second, and the underlying figure
    // genuinely swings between single digits under acceleration and the 99.9 cap on
    // overrun. Both facts are real; a numeral that reflects them directly is unreadable.
    //
    // So the needle and the numeral are damped differently, which is what OEM consumption
    // gauges do. The arc follows a 1.5s exponential average - the eye reads a moving shape
    // fine, and you still see it dive within about a second of opening the throttle. The
    // numeral is resampled from that same average twice a second, because a digit changing
    // 12 times a second carries no information a driver can use.
    private var displayMpgAverage = 0.0
    private var displayMpgLatched = 0.0
    private var displayMpgLatchAgeSec = 0.0
    private val displayTimeConstantSec = 1.5
    private val displayLatchIntervalSec = 0.5

    /**
     * The blend in the tank. Every mass-to-volume and air-to-fuel conversion below depends
     * on it: the ECU targets stoichiometry for whatever fuel is actually present, so the
     * lambda it reports is relative to that fuel's ratio, not to pure gasoline's 14.7.
     */
    private var blend: FuelBlendProperties = fuelBlend(DEFAULT_FUEL_BLEND)

    fun setFuelBlend(id: FuelBlendId) {
        blend = fuelBlend(id)
    }

    fun getFuelBlend(): FuelBlendProperties = blend

    /**
     * Calculates actual air:fuel ratio from wideband lambda, or from fuel trims when the car
     * has no wideband PID to read.
     *
     * The lambda argument is nullable and has no default, which is the whole point. It used
     * to default to 1.0, and a car with no wideband PID therefore arrived here with a lambda
     * of exactly 1.0 on every single tick - forever. That is not a neutral value: 1.0 passes
     * the validity range below, so the function took the wideband branch, returned bare
     * stoichiometry, and never reached the trim fallback. The app reported a mixture it had
     * never measured while discarding the fuel trims it actually had.
     *
     * Passing null now means "not measured" and is the only way to reach the fallback, so a
     * missing reading can no longer impersonate a stoichiometric one.
     */
    fun calculateAirFuelRatio(
        equivalenceRatioLambda: Double?,
        shortTermFuelTrimPercent: Double = 0.0,
        longTermFuelTrimPercent: Double = 0.0,
    ): Double {
        // A real wideband reading already reflects post-trim combustion AFR. Applying trims
        // on top of it would double-count them.
        if (equivalenceRatioLambda != null &&
            equivalenceRatioLambda.isFinite() &&
            equivalenceRatioLambda > 0.5 &&
            equivalenceRatioLambda < 2.0
        ) {
            val dynamicAfr = blend.stoichAfr * equivalenceRatioLambda
            return max(6.0, min(22.0, dynamicAfr))
        }
        // Narrowband fallback: positive trim = ECU injecting MORE fuel = lower AFR.
        val totalTrimFactor = 1.0 + (shortTermFuelTrimPercent + longTermFuelTrimPercent) / 100.0
        val dynamicAfr = blend.stoichAfr / (if (totalTrimFactor > 0) totalTrimFactor else 1.0)
        return max(6.0, min(22.0, dynamicAfr))
    }

    /**
     * Computes Deceleration Fuel Cut-Off (DFCO) status.
     *
     * In a 5-speed manual Civic, with the throttle closed and engine speed above 1200 RPM
     * while the car is moving in gear, the ECU cuts injector pulses entirely.
     */
    fun checkDfco(
        throttlePosPercent: Double,
        rpm: Double,
        speedKmh: Double,
        currentGear: GearSelection,
    ): Boolean {
        val isThrottleClosed = throttlePosPercent <= CivicSpecs.CLOSED_THROTTLE_BASELINE_PERCENT
        val isAboveIdleRpm = rpm >= 1200
        val isMoving = speedKmh >= 10
        val isInGear = currentGear is GearSelection.Gear && currentGear.number in 1..5

        return isThrottleClosed && isAboveIdleRpm && isMoving && isInGear
    }

    /**
     * Real-time fuel flow in grams/sec, gallons/hour and litres/hour.
     */
    fun calculateFuelFlow(
        mafGramsPerSec: Double,
        airFuelRatio: Double,
        isDfcoActive: Boolean,
    ): FuelFlow {
        if (isDfcoActive) {
            return FuelFlow(0.0, 0.0, 0.0)
        }

        val afr = if (airFuelRatio > 0) airFuelRatio else blend.stoichAfr
        val maf = max(0.0, mafGramsPerSec)

        // Fuel mass flow (g/s) = air mass flow (g/s) / AFR. MAF measures air mass directly,
        // so this chain stays in mass until the final division by density - which is where
        // the blend's density has to be the real one.
        val fuelFlowGramsPerSec = maf / afr

        return FuelFlow(
            fuelFlowGramsPerSec = fuelFlowGramsPerSec,
            fuelFlowGalPerHour = (fuelFlowGramsPerSec * 3600) / blend.densityGramsPerGallon,
            fuelFlowLitersPerHour = (fuelFlowGramsPerSec * 3600) / blend.densityGramsPerLiter,
        )
    }

    /**
     * Instantaneous MPG. Returns 0 when stopped and the 99.9 display cap on a closed
     * throttle - see [MpgDisplayState] for why neither is an economy figure.
     */
    fun calculateInstantMpg(
        speedMph: Double,
        fuelFlowGalPerHour: Double,
        isDfcoActive: Boolean,
    ): Double {
        if (speedMph <= 1.0) {
            return 0.0
        }

        if (isDfcoActive || fuelFlowGalPerHour <= 0.001) {
            return 99.9 // Standard automotive digital gauge cap for DFCO coasting
        }

        val rawMpg = speedMph / fuelFlowGalPerHour
        return min(99.9, max(0.0, rawMpg))
    }

    /**
     * Rolling smoothed MPG, to take single-packet sensor spikes out of the figure.
     *
     * Harmonic mean - N / Σ(1/MPG) - excluding zero and DFCO entries, so 99.9 coasting
     * spikes cannot inflate the average.
     */
    fun updateRollingMpg(instantMpg: Double): Double {
        recentMpgHistory.addLast(instantMpg)
        if (recentMpgHistory.size > maxHistorySamples) {
            recentMpgHistory.removeFirst()
        }

        var reciprocalSum = 0.0
        var validCount = 0
        for (mpg in recentMpgHistory) {
            if (mpg > 0.1 && mpg < 99.9) {
                reciprocalSum += 1.0 / mpg
                validCount++
            }
        }
        return if (validCount > 0) validCount / reciprocalSum else 0.0
    }

    /**
     * Shapes instant MPG into something a driver can read at a glance, and says which of the
     * three things it currently is. See the field comments above for why the damping exists.
     *
     * Standing still and coasting deliberately do not feed the average. Letting them in was
     * the whole problem: every red light dragged it to zero and every off-throttle moment
     * pulled it toward 99.9, so the figure spent most of a drive recovering from states that
     * were never economy readings in the first place. Frozen instead of reset, so pulling
     * away from a light resumes from what you were getting rather than climbing from nothing.
     */
    fun updateDisplayMpg(
        instantMpg: Double,
        speedMph: Double,
        isDfcoActive: Boolean,
        dtSec: Double,
    ): MpgDisplayReading {
        val state = when {
            speedMph <= 1.0 -> MpgDisplayState.IDLE
            isDfcoActive -> MpgDisplayState.COASTING
            else -> MpgDisplayState.DRIVING
        }

        if (state == MpgDisplayState.DRIVING) {
            // Frame-rate independent: a dropped frame damps by the time that actually passed
            // rather than by one fixed step, so the needle behaves the same on a slow phone.
            val alpha = 1 - exp(-max(0.0, dtSec) / displayTimeConstantSec)
            displayMpgAverage += (instantMpg - displayMpgAverage) * alpha
        }

        displayMpgLatchAgeSec += max(0.0, dtSec)
        if (displayMpgLatchAgeSec >= displayLatchIntervalSec) {
            displayMpgLatchAgeSec = 0.0
            displayMpgLatched = displayMpgAverage
        }

        return MpgDisplayReading(value = displayMpgLatched, state = state)
    }

    /**
     * Remaining range in miles from tank level and the current rolling MPG.
     *
     * Falls back to the EPA combined rating before a real rolling sample has built up (right
     * at startup, say), so the readout does not show 0 or blow up on a near-zero divisor.
     */
    fun calculateFuelRange(
        fuelLevelPercent: Double,
        tankCapacityGallons: Double,
        rollingMpg: Double,
    ): Double {
        val gallonsRemaining = (fuelLevelPercent.coerceIn(0.0, 100.0) / 100.0) * tankCapacityGallons
        val effectiveMpg = if (rollingMpg > 1) rollingMpg else CivicSpecs.EPA_COMBINED_MPG_DEFAULT
        return max(0.0, gallonsRemaining * effectiveMpg)
    }

    /**
     * Speed-density estimate, for when the MAF sensor is disconnected or faulty.
     *
     * Ideal gas law: air density = P / (R_specific * T), with R_specific for dry air of
     * 287.058 J/(kg·K).
     */
    fun estimateMafFromSpeedDensity(
        mapKpa: Double,
        intakeAirTempC: Double,
        rpm: Double,
        volumetricEfficiency: Double = 0.85,
    ): Double {
        val iatKelvin = intakeAirTempC + 273.15
        val displacementL = CivicSpecs.ENGINE_DISPLACEMENT_LITERS
        val mafGramsPerSec =
            (mapKpa * (rpm / 120) * displacementL * volumetricEfficiency * 28.97) / (8.314 * iatKelvin)
        return max(0.0, mafGramsPerSec)
    }
}
