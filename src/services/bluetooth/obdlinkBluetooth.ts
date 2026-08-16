/**
 * STN/ELM327 protocol client for the 2013 Civic's ISO 15765-4 CAN bus.
 *
 * Transport-agnostic by design: the AT handshake, PID polling and response parsing below
 * are identical whether the bytes travel over Bluetooth LE in a browser or Bluetooth
 * Classic RFCOMM in the native app. See transport.ts for why that split exists.
 */
import { ObdTransport, ObdConnectOptions } from './transport';
import { WebBluetoothTransport, OBDLINK_SERVICE_UUIDS } from './webBluetoothTransport';
import { ClassicSppTransport, isNativePlatform } from './classicSppTransport';

export { OBDLINK_SERVICE_UUIDS };

export interface RawObdData {
  rpm: number;
  speedKmh: number;
  maf: number;
  coolantC: number;
  iatC: number;
  engineLoad: number;
  throttlePos: number;
  stft: number;
  ltft: number;
  timingAdvance: number;
  lambda: number;
  batteryVoltage: number;
  fuelLevelPercent: number;
  ambientC: number;
  o2Sensor1Voltage: number;
  o2Sensor2Voltage: number;
  engineRuntimeSec: number;
}

export class OBDLinkBluetoothManager {
  private transport: ObdTransport;
  private isPolling = false;
  private incomingBuffer = '';
  private pendingResolver: ((value: string) => void) | null = null;

  public latestData: RawObdData = {
    rpm: 0,
    speedKmh: 0,
    maf: 2.8,
    coolantC: 85,
    iatC: 22,
    engineLoad: 20,
    throttlePos: 14,
    stft: 0,
    ltft: 0,
    timingAdvance: 10,
    lambda: 1.0,
    batteryVoltage: 14.2,
    fuelLevelPercent: 65,
    ambientC: 22,
    o2Sensor1Voltage: 0.45,
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
    await this.transport.connect(onStatus, options);

    onStatus?.('Initializing ISO 15765-4 CAN protocol...');
    await this.initializeElm327();

    onStatus?.('Connected & streaming telemetry');
    this.startPollingLoop();
    return true;
  }

  private handleIncoming(chunk: string): void {
    this.incomingBuffer += chunk;

    // ELM327/STN terminates every response with the '>' prompt.
    if (this.incomingBuffer.includes('>')) {
      const response = this.incomingBuffer.trim();
      this.incomingBuffer = '';
      if (this.pendingResolver) {
        const resolve = this.pendingResolver;
        this.pendingResolver = null;
        resolve(response);
      }
    }
  }

  public disconnect(): void {
    this.isPolling = false;
    this.incomingBuffer = '';
    this.pendingResolver = null;
    void this.transport.disconnect();
  }

  public async sendCommand(cmd: string, timeoutMs: number = 600): Promise<string> {
    const cleanCmd = cmd.trim() + '\r';

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.pendingResolver) {
          this.pendingResolver = null;
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
  }

  private async initializeElm327(): Promise<void> {
    // Reset and configure ELM327 / STN protocol
    await this.sendCommand('AT Z', 1000); // Reset
    await this.sendCommand('AT E0');      // Echo off
    await this.sendCommand('AT L0');      // Linefeeds off
    await this.sendCommand('AT S0');      // Spaces off
    await this.sendCommand('AT H0');      // Headers off
    // ISO 15765-4 (CAN 11-bit 500k) is Protocol 6
    await this.sendCommand('AT SP 6');
  }

  private async startPollingLoop(): Promise<void> {
    this.isPolling = true;
    let cycle = 0;

    while (this.isPolling) {
      try {
        // High-frequency primary PIDs (polled every cycle: RPM, Speed, MAF, Throttle)
        const rpmResp = await this.sendCommand('010C');
        this.parseRpm(rpmResp);

        const speedResp = await this.sendCommand('010D');
        this.parseSpeed(speedResp);

        const mafResp = await this.sendCommand('0110');
        this.parseMaf(mafResp);

        const throttleResp = await this.sendCommand('0111');
        this.parseThrottle(throttleResp);

        // O2 sensor voltages oscillate rapidly (pre-cat especially) - a slow poll would
        // alias them into a flat line, which defeats the point of showing a live trace.
        const o2s1Resp = await this.sendCommand('0114');
        this.parseO2Sensor1(o2s1Resp);

        const o2s2Resp = await this.sendCommand('0115');
        this.parseO2Sensor2(o2s2Resp);

        // Medium-frequency secondary PIDs (polled every 5-10 cycles)
        if (cycle % 6 === 0) {
          const coolantResp = await this.sendCommand('0105');
          this.parseCoolant(coolantResp);

          const loadResp = await this.sendCommand('0104');
          this.parseLoad(loadResp);

          const timingResp = await this.sendCommand('010E');
          this.parseTiming(timingResp);

          const batteryResp = await this.sendCommand('0142');
          this.parseBatteryVoltage(batteryResp);

          const ambientResp = await this.sendCommand('0146');
          this.parseAmbientTemp(ambientResp);
        }

        if (cycle % 12 === 0) {
          const stftResp = await this.sendCommand('0106');
          this.parseStft(stftResp);

          const ltftResp = await this.sendCommand('0107');
          this.parseLtft(ltftResp);

          const lambdaResp = await this.sendCommand('0124');
          this.parseLambda(lambdaResp);

          // Fuel level & engine runtime change slowly - the slowest tier is plenty.
          const fuelLevelResp = await this.sendCommand('012F');
          this.parseFuelLevel(fuelLevelResp);

          const runtimeResp = await this.sendCommand('011F');
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

  private parseLambda(resp: string): void {
    const hex = this.extractHexBytes(resp, '4124');
    if (hex && hex.length >= 4) {
      const a = parseInt(hex.substring(0, 2), 16);
      const b = parseInt(hex.substring(2, 4), 16);
      this.latestData.lambda = parseFloat((((a * 256) + b) / 32768).toFixed(3));
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

  private parseAmbientTemp(resp: string): void {
    const hex = this.extractHexBytes(resp, '4146');
    if (hex && hex.length >= 2) {
      const a = parseInt(hex.substring(0, 2), 16);
      this.latestData.ambientC = a - 40;
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
