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
    /**
     * Null until there is enough history for a daily mileage rate to mean anything.
     *
     * It is miles remaining divided by miles per day, and over a short window that divisor is
     * noise. On a record three days old it read "about 12 days" - the car would have had to
     * cover thirteen hundred miles a day for that to be true.
     */
    val estimatedDaysRemaining: Int?,
    val oilConditionGrade: OilConditionGrade,
    val degradationBreakdown: DegradationBreakdown,
    /**
     * Whether [currentOdometer] has been tied to the car's own odometer by a reading the
     * driver gave. Until then it counts miles from an arbitrary base and means nothing on its
     * own, so nothing may be shown as "due at" a dashboard figure.
     */
    val odometerSynced: Boolean = false,
    /**
     * Revolutions, each multiplied by the penalty factor for the oil temperature it ran at
     * ([GmOilLifeMethod.penaltyFactor]). This is the measurement oil life is a view of: the
     * oil is spent when it reaches [GmOilLifeMethod.BUDGET_REVOLUTIONS].
     */
    val weightedRevolutions: Double = 0.0,
    /** The part of [weightedRevolutions] above factor 1 run with oil under 80°C on drives that warmed up. */
    val coldExtraRevolutions: Double = 0.0,
    /** The same, on drives that ended before the oil ever reached 80°C. */
    val shortTripExtraRevolutions: Double = 0.0,
    /** The part above factor 1 run with oil over 130°C. */
    val hotExtraRevolutions: Double = 0.0,
    /** When the engine last stopped, for the patent's short-time-off correction. */
    val lastEngineStopMillis: Long? = null,
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

/** Below this, a daily mileage figure is an artefact of the sample window, not a habit. */
private const val MIN_DAYS_FOR_RATE = 7.0

class OilLifeEngine(
    private val store: OilProfileStore = InMemoryOilProfileStore(),
    private val clock: MillisClock = SystemMillisClock,
) {
    private var profile: OilLifeProfile
    private var currentTripDurationSec: Double = 0.0
    private var currentTripMaxTempC: Double = 0.0
    private var lastSaveTimestamp: Long = 0L

    // The oil-temperature estimate for the drive in progress. Not persisted: if the app
    // restarts mid-drive the warm-up starts again from the coolant reading, which on a warm
    // engine reaches 80°C almost at once.
    private var tripReferenceTempC: Double? = null
    private var tripRevolutions: Double = 0.0
    private var tripOilWarm: Boolean = false
    private var tripColdExtra: Double = 0.0

    init {
        val loaded = store.load()
        profile = when {
            loaded == null -> defaultProfile().also { store.save(it) }
            // A record from before the weighted count existed. Its revolutions were never
            // weighted by temperature, so carry them over at factor 1. That understates
            // cold running on the old record, which ends at the next oil change anyway.
            loaded.weightedRevolutions == 0.0 && loaded.accumulatedRevolutions > 0.0 ->
                loaded.copy(weightedRevolutions = loaded.accumulatedRevolutions)
            else -> loaded
        }

        // Recompute the derived half of what was just loaded, rather than trusting it.
        //
        // The same rule the lifetime record follows: revolutions, cold starts, short trips and
        // the odometer are the measurements; the percentage, the grade, the breakdown and the
        // miles remaining are views of them. Trusting the stored views meant a change to how
        // any of them is worked out did not reach a record already on disk until the next
        // drive - so a corrected projection sat behind the old one, on the screen someone
        // reads while deciding whether to change their oil.
        recalculateOilHealth()
    }

    private fun defaultProfile(): OilLifeProfile = OilLifeProfile(
        lastResetTimestamp = clock.nowMillis() - 30L * 24 * 60 * 60 * 1000, // 30 days ago
        lastResetOdometer = 112000.0,
        currentOdometer = 114250.0,
        oilLifePercent = 78.5,
        accumulatedRevolutions = 3_200_000.0,
        weightedRevolutions = 3_200_000.0,
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
        if (rpm < CivicSpecs.ENGINE_RUNNING_RPM) {
            // Engine is not running.
            return profile
        }

        currentTripDurationSec += dtSec
        currentTripMaxTempC = max(currentTripMaxTempC, coolantTempC)

        val stepRevs = (rpm / 60.0) * dtSec
        val stepMiles = (speedMph / 3600.0) * dtSec

        // Oil temperature, by the patent's two equations. A drive whose start was never
        // registered warms up from the coolant reading it was first seen at.
        val referenceTempC = tripReferenceTempC ?: coolantTempC.also { tripReferenceTempC = it }
        val oilTempC = if (tripOilWarm) {
            GmOilLifeMethod.equilibriumOilTempC(coolantTempC, rpm, speedMph)
        } else {
            GmOilLifeMethod.warmupOilTempC(referenceTempC, tripRevolutions)
        }
        tripRevolutions += stepRevs
        if (oilTempC >= GmOilLifeMethod.WARM_OIL_C) tripOilWarm = true

        val factor = GmOilLifeMethod.penaltyFactor(oilTempC)
        val extra = stepRevs * (factor - 1.0)
        val coldExtra = if (oilTempC < GmOilLifeMethod.WARM_OIL_C) extra else 0.0
        val hotExtra = if (oilTempC >= GmOilLifeMethod.HOT_OIL_C) extra else 0.0
        tripColdExtra += coldExtra

        // Kept as plain counts for the screen. They no longer drive the percentage - the
        // weighted revolutions do - but they say in everyday terms what the weighting saw.
        val isCold = coolantTempC < CivicSpecs.OPERATING_TEMP_THRESHOLD_C

        profile = profile.copy(
            accumulatedRevolutions = profile.accumulatedRevolutions + stepRevs,
            weightedRevolutions = profile.weightedRevolutions + stepRevs * factor,
            coldExtraRevolutions = profile.coldExtraRevolutions + coldExtra,
            hotExtraRevolutions = profile.hotExtraRevolutions + hotExtra,
            currentOdometer = profile.currentOdometer + stepMiles,
            timeBelowOperatingTempSec = profile.timeBelowOperatingTempSec + if (isCold) dtSec else 0.0,
            // Seconds with the estimated oil over 130°C. Until this model it counted seconds
            // over 4,500 rpm at high load; a record from then carries a few of those.
            highThermalStressSec = profile.highThermalStressSec +
                if (oilTempC >= GmOilLifeMethod.HOT_OIL_C) dtSec else 0.0,
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

        val minutesOff = profile.lastEngineStopMillis
            ?.let { (clock.nowMillis() - it) / 60_000.0 }
        tripReferenceTempC = GmOilLifeMethod.referenceTempC(coolantTempC, minutesOff)
        tripRevolutions = 0.0
        tripOilWarm = false
        tripColdExtra = 0.0

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
        // A drive that ended with the oil still cold: its cold-oil cost belongs to short
        // trips, which is what the screen calls it. Same total, different label.
        if (!tripOilWarm && tripColdExtra > 0.0) {
            profile = profile.copy(
                coldExtraRevolutions = max(0.0, profile.coldExtraRevolutions - tripColdExtra),
                shortTripExtraRevolutions = profile.shortTripExtraRevolutions + tripColdExtra,
            )
        }
        profile = profile.copy(lastEngineStopMillis = clock.nowMillis())
        tripReferenceTempC = null
        tripRevolutions = 0.0
        tripOilWarm = false
        tripColdExtra = 0.0
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
            estimatedMilesRemaining = GmOilLifeMethod.MAX_INTERVAL_MILES.toInt(),
            // No history yet, so no rate to project from. It fills in once there is one.
            estimatedDaysRemaining = null,
            oilConditionGrade = OilConditionGrade.EXCELLENT,
            degradationBreakdown = DegradationBreakdown(0.0, 0.0, 0.0, 0.0),
            odometerSynced = profile.odometerSynced,
            lastEngineStopMillis = profile.lastEngineStopMillis,
        )
        saveProfile()
        return profile
    }

    /**
     * Ties the oil record's odometer to the one on the dashboard.
     *
     * The record's odometer started from a seeded base and has only ever advanced by the
     * miles this app watched, so it is wrong by a constant plus every mile driven with the
     * phone elsewhere. A reading typed in with a fuel receipt is the car's own figure.
     *
     * The reset odometer moves by the same amount, on purpose. Miles driven since the oil
     * change is [OilLifeProfile.currentOdometer] minus [OilLifeProfile.lastResetOdometer],
     * and it is divided into the wear that was measured - which only covers the miles that
     * were watched. Counting the unwatched ones on top would stretch the projected interval
     * with fuel and revolutions nobody counted, and that is the optimistic direction.
     */
    fun syncOdometer(dashOdometer: Double) {
        if (!dashOdometer.isFinite() || dashOdometer <= 0.0) return
        val delta = dashOdometer - profile.currentOdometer
        profile = profile.copy(
            lastResetOdometer = profile.lastResetOdometer + delta,
            currentOdometer = dashOdometer,
            odometerSynced = true,
        )
        recalculateOilHealth()
        saveProfile()
    }

    private fun recalculateOilHealth() {
        // GM's published method (see GmOilLifeMethod): the oil is spent when the
        // temperature-weighted revolutions reach the budget. The penalties are inside the
        // weighting, so nothing is added on top - the model this replaced added per-event
        // percentages to a 14.5M budget and asked for a change after about 4,000 miles of
        // a mostly-freeway commute (Robert, 2026-11-21).
        val budget = GmOilLifeMethod.BUDGET_REVOLUTIONS
        fun percentOfBudget(revs: Double) = revs / budget * 100

        val coldPercent = percentOfBudget(profile.coldExtraRevolutions)
        val shortTripPercent = percentOfBudget(profile.shortTripExtraRevolutions)
        val hotPercent = percentOfBudget(profile.hotExtraRevolutions)
        val basePercent = max(
            0.0,
            percentOfBudget(profile.weightedRevolutions) - coldPercent - shortTripPercent - hotPercent,
        )

        val daysSinceReset =
            max(1.0, (clock.nowMillis() - profile.lastResetTimestamp) / (24.0 * 60 * 60 * 1000))
        // Patent: a year after the change the oil is due whatever the count says.
        val expired = daysSinceReset > GmOilLifeMethod.MAX_AGE_DAYS
        val remainingPercent = if (expired) {
            0.0
        } else {
            max(0.0, min(100.0, 100.0 - percentOfBudget(profile.weightedRevolutions)))
        }

        // Miles remaining: the budget left, divided by what a mile has cost so far.
        //
        // A mile can never cost fewer revolutions than one in fifth gear with warm oil, so
        // that is the floor on the rate. It matters: the app does not see every drive, and a
        // record whose odometer moved further than its revolutions did (1,300 miles on 665k
        // revolutions, once) would otherwise project an interval no oil could last.
        val milesDriven = max(0.0, profile.currentOdometer - profile.lastResetOdometer)
        val observedRevsPerMile =
            if (milesDriven > 50) profile.weightedRevolutions / milesDriven else 0.0
        val revsPerMile = max(GmOilLifeMethod.TOP_GEAR_REVS_PER_MILE, observedRevsPerMile)
        val revsLeft = if (expired) 0.0 else max(0.0, budget - profile.weightedRevolutions)
        val estimatedMilesRemaining = (revsLeft / revsPerMile).roundToLong().toInt()

        // Days remaining, from the daily mileage burn rate - but only once that rate is
        // measured over long enough to be a rate rather than an accident of when the app
        // happened to be running.
        val dailyMileage = milesDriven / daysSinceReset
        val estimatedDaysRemaining = if (daysSinceReset >= MIN_DAYS_FOR_RATE && dailyMileage > 0.1) {
            (estimatedMilesRemaining / dailyMileage).roundToInt()
        } else {
            null
        }

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
                revWearFactor = round1(basePercent),
                coldStartPenalty = round1(coldPercent),
                shortTripPenalty = round1(shortTripPercent),
                thermalShearPenalty = round1(hotPercent),
            ),
            estimatedMilesRemaining = estimatedMilesRemaining,
            estimatedDaysRemaining = estimatedDaysRemaining,
            oilConditionGrade = grade,
        )
    }
}
