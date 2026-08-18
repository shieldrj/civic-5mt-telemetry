package com.shieldrj.civic5mt.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class OilConditionGrade(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    SERVICE_DUE("Service Due"),
    DEGRADED("Degraded"),
    ;

    override fun toString(): String = label
}

data class DegradationBreakdown(
    val revWearFactor: Double,
    val coldStartPenalty: Double,
    val shortTripPenalty: Double,
    val thermalShearPenalty: Double,
)

data class OilLifeProfile(
    val lastResetTimestamp: Long,
    val lastResetOdometer: Double,
    val currentOdometer: Double,
    /** 0 - 100%. */
    val oilLifePercent: Double,

    // Deep tracking factors
    /** Total crankshaft cycles. */
    val accumulatedRevolutions: Double,
    /** Starts with coolant below 160°F. */
    val coldStartsCount: Int,
    /** Seconds under 160°F. */
    val timeBelowOperatingTempSec: Double,
    /** Trips under 15 minutes without full warmup. */
    val shortTripsCount: Int,
    /** Seconds above 4500 RPM at high load. */
    val highThermalStressSec: Double,

    // Projected wear
    val estimatedMilesRemaining: Int,
    val estimatedDaysRemaining: Int,
    val oilConditionGrade: OilConditionGrade,
    val degradationBreakdown: DegradationBreakdown,
)

/**
 * Where the oil profile is kept between runs.
 *
 * An interface because this module has no Android in it and must not gain any: the real
 * implementation will be Room, the tests use [InMemoryOilProfileStore], and the migration
 * importer will hand over the values rescued from the WebView's localStorage. The model
 * itself does not care which.
 */
interface OilProfileStore {
    fun load(): OilLifeProfile?
    fun save(profile: OilLifeProfile)
}

class InMemoryOilProfileStore(private var stored: OilLifeProfile? = null) : OilProfileStore {
    override fun load(): OilLifeProfile? = stored
    override fun save(profile: OilLifeProfile) {
        stored = profile
    }
}

/** Round to one decimal, the way the TypeScript's `parseFloat(x.toFixed(1))` did. */
private fun round1(value: Double): Double = toFixed(value, 1).toDouble()

class OilLifeEngine(
    private val store: OilProfileStore = InMemoryOilProfileStore(),
    private val clock: MillisClock = SystemMillisClock,
) {
    private var profile: OilLifeProfile
    private var currentTripDurationSec: Double = 0.0
    private var currentTripMaxTempC: Double = 0.0
    private var lastSaveTimestamp: Long = 0L

    init {
        profile = store.load() ?: defaultProfile().also { store.save(it) }
    }

    private fun defaultProfile(): OilLifeProfile = OilLifeProfile(
        lastResetTimestamp = clock.nowMillis() - 30L * 24 * 60 * 60 * 1000, // 30 days ago
        lastResetOdometer = 112000.0,
        currentOdometer = 114250.0,
        oilLifePercent = 78.5,
        accumulatedRevolutions = 3_200_000.0,
        coldStartsCount = 42,
        timeBelowOperatingTempSec = 28400.0,
        shortTripsCount = 14,
        highThermalStressSec = 920.0,
        estimatedMilesRemaining = 5887,
        estimatedDaysRemaining = 74,
        oilConditionGrade = OilConditionGrade.GOOD,
        degradationBreakdown = DegradationBreakdown(
            revWearFactor = 22.0,
            coldStartPenalty = 4.8,
            shortTripPenalty = 3.2,
            thermalShearPenalty = 1.5,
        ),
    )

    fun getProfile(): OilLifeProfile = profile

    fun saveProfile(toSave: OilLifeProfile = profile) {
        profile = toSave
        store.save(toSave)
    }

    /** Save at most once per 30 seconds - this runs on every telemetry tick. */
    private fun debouncedSave() {
        val now = clock.nowMillis()
        if (now - lastSaveTimestamp >= 30_000) {
            lastSaveTimestamp = now
            saveProfile()
        }
    }

    /**
     * Accumulates wear factors from one live engine update.
     *
     * @param rpm current engine speed
     * @param coolantTempC coolant temperature in Celsius
     * @param engineLoadPercent calculated engine load, 0-100
     * @param speedMph vehicle speed
     * @param dtSec seconds since the last update
     */
    fun recordTelemetryStep(
        rpm: Double,
        coolantTempC: Double,
        engineLoadPercent: Double,
        speedMph: Double,
        dtSec: Double,
    ): OilLifeProfile {
        if (rpm < 400) {
            // Engine is not running.
            return profile
        }

        currentTripDurationSec += dtSec
        currentTripMaxTempC = max(currentTripMaxTempC, coolantTempC)

        // 1. Mechanical revolutions.
        val stepRevs = (rpm / 60.0) * dtSec
        // Mileage increment estimate.
        val stepMiles = (speedMph / 3600.0) * dtSec

        // 2. Cold operation and warmup penalty. Normal operating temperature is ~71-90°C.
        val isCold = coolantTempC < CivicSpecs.OPERATING_TEMP_THRESHOLD_C

        // 3. High thermal / RPM stress penalty.
        val isHighStress = rpm > CivicSpecs.HIGH_THERMAL_THRESHOLD_RPM &&
            engineLoadPercent > CivicSpecs.HIGH_LOAD_THRESHOLD_PERCENT

        profile = profile.copy(
            accumulatedRevolutions = profile.accumulatedRevolutions + stepRevs,
            currentOdometer = profile.currentOdometer + stepMiles,
            timeBelowOperatingTempSec = profile.timeBelowOperatingTempSec + if (isCold) dtSec else 0.0,
            highThermalStressSec = profile.highThermalStressSec + if (isHighStress) dtSec else 0.0,
        )

        recalculateOilHealth()
        debouncedSave()
        return profile
    }

    /**
     * Marks a new engine start. Below the operating-temperature threshold it counts as a
     * cold start.
     */
    fun registerEngineStart(coolantTempC: Double) {
        currentTripDurationSec = 0.0
        currentTripMaxTempC = coolantTempC

        if (coolantTempC < CivicSpecs.OPERATING_TEMP_THRESHOLD_C) {
            profile = profile.copy(coldStartsCount = profile.coldStartsCount + 1)
        }
        saveProfile()
    }

    /**
     * Marks trip completion. A trip under 15 minutes that never got hot enough to boil off
     * condensation leaves moisture and fuel in the oil, so it is counted against it.
     */
    fun registerEngineStop() {
        if (currentTripDurationSec > 60 && currentTripDurationSec < 900 && currentTripMaxTempC < 80) {
            profile = profile.copy(shortTripsCount = profile.shortTripsCount + 1)
        }
        currentTripDurationSec = 0.0
        currentTripMaxTempC = 0.0
        recalculateOilHealth()
        saveProfile()
    }

    /**
     * Resets the tracker to 100% after an oil and filter change.
     */
    fun resetOilLife(odometerAtReset: Double? = null): OilLifeProfile {
        val currentOdo = odometerAtReset ?: profile.currentOdometer
        profile = OilLifeProfile(
            lastResetTimestamp = clock.nowMillis(),
            lastResetOdometer = currentOdo,
            currentOdometer = currentOdo,
            oilLifePercent = 100.0,
            accumulatedRevolutions = 0.0,
            coldStartsCount = 0,
            timeBelowOperatingTempSec = 0.0,
            shortTripsCount = 0,
            highThermalStressSec = 0.0,
            estimatedMilesRemaining = CivicSpecs.BASELINE_OIL_LIFE_MILES.toInt(),
            estimatedDaysRemaining = 180, // ~6 months
            oilConditionGrade = OilConditionGrade.EXCELLENT,
            degradationBreakdown = DegradationBreakdown(0.0, 0.0, 0.0, 0.0),
        )
        saveProfile()
        return profile
    }

    private fun recalculateOilHealth() {
        // 1. Baseline revolutions wear: 14.5M revolutions = 100% baseline wear.
        val revWearPercent =
            (profile.accumulatedRevolutions / CivicSpecs.BASELINE_LIFETIME_REVOLUTIONS) * 100

        // 2. Cold start and warmup penalty - each cold start and each minute below 160°F
        //    shears oil molecules.
        val coldStartPenaltyPercent =
            (profile.coldStartsCount * 0.15) + (profile.timeBelowOperatingTempSec / 3600) * 0.4

        // 3. Short-trip moisture dilution penalty.
        val shortTripPenaltyPercent = profile.shortTripsCount * 0.35

        // 4. High thermal / RPM shear penalty.
        val thermalPenaltyPercent = (profile.highThermalStressSec / 60) * 0.25

        val totalDegradation =
            revWearPercent + coldStartPenaltyPercent + shortTripPenaltyPercent + thermalPenaltyPercent
        val remainingPercent = max(0.0, min(100.0, 100.0 - totalDegradation))

        // Estimated miles remaining.
        val milesDriven = max(0.0, profile.currentOdometer - profile.lastResetOdometer)
        val degradationRatio = if (totalDegradation > 0) totalDegradation / 100 else 0.01
        val effectiveTotalMiles =
            if (milesDriven > 50) milesDriven / degradationRatio else CivicSpecs.BASELINE_OIL_LIFE_MILES
        val estimatedMilesRemaining =
            max(0L, ((remainingPercent / 100) * effectiveTotalMiles).roundToLong()).toInt()

        // Days remaining, from the daily mileage burn rate.
        val daysSinceReset =
            max(1.0, (clock.nowMillis() - profile.lastResetTimestamp) / (24.0 * 60 * 60 * 1000))
        val dailyMileage = milesDriven / daysSinceReset
        val estimatedDaysRemaining =
            if (dailyMileage > 0.1) (estimatedMilesRemaining / dailyMileage).roundToInt() else 180

        val grade = when {
            remainingPercent > 70 -> OilConditionGrade.EXCELLENT
            remainingPercent > 40 -> OilConditionGrade.GOOD
            remainingPercent > 15 -> OilConditionGrade.FAIR
            remainingPercent > 5 -> OilConditionGrade.SERVICE_DUE
            else -> OilConditionGrade.DEGRADED
        }

        profile = profile.copy(
            oilLifePercent = round1(remainingPercent),
            degradationBreakdown = DegradationBreakdown(
                revWearFactor = round1(revWearPercent),
                coldStartPenalty = round1(coldStartPenaltyPercent),
                shortTripPenalty = round1(shortTripPenaltyPercent),
                thermalShearPenalty = round1(thermalPenaltyPercent),
            ),
            estimatedMilesRemaining = estimatedMilesRemaining,
            estimatedDaysRemaining = estimatedDaysRemaining,
            oilConditionGrade = grade,
        )
    }
}
