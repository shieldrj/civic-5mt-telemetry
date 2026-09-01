package com.shieldrj.civic5mt.core

import kotlin.math.abs
import kotlin.math.max

/**
 * The pump receipt, which is the only fuel figure in this app that is not an inference.
 *
 * Everything else here is derived. Fuel burned is the MAF reading divided by an air-fuel
 * ratio and a density; distance is the car's road-speed PID integrated over time. Both are
 * good, and neither is checked against anything. That was the gap: a MAF that reads a few
 * percent low reports less fuel burned, which raises MPG, which lengthens the range - and the
 * same understated fuel figure is what calibrates gallons-per-percent, which inflates the
 * reserve, which lengthens the range again. One sensor bias, arriving twice, both times in the
 * optimistic direction. Nothing in the app could notice, because nothing in the app had a
 * second opinion.
 *
 * A fill-to-shutoff is that second opinion. Fill to the click, and the gallons the pump
 * charged for are the gallons burned since the last time it was filled to the click - the tank
 * started at the same place and ended at the same place, so whatever went in is what came out.
 * That identity is what makes the receipt a measurement of the engine rather than of the tank,
 * and it is the whole basis of this file.
 *
 * The odometer is the same trick for distance. Two odometer readings a tank apart are the
 * miles the car says it went, against the miles this app integrated from a speed PID that
 * reads in whole km/h and, on most cars, slightly fast. Together the two give a miles-per-
 * gallon figure - odometer delta over pump gallons - that touches none of this app's sensors
 * and is the figure everything else is measured against.
 */
data class FillSample(
    val timestampMillis: Long,
    /** Gallons the pump charged for. */
    val pumpGallons: Double,
    /** Gallons the MAF chain reported burning since the previous fill, as it reported them. */
    val measuredGallons: Double,
    /** Miles this app integrated since the previous fill, as it integrated them. */
    val measuredMiles: Double,
    /**
     * The fuel correction that was already in effect while [measuredGallons] accumulated.
     *
     * Stored so the correction can be divided back out. Without it the estimator would be
     * learning from its own previous answer: a factor of 0.95 makes the next tank's measured
     * gallons 5% smaller, which looks like fresh evidence for another 5% and compounds every
     * fill. [rawGallons] undoes it, so every sample speaks about the sensor rather than about
     * the last correction.
     */
    val fuelFactorInEffect: Double,
    /** The distance correction in effect while [measuredMiles] accumulated. Same reason. */
    val distanceFactorInEffect: Double,
    /** Odometer delta over this tank, when the odometer was read at both ends. */
    val odometerMiles: Double? = null,
) {
    /** [measuredGallons] with the correction of the day removed - what the sensor chain said. */
    val rawGallons: Double
        get() = if (fuelFactorInEffect > 0) measuredGallons / fuelFactorInEffect else measuredGallons

    /** [measuredMiles] with the correction of the day removed. */
    val rawMiles: Double
        get() = if (distanceFactorInEffect > 0) measuredMiles / distanceFactorInEffect else measuredMiles

    /** What this one fill, on its own, says the fuel correction should be. */
    val impliedFuelFactor: Double
        get() = if (rawGallons > 0) pumpGallons / rawGallons else 1.0

    /** What this one fill says the distance correction should be, when the odometer was read. */
    val impliedDistanceFactor: Double?
        get() = odometerMiles?.let { if (rawMiles > 0) it / rawMiles else null }

    /**
     * Miles per gallon for this tank, measured by nothing this app controls.
     *
     * The odometer when there is one, because then neither number is the app's; the app's own
     * distance otherwise, which still has a real pump volume underneath it.
     */
    val verifiedMpg: Double
        get() = if (pumpGallons > 0) (odometerMiles ?: measuredMiles) / pumpGallons else 0.0
}

/** Why a fill did not become a calibration sample. Shown to the driver, so each is specific. */
enum class FillRejection {
    /** The previous fill was not to shutoff, so there is no matching start point to measure from. */
    NO_FULL_FILL_BASELINE,

    /** This fill was not to shutoff. It becomes the baseline for the next one. */
    NOT_FILLED_TO_SHUTOFF,

    /** Too little fuel or too few miles for the division to mean anything. */
    SPAN_TOO_SHORT,

    /** More gallons than the tank holds, or fewer than nothing. A typo, not a measurement. */
    IMPLAUSIBLE_PUMP_GALLONS,

    /** The app tracked nothing across this tank - it was shut, or the adapter was elsewhere. */
    NO_MEASUREMENT,

    /**
     * Pump and sensor disagree by more than a sensor can plausibly be wrong.
     *
     * Rejected rather than adopted. A factor this far out is not a MAF reading low, it is a
     * missed fill or a partial one entered as a full one, and applying it would put a large
     * error into every range figure until the next fill argued it back.
     */
    IMPLAUSIBLE_RATIO,
}

sealed interface FillOutcome {
    /** The fill was measured, and [state] carries the corrections it produced. */
    data class Accepted(val sample: FillSample, val state: FuelCalibrationState) : FillOutcome

    /** The fill was logged but taught nothing. The tank still restarts; only the maths is skipped. */
    data class Rejected(val reason: FillRejection, val state: FuelCalibrationState) : FillOutcome
}

object FuelCalibrationRules {

    /**
     * How many fills the corrections are averaged over.
     *
     * Six is roughly two thousand miles on this car, which is long enough to average out the
     * one thing the method cannot control - where exactly the pump's auto-shutoff trips, which
     * moves by a tenth of a gallon or so with nozzle angle and how hard the tank is breathing.
     * A tenth of a gallon on an eleven-gallon fill is under one percent, and six of them
     * averaged is a quarter of that.
     *
     * It is a window rather than a running total because a MAF drifts as it ages. Old fills
     * describe a sensor that no longer exists.
     */
    const val WINDOW = 6

    /**
     * A fill has to be at least this large to calibrate from.
     *
     * Splash-and-dash fills are where the shutoff scatter lives: the same tenth of a gallon of
     * nozzle luck is one percent of eleven gallons and eight percent of one and a half.
     */
    const val MIN_PUMP_GALLONS = 4.0

    /** And this many miles, so the distance side has something to divide into. */
    const val MIN_MILES = 40.0

    /** Beyond the tank's own capacity, plus a little for a hard-brimmed filler neck. */
    const val MAX_PUMP_GALLONS = CivicSpecs.FUEL_TANK_CAPACITY_GALLONS + 1.5

    /**
     * How far a correction is allowed to move the sensors.
     *
     * A MAF that has drifted, a slightly wrong assumed fuel density and injectors that are not
     * quite what the model thinks together account for a few percent, not twenty. A figure
     * outside this is evidence of a bookkeeping problem - a fill the app never saw, a tank
     * driven with the phone at home - and the honest response to bad bookkeeping is to keep the
     * previous answer rather than to adopt a new wrong one.
     */
    const val MIN_FACTOR = 0.80
    const val MAX_FACTOR = 1.25

    /**
     * How far a single fill may sit from the running correction before it is discarded.
     *
     * Wider than [MIN_FACTOR]..[MAX_FACTOR] is applied to the pooled answer, because one fill
     * carries the shutoff scatter that six averaged do not.
     */
    const val MAX_SINGLE_FILL_FACTOR = 1.40
    const val MIN_SINGLE_FILL_FACTOR = 0.70

    /** Fills needed before the corrections are used at all. */
    const val MIN_SAMPLES_TO_APPLY = 1

    /** Fills needed before the verified MPG is trusted as the baseline for range. */
    const val MIN_SAMPLES_FOR_MPG_BASELINE = 2
}

/**
 * What the fills have taught, and the two corrections that come out of them.
 *
 * Both corrections are pooled ratios - all the pump gallons over all the sensor gallons - and
 * not the average of the per-fill ratios. The difference matters at the sizes involved here: a
 * pooled ratio weights each fill by how much fuel it actually measured, so an eleven-gallon
 * fill counts for what it is worth against a five-gallon one, and the shutoff scatter, which is
 * a fixed fraction of a gallon rather than a fixed percentage, gets divided by the larger
 * total rather than by each fill separately.
 */
data class FuelCalibrationState(
    /** Newest last, capped at [FuelCalibrationRules.WINDOW]. */
    val samples: List<FillSample> = emptyList(),
    /**
     * Whether the last fill logged went to the pump's automatic shutoff.
     *
     * The identity this whole file rests on needs both ends of the tank at the same level, so
     * a fill can only be measured when the one before it was also to the click. A partial fill
     * is not wasted - it becomes the baseline for the next one.
     */
    val lastFillWasFull: Boolean = false,
    /** Odometer at the last fill, when it was given. The start point for the next tank's miles. */
    val lastOdometerMiles: Double? = null,
) {
    /**
     * What the MAF chain's gallons must be multiplied by.
     *
     * One until a fill says otherwise, which is the old behaviour exactly: an uncalibrated app
     * behaves as it did before this file existed.
     */
    val fuelCorrectionFactor: Double
        get() {
            if (samples.size < FuelCalibrationRules.MIN_SAMPLES_TO_APPLY) return 1.0
            val pump = samples.sumOf { it.pumpGallons }
            val raw = samples.sumOf { it.rawGallons }
            if (raw <= 0) return 1.0
            return (pump / raw).coerceIn(FuelCalibrationRules.MIN_FACTOR, FuelCalibrationRules.MAX_FACTOR)
        }

    /**
     * What the integrated road-speed miles must be multiplied by.
     *
     * Only the fills that carried an odometer reading count, so this stays at one for a driver
     * who logs gallons and skips the odometer - the fuel side still calibrates on its own.
     */
    val distanceCorrectionFactor: Double
        get() {
            val withOdo = samples.filter { it.odometerMiles != null }
            if (withOdo.isEmpty()) return 1.0
            val odo = withOdo.sumOf { it.odometerMiles ?: 0.0 }
            val raw = withOdo.sumOf { it.rawMiles }
            if (raw <= 0) return 1.0
            return (odo / raw).coerceIn(FuelCalibrationRules.MIN_FACTOR, FuelCalibrationRules.MAX_FACTOR)
        }

    val calibrated: Boolean
        get() = samples.size >= FuelCalibrationRules.MIN_SAMPLES_TO_APPLY

    val distanceCalibrated: Boolean
        get() = samples.any { it.odometerMiles != null }

    /**
     * Miles per gallon measured entirely outside this app, or null before there are fills.
     *
     * Pooled the same way and for the same reason: total miles over total pump gallons, so a
     * long tank counts for more than a short one.
     */
    val verifiedMpg: Double?
        get() {
            if (samples.isEmpty()) return null
            val gallons = samples.sumOf { it.pumpGallons }
            if (gallons <= 0) return null
            return samples.sumOf { it.odometerMiles ?: it.measuredMiles } / gallons
        }

    /** Whether [verifiedMpg] has enough fills behind it to be the baseline range is built on. */
    val verifiedMpgUsable: Boolean
        get() = samples.size >= FuelCalibrationRules.MIN_SAMPLES_FOR_MPG_BASELINE &&
            (verifiedMpg ?: 0.0) > 10.0

    /** Pump gallons behind [verifiedMpg], so a screen can say how much is behind the figure. */
    val verifiedGallons: Double
        get() = samples.sumOf { it.pumpGallons }

    val verifiedMiles: Double
        get() = samples.sumOf { it.odometerMiles ?: it.measuredMiles }

    /**
     * How much the individual fills disagree with the pooled correction, as a percentage.
     *
     * This is the honest width of every figure downstream. Range is gallons times MPG and both
     * come from here, so a set of fills scattered by three percent cannot produce a distance to
     * empty better than about three percent - six miles in two hundred. Worth showing rather
     * than burying: a driver who wants the last mile should be told which mile is the last one
     * the measurement can actually see.
     *
     * Null with fewer than two fills, because one fill agrees with itself perfectly and saying
     * so would be a claim of precision rather than a measurement of it.
     */
    val spreadPercent: Double?
        get() {
            if (samples.size < 2) return null
            val pooled = fuelCorrectionFactor
            if (pooled <= 0) return null
            val worst = samples.maxOf { abs(it.impliedFuelFactor - pooled) }
            return 100.0 * worst / pooled
        }
}

/** Where the fill history is kept between runs. */
interface FuelCalibrationStore {
    fun load(): FuelCalibrationState?
    fun save(state: FuelCalibrationState)
}

class InMemoryFuelCalibrationStore(
    private var stored: FuelCalibrationState? = null,
) : FuelCalibrationStore {
    override fun load(): FuelCalibrationState? = stored
    override fun save(state: FuelCalibrationState) {
        stored = state
    }
}

/**
 * Turns fill-ups into corrections.
 *
 * Deliberately knows nothing about tanks, senders or driving. It is handed what the app
 * measured across a tank and what the pump said, and it answers with two multipliers. That
 * separation is what keeps the feedback loop honest: [TankTracker] measures the sender against
 * corrected fuel, this measures corrected fuel against the pump, and neither is in a position
 * to confirm its own answer.
 */
class FuelCalibrationEngine(
    private val store: FuelCalibrationStore = InMemoryFuelCalibrationStore(),
    private val clock: MillisClock = SystemMillisClock,
) {
    private var state: FuelCalibrationState = store.load() ?: FuelCalibrationState()

    fun get(): FuelCalibrationState = state

    /** The multiplier to apply to sensor-derived gallons right now. */
    fun fuelFactor(): Double = state.fuelCorrectionFactor

    /** The multiplier to apply to integrated miles right now. */
    fun distanceFactor(): Double = state.distanceCorrectionFactor

    /**
     * Logs a fill and, when it can, learns from it.
     *
     * @param pumpGallons what the pump charged for
     * @param filledToShutoff whether the nozzle was left to click off by itself
     * @param measuredGallons what the app thinks was burned since the previous fill
     * @param measuredMiles what the app thinks was driven since the previous fill
     * @param odometerMiles the odometer now, if it was read. Only useful when the previous
     *   fill's odometer was read too, which is what makes a delta.
     */
    fun recordFill(
        pumpGallons: Double,
        filledToShutoff: Boolean,
        measuredGallons: Double,
        measuredMiles: Double,
        odometerMiles: Double? = null,
    ): FillOutcome {
        val previousOdometer = state.lastOdometerMiles
        val hadFullBaseline = state.lastFillWasFull

        // Whatever happens to the calibration, this fill is the start point for the next tank.
        // Recorded before any rejection returns, because a fill that taught nothing this time
        // is exactly the one that lets the next fill be measured.
        fun settleBaseline(): FuelCalibrationState = state.copy(
            lastFillWasFull = filledToShutoff,
            // Only kept when it can be a start point. A reading that arrives without its
            // partner is not half a measurement, it is a number with nothing to subtract.
            lastOdometerMiles = odometerMiles ?: state.lastOdometerMiles,
        )

        fun reject(reason: FillRejection): FillOutcome {
            state = settleBaseline()
            store.save(state)
            return FillOutcome.Rejected(reason, state)
        }

        if (pumpGallons <= 0 || pumpGallons > FuelCalibrationRules.MAX_PUMP_GALLONS) {
            return reject(FillRejection.IMPLAUSIBLE_PUMP_GALLONS)
        }
        if (!filledToShutoff) return reject(FillRejection.NOT_FILLED_TO_SHUTOFF)
        if (!hadFullBaseline) return reject(FillRejection.NO_FULL_FILL_BASELINE)
        if (measuredGallons <= 0.0 || measuredMiles <= 0.0) return reject(FillRejection.NO_MEASUREMENT)
        if (pumpGallons < FuelCalibrationRules.MIN_PUMP_GALLONS ||
            measuredMiles < FuelCalibrationRules.MIN_MILES
        ) {
            return reject(FillRejection.SPAN_TOO_SHORT)
        }

        // The odometer delta, but only when both ends of it exist. A single reading cannot
        // become a distance, and pairing this fill's odometer with a previous fill that had
        // none would silently measure the wrong span.
        val odometerDelta = if (odometerMiles != null && previousOdometer != null) {
            (odometerMiles - previousOdometer).takeIf { it > FuelCalibrationRules.MIN_MILES }
        } else {
            null
        }

        val sample = FillSample(
            timestampMillis = clock.nowMillis(),
            pumpGallons = pumpGallons,
            measuredGallons = measuredGallons,
            measuredMiles = measuredMiles,
            fuelFactorInEffect = state.fuelCorrectionFactor,
            distanceFactorInEffect = state.distanceCorrectionFactor,
            odometerMiles = odometerDelta,
        )

        val implied = sample.impliedFuelFactor
        if (implied < FuelCalibrationRules.MIN_SINGLE_FILL_FACTOR ||
            implied > FuelCalibrationRules.MAX_SINGLE_FILL_FACTOR
        ) {
            return reject(FillRejection.IMPLAUSIBLE_RATIO)
        }

        val kept = (state.samples + sample).takeLast(FuelCalibrationRules.WINDOW)
        state = state.copy(
            samples = kept,
            lastFillWasFull = true,
            lastOdometerMiles = odometerMiles ?: state.lastOdometerMiles,
        )
        store.save(state)
        return FillOutcome.Accepted(sample, state)
    }

    /**
     * Forgets everything and starts again.
     *
     * For a MAF replacement or a tyre size change, either of which makes every stored fill a
     * measurement of a car that no longer exists.
     */
    fun reset() {
        state = FuelCalibrationState()
        store.save(state)
    }

    fun flush() {
        store.save(state)
    }
}

/** Miles this app measured, corrected by what the odometer said across the logged fills. */
fun correctedMiles(rawMiles: Double, calibration: FuelCalibrationState): Double =
    max(0.0, rawMiles * calibration.distanceCorrectionFactor)
