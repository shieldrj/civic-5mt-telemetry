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
    /** Slip as a percentage of locked engine speed. */
    val slipPercent: Double = 0.0,
    val estimatedTorqueNm: Double = 0.0,
    val slipPowerWatts: Double = 0.0,
    val discTempC: Double = 25.0,
    val classification: SlipClassification = SlipClassification.LOCKED,
    val isSlipping: Boolean = false,
    val isMacroSlip: Boolean = false,
    /** The gear the slip is measured against, or null when the driveline is open. */
    val attributedGear: Int? = null,
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

    /**
     * Whether the disc was known to be new when tracking started.
     *
     * False on a car whose clutch history predates the app, which is every car until
     * somebody fits a new one and presses reset. While it is false the health figure is
     * wear this app has *watched*, not wear the clutch has *had*, and the screen has to
     * say so rather than presenting the two as the same number.
     */
    val baselineKnown: Boolean,

    // Deep mechanical factors
    /** Cumulative thermal friction energy dissipated across the disc (Joules). */
    val accumulatedFrictionEnergyJoules: Double,
    val totalEngagementsCount: Int,
    val abnormalSlipCount: Int,
    val maxObservedTempC: Double,
    /**
     * Holding capacity in N·m: the lower of the wear-faded estimate and anything an
     * observed slip has proved. Derived on every recalculation - see
     * [ClutchHealthEngine.recalculateClutchHealth].
     */
    val estimatedTorqueCapacityNm: Double,
    /** The floor a witnessed macro-slip has established, independent of modelled fade. */
    val observedCapacityFloorNm: Double,
    /**
     * Learned correction between geometric and actual rolling circumference, applied to
     * every expected-RPM calculation. 1.0 until a steady cruise has been observed.
     */
    val ratioCalibration: Double,

    // Prognostics / Remaining Useful Life
    /** Null until enough miles have been watched to project from. */
    val estimatedMilesRemaining: Int?,
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

/** Below this there are not enough watched miles for a wear rate to mean anything. */
private const val MIN_MILES_FOR_RATE = 200.0

/**
 * Throttle below this counts as a lift: the accelerator came up, so the clutch pedal may
 * have gone down and the gear may have changed. Honda's DBW reads ~14% at foot-off idle.
 */
private const val LIFT_THROTTLE_PERCENT = 18.0

/** Throttle above this is a driver asking for torque, so slip there is the clutch's doing. */
private const val LOADED_THROTTLE_PERCENT = 35.0

class ClutchHealthEngine(
    private val store: ClutchProfileStore = InMemoryClutchProfileStore(),
    private val clock: MillisClock = SystemMillisClock,
) {
    private var profile: ClutchProfile
    private var currentDiscTempC: Double = 25.0
    private var activeSlipDurationSec: Double = 0.0
    private var activeSlipPeakRpm: Double = 0.0
    private var activeSlipPeakTorque: Double = 0.0
    private var activeSlipGear: Int? = null
    private var lastSaveTimestamp: Long = 0L

    // Shift engagement transition tracking
    private var previousGearNumber: Int? = null
    private var timeInCurrentGearSec: Double = 0.0

    /**
     * The last gear the ratio actually matched, carried forward while the driver's foot
     * stays in it. See [attributeGear] for why this is not simply the reported gear.
     */
    private var confirmedGear: Int? = null
    private var liftedSinceGearConfirmed: Boolean = true

    init {
        profile = store.load() ?: defaultProfile().also { store.save(it) }
        recalculateClutchHealth()
    }

    /**
     * What the app knows about a clutch it has never seen before: nothing.
     *
     * Every figure here used to be invented - 88.5% healthy, 114,250 miles, 4,200
     * engagements, two abnormal slips, a 142°C peak - and all of it appeared on the screen
     * the first time the app was opened, on a car it had never been plugged into. A number
     * the car did not supply does not belong on a gauge. Same reasoning that retired the
     * confident fuel figure after the sender gives up.
     */
    private fun defaultProfile(): ClutchProfile = ClutchProfile(
        lastResetTimestamp = clock.nowMillis(),
        lastResetOdometer = 0.0,
        currentOdometer = 0.0,
        clutchHealthPercent = 100.0,
        baselineKnown = false,
        accumulatedFrictionEnergyJoules = 0.0,
        totalEngagementsCount = 0,
        abnormalSlipCount = 0,
        maxObservedTempC = 25.0,
        estimatedTorqueCapacityNm = CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM,
        observedCapacityFloorNm = CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM,
        ratioCalibration = 1.0,
        estimatedMilesRemaining = null,
        estimatedDaysRemaining = null,
        estimatedShiftsRemaining = 0,
        conditionGrade = ClutchConditionGrade.EXCELLENT,
        degradationBreakdown = ClutchWearBreakdown(),
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
     * Estimates brake torque at the flywheel (N·m) from engine speed, MAF and spark timing.
     *
     * Airflow per cycle is what sets indicated torque on a throttled engine, and MAF/RPM is
     * that quantity up to a constant, so the shape is right even though the coefficient is
     * fitted rather than derived. Pumping and rubbing losses are then subtracted, because
     * the clutch is driven by what leaves the crankshaft rather than by what the combustion
     * made: without that term the model credited an idling engine with ~36 N·m it is not
     * producing.
     */
    fun estimateBrakeTorqueNm(
        rpm: Double,
        mafGramsPerSec: Double,
        lambda: Double? = 1.0,
        timingAdvanceDeg: Double = 20.0,
    ): Double {
        if (rpm < CivicSpecs.ENGINE_RUNNING_RPM) return 0.0

        val effLambda = (lambda ?: 1.0).coerceIn(0.75, 1.25)
        val lambdaEfficiency = if (effLambda < 1.0) {
            // Slight rich enrichment torque gain
            1.0 + (1.0 - effLambda) * 0.15
        } else {
            // Lean torque drop
            1.0 - (effLambda - 1.0) * 0.4
        }.coerceIn(0.7, 1.05)

        // Only retard costs torque. The old curve was symmetric about 24°, which docked a
        // light-throttle cruise about 19% for running the 35-45° advance that part load is
        // supposed to run - penalising the engine for being efficient.
        val timingEfficiency = if (timingAdvanceDeg >= 24.0) {
            1.0
        } else {
            (1.0 - (24.0 - timingAdvanceDeg) * 0.012).coerceIn(0.75, 1.0)
        }

        val normalizedRpm = max(750.0, rpm) / 1000.0
        val indicatedTorque = (mafGramsPerSec * 9.55 * lambdaEfficiency * timingEfficiency) / normalizedRpm

        // FMEP of roughly 100 kPa across this engine's range: T_loss = FMEP * Vd / 4pi.
        val frictionTorque = 100_000.0 * (CivicSpecs.ENGINE_DISPLACEMENT_LITERS / 1000.0) / (4 * PI)

        return min(CivicSpecs.ENGINE_PEAK_TORQUE_NM, max(0.0, indicatedTorque - frictionTorque))
    }

    /** Locked engine speed for a gear at the current road speed, tyre correction applied. */
    private fun expectedRpmFor(gear: Int, wheelRpm: Double): Double =
        wheelRpm * CivicSpecs.GEAR_RATIOS.getValue(gear) *
            CivicSpecs.FINAL_DRIVE_RATIO * profile.ratioCalibration

    /**
     * Which gear the slip should be measured against.
     *
     * [GearCalculatorEngine] identifies a gear by matching the RPM-to-speed ratio within
     * 8%, which is sound for a locked driveline and useless here: slip is precisely what
     * pushes that ratio out of the window. A clutch slipping 300 RPM in 5th stopped being
     * reported as 5th and became "clutch pedal down", so the wear it was doing was filed as
     * no wear at all. Worse, at larger slip the ratio wandered into the *next* gear's
     * window and a badly slipping 5th read as a perfectly healthy 4th.
     *
     * So rather than trust the ratio, hold on to the last gear that did match. A manual
     * gearbox cannot change gear without the clutch pedal, and the clutch pedal does not go
     * down without the accelerator coming up - so while the driver's foot has stayed in it
     * since the gear was confirmed, the car is still in that gear and any RPM above the
     * locked figure is slip. Once a lift is seen the gear is no longer trustworthy and
     * nothing is attributed until the ratio matches again.
     */
    private fun attributeGear(reportedGear: Int?, throttlePercent: Double, stationary: Boolean): Int? {
        if (stationary) {
            // Stopped: whatever gear it was in, the next move re-establishes it.
            confirmedGear = null
            liftedSinceGearConfirmed = true
            return null
        }
        if (throttlePercent < LIFT_THROTTLE_PERCENT) liftedSinceGearConfirmed = true

        if (reportedGear != null) {
            if (confirmedGear == null || liftedSinceGearConfirmed || reportedGear == confirmedGear) {
                confirmedGear = reportedGear
                liftedSinceGearConfirmed = false
                return reportedGear
            }
            // A *different* gear, with no lift in between. The gearbox cannot have changed
            // gear without one, so the ratio did not move because the driver moved the
            // lever - it moved because the clutch is slipping. This is the case that made
            // the bug dangerous rather than merely blind: at around 25% slip in 5th the
            // ratio drifts into 4th's match window, so the calculator does not report an
            // open driveline, it reports a perfectly healthy lower gear.
            //
            // A heel-toe downshift quick enough to lift and re-apply inside one 80ms tick
            // would be misread here, but only until the next lift, and an incident needs
            // 0.3s of sustained slip before it is recorded.
            return confirmedGear
        }
        return if (!liftedSinceGearConfirmed) confirmedGear else null
    }

    /**
     * Learns the rolling-radius correction from cruise.
     *
     * The geometric tyre circumference in [CivicSpecs] is the circle the sidewall describes
     * unloaded. What the car actually rolls on is 2-3% shorter, and shrinks further as the
     * tyres wear. That error lands directly on expected RPM and is indistinguishable from
     * slip: at 90 km/h in 4th it was manufacturing 92 RPM of it, which was enough to report
     * micro-slip on a healthy clutch.
     *
     * Only light-throttle cruise in a high gear is used as the reference. A clutch that
     * slips at a quarter throttle in 4th is long past diagnosis, so what is measured there
     * is tyre rather than clutch. The correction is clamped either way, so a genuinely
     * slipping clutch cannot quietly teach the model to call itself normal.
     */
    private fun updateCalibration(
        gear: Int?,
        rpm: Double,
        wheelRpm: Double,
        throttlePercent: Double,
        speedKmh: Double,
    ) {
        if (gear == null || gear < 3) return
        if (throttlePercent > 30.0 || speedKmh < 40.0 || rpm < 1200.0) return

        val geometric = wheelRpm * CivicSpecs.GEAR_RATIOS.getValue(gear) * CivicSpecs.FINAL_DRIVE_RATIO
        if (geometric < 500.0) return

        val observed = (rpm / geometric)
            .coerceIn(CivicSpecs.CLUTCH_CALIBRATION_MIN, CivicSpecs.CLUTCH_CALIBRATION_MAX)
        // Slow EMA. This is a property of the tyres, so it should take miles to move.
        profile = profile.copy(
            ratioCalibration = profile.ratioCalibration + (observed - profile.ratioCalibration) * 0.002,
        )
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

        val reportedGear = (gearSelection as? GearSelection.Gear)?.number
        if (reportedGear != previousGearNumber) {
            if (previousGearNumber != null && reportedGear != null) {
                profile = profile.copy(totalEngagementsCount = profile.totalEngagementsCount + 1)
            }
            previousGearNumber = reportedGear
            timeInCurrentGearSec = 0.0
        } else {
            timeInCurrentGearSec += stepDt
        }

        // 1. Drivetrain kinematics
        val stationary = speedKmh < CivicSpecs.CLUTCH_MIN_TRACKING_SPEED_KMH
        val wheelRpm = if (stationary) 0.0 else (speedKmh / 60.0) / CivicSpecs.TIRE_CIRCUMFERENCE_KM
        val attributedGear = attributeGear(reportedGear, throttlePercent, stationary)
        updateCalibration(reportedGear, rpm, wheelRpm, throttlePercent, speedKmh)

        var slipRpm = 0.0
        var slipRatio = 0.0
        var classification = SlipClassification.LOCKED

        if (stationary) {
            // Nothing to slip against. Revving in neutral at a light, a cold fast idle on
            // the driveway, and an idle raised by the A/C compressor all land here.
            classification = SlipClassification.LOCKED
        } else if (attributedGear != null) {
            val expectedRpm = expectedRpmFor(attributedGear, wheelRpm)
            slipRpm = rpm - expectedRpm
            slipRatio = if (expectedRpm > 200.0) slipRpm / expectedRpm else 0.0

            classification = when {
                attributedGear == 1 && speedKmh < 14.0 && timeInCurrentGearSec < 2.0 ->
                    SlipClassification.LAUNCH

                timeInCurrentGearSec < 0.6 && abs(slipRatio) > CivicSpecs.CLUTCH_LOCKED_SLIP_RATIO ->
                    SlipClassification.SHIFT_ENGAGEMENT

                abs(slipRatio) <= CivicSpecs.CLUTCH_LOCKED_SLIP_RATIO ->
                    SlipClassification.LOCKED

                // Severity is a ratio, not a count of RPM. The old rule wanted +250 RPM,
                // which in 5th at any ordinary road speed is a bigger error than the gear
                // window could even represent - so severe slip in the two gears where a
                // worn clutch actually lets go was arithmetically unreachable.
                throttlePercent > LOADED_THROTTLE_PERCENT && slipRatio > CivicSpecs.CLUTCH_MACRO_SLIP_RATIO ->
                    SlipClassification.MACRO_SLIP

                throttlePercent > LOADED_THROTTLE_PERCENT && slipRatio > CivicSpecs.CLUTCH_LOCKED_SLIP_RATIO ->
                    SlipClassification.MICRO_SLIP

                else -> SlipClassification.LOCKED
            }
        } else if (speedKmh < CivicSpecs.CLUTCH_LAUNCH_MAX_SPEED_KMH &&
            throttlePercent > LIFT_THROTTLE_PERCENT &&
            rpm > CivicSpecs.IDLE_RPM + 100
        ) {
            // Moving off. The ratio matches nothing because the clutch is mid-engagement,
            // so 1st is assumed - the only gear a launch happens in - and slip is measured
            // against where the input shaft actually is rather than against a standstill.
            val expectedRpm = expectedRpmFor(1, wheelRpm)
            slipRpm = max(0.0, rpm - expectedRpm)
            slipRatio = if (expectedRpm > 50.0) slipRpm / expectedRpm else 1.0
            classification = SlipClassification.LAUNCH
        } else {
            // Coasting in neutral, or mid-shift with the pedal down: no torque path.
            classification = SlipClassification.LOCKED
        }

        val isSlipping = classification == SlipClassification.MICRO_SLIP ||
            classification == SlipClassification.MACRO_SLIP
        val isMacroSlip = classification == SlipClassification.MACRO_SLIP

        // 2. Slip friction power, P = T * delta-omega [W]. Only where the disc is actually
        //    slipping under load; a locked driveline dissipates nothing.
        val dissipating = classification != SlipClassification.LOCKED
        val deltaOmega = if (dissipating) (2.0 * PI / 60.0) * abs(slipRpm) else 0.0
        val slipPowerWatts = torqueNm * deltaOmega

        // 3. Thermodynamic heat model (lumped parameter)
        val thermalPowerOut = CivicSpecs.CLUTCH_COOLING_COEFF_W_PER_K * (currentDiscTempC - ambient)
        val netThermalRate = (slipPowerWatts - thermalPowerOut) / CivicSpecs.CLUTCH_THERMAL_MASS_J_PER_K
        currentDiscTempC = max(ambient, currentDiscTempC + netThermalRate * stepDt)

        // 4. Archard wear accumulation with non-linear thermal acceleration
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
        var updatedCapacityFloor = profile.observedCapacityFloorNm

        if (isMacroSlip && attributedGear != null) {
            if (activeSlipDurationSec == 0.0) activeSlipGear = attributedGear
            activeSlipDurationSec += stepDt
            activeSlipPeakRpm = max(activeSlipPeakRpm, slipRpm)
            activeSlipPeakTorque = max(activeSlipPeakTorque, torqueNm)

            // Slipping in a high gear proves the disc could not hold the torque it was
            // being handed, so its capacity is at most that.
            if (attributedGear >= 3 && torqueNm > 50.0) {
                updatedCapacityFloor = min(updatedCapacityFloor, max(80.0, torqueNm * 1.05))
            }
        } else {
            if (activeSlipDurationSec >= 0.3) {
                updatedAbnormalSlipCount++
                val incident = ClutchSlipIncident(
                    timestamp = clock.nowMillis(),
                    // The gear the slip happened in, captured when it started. Reading it
                    // here caught whatever gear the driver had changed into to end it.
                    gear = activeSlipGear ?: attributedGear ?: 1,
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
            activeSlipGear = null
        }

        val stepMiles = (speedMph / 3600.0) * stepDt

        profile = profile.copy(
            accumulatedFrictionEnergyJoules = profile.accumulatedFrictionEnergyJoules + effectiveWearEnergyJoules,
            currentOdometer = profile.currentOdometer + stepMiles,
            abnormalSlipCount = updatedAbnormalSlipCount,
            maxObservedTempC = max(profile.maxObservedTempC, currentDiscTempC),
            observedCapacityFloorNm = updatedCapacityFloor,
            recentIncidents = updatedIncidents,
        )

        recalculateClutchHealth()
        debouncedSave()

        val liveStatus = ClutchLiveStatus(
            slipRpm = round1(slipRpm),
            slipPercent = round1(slipRatio * 100.0),
            estimatedTorqueNm = round1(torqueNm),
            slipPowerWatts = round1(slipPowerWatts),
            discTempC = round1(currentDiscTempC),
            classification = classification,
            isSlipping = isSlipping,
            isMacroSlip = isMacroSlip,
            attributedGear = attributedGear,
        )

        return liveStatus to profile
    }

    /**
     * Recomputes derived clutch health metrics, condition grades, and RUL projections.
     */
    fun recalculateClutchHealth() {
        // 1. Friction material energy depletion
        val frictionDepletionPercent =
            (profile.accumulatedFrictionEnergyJoules / CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES) * 100.0
        val depletionFraction = (frictionDepletionPercent / 100.0).coerceIn(0.0, 1.0)

        // 2. Physical wear energy breakdown
        val shiftWear = min(
            frictionDepletionPercent,
            (
                profile.totalEngagementsCount * CivicSpecs.CLUTCH_SHIFT_ENERGY_J /
                    CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES
                ) * 100.0,
        )
        val launchWear = min(
            max(0.0, frictionDepletionPercent - shiftWear),
            (profile.totalEngagementsCount * 0.25 * 7000.0 / CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES) * 100.0,
        )
        val slipWear = max(0.0, frictionDepletionPercent - shiftWear - launchWear)

        val thermalGlazePenalty = if (profile.maxObservedTempC > CivicSpecs.CLUTCH_GLAZE_TEMP_THRESHOLD_C) {
            min(15.0, (profile.maxObservedTempC - CivicSpecs.CLUTCH_GLAZE_TEMP_THRESHOLD_C) * 0.3)
        } else {
            0.0
        }

        // 3. Holding capacity.
        //
        // Capacity is not constant until the day it slips. As the facing wears down the
        // diaphragm spring rides past its design point and clamp load falls with it, so
        // capacity fades with the friction budget and arrives at engine peak torque as that
        // budget runs out. Without the fade, capacity only ever moved when a slip was
        // actually witnessed - and since it carries 40% of the health score, a clutch that
        // had consumed its entire service life still scored 40% and read "Moderate Wear".
        val fadedCapacity = CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM -
            (CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM - CivicSpecs.ENGINE_PEAK_TORQUE_NM * 0.9) * depletionFraction
        val effectiveCapacity = min(profile.observedCapacityFloorNm, fadedCapacity)

        val torqueMarginPercent = if (effectiveCapacity <= CivicSpecs.ENGINE_PEAK_TORQUE_NM) {
            0.0
        } else {
            min(
                100.0,
                (
                    (effectiveCapacity - CivicSpecs.ENGINE_PEAK_TORQUE_NM) /
                        (CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM - CivicSpecs.ENGINE_PEAK_TORQUE_NM)
                    ) * 100.0,
            )
        }

        // 60% remaining friction budget, 40% torque holding margin, less thermal glazing.
        val rawHealth = (100.0 - frictionDepletionPercent) * 0.6 + (torqueMarginPercent * 0.4) - thermalGlazePenalty
        val clutchHealthPercent = max(0.0, min(100.0, rawHealth))

        // 4. Prognostics / Remaining Useful Life
        val milesDriven = max(0.0, profile.currentOdometer - profile.lastResetOdometer)
        val estimatedMilesRemaining: Int? =
            if (milesDriven >= MIN_MILES_FOR_RATE && frictionDepletionPercent > 0.0) {
                val projectedTotalMiles = milesDriven / (frictionDepletionPercent / 100.0)
                max(0L, ((clutchHealthPercent / 100.0) * min(250_000.0, projectedTotalMiles)).roundToLong()).toInt()
            } else {
                // Too few watched miles for a wear rate to mean anything. Saying nothing
                // beats extrapolating a service life from a drive to the shops.
                null
            }

        val daysSinceReset = max(1.0, (clock.nowMillis() - profile.lastResetTimestamp) / (24.0 * 60 * 60 * 1000))
        val dailyMileage = milesDriven / daysSinceReset
        val estimatedDaysRemaining = if (
            estimatedMilesRemaining != null && daysSinceReset >= MIN_DAYS_FOR_RATE && dailyMileage > 0.1
        ) {
            (estimatedMilesRemaining / dailyMileage).roundToInt()
        } else {
            null
        }

        val energyRemainingJoules =
            max(0.0, CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES - profile.accumulatedFrictionEnergyJoules)
        val estimatedShiftsRemaining = max(
            0,
            (
                energyRemainingJoules * CivicSpecs.CLUTCH_SHIFT_ENERGY_SHARE /
                    CivicSpecs.CLUTCH_SHIFT_ENERGY_J
                ).roundToInt(),
        )

        val grade = when {
            clutchHealthPercent >= 85.0 -> ClutchConditionGrade.EXCELLENT
            clutchHealthPercent >= 65.0 -> ClutchConditionGrade.GOOD
            clutchHealthPercent >= 40.0 -> ClutchConditionGrade.MODERATE_WEAR
            clutchHealthPercent >= 20.0 -> ClutchConditionGrade.INCIPIENT_SLIP
            else -> ClutchConditionGrade.CRITICAL
        }

        profile = profile.copy(
            clutchHealthPercent = round1(clutchHealthPercent),
            estimatedTorqueCapacityNm = round1(effectiveCapacity),
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
     *
     * This is the only thing that sets [ClutchProfile.baselineKnown]. Afterwards the app has
     * watched the disc from new, so its health figure is the whole story rather than only
     * the part it happened to be present for. The learned tyre correction survives the
     * reset - it is a property of the wheels, not of the clutch.
     */
    fun resetClutchProfile(odometerAtReset: Double? = null): ClutchProfile {
        val currentOdo = odometerAtReset ?: profile.currentOdometer
        profile = ClutchProfile(
            lastResetTimestamp = clock.nowMillis(),
            lastResetOdometer = currentOdo,
            currentOdometer = currentOdo,
            clutchHealthPercent = 100.0,
            baselineKnown = true,
            accumulatedFrictionEnergyJoules = 0.0,
            totalEngagementsCount = 0,
            abnormalSlipCount = 0,
            maxObservedTempC = 25.0,
            estimatedTorqueCapacityNm = CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM,
            observedCapacityFloorNm = CivicSpecs.CLUTCH_NEW_TORQUE_CAPACITY_NM,
            ratioCalibration = profile.ratioCalibration,
            estimatedMilesRemaining = null,
            estimatedDaysRemaining = null,
            estimatedShiftsRemaining = (
                CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES * CivicSpecs.CLUTCH_SHIFT_ENERGY_SHARE /
                    CivicSpecs.CLUTCH_SHIFT_ENERGY_J
                ).roundToInt(),
            conditionGrade = ClutchConditionGrade.EXCELLENT,
            degradationBreakdown = ClutchWearBreakdown(0.0, 0.0, 0.0, 0.0),
            recentIncidents = emptyList(),
        )
        currentDiscTempC = 25.0
        confirmedGear = null
        liftedSinceGearConfirmed = true
        saveProfile()
        return profile
    }
}
