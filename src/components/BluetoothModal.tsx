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
    <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-md flex items-center justify-center p-4">
      <div className="bg-[#0e1118] border border-[#252b3d] rounded-2xl max-w-md w-full p-6 shadow-2xl flex flex-col gap-4">
        {/* Modal Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-[#00d2ff]/15 text-[#00d2ff] border border-[#00d2ff]/30">
              <Bluetooth size={20} />
            </div>
            <div>
              <h2 className="text-base font-bold font-['Chakra_Petch'] text-[#f8fafc]">
                OBD-II Hardware Link
              </h2>
              <p className="text-[11px] text-[#64748b]">Vgate vLinker MC+ / BLE Interface</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1 rounded-lg text-[#64748b] hover:text-[#f8fafc] hover:bg-[#161a26] transition-colors"
          >
            <X size={18} />
          </button>
        </div>

        {/* Status Indicator Bar */}
        <div className="bg-[#08090d] border border-[#161a26] rounded-xl p-3.5 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div
              className={`w-3.5 h-3.5 rounded-full ${
                status === 'connected'
                  ? 'bg-[#00e676] shadow-[0_0_10px_#00e676]'
                  : status === 'simulating'
                  ? 'bg-[#00d2ff] shadow-[0_0_10px_#00d2ff]'
                  : status === 'connecting'
                  ? 'bg-[#ffaa00] animate-ping'
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
              className="px-3 py-1.5 rounded-lg bg-[#ff2a40]/20 text-[#ff2a40] border border-[#ff2a40]/40 text-xs font-bold font-['Chakra_Petch']"
            >
              Disconnect
            </button>
          ) : (
            <button
              onClick={onConnect}
              className="px-4 py-2 rounded-xl bg-gradient-to-r from-[#00d2ff] to-[#0072ff] text-white text-xs font-bold font-['Chakra_Petch'] shadow-[0_0_15px_rgba(0,210,255,0.4)] hover:brightness-110 transition-all flex items-center gap-1.5"
            >
              <Radio size={14} />
              Pair vLinker MC+
            </button>
          )}
        </div>

        {/* Android & Vgate instructions */}
        <div className="bg-[#0b0e16] border border-[#161a26] rounded-xl p-3.5 flex flex-col gap-2.5 text-xs text-[#94a3b8]">
          <div className="flex items-center gap-1.5 text-[#f8fafc] font-bold font-['Chakra_Petch']">
            <Info size={14} className="text-[#00d2ff]" />
            Connection Guide for Android:
          </div>
          <ol className="list-decimal list-inside space-y-1 text-[11px] leading-relaxed text-[#94a3b8]">
            <li>Plug your <strong>Vgate vLinker MC+</strong> into your 2013 Civic OBD-II port (under steering column).</li>
            <li>Turn your Civic ignition to <strong>ON / II</strong> (or start the engine).</li>
            <li>Enable <strong>Bluetooth</strong> and <strong>Location</strong> on your Android phone.</li>
            <li>Click <strong>Pair vLinker MC+</strong> above and select your device from the Chrome popup.</li>
          </ol>
        </div>

        {/* Switch to Simulator */}
        <div className="flex items-center justify-between pt-1 border-t border-[#1a2030]">
          <span className="text-xs text-[#64748b]">Want to test without going to the car?</span>
          <button
            onClick={() => {
              onStartSim();
              onClose();
            }}
            className="flex items-center gap-1 px-3 py-1.5 rounded-lg bg-[#161a26] hover:bg-[#1f2638] text-[#00d2ff] text-xs font-bold font-['Chakra_Petch'] transition-colors"
          >
            <Cpu size={14} />
            Switch to Simulator
          </button>
        </div>
      </div>
    </div>
  );
};
