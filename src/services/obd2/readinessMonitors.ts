/**
 * Emissions readiness monitor bitmaps, decoded.
 *
 * This lives on its own rather than inside dtcScanner because two different screens read
 * the same bitmap from two different PIDs: the diagnostics tab reads PID 01 (since codes
 * were cleared) and the discovery tab reads PID 41 (this drive cycle). Both use the byte
 * layout below. dtcScanner imports the Bluetooth manager, which imports the PID catalogue,
 * so the catalogue cannot reach back into dtcScanner - and a second copy of a bit layout
 * is how two readings of the same bytes end up disagreeing.
 */

export interface ReadinessMonitorStatus {
  misfire: 'Ready' | 'Not Ready' | 'N/A';
  fuelSystem: 'Ready' | 'Not Ready' | 'N/A';
  comprehensive: 'Ready' | 'Not Ready' | 'N/A';
  catalyst: 'Ready' | 'Not Ready' | 'N/A';
  evap: 'Ready' | 'Not Ready' | 'N/A';
  o2Sensor: 'Ready' | 'Not Ready' | 'N/A';
  o2Heater: 'Ready' | 'Not Ready' | 'N/A';
  egrVvt: 'Ready' | 'Not Ready' | 'N/A';
}

/** Display names, so a caller can list the monitors that are not finished. */
export const MONITOR_LABELS: Record<keyof ReadinessMonitorStatus, string> = {
  misfire: 'misfire',
  fuelSystem: 'fuel system',
  comprehensive: 'components',
  catalyst: 'catalyst',
  evap: 'evap',
  o2Sensor: 'O2 sensor',
  o2Heater: 'O2 heater',
  egrVvt: 'EGR / VVT',
};

/**
 * Decodes the readiness monitor bits from bytes B, C and D.
 *
 * For every monitor there are two bits: one saying the ECU supports the test at all,
 * and one saying the test has not finished. A monitor the engine does not have reports
 * N/A rather than Ready - claiming a test passed when it was never run is the failure
 * mode this replaced, and it is the reading that matters when someone is deciding whether
 * the car is ready for a smog check.
 *
 * Byte B carries the three tests common to every engine, and its low nibble is the
 * "supported" half while bits 4-6 are the "incomplete" half. Bytes C and D are the
 * spark-ignition monitor set, split the same way: C supported, D incomplete.
 *
 * PID 01 and PID 41 share this layout exactly. They differ only in the window they
 * describe - PID 01 since codes were cleared, PID 41 this drive cycle - so the caller
 * supplies the bytes and says which question it is asking.
 */
export function decodeReadinessMonitors(b: number, c: number, d: number): ReadinessMonitorStatus {
  const read = (supported: boolean, incomplete: boolean): 'Ready' | 'Not Ready' | 'N/A' =>
    !supported ? 'N/A' : incomplete ? 'Not Ready' : 'Ready';

  const bit = (byte: number, index: number) => (byte & (1 << index)) !== 0;

  return {
    misfire: read(bit(b, 0), bit(b, 4)),
    fuelSystem: read(bit(b, 1), bit(b, 5)),
    comprehensive: read(bit(b, 2), bit(b, 6)),
    catalyst: read(bit(c, 0), bit(d, 0)),
    evap: read(bit(c, 2), bit(d, 2)),
    o2Sensor: read(bit(c, 5), bit(d, 5)),
    o2Heater: read(bit(c, 6), bit(d, 6)),
    egrVvt: read(bit(c, 7), bit(d, 7)),
  };
}

/** Every monitor unknown - used when the ECU's reply cannot be read. */
export const UNKNOWN_MONITORS: ReadinessMonitorStatus = {
  misfire: 'N/A',
  fuelSystem: 'N/A',
  comprehensive: 'N/A',
  catalyst: 'N/A',
  evap: 'N/A',
  o2Sensor: 'N/A',
  o2Heater: 'N/A',
  egrVvt: 'N/A',
};

/**
 * One line summarising a monitor set: what is still running, or that nothing is.
 * Returns null when the ECU supports no monitors at all, which is not a status worth
 * printing as "all complete".
 */
export function summariseMonitors(status: ReadinessMonitorStatus): string | null {
  const entries = Object.entries(status) as [keyof ReadinessMonitorStatus, string][];
  const supported = entries.filter(([, v]) => v !== 'N/A');
  if (!supported.length) return null;

  const incomplete = supported.filter(([, v]) => v === 'Not Ready').map(([k]) => MONITOR_LABELS[k]);
  if (!incomplete.length) return `${supported.length} monitors, all complete`;
  return `${incomplete.length} of ${supported.length} still running — ${incomplete.join(', ')}`;
}
