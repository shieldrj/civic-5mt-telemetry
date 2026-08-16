import { registerPlugin, Capacitor } from '@capacitor/core';
import { ObdTransport, ObdConnectOptions, ObdTransportError } from './transport';

export interface SppDevice {
  name: string;
  address: string;
}

/**
 * The native RFCOMM bridge. Implemented in android/app/src/main/java/.../ObdSerial.kt.
 *
 * Bonded devices only - pairing stays in Android's own settings, which is where the OS
 * wants the PIN exchange to happen and where the adapter is already paired.
 */
export interface ObdSerialPlugin {
  isAvailable(): Promise<{ available: boolean; reason?: string }>;
  /** Devices already paired in Android Bluetooth settings. */
  listPairedDevices(): Promise<{ devices: SppDevice[] }>;
  connect(options: { address: string }): Promise<void>;
  disconnect(): Promise<void>;
  write(options: { data: string }): Promise<void>;
  addListener(
    eventName: 'data',
    listener: (event: { data: string }) => void
  ): Promise<{ remove: () => Promise<void> }>;
  addListener(
    eventName: 'disconnected',
    listener: () => void
  ): Promise<{ remove: () => Promise<void> }>;
}

export const ObdSerial = registerPlugin<ObdSerialPlugin>('ObdSerial');

export function isNativePlatform(): boolean {
  try {
    return Capacitor.isNativePlatform();
  } catch {
    return false;
  }
}

/**
 * Bluetooth Classic (RFCOMM / SPP) transport, available only inside the native Android app.
 *
 * This is the transport the OBDLink MX+ actually needs. It advertises Classic and not LE,
 * which is why nothing ever appeared in the browser's picker no matter what was scanned.
 */
export class ClassicSppTransport implements ObdTransport {
  public readonly kind = 'spp' as const;
  public readonly label = 'Bluetooth Classic (native)';

  private dataHandler: ((chunk: string) => void) | null = null;
  private disconnectHandler: (() => void) | null = null;
  private listeners: { remove: () => Promise<void> }[] = [];
  private connected = false;

  public setDataHandler(handler: (chunk: string) => void): void {
    this.dataHandler = handler;
  }

  public setDisconnectHandler(handler: () => void): void {
    this.disconnectHandler = handler;
  }

  public async isAvailable(): Promise<boolean> {
    if (!isNativePlatform()) return false;
    try {
      const { available } = await ObdSerial.isAvailable();
      return available;
    } catch {
      return false;
    }
  }

  /** Paired adapters, so the UI can offer a choice rather than guessing. */
  public async listAdapters(): Promise<SppDevice[]> {
    const { devices } = await ObdSerial.listPairedDevices();
    return devices;
  }

  /**
   * Connects to a paired adapter. With no address, picks the first paired device whose
   * name looks like an OBD adapter - which on a phone paired to one adapter is the
   * whole interaction.
   */
  public async connect(
    onStatus?: (msg: string) => void,
    options: ObdConnectOptions = {}
  ): Promise<void> {
    if (!isNativePlatform()) {
      throw new ObdTransportError(
        'Bluetooth Classic is only available in the native Android app. In a browser, only Bluetooth LE adapters can be used.'
      );
    }

    const availability = await ObdSerial.isAvailable();
    if (!availability.available) {
      throw new ObdTransportError(
        availability.reason || 'Bluetooth is unavailable. Switch it on and grant the app Bluetooth permission.'
      );
    }

    let address = options.address;
    if (!address) {
      onStatus?.('Looking for a paired OBD adapter...');
      const devices = await this.listAdapters();
      if (!devices.length) {
        throw new ObdTransportError(
          'No paired Bluetooth devices. Pair the adapter in Android Settings > Bluetooth first, then come back.'
        );
      }
      const match = devices.find((d) => /obd|mx\+|scantool|stn|elm|vgate|veepeak/i.test(d.name));
      if (!match) {
        throw new ObdTransportError(
          `None of the ${devices.length} paired devices look like an OBD adapter. Pick one manually, or pair the adapter first.`
        );
      }
      address = match.address;
      onStatus?.(`Connecting to ${match.name}...`);
    } else {
      onStatus?.('Connecting to adapter...');
    }

    await this.attachListeners();

    try {
      await ObdSerial.connect({ address });
      this.connected = true;
    } catch (err: any) {
      await this.detachListeners();
      throw new ObdTransportError(
        err?.message ||
          'Could not open a serial connection. Check the ignition is on and that no other app (including the OBDLink app) is holding the adapter.',
        err
      );
    }
  }

  public async disconnect(): Promise<void> {
    this.connected = false;
    await this.detachListeners();
    try {
      if (isNativePlatform()) await ObdSerial.disconnect();
    } catch {
      // Already closed
    }
  }

  public async write(text: string): Promise<void> {
    if (!this.connected) throw new ObdTransportError('Not connected to an adapter.');
    await ObdSerial.write({ data: text });
  }

  private async attachListeners(): Promise<void> {
    await this.detachListeners();
    this.listeners.push(
      await ObdSerial.addListener('data', (event) => this.dataHandler?.(event.data))
    );
    this.listeners.push(
      await ObdSerial.addListener('disconnected', () => {
        this.connected = false;
        this.disconnectHandler?.();
      })
    );
  }

  private async detachListeners(): Promise<void> {
    for (const listener of this.listeners) {
      try {
        await listener.remove();
      } catch {
        // Ignore
      }
    }
    this.listeners = [];
  }
}
