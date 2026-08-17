/**
 * Names and formulas for mode 01 PIDs, used by the discovery screen.
 *
 * This catalogue answers "what does my car expose, and what is it saying" without anyone
 * having to guess from the model year. Every entry here is the generic OBD-II definition -
 * which is only half the story, because whether a given ECU implements a PID varies by
 * manufacturer and trim. That half comes from the car itself, via the 0100/0120/... support
 * bitmaps. Nothing in this file asserts that the Civic has any particular PID.
 *
 * A PID with no `decode` still gets listed and still shows its raw bytes. Showing the hex
 * and saying "not decoded" is honest; inventing a plausible formula is how a dashboard ends
 * up confidently displaying a wrong number, which is the failure this whole exercise has
 * been chasing.
 */

export interface PidDefinition {
  name: string;
  unit?: string;
  /** Bytes A, B, C, D... after the 41xx prefix. Return null if the reply is too short. */
  decode?: (b: number[]) => number | null;
  /** Rendered instead of a number, for enumerations and bitmaps. */
  describe?: (b: number[]) => string | null;
}

const pct255 = (b: number[]) => (b.length ? (b[0] * 100) / 255 : null);
const tempA = (b: number[]) => (b.length ? b[0] - 40 : null);
const trim = (b: number[]) => (b.length ? ((b[0] - 128) * 100) / 128 : null);
const word = (b: number[]) => (b.length >= 2 ? b[0] * 256 + b[1] : null);

const FUEL_SYSTEM_STATUS: Record<number, string> = {
  0: 'Off',
  1: 'Open loop — engine cold',
  2: 'Closed loop — using O2 feedback',
  4: 'Open loop — load or deceleration',
  8: 'Open loop — system fault',
  16: 'Closed loop — one O2 sensor faulted',
};

const OBD_STANDARD: Record<number, string> = {
  1: 'OBD-II (California ARB)',
  3: 'OBD and OBD-II',
  6: 'EOBD',
  9: 'EOBD and OBD-II',
};

export const PID_CATALOG: Record<number, PidDefinition> = {
  0x01: {
    name: 'Monitor status / MIL',
    describe: (b) =>
      b.length
        ? `${b[0] & 0x80 ? 'CHECK ENGINE ON' : 'No MIL'} · ${b[0] & 0x7f} stored code${
            (b[0] & 0x7f) === 1 ? '' : 's'
          }`
        : null,
  },
  0x03: {
    name: 'Fuel system status',
    describe: (b) => (b.length ? FUEL_SYSTEM_STATUS[b[0]] ?? `Unknown (0x${b[0].toString(16)})` : null),
  },
  0x04: { name: 'Calculated engine load', unit: '%', decode: pct255 },
  0x05: { name: 'Coolant temperature', unit: '°C', decode: tempA },
  0x06: { name: 'Short term fuel trim', unit: '%', decode: trim },
  0x07: { name: 'Long term fuel trim', unit: '%', decode: trim },
  0x0a: { name: 'Fuel pressure', unit: 'kPa', decode: (b) => (b.length ? b[0] * 3 : null) },
  0x0b: { name: 'Manifold pressure', unit: 'kPa', decode: (b) => (b.length ? b[0] : null) },
  0x0c: { name: 'Engine RPM', unit: 'rpm', decode: (b) => (b.length >= 2 ? (b[0] * 256 + b[1]) / 4 : null) },
  0x0d: { name: 'Vehicle speed', unit: 'km/h', decode: (b) => (b.length ? b[0] : null) },
  0x0e: { name: 'Timing advance', unit: '°', decode: (b) => (b.length ? b[0] / 2 - 64 : null) },
  0x0f: { name: 'Intake air temperature', unit: '°C', decode: tempA },
  0x10: { name: 'Mass air flow', unit: 'g/s', decode: (b) => (b.length >= 2 ? (b[0] * 256 + b[1]) / 100 : null) },
  0x11: { name: 'Throttle position', unit: '%', decode: pct255 },
  0x13: {
    name: 'O2 sensors present',
    describe: (b) => {
      if (!b.length) return null;
      const present = [];
      for (let i = 0; i < 8; i++) if (b[0] & (1 << i)) present.push(`B${i < 4 ? 1 : 2}S${(i % 4) + 1}`);
      return present.length ? present.join(', ') : 'None reported';
    },
  },
  0x15: { name: 'O2 sensor B1S2 voltage', unit: 'V', decode: (b) => (b.length ? b[0] / 200 : null) },
  0x1c: {
    name: 'OBD standard',
    describe: (b) => (b.length ? OBD_STANDARD[b[0]] ?? `Type ${b[0]}` : null),
  },
  0x1f: { name: 'Engine run time', unit: 's', decode: word },
  0x21: { name: 'Distance with MIL on', unit: 'km', decode: word },
  0x24: {
    name: 'O2 S1 lambda (wide range)',
    unit: 'ratio',
    decode: (b) => (b.length >= 2 ? (b[0] * 256 + b[1]) / 32768 : null),
  },
  0x2e: { name: 'Commanded evap purge', unit: '%', decode: pct255 },
  0x2f: { name: 'Fuel tank level', unit: '%', decode: pct255 },
  0x30: { name: 'Warm-ups since codes cleared', decode: (b) => (b.length ? b[0] : null) },
  0x31: { name: 'Distance since codes cleared', unit: 'km', decode: word },
  0x33: { name: 'Barometric pressure', unit: 'kPa', decode: (b) => (b.length ? b[0] : null) },
  0x3c: {
    name: 'Catalyst temperature B1S1',
    unit: '°C',
    decode: (b) => (b.length >= 2 ? (b[0] * 256 + b[1]) / 10 - 40 : null),
  },
  0x42: { name: 'Control module voltage', unit: 'V', decode: (b) => (b.length >= 2 ? (b[0] * 256 + b[1]) / 1000 : null) },
  0x43: {
    name: 'Absolute engine load',
    unit: '%',
    decode: (b) => (b.length >= 2 ? ((b[0] * 256 + b[1]) * 100) / 255 : null),
  },
  0x44: {
    name: 'Commanded equivalence ratio',
    unit: 'ratio',
    decode: (b) => (b.length >= 2 ? (b[0] * 256 + b[1]) / 32768 : null),
  },
  0x45: { name: 'Relative throttle position', unit: '%', decode: pct255 },
  0x46: { name: 'Ambient air temperature', unit: '°C', decode: tempA },
  0x47: { name: 'Absolute throttle B', unit: '%', decode: pct255 },
  0x49: { name: 'Accelerator pedal D', unit: '%', decode: pct255 },
  0x4a: { name: 'Accelerator pedal E', unit: '%', decode: pct255 },
  0x4c: { name: 'Commanded throttle actuator', unit: '%', decode: pct255 },
  0x5c: { name: 'Engine oil temperature', unit: '°C', decode: tempA },
  0x5e: { name: 'Engine fuel rate', unit: 'L/h', decode: (b) => (b.length >= 2 ? (b[0] * 256 + b[1]) / 20 : null) },
};

/** PIDs at each bank boundary report which PIDs the *next* bank supports, not a reading. */
export const BANK_MARKER_PIDS = new Set([0x20, 0x40, 0x60, 0x80, 0xa0, 0xc0]);

/** PIDs this app already reads for the gauges, so discovery can show what is going unused. */
export const PIDS_IN_USE = new Set([
  0x04, 0x05, 0x06, 0x07, 0x0c, 0x0d, 0x0e, 0x10, 0x11, 0x15, 0x1f, 0x24, 0x2f, 0x42, 0x46,
]);

export function pidCommand(pid: number): string {
  return `01${pid.toString(16).toUpperCase().padStart(2, '0')}`;
}

export function pidLabel(pid: number): string {
  return PID_CATALOG[pid]?.name ?? `PID ${pidCommand(pid).slice(2)}`;
}

/**
 * Turns a raw adapter reply into something readable. Returns null when the car answered
 * but this catalogue has no formula - the caller shows the hex instead of a guess.
 */
export function decodePidValue(pid: number, hexPayload: string): string | null {
  const def = PID_CATALOG[pid];
  if (!def) return null;

  const bytes: number[] = [];
  for (let i = 0; i + 1 < hexPayload.length; i += 2) {
    const byte = parseInt(hexPayload.substring(i, i + 2), 16);
    if (Number.isNaN(byte)) break;
    bytes.push(byte);
  }
  if (!bytes.length) return null;

  if (def.describe) return def.describe(bytes);
  if (!def.decode) return null;

  const value = def.decode(bytes);
  if (value === null || !Number.isFinite(value)) return null;

  const rounded = Math.abs(value) >= 100 ? Math.round(value) : parseFloat(value.toFixed(2));
  return def.unit ? `${rounded} ${def.unit}` : `${rounded}`;
}
