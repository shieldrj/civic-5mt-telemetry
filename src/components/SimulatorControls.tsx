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
          <div className="p-1.5 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#00d2ff] border border-[rgba(255,255,255,0.08)]">
            <Sliders size={16} />
          </div>
          <div>
            <h3 className="text-xs font-bold text-[#f8fafc] font-['Chakra_Petch'] tracking-wide">
              VIRTUAL 2013 CIVIC ECU BENCH
            </h3>
            <p className="text-[10px] text-[#64748b]">Real-time Calculation & Sensor Simulation</p>
          </div>
        </div>

        <span className="badge-pill badge-cyan">
          <Sparkles size={11} />
          SIMULATOR READY
        </span>
      </div>

      {/* Scenario Buttons */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
        {scenarios.map((s) => {
          const isSelected = scenario === s.id;
          return (
            <button
              key={s.id}
              onClick={() => onSelectScenario(s.id)}
              className={`p-2.5 rounded-xl border text-left flex flex-col justify-between transition-all ${
                isSelected
                  ? 'bg-[#182030] border-[#00d2ff]'
                  : 'bg-[#08090d] border-[rgba(255,255,255,0.06)] hover:border-[rgba(255,255,255,0.12)]'
              }`}
            >
              <div className="flex items-center justify-between w-full">
                <span className={`text-xs font-bold font-['Chakra_Petch'] ${isSelected ? 'text-[#00d2ff]' : 'text-[#f8fafc]'}`}>
                  {s.label}
                </span>
                {isSelected && <Play size={10} className="text-[#00d2ff] fill-[#00d2ff]" />}
              </div>
              <p className="text-[9px] text-[#64748b] mt-1 leading-tight">{s.desc}</p>
            </button>
          );
        })}
      </div>

      {/* Interactive Pedals Slider */}
      {scenario === 'manual' && (
        <div className="telemetry-card-subtle flex flex-col gap-2.5">
          <div className="flex items-center justify-between text-xs font-['Chakra_Petch']">
            <span className="text-[#94a3b8] text-[11px]">THROTTLE PEDAL</span>
            <span className="text-[#00d2ff] font-bold tabular-nums">{sim.throttlePos}%</span>
          </div>
          <input
            type="range"
            min={0}
            max={100}
            value={sim.throttlePos}
            onChange={(e) => {
              sim.throttlePos = parseInt(e.target.value, 10);
            }}
            className="w-full h-1.5 bg-[#161a26] rounded-lg appearance-none cursor-pointer accent-[#00d2ff]"
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
                  className={`w-7 h-7 rounded-md font-bold font-['Chakra_Petch'] text-xs border ${
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
