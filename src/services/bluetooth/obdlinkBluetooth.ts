/**
 * OBDLink MX+ Bluetooth Low Energy (BLE) Client & STN/ELM327 CAN Parser
 */

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

export const OBDLINK_SERVICE_UUIDS = [
  '0000ffe0-0000-1000-8000-00805f9b34fb', // Standard BLE Serial Service / OBDLink
  '6e400001-b5a3-f393-e0a9-e50e24dcca9e', // Nordic UART Service (OBDLink MX+ BLE)
  'bef8d6c0-ae6c-11e6-bdf4-0800200c9a66', // OBDLink proprietary BLE Service
  'e7810a71-73ae-499d-8c15-faa9aef0c3f2', // OBDLink GATT Service
  '0000fff0-0000-1000-8000-00805f9b34fb', // Alternate BLE Service
  '000018f0-0000-1000-8000-00805f9b34fb',
];

export const OBDLINK_RX_CHAR_UUIDS = [
  '0000ffe1-0000-1000-8000-00805f9b34fb',
  '6e400002-b5a3-f393-e0a9-e50e24dcca9e',
  'bef8d6c1-ae6c-11e6-bdf4-0800200c9a66',
  '0000fff1-0000-1000-8000-00805f9b34fb',
];

export const OBDLINK_TX_CHAR_UUIDS = [
  '0000ffe1-0000-1000-8000-00805f9b34fb',
  '6e400003-b5a3-f393-e0a9-e50e24dcca9e',
  'bef8d6c2-ae6c-11e6-bdf4-0800200c9a66',
  '0000fff2-0000-1000-8000-00805f9b34fb',
];

export class OBDLinkBluetoothManager {
  private device: BluetoothDevice | null = null;
  private server: BluetoothRemoteGATTServer | null = null;
  private rxCharacteristic: BluetoothRemoteGATTCharacteristic | null = null;
  private txCharacteristic: BluetoothRemoteGATTCharacteristic | null = null;
  
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

  public isSupported(): boolean {
    return typeof navigator !== 'undefined' && 'bluetooth' in navigator;
  }

  /** Web Bluetooth is refused outright outside a secure context. */
  public isSecureContext(): boolean {
    return typeof window === 'undefined' || window.isSecureContext !== false;
  }

  /** Whether the phone has a usable Bluetooth radio switched on. */
  public async isAdapterAvailable(): Promise<boolean> {
    try {
      if (!this.isSupported()) return false;
      const bt = navigator.bluetooth as Bluetooth & { getAvailability?: () => Promise<boolean> };
      if (typeof bt.getAvailability !== 'function') return true;
      return await bt.getAvailability();
    } catch {
      return true; // Unknown - let the picker be the judge
    }
  }

  /**
   * Opens the browser's device picker and connects.
   *
   * `acceptAllDevices` drops the name filters and lists everything advertising nearby.
   * It exists because a filtered picker that finds nothing is ambiguous: it looks
   * identical whether the adapter is absent, is not advertising over BLE, or is simply
   * advertising under a name the filters do not match. Listing everything separates
   * those cases in a couple of seconds.
   */
  public async connect(
    onStatus?: (msg: string) => void,
    options: { acceptAllDevices?: boolean } = {}
  ): Promise<boolean> {
    if (!this.isSupported()) {
      throw new Error(
        'This browser has no Web Bluetooth. Use Chrome or Edge on Android - Firefox and iOS Safari do not support it at all.'
      );
    }
    if (!this.isSecureContext()) {
      throw new Error('Web Bluetooth requires HTTPS. Open the published https:// address rather than a local or http:// one.');
    }
    if (!(await this.isAdapterAvailable())) {
      throw new Error('No Bluetooth radio available. Switch Bluetooth on and try again.');
    }

    try {
      onStatus?.(
        options.acceptAllDevices
          ? 'Listing every nearby Bluetooth LE device...'
          : 'Scanning for OBDLink MX+ adapter...'
      );

      // Web Bluetooth forbids combining filters with acceptAllDevices.
      const request: RequestDeviceOptions = options.acceptAllDevices
        ? { acceptAllDevices: true, optionalServices: OBDLINK_SERVICE_UUIDS }
        : {
            filters: [
              { namePrefix: 'OBDLink' },
              { namePrefix: 'MX+' },
              { namePrefix: 'ScanTool' },
              { namePrefix: 'OBD' },
              { namePrefix: 'STN' },
              { namePrefix: 'ELM' },
              { namePrefix: 'Vgate' },
              { namePrefix: 'VEEPEAK' },
            ],
            optionalServices: OBDLINK_SERVICE_UUIDS,
          };

      this.device = await navigator.bluetooth.requestDevice(request);

      onStatus?.(`Connecting to ${this.device.name || 'OBDLink MX+'}...`);
      
      this.device.addEventListener('gattserverdisconnected', () => {
        this.isPolling = false;
        onStatus?.('OBDLink Bluetooth connection lost');
      });

      this.server = await this.device.gatt?.connect() || null;
      if (!this.server) {
        throw new Error('Failed to connect to GATT Server.');
      }

      onStatus?.('Discovering OBD-II Services...');

      // Enumerate everything the device exposes rather than probing known UUIDs one by
      // one, so an adapter using a serial service we have not seen before still works.
      // Only services named in optionalServices are visible, which is why that list has
      // to stay broad.
      let services: BluetoothRemoteGATTService[] = [];
      try {
        services = await this.server.getPrimaryServices();
      } catch {
        for (const uuid of OBDLINK_SERVICE_UUIDS) {
          try {
            services.push(await this.server.getPrimaryService(uuid));
          } catch {
            // Not present on this device
          }
        }
      }

      if (!services.length) {
        throw new Error(
          'Connected, but the adapter exposes no readable BLE service. This usually means it is a Bluetooth Classic (SPP) adapter, which Web Bluetooth cannot use.'
        );
      }

      // A serial link needs one characteristic to write commands and one that notifies
      // with replies. Some adapters combine both into a single characteristic.
      for (const service of services) {
        let characteristics: BluetoothRemoteGATTCharacteristic[] = [];
        try {
          characteristics = await service.getCharacteristics();
        } catch {
          continue;
        }

        const notifier = characteristics.find((c) => c.properties.notify || c.properties.indicate);
        const writer = characteristics.find(
          (c) => c.properties.write || c.properties.writeWithoutResponse
        );

        if (notifier && (writer || notifier)) {
          this.txCharacteristic = notifier;
          this.rxCharacteristic = writer ?? notifier;
          await notifier.startNotifications();
          notifier.addEventListener('characteristicvaluechanged', this.handleNotification.bind(this));
          break;
        }
      }

      if (!this.txCharacteristic || !this.rxCharacteristic) {
        throw new Error(
          'Connected, but found no serial read/write characteristic on this device. It is probably not an OBD adapter, or it only speaks Bluetooth Classic.'
        );
      }

      onStatus?.('Initializing 2013 Honda Civic ISO 15765-4 CAN Protocol...');
      await this.initializeElm327();

      onStatus?.('Connected & Streaming Telemetry');
      this.startPollingLoop();
      return true;
    } catch (err: any) {
      this.disconnect();
      throw new Error(this.describeConnectError(err, options.acceptAllDevices === true));
    }
  }

  /**
   * Turns a Web Bluetooth DOMException into something that says what to do next.
   *
   * NotFoundError is the awkward one: the spec raises the identical error whether the
   * picker found nothing or the user dismissed it, and the page is not allowed to see
   * the list, so it genuinely cannot tell those apart. Rather than guess - the previous
   * wording asserted "cancelled", which sent you looking in the wrong place - the message
   * asks the one question that separates them. With no filters applied, an empty list
   * means the scan itself never ran, because something is always advertising.
   */
  private describeConnectError(err: any, wasUnfiltered: boolean): string {
    const name = err?.name ?? '';
    const message = err?.message ?? String(err);

    if (name === 'NotFoundError') {
      if (wasUnfiltered) {
        return (
          'Picker closed with nothing selected. The browser cannot tell whether it found nothing or you dismissed it, so: ' +
          'did the list show ANY device at all - earbuds, a TV, another phone? ' +
          'If it was completely empty, Android is blocking the scan, not the adapter: grant Chrome the "Nearby devices" permission ' +
          'and turn Location on. If other devices appeared but the adapter did not, it is not advertising over Bluetooth LE - ' +
          'hold its Pair button until the LED flashes, and forget it in Android Bluetooth settings first.'
        );
      }
      return (
        'No adapter selected. Try "Show all nearby devices" - if other Bluetooth gear appears there but the adapter does not, ' +
        'it is not advertising over LE. If nothing appears at all, Android is blocking the scan: Location on, and grant Chrome ' +
        'the "Nearby devices" permission.'
      );
    }
    if (name === 'SecurityError') {
      return `Blocked by the browser: ${message}. Web Bluetooth needs an HTTPS page and a tap to start the scan.`;
    }
    if (name === 'NetworkError') {
      return 'Connection dropped during pairing. Make sure the ignition is ON, the adapter LED is lit, and nothing else is connected to it.';
    }
    if (name === 'NotSupportedError') {
      return 'This device or browser refused the connection. Web Bluetooth cannot use Bluetooth Classic (SPP) adapters - only Bluetooth LE.';
    }
    return message;
  }

  public disconnect(): void {
    this.isPolling = false;
    if (this.device?.gatt?.connected) {
      this.device.gatt.disconnect();
    }
    this.device = null;
    this.server = null;
    this.rxCharacteristic = null;
    this.txCharacteristic = null;
  }

  private handleNotification(event: Event): void {
    const target = event.target as BluetoothRemoteGATTCharacteristic;
    const value = target.value;
    if (!value) return;

    const decoder = new TextDecoder('utf-8');
    const chunk = decoder.decode(value);
    this.incomingBuffer += chunk;

    // ELM327/STN terminates responses with '>' prompt
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

  public async sendCommand(cmd: string, timeoutMs: number = 600): Promise<string> {
    if (!this.rxCharacteristic) {
      throw new Error('Not connected to OBDLink adapter');
    }

    const cleanCmd = cmd.trim() + '\r';
    const encoder = new TextEncoder();
    const data = encoder.encode(cleanCmd);

    return new Promise(async (resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.pendingResolver) {
          this.pendingResolver = null;
          resolve(''); // Timeout fallback without crashing
        }
      }, timeoutMs);

      this.pendingResolver = (resp: string) => {
        clearTimeout(timer);
        resolve(resp);
      };

      try {
        await this.rxCharacteristic!.writeValue(data);
      } catch (err) {
        clearTimeout(timer);
        this.pendingResolver = null;
        reject(err);
      }
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
