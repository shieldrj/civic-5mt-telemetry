import React from 'react';
import { Gauge, AlertOctagon, ArrowUpRight, CheckCircle2 } from 'lucide-react';
import { OBDLiveMetrics } from '../types/obd';

interface ManualTransmissionCardProps {
  metrics: OBDLiveMetrics;
}

export const ManualTransmissionCard: React.FC<ManualTransmissionCardProps> = ({ metrics }) => {
  const gear = metrics.currentGear;
  const isSlipping = metrics.isClutchSlipping;
  const gears: (1 | 2 | 3 | 4 | 5)[] = [1, 2, 3, 4, 5];

  return (
    <div className="telemetry-card flex flex-col justify-between gap-3">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="p-1.5 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#00d2ff] border border-[rgba(255,255,255,0.08)]">
            <Gauge size={16} />
          </div>
          <div>
            <h3 className="text-xs font-bold text-[#f8fafc] font-['Chakra_Petch'] tracking-wide">
              5-SPEED MANUAL DYNAMICS
            </h3>
            <p className="text-[10px] text-[#64748b]">Gear Deduction & Clutch Sync</p>
          </div>
        </div>

        {isSlipping ? (
          <div className="badge-pill badge-red animate-pulse">
            <AlertOctagon size={11} />
            CLUTCH SLIP
          </div>
        ) : (
          <div className="badge-pill badge-green">
            <CheckCircle2 size={11} />
            SYNCED
          </div>
        )}
      </div>

      {/* Main Gear Cluster */}
      <div className="grid grid-cols-2 gap-3 items-center telemetry-card-subtle">
        {/* Left: Giant Active Gear Box */}
        <div className="flex items-center gap-3">
          <div className="w-14 h-14 rounded-xl bg-[#08090d] border border-[#ff2a40]/50 flex flex-col items-center justify-center">
            <span className="text-3xl font-black text-[#f8fafc] font-['Chakra_Petch'] leading-none">
              {gear === 'CLUTCH' ? 'C' : gear}
            </span>
            <span className="text-[8px] uppercase font-bold text-[#ff6b7b] font-['Chakra_Petch'] mt-0.5">
              {gear === 'N' ? 'NEUTRAL' : gear === 'CLUTCH' ? 'CLUTCH' : 'GEAR'}
            </span>
          </div>

          <div className="flex flex-col">
            <span className="text-[9px] uppercase font-bold text-[#64748b] font-['Chakra_Petch']">
              Ratio
            </span>
            <div className="flex items-baseline gap-0.5">
              <span className="text-base font-bold text-[#f8fafc] font-['Chakra_Petch'] tabular-nums">
                {metrics.gearRatio > 0 ? metrics.gearRatio.toFixed(2) : '--'}
              </span>
              <span className="text-[10px] text-[#64748b]">:1</span>
            </div>
            <span className="text-[9px] text-[#94a3b8]">
              Shift: <strong className="text-[#f8fafc] font-['Chakra_Petch']">{metrics.optimalShiftRpm}</strong>
            </span>
          </div>
        </div>

        {/* Right: 5-Speed Shift Gate Selector */}
        <div className="flex items-center justify-end gap-1">
          {gears.map((g) => {
            const isCurrent = gear === g;
            return (
              <div
                key={g}
                className={`w-7 h-9 rounded-md flex flex-col items-center justify-center font-['Chakra_Petch'] font-bold text-xs transition-all ${
                  isCurrent
                    ? 'bg-[#ff2a40] text-white border border-[#ff4b5c]'
                    : 'bg-[#08090d] text-[#64748b] border border-[rgba(255,255,255,0.06)]'
                }`}
              >
                <span>{g}</span>
                <span className="text-[7px] opacity-70">MT</span>
              </div>
            );
          })}
        </div>
      </div>

      {/* Shift Advice Footer */}
      <div className="flex items-center justify-between px-1 text-[10px] font-['Chakra_Petch'] text-[#64748b]">
        <span className="flex items-center gap-1">
          <ArrowUpRight size={11} className="text-[#00e676]" />
          Shift Advisory:
        </span>
        <span className={metrics.shouldShiftUp && gear !== 5 ? 'text-[#00d2ff] font-bold animate-pulse' : 'text-[#94a3b8]'}>
          {metrics.shouldShiftUp && gear !== 5
            ? `Shift to ${typeof gear === 'number' ? gear + 1 : 'next'} gear`
            : gear === 5
            ? '5th Gear (Overdrive)'
            : 'Holding optimal power band'}
        </span>
      </div>
    </div>
  );
};
