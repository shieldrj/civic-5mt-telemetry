import React from 'react';

interface ShiftLightBarProps {
  stage: number; // 0 to 5
  rpm: number;
  shiftMode: 'eco' | 'power';
  shouldShiftUp: boolean;
  onToggleMode: () => void;
}

export const ShiftLightBar: React.FC<ShiftLightBarProps> = ({
  stage,
  rpm,
  shiftMode,
  shouldShiftUp,
  onToggleMode,
}) => {
  // 12 LED segments total
  // In Power mode: 4 Green, 4 Amber, 3 Cyan/Blue, 1 Red / Flashing
  // In Eco mode: 6 Green, 4 Amber, 2 Blue/Shift Prompt
  const totalLeds = 12;
  const activeLeds = Math.min(totalLeds, Math.round((stage / 5) * totalLeds));

  return (
    <div className="w-full bg-[#0b0d14] border border-[#1b2030] rounded-xl p-3 flex flex-col gap-2 shadow-lg">
      <div className="flex items-center justify-between px-1">
        <div className="flex items-center gap-2">
          <span className="text-[10px] font-bold tracking-widest text-[#64748b] uppercase font-['Chakra_Petch']">
            Shift Coach
          </span>
          <button
            onClick={onToggleMode}
            className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase transition-colors font-['Chakra_Petch'] ${
              shiftMode === 'eco'
                ? 'bg-[#00e676]/20 text-[#00e676] border border-[#00e676]/40'
                : 'bg-[#ff2a40]/20 text-[#ff2a40] border border-[#ff2a40]/40'
            }`}
          >
            {shiftMode === 'eco' ? '🌱 Eco Mode' : '⚡ VTEC / Power'}
          </button>
        </div>

        <div className="flex items-center gap-2 font-['Chakra_Petch'] text-xs">
          {shouldShiftUp && (
            <span className="animate-bounce text-[#00d2ff] font-bold flex items-center gap-1">
              ▲ SHIFT UP
            </span>
          )}
          <span className="text-[#94a3b8] font-semibold">{rpm} <span className="text-[10px] text-[#64748b]">RPM</span></span>
        </div>
      </div>

      {/* LED segments bar */}
      <div className={`grid grid-cols-12 gap-1.5 h-4 w-full p-1 bg-[#05060a] rounded-lg border border-[#161a26] ${
        stage >= 5 ? 'animate-redline' : ''
      }`}>
        {Array.from({ length: totalLeds }).map((_, index) => {
          const isActive = index < activeLeds;
          let activeColor = '#00e676'; // Green

          if (shiftMode === 'power') {
            if (index >= 8) {
              activeColor = '#ff2a40'; // Red
            } else if (index >= 6) {
              activeColor = '#00d2ff'; // Cyan
            } else if (index >= 3) {
              activeColor = '#ffaa00'; // Amber
            }
          } else {
            // Eco Mode
            if (index >= 9) {
              activeColor = '#ffaa00'; // Amber warning: revving high for eco
            } else if (index >= 6) {
              activeColor = '#00d2ff'; // Cyan shift prompt
            }
          }

          return (
            <div
              key={index}
              className="h-full rounded-sm transition-all duration-75"
              style={{
                backgroundColor: isActive ? activeColor : '#121520',
                boxShadow: isActive ? `0 0 8px ${activeColor}` : 'none',
              }}
            />
          );
        })}
      </div>
    </div>
  );
};
