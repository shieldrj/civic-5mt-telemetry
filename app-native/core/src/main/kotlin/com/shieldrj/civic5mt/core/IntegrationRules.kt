package com.shieldrj.civic5mt.core

enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, SIMULATING, ERROR }

/**
 * The two rules that decide what is allowed to reach the permanent record.
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
}
