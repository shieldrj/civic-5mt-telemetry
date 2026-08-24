package com.shieldrj.civic5mt.core

/**
 * Whether the charging system is doing its job, on a car designed to spend much of its time
 * not charging.
 *
 * This is the rule that used to read `batteryVoltage < 12.8 while running -> CHARGING SYSTEM
 * LOW`, and on this car that fires on a healthy afternoon's driving. The 2013 Civic does not
 * run a fixed-output alternator. It runs Honda's Electrical Power Management: an Electrical
 * Load Detector on the battery feed tells the ECM how much current the car is actually
 * drawing, and the ECM commands the alternator between two regimes.
 *
 *  - **High output**, roughly 13.5-14.8 V. After a start, under electrical load, on
 *    deceleration - the moments when charging is either needed or free.
 *  - **Low output**, roughly 12.4-12.9 V. A charged battery and a light load at steady
 *    cruise. The alternator is deliberately backed off, because spinning it costs fuel.
 *
 * So a reading in the twelves with the engine running is not a fault on this car. It is the
 * ECM doing the thing it was designed to do, and a warning banner every time it does is the
 * kind of alarm that teaches a driver to ignore the banner - including on the day it means
 * something.
 *
 * What is worth warning about is narrower, and none of it can be read off a single sample:
 *
 *  - The voltage sitting **below a rested battery's own** ([DRAIN_VOLTS]) for a sustained
 *    stretch with the engine running. Low output holds near 12.4-12.9; below 12.2 the car is
 *    running down its own battery rather than idling the alternator.
 *  - **Never reaching high output at all** across a whole drive. Low output is a mode the
 *    ECM enters from high output; a charging system that never once commands a charge is one
 *    that cannot. This is the check that actually catches a dying alternator on this car, and
 *    it is invisible to any instantaneous threshold.
 *  - A reading **far below** anything a running car should show ([CRITICAL_VOLTS]).
 *
 * Measured at the control module via PID 42, which reads a little under the battery post -
 * another reason not to hang a verdict on tenths of a volt from one sample.
 */
object ChargingRules {

    /** Below this is a dead sensor or a garbled frame, not a battery state. */
    const val MIN_PLAUSIBLE_VOLTS: Double = 5.0

    /**
     * At or above this, the ECM has commanded a real charge.
     *
     * Set below the 13.5 V that high output actually holds, because what is being detected is
     * "the alternator can and does charge", not "how well". A drive that touches 13.2 once
     * has answered the question this is asking.
     */
    const val HIGH_OUTPUT_VOLTS: Double = 13.0

    /**
     * Sustained below this while running is a net drain rather than a backed-off alternator.
     *
     * A rested, fully charged battery sits at 12.6, and the ECM's low-output mode holds
     * around 12.4-12.9. 12.2 is under both, with enough margin that the ordinary bottom of
     * low output does not trip it.
     */
    const val DRAIN_VOLTS: Double = 12.2

    /** Far below anything a running car explains. The battery is carrying the whole car. */
    const val CRITICAL_VOLTS: Double = 11.8

    /**
     * How long a low reading has to hold before it is a verdict.
     *
     * PID 42 comes round once every forty-two poll cycles, roughly every five seconds, so
     * this is four or five independent readings rather than one unlucky frame. It also rides
     * out the things that legitimately pull the rail down for a moment: the starter, a
     * radiator fan cutting in, rear defog, the AC clutch engaging.
     */
    const val SUSTAIN_SEC: Double = 20.0

    /**
     * Engine-running seconds before "never charged" becomes a conclusion.
     *
     * Ten minutes. A cold start commands high output almost immediately to put back what the
     * starter took, so the usual drive answers this within a minute. The long grace is for
     * the case that would otherwise be a false alarm - a warm restart with a full battery and
     * nothing switched on, where the ECM can legitimately hold low output for a while.
     */
    const val HIGH_OUTPUT_GRACE_SEC: Double = 600.0
}

/**
 * What the charging system is doing, in the only terms worth putting on a banner.
 *
 * [NORMAL] deliberately covers both regimes. "Charging" and "holding in low output" are the
 * same answer to the only question being asked, which is whether anything is wrong.
 */
enum class ChargingVerdict {
    /** No reading yet, or the engine is not running. Nothing has been established. */
    UNKNOWN,

    /** Charging, or backed off with the battery holding station. Nothing to report. */
    NORMAL,

    /** Sustained under a rested battery: the car is running down its own battery. */
    DRAINING,

    /** Long enough into a drive with no commanded charge ever seen. */
    NOT_CHARGING,

    /** Far under anything a running engine accounts for. */
    CRITICAL,
}

/**
 * Watches the charging system across a drive rather than sampling it.
 *
 * Stateful on purpose, and reset per drive by [resetForDrive]: two of the three verdicts are
 * about duration - how long a low reading has held, and whether a charge was ever commanded -
 * and neither is answerable from the snapshot the old threshold was reading.
 */
class ChargingMonitor(
    private val sustainSec: Double = ChargingRules.SUSTAIN_SEC,
    private val highOutputGraceSec: Double = ChargingRules.HIGH_OUTPUT_GRACE_SEC,
) {
    private var runningSec: Double = 0.0
    private var belowDrainSec: Double = 0.0
    private var belowCriticalSec: Double = 0.0

    /** Highest plausible reading seen with the engine running this drive, or null. */
    var peakVolts: Double? = null
        private set

    var verdict: ChargingVerdict = ChargingVerdict.UNKNOWN
        private set

    /** The reading the current verdict was reached on, for the banner to quote. */
    var volts: Double? = null
        private set

    /** True once the ECM has been seen commanding a real charge this drive. */
    val sawHighOutput: Boolean
        get() = (peakVolts ?: 0.0) >= ChargingRules.HIGH_OUTPUT_VOLTS

    /**
     * One step.
     *
     * @param volts PID 42, or null if the car has not answered for it yet
     * @param rpm engine speed, to tell a running car from a key-on one
     * @param dtSec wall-clock seconds since the last step
     */
    fun observe(volts: Double?, rpm: Double, dtSec: Double): ChargingVerdict {
        val running = rpm >= CivicSpecs.ENGINE_RUNNING_RPM
        val reading = volts?.takeIf { it > ChargingRules.MIN_PLAUSIBLE_VOLTS }

        // Key-on-engine-off is not a charging fault, it is a car that is switched off. The
        // 12.4 sitting there is a rested battery, and the alternator is not turning.
        if (!running || reading == null) {
            belowDrainSec = 0.0
            belowCriticalSec = 0.0
            this.volts = reading
            verdict = ChargingVerdict.UNKNOWN
            return verdict
        }

        if (dtSec > 0) runningSec += dtSec
        peakVolts = maxOf(peakVolts ?: reading, reading)
        this.volts = reading

        belowCriticalSec = if (reading < ChargingRules.CRITICAL_VOLTS) belowCriticalSec + dtSec else 0.0
        belowDrainSec = if (reading < ChargingRules.DRAIN_VOLTS) belowDrainSec + dtSec else 0.0

        verdict = when {
            belowCriticalSec >= sustainSec -> ChargingVerdict.CRITICAL
            belowDrainSec >= sustainSec -> ChargingVerdict.DRAINING
            // Only once the drive has run long enough that a charge should have happened, and
            // only while none ever has. One reading at 13.0 settles this for the whole drive.
            !sawHighOutput && runningSec >= highOutputGraceSec -> ChargingVerdict.NOT_CHARGING
            else -> ChargingVerdict.NORMAL
        }
        return verdict
    }

    /**
     * Starts the drive over.
     *
     * The peak has to go with it. Carrying yesterday's 14.4 into today is what would let a
     * failed alternator pass the one check built to catch it.
     */
    fun resetForDrive() {
        runningSec = 0.0
        belowDrainSec = 0.0
        belowCriticalSec = 0.0
        peakVolts = null
        volts = null
        verdict = ChargingVerdict.UNKNOWN
    }
}

/**
 * The banner text for a verdict, or null when there is nothing to say.
 *
 * Kept beside the rules rather than in the manager so the wording and the thresholds that
 * justify it stay in one place - the detail lines quote the numbers above, and a threshold
 * moved without its explanation is how a banner starts lying.
 */
fun chargingHealthStatus(verdict: ChargingVerdict, volts: Double?, peakVolts: Double?): VehicleHealthStatus? =
    when (verdict) {
        ChargingVerdict.CRITICAL -> VehicleHealthStatus(
            level = HealthLevel.CRITICAL,
            summary = "CHARGING SYSTEM FAILING · %.2fV".format(volts ?: 0.0),
            detail = "Under ${"%.1f".format(ChargingRules.CRITICAL_VOLTS)}V with the engine " +
                "running. The battery is carrying the car and will not restart it.",
        )

        ChargingVerdict.NOT_CHARGING -> VehicleHealthStatus(
            level = HealthLevel.ADVISORY,
            summary = "NO CHARGE THIS DRIVE · PEAK %.2fV".format(peakVolts ?: 0.0),
            detail = "The alternator never came up to ${"%.1f".format(ChargingRules.HIGH_OUTPUT_VOLTS)}V " +
                "this drive. Low output in the twelves is normal on this car; never leaving it is not.",
        )

        ChargingVerdict.DRAINING -> VehicleHealthStatus(
            level = HealthLevel.ADVISORY,
            summary = "BATTERY DRAINING · %.2fV".format(volts ?: 0.0),
            detail = "Below a rested battery's own ${"%.1f".format(ChargingRules.DRAIN_VOLTS)}V " +
                "while running, so the car is drawing the battery down rather than holding it.",
        )

        ChargingVerdict.NORMAL, ChargingVerdict.UNKNOWN -> null
    }
