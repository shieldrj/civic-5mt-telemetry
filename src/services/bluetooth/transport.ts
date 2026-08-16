/**
 * A byte pipe to an OBD adapter.
 *
 * Everything above this interface - ELM327 initialisation, PID polling, response parsing -
 * is identical whether the bytes arrive over Bluetooth LE in a browser or over Bluetooth
 * Classic RFCOMM in the native app. Only the pipe differs, so only the pipe is swapped.
 *
 * This split exists because the OBDLink MX+ advertises Classic (SPP) and not LE, which no
 * browser can reach: Web Bluetooth is LE-only by specification. Rather than fork the app,
 * the protocol layer was made transport-agnostic.
 */
export interface ObdTransport {
  /** Which physical link this uses - surfaced in the UI so the two are distinguishable. */
  readonly kind: 'ble' | 'spp';
  readonly label: string;

  /** Whether this transport can run at all in the current environment. */
  isAvailable(): Promise<boolean>;

  /**
   * Opens a device picker if needed and establishes a data link.
   * Resolves only once the link is ready to carry AT commands.
   */
  connect(onStatus?: (msg: string) => void, options?: ObdConnectOptions): Promise<void>;

  disconnect(): Promise<void>;

  /** Writes a command. The caller appends its own terminator. */
  write(text: string): Promise<void>;

  /** Registers the sink for inbound text. Replaces any previous handler. */
  setDataHandler(handler: (chunk: string) => void): void;

  /** Invoked if the link drops on its own. */
  setDisconnectHandler(handler: () => void): void;
}

export interface ObdConnectOptions {
  /**
   * Drop name filters and list every device. Only meaningful for the LE transport, where
   * a filtered picker finding nothing is indistinguishable from a scan that never ran.
   */
  acceptAllDevices?: boolean;

  /**
   * MAC address of an already-paired adapter. Only meaningful for the SPP transport,
   * which connects to a bonded device rather than opening a scan. Omit to auto-pick the
   * paired device whose name looks like an OBD adapter.
   */
  address?: string;
}

export class ObdTransportError extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = 'ObdTransportError';
  }
}
