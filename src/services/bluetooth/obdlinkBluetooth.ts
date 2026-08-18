/**
 * STN/ELM327 protocol client for the 2013 Civic's ISO 15765-4 CAN bus.
 *
 * Transport-agnostic by design: the AT handshake, PID polling and response parsing below
 * are identical whether the bytes travel over Bluetooth LE in a browser or Bluetooth
 * Classic RFCOMM in the native app. See transport.ts for why that split exists.
 */
import { ObdTransport, ObdConnectOptions, ObdTransportError } from './transport';
import { WebBluetoothTransport, OBDLINK_SERVICE_UUIDS } from './webBluetoothTransport';
import { ClassicSppTransport, isNativePlatform } from './classicSppTransport';
import {
  BANK_MARKER_PIDS,
  pidsInUseFor,
  choosePid,
  LAMBDA_PID_CANDIDATES,
  PRE_CAT_PID_CANDIDATES,
  OUTSIDE_AIR_PID_CANDIDATES,
  decodePidValue,
  pidCommand,
  pidLabel,
} from '../obd2/pidCatalog';

export { OBDLINK_SERVICE_UUIDS };

/** One PID the car reported it supports, with whatever it answered when asked. */
export interface PidProbeResult {
  pid: number;
  cmd: string;
  name: string;
  raw: string;
  /** Decoded reading, or null when the catalogue has no formula - `raw` still holds the hex. */
  value: string | null;
  isBankMarker: boolean;
  /** Whether a gauge already consumes this PID. */
  inUse: boolean;
  /**
   * The hex data bytes, or null when nothing parseable came back.
   *
   * This exists so "the car answered" and "we understood the answer" can be counted
   * separately. The discovery screen used to derive one from the other and label the
   * result "Answered", which undercounted by every PID that replied without a formula -
   * six of them on this Civic. A screen whose whole purpose is honest measurement should
   * not be the thing miscounting.
   */
  payload: string | null;
}

/**
 * Command timeouts.
 *
 * These are not arbitrary. A reply that arrives after its own command has timed out is
 * indistinguishable, on a stream with no request IDs, from the next command's reply - so a
 * timeout that fires early does not merely lose one answer, it shifts every later answer
 * one command out of step and the parsers stop recognising anything. Generous is cheap;
 * early is silently fatal. See drainLine() for the other half of that defence.
 */
/** AT Z reboots the STN chip in the MX+, which takes appreciably longer than an ELM327 clone. */
const RESET_TIMEOUT_MS = 6000;
/** Configuration ATs answer from RAM and are quick, but not free over RFCOMM. */
const AT_INIT_TIMEOUT_MS = 2500;
/** The first real request has to bring the CAN bus up, which is the slowest thing here. */
const BUS_PROBE_TIMEOUT_MS = 6000;
/** Steady-state PID polling, once the bus is known good. */
const PID_TIMEOUT_MS = 1200;

/** One command and whatever came back, for the on-screen adapter log. */
export interface ProtocolLogEntry {
  at: number;
  cmd: string;
  resp: string;
}

/** Where an outside-air figure came from, because 0F is not the same quantity as 46. */
export type OutsideAirSource = 'ambient' | 'intake';

/*
 * Readings the car may simply not have are `number | null`, and start as null.
 *
 * Every field here used to be a number seeded with a plausible idle value - lambda 1.0,
 * ambient 22 C, pre-catalyst 0.45 V. When the PID behind one of those does not exist, the
 * seed is what the gauge displays, indefinitely, and it is indistinguishable on screen
 * from a measurement. This Civic supports none of PIDs 24, 46 or 14, so all three of those
 * numbers were fabricated. Null makes "never measured" representable, and the display layer
 * has to say so rather than print a number.
 *
 * The fields that stay non-null are the ones every OBD-II car answers; their seeds are only
 * ever visible for the fraction of a second before the first poll returns.
 */
export interface RawObdData {
  rpm: number;
  speedKmh: number;
  maf: number;
  coolantC: number;
  engineLoad: number;
  throttlePos: number;
  stft: number;
  ltft: number;
  timingAdvance: number;
  /** Wide-range lambda from PID 24 or 34, or null when the car has neither. */
  lambda: number | null;
  batteryVoltage: number;
  fuelLevelPercent: number;
  /** Outside air, or null when neither PID 46 nor 0F answered. */
  ambientC: number | null;
  /** Which PID the figure above came from, so it can be labelled truthfully. */
  ambientSource: OutsideAirSource | null;
  /** Pre-catalyst narrowband voltage from PID 14, null on a car that reports lambda instead. */
  o2Sensor1Voltage: number | null;
  /** Pre-catalyst lambda from PID 34, null on a car with a narrowband there instead. */
  o2Sensor1Lambda: number | null;
  /** Wide-range sensor current in mA from PID 34. Near zero means sitting at balance. */
  o2Sensor1CurrentMa: number | null;
  o2Sensor2Voltage: number;
  engineRuntimeSec: number;
}

export class OBDLinkBluetoothManager {
  private transport: ObdTransport;
  private isPolling = false;
  private incomingBuffer = '';
  private pendingResolver: ((value: string) => void) | null = null;

  /**
   * Set when a command times out. The next command must not be sent until the line has
   * fallen quiet, or the late reply lands on it and every answer from then on is off by one.
   */
  private needsDrain = false;

  /**
   * Total bytes seen, ever. drainLine() watches this rather than the buffer length because
   * handleIncoming() empties the buffer on every '>' - including replies nobody is waiting
   * for, which are exactly the ones being drained.
   */
  private bytesReceived = 0;

  private readonly logEntries: ProtocolLogEntry[] = [];
  private logListener: ((entries: ProtocolLogEntry[]) => void) | null = null;
  /**
   * Logging is verbose for the handshake and the first full poll cycle, then drops to
   * failures only. The first cycle is the part worth seeing: it shows every PID's actual
   * reply once, which is what says whether a gauge is stuck because the car said nothing
   * or because the reply was not understood. Failures-only hid exactly that - a PID
   * answering NO DATA is a non-empty reply, so nothing was recorded at all.
   */
  private verboseLog = true;

  /** PIDs the car reported via 0100/0120/0140. Empty means "unknown, poll everything". */
  private readonly supportedPids = new Set<number>();

  /**
   * Which PID supplies each reading that more than one PID can supply, decided once from
   * the support bitmaps. Resolved through the shared candidate lists in pidCatalog, so the
   * discovery screen's "already drives a gauge" tick cannot disagree with what is polled.
   */
  private lambdaPid: number | null = null;
  private preCatPid: number | null = null;
  private outsideAirPid: number | null = null;

  /** Commands already recorded once, so steady-state polling stops adding log lines. */
  private readonly loggedOnce = new Set<string>();

  /** Tail of the command queue. See sendCommand for why every caller shares one line. */
  private commandChain: Promise<void> = Promise.resolve();

  public latestData: RawObdData = {
    rpm: 0,
    speedKmh: 0,
    maf: 2.8,
    coolantC: 85,
    engineLoad: 20,
    throttlePos: 14,
    stft: 0,
    ltft: 0,
    timingAdvance: 10,
    lambda: null,
    batteryVoltage: 14.2,
    fuelLevelPercent: 65,
    ambientC: null,
    ambientSource: null,
    o2Sensor1Voltage: null,
    o2Sensor1Lambda: null,
    o2Sensor1CurrentMa: null,
    o2Sensor2Voltage: 0.65,
    engineRuntimeSec: 0,
  };

  constructor(transport?: ObdTransport) {
    // Native builds get Bluetooth Classic, the only way to reach an OBDLink MX+; the
    // browser gets Bluetooth LE, which is all it is permitted to speak.
    this.transport =
      transport ?? (isNativePlatform() ? new ClassicSppTransport() : new WebBluetoothTransport());
    this.transport.setDataHandler((chunk) => this.handleIncoming(chunk));
    this.transport.setDisconnectHandler(() => {
      this.isPolling = false;
    });
  }

  public get transportKind(): 'ble' | 'spp' {
    return this.transport.kind;
  }

  public get transportLabel(): string {
    return this.transport.label;
  }

  public isSupported(): boolean {
    return this.transport instanceof WebBluetoothTransport ? this.transport.isSupported() : true;
  }

  public isSecureContext(): boolean {
    return this.transport instanceof WebBluetoothTransport ? this.transport.isSecureContext() : true;
  }

  public async isAdapterAvailable(): Promise<boolean> {
    return this.transport.isAvailable();
  }

  /** Paired adapters. Native transport only - empty in the browser. */
  public async listPairedAdapters(): Promise<{ name: string; address: string }[]> {
    if (this.transport instanceof ClassicSppTransport) {
      try {
        return await this.transport.listAdapters();
      } catch {
        return [];
      }
    }
    return [];
  }

  public async connect(
    onStatus?: (msg: string) => void,
    options: ObdConnectOptions = {}
  ): Promise<boolean> {
    this.logEntries.length = 0;
    this.loggedOnce.clear();
    this.verboseLog = true;
    this.needsDrain = false;
    this.incomingBuffer = '';

    await this.transport.connect(onStatus, options);

    onStatus?.('Initializing ISO 15765-4 CAN protocol...');
    await this.initializeElm327(onStatus);

    onStatus?.('Connected & streaming telemetry');
    this.verboseLog = false; // Polling now records each PID once - see sendCommand
    this.startPollingLoop();
    return true;
  }

  private handleIncoming(chunk: string): void {
    this.incomingBuffer += chunk;
    this.bytesReceived += chunk.length;

    // ELM327/STN terminates every response with the '>' prompt.
    if (this.incomingBuffer.includes('>')) {
      const response = this.incomingBuffer.trim();
      this.incomingBuffer = '';
      if (this.pendingResolver) {
        const resolve = this.pendingResolver;
        this.pendingResolver = null;
        resolve(response);
      } else {
        // Nobody is waiting: this is a late reply to a command that already timed out.
        // Dropping it here is the point - drainLine() is what stops the next command
        // being sent until these have stopped arriving.
        this.log('(late)', response);
      }
    }
  }

  /**
   * Waits for the adapter to stop talking, then discards whatever it said.
   *
   * Called after a timeout. Without it, the reply the adapter was still composing arrives
   * mid-way through the next command and satisfies its resolver, leaving every subsequent
   * response one command behind - which parses as nothing at all, so the gauges sit on
   * their defaults and the app reports a healthy connection.
   */
  private async drainLine(quietMs = 250, maxMs = 4000): Promise<void> {
    const start = Date.now();
    while (Date.now() - start < maxMs) {
      const before = this.bytesReceived;
      await new Promise((r) => setTimeout(r, quietMs));
      if (this.bytesReceived === before) break;
    }
    this.incomingBuffer = '';
    this.needsDrain = false;
  }

  /** Recent exchanges, oldest first, for the connection screen. */
  public getProtocolLog(): ProtocolLogEntry[] {
    return [...this.logEntries];
  }

  public setLogListener(listener: ((entries: ProtocolLogEntry[]) => void) | null): void {
    this.logListener = listener;
  }

  private log(cmd: string, resp: string): void {
    this.logEntries.push({ at: Date.now(), cmd, resp: resp || '(no reply)' });
    if (this.logEntries.length > 60) this.logEntries.shift();
    this.logListener?.(this.getProtocolLog());
  }

  public disconnect(): void {
    this.isPolling = false;
    this.incomingBuffer = '';
    this.pendingResolver = null;
    void this.transport.disconnect();
  }

  /**
   * Queues a command behind every command already issued, and resolves with its reply.
   *
   * The queue is the point. An ELM327 has one command in flight at a time and no way to
   * label which reply belongs to which request - so two callers writing at once do not
   * merely interleave, they permanently swap replies, and the adapter aborts the
   * half-written one with STOPPED. That is exactly what happened: the DTC scanner shares
   * this instance with the polling loop, its 0101 collided with a PID request mid-reply,
   * and every gauge froze on its last good value while the connection still looked healthy.
   *
   * Serialising here rather than in the callers means a future third caller cannot
   * reintroduce it by forgetting.
   */
  public sendCommand(cmd: string, timeoutMs: number = PID_TIMEOUT_MS): Promise<string> {
    const run = this.commandChain.then(() => this.executeCommand(cmd, timeoutMs));
    // Swallow failures for the chain's own purposes only - `run` still rejects for the
    // caller. Without this a single rejected command would stall every command behind it.
    this.commandChain = run.then(
      () => undefined,
      () => undefined
    );
    return run;
  }

  private async executeCommand(cmd: string, timeoutMs: number): Promise<string> {
    // A previous command timed out, so its reply may still be in flight. Let it land and
    // throw it away before putting a new command on the wire.
    if (this.needsDrain) await this.drainLine();

    const cleanCmd = cmd.trim() + '\r';
    this.incomingBuffer = '';

    const response = await new Promise<string>((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.pendingResolver) {
          this.pendingResolver = null;
          this.needsDrain = true;
          resolve(''); // Timeout fallback without crashing the polling loop
        }
      }, timeoutMs);

      this.pendingResolver = (resp: string) => {
        clearTimeout(timer);
        resolve(resp);
      };

      this.transport.write(cleanCmd).catch((err) => {
        clearTimeout(timer);
        this.pendingResolver = null;
        reject(err);
      });
    });

    // Polling runs six-plus commands a second, so logging all of it would bury the
    // handshake within seconds. Each PID is instead recorded once, the first time it is
    // asked: that is a complete picture of what this car answers, in about sixteen lines,
    // and it does not grow. Timeouts are always recorded, whenever they happen.
    if (this.verboseLog || !response || !this.loggedOnce.has(cmd)) {
      this.loggedOnce.add(cmd);
      this.log(cmd, response);
    }
    return response;
  }

  private async initializeElm327(onStatus?: (msg: string) => void): Promise<void> {
    // Reset and configure ELM327 / STN protocol
    const banner = await this.sendCommand('AT Z', RESET_TIMEOUT_MS);
    if (!banner) {
      throw new ObdTransportError(
        'The adapter accepted a Bluetooth connection but never answered the reset command. ' +
          'That is usually another app still holding the link — close the OBDLink app ' +
          'completely (swipe it away, do not just background it) and try again.'
      );
    }

    await this.sendCommand('AT E0', AT_INIT_TIMEOUT_MS); // Echo off
    await this.sendCommand('AT L0', AT_INIT_TIMEOUT_MS); // Linefeeds off
    await this.sendCommand('AT S0', AT_INIT_TIMEOUT_MS); // Spaces off
    await this.sendCommand('AT H0', AT_INIT_TIMEOUT_MS); // Headers off

    // Protocol 7 is ISO 15765-4 CAN with 29-bit IDs. This car answers on 7, not the 6
    // (11-bit) you would expect of a 2013 Civic - measured, via AT DPN reporting A7 after
    // an auto-detect. Protocol 6 returns NO DATA here, so 7 is tried first and 6 second.
    onStatus?.('Waking the CAN bus...');
    for (const proto of ['7', '6']) {
      await this.sendCommand(`AT SP ${proto}`, AT_INIT_TIMEOUT_MS);
      if (await this.probeBus()) {
        await this.loadSupportedPids();
        return;
      }
    }

    // Neither fixed protocol answered. Auto-detect is slower, but if the car replies to it
    // the log says which protocol worked and the list above can be corrected properly.
    onStatus?.('Fixed protocols got no answer — trying auto-detect...');
    await this.sendCommand('AT SP 0', AT_INIT_TIMEOUT_MS);
    if (await this.probeBus()) {
      await this.sendCommand('AT DPN', AT_INIT_TIMEOUT_MS); // Logs the protocol that worked
      await this.loadSupportedPids();
      return;
    }

    throw new ObdTransportError(
      'The adapter is connected and answering, but the car is not. Turn the ignition to ' +
        'ON / II — the ECU powers down otherwise, and the adapter stays awake on its own, ' +
        'which is why it still pairs. Check the adapter log below for what it replied.'
    );
  }

  /**
   * Reads the three supported-PID bitmaps so polling can skip what this car does not have.
   *
   * Worth the three extra commands: a 2013 Civic LX has no PID 14 (O2 sensor 1), and asking
   * for it every cycle costs a real round-trip to be told NO DATA. An empty set here means
   * the bitmaps could not be read, and everything is polled as before rather than nothing.
   */
  private async loadSupportedPids(): Promise<void> {
    this.supportedPids.clear();
    // Every bank, not just the first three. Each is only asked for if the previous one set
    // its continuation bit, so a car that stops at 0x20 still costs exactly two commands.
    for (const [base, cmd] of [
      [0x00, '0100'],
      [0x20, '0120'],
      [0x40, '0140'],
      [0x60, '0160'],
      [0x80, '0180'],
      [0xa0, '01A0'],
      [0xc0, '01C0'],
    ] as const) {
      const hex = this.extractHexBytes(
        await this.sendCommand(cmd, AT_INIT_TIMEOUT_MS),
        `41${cmd.slice(2)}`
      );
      if (!hex || hex.length < 8) break;
      const mask = parseInt(hex.substring(0, 8), 16);
      for (let bit = 0; bit < 32; bit++) {
        if (mask & (0x80000000 >>> bit)) this.supportedPids.add(base + bit + 1);
      }
      if (!this.supportedPids.has(base + 0x20)) break; // Bit 32 says whether the next bank exists
    }

    this.resolveOptionalPids();
  }

  /**
   * Enumerates every PID the car reports, then reads each one so the value can be shown
   * alongside it. This is the measurement that replaces guessing what a 2013 Civic exposes.
   *
   * Runs through the same command queue as everything else, so the gauges keep their data -
   * they simply share the line and slow down while this is running.
   */
  public async discoverPids(
    onProgress?: (done: number, total: number) => void
  ): Promise<PidProbeResult[]> {
    // Always re-read rather than trusting the set cached at connect: this screen exists to
    // report what the car says now, and a stale list would defeat the point of it.
    await this.loadSupportedPids();

    const pids = [...this.supportedPids].sort((a, b) => a - b);
    // The same rule the poll loop uses, so a ticked row is one that really is being read.
    const inUsePids = pidsInUseFor(this.supportedPids);
    const results: PidProbeResult[] = [];

    for (const pid of pids) {
      const cmd = pidCommand(pid);

      // The bank markers are support bitmaps, not readings. Listing them as sensors with a
      // decoded value would be nonsense, but hiding them loses the fact that they answered.
      if (BANK_MARKER_PIDS.has(pid)) {
        results.push({
          pid,
          cmd,
          name: `Support map for PIDs ${(pid + 1).toString(16).toUpperCase()}–${(pid + 0x20)
            .toString(16)
            .toUpperCase()}`,
          raw: '',
          payload: null,
          value: null,
          isBankMarker: true,
          inUse: false,
        });
        onProgress?.(results.length, pids.length);
        continue;
      }

      const raw = await this.sendCommand(cmd, 1500);
      const payload = this.extractHexBytes(raw, `41${cmd.slice(2)}`);
      results.push({
        pid,
        cmd,
        name: pidLabel(pid),
        raw: raw.replace(/[\r\n]+/g, ' ').trim(),
        payload: payload || null,
        value: payload ? decodePidValue(pid, payload) : null,
        isBankMarker: false,
        inUse: inUsePids.has(pid),
      });
      onProgress?.(results.length, pids.length);
    }

    return results;
  }

  /**
   * Decides which PID supplies lambda, the pre-catalyst sensor and outside air.
   *
   * Called after every bitmap read, because a stale choice is the same class of bug as a
   * hardcoded one. The resolution itself lives in pidCatalog so the discovery screen can
   * apply the identical rule when it marks a row as already driving a gauge.
   */
  private resolveOptionalPids(): void {
    this.lambdaPid = choosePid(LAMBDA_PID_CANDIDATES, this.supportedPids);
    this.preCatPid = choosePid(PRE_CAT_PID_CANDIDATES, this.supportedPids);
    this.outsideAirPid = choosePid(OUTSIDE_AIR_PID_CANDIDATES, this.supportedPids);

    const name = (pid: number | null) =>
      pid === null ? 'none' : pid.toString(16).toUpperCase().padStart(2, '0');

    this.log(
      'PID-SELECT',
      'lambda=' +
        name(this.lambdaPid) +
        ' pre-cat=' +
        name(this.preCatPid) +
        ' outside-air=' +
        name(this.outsideAirPid)
    );
  }

  /** False only when the bitmaps were read and positively say the car lacks this PID. */
  private isPidSupported(cmd: string): boolean {
    if (!this.supportedPids.size) return true;
    return this.supportedPids.has(parseInt(cmd.slice(2), 16));
  }

  /**
   * One PID request. Skips PIDs the car reported it does not have, and returns '' for them
   * so the parsers leave the previous value alone.
   */
  private async pollPid(cmd: string): Promise<string> {
    if (!this.isPidSupported(cmd)) return '';
    return this.sendCommand(cmd);
  }

  /**
   * Asks the ECU which PIDs it supports. Cheapest possible proof that the bus is actually
   * up, and the one thing the old handshake never did - it set a protocol and went straight
   * to polling, so a bus that never came up looked identical to a working one.
   */
  private async probeBus(): Promise<boolean> {
    for (let attempt = 0; attempt < 3; attempt++) {
      const resp = await this.sendCommand('0100', BUS_PROBE_TIMEOUT_MS);
      if (resp.replace(/[\s\r\n>]/g, '').toUpperCase().includes('4100')) return true;
    }
    return false;
  }

  private async startPollingLoop(): Promise<void> {
    this.isPolling = true;
    let cycle = 0;

    while (this.isPolling) {
      try {
        // High-frequency primary PIDs (polled every cycle: RPM, Speed, MAF, Throttle)
        const rpmResp = await this.pollPid('010C');
        this.parseRpm(rpmResp);

        const speedResp = await this.pollPid('010D');
        this.parseSpeed(speedResp);

        const mafResp = await this.pollPid('0110');
        this.parseMaf(mafResp);

        const throttleResp = await this.pollPid('0111');
        this.parseThrottle(throttleResp);

        // O2 sensor readings oscillate rapidly (pre-cat especially) - a slow poll would
        // alias them into a flat line, which defeats the point of showing a live trace.
        //
        // Which PID that is depends on the car. A narrowband front sensor answers PID 14
        // with a voltage; a wide-range one answers PID 34 with lambda and current. This
        // Civic has only 34, and asking it for 14 forever was why the "Pre-catalyst" row
        // showed a constant 0.45 V that had never come from the car.
        if (this.preCatPid === 0x34) {
          // One request covers both jobs: 34 is the pre-catalyst sensor *and* the wideband
          // the fuel model needs, so polling it here also keeps lambda at this tier's rate.
          this.parseWideRangeO2(await this.pollPid('0134'));
        } else if (this.preCatPid === 0x14) {
          this.parseO2Sensor1(await this.pollPid('0114'));
        }

        const o2s2Resp = await this.pollPid('0115');
        this.parseO2Sensor2(o2s2Resp);

        // Medium-frequency secondary PIDs (polled every 5-10 cycles)
        if (cycle % 6 === 0) {
          const coolantResp = await this.pollPid('0105');
          this.parseCoolant(coolantResp);

          const loadResp = await this.pollPid('0104');
          this.parseLoad(loadResp);

          const timingResp = await this.pollPid('010E');
          this.parseTiming(timingResp);

          const batteryResp = await this.pollPid('0142');
          this.parseBatteryVoltage(batteryResp);

          // PID 46 is outside air; 0F is intake air, which is a different quantity and gets
          // labelled as one. Neither existing leaves the reading null rather than at 22 C.
          if (this.outsideAirPid === 0x46) {
            this.parseOutsideAirTemp(await this.pollPid('0146'), 'ambient');
          } else if (this.outsideAirPid === 0x0f) {
            this.parseOutsideAirTemp(await this.pollPid('010F'), 'intake');
          }
        }

        if (cycle % 12 === 0) {
          const stftResp = await this.pollPid('0106');
          this.parseStft(stftResp);

          const ltftResp = await this.pollPid('0107');
          this.parseLtft(ltftResp);

          // Only when lambda is not already arriving with the pre-catalyst read above.
          // On this Civic the two are the same PID, so this branch does not run at all;
          // a car with a narrowband front sensor plus a separate wideband needs it to.
          if (this.lambdaPid !== null && this.lambdaPid !== this.preCatPid) {
            if (this.lambdaPid === 0x24) {
              this.parseLambda(await this.pollPid('0124'));
            } else if (this.lambdaPid === 0x34) {
              this.parseWideRangeO2(await this.pollPid('0134'));
            }
          }

          // Fuel level & engine runtime change slowly - the slowest tier is plenty.
          const fuelLevelResp = await this.pollPid('012F');
          this.parseFuelLevel(fuelLevelResp);

          const runtimeResp = await this.pollPid('011F');
          this.parseRuntime(runtimeResp);
        }

        cycle++;
        // Low sleep to maximize refresh rate on STN processor
        await new Promise((r) => setTimeout(r, 15));
      } catch (err) {
        console.warn('OBDLink polling cycle warning:', err);
        await new Promise((r) => setTimeout(r, 200));
      }
    }
  }

  // --- PID Parsers ---

  private parseRpm(resp: string): void {
    const hex = this.extractHexBytes(resp, '410C');
    if (hex && hex.length >= 4) {
      const a = parseInt(hex.substring(0, 2), 16);
      const b = parseInt(hex.substring(2, 4), 16);
      this.latestData.rpm = Math.round(((a * 256) + b) / 4);
    }
  }

  private parseSpeed(resp: string): void {
    const hex = this.extractHexBytes(resp, '410D');
    if (hex && hex.length >= 2) {
      const a = parseInt(hex.substring(0, 2), 16);
      this.latestData.speedKmh = a;
    }
  }

  private parseMaf(resp: string): void {
    const hex = this.extractHexBytes(resp, '4110');
    if (hex && hex.length >= 4) {
      const a = parseInt(hex.substring(0, 2), 16);
      const b = parseInt(hex.substring(2, 4), 16);
      this.latestData.maf = parseFloat((((a * 256) + b) / 100).toFixed(2));
    }
  }

  private parseThrottle(resp: string): void {
    const hex = this.extractHexBytes(resp, '4111');
    if (hex && hex.length >= 2) {
      const a = parseInt(hex.substring(0, 2), 16);
      this.latestData.throttlePos = parseFloat(((a * 100) / 255).toFixed(1));
    }
  }

  private parseCoolant(resp: string): void {
    const hex = this.extractHexBytes(resp, '4105');
    if (hex && hex.length >= 2) {
      const a = parseInt(hex.substring(0, 2), 16);
      this.latestData.coolantC = a - 40;
    }
  }

  private parseLoad(resp: string): void {
    const hex = this.extractHexBytes(resp, '4104');
    if (hex && hex.length >= 2) {
      const a = parseInt(hex.substring(0, 2), 16);
      this.latestData.engineLoad = parseFloat(((a * 100) / 255).toFixed(1));
    }
  }

  private parseTiming(resp: string): void {
    const hex = this.extractHexBytes(resp, '410E');
    if (hex && hex.length >= 2) {
      const a = parseInt(hex.substring(0, 2), 16);
      this.latestData.timingAdvance = parseFloat(((a / 2) - 64).toFixed(1));
    }
  }

  private parseStft(resp: string): void {
    const hex = this.extractHexBytes(resp, '4106');
    if (hex && hex.length >= 2) {
      const a = parseInt(hex.substring(0, 2), 16);
      this.latestData.stft = parseFloat((((a - 128) * 100) / 128).toFixed(1));
    }
  }

  private parseLtft(resp: string): void {
    const hex = this.extractHexBytes(resp, '4107');
    if (hex && hex.length >= 2) {
      const a = parseInt(hex.substring(0, 2), 16);
      this.latestData.ltft = parseFloat((((a - 128) * 100) / 128).toFixed(1));
    }
  }

  /** PID 24: wide-range lambda with sensor voltage. Only the lambda word is used here. */
  private parseLambda(resp: string): void {
    const hex = this.extractHexBytes(resp, '4124');
    if (hex && hex.length >= 4) {
      const a = parseInt(hex.substring(0, 2), 16);
      const b = parseInt(hex.substring(2, 4), 16);
      this.latestData.lambda = parseFloat((((a * 256) + b) / 32768).toFixed(3));
    }
  }

  /**
   * PID 34: the wide-range front sensor, as lambda plus sensor current.
   *
   * Bytes A and B are the same lambda word as PID 24, which is why a car with 34 and no 24
   * still has everything the fuel model needs. Bytes C and D are current in mA, offset by
   * 128; near zero means the sensor is sitting at balance, which is a healthy closed loop.
   */
  private parseWideRangeO2(resp: string): void {
    const hex = this.extractHexBytes(resp, '4134');
    if (!hex || hex.length < 4) return;

    const a = parseInt(hex.substring(0, 2), 16);
    const b = parseInt(hex.substring(2, 4), 16);
    const lambda = parseFloat((((a * 256) + b) / 32768).toFixed(3));
    this.latestData.o2Sensor1Lambda = lambda;
    this.latestData.lambda = lambda;

    if (hex.length >= 8) {
      const c = parseInt(hex.substring(4, 6), 16);
      const d = parseInt(hex.substring(6, 8), 16);
      this.latestData.o2Sensor1CurrentMa = parseFloat((((c * 256) + d) / 256 - 128).toFixed(2));
    }
  }

  private parseBatteryVoltage(resp: string): void {
    const hex = this.extractHexBytes(resp, '4142');
    if (hex && hex.length >= 4) {
      const a = parseInt(hex.substring(0, 2), 16);
      const b = parseInt(hex.substring(2, 4), 16);
      this.latestData.batteryVoltage = parseFloat((((a * 256) + b) / 1000).toFixed(2));
    }
  }

  private parseFuelLevel(resp: string): void {
    const hex = this.extractHexBytes(resp, '412F');
    if (hex && hex.length >= 2) {
      const a = parseInt(hex.substring(0, 2), 16);
      this.latestData.fuelLevelPercent = parseFloat(((a * 100) / 255).toFixed(1));
    }
  }

  /**
   * Outside air, from PID 46 where it exists and PID 0F where it does not.
   *
   * The source is recorded rather than discarded. They are genuinely different readings -
   * intake air sits in the engine bay and read 51 C on a warm idle here, which is not the
   * weather - so the screen has to be able to say which one it is showing.
   */
  private parseOutsideAirTemp(resp: string, source: OutsideAirSource): void {
    const prefix = source === 'ambient' ? '4146' : '410F';
    const hex = this.extractHexBytes(resp, prefix);
    if (hex && hex.length >= 2) {
      const a = parseInt(hex.substring(0, 2), 16);
      this.latestData.ambientC = a - 40;
      this.latestData.ambientSource = source;
    }
  }

  private parseO2Sensor1(resp: string): void {
    const hex = this.extractHexBytes(resp, '4114');
    if (hex && hex.length >= 2) {
      const a = parseInt(hex.substring(0, 2), 16);
      this.latestData.o2Sensor1Voltage = parseFloat((a / 200).toFixed(3));
    }
  }

  private parseO2Sensor2(resp: string): void {
    const hex = this.extractHexBytes(resp, '4115');
    if (hex && hex.length >= 2) {
      const a = parseInt(hex.substring(0, 2), 16);
      this.latestData.o2Sensor2Voltage = parseFloat((a / 200).toFixed(3));
    }
  }

  private parseRuntime(resp: string): void {
    const hex = this.extractHexBytes(resp, '411F');
    if (hex && hex.length >= 4) {
      const a = parseInt(hex.substring(0, 2), 16);
      const b = parseInt(hex.substring(2, 4), 16);
      this.latestData.engineRuntimeSec = (a * 256) + b;
    }
  }

  private extractHexBytes(rawResp: string, prefix: string): string | null {
    const clean = rawResp.replace(/[\s\r\n>]/g, '').toUpperCase();
    const idx = clean.indexOf(prefix);
    if (idx !== -1) {
      return clean.substring(idx + prefix.length);
    }
    return null;
  }
}
