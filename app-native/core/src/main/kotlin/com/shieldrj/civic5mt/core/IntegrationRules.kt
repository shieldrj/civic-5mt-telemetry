package com.shieldrj.civic5mt.core

/**
 * RECONNECTING is deliberately its own state rather than reusing CONNECTING.
 *
 * They differ in what is behind them: CONNECTING has no drive yet, RECONNECTING has one open
 * and paused with its distance and fuel intact. A screen that cannot tell them apart either
 * throws the driver back to an adapter list in the middle of a drive, or claims to be logging
 * while nothing is arriving.
 */
enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, SIMULATING, ERROR }

/**
 * The three rules that decide what is allowed to reach the permanent record.
 *
 * They live here, apart from the telemetry manager, because that one owns the tick loop -
 * and in the TypeScript it constructed a singleton on import, so reaching these through it
 * started a timer and hung the test runner outright. Rules this load-bearing need to be
 * testable without booting the app. In the native build the loop lives in a service, which
 * is a far worse thing to start from a unit test, so the separation matters more, not less.
 */
object IntegrationRules {

    /**
     * Longest gap the integrator treats as observed driving. The loop runs at 80ms, so a gap
     * beyond this means the timer was stalled (backgrounded, dozing, locked phone) rather
     * than that the car did something for that long.
     */
    const val MAX_INTEGRATION_STEP_SEC: Double = 1.0

    /**
     * Returns the time step to integrate over, or 0 when the gap is too large to trust.
     *
     * Integrating a multi-minute stall against one stale sample would book driving that never
     * happened - park with the app open, let the phone lock for twenty minutes, and the next
     * tick would otherwise record twenty minutes at whatever the last reading was.
     */
    fun resolveIntegrationStep(
        rawDtSec: Double,
        maxStepSec: Double = MAX_INTEGRATION_STEP_SEC,
    ): Double {
        if (!rawDtSec.isFinite() || rawDtSec <= 0) return 0.0
        return if (rawDtSec <= maxStepSec) rawDtSec else 0.0
    }

    /**
     * Whether a sample may enter the permanent lifetime record.
     *
     * Only a real adapter counts. The simulator runs whenever nothing is connected, so
     * anything looser than this silently fills the lifetime figure with invented driving -
     * which is exactly what it used to do.
     */
    fun shouldRecordLifetime(status: ConnectionStatus): Boolean =
        status == ConnectionStatus.CONNECTED

    /**
     * Longest a reading may be carried forward and still be integrated.
     *
     * A slower poll is not a problem in itself: holding the last speed between samples is
     * ordinary zero-order-hold integration, and its error is small and unbiased - it
     * overshoots on deceleration exactly as it undershoots on acceleration. Holding the last
     * speed because the source STOPPED PRODUCING is a different thing entirely. That error is
     * one-signed and unbounded, and it is what books miles and gallons against a car that is
     * switched off.
     *
     * Every field on RawObdData carries forward on a non-answer - `parse(...) ?: it.field` -
     * so a snapshot reading 750 rpm means either "measured 40ms ago" or "measured forty
     * seconds ago and the ECU has been asleep since". The rpm >= 350 gate tests the value and
     * cannot tell those apart. Switch off at idle and rpm holds at 750, speed at 0 and MAF at
     * 2.8: step miles are zero, step gallons are not, and updateLifetime only returns early
     * when BOTH are - so lifetime gallons grew while lifetime miles stood still, on every
     * drive, diluting a figure that only moves one way and cannot be corrected afterwards.
     *
     * Two seconds is set against the worst legitimate gap between two successful motion
     * reads: one cycle budget plus one timed-out 010C, about 1.4s. It deliberately does not
     * cover a bus timing out repeatedly for seconds - a bus in that state is not one to book
     * lifetime miles from, and refusing errs towards undercounting, which is the safe
     * direction for a number nobody can go back and fix. If [ObdPacing.CYCLE_BUDGET_MS] ever
     * goes past ~800ms, or [ObdPacing.ENGINE_OFF_CYCLE_MS] past 2000ms, this constant becomes
     * the binding constraint and has to be revisited with them.
     */
    const val MAX_READING_AGE_MS: Long = 2_000

    /**
     * Whether a reading was measured recently enough to reach the permanent record.
     *
     * Null means never measured, which is not the same as measured long ago and must not be
     * read as an old-but-real value. The lower bound rejects a negative age from a clock
     * adjustment, matching what [resolveIntegrationStep] does with a negative step.
     */
    fun isFreshEnoughToIntegrate(
        sampledAtMillis: Long?,
        nowMillis: Long,
        maxAgeMs: Long = MAX_READING_AGE_MS,
    ): Boolean = sampledAtMillis != null && (nowMillis - sampledAtMillis) in 0..maxAgeMs
}
