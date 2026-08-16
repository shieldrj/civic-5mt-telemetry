import React from 'react';
import { Bluetooth, X, Radio, Info, Cpu } from 'lucide-react';
import { ConnectionStatus } from '../types/obd';

interface BluetoothModalProps {
  isOpen: boolean;
  onClose: () => void;
  status: ConnectionStatus;
  statusMessage: string;
  onConnect: () => void;
  onDisconnect: () => void;
  onStartSim: () => void;
}

export const BluetoothModal: React.FC<BluetoothModalProps> = ({
  isOpen,
  onClose,
  status,
  statusMessage,
  onConnect,
  onDisconnect,
  onStartSim,
}) => {
  if (!isOpen) return null;

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
              onClick={onConnect}
              className="px-3.5 py-1.5 rounded-xl bg-[#00d2ff] hover:bg-[#00b4db] text-black text-xs font-bold font-['Chakra_Petch'] transition-all flex items-center gap-1.5"
            >
              <Radio size={13} />
              Pair OBDLink MX+
            </button>
          )}
        </div>

        {/* Android instructions */}
        <div className="telemetry-card-subtle flex flex-col gap-2 text-xs text-[#94a3b8]">
          <div className="flex items-center gap-1.5 text-[#f8fafc] font-bold font-['Chakra_Petch'] text-[11px]">
            <Info size={13} className="text-[#00d2ff]" />
            Quick Connection Steps:
          </div>
          <ol className="list-decimal list-inside space-y-1 text-[11px] leading-relaxed text-[#94a3b8]">
            <li>Plug <strong>OBDLink MX+</strong> into the Civic OBD port (under steering column).</li>
            <li>Press the <strong>Pair</strong> button on the front of the OBDLink MX+.</li>
            <li>Turn ignition to <strong>ON / II</strong> (or start engine).</li>
            <li>Click <strong>Pair OBDLink MX+</strong> above and select your device.</li>
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
