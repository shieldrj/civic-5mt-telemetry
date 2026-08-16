import { ObdTransport, ObdConnectOptions, ObdTransportError } from './transport';

export const OBDLINK_SERVICE_UUIDS = [
  '0000ffe0-0000-1000-8000-00805f9b34fb', // Standard BLE Serial Service / OBDLink
  '6e400001-b5a3-f393-e0a9-e50e24dcca9e', // Nordic UART Service (OBDLink MX+ BLE)
  'bef8d6c0-ae6c-11e6-bdf4-0800200c9a66', // OBDLink proprietary BLE Service
  'e7810a71-73ae-499d-8c15-faa9aef0c3f2', // OBDLink GATT Service
  '0000fff0-0000-1000-8000-00805f9b34fb', // Alternate BLE Service
  '000018f0-0000-1000-8000-00805f9b34fb',
];

/**
 * Bluetooth LE transport via the Web Bluetooth API.
 *
 * Works with LE-native adapters (Vgate iCar Pro BLE, Veepeak BLE+, most ELM327 clones).
 * It cannot reach a Bluetooth Classic adapter such as the OBDLink MX+ - that is a
 * limitation of the browser API, not of this code, and is why the SPP transport exists.
 */
export class WebBluetoothTransport implements ObdTransport {
  public readonly kind = 'ble' as const;
  public readonly label = 'Bluetooth LE (browser)';

  private device: BluetoothDevice | null = null;
  private server: BluetoothRemoteGATTServer | null = null;
  private writeCharacteristic: BluetoothRemoteGATTCharacteristic | null = null;
  private notifyCharacteristic: BluetoothRemoteGATTCharacteristic | null = null;
  private dataHandler: ((chunk: string) => void) | null = null;
  private disconnectHandler: (() => void) | null = null;
  private readonly decoder = new TextDecoder('utf-8');

  public setDataHandler(handler: (chunk: string) => void): void {
    this.dataHandler = handler;
  }

  public setDisconnectHandler(handler: () => void): void {
    this.disconnectHandler = handler;
  }

  public isSupported(): boolean {
    return typeof navigator !== 'undefined' && 'bluetooth' in navigator;
  }

  public isSecureContext(): boolean {
    return typeof window === 'undefined' || window.isSecureContext !== false;
  }

  public async isAvailable(): Promise<boolean> {
    try {
      if (!this.isSupported()) return false;
      const bt = navigator.bluetooth as Bluetooth & { getAvailability?: () => Promise<boolean> };
      if (typeof bt.getAvailability !== 'function') return true;
      return await bt.getAvailability();
    } catch {
      return true;
    }
  }

  public async connect(
    onStatus?: (msg: string) => void,
    options: ObdConnectOptions = {}
  ): Promise<void> {
    if (!this.isSupported()) {
      throw new ObdTransportError(
        'This browser has no Web Bluetooth. Use Chrome or Edge on Android - Firefox and iOS Safari do not support it at all.'
      );
    }
    if (!this.isSecureContext()) {
      throw new ObdTransportError('Web Bluetooth requires HTTPS. Open the published https:// address.');
    }
    if (!(await this.isAvailable())) {
      throw new ObdTransportError('No Bluetooth radio available. Switch Bluetooth on and try again.');
    }

    try {
      onStatus?.(
        options.acceptAllDevices
          ? 'Listing every nearby Bluetooth LE device...'
          : 'Scanning for a Bluetooth LE OBD adapter...'
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
      onStatus?.(`Connecting to ${this.device.name || 'adapter'}...`);

      this.device.addEventListener('gattserverdisconnected', () => {
        this.disconnectHandler?.();
      });

      this.server = (await this.device.gatt?.connect()) || null;
      if (!this.server) throw new ObdTransportError('Failed to connect to the GATT server.');

      onStatus?.('Discovering OBD-II services...');

      // Enumerate what the device actually exposes rather than probing known UUIDs, so an
      // adapter using an unfamiliar serial service still works. Only services declared in
      // optionalServices are visible, which is why that list stays broad.
      let services: BluetoothRemoteGATTService[] = [];
      try {
        services = await this.server.getPrimaryServices();
      } catch {
        for (const uuid of OBDLINK_SERVICE_UUIDS) {
          try {
            services.push(await this.server.getPrimaryService(uuid));
          } catch {
            // Not present
          }
        }
      }

      if (!services.length) {
        throw new ObdTransportError(
          'Connected, but the adapter exposes no readable BLE service. That is the signature of a Bluetooth Classic adapter, which the browser cannot use.'
        );
      }

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
        if (notifier) {
          this.notifyCharacteristic = notifier;
          this.writeCharacteristic = writer ?? notifier;
          await notifier.startNotifications();
          notifier.addEventListener('characteristicvaluechanged', (event) => {
            const value = (event.target as BluetoothRemoteGATTCharacteristic).value;
            if (value) this.dataHandler?.(this.decoder.decode(value));
          });
          break;
        }
      }

      if (!this.notifyCharacteristic || !this.writeCharacteristic) {
        throw new ObdTransportError(
          'Connected, but found no serial read/write characteristic. This is probably not an OBD adapter, or it only speaks Bluetooth Classic.'
        );
      }
    } catch (err: any) {
      await this.disconnect();
      throw err instanceof ObdTransportError
        ? err
        : new ObdTransportError(this.describeError(err, options.acceptAllDevices === true), err);
    }
  }

  public async disconnect(): Promise<void> {
    try {
      if (this.device?.gatt?.connected) this.device.gatt.disconnect();
    } catch {
      // Already gone
    }
    this.device = null;
    this.server = null;
    this.writeCharacteristic = null;
    this.notifyCharacteristic = null;
  }

  public async write(text: string): Promise<void> {
    if (!this.writeCharacteristic) throw new ObdTransportError('Not connected to an adapter.');
    await this.writeCharacteristic.writeValue(new TextEncoder().encode(text));
  }

  /**
   * NotFoundError is raised identically whether the picker found nothing or the user
   * dismissed it, and the page cannot see the list, so it genuinely cannot tell them
   * apart. The message asks the question that separates them instead of guessing.
   */
  private describeError(err: any, wasUnfiltered: boolean): string {
    const name = err?.name ?? '';
    const message = err?.message ?? String(err);

    if (name === 'NotFoundError') {
      if (wasUnfiltered) {
        return (
          'Picker closed with nothing selected. Did the list show ANY device - earbuds, a TV, another phone? ' +
          'If it was completely empty, Android is blocking the scan: grant Chrome the "Nearby devices" permission and turn Location on. ' +
          'If other devices appeared but the adapter did not, it is not advertising over Bluetooth LE, and only the native app can reach it.'
        );
      }
      return (
        'No adapter selected. Try "Show all nearby devices" - if other Bluetooth gear appears there but the adapter does not, ' +
        'it is Bluetooth Classic only and needs the native Android app.'
      );
    }
    if (name === 'SecurityError') {
      return `Blocked by the browser: ${message}. Web Bluetooth needs an HTTPS page and a tap to start the scan.`;
    }
    if (name === 'NetworkError') {
      return 'Connection dropped during pairing. Check the ignition is ON and nothing else is connected to the adapter.';
    }
    if (name === 'NotSupportedError') {
      return 'The browser refused this device. Web Bluetooth cannot use Bluetooth Classic (SPP) adapters - only Bluetooth LE.';
    }
    return message;
  }
}
