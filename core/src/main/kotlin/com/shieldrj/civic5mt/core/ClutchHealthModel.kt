package com.shieldrj.civic5mt.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class ClutchConditionGrade(val label: String) {
    EXCELLENT("Pristine"),
    GOOD("Healthy"),
    MODERATE_WEAR("Moderate Wear"),
    INCIPIENT_SLIP("Worn / Incipient Slip"),
    CRITICAL("Critical / Replace Now"),
    ;

    override fun toString(): String = label
}

enum class SlipClassification(val label: String) {
    LOCKED("Locked"),
    LAUNCH("Launch Engagement"),
    SHIFT_ENGAGEMENT("Shift Transition"),
    MICRO_SLIP("Incipient Micro-Slip"),
    MACRO_SLIP("Severe Slip"),
}

data class ClutchLiveStatus(
    val slipRpm: Double = 0.0,
    val slipRatio: Double = 0.0,
    val estimatedTorqueNm: Double = 0.0,
    val slipPowerWatts: Double = 0.0,
    val discTempC: Double = 25.0,
    val classification: SlipClassification = SlipClassification.LOCKED,
    val isSlipping: Boolean = false,
    val isMacroSlip: Boolean = false,
)

data class ClutchWearBreakdown(
    val shiftWearPercent: Double = 0.0,
    val launchWearPercent: Double = 0.0,
    val slipWearPercent: Double = 0.0,
    val thermalGlazePenaltyPercent: Double = 0.0,
)

data class ClutchSlipIncident(
    val timestamp: Long,
    val gear: Int,
    val peakSlipRpm: Double,
    val peakTorqueNm: Double,
    val speedKmh: Double,
    val durationSec: Double,
)

data class ClutchProfile(
    val lastResetTimestamp: Long,
    val lastResetOdometer: Double,
    val currentOdometer: Double,
    /** 0.0 - 100.0%. */
    val clutchHealthPercent: Double,

    // Deep mechanical factors
    /** Cumulative thermal friction energy dissipated across the disc (Joules). */
    val accumulatedFrictionEnergyJoules: Double,
    val totalEngagementsCount: Int,
    val abnormalSlipCount: Int,
    val maxObservedTempC: Double,
    /** Dynamic holding capacity boundary in N·m. New is ~277 N·m, peak engine torque is 174 N·m. */
    val estimatedTorqueCapacityNm: Double,

    // Prognostics / Remaining Useful Life
    val estimatedMilesRemaining: Int,
    val estimatedDaysRemaining: Int?,
    val estimatedShiftsRemaining: Int,
    val conditionGrade: ClutchConditionGrade,
    val degradationBreakdown: ClutchWearBreakdown,
    val recentIncidents: List<ClutchSlipIncident> = emptyList(),
)

interface ClutchProfileStore {
    fun load(): ClutchProfile?
    fun save(profile: ClutchProfile)
}

class InMemoryClutchProfileStore(private var stored: ClutchProfile? = null) : ClutchProfileStore {
    override fun load(): ClutchProfile? = stored
    override fun save(profile: ClutchProfile) {
        stored = profile
    }
}

/** Round to one decimal place. */
private fun round1(value: Double): Double = toFixed(value, 1).toDouble()

private const val MIN_DAYS_FOR_RATE = 7.0

class ClutchHealthEngine(
    private val store: ClutchProfileStore = InMemoryClutchProfileStore(),
    private val clock: MillisClock = SystemMillisClock,
) {
    private var profile: ClutchProfile
    private var currentDiscTempC: Double = 25.0
    private var activeSlipDurationSec: Double = 0.0
    private var activeSlipPeakRpm: Double = 0.0
    private var activeSlipPeakTorque: Double = 0.0
    private var lastSaveTimestamp: Long = 0L

    // Shift engagement transition tracking
    private var previousGearNumber: Int? = null
    private var timeInCurrentGearSec: Double = 0.0

    init {
        profile = store.load() ?: defaultProfile().also { store.save(it) }
        recalculateClutchHealth()
    }

    private fun defaultProfile(): ClutchProfile = ClutchProfile(
        lastResetTimestamp = clock.nowMillis() - 30L * 24 * 60 * 60 * 1000,
        lastResetOdometer = 112000.0,
        currentOdometer = 114250.0,
        clutchHealthPercent = 88.5,
        accumulatedFrictionEnergyJoules = 4_830_000.0, // ~4.83 MJ
        totalEngagementsCount = 4200,
        abnormalSlipCount = 2,
        maxObservedTempC = 142.0,
        estimatedTorqueCapacityNm = 265.0,
        estimatedMilesRemaining = 78500,
        estimatedDaysRemaining = 980,
        estimatedShiftsRemaining = 32000,
        conditionGrade = ClutchConditionGrade.EXCELLENT,
        degradationBreakdown = ClutchWearBreakdown(
            shiftWearPercent = 8.2,
            launchWearPercent = 2.5,
            slipWearPercent = 0.5,
            thermalGlazePenaltyPercent = 0.3,
        ),
        recentIncidents = emptyList(),
    )

    fun getProfile(): ClutchProfile = profile

    fun saveProfile(toSave: ClutchProfile = profile) {
        profile = toSave
        store.save(toSave)
    }

    private fun debouncedSave() {
        val now = clock.nowMillis()
        if (now - lastSaveTimestamp >= 30_000) {
            lastSaveTimestamp = now
            saveProfile()
        }
    }

    /**
     * Estimates brake torque on the R18Z1 flywheel (N·m) from engine speed, MAF, and combustion efficiency.
     */
    fun estimateBrakeTorqueNm(
        rpm: Double,
        mafGramsPerSec: Double,
        lambda: Double? = 1.0,
        timingAdvanceDeg: Double = 20.0,
    ): Double {
        if (rpm < CivicSpecs.ENGINE_RUNNING_RPM) return 0.0

        val effLambda = (lambda ?: 1.0).coerceIn(0.75, 1.25)
        // Fuel energy & volumetric scaling
        // R18Z1 produces max 174 Nm @ 4300 RPM.
        val lambdaEfficiency = if (effLambda < 1.0) {
            // Slight rich enrichment torque gain
            1.0 + (1.0 - effLambda) * 0.15
        } else {
            // Lean torque drop
            1.0 - (effLambda - 1.0) * 0.4
        }.coerceIn(0.7, 1.05)

        val timingEfficiency = (1.0 - abs(timingAdvanceDeg - 24.0) * 0.012).coerceIn(0.75, 1.0)

        val normalizedRpm = max(750.0, rpm) / 1000.0
        // Torque = Power / omega = (MAF * scaling) / RPM
        val rawTorque = (mafGramsPerSec * 9.55 * lambdaEfficiency * timingEfficiency) / normalizedRpm
        return min(CivicSpecs.ENGINE_PEAK_TORQUE_NM, max(0.0, rawTorque))
    }

    /**
     * Analyzes slip kinematics and accumulates physical wear from one telemetry step.
     */
    fun recordTelemetryStep(
        rpm: Double,
        speedKmh: Double,
        throttlePercent: Double,
        mafGramsPerSec: Double,
        lambda: Double?,
        timingAdvanceDeg: Double,
        gearSelection: GearSelection,
        ambientTempC: Double?,
        speedMph: Double,
        dtSec: Double,
    ): Pair<ClutchLiveStatus, ClutchProfile> {
        val stepDt = max(0.01, min(1.0, dtSec))
        val ambient = ambientTempC ?: 20.0

        val torqueNm = estimateBrakeTorqueNm(rpm, mafGramsPerSec, lambda, timingAdvanceDeg)

        // 1. Drivetrain Kinematics & Theoretical Engine Speed
        val detectedGearNum = (gearSelection as? GearSelection.Gear)?.number
        if (detectedGearNum != previousGearNumber) {
            if (previousGearNumber != null && detectedGearNum != null) {
                profile = profile.copy(totalEngagementsCount = profile.totalEngagementsCount + 1)
            }
            previousGearNumber = detectedGearNum
            timeInCurrentGearSec = 0.0
        } else {
            timeInCurrentGearSec += stepDt
        }

        val expectedRpm: Double
        val slipRpm: Double
        val slipRatio: Double
        val classification: SlipClassification

        if (detectedGearNum != null && speedKmh >= 3.0) {
            val gearRatio = CivicSpecs.GEAR_RATIOS.getValue(detectedGearNum)
            val totalRatio = gearRatio * CivicSpecs.FINAL_DRIVE_RATIO
            val wheelRpm = (speedKmh / 60.0) / CivicSpecs.TIRE_CIRCUMFERENCE_KM
            expectedRpm = wheelRpm * totalRatio
            slipRpm = rpm - expectedRpm
            slipRatio = if (expectedRpm > 10.0) slipRpm / expectedRpm else 0.0

            classification = when {
                // Launch in 1st gear under 12 km/h
                detectedGearNum == 1 && speedKmh < 14.0 && timeInCurrentGearSec < 2.0 -> {
                    SlipClassification.LAUNCH
                }
                // Transition engagement right after shift
                timeInCurrentGearSec < 0.6 && abs(slipRpm) > 75.0 -> {
                    SlipClassification.SHIFT_ENGAGEMENT
                }
                // Locked driveline
                abs(slipRpm) <= 65.0 || abs(slipRatio) <= 0.025 -> {
                    SlipClassification.LOCKED
                }
                // Macro slip under load
                throttlePercent > 35.0 && slipRpm > 250.0 && rpm > 2200.0 -> {
                    SlipClassification.MACRO_SLIP
                }
                // Micro slip
                throttlePercent > 40.0 && slipRpm in 75.0..250.0 -> {
                    SlipClassification.MICRO_SLIP
                }
                else -> SlipClassification.LOCKED
            }
        } else if (speedKmh < 3.0 && rpm > CivicSpecs.IDLE_RPM + 100) {
            expectedRpm = 0.0
            slipRpm = rpm
            slipRatio = 1.0
            classification = if (detectedGearNum == 1) SlipClassification.LAUNCH else SlipClassification.SHIFT_ENGAGEMENT
        } else {
            expectedRpm = 0.0
            slipRpm = 0.0
            slipRatio = 0.0
            classification = SlipClassification.LOCKED
        }

        val isSlipping = classification == SlipClassification.MICRO_SLIP || classification == SlipClassification.MACRO_SLIP
        val isMacroSlip = classification == SlipClassification.MACRO_SLIP

        // 2. Transmitted Torque & Slip Friction Power
        // P = Torque * DeltaOmega [Watts]
        val deltaOmega = (2.0 * PI / 60.0) * abs(slipRpm)
        val slipPowerWatts = torqueNm * deltaOmega

        // 3. Thermodynamic Heat Model (Lumped Parameter)
        val thermalPowerIn = slipPowerWatts
        val thermalPowerOut = CivicSpecs.CLUTCH_COOLING_COEFF_W_PER_K * (currentDiscTempC - ambient)
        val netThermalRate = (thermalPowerIn - thermalPowerOut) / CivicSpecs.CLUTCH_THERMAL_MASS_J_PER_K
        currentDiscTempC = max(ambient, currentDiscTempC + netThermalRate * stepDt)

        // 4. Archard Wear Accumulation with Non-Linear Thermal Acceleration
        val thermalWearMultiplier = when {
            currentDiscTempC < CivicSpecs.CLUTCH_NORMAL_TEMP_THRESHOLD_C -> 1.0
            currentDiscTempC < CivicSpecs.CLUTCH_GLAZE_TEMP_THRESHOLD_C -> {
                1.0 + 1.5 * ((currentDiscTempC - CivicSpecs.CLUTCH_NORMAL_TEMP_THRESHOLD_C) / 70.0)
            }
            else -> {
                2.5 + 5.5 * min(2.0, (currentDiscTempC - CivicSpecs.CLUTCH_GLAZE_TEMP_THRESHOLD_C) / 50.0)
            }
        }

        val effectiveWearEnergyJoules = slipPowerWatts * thermalWearMultiplier * stepDt

        // Incident tracking
        var updatedIncidents = profile.recentIncidents
        var updatedAbnormalSlipCount = profile.abnormalSlipCount
        var updatedTorqueCapacity = profile.estimatedTorqueCapacityNm

        if (isMacroSlip && detectedGearNum != null) {
            activeSlipDurationSec += stepDt
            activeSlipPeakRpm = max(activeSlipPeakRpm, slipRpm)
            activeSlipPeakTorque = max(activeSlipPeakTorque, torqueNm)

            // When macro slip occurs in 3rd, 4th, or 5th gear, the holding capacity has degraded to current torque
            if (detectedGearNum >= 3 && torqueNm > 50.0) {
                updatedTorqueCapacity = min(updatedTorqueCapacity, max(80.0, torqueNm * 1.05))
            }
        } else {
            if (activeSlipDurationSec >= 0.3) {
                // Log incident
                updatedAbnormalSlipCount++
                val incident = ClutchSlipIncident(
                    timestamp = clock.nowMillis(),
                    gear = previousGearNumber ?: 1,
                    peakSlipRpm = round1(activeSlipPeakRpm),
                    peakTorqueNm = round1(activeSlipPeakTorque),
                    speedKmh = round1(speedKmh),
                    durationSec = round1(activeSlipDurationSec),
                )
                updatedIncidents = (listOf(incident) + updatedIncidents).take(10)
            }
            activeSlipDurationSec = 0.0
            activeSlipPeakRpm = 0.0
            activeSlipPeakTorque = 0.0
        }

        // Increment mileage
        val stepMiles = (speedMph / 3600.0) * stepDt

        profile = profile.copy(
            accumulatedFrictionEnergyJoules = profile.accumulatedFrictionEnergyJoules + effectiveWearEnergyJoules,
            currentOdometer = profile.currentOdometer + stepMiles,
            abnormalSlipCount = updatedAbnormalSlipCount,
            maxObservedTempC = max(profile.maxObservedTempC, currentDiscTempC),
            estimatedTorqueCapacityNm = updatedTorqueCapacity,
            recentIncidents = updatedIncidents,
        )

        recalculateClutchHealth()
        debouncedSave()

        val liveStatus = ClutchLiveStatus(
            slipRpm = round1(slipRpm),
            slipRatio = round1(slipRatio * 100.0), // as percentage
            estimatedTorqueNm = round1(torqueNm),
            slipPowerWatts = round1(slipPowerWatts),
            discTempC = round1(currentDiscTempC),
            classification = classification,
            isSlipping = isSlipping,
            isMacroSlip = isMacroSlip,
        )

        return liveStatus to profile
    }

    /**
     * Recomputes derived clutch health metrics, condition grades, and RUL projections.
     */
    fun recalculateClutchHealth() {
        // 1. Friction material energy depletion (Archard model vs 42 MJ baseline)
        val frictionDepletionPercent =
            (profile.accumulatedFrictionEnergyJoules / CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES) * 100.0

        // 2. Physical wear energy breakdown (Sums consistently to the total accumulated friction work)
        val shiftWear = min(frictionDepletionPercent, (profile.totalEngagementsCount * 650.0 / CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES) * 100.0)
        val launchWear = min(
            max(0.0, frictionDepletionPercent - shiftWear),
            (profile.totalEngagementsCount * 0.25 * 1800.0 / CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES) * 100.0
        )
        val slipWear = max(0.0, frictionDepletionPercent - shiftWear - launchWear)

        val thermalGlazePenalty = if (profile.maxObservedTempC > CivicSpecs.CLUTCH_GLAZE_TEMP_THRESHOLD_C) {
            min(15.0, (profile.maxObservedTempC - CivicSpecs.CLUTCH_GLAZE_TEMP_THRESHOLD_C) * 0.3)
        } else {
            0.0
        }

        // 3. Clamping torque reserve margin
        // New is ~277 Nm, peak engine is 174 Nm. If capacity <= 174 Nm, reserve is 0%.
        val torqueMarginPercent = if (profile.estimatedTorqueCapacityNm <= CivicSpecs.ENGINE_PEAK_TORQUE_NM) {
            0.0
        } else {
            min(100.0, ((profile.estimatedTorqueCapacityNm - CivicSpecs.ENGINE_PEAK_TORQUE_NM) /
                (CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM - CivicSpecs.ENGINE_PEAK_TORQUE_NM)) * 100.0)
        }

        // Weighted Composite Health:
        // 60% remaining friction energy budget, 30% torque holding margin, minus thermal glaze penalty
        val rawHealth = (100.0 - frictionDepletionPercent) * 0.6 + (torqueMarginPercent * 0.4) - thermalGlazePenalty
        val clutchHealthPercent = max(0.0, min(100.0, rawHealth))

        // 4. Prognostics / Remaining Useful Life
        val milesDriven = max(0.0, profile.currentOdometer - profile.lastResetOdometer)
        val degradationRatio = if (frictionDepletionPercent > 0) frictionDepletionPercent / 100.0 else 0.005
        val projectedTotalMiles = if (milesDriven > 50) milesDriven / degradationRatio else 100_000.0
        val estimatedMilesRemaining = max(0L, ((clutchHealthPercent / 100.0) * min(150_000.0, projectedTotalMiles)).roundToLong()).toInt()

        // Days remaining
        val daysSinceReset = max(1.0, (clock.nowMillis() - profile.lastResetTimestamp) / (24.0 * 60 * 60 * 1000))
        val dailyMileage = milesDriven / daysSinceReset
        val estimatedDaysRemaining = if (daysSinceReset >= MIN_DAYS_FOR_RATE && dailyMileage > 0.1) {
            (estimatedMilesRemaining / dailyMileage).roundToInt()
        } else {
            null
        }

        // Shifts remaining: average clean shift consumes ~750 Joules of friction work
        val energyRemainingJoules = max(0.0, CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES - profile.accumulatedFrictionEnergyJoules)
        val estimatedShiftsRemaining = max(0, (energyRemainingJoules / 750.0).roundToInt())

        val grade = when {
            clutchHealthPercent >= 85.0 -> ClutchConditionGrade.EXCELLENT
            clutchHealthPercent >= 65.0 -> ClutchConditionGrade.GOOD
            clutchHealthPercent >= 40.0 -> ClutchConditionGrade.MODERATE_WEAR
            clutchHealthPercent >= 20.0 -> ClutchConditionGrade.INCIPIENT_SLIP
            else -> ClutchConditionGrade.CRITICAL
        }

        profile = profile.copy(
            clutchHealthPercent = round1(clutchHealthPercent),
            estimatedMilesRemaining = estimatedMilesRemaining,
            estimatedDaysRemaining = estimatedDaysRemaining,
            estimatedShiftsRemaining = estimatedShiftsRemaining,
            conditionGrade = grade,
            degradationBreakdown = ClutchWearBreakdown(
                shiftWearPercent = round1(shiftWear),
                launchWearPercent = round1(launchWear),
                slipWearPercent = round1(slipWear),
                thermalGlazePenaltyPercent = round1(thermalGlazePenalty),
            ),
        )
    }

    /**
     * Resets clutch health to 100% after replacement with a new friction disc & pressure plate.
     */
    fun resetClutchProfile(odometerAtReset: Double? = null): ClutchProfile {
        val currentOdo = odometerAtReset ?: profile.currentOdometer
        profile = ClutchProfile(
            lastResetTimestamp = clock.nowMillis(),
            lastResetOdometer = currentOdo,
            currentOdometer = currentOdo,
            clutchHealthPercent = 100.0,
            accumulatedFrictionEnergyJoules = 0.0,
            totalEngagementsCount = 0,
            abnormalSlipCount = 0,
            maxObservedTempC = 25.0,
            estimatedTorqueCapacityNm = CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM,
            estimatedMilesRemaining = 120_000,
            estimatedDaysRemaining = null,
            estimatedShiftsRemaining = 56_000,
            conditionGrade = ClutchConditionGrade.EXCELLENT,
            degradationBreakdown = ClutchWearBreakdown(0.0, 0.0, 0.0, 0.0),
            recentIncidents = emptyList(),
        )
        currentDiscTempC = 25.0
        saveProfile()
        return profile
    }
}
