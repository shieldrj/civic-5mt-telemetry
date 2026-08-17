import React, { useEffect, useState } from 'react';
import { Bluetooth, X, Radio, Info, Cpu, AlertTriangle, Search, CheckCircle2 } from 'lucide-react';
import { ConnectionStatus } from '../types/obd';

interface BluetoothEnvironment {
  supported: boolean;
  secureContext: boolean;
  adapterAvailable: boolean;
  transportKind: 'ble' | 'spp';
  transportLabel: string;
  pairedAdapters: { name: string; address: string }[];
}

interface ProtocolLogEntry {
  at: number;
  cmd: string;
  resp: string;
}

interface BluetoothModalProps {
  isOpen: boolean;
  onClose: () => void;
  status: ConnectionStatus;
  statusMessage: string;
  onConnect: (options?: { acceptAllDevices?: boolean; address?: string }) => void;
  onDisconnect: () => void;
  onStartSim: () => void;
  checkEnvironment: () => Promise<BluetoothEnvironment>;
  getProtocolLog: () => ProtocolLogEntry[];
  setProtocolLogListener: (listener: (() => void) | null) => void;
}

export const BluetoothModal: React.FC<BluetoothModalProps> = ({
  isOpen,
  onClose,
  status,
  statusMessage,
  onConnect,
  onDisconnect,
  onStartSim,
  checkEnvironment,
  getProtocolLog,
  setProtocolLogListener,
}) => {
  const [env, setEnv] = useState<BluetoothEnvironment | null>(null);
  const [log, setLog] = useState<ProtocolLogEntry[]>([]);

  useEffect(() => {
    if (!isOpen) return;
    let cancelled = false;
    checkEnvironment().then((result) => {
      if (!cancelled) setEnv(result);
    });
    return () => {
      cancelled = true;
    };
  }, [isOpen, checkEnvironment]);

  // The handshake is the part that fails silently, so it is shown rather than summarised.
  useEffect(() => {
    if (!isOpen) return;
    setLog(getProtocolLog());
    setProtocolLogListener(() => setLog(getProtocolLog()));
    return () => setProtocolLogListener(null);
  }, [isOpen, getProtocolLog, setProtocolLogListener]);

  if (!isOpen) return null;

  const isNative = env?.transportKind === 'spp';

  const blocker = !env
    ? null
    : isNative
    ? !env.adapterAvailable
      ? 'Bluetooth is off, or this app has not been granted the Nearby devices permission.'
      : null
    : !env.supported
    ? 'This browser has no Web Bluetooth. Use Chrome or Edge on Android — Firefox and iOS Safari cannot do this at all.'
    : !env.secureContext
    ? 'This page is not on HTTPS, so the browser will refuse Bluetooth. Open the published https:// address.'
    : !env.adapterAvailable
    ? 'No Bluetooth radio is available. Switch Bluetooth on.'
    : null;

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-xs flex items-center justify-center p-4">
      <div className="bg-[#181b20] border border-[rgba(255,255,255,0.12)] rounded-2xl max-w-md w-full p-5 shadow-2xl flex flex-col gap-4">
        {/* Modal Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-1.5 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#9aa1a9] border border-[rgba(255,255,255,0.08)]">
              <Bluetooth size={18} />
            </div>
            <div>
              <h2 className="text-sm font-bold text-[#eef0f2]">
                OBD-II Hardware Link
              </h2>
              <p className="text-[10px] text-[#6b727a]">OBDLink MX+ BLE Interface</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded-lg text-[#6b727a] hover:text-[#eef0f2] hover:bg-[#1f2328] transition-colors"
          >
            <X size={16} />
          </button>
        </div>

        {/* Status Indicator Bar */}
        <div className="telemetry-card-subtle flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div
              className={`w-2.5 h-2.5 rounded-full ${
                status === 'connected'
                  ? 'bg-[#eef0f2]'
                  : status === 'simulating'
                  ? 'bg-[#9aa1a9]'
                  : status === 'connecting'
                  ? 'bg-[#c8952e]'
                  : 'bg-[#d8453b]'
              }`}
            />
            <div className="flex flex-col">
              <span className="text-xs font-bold text-[#eef0f2] uppercase">
                {status === 'connected'
                  ? 'Live OBD Connected'
                  : status === 'simulating'
                  ? 'Virtual ECU Active'
                  : status === 'connecting'
                  ? 'Connecting...'
                  : 'Disconnected'}
              </span>
              <span className="text-[10px] text-[#6b727a]">{statusMessage}</span>
            </div>
          </div>

          {status === 'connected' ? (
            <button
              onClick={onDisconnect}
              className="px-2.5 py-1.5 rounded-lg bg-[#d8453b]/20 text-[#d8453b] border border-[#d8453b]/40 text-xs font-bold"
            >
              Disconnect
            </button>
          ) : (
            <button
              onClick={() => onConnect()}
              className="px-3.5 py-1.5 rounded-xl bg-[#9aa1a9] hover:bg-[#00b4db] text-black text-xs font-bold transition-all flex items-center gap-1.5"
            >
              <Radio size={13} />
              Pair OBDLink MX+
            </button>
          )}
        </div>

        {/* Environment blocker - the things that make scanning fail before it starts */}
        {blocker && (
          <div className="flex items-start gap-2 rounded-lg border border-[#d8453b]/40 bg-[#d8453b]/10 p-2.5 text-[11px] leading-relaxed text-[#ff9aa5]">
            <AlertTriangle size={14} className="text-[#d8453b] shrink-0 mt-0.5" />
            <span>{blocker}</span>
          </div>
        )}
        {env && !blocker && status !== 'connected' && (
          <div className="flex items-center gap-2 rounded-lg border border-[#eef0f2]/30 bg-[#eef0f2]/10 p-2 text-[11px] text-[#eef0f2]">
            <CheckCircle2 size={13} className="shrink-0" />
            Browser, HTTPS and Bluetooth radio all check out.
          </div>
        )}

        {/* Which physical link this build speaks. The distinction is the whole reason the
            native app exists, so it is stated rather than implied. */}
        {env && (
          <div className="flex items-center justify-between gap-2 rounded-lg border border-[rgba(255,255,255,0.08)] bg-[#101215] px-2.5 py-1.5 text-[11px]">
            <span className="text-[#6b727a] uppercase tracking-wide">Link</span>
            <span className={isNative ? 'text-[#eef0f2] font-bold' : 'text-[#9aa1a9]'}>
              {env.transportLabel}
            </span>
          </div>
        )}

        {/* Native: pick from adapters already paired in Android settings. */}
        {isNative && status !== 'connected' && env && env.pairedAdapters.length > 0 && (
          <div className="flex flex-col gap-1.5">
            <span className="text-[11px] text-[#6b727a] uppercase tracking-wide">
              Paired adapters
            </span>
            {env.pairedAdapters.map((device) => (
              <button
                key={device.address}
                onClick={() => onConnect({ address: device.address })}
                className="flex items-center justify-between gap-2 w-full px-2.5 py-2 rounded-lg bg-[rgba(255,255,255,0.05)] border border-[rgba(255,255,255,0.12)] text-left transition-colors hover:bg-[rgba(255,255,255,0.1)]"
              >
                <span className="text-[12px] font-bold text-[#eef0f2]">
                  {device.name}
                </span>
                <span className="text-[10px] text-[#6b727a] tabular-nums">{device.address}</span>
              </button>
            ))}
          </div>
        )}

        {/* Browser only: a filtered picker that finds nothing looks the same whether the
            adapter is absent, silent, or just named unexpectedly. */}
        {!isNative && status !== 'connected' && (
          <button
            onClick={() => onConnect({ acceptAllDevices: true })}
            className="flex items-center justify-center gap-1.5 w-full py-2 rounded-lg bg-[rgba(255,255,255,0.05)] border border-[rgba(255,255,255,0.12)] text-[#eef0f2] text-[12px] font-bold transition-colors hover:bg-[rgba(255,255,255,0.1)]"
          >
            <Search size={13} className="text-[#9aa1a9]" />
            Show all nearby devices
          </button>
        )}

        {/* Native build: pairing lives in Android settings, so there is nothing to scan. */}
        {isNative && (
          <div className="telemetry-card-subtle flex flex-col gap-2 text-xs text-[#9aa1a9]">
            <div className="flex items-center gap-1.5 text-[#eef0f2] font-bold text-[11px]">
              <Info size={13} className="text-[#9aa1a9]" />
              Native Bluetooth Classic
            </div>
            <p className="text-[11px] leading-relaxed">
              This build talks RFCOMM directly, which is what the OBDLink MX+ actually speaks —
              no LE scanning involved. Pair the adapter once in{' '}
              <strong className="text-[#eef0f2]">Android Settings → Bluetooth</strong>, turn the
              ignition to <strong className="text-[#eef0f2]">ON / II</strong>, then pick it above.
            </p>
            <p className="text-[10px] leading-relaxed text-[#6b727a]">
              Nothing listed? Pair it in Android settings first. Connection refused? Close the
              official OBDLink app — only one app can hold the adapter at a time.
            </p>
          </div>
        )}

        {/* Browser build: the LE troubleshooting path */}
        {!isNative && (
        <div className="telemetry-card-subtle flex flex-col gap-2 text-xs text-[#9aa1a9]">
          <div className="flex items-center gap-1.5 text-[#eef0f2] font-bold text-[11px]">
            <Info size={13} className="text-[#9aa1a9]" />
            If the list stays empty:
          </div>
          <p className="text-[11px] leading-relaxed text-[#9aa1a9]">
            First answer this: in <strong className="text-[#eef0f2]">Show all nearby devices</strong>,
            did <em>any</em> device appear — earbuds, a TV, another phone?
          </p>
          <div className="rounded-md border border-[rgba(255,255,255,0.08)] bg-[#101215] p-2 text-[11px] leading-relaxed">
            <p className="text-[#c8952e] font-bold">Completely empty →</p>
            <p className="text-[#9aa1a9]">
              Android is blocking the scan, not the adapter. Grant Chrome{' '}
              <strong className="text-[#eef0f2]">Nearby devices</strong> permission (Settings → Apps →
              Chrome → Permissions) and switch <strong className="text-[#eef0f2]">Location</strong> on.
              Both are required for any BLE scan, and both fail silently.
            </p>
          </div>
          <div className="rounded-md border border-[rgba(255,255,255,0.08)] bg-[#101215] p-2 text-[11px] leading-relaxed">
            <p className="text-[#9aa1a9] font-bold">Other devices, but not the adapter →</p>
            <p className="text-[#9aa1a9]">
              It is not advertising over LE. Forget it in Android Bluetooth settings, turn the
              ignition to <strong className="text-[#eef0f2]">ON / II</strong>, then hold the adapter's
              <strong className="text-[#eef0f2]"> Pair</strong> button until its LED flashes and scan again.
            </p>
          </div>
          <p className="text-[10px] leading-relaxed text-[#6b727a]">
            Still nothing? Install a BLE scanner (nRF Connect) and look for the adapter there. That
            app sees everything advertising over LE — if it cannot find it either, the adapter does
            not speak Bluetooth LE on Android, and no browser can reach it — that is what the
            native Android build is for.
          </p>
        </div>
        )}

        {/* What the adapter actually said. Without this, a handshake that fails part-way
            is indistinguishable from one that worked - the gauges simply never move. */}
        {log.length > 0 && (
          <div className="flex flex-col gap-1.5">
            <span className="text-[11px] text-[#6b727a] uppercase tracking-wide">
              Adapter log
            </span>
            <div className="max-h-36 overflow-y-auto rounded-lg border border-[rgba(255,255,255,0.08)] bg-[#101215] p-2 font-mono text-[10px] leading-relaxed">
              {log.map((entry, i) => (
                <div key={i} className="flex gap-1.5">
                  <span className="text-[#9aa1a9] shrink-0">{entry.cmd}</span>
                  <span
                    className={
                      entry.resp === '(no reply)' ? 'text-[#d8453b]' : 'text-[#9aa1a9] break-all'
                    }
                  >
                    {entry.resp.replace(/\r/g, ' ')}
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Switch to Simulator */}
        <div className="flex items-center justify-between pt-1 border-t border-[rgba(255,255,255,0.06)]">
          <span className="text-[11px] text-[#6b727a]">Testing without vehicle?</span>
          <button
            onClick={() => {
              onStartSim();
              onClose();
            }}
            className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-[rgba(255,255,255,0.05)] hover:bg-[rgba(255,255,255,0.1)] text-[#9aa1a9] text-xs font-bold transition-colors"
          >
            <Cpu size={13} />
            Run Simulator
          </button>
        </div>
      </div>
    </div>
  );
};
