import React, { useEffect, useState } from 'react';
import { Bluetooth, X, Radio, Info, Cpu, AlertTriangle, Search, CheckCircle2 } from 'lucide-react';
import { ConnectionStatus } from '../types/obd';

interface BluetoothEnvironment {
  supported: boolean;
  secureContext: boolean;
  adapterAvailable: boolean;
}

interface BluetoothModalProps {
  isOpen: boolean;
  onClose: () => void;
  status: ConnectionStatus;
  statusMessage: string;
  onConnect: (options?: { acceptAllDevices?: boolean }) => void;
  onDisconnect: () => void;
  onStartSim: () => void;
  checkEnvironment: () => Promise<BluetoothEnvironment>;
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
}) => {
  const [env, setEnv] = useState<BluetoothEnvironment | null>(null);

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

  if (!isOpen) return null;

  const blocker = !env
    ? null
    : !env.supported
    ? 'This browser has no Web Bluetooth. Use Chrome or Edge on Android — Firefox and iOS Safari cannot do this at all.'
    : !env.secureContext
    ? 'This page is not on HTTPS, so the browser will refuse Bluetooth. Open the published https:// address.'
    : !env.adapterAvailable
    ? 'No Bluetooth radio is available. Switch Bluetooth on.'
    : null;

  return (
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-xs flex items-center justify-center p-4">
      <div className="bg-[#0e111a] border border-[rgba(255,255,255,0.12)] rounded-2xl max-w-md w-full p-5 shadow-2xl flex flex-col gap-4">
        {/* Modal Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-1.5 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#00d2ff] border border-[rgba(255,255,255,0.08)]">
              <Bluetooth size={18} />
            </div>
            <div>
              <h2 className="text-sm font-bold font-['Chakra_Petch'] text-[#f8fafc]">
                OBD-II Hardware Link
              </h2>
              <p className="text-[10px] text-[#64748b]">OBDLink MX+ BLE Interface</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded-lg text-[#64748b] hover:text-[#f8fafc] hover:bg-[#161a26] transition-colors"
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
                  ? 'bg-[#00e676]'
                  : status === 'simulating'
                  ? 'bg-[#00d2ff]'
                  : status === 'connecting'
                  ? 'bg-[#ffaa00] animate-pulse'
                  : 'bg-[#ff2a40]'
              }`}
            />
            <div className="flex flex-col">
              <span className="text-xs font-bold font-['Chakra_Petch'] text-[#f8fafc] uppercase">
                {status === 'connected'
                  ? 'Live OBD Connected'
                  : status === 'simulating'
                  ? 'Virtual ECU Active'
                  : status === 'connecting'
                  ? 'Connecting...'
                  : 'Disconnected'}
              </span>
              <span className="text-[10px] text-[#64748b]">{statusMessage}</span>
            </div>
          </div>

          {status === 'connected' ? (
            <button
              onClick={onDisconnect}
              className="px-2.5 py-1.5 rounded-lg bg-[#ff2a40]/20 text-[#ff6b7b] border border-[#ff2a40]/40 text-xs font-bold font-['Chakra_Petch']"
            >
              Disconnect
            </button>
          ) : (
            <button
              onClick={() => onConnect()}
              className="px-3.5 py-1.5 rounded-xl bg-[#00d2ff] hover:bg-[#00b4db] text-black text-xs font-bold font-['Chakra_Petch'] transition-all flex items-center gap-1.5"
            >
              <Radio size={13} />
              Pair OBDLink MX+
            </button>
          )}
        </div>

        {/* Environment blocker - the things that make scanning fail before it starts */}
        {blocker && (
          <div className="flex items-start gap-2 rounded-lg border border-[#ff2a40]/40 bg-[#ff2a40]/10 p-2.5 text-[11px] leading-relaxed text-[#ff9aa5]">
            <AlertTriangle size={14} className="text-[#ff2a40] shrink-0 mt-0.5" />
            <span>{blocker}</span>
          </div>
        )}
        {env && !blocker && status !== 'connected' && (
          <div className="flex items-center gap-2 rounded-lg border border-[#00e676]/30 bg-[#00e676]/10 p-2 text-[11px] text-[#5aff9f]">
            <CheckCircle2 size={13} className="shrink-0" />
            Browser, HTTPS and Bluetooth radio all check out.
          </div>
        )}

        {/* Escape hatch: a filtered picker that finds nothing looks the same whether the
            adapter is absent, silent, or just named unexpectedly. */}
        {status !== 'connected' && (
          <button
            onClick={() => onConnect({ acceptAllDevices: true })}
            className="flex items-center justify-center gap-1.5 w-full py-2 rounded-lg bg-[rgba(255,255,255,0.05)] border border-[rgba(255,255,255,0.12)] text-[#f8fafc] text-[12px] font-bold font-['Chakra_Petch'] transition-colors hover:bg-[rgba(255,255,255,0.1)]"
          >
            <Search size={13} className="text-[#00d2ff]" />
            Show all nearby devices
          </button>
        )}

        {/* Android instructions */}
        <div className="telemetry-card-subtle flex flex-col gap-2 text-xs text-[#94a3b8]">
          <div className="flex items-center gap-1.5 text-[#f8fafc] font-bold font-['Chakra_Petch'] text-[11px]">
            <Info size={13} className="text-[#00d2ff]" />
            If the list stays empty:
          </div>
          <ol className="list-decimal list-inside space-y-1.5 text-[11px] leading-relaxed text-[#94a3b8]">
            <li>
              <strong className="text-[#f8fafc]">Turn Location on.</strong> Android blocks all
              Bluetooth LE scanning without it, and gives no warning — the list just stays empty.
            </li>
            <li>
              <strong className="text-[#f8fafc]">Un-pair it in Android Bluetooth settings.</strong>{' '}
              This app needs Bluetooth <em>LE</em>, not the Classic pairing those settings create.
              Holding a Classic connection can stop the adapter advertising over LE entirely.
            </li>
            <li>Turn the ignition to <strong>ON / II</strong> so the adapter is powered and its LED is lit.</li>
            <li>Tap <strong>Show all nearby devices</strong> — if it appears there but not above, the name filter is the problem.</li>
          </ol>
        </div>

        {/* Switch to Simulator */}
        <div className="flex items-center justify-between pt-1 border-t border-[rgba(255,255,255,0.06)]">
          <span className="text-[11px] text-[#64748b]">Testing without vehicle?</span>
          <button
            onClick={() => {
              onStartSim();
              onClose();
            }}
            className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-[rgba(255,255,255,0.05)] hover:bg-[rgba(255,255,255,0.1)] text-[#00d2ff] text-xs font-bold font-['Chakra_Petch'] transition-colors"
          >
            <Cpu size={13} />
            Run Simulator
          </button>
        </div>
      </div>
    </div>
  );
};
