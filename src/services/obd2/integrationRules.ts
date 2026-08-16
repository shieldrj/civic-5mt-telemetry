import { ConnectionStatus } from '../../types/obd';

/**
 * The two rules that decide what is allowed to reach the permanent record.
 *
 * They live here, apart from telemetryManager, because that module constructs a singleton
 * on import which starts the telemetry interval - importing it just to reach these would
 * start a timer, and in a test runner would hang the process outright. Rules this
 * load-bearing need to be testable without booting the app.
 */

/**
 * Longest gap the integrator treats as observed driving. The loop runs at 80ms, so a gap
 * beyond this means the timer was stalled (backgrounded tab, locked phone) rather than
 * that the car did something for that long.
 */
export const MAX_INTEGRATION_STEP_SEC = 1.0;

/**
 * Returns the time step to integrate over, or 0 when the gap is too large to trust.
 *
 * Integrating a multi-minute stall against one stale sample would book driving that never
 * happened - park with the app open, let the phone lock for twenty minutes, and the next
 * tick would otherwise record twenty minutes at whatever the last reading was.
 */
export function resolveIntegrationStep(
  rawDtSec: number,
  maxStepSec: number = MAX_INTEGRATION_STEP_SEC
): number {
  if (!Number.isFinite(rawDtSec) || rawDtSec <= 0) return 0;
  return rawDtSec <= maxStepSec ? rawDtSec : 0;
}

/**
 * Whether a sample may enter the permanent lifetime record.
 *
 * Only a real adapter counts. The simulator starts from the constructor and runs whenever
 * nothing is connected, so anything looser than this silently fills the lifetime figure
 * with invented driving - which is exactly what it used to do.
 */
export function shouldRecordLifetime(status: ConnectionStatus): boolean {
  return status === 'connected';
}
