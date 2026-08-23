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
     */
    val levelPercentAtFill: Double = 0.0,
    val milesSinceFill: Double = 0.0,
    val gallonsUsedSinceFill: Double = 0.0,
    /**
     * Gallons per one percent of sender reading, measured on this car.
     *
     * Starts at the nominal figure - tank capacity divided by 100 - and is replaced by a
     * measured one as soon as a tank has been run down far enough to measure it.
     */
    val gallonsPerPercent: Double = CivicSpecs.FUEL_TANK_CAPACITY_GALLONS / 100.0,
    /** Whether [gallonsPerPercent] has been measured, or is still the nominal figure. */
    val calibrated: Boolean = false,
    /** Sender reading, smoothed. Raw readings move with fuel sloshing in the tank. */
    val smoothedLevelPercent: Double = 0.0,
    /** Lowest smoothed reading since the fill, which is what a rise is measured against. */
    val lowestLevelPercent: Double = 100.0,
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

    /** Gallons in the tank now, from the measured gallons-per-percent. */
    val gallonsRemaining: Double
        get() = max(0.0, gallonsPerPercent * smoothedLevelPercent)
}

object TankRules {

    /**
     * A rise of this many percent counts as a fill.
     *
     * Requiring a 10% rise prevents cornering/hill sloshing from triggering false tank resets.
     */
    const val FILL_RISE_PERCENT = 10.0

    /**
     * The sender must fall this far before gallons-per-percent is measured from it.
     *
     * A short span divides a small fuel figure by a small percent figure, and the error in
     * both lands in the answer. Twenty percent of a tank is roughly 2.6 gallons, which is far
     * larger than anything the sender's own resolution can contribute.
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
     * The nominal figure for this car is 0.132. A measurement outside this range means
     * something else went wrong - the app was closed for part of the tank, or a fill was
     * missed - and a bad calibration would then be applied to every later reading.
     */
    const val MIN_GALLONS_PER_PERCENT = 0.08
    const val MAX_GALLONS_PER_PERCENT = 0.20

    /**
     * A lifetime figure needs this many real miles behind it before range leans on it.
     */
    const val MIN_LIFETIME_MILES_FOR_RANGE = 20.0

    /**
     * Which economy figure range should be based on, in order of preference.
     *
     * This tank first, because it describes this fuel in this car in this weather. Then the
     * lifetime average, once there is enough of it to be an average. Then the EPA rating,
     * which is a real figure for this model and is right within a few miles per gallon.
     */
    fun mpgForRange(tankMpg: Double?, lifetimeMpg: Double, lifetimeMiles: Double): Double = when {
        tankMpg != null && tankMpg > 10.0 && tankMpg < 65.0 -> tankMpg
        lifetimeMpg > 10.0 && lifetimeMiles >= MIN_LIFETIME_MILES_FOR_RANGE -> lifetimeMpg
        else -> CivicSpecs.EPA_COMBINED_MPG_DEFAULT
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
    private var state: TankState = store.load() ?: TankState()
    private var lastSaveAt: Long = 0L
    private var started = false

    /** How long the sender has been reading well above the smoothed level. */
    private var risingForSec: Double = 0.0

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

        state = state.copy(
            smoothedLevelPercent = smoothed,
            milesSinceFill = state.milesSinceFill + milesStep,
            gallonsUsedSinceFill = state.gallonsUsedSinceFill + gallonsStep,
            levelPercentAtFill = max(state.levelPercentAtFill, smoothed),
            lowestLevelPercent = min(state.lowestLevelPercent, smoothed),
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
        val dropped = state.levelPercentAtFill - state.lowestLevelPercent
        val measured = if (
            dropped >= TankRules.MIN_DROP_FOR_CALIBRATION &&
            state.gallonsUsedSinceFill > 0
        ) {
            val perPercent = state.gallonsUsedSinceFill / dropped
            if (perPercent in TankRules.MIN_GALLONS_PER_PERCENT..TankRules.MAX_GALLONS_PER_PERCENT) {
                perPercent
            } else {
                // Outside the possible. The app missed part of the tank, or a fill went
                // unseen. Keeping the previous figure is better than adopting a wrong one.
                null
            }
        } else {
            null
        }

        state = TankState(
            fillTimestamp = clock.nowMillis(),
            levelPercentAtFill = levelNow,
            milesSinceFill = 0.0,
            gallonsUsedSinceFill = 0.0,
            gallonsPerPercent = measured ?: state.gallonsPerPercent,
            calibrated = measured != null || state.calibrated,
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
        )
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
fun rangeMiles(tank: TankState, lifetimeMpg: Double, lifetimeMiles: Double = Double.MAX_VALUE): Int =
    (tank.gallonsRemaining * TankRules.mpgForRange(tank.tankMpg, lifetimeMpg, lifetimeMiles)).toInt()

/** True when the two independent ways of knowing the fuel level disagree enough to matter. */
fun tankDisagreesWithSender(tank: TankState, senderPercent: Double?): Boolean {
    if (senderPercent == null) return false
    return abs(senderPercent - tank.smoothedLevelPercent) > 15.0
}
