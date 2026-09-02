package com.shieldrj.civic5mt.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * One tank of fuel: how far it went, and how much is left.
 *
 * This is the figure a driver checks. Instant MPG is already on the dash, and it changes
 * every second, so it answers nothing. "How am I doing on this tank" is a question with one
 * answer, and it only changes slowly.
 *
 * The range figure had two faults, and they had different causes.
 *
 * **It swung wildly.** Range was the tank level multiplied by a 30-second average MPG. A
 * 30-second average is not an economy figure - it is a record of the last hill. Going up one
 * halved the range and coming down doubled it. Range now uses the average for the whole tank,
 * which changes by a fraction of an MPG per mile.
 *
 * **A full tank read 93%.** The fuel sender is not a measuring jug. Its scale is whatever the
 * float and the tank shape produce, and no car makes that a straight line from 0 to 100. So
 * this does not convert percent to gallons with a fixed number. It measures the conversion:
 * fuel burned, divided by percent dropped, is gallons per percent, and that comes from this
 * car's own sender rather than from a specification.
 */
data class TankState(
    /** When the fill was seen. */
    val fillTimestamp: Long = 0L,
    /**
     * Highest sender reading seen since the fill, in percent.
     *
     * The highest, not the first. A fill is detected as soon as the level has risen five
     * percent, which is part way up the rise - the pump is still running. Recording that
     * moment as the fill level put the tank at 35% when it was on its way to 93%.
     *
     * A record of the tank rather than an input to anything. It used to be one half of the
     * calibration - this minus [lowestLevelPercent] was the span the fuel was divided by -
     * and that is exactly what made the calibration wrong, because the span included every
     * mile driven with the app shut and the fuel figure did not. [observedDropPercent] is
     * the span now.
     */
    val levelPercentAtFill: Double = 0.0,
    val milesSinceFill: Double = 0.0,
    val gallonsUsedSinceFill: Double = 0.0,
    /**
     * Gallons per one percent of sender reading, measured on this car.
     *
     * Starts at [CivicSpecs.NOMINAL_GALLONS_PER_SENDER_PERCENT] - the sender's real span,
     * not the tank's - and is replaced by a measured one as soon as a tank has been run down
     * far enough to measure it.
     *
     * It used to start at tank capacity divided by a hundred, which quietly asserted that the
     * sender's zero is an empty tank. It is not, and the consequence landed in
     * [reserveGallons]: 0.92 gallons under the sender's zero against Honda's published 1.9.
     */
    val gallonsPerPercent: Double = CivicSpecs.NOMINAL_GALLONS_PER_SENDER_PERCENT,
    /** Whether [gallonsPerPercent] has been measured, or is still the nominal figure. */
    val calibrated: Boolean = false,
    /** Sender reading, smoothed. Raw readings move with fuel sloshing in the tank. */
    val smoothedLevelPercent: Double = 0.0,
    /** Lowest smoothed reading since the fill, which is what a rise is measured against. */
    val lowestLevelPercent: Double = 100.0,
    /**
     * The highest smoothed sender reading this car has ever shown, across every tank.
     *
     * This is the "full" mark, and it is what lets a percentage be honest. The sender's scale
     * is not a fuel gauge: on this car it stops around 93 with the tank brimmed, and it reads
     * 0 with fuel still in the tank. Neither end is where it claims to be.
     *
     * The top end is measurable, and this is the measurement. A reading this high has only
     * ever happened with a full tank, so it is the point worth a whole
     * [CivicSpecs.FUEL_TANK_CAPACITY_GALLONS]. The bottom end then follows from it - see
     * [reserveGallons] - instead of being assumed to be zero, which is the assumption that
     * made the old percentage wrong at both ends.
     *
     * It only ever rises. A tank that was not quite filled cannot lower it, and a fuller one
     * later corrects it.
     */
    val fullMarkPercent: Double = 0.0,
    /**
     * Sender percent that dropped *while the app was watching it*, since the fill.
     *
     * Not the same as the level at the fill minus the level now, and the difference is the
     * whole reason this field exists. The app is not running for most of a tank - it is
     * launched for some drives and not for others - so the sender falls whether or not
     * anything is counting the fuel that made it fall. Measuring gallons-per-percent as
     * [gallonsUsedSinceFill] over that whole span divides fuel the app *did* see by percent
     * it mostly *did not*, and the answer comes out low in exact proportion to how much of
     * the tank went unwatched.
     *
     * This is the paired figure: it only advances on the same steps [observedGallons] does,
     * so a gap in one is a gap in the other and the ratio survives it.
     */
    val observedDropPercent: Double = 0.0,
    /** Fuel burned on the steps counted in [observedDropPercent], and only those. */
    val observedGallons: Double = 0.0,
) {
    /**
     * Miles per gallon for this tank, or null before there is enough to divide.
     *
     * Null rather than a large number: half a mile after a fill, distance over fuel is
     * arithmetic on rounding error.
     */
    val tankMpg: Double?
        get() = if (gallonsUsedSinceFill >= TankRules.MIN_GALLONS_FOR_MPG) {
            roundTo(milesSinceFill / gallonsUsedSinceFill, 1)
        } else {
            null
        }

    /**
     * Fuel still in the tank when the sender reads zero.
     *
     * There is some, and that is the whole point of this figure. The gauge reaches E with a
     * usable amount left - anyone who drives this car knows it - and multiplying the sender's
     * percent by a gallons-per-percent figure denies it, because that arithmetic puts zero
     * fuel at zero percent by construction.
     *
     * It is derived, not looked up in a manual. Two things are known about this particular
     * car: a full tank holds [CivicSpecs.FUEL_TANK_CAPACITY_GALLONS], and a full tank reads
     * [fullMarkPercent] on its own sender. One percent of sender is worth [gallonsPerPercent]
     * gallons, measured from fuel this car actually burned, so the sender's whole span only
     * accounts for that many gallons times that many percent. Whatever the tank holds beyond
     * that is sitting below the sender's zero.
     *
     * Guarded twice. A mark that was never a full tank would inflate this, so anything below
     * [TankRules.FULL_MARK_MIN_PERCENT] is not treated as full at all and the reserve stays
     * at zero - the old behaviour, which understates but does not invent. And the answer is
     * capped: a reserve larger than [TankRules.MAX_RESERVE_GALLONS] means the slope
     * measurement was off, not that the tank is bigger than Honda built it.
     */
    val reserveGallons: Double
        get() {
            if (fullMarkPercent < TankRules.FULL_MARK_MIN_PERCENT) return 0.0
            val accountedForBySender = gallonsPerPercent * fullMarkPercent
            return (CivicSpecs.FUEL_TANK_CAPACITY_GALLONS - accountedForBySender)
                .coerceIn(0.0, TankRules.MAX_RESERVE_GALLONS)
        }

    /** Gallons in the tank now: the sender's span, measured, plus what sits below its zero. */
    val gallonsRemaining: Double
        get() = (gallonsPerPercent * smoothedLevelPercent + reserveGallons)
            .coerceIn(0.0, CivicSpecs.FUEL_TANK_CAPACITY_GALLONS)

    /**
     * How much of a tankful is left, as a share of what the tank really holds.
     *
     * Deliberately not the sender reading. That number is on the dashboard already and it is
     * wrong at both ends: 93 with the tank brimmed, 0 with a couple of gallons still in it.
     * This one reads 100 standing at the pump, which is the only version of the question a
     * driver can act on.
     *
     * It does not go on to reach zero. It cannot: the sender stops at its own zero with the
     * reserve still in the tank, so this bottoms out at the reserve's share of a tankful -
     * about seven percent - and stays there until the car stops. That is a limit of what a
     * sender can be asked, not an oversight, and [belowSenderZero] is how the screens are
     * told to stop presenting it as a live reading.
     */
    val fuelPercentRemaining: Double
        get() = 100.0 * gallonsRemaining / CivicSpecs.FUEL_TANK_CAPACITY_GALLONS

    /**
     * True once the sender has bottomed out and every figure above has stopped moving.
     *
     * All of this is a reading of the sender, so when the sender runs out of things to say,
     * so does this. Below its zero there is still fuel - that is what [reserveGallons] is -
     * but nothing measures it going down. [gallonsRemaining] sits at the reserve,
     * [fuelPercentRemaining] sits at the reserve's share of the tank, and distance to empty
     * sits at that many gallons times the economy. Someone watching the number can drive for
     * half an hour and see it say the same thing the whole way.
     *
     * The arithmetic cannot be fixed, because there is no measurement down there to fix it
     * with. So the display is told instead, and prints these as bounds rather than readings:
     * under seven percent, under thirty miles. Both are true, and neither invites anyone to
     * plan the next thirty miles around it.
     *
     * False on a car whose sender really does reach zero. There, zero percent is a
     * measurement like any other and there is no reserve hiding underneath it.
     */
    val belowSenderZero: Boolean
        get() = reserveGallons > 0.0 && smoothedLevelPercent <= TankRules.SENDER_FLOOR_PERCENT
}

/**
 * Drops a stored calibration that cannot be reconciled with the stored full mark.
 *
 * Read once, when a tank is loaded, and it exists because a bad figure is durable. Gallons
 * per percent is measured at a fill and then kept, so a tank measured wrongly - or measured
 * correctly by an older version that did not know to discount the miles it had not seen -
 * goes on being applied to every reading for as long as the record survives. On the car this
 * was found on, a stored 0.0835 had been reporting a full tank as 74% across several fills,
 * and would have gone on doing so after the measurement itself was fixed.
 *
 * Falls back to the nominal figure and clears [TankState.calibrated], which is the state the
 * app would have been in had the bad tank never been measured. The next fill measures again.
 */
fun TankState.withUsableCalibration(): TankState {
    // Nothing to distrust in a figure that never claimed to be measured.
    if (!calibrated) return this
    val usable = TankRules.reconcileGallonsPerPercent(gallonsPerPercent, fullMarkPercent)
        ?: return copy(
            gallonsPerPercent = CivicSpecs.NOMINAL_GALLONS_PER_SENDER_PERCENT,
            calibrated = false,
        )
    return if (usable == gallonsPerPercent) this else copy(gallonsPerPercent = usable)
}

object TankRules {

    /**
     * A rise of this many percent counts as a fill.
     *
     * Requiring a 10% rise prevents cornering/hill sloshing from triggering false tank resets.
     */
    const val FILL_RISE_PERCENT = 10.0

    /**
     * The sender must fall this far *while being watched* before gallons-per-percent is
     * measured from it. See [TankState.observedDropPercent].
     *
     * A short span divides a small fuel figure by a small percent figure, and the error in
     * both lands in the answer. Twenty percent of a tank is roughly 2.6 gallons, which is far
     * larger than anything the sender's own resolution can contribute.
     *
     * Twenty watched points is a good deal more driving than twenty points of tank, and on a
     * car the app is not running in all the time some tanks will not reach it. Those tanks
     * measure nothing and the nominal figure stays, which is the right outcome: a full tank
     * still reads 100% on the nominal figure, and [TankState.calibrated] says plainly that
     * nothing has been measured yet.
     */
    const val MIN_DROP_FOR_CALIBRATION = 20.0

    /** Below this, distance over fuel is not yet an economy figure (~3 miles of driving). */
    const val MIN_GALLONS_FOR_MPG = 0.10

    /**
     * How fast the smoothed level follows the sender.
     *
     * Sixty seconds. Fuel moves in the tank on corners, hills and braking, and the sender
     * reports the float, not the fuel. A tank level genuinely changes over tens of minutes,
     * so nothing is lost by ignoring anything faster.
     */
    const val LEVEL_TIME_CONSTANT_SEC = 60.0

    /**
     * How fast the smoothed level follows the sender while the tank is being filled.
     *
     * Sixty-second smoothing is right for fuel being used and wrong for fuel being added. A
     * fill is a step change, and easing into it over four minutes means the range reads low
     * at the exact moment someone checks it - standing at the pump having just filled up.
     */
    const val FILL_TIME_CONSTANT_SEC = 5.0

    /**
     * How long the sender must stay above the smoothed level before that counts as filling.
     *
     * This is what separates a pump from a roundabout. Fuel sloshing throws the float up for
     * a second or two and then back down by the same amount; a pump holds it up and keeps
     * pushing. Without the wait, the fast tracking would follow every upward slosh and ignore
     * every downward one, and the level would drift up all day.
     */
    const val SUSTAINED_RISE_SEC = 10.0

    /**
     * Gallons per percent has to stay inside the physically possible.
     *
     * These are the loose bounds, and they only apply before a full tank has ever been seen.
     * Once there is a full mark, [physicalGallonsPerPercent] is far tighter and is derived
     * rather than chosen, so it is what actually does the work - which matters, because the
     * nominal figure for this car is about 0.1215 and these bounds cheerfully admit 0.0835.
     */
    const val MIN_GALLONS_PER_PERCENT = 0.08
    const val MAX_GALLONS_PER_PERCENT = 0.20

    /**
     * How far outside the physically possible a measurement may land and still be believed.
     *
     * Not zero, because the bounds below are exact and a measurement never is. It comes from
     * a smoothed level and an integrated fuel figure, either of which can be a little out,
     * and refusing a reading that misses a hard edge by a hair would throw away good tanks.
     *
     * Half a hundredth of a gallon per sender point is about half a gallon across a whole
     * tank: comfortably more than the measurement's own noise, and nowhere near enough to
     * let a genuinely broken figure through. The one that caused this - 0.083 against a
     * floor of 0.120 - misses by seven times this.
     */
    const val CALIBRATION_TOLERANCE_GALLONS_PER_PERCENT = 0.005

    /**
     * The gallons-per-percent figures that can be reconciled with a full tank, or null
     * before a full mark exists to reconcile against.
     *
     * This is the guard that was missing, and it is not a chosen range - it follows from two
     * figures already here. A full tank holds [CivicSpecs.FUEL_TANK_CAPACITY_GALLONS] and
     * reads [fullMarkPercent] on the sender, so the sender's span accounts for
     * `gallonsPerPercent * fullMarkPercent` gallons and the rest is [TankState.reserveGallons].
     * That reserve cannot be negative, and it cannot exceed [MAX_RESERVE_GALLONS]. Those two
     * statements are the two ends of this range, rearranged.
     *
     * What makes it worth deriving rather than guessing is the invariant it buys. For any
     * figure inside this range the reserve is not clamped, so the gallons at a full tank come
     * to `g * full + (capacity - g * full)` - the calibration cancels, exactly, and a brimmed
     * tank reads 100% whatever `g` turned out to be. Outside the range the clamp bites, the
     * cancellation fails, and a full tank reads low: the reported fault was a stored 0.0835
     * against a floor of 0.120, which put a freshly filled tank at 74%.
     */
    fun physicalGallonsPerPercent(fullMarkPercent: Double): ClosedFloatingPointRange<Double>? {
        if (fullMarkPercent < FULL_MARK_MIN_PERCENT) return null
        val capacity = CivicSpecs.FUEL_TANK_CAPACITY_GALLONS
        return ((capacity - MAX_RESERVE_GALLONS) / fullMarkPercent)..(capacity / fullMarkPercent)
    }

    /**
     * Takes a gallons-per-percent figure and returns one that can be used, or null to refuse.
     *
     * Refusing is the important half. A figure that cannot be reconciled with a full tank is
     * not a slightly-off calibration to be nudged into shape, it is evidence that the tank it
     * came from was not fully measured, and the honest answer is to keep the nominal figure
     * and say so via [TankState.calibrated] rather than to print a confident wrong number.
     *
     * Inside the tolerance the value is pulled onto the edge instead. That is not inventing a
     * measurement: it is a real one carrying real noise, and the edges are hard physical
     * facts - a reserve below zero or above two gallons does not exist in this car.
     */
    fun reconcileGallonsPerPercent(perPercent: Double, fullMarkPercent: Double): Double? {
        val physical = physicalGallonsPerPercent(fullMarkPercent)
            ?: return perPercent.takeIf {
                it in MIN_GALLONS_PER_PERCENT..MAX_GALLONS_PER_PERCENT
            }
        val slack = CALIBRATION_TOLERANCE_GALLONS_PER_PERCENT
        if (perPercent < physical.start - slack) return null
        if (perPercent > physical.endInclusive + slack) return null
        return perPercent.coerceIn(physical)
    }

    /**
     * Two sender readings further apart in time than this are not consecutive.
     *
     * The span between them is measured as the fall from one raw reading to the next, which
     * is only fuel if nothing happened in between. Thirty seconds is many times the loop's
     * 80ms tick, so this catches a real interruption - the link dropping, the phone sleeping -
     * rather than a slow tick.
     */
    const val MAX_CONTIGUOUS_STEP_SEC = 30.0

    /**
     * How long after the level stops being pushed up before measuring resumes.
     *
     * Fuel going in is not fuel coming out, and the moment the pump stops is not the moment
     * the float settles. A minute of quiet before counting again costs nothing on a tank that
     * takes a fortnight and keeps the end of a fill out of the measurement.
     */
    const val SETTLE_AFTER_FILL_SEC = 60.0

    /**
     * A sender reading has to reach this before it counts as a full tank.
     *
     * The mark is what pins the top of the scale to a known volume, so a reading taken part
     * way up is the one thing that can quietly ruin the percentage. Eighty-eight is chosen to
     * sit just under this car's real brimmed reading of about 93: high enough that a
     * half-filled tank cannot reach it, low enough that a sender reading a little lower than
     * expected still gets recognised.
     */
    const val FULL_MARK_MIN_PERCENT = 88.0

    /**
     * The most fuel that may be claimed to sit below the sender's zero.
     *
     * Two gallons is just above what this car actually holds down there, which is the right
     * place for a cap: it admits the real figure and rejects anything that is not one. Honda's
     * manual puts 1.9 US gal in the tank when the low fuel light comes on, and owners who ran
     * a 9th-gen to a zero range reading and then filled to the click pumped about 11.5 of the
     * 13.2 gallons, leaving 1.7. See [CivicSpecs.FUEL_RESERVE_BELOW_SENDER_ZERO_GALLONS].
     *
     * Anything larger is not a reserve, it is a bad gallons-per-percent measurement arriving
     * by another route, and a range figure inflated by it is exactly the mistake nobody can
     * afford here.
     */
    const val MAX_RESERVE_GALLONS = 2.0

    /**
     * At or below this sender reading, the sender counts as having bottomed out.
     *
     * Not exactly zero, for two reasons. The reading arrives as one byte and steps about four
     * tenths of a percent at a time, so the last step above empty is already inside the
     * sender's own resolution. And the smoothed level approaches zero without ever arriving,
     * being an exponential average - waiting for a true zero would wait forever.
     *
     * One percent is a tenth of a gallon, or about three miles. Nothing worth having is lost
     * by rounding it away.
     */
    const val SENDER_FLOOR_PERCENT = 1.0

    /**
     * A lifetime figure needs this many real miles behind it before range leans on it.
     */
    const val MIN_LIFETIME_MILES_FOR_RANGE = 20.0

    /**
     * How much fuel a tank's own average has to have behind it before range trusts it fully.
     *
     * Two gallons is roughly sixty-five miles of driving. Below that, "this tank" is not a
     * tank average at all - it is the drive to work, and it says whatever that drive was.
     */
    const val TANK_MPG_FULL_WEIGHT_GALLONS = 2.0

    /** How fast the displayed distance to empty follows the computed one. See [RangeDamper]. */
    const val RANGE_TIME_CONSTANT_SEC = 180.0

    /** A rise this large is a fill, and is shown at once rather than eased into. */
    const val RANGE_SNAP_MILES = 25.0

    /**
     * Which economy figure range should be based on.
     *
     * This tank first, because it describes this fuel in this car in this weather. Then the
     * lifetime average, once there is enough of it to be an average. Then the EPA rating,
     * which is a real figure for this model and is right within a few miles per gallon.
     *
     * The tank figure fades in rather than switching on, and that is the fix for the
     * remaining swing. It used to take over the moment a tenth of a gallon had been burned -
     * three miles after a fill - so range stopped being twelve gallons times a settled
     * lifetime average and became twelve gallons times whatever the drive out of the filling
     * station happened to be. One cold morning and the number moved eighty miles, having
     * learned nothing. Weighting it by the fuel actually behind it means the first few miles
     * of a tank barely move the answer and a tank halfway through owns it outright.
     */
    fun mpgForRange(
        tankMpg: Double?,
        tankGallonsUsed: Double,
        lifetimeMpg: Double,
        lifetimeMiles: Double,
        verifiedMpg: Double? = null,
    ): Double {
        val baseline = when {
            // Odometer miles over pump gallons. Nothing in this app measured either one, which
            // is exactly why it outranks the lifetime figure: the lifetime average is a very
            // long integration of the same MAF chain that the fills exist to check. Once the
            // corrections are applied the two converge, and that convergence is the evidence
            // the calibration is working rather than a coincidence to lean on.
            verifiedMpg != null && verifiedMpg > 10.0 -> verifiedMpg
            lifetimeMpg > 10.0 && lifetimeMiles >= MIN_LIFETIME_MILES_FOR_RANGE -> lifetimeMpg
            else -> CivicSpecs.EPA_COMBINED_MPG_DEFAULT
        }
        if (tankMpg == null || tankMpg <= 10.0 || tankMpg >= 65.0) return baseline

        val weight = (tankGallonsUsed / TANK_MPG_FULL_WEIGHT_GALLONS).coerceIn(0.0, 1.0)
        return baseline + (tankMpg - baseline) * weight
    }
}

/** Where the current tank is kept between runs. */
interface TankStore {
    fun load(): TankState?
    fun save(state: TankState)
}

class InMemoryTankStore(private var stored: TankState? = null) : TankStore {
    override fun load(): TankState? = stored
    override fun save(state: TankState) {
        stored = state
    }
}

class TankTracker(
    private val store: TankStore = InMemoryTankStore(),
    private val clock: MillisClock = SystemMillisClock,
) {
    private var state: TankState = (store.load() ?: TankState()).withUsableCalibration()
    private var lastSaveAt: Long = 0L
    private var started = false

    /** How long the sender has been reading well above the smoothed level. */
    private var risingForSec: Double = 0.0

    /**
     * The previous raw sender reading, or null when there isn't one to compare against.
     *
     * The span is measured from this rather than from the smoothed level, and that choice is
     * the fix. A smoothed level carries the past in it: coming back to a car driven without
     * the app, it spends minutes easing down from where it was left to where the fuel
     * actually is, and every one of those minutes looks like a fast-draining tank - a large
     * fall in percent against almost no fuel. Raw readings carry nothing. The fall from one
     * to the next is fuel or it is noise, and noise cancels over a tank because the falls are
     * added signed.
     *
     * Null at the start of every run, which is exactly where the seam is: whatever the sender
     * did while the app was shut happened between two readings this never saw together.
     */
    private var lastRawLevel: Double? = null

    /** How long since the sender was last being pushed up. See [TankRules.SETTLE_AFTER_FILL_SEC]. */
    private var settledForSec: Double = 0.0

    fun get(): TankState = state

    /**
     * One step of real driving.
     *
     * @param levelPercent the sender reading, or null on a car that does not report one
     * @param milesStep miles covered in this step
     * @param gallonsStep fuel burned in this step
     * @param dtSec length of the step
     */
    fun record(levelPercent: Double?, milesStep: Double, gallonsStep: Double, dtSec: Double) {
        if (levelPercent == null) return
        if (dtSec <= 0) return

        if (!started) {
            started = true

            if (state.fillTimestamp == 0L) {
                // Nothing stored: a new install, or the first drive. Take the sender as it
                // stands rather than easing up to it from zero, which would report an empty
                // tank for the first few minutes.
                state = state.copy(
                    fillTimestamp = clock.nowMillis(),
                    levelPercentAtFill = levelPercent,
                    smoothedLevelPercent = levelPercent,
                    lowestLevelPercent = levelPercent,
                )
                store.save(state)
                lastSaveAt = clock.nowMillis()
                return
            }

            // A fill that happened while the app was shut. This is the normal case, not an
            // edge one: nobody fills a tank with the engine running, so the app is never
            // watching when the level goes up. Without this the old tank's miles and gallons
            // carry on into the new one, and the two get averaged into a figure describing
            // neither.
            if (levelPercent - state.smoothedLevelPercent >= TankRules.FILL_RISE_PERCENT) {
                startNewTank(levelPercent, snapLevel = levelPercent)
                return
            }
            // Otherwise the tank is where it was left. Carry on from the stored figures.
        }

        val above = levelPercent - state.smoothedLevelPercent
        risingForSec = if (above > TankRules.FILL_RISE_PERCENT) risingForSec + dtSec else 0.0

        val timeConstant = if (risingForSec >= TankRules.SUSTAINED_RISE_SEC) {
            TankRules.FILL_TIME_CONSTANT_SEC
        } else {
            TankRules.LEVEL_TIME_CONSTANT_SEC
        }

        val alpha = 1 - exp(-dtSec / timeConstant)
        val smoothed = state.smoothedLevelPercent + above * alpha

        // Whether this step may be measured from. Two readings taken far enough apart that
        // anything could have happened between them are not a measurement, and neither is a
        // fill or the minute after one.
        settledForSec = if (risingForSec > 0.0) 0.0 else settledForSec + dtSec
        val previousRaw = lastRawLevel
        lastRawLevel = levelPercent
        val measurable = previousRaw != null &&
            dtSec <= TankRules.MAX_CONTIGUOUS_STEP_SEC &&
            settledForSec >= TankRules.SETTLE_AFTER_FILL_SEC
        val droppedThisStep = if (measurable) previousRaw!! - levelPercent else 0.0

        state = state.copy(
            smoothedLevelPercent = smoothed,
            milesSinceFill = state.milesSinceFill + milesStep,
            gallonsUsedSinceFill = state.gallonsUsedSinceFill + gallonsStep,
            // Signed, so that a slosh up cancels the slosh back down. Taking only the falls
            // would count the noise as fuel and bias this high, which is the same mistake as
            // the one being fixed, pointing the other way.
            observedDropPercent = state.observedDropPercent + droppedThisStep,
            observedGallons = state.observedGallons + if (measurable) gallonsStep else 0.0,
            levelPercentAtFill = max(state.levelPercentAtFill, smoothed),
            lowestLevelPercent = min(state.lowestLevelPercent, smoothed),
            // The full mark is the highest this sender has ever gone, over the life of the
            // car rather than of this tank - which is why it is taken here, on the smoothed
            // reading, and carried through every fill. See TankState.fullMarkPercent.
            fullMarkPercent = max(state.fullMarkPercent, smoothed),
        )

        if (smoothed - state.lowestLevelPercent >= TankRules.FILL_RISE_PERCENT) {
            startNewTank(smoothed)
        }

        debouncedSave()
    }

    /**
     * Closes the old tank off and opens a new one.
     *
     * The calibration is taken here rather than continuously, because this is the moment the
     * whole span is known: the level at the last fill, the level now, and every gallon burned
     * between them.
     *
     * This runs more than once during a single fill - the level keeps rising past the
     * threshold as the pump runs, so it triggers again every five percent. That is harmless
     * and is why it is written to be: the counters being restarted are still near zero, and
     * the span on those later triggers is too short to measure from, so the calibration taken
     * on the first one is kept.
     */
    private fun startNewTank(levelNow: Double, snapLevel: Double? = null) {
        // Taken before the state is replaced, because the mark this measurement has to be
        // reconciled against includes the fill happening right now.
        val newFullMark = max(state.fullMarkPercent, snapLevel ?: state.smoothedLevelPercent)

        // Measured from the watched part of the tank only. Using the whole span - the level
        // at the fill minus the lowest it reached - against fuel counted only while the app
        // ran is what produced 0.0835 gallons per percent on a car whose real figure is
        // 0.132, and a freshly filled tank reading 74%.
        val measured = if (
            state.observedDropPercent >= TankRules.MIN_DROP_FOR_CALIBRATION &&
            state.observedGallons > 0
        ) {
            TankRules.reconcileGallonsPerPercent(
                state.observedGallons / state.observedDropPercent,
                newFullMark,
            )
        } else {
            null
        }

        // The figure already in hand gets the same test, not just the new one. A fill is when
        // the full mark moves, so it is also when a calibration carried over from before -
        // possibly from a version that measured it the old way, possibly from a tank taken
        // before this car had ever been seen full - can first be shown to be impossible.
        //
        // Only a measured one. The nominal figure is not a measurement to be reconciled, it
        // is what the app falls back to for want of one, and quietly nudging it about would
        // be inventing a calibration out of a car that has never been measured.
        val carried = if (state.calibrated) {
            TankRules.reconcileGallonsPerPercent(state.gallonsPerPercent, newFullMark)
        } else {
            null
        }

        state = TankState(
            fillTimestamp = clock.nowMillis(),
            levelPercentAtFill = levelNow,
            milesSinceFill = 0.0,
            gallonsUsedSinceFill = 0.0,
            gallonsPerPercent = measured ?: carried ?: CivicSpecs.NOMINAL_GALLONS_PER_SENDER_PERCENT,
            calibrated = measured != null || (carried != null && state.calibrated),
            // The smoothed level is carried over rather than snapped to the reading at this
            // instant, because the pump is still running: this is part way up the rise and
            // the level has to go on climbing. Snapping here reported 12.0 gallons in a tank
            // holding 13.2.
            //
            // snapLevel is the exception, and it is for the fill nobody watched. Coming back
            // to a car that was filled yesterday, there is no rise to follow - the level is
            // simply what it is.
            smoothedLevelPercent = snapLevel ?: state.smoothedLevelPercent,
            lowestLevelPercent = levelNow,
            // Carried, not reset. It is a fact about the car's sender, not about this tank,
            // and losing it at every fill would mean losing it forever - a fill is the only
            // time it is ever set.
            fullMarkPercent = max(state.fullMarkPercent, snapLevel ?: state.smoothedLevelPercent),
        )
        // A new tank is measured from scratch, and the reading this fill was detected on is
        // not one half of a pair with anything.
        lastRawLevel = null
        settledForSec = 0.0
        store.save(state)
        lastSaveAt = clock.nowMillis()
    }

    /**
     * Starts a tank by hand.
     *
     * For a fill too small to be detected - a few gallons rather than a tankful - or one the
     * app missed. Snaps the level, because the driver is standing at the pump saying so.
     */
    fun markFilled(levelPercent: Double) {
        started = true
        startNewTank(levelPercent, snapLevel = levelPercent)
    }

    fun flush() {
        store.save(state)
        lastSaveAt = clock.nowMillis()
    }

    private fun debouncedSave() {
        val now = clock.nowMillis()
        if (now - lastSaveAt >= 30_000) {
            lastSaveAt = now
            store.save(state)
        }
    }
}

/**
 * Distance to empty.
 *
 * Kept apart from [TankState] because it needs the lifetime figure as well, and because the
 * fallback order is the interesting part rather than the multiplication.
 */
fun rangeMiles(
    tank: TankState,
    lifetimeMpg: Double,
    lifetimeMiles: Double = Double.MAX_VALUE,
    verifiedMpg: Double? = null,
): Int = rawRangeMiles(tank, lifetimeMpg, lifetimeMiles, verifiedMpg).toInt()

/** [rangeMiles] before it is rounded, which is what [RangeDamper] needs to smooth. */
fun rawRangeMiles(
    tank: TankState,
    lifetimeMpg: Double,
    lifetimeMiles: Double = Double.MAX_VALUE,
    verifiedMpg: Double? = null,
): Double = tank.gallonsRemaining * TankRules.mpgForRange(
    tankMpg = tank.tankMpg,
    tankGallonsUsed = tank.gallonsUsedSinceFill,
    lifetimeMpg = lifetimeMpg,
    lifetimeMiles = lifetimeMiles,
    verifiedMpg = verifiedMpg,
)

/**
 * Distance to empty, split at the point the fuel gauge stops being able to see.
 *
 * The split is the difference between this app's answer and the dashboard's, and it is not a
 * disagreement about fuel - it is a disagreement about which question is being asked. Honda's
 * distance to empty counts down to zero with the reserve still in the tank, on purpose. This
 * app counts the reserve, because it was asked for what is actually there.
 *
 * Both are true and they are two different numbers, so both are returned. [toSenderZero] is
 * the one that lines up with the dashboard and the one to plan a fuel stop around;
 * [reserve] is the fuel underneath the sender's zero, which is real, is measured
 * (see [TankState.reserveGallons]) and is the least certain fuel in the tank - it is the only
 * part no sensor watches going down. Presenting it as part of one long number is what made
 * the app read a hundred and thirty when the dash read fifty-four.
 */
data class RangeEstimate(
    /** Everything in the tank, reserve included. */
    val totalMiles: Int,
    /** Miles before the sender reads zero. Comparable with the dashboard's figure. */
    val toSenderZeroMiles: Int,
    /** Miles held in the reserve below the sender's zero. */
    val reserveMiles: Int,
    /** The economy figure the whole estimate was built on. */
    val mpgUsed: Double,
)

/**
 * Holds the distance-to-empty figure still.
 *
 * The inputs to range are already slow - a tank average that moves by a fraction of an MPG per
 * mile, and a level smoothed over a minute - and it still twitches, because it is a product of
 * two of them and the sender arrives in steps of about four tenths of a percent. Each of those
 * steps is worth roughly two miles, so the last digit never settles.
 *
 * That is a display problem rather than a measurement one, so it is fixed at the display.
 * Three minutes of smoothing costs about two miles of lag at motorway speed, which is nothing
 * against a figure in the hundreds, and it buys a number that only ever counts down.
 *
 * A fill is the exception and must not be eased into: someone who has just filled up is
 * looking at the card right then. A jump upward past [TankRules.RANGE_SNAP_MILES] is taken
 * whole. Fuel does not appear in a tank by any other means, so nothing else can trip it.
 */
class RangeDamper(
    private val timeConstantSec: Double = TankRules.RANGE_TIME_CONSTANT_SEC,
) {
    private var damped: Double? = null

    fun update(rawMiles: Double, dtSec: Double): Int {
        val previous = damped
        if (previous == null || rawMiles - previous >= TankRules.RANGE_SNAP_MILES) {
            damped = rawMiles
            return max(0.0, rawMiles).toInt()
        }
        val alpha = 1 - exp(-max(0.0, dtSec) / timeConstantSec)
        val next = previous + (rawMiles - previous) * alpha
        damped = next
        return max(0.0, next).toInt()
    }
}

/**
 * Splits a settled distance-to-empty figure into the part the sender can see and the part it
 * cannot.
 *
 * The split is taken as a proportion of the damped total rather than computed fresh from the
 * gallons, so both halves inherit the same smoothing and always add back up to the number on
 * screen. A split computed independently would drift a mile or two away from its own total
 * during the three minutes [RangeDamper] takes to settle, and two figures that do not add up
 * is precisely the kind of small wrongness that makes someone stop believing the large one.
 */
fun splitRange(tank: TankState, dampedTotalMiles: Int, mpgUsed: Double): RangeEstimate {
    val gallons = tank.gallonsRemaining
    val reserveShare = if (gallons > 0.001) (tank.reserveGallons / gallons).coerceIn(0.0, 1.0) else 0.0
    val reserveMiles = (dampedTotalMiles * reserveShare).toInt()
    return RangeEstimate(
        totalMiles = dampedTotalMiles,
        toSenderZeroMiles = (dampedTotalMiles - reserveMiles).coerceAtLeast(0),
        reserveMiles = reserveMiles,
        mpgUsed = mpgUsed,
    )
}

/** True when the two independent ways of knowing the fuel level disagree enough to matter. */
fun tankDisagreesWithSender(tank: TankState, senderPercent: Double?): Boolean {
    if (senderPercent == null) return false
    return abs(senderPercent - tank.smoothedLevelPercent) > 15.0
}
