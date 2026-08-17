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
          <div className="p-1.5 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#9aa1a9] border border-[rgba(255,255,255,0.08)]">
            <Sliders size={16} />
          </div>
          <div>
            <h3 className="text-xs font-bold text-[#eef0f2] tracking-wide">
              VIRTUAL 2013 CIVIC ECU BENCH
            </h3>
            <p className="text-[10px] text-[#6b727a]">Real-time Calculation & Sensor Simulation</p>
          </div>
        </div>

        <span className="badge-pill">
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
                  ? 'bg-[#182030] border-[#9aa1a9]'
                  : 'bg-[#101215] border-[rgba(255,255,255,0.06)] hover:border-[rgba(255,255,255,0.12)]'
              }`}
            >
              <div className="flex items-center justify-between w-full">
                <span className={`text-xs font-bold ${isSelected ? 'text-[#9aa1a9]' : 'text-[#eef0f2]'}`}>
                  {s.label}
                </span>
                {isSelected && <Play size={10} className="text-[#9aa1a9] fill-[#9aa1a9]" />}
              </div>
              <p className="text-[9px] text-[#6b727a] mt-1 leading-tight">{s.desc}</p>
            </button>
          );
        })}
      </div>

      {/* Interactive Pedals Slider */}
      {scenario === 'manual' && (
        <div className="telemetry-card-subtle flex flex-col gap-2.5">
          <div className="flex items-center justify-between text-xs">
            <span className="text-[#9aa1a9] text-[11px]">THROTTLE PEDAL</span>
            <span className="text-[#9aa1a9] font-bold tabular-nums">{sim.throttlePos}%</span>
          </div>
          <input
            type="range"
            min={0}
            max={100}
            value={sim.throttlePos}
            onChange={(e) => {
              sim.throttlePos = parseInt(e.target.value, 10);
            }}
            className="w-full h-1.5 bg-[#1f2328] rounded-lg appearance-none cursor-pointer accent-[#9aa1a9]"
          />

          <div className="flex items-center justify-between pt-1">
            <button
              onMouseDown={() => { sim.clutchPressed = true; }}
              onMouseUp={() => { sim.clutchPressed = false; }}
              onTouchStart={() => { sim.clutchPressed = true; }}
              onTouchEnd={() => { sim.clutchPressed = false; }}
              className={`px-3 py-1.5 rounded-lg border text-xs font-bold transition-all ${
                sim.clutchPressed
                  ? 'bg-[#c8952e] text-black border-[#c8952e]'
                  : 'bg-[#1f2328] text-[#9aa1a9] border-[#252b3d]'
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
                  className={`w-7 h-7 rounded-md font-bold text-xs border ${
                    sim.manualGear === g
                      ? 'bg-[#d8453b] text-white border-[#d8453b]'
                      : 'bg-[#1f2328] text-[#6b727a] border-[#1a2030]'
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
