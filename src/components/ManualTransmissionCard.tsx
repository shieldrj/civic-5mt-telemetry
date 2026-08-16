import React from 'react';
import { Gauge, AlertOctagon, ArrowUpRight } from 'lucide-react';
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
      {/* Card Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="p-1.5 rounded-lg bg-[#00d2ff]/15 text-[#00d2ff] border border-[#00d2ff]/30">
            <Gauge size={18} />
          </div>
          <div>
            <h3 className="text-sm font-bold text-[#f8fafc] font-['Chakra_Petch'] tracking-wide">
              5-SPEED MANUAL DYNAMICS
            </h3>
            <p className="text-[10px] text-[#64748b] font-medium">Real-time Gear & Clutch Analysis</p>
          </div>
        </div>

        {isSlipping ? (
          <div className="badge-pill badge-red animate-bounce">
            <AlertOctagon size={12} />
            CLUTCH SLIP DETECTED
          </div>
        ) : (
          <div className="badge-pill badge-cyan">
            5MT SYNCED
          </div>
        )}
      </div>

      {/* Main Gear Cluster Display */}
      <div className="flex items-center justify-between bg-[#090b10] border border-[#161a26] rounded-xl p-3.5">
        {/* Giant Active Gear Badge */}
        <div className="flex items-center gap-3">
          <div className="w-16 h-16 rounded-2xl bg-[#121622] border-2 border-[#ff2a40] flex flex-col items-center justify-center shadow-[0_0_15px_rgba(255,42,64,0.3)]">
            <span className="text-3xl font-extrabold text-[#f8fafc] font-['Chakra_Petch'] leading-none">
              {gear === 'CLUTCH' ? 'C' : gear}
            </span>
            <span className="text-[9px] uppercase font-bold text-[#ff6b7b] font-['Chakra_Petch'] mt-0.5">
              {gear === 'N' ? 'NEUTRAL' : gear === 'CLUTCH' ? 'CLUTCH' : 'GEAR'}
            </span>
          </div>

          {/* Ratio telemetry */}
          <div className="flex flex-col">
            <span className="text-[10px] uppercase font-bold text-[#64748b] font-['Chakra_Petch']">
              Overall Ratio
            </span>
            <div className="flex items-baseline gap-1">
              <span className="text-xl font-bold text-[#f8fafc] font-['Chakra_Petch']">
                {metrics.gearRatio > 0 ? metrics.gearRatio.toFixed(2) : '--'}
              </span>
              <span className="text-xs text-[#64748b] font-['Chakra_Petch']">: 1</span>
            </div>
            <span className="text-[10px] text-[#475569]">
              Shift target: <strong className="text-[#94a3b8]">{metrics.optimalShiftRpm} RPM</strong>
            </span>
          </div>
        </div>

        {/* 5-Speed Shift Gate Selector Indicators */}
        <div className="flex items-center gap-1">
          {gears.map((g) => {
            const isCurrent = gear === g;
            return (
              <div
                key={g}
                className={`w-7 h-10 rounded-lg flex flex-col items-center justify-center border font-['Chakra_Petch'] font-bold text-xs transition-all duration-150 ${
                  isCurrent
                    ? 'bg-[#ff2a40] text-white border-[#ff4b5c] shadow-[0_0_10px_#ff2a40]'
                    : 'bg-[#0e1118] text-[#475569] border-[#1a2030]'
                }`}
              >
                <span>{g}</span>
                <span className="text-[8px] opacity-70">MT</span>
              </div>
            );
          })}
        </div>
      </div>

      {/* Clutch Slip / Shift Advisor Notice */}
      <div className="flex items-center justify-between bg-[#08090d] border border-[#141722] rounded-lg px-2.5 py-1.5 text-[11px] font-['Chakra_Petch']">
        <span className="text-[#64748b] flex items-center gap-1">
          <ArrowUpRight size={12} className="text-[#00e676]" />
          Shift Guidance:
        </span>
        <span className={metrics.shouldShiftUp ? 'text-[#00d2ff] font-bold animate-pulse' : 'text-[#94a3b8]'}>
          {metrics.shouldShiftUp
            ? `Upshift to ${typeof gear === 'number' ? gear + 1 : 'next gear'} for fuel efficiency`
            : 'Holding optimal RPM band'}
        </span>
      </div>
    </div>
  );
};
