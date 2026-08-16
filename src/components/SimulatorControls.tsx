import React from 'react';
import { Play, Sparkles, Sliders } from 'lucide-react';
import { SimulatorScenario } from '../services/simulator/civicSimulator';
import { telemetryManager } from '../services/telemetryManager';

interface SimulatorControlsProps {
  scenario: SimulatorScenario;
  onSelectScenario: (sc: SimulatorScenario) => void;
}

export const SimulatorControls: React.FC<SimulatorControlsProps> = ({
  scenario,
  onSelectScenario,
}) => {
  const sim = telemetryManager.simulator;

  const scenarios: { id: SimulatorScenario; label: string; desc: string }[] = [
    {
      id: 'city_commute',
      label: 'City Commute',
      desc: 'Red lights (idle loss), 1st-4th eco shifts, DFCO engine braking',
    },
    {
      id: 'spirited_pull',
      label: 'Spirited 6.5k Pull',
      desc: 'Wide Open Throttle, VTEC cam switch, peak redline shift lights',
    },
    {
      id: 'highway_cruise',
      label: 'Highway 5th Gear',
      desc: 'Cruising at 68 mph in 5th gear with optimal high MPG',
    },
    {
      id: 'manual',
      label: 'Interactive Pedals',
      desc: 'Manual throttle slider, clutch pedal, and gear selector',
    },
  ];

  return (
    <div className="telemetry-card flex flex-col gap-3">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="p-1.5 rounded-lg bg-[#00d2ff]/15 text-[#00d2ff] border border-[#00d2ff]/30">
            <Sliders size={18} />
          </div>
          <div>
            <h3 className="text-sm font-bold text-[#f8fafc] font-['Chakra_Petch'] tracking-wide">
              VIRTUAL 2013 CIVIC ECU BENCH
            </h3>
            <p className="text-[10px] text-[#64748b] font-medium">Test & Validate Live Calculations</p>
          </div>
        </div>

        <span className="badge-pill badge-cyan">
          <Sparkles size={12} />
          SIMULATOR READY
        </span>
      </div>

      {/* Scenario buttons */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
        {scenarios.map((s) => {
          const isSelected = scenario === s.id;
          return (
            <button
              key={s.id}
              onClick={() => onSelectScenario(s.id)}
              className={`p-2.5 rounded-xl border text-left flex flex-col justify-between transition-all ${
                isSelected
                  ? 'bg-[#182030] border-[#00d2ff] shadow-[0_0_12px_rgba(0,210,255,0.25)]'
                  : 'bg-[#090b10] border-[#161a26] hover:border-[#252b3d]'
              }`}
            >
              <div className="flex items-center justify-between w-full">
                <span className={`text-xs font-bold font-['Chakra_Petch'] ${isSelected ? 'text-[#00d2ff]' : 'text-[#f8fafc]'}`}>
                  {s.label}
                </span>
                {isSelected && <Play size={12} className="text-[#00d2ff] fill-[#00d2ff]" />}
              </div>
              <p className="text-[9px] text-[#64748b] mt-1 leading-tight">{s.desc}</p>
            </button>
          );
        })}
      </div>

      {/* Manual Controls Slider if scenario === 'manual' */}
      {scenario === 'manual' && (
        <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3 flex flex-col gap-3">
          <div className="flex items-center justify-between text-xs font-['Chakra_Petch']">
            <span className="text-[#94a3b8]">THROTTLE PEDAL</span>
            <span className="text-[#00d2ff] font-bold">{sim.throttlePos}%</span>
          </div>
          <input
            type="range"
            min={0}
            max={100}
            value={sim.throttlePos}
            onChange={(e) => {
              sim.throttlePos = parseInt(e.target.value, 10);
            }}
            className="w-full h-2 bg-[#161a26] rounded-lg appearance-none cursor-pointer accent-[#00d2ff]"
          />

          <div className="flex items-center justify-between pt-1">
            <button
              onMouseDown={() => { sim.clutchPressed = true; }}
              onMouseUp={() => { sim.clutchPressed = false; }}
              onTouchStart={() => { sim.clutchPressed = true; }}
              onTouchEnd={() => { sim.clutchPressed = false; }}
              className={`px-3 py-1.5 rounded-lg border text-xs font-bold font-['Chakra_Petch'] transition-all ${
                sim.clutchPressed
                  ? 'bg-[#ffaa00] text-black border-[#ffc966]'
                  : 'bg-[#161a26] text-[#94a3b8] border-[#252b3d]'
              }`}
            >
              🦶 Hold Clutch
            </button>

            {/* Gear Selector */}
            <div className="flex items-center gap-1">
              {(['N', 1, 2, 3, 4, 5] as const).map((g) => (
                <button
                  key={g}
                  onClick={() => {
                    sim.manualGear = g;
                  }}
                  className={`w-8 h-8 rounded-lg font-bold font-['Chakra_Petch'] text-xs border ${
                    sim.manualGear === g
                      ? 'bg-[#ff2a40] text-white border-[#ff4b5c]'
                      : 'bg-[#121622] text-[#64748b] border-[#1a2030]'
                  }`}
                >
                  {g}
                </button>
              ))}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
