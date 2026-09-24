package com.shieldrj.civic5mt.core

import kotlin.math.abs
import kotlin.math.max

/**
 * The pump receipt, which is the only fuel figure in this app that is not an inference.
 *
 * Everything else here is derived. Fuel burned is the MAF reading divided by an air-fuel
 * ratio and a density; distance is the road-speed PID integrated over time; the tank level is
 * a float on a wire. All three are good and none of them is checked against anything unless a
 * receipt is.
 *
 * ## What changed, and why
 *
 * The first version of this file compared the pump against the fuel the app had counted since
 * the previous fill. That identity is exact only if the app watched every mile of the tank, and
 * it does not: one drive with the phone elsewhere and the pump reads high against the count,
 * which looks like a MAF reading low. Worse, it had to be typed in at the pump, because the
 * receipt was matched against whatever tank was open when the button was pressed - and the car
 * opens a new tank itself the moment it sees the level rise. Typed in at home, 35 miles later,
 * a receipt was compared against 35 miles of the new tank.
 *
 * So the receipt is now matched against something that cannot move and does not depend on the
 * app being there for every drive: **how far the fuel gauge rose.** Every fill is recorded as a
 * [FillRecord] when the car notices it - the level before, the level after - and the receipt is
 * attached to that record whenever it arrives, hours or days later. Pump gallons over the rise
 * is gallons per percent of gauge, measured by the pump. That one figure calibrates the tank
 * (see [TankState.gallonsPerPercent]) and, through it, the reserve under E.
 *
 * With the gauge measured, the gauge becomes the reference for the MAF. Over the parts of a
 * tank the app did watch, it has both the gauge's fall and the MAF's gallons, step for step
 * ([TankState.observedDropPercent]); gauge fall times gallons-per-percent is the fuel that
 * really went, and its ratio to the MAF's figure is the correction. A missed drive removes
 * both halves of that pair together, so it no longer biases anything.
 *
 * The odometer stays optional and does one job only it can do: odometer miles over pump
 * gallons between two fills to the click is miles per gallon that touches none of this app's
 * sensors, missed drives included.
 */
data class FillRecord(
    /** When the car noticed the fill, or when it was logged by hand. Identifies the record. */
    val detectedAtMillis: Long,
    /** Gauge reading just before the fuel went in. Null when the car never saw it. */
    val levelBefore: Double? = null,
    /** Settled gauge reading after the fill. Null until the car reports one. */
    val levelAfter: Double? = null,
    /** Gallons on the receipt. Null until entered. */
    val pumpGallons: Double? = null,
    /** Whether the nozzle was left to click off. Only matters for the odometer check. */
    val filledToShutoff: Boolean = true,
    /** Odometer at the pump, worked back from a reading taken later if need be. */
    val odometerAtFill: Double? = null,
    /** The driver said there is no receipt for this one, so stop asking. */
    val receiptSkipped: Boolean = false,
    /**
     * The tank this fill brought to an end, as the app watched it. See [ClosedTank].
     * Raw figures - with the corrections of the day divided back out - so the calibration never
     * learns from its own previous answer.
     */
    val spanRawMiles: Double = 0.0,
    val spanRawGallons: Double = 0.0,
    val spanObservedDropPercent: Double = 0.0,
    val spanObservedRawGallons: Double = 0.0,
) {
    /** How far the gauge rose, when both ends were seen. */
    val risePercent: Double?
        get() {
            val before = levelBefore ?: return null
            val after = levelAfter ?: return null
            return after - before
        }

    /** What this receipt, on its own, says one percent of gauge is worth. */
    val gallonsPerPercent: Double?
        get() {
            val pump = pumpGallons ?: return null
            val rise = risePercent ?: return null
            if (pump < FuelCalibrationRules.MIN_PUMP_GALLONS) return null
            if (rise < FuelCalibrationRules.MIN_RISE_PERCENT) return null
            val g = pump / rise
            return g.takeIf { it in TankRules.MIN_GALLONS_PER_PERCENT..TankRules.MAX_GALLONS_PER_PERCENT }
        }

    val awaitingReceipt: Boolean
        get() = pumpGallons == null && !receiptSkipped
}

/**
 * What [TankTracker] hands over when a fill closes a tank.
 *
 * The level before and the watched span - everything about the old tank that the receipt will
 * later be compared with. Taken at the moment of closing because it is gone the instant after.
 */
data class ClosedTank(
    val closedAtMillis: Long,
    val levelBefore: Double?,
    val rawMiles: Double,
    val rawGallons: Double,
    val observedDropPercent: Double,
    val observedRawGallons: Double,
)

/** Why a receipt was not stored. Only one thing can do that now. */
enum class ReceiptRefusal {
    /** More gallons than the tank holds, or none. A typo, not a measurement. */
    IMPLAUSIBLE_PUMP_GALLONS,
}

sealed interface ReceiptOutcome {
    data class Saved(val record: FillRecord, val state: FuelCalibrationState) : ReceiptOutcome
    data class Refused(val reason: ReceiptRefusal, val state: FuelCalibrationState) : ReceiptOutcome
}

object FuelCalibrationRules {

    /** How many recent fills the corrections are drawn from. About two thousand miles. */
    const val WINDOW = 6

    /** How many fill records are kept at all. The rest describe a car that has moved on. */
    const val KEEP = 12

    /**
     * A receipt has to be at least this large to measure the gauge from.
     *
     * The gauge's own resolution is a fixed fraction of a percent, so a small fill divides a
     * small figure by a small figure and the noise lands in the answer.
     */
    const val MIN_PUMP_GALLONS = 4.0

    /** And the gauge has to have risen this far. Thirty percent is about four gallons. */
    const val MIN_RISE_PERCENT = 30.0

    /** Beyond the tank's own capacity, plus a little for a hard-brimmed filler neck. */
    const val MAX_PUMP_GALLONS = CivicSpecs.FUEL_TANK_CAPACITY_GALLONS + 1.5

    /**
     * The gauge has to fall this far while watched before it can check the MAF.
     *
     * Twenty percent is about two and a half gallons, far more than the gauge's resolution.
     */
    const val MIN_WATCHED_DROP_PERCENT = 20.0

    /** How far a correction is allowed to move the MAF. A few percent is real; twenty is not. */
    const val MIN_FACTOR = 0.80
    const val MAX_FACTOR = 1.25

    /** One tank's figure outside this is a bookkeeping problem, not a sensor. */
    const val MIN_SINGLE_FILL_FACTOR = 0.70
    const val MAX_SINGLE_FILL_FACTOR = 1.40

    /** A span needs this many miles before its odometer or economy figure means anything. */
    const val MIN_SPAN_MILES = 40.0

    /** And this many watched miles before its economy stands in for the odometer's. */
    const val MIN_WATCHED_MILES_FOR_MPG = 100.0

    /**
     * How far odometer miles may sit from the app's miles before the gap is missed driving.
     *
     * The road-speed PID reads a couple of percent fast on most cars and never by ten. A tank
     * whose odometer says six percent more than the app counted had a drive the app missed, and
     * learning a distance correction from it would stretch every mile after it.
     */
    const val MIN_DISTANCE_RATIO = 0.96
    const val MAX_DISTANCE_RATIO = 1.06

    /** Economy outside this for a whole tank is a typo in the odometer, not driving. */
    const val MIN_PLAUSIBLE_MPG = 15.0
    const val MAX_PLAUSIBLE_MPG = 60.0

    /**
     * A second detection this soon, with this little driving since, is the same fill.
     *
     * The level crosses the detection threshold more than once while a pump is running, and a
     * receipt logged at the pump opens a record the car then re-detects when it wakes.
     */
    const val SAME_FILL_WINDOW_MILLIS = 6 * 60 * 60 * 1000L
    const val SAME_FILL_MAX_MILES = 2.0

    /**
     * How long a fill waits for its receipt before the app stops asking.
     *
     * Three days is the drive home, the evening and a forgotten day. Longer, and the next fill
     * could arrive while the card still names this one.
     */
    const val RECEIPT_WINDOW_MILLIS = 3 * 24 * 60 * 60 * 1000L

    /**
     * A fill reads as not to the click when the gauge stopped this far short of full.
     *
     * Five percent is over half a gallon - far more than the scatter in where a nozzle clicks
     * off - so a real brim is never mistaken for a part fill.
     */
    const val FULL_TOLERANCE_PERCENT = 5.0
}

/**
 * Every fill the car noticed, with whatever receipts have been attached, and what they teach.
 *
 * Everything below [fills] is derived. Nothing is stored that can be recomputed, which is what
 * lets a receipt arrive late, be corrected, or be skipped without leaving a stale figure behind.
 */
data class FuelCalibrationState(
    /** Oldest first, capped at [FuelCalibrationRules.KEEP]. */
    val fills: List<FillRecord> = emptyList(),
) {
    private val recent: List<FillRecord>
        get() = fills.takeLast(FuelCalibrationRules.WINDOW)

    /** The receipts that measured the gauge, newest last. */
    private val gaugeMeasurements: List<Double>
        get() = fills.mapNotNull { it.gallonsPerPercent }.takeLast(FuelCalibrationRules.WINDOW)

    /**
     * Gallons per percent of gauge, measured by the pump. Null until a receipt has done it.
     *
     * The median, not the mean. One fill whose "before" reading was stale - a drive to the
     * station the app missed - is a real outlier, and a median of three shrugs it off where an
     * average would carry a third of it into every figure.
     */
    val pumpGallonsPerPercent: Double?
        get() = median(gaugeMeasurements)

    /** Receipts that measured the gauge. */
    val gaugeReceiptCount: Int
        get() = gaugeMeasurements.size

    /**
     * How far the receipts disagree about the gauge, as a percentage.
     *
     * The honest width of the tank figure. Null with one receipt, which agrees with itself.
     */
    val spreadPercent: Double?
        get() {
            val all = gaugeMeasurements
            val mid = median(all) ?: return null
            if (all.size < 2 || mid <= 0) return null
            return 100.0 * all.maxOf { abs(it - mid) } / mid
        }

    /**
     * What the MAF's gallons must be multiplied by.
     *
     * The gauge, measured by the pump, is the reference: over each watched stretch, gauge fall
     * times gallons-per-percent is what was burned, and the MAF said [FillRecord.spanObservedRawGallons].
     * Pooled over tanks so a long one counts for more than a short one. One until the gauge has
     * been measured - an uncalibrated app behaves exactly as it did before.
     */
    val fuelCorrectionFactor: Double
        get() {
            val g = pumpGallonsPerPercent ?: return 1.0
            var truth = 0.0
            var maf = 0.0
            for (f in recent) {
                if (f.spanObservedDropPercent < FuelCalibrationRules.MIN_WATCHED_DROP_PERCENT) continue
                if (f.spanObservedRawGallons <= 0) continue
                val burned = g * f.spanObservedDropPercent
                val implied = burned / f.spanObservedRawGallons
                if (implied < FuelCalibrationRules.MIN_SINGLE_FILL_FACTOR ||
                    implied > FuelCalibrationRules.MAX_SINGLE_FILL_FACTOR
                ) {
                    continue
                }
                truth += burned
                maf += f.spanObservedRawGallons
            }
            if (maf <= 0) return 1.0
            return (truth / maf).coerceIn(FuelCalibrationRules.MIN_FACTOR, FuelCalibrationRules.MAX_FACTOR)
        }

    /** Whether [fuelCorrectionFactor] rests on a measurement rather than being the default one. */
    val fuelCalibrated: Boolean
        get() = pumpGallonsPerPercent != null && recent.any {
            it.spanObservedDropPercent >= FuelCalibrationRules.MIN_WATCHED_DROP_PERCENT &&
                it.spanObservedRawGallons > 0
        }

    /** The highest gauge reading any fill reached. Stands in for "full" when judging a brim. */
    private val fullestLevel: Double?
        get() = fills.mapNotNull { it.levelAfter }.maxOrNull()

    /**
     * Whether a fill really went to the click.
     *
     * The driver's word, checked against the gauge: a fill that left it well short of full was
     * not a brim, whatever the box said, and pairing it as one would measure a tank that did
     * not end where it started.
     */
    private fun wasFull(f: FillRecord): Boolean {
        if (!f.filledToShutoff) return false
        val after = f.levelAfter ?: return true
        val full = fullestLevel ?: return true
        if (full < TankRules.FULL_MARK_MIN_PERCENT) return true
        return after >= full - FuelCalibrationRules.FULL_TOLERANCE_PERCENT
    }

    /**
     * Consecutive pairs of fills to the click with an odometer at both ends.
     *
     * Consecutive in the record, so no fill can hide between them: the car notices every fill
     * large enough to matter, and one it noticed without a receipt breaks the pair.
     */
    private val odometerSpans: List<Pair<FillRecord, FillRecord>>
        get() = fills.zipWithNext().filter { (prev, cur) ->
            val pump = cur.pumpGallons ?: return@filter false
            val a = prev.odometerAtFill ?: return@filter false
            val b = cur.odometerAtFill ?: return@filter false
            val miles = b - a
            wasFull(prev) && wasFull(cur) &&
                pump >= FuelCalibrationRules.MIN_PUMP_GALLONS &&
                miles >= FuelCalibrationRules.MIN_SPAN_MILES &&
                miles / pump in FuelCalibrationRules.MIN_PLAUSIBLE_MPG..FuelCalibrationRules.MAX_PLAUSIBLE_MPG
        }.takeLast(FuelCalibrationRules.WINDOW)

    /**
     * Miles per gallon measured entirely outside this app: odometer over pump.
     *
     * Untouched by missed drives - the odometer counted them and the pump paid for them.
     */
    val verifiedMpg: Double?
        get() {
            val spans = odometerSpans
            if (spans.isEmpty()) return null
            val miles = spans.sumOf { (a, b) -> b.odometerAtFill!! - a.odometerAtFill!! }
            val gallons = spans.sumOf { (_, b) -> b.pumpGallons!! }
            return miles / gallons
        }

    /**
     * What the road-speed miles must be multiplied by.
     *
     * Only from tanks where the app plainly saw the whole thing - odometer and app within a few
     * percent. A bigger gap is a missed drive, and missed driving is not a fast speed sensor.
     */
    val distanceCorrectionFactor: Double
        get() {
            var odo = 0.0
            var app = 0.0
            for ((a, b) in odometerSpans) {
                val miles = b.odometerAtFill!! - a.odometerAtFill!!
                if (b.spanRawMiles <= 0) continue
                val ratio = miles / b.spanRawMiles
                if (ratio !in FuelCalibrationRules.MIN_DISTANCE_RATIO..FuelCalibrationRules.MAX_DISTANCE_RATIO) continue
                odo += miles
                app += b.spanRawMiles
            }
            return if (app > 0) odo / app else 1.0
        }

    val distanceCalibrated: Boolean
        get() = distanceCorrectionFactor != 1.0

    /**
     * Economy over the driving the app watched, with both corrections applied.
     *
     * The fallback for a driver who skips the odometer. Both halves come from the same watched
     * miles, so a missed drive shortens the sample without biasing it.
     */
    val watchedMpg: Double?
        get() {
            if (!fuelCalibrated) return null
            val spans = recent.filter { it.spanRawMiles > 0 && it.spanRawGallons > 0 }
            val miles = spans.sumOf { it.spanRawMiles }
            if (miles < FuelCalibrationRules.MIN_WATCHED_MILES_FOR_MPG) return null
            val gallons = spans.sumOf { it.spanRawGallons }
            return (miles * distanceCorrectionFactor) / (gallons * fuelCorrectionFactor)
        }

    /** The best economy figure the receipts can give: the odometer's, else the watched one. */
    val economyMpg: Double?
        get() = verifiedMpg ?: watchedMpg

    /** Receipts entered at all, measured or not. */
    val receiptCount: Int
        get() = fills.count { it.pumpGallons != null }

    /** Gallons across the receipts, for saying how much stands behind a figure. */
    val receiptGallons: Double
        get() = fills.sumOf { it.pumpGallons ?: 0.0 }

    /** Whether the gauge has been measured by the pump. */
    val calibrated: Boolean
        get() = pumpGallonsPerPercent != null

    /** The last fill, when it is still waiting for its receipt. See [FuelCalibrationRules.RECEIPT_WINDOW_MILLIS]. */
    fun pendingReceipt(nowMillis: Long): FillRecord? {
        val last = fills.lastOrNull() ?: return null
        if (!last.awaitingReceipt) return null
        if (nowMillis - last.detectedAtMillis > FuelCalibrationRules.RECEIPT_WINDOW_MILLIS) return null
        return last
    }

    companion object {
        private fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            val mid = s.size / 2
            return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
        }
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
 * Keeps the fill record and attaches receipts to it.
 *
 * Knows nothing about sensors or driving. [TankTracker] tells it when a fill closed a tank and
 * where the gauge settled; the driver tells it what the pump said. Everything else is derived
 * in [FuelCalibrationState].
 */
class FuelCalibrationEngine(
    private val store: FuelCalibrationStore = InMemoryFuelCalibrationStore(),
    private val clock: MillisClock = SystemMillisClock,
) {
    private var state: FuelCalibrationState = store.load() ?: FuelCalibrationState()

    fun get(): FuelCalibrationState = state

    fun fuelFactor(): Double = state.fuelCorrectionFactor

    fun distanceFactor(): Double = state.distanceCorrectionFactor

    /**
     * Records a fill the car just noticed.
     *
     * Returns false when it was the same fill noticed again - a level still climbing at the
     * pump, or a car waking up after the receipt was already logged by hand - in which case the
     * existing record is kept, because it holds the true "before" reading.
     */
    fun onFillDetected(closed: ClosedTank): Boolean {
        val last = state.fills.lastOrNull()
        if (last != null &&
            closed.rawMiles < FuelCalibrationRules.SAME_FILL_MAX_MILES &&
            closed.closedAtMillis - last.detectedAtMillis in 0..FuelCalibrationRules.SAME_FILL_WINDOW_MILLIS
        ) {
            return false
        }
        val record = FillRecord(
            detectedAtMillis = closed.closedAtMillis,
            levelBefore = closed.levelBefore,
            spanRawMiles = closed.rawMiles,
            spanRawGallons = closed.rawGallons,
            spanObservedDropPercent = closed.observedDropPercent,
            spanObservedRawGallons = closed.observedRawGallons,
        )
        commit(state.copy(fills = (state.fills + record).takeLast(FuelCalibrationRules.KEEP)))
        return true
    }

    /**
     * Where the gauge has settled since the last fill.
     *
     * Only ever raised, and only by a meaningful amount so the file is not rewritten every tick:
     * the level after a fill is the highest it reaches, and it climbs for a minute or so as the
     * float settles.
     */
    fun updateLevelAfter(levelPercent: Double) {
        val last = state.fills.lastOrNull() ?: return
        // Not until the gauge has actually risen. A receipt logged at the pump opens the record
        // before the engine is started, when the only level to hand is the one from before.
        last.levelBefore?.let { if (levelPercent <= it + 1.0) return }
        val current = last.levelAfter
        if (current != null && levelPercent < current + 0.1) return
        replaceLast(last.copy(levelAfter = levelPercent))
    }

    /**
     * Attaches a receipt to the last fill.
     *
     * @param odometerAtFill the odometer at the pump. The caller works it back from a reading
     *   taken later, since the driver is usually home by the time they type it.
     */
    fun attachReceipt(
        pumpGallons: Double,
        filledToShutoff: Boolean,
        odometerAtFill: Double? = null,
    ): ReceiptOutcome {
        if (pumpGallons <= 0 || pumpGallons > FuelCalibrationRules.MAX_PUMP_GALLONS) {
            return ReceiptOutcome.Refused(ReceiptRefusal.IMPLAUSIBLE_PUMP_GALLONS, state)
        }
        val last = state.fills.lastOrNull()
            ?: return ReceiptOutcome.Refused(ReceiptRefusal.IMPLAUSIBLE_PUMP_GALLONS, state)
        val updated = last.copy(
            pumpGallons = pumpGallons,
            filledToShutoff = filledToShutoff,
            odometerAtFill = odometerAtFill ?: last.odometerAtFill,
            receiptSkipped = false,
        )
        replaceLast(updated)
        return ReceiptOutcome.Saved(updated, state)
    }

    /** The driver has no receipt for the last fill. Stop asking for it. */
    fun skipReceipt() {
        val last = state.fills.lastOrNull() ?: return
        if (last.pumpGallons != null) return
        replaceLast(last.copy(receiptSkipped = true))
    }

    /** Forgets everything. For a MAF replacement or a tyre size change. */
    fun reset() {
        commit(FuelCalibrationState())
    }

    fun flush() {
        store.save(state)
    }

    private fun replaceLast(record: FillRecord) {
        commit(state.copy(fills = state.fills.dropLast(1) + record))
    }

    private fun commit(next: FuelCalibrationState) {
        state = next
        store.save(state)
    }
}

/** Miles this app measured, corrected by what the odometer said across the logged fills. */
fun correctedMiles(rawMiles: Double, calibration: FuelCalibrationState): Double =
    max(0.0, rawMiles * calibration.distanceCorrectionFactor)
