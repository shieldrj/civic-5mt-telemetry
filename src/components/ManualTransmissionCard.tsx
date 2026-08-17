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
          <div className="p-1.5 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#9aa1a9] border border-[rgba(255,255,255,0.08)]">
            <Gauge size={16} />
          </div>
          <div>
            <h3 className="text-xs font-bold text-[#eef0f2] tracking-wide">
              5-SPEED MANUAL DYNAMICS
            </h3>
            <p className="text-[10px] text-[#6b727a]">Gear Deduction & Clutch Sync</p>
          </div>
        </div>

        {isSlipping ? (
          <div className="badge-pill badge-alert">
            <AlertOctagon size={11} />
            CLUTCH SLIP
          </div>
        ) : (
          <div className="badge-pill">
            <CheckCircle2 size={11} />
            SYNCED
          </div>
        )}
      </div>

      {/* Main Gear Cluster */}
      <div className="grid grid-cols-2 gap-3 items-center telemetry-card-subtle">
        {/* Left: Giant Active Gear Box */}
        <div className="flex items-center gap-3">
          <div className="w-14 h-14 rounded-xl bg-[#101215] border border-[#d8453b]/50 flex flex-col items-center justify-center">
            <span className="text-3xl font-medium text-[#eef0f2] leading-none">
              {gear === 'CLUTCH' ? 'C' : gear}
            </span>
            <span className="text-[8px] uppercase font-bold text-[#d8453b] mt-0.5">
              {gear === 'N' ? 'NEUTRAL' : gear === 'CLUTCH' ? 'CLUTCH' : 'GEAR'}
            </span>
          </div>

          <div className="flex flex-col">
            <span className="text-[9px] uppercase font-bold text-[#6b727a]">
              Ratio
            </span>
            <div className="flex items-baseline gap-0.5">
              <span className="text-base font-bold text-[#eef0f2] tabular-nums">
                {metrics.gearRatio > 0 ? metrics.gearRatio.toFixed(2) : '--'}
              </span>
              <span className="text-[10px] text-[#6b727a]">:1</span>
            </div>
            <span className="text-[9px] text-[#9aa1a9]">
              Shift: <strong className="text-[#eef0f2]">{metrics.optimalShiftRpm}</strong>
            </span>
          </div>
        </div>

        {/* Right: 5-Speed Shift Gate Selector */}
        <div className="flex items-center justify-end gap-1.5">
          {gears.map((g) => {
            const isCurrent = gear === g;
            return (
              <div
                key={g}
                className={`w-8 h-10 rounded-md flex flex-col items-center justify-center font-bold text-sm transition-all ${
                  isCurrent
                    ? 'bg-[#d8453b] text-white border border-[#d8453b]'
                    : 'bg-[#101215] text-[#6b727a] border border-[rgba(255,255,255,0.06)]'
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
      <div className="flex items-center justify-between px-1 text-[10px] text-[#6b727a]">
        <span className="flex items-center gap-1">
          <ArrowUpRight size={11} className="text-[#eef0f2]" />
          Shift Advisory:
        </span>
        <span className={metrics.shouldShiftUp && gear !== 5 ? 'text-[#9aa1a9] font-bold' : 'text-[#9aa1a9]'}>
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
