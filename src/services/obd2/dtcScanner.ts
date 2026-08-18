import { OBDLinkBluetoothManager } from '../bluetooth/obdlinkBluetooth';
import { DtcDefinition, HONDA_DTC_DATABASE } from './dtcSpecs';
import { decodeReadinessMonitors, UNKNOWN_MONITORS } from './readinessMonitors';
import type { ReadinessMonitorStatus } from './readinessMonitors';

export type DtcStatusType = 'Pending' | 'Confirmed' | 'Permanent';

export interface ScannedDtc {
  code: string;
  type: DtcStatusType;
  details: DtcDefinition;
  freezeFrame?: {
    rpm: number;
    speedMph: number;
    coolantTempF: number;
    calcLoad: number;
    fuelTrimSt: number;
    fuelTrimLt: number;
  };
}

/**
 * The readiness bitmap decoder now lives in readinessMonitors.ts, because the PID
 * catalogue needs it too and cannot import this file - see the comment there. Re-exported
 * so every existing caller of `from './dtcScanner'` keeps working.
 */
export {
  decodeReadinessMonitors,
  UNKNOWN_MONITORS,
  MONITOR_LABELS,
  summariseMonitors,
} from './readinessMonitors';
export type { ReadinessMonitorStatus } from './readinessMonitors';

export interface DtcScanReport {
  timestamp: number;
  milOn: boolean;
  totalDtcCount: number;
  pendingCodes: ScannedDtc[];
  confirmedCodes: ScannedDtc[];
  permanentCodes: ScannedDtc[];
  monitors: ReadinessMonitorStatus;
}

export class DtcScannerEngine {
  private bluetooth: OBDLinkBluetoothManager;

  constructor(bluetooth: OBDLinkBluetoothManager) {
    this.bluetooth = bluetooth;
  }

  /**
   * Performs a comprehensive OBD-II Diagnostic Scan across Modes 01, 03, 07, and 0A.
   */
  public async performFullScan(isSimulating: boolean = false): Promise<DtcScanReport> {
    if (isSimulating) {
      // Return realistic simulated diagnostic data for 2013 Honda Civic
      await new Promise((r) => setTimeout(r, 900)); // realistic scan delay

      const p0133 = HONDA_DTC_DATABASE['P0133'];
      const p0456 = HONDA_DTC_DATABASE['P0456'];

      const simulatedPending: ScannedDtc[] = [
        {
          code: 'P0133',
          type: 'Pending',
          details: p0133,
          freezeFrame: {
            rpm: 2150,
            speedMph: 42,
            coolantTempF: 178,
            calcLoad: 38,
            fuelTrimSt: 4.5,
            fuelTrimLt: 2.3,
          },
        },
      ];

      const simulatedConfirmed: ScannedDtc[] = [];
      const simulatedPermanent: ScannedDtc[] = [
        {
          code: 'P0456',
          type: 'Permanent',
          details: p0456,
        },
      ];

      return {
        timestamp: Date.now(),
        milOn: false, // CEL is NOT illuminated (demonstrating pending detection!)
        totalDtcCount: simulatedPending.length + simulatedConfirmed.length + simulatedPermanent.length,
        pendingCodes: simulatedPending,
        confirmedCodes: simulatedConfirmed,
        permanentCodes: simulatedPermanent,
        monitors: {
          misfire: 'Ready',
          fuelSystem: 'Ready',
          comprehensive: 'Ready',
          catalyst: 'Ready',
          evap: 'Ready',
          o2Sensor: 'Ready',
          o2Heater: 'Ready',
          egrVvt: 'Ready',
        },
      };
    }

    // --- Live Hardware Scan via OBDLink MX+ ---
    try {
      // 1. Check MIL status and readiness monitors (Mode 01 PID 01)
      const milResp = await this.bluetooth.sendCommand('0101', 800);
      const milOn = this.parseMilStatus(milResp);
      const monitors = this.parseReadinessMonitors(milResp);

      // 2. Scan Mode 03 (Confirmed / Current Trouble Codes)
      const mode03Resp = await this.bluetooth.sendCommand('03', 1000);
      const confirmedCodes = this.decodeDtcResponse(mode03Resp, 'Confirmed');

      // 3. Scan Mode 07 (Pending Trouble Codes - Non-CEL triggers!)
      const mode07Resp = await this.bluetooth.sendCommand('07', 1000);
      const pendingCodes = this.decodeDtcResponse(mode07Resp, 'Pending');

      // 4. Scan Mode 0A (Permanent / Historic Codes in non-volatile memory)
      const mode0aResp = await this.bluetooth.sendCommand('0A', 1000);
      const permanentCodes = this.decodeDtcResponse(mode0aResp, 'Permanent');

      const totalCount = confirmedCodes.length + pendingCodes.length + permanentCodes.length;

      // 5. Freeze frame (Mode 02) - the snapshot the ECU stored when the fault set.
      // Only a confirmed code stores one, and there is a single frame (00) to read.
      if (confirmedCodes.length > 0) {
        const frame = await this.readFreezeFrame();
        if (frame) {
          confirmedCodes[0].freezeFrame = frame;
        }
      }

      return {
        timestamp: Date.now(),
        milOn,
        totalDtcCount: totalCount,
        pendingCodes,
        confirmedCodes,
        permanentCodes,
        monitors,
      };
    } catch (err: any) {
      console.error('DTC Scan Error:', err);
      throw new Error(`DTC Scanner Error: ${err.message || 'Communication failure'}`);
    }
  }

  /**
   * Clears all diagnostic codes and resets the Check Engine Light (Mode 04).
   */
  public async clearAllCodes(isSimulating: boolean = false): Promise<boolean> {
    if (isSimulating) {
      await new Promise((r) => setTimeout(r, 600));
      return true;
    }

    try {
      const resp = await this.bluetooth.sendCommand('04', 1200);
      // Response "44" indicates successful DTC reset
      return resp.includes('44') || resp.includes('OK');
    } catch (err: any) {
      console.error('Error clearing DTCs:', err);
      throw err;
    }
  }

  /**
   * Parses Mode 01 PID 01 response to check if Check Engine Light (MIL) is active.
   */
  private parseMilStatus(resp: string): boolean {
    const clean = resp.replace(/[\s\r\n>]/g, '').toUpperCase();
    const idx = clean.indexOf('4101');
    if (idx !== -1 && clean.length >= idx + 6) {
      const byteA = parseInt(clean.substring(idx + 4, idx + 6), 16);
      // Bit 7 of Byte A indicates MIL active
      return (byteA & 0x80) !== 0;
    }
    return false;
  }

  /**
   * Reads the readiness monitors out of the same Mode 01 PID 01 reply the MIL comes from.
   * Returns all-N/A rather than all-Ready when the reply is unreadable, so an unanswered
   * ECU never renders as a car that has passed every emissions self-test.
   */
  private parseReadinessMonitors(resp: string): ReadinessMonitorStatus {
    const clean = resp.replace(/[\s\r\n>]/g, '').toUpperCase();
    const idx = clean.indexOf('4101');
    if (idx === -1 || clean.length < idx + 12) {
      return UNKNOWN_MONITORS;
    }
    const b = parseInt(clean.substring(idx + 6, idx + 8), 16);
    const c = parseInt(clean.substring(idx + 8, idx + 10), 16);
    const d = parseInt(clean.substring(idx + 10, idx + 12), 16);
    if ([b, c, d].some((v) => Number.isNaN(v))) {
      return UNKNOWN_MONITORS;
    }
    return decodeReadinessMonitors(b, c, d);
  }

  /**
   * Reads freeze frame 00 over Mode 02 - the running conditions the ECU captured at the
   * moment the fault was stored. Each PID is requested individually because a single
   * multi-PID request is not something every adapter answers consistently.
   *
   * Returns undefined if the ECU has no frame stored, which is the normal answer when the
   * only codes present are pending or permanent rather than confirmed.
   */
  private async readFreezeFrame(): Promise<ScannedDtc['freezeFrame'] | undefined> {
    const read = async (pid: string): Promise<number[] | null> => {
      try {
        const resp = await this.bluetooth.sendCommand(`02${pid}00`, 900);
        const clean = resp.replace(/[\s\r\n>]/g, '').toUpperCase();
        if (clean.includes('NODATA')) return null;
        const idx = clean.indexOf(`42${pid}`);
        if (idx === -1) return null;
        // Skip the mode+PID echo and the frame number byte that follows it.
        const data = clean.substring(idx + 4 + 2);
        if (data.length < 2) return null;
        const bytes: number[] = [];
        for (let i = 0; i + 2 <= data.length && bytes.length < 2; i += 2) {
          const v = parseInt(data.substring(i, i + 2), 16);
          if (Number.isNaN(v)) return null;
          bytes.push(v);
        }
        return bytes.length ? bytes : null;
      } catch {
        return null;
      }
    };

    const [rpmB, speedB, coolantB, loadB, stftB, ltftB] = await Promise.all([
      read('0C'),
      read('0D'),
      read('05'),
      read('04'),
      read('06'),
      read('07'),
    ]);

    // No frame stored at all - report nothing rather than a snapshot of zeroes.
    if (!rpmB && !speedB && !coolantB && !loadB) {
      return undefined;
    }

    return {
      rpm: rpmB && rpmB.length >= 2 ? Math.round(((rpmB[0] * 256) + rpmB[1]) / 4) : 0,
      speedMph: speedB ? Math.round(speedB[0] * 0.621371) : 0,
      coolantTempF: coolantB ? Math.round(((coolantB[0] - 40) * 9) / 5 + 32) : 0,
      calcLoad: loadB ? parseFloat(((loadB[0] * 100) / 255).toFixed(1)) : 0,
      fuelTrimSt: stftB ? parseFloat((((stftB[0] - 128) * 100) / 128).toFixed(1)) : 0,
      fuelTrimLt: ltftB ? parseFloat((((ltftB[0] - 128) * 100) / 128).toFixed(1)) : 0,
    };
  }

  /**
   * Decodes raw hex bytes from Mode 03, 07, or 0A into standard SAE DTC codes.
   */
  private decodeDtcResponse(resp: string, type: DtcStatusType): ScannedDtc[] {
    const clean = resp.replace(/[\s\r\n>]/g, '').toUpperCase();
    const results: ScannedDtc[] = [];

    // Filter out "NO DATA" or "UNABLE TO CONNECT"
    if (clean.includes('NODATA') || clean.length < 4) {
      return [];
    }

    // Responses start with 43 (Mode 03), 47 (Mode 07), or 4A (Mode 0A)
    // Followed by pairs of bytes: Byte1 and Byte2 represent 1 DTC.
    const modePrefix = type === 'Confirmed' ? '43' : type === 'Pending' ? '47' : '4A';
    const prefixIdx = clean.indexOf(modePrefix);
    if (prefixIdx === -1) return [];

    const dtcData = clean.substring(prefixIdx + 2);
    
    // Each code is 4 hex characters (2 bytes)
    for (let i = 0; i + 4 <= dtcData.length; i += 4) {
      const byte1Hex = dtcData.substring(i, i + 2);
      const byte2Hex = dtcData.substring(i + 2, i + 4);

      if (byte1Hex === '00' && byte2Hex === '00') {
        continue; // Padding
      }

      const byte1 = parseInt(byte1Hex, 16);
      const byte2 = parseInt(byte2Hex, 16);

      // Bit manipulation for SAE Standard:
      // First 2 bits of byte 1:
      // 00 -> P (Powertrain)
      // 01 -> C (Chassis)
      // 10 -> B (Body)
      // 11 -> U (Network)
      const prefixBits = (byte1 >> 6) & 0x03;
      const prefixChar = ['P', 'C', 'B', 'U'][prefixBits];

      // Second 2 bits: 0, 1, 2, or 3
      const codeTypeBit = (byte1 >> 4) & 0x03;

      // Lower 4 bits of byte1
      const digit3 = (byte1 & 0x0F).toString(16).toUpperCase();

      // Byte 2 hex
      const digit45 = byte2.toString(16).padStart(2, '0').toUpperCase();

      const formattedCode = `${prefixChar}${codeTypeBit}${digit3}${digit45}`;

      // Match with Honda database or provide generic definition
      const def: DtcDefinition = HONDA_DTC_DATABASE[formattedCode] || {
        code: formattedCode,
        category: prefixChar === 'P' ? 'Powertrain' : prefixChar === 'C' ? 'Chassis' : prefixChar === 'B' ? 'Body' : 'Network',
        system: 'General Diagnostic Code',
        title: `Generic Fault Code ${formattedCode}`,
        description: `Standard OBD-II diagnostic fault code detected on the 2013 Honda Civic ECU.`,
        severity: type === 'Confirmed' ? 'Moderate' : 'Minor',
        symptoms: ['Potential driveability, emissions, or sensor communication anomaly'],
        possibleCauses: ['Sensor reading outside normal operating parameters', 'Intermittent wiring connection'],
      };

      results.push({
        code: formattedCode,
        type,
        details: def,
      });
    }

    return results;
  }
}
