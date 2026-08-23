package com.shieldrj.civic5mt.core

/**
 * How hard the poll loop is allowed to work the adapter.
 *
 * Deliberately not in [CivicSpecs], which is physics calibration - FuelModelEngine sizes its
 * rolling buffer by dividing into TELEMETRY_TICK_MS, and a radio-pacing figure sitting beside
 * that invites a future reader to infer a coupling that must not exist. Deliberately not in
 * [ObdTimeouts] either: those are how long to wait for an answer, and a timeout that is really
 * a pace is how you end up "fixing" battery drain by making commands fail.
 *
 * The thing worth understanding here: before this existed the loop's only sleep was a flat 15ms
 * per *cycle*, so dropping PIDs from the loop bought nothing at all - the cycle just finished
 * sooner and started again, at the same radio duty and the same wakeup rate. Freed round trips
 * only become battery when something converts them into idle, and that is what a budget is.
 */
object ObdPacing {

    /**
     * Target period for one full-rate cycle: 8Hz.
     *
     * Nothing in the fast set needs more. It feeds the shift light, the gear calculator and
     * DFCO detection, all of which are read by a human with a ~250ms reaction time. The
     * physics tick is a separate 80ms loop that reads the last snapshot, so this figure does
     * not set the integration rate - see TelemetryService.
     */
    const val CYCLE_BUDGET_MS: Long = 125

    /**
     * The loop always yields at least this long, even on a cycle that overran its budget.
     *
     * Not for the radio. It is what guarantees the loop suspends every cycle, which is what
     * lets cancellation be observed - and what stops a synchronous fake transport spinning
     * forever under a test's virtual clock, where the injected MillisClock and the coroutine
     * scheduler advance independently and `elapsed` can read far past the budget on a loop
     * that has taken no real time at all.
     */
    const val MIN_CYCLE_IDLE_MS: Long = 5

    /** One 010C per second while the engine is off: enough to notice it starting. */
    const val ENGINE_OFF_CYCLE_MS: Long = 1_000

    /**
     * Sustained fresh sub-400 rpm at a standstill before dropping to the engine-off tier.
     *
     * Not a guard against long traffic lights - there is no false positive available there.
     * The R18Z1 has no idle stop-start, so with the ignition on, a *fresh* reading below 400
     * means the engine is genuinely not turning. At a red light it idles around 750. This is
     * guarding against a transient: one bad parse, or the second or so of a stall and restart.
     */
    const val ENGINE_OFF_CONFIRM_MS: Long = 5_000
}

/**
 * How long to sleep at the end of a cycle to hold the target period.
 *
 * A cycle that overran sleeps the floor and the loop simply runs slower than the target. No
 * catch-up and no debt tracking on purpose: sleeping less on the next cycle to hit an average
 * rate turns a struggling bus into a busy-loop exactly when it is already struggling.
 */
fun cycleSleepMs(elapsedMs: Long, budgetMs: Long): Long =
    (budgetMs - elapsedMs).coerceAtLeast(ObdPacing.MIN_CYCLE_IDLE_MS)

/**
 * Decides when the loop may stop watching a car that is switched off.
 *
 * **Fed the fresh parse of 010C, never the snapshot.** That distinction is the whole thing.
 * Every field on RawObdData carries forward on a non-answer, so a snapshot reading 750 rpm
 * after the ignition went off is indistinguishable from an engine at idle - and a detector
 * reading it would never fire in the one case it is for. A null here means the car did not
 * answer, which is not evidence of anything and must not start the clock on an engine-off
 * decision.
 *
 * That is also what makes the tier safe for the permanent record: entering requires a real,
 * just-measured rpm near zero, so the integrators are already gated off by their own rpm
 * tests before the poll rate drops, and the value that then carries forward is a fresh zero.
 *
 * Scope, honestly: on an ordinary shutdown the ECU sleeps, every PID times out, and
 * [ObdTimeouts.SILENT_ADAPTER_MS] ends the drive - the tier is never entered at all, because a
 * sleeping ECU produces no fresh reading. What it is for is key-on-engine-off with the ECU
 * still answering: sitting in the car with the radio on, or connecting the app before starting
 * up, which is not exotic - the handshake needs the ignition on, so every drive that gets
 * connected early spends that time here.
 */
class EngineOffDetector(
    private val confirmMs: Long = ObdPacing.ENGINE_OFF_CONFIRM_MS,
) {
    private var offSince: Long? = null

    var isEngineOff: Boolean = false
        private set

    /** @return true when the loop should be in the engine-off tier after this observation. */
    fun observe(freshRpm: Double?, speedKmh: Double, nowMillis: Long): Boolean {
        // No answer is not evidence. Do not start the timer, do not clear it, do not switch.
        if (freshRpm == null) return isEngineOff

        val stopped = freshRpm < CivicSpecs.ENGINE_RUNNING_RPM && speedKmh <= 0.0
        if (!stopped) {
            // Instant exit, no hysteresis: a car that is turning over is a car to watch.
            offSince = null
            isEngineOff = false
            return false
        }

        val since = offSince ?: nowMillis.also { offSince = it }
        if (nowMillis - since >= confirmMs) isEngineOff = true
        return isEngineOff
    }

    fun reset() {
        offSince = null
        isEngineOff = false
    }
}
