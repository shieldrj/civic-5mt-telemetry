import React from 'react';
import { Zap, Sparkles } from 'lucide-react';

interface ShiftLightBarProps {
  stage: number; // 0 to 5
  rpm: number;
  shiftMode: 'eco' | 'power';
  shouldShiftUp: boolean;
  onToggleMode: () => void;
  currentGear?: number | 'N' | 'CLUTCH';
}

export const ShiftLightBar: React.FC<ShiftLightBarProps> = ({
  stage,
  rpm,
  shiftMode,
  shouldShiftUp,
  onToggleMode,
  currentGear = 'N',
}) => {
  const totalLeds = 16;
  const activeLeds = Math.min(totalLeds, Math.round((stage / 5) * totalLeds));

  return (
    <div className="w-full bg-[#0e111a] border border-[rgba(255,255,255,0.08)] rounded-xl p-2.5 sm:p-3 flex flex-col gap-2 shadow-sm">
      {/* Top Header: Shift Coach, Active Gear & RPM Readout */}
      <div className="flex items-center justify-between px-0.5">
        {/* Left: Mode Switcher */}
        <div className="flex items-center gap-2">
          <button
            onClick={onToggleMode}
            className={`flex items-center gap-1.5 px-2.5 py-1 rounded-md text-[10px] font-bold uppercase transition-colors font-['Chakra_Petch'] ${
              shiftMode === 'eco'
                ? 'bg-[#00e676]/15 text-[#5aff9f] border border-[#00e676]/30 hover:bg-[#00e676]/25'
                : 'bg-[#ff2a40]/15 text-[#ff6b7b] border border-[#ff2a40]/30 hover:bg-[#ff2a40]/25'
            }`}
          >
            {shiftMode === 'eco' ? (
              <>
                <Sparkles size={11} className="text-[#00e676]" />
                <span>ECO SHIFT</span>
              </>
            ) : (
              <>
                <Zap size={11} className="text-[#ff2a40]" />
                <span>VTEC POWER</span>
              </>
            )}
          </button>

          {/* Shift Guidance Cue */}
          {shouldShiftUp && (
            <span className="text-[#00d2ff] font-bold text-xs flex items-center gap-1 animate-pulse font-['Chakra_Petch']">
              ▲ SHIFT UP
            </span>
          )}
        </div>

        {/* Right: Active Gear & Live RPM Readout */}
        <div className="flex items-center gap-2 font-['Chakra_Petch']">
          {/* Prominent Active Gear Pill */}
          <div className="flex items-center gap-1 bg-[#08090d] border border-[#ff2a40]/40 px-2.5 py-0.5 rounded-lg">
            <span className="text-base font-black text-[#f8fafc] leading-none">
              {currentGear === 'CLUTCH' ? 'C' : currentGear}
            </span>
            <span className="text-[9px] uppercase font-bold text-[#ff6b7b]">
              {currentGear === 'N' ? 'NEUTRAL' : currentGear === 'CLUTCH' ? 'CLUTCH' : 'GEAR'}
            </span>
          </div>

          {/* Large RPM Digital Readout */}
          <div className="flex items-baseline gap-1 bg-[#08090d] px-2.5 py-0.5 rounded-lg border border-[rgba(255,255,255,0.08)]">
            <span className="text-[#f8fafc] font-black tabular-nums text-sm sm:text-base">
              {rpm}
            </span>
            <span className="text-[9px] text-[#64748b] font-semibold">RPM</span>
          </div>
        </div>
      </div>

      {/* LED Segmented Ribbon */}
      <div
        className={`grid grid-cols-16 gap-1 h-3.5 sm:h-4 w-full p-1 bg-[#08090d] rounded-lg border border-[rgba(255,255,255,0.06)] ${
          stage >= 5 ? 'animate-redline' : ''
        }`}
      >
        {Array.from({ length: totalLeds }).map((_, index) => {
          const isActive = index < activeLeds;
          let segmentColor = '#00e676'; // Eco Green

          if (shiftMode === 'power') {
            if (index >= 12) {
              segmentColor = '#ff2a40'; // Redline
            } else if (index >= 9) {
              segmentColor = '#00d2ff'; // VTEC Cam
            } else if (index >= 5) {
              segmentColor = '#ffaa00'; // Midband
            }
          } else {
            // Eco Mode
            if (index >= 12) {
              segmentColor = '#ffaa00'; // Over-rev for eco
            } else if (index >= 8) {
              segmentColor = '#00d2ff'; // Optimal Shift Prompt
            }
          }

          return (
            <div
              key={index}
              className="h-full rounded-xs transition-colors duration-75"
              style={{
                backgroundColor: isActive ? segmentColor : '#131722',
                opacity: isActive ? 1 : 0.35,
              }}
            />
          );
        })}
      </div>
    </div>
  );
};
