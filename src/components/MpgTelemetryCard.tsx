import React from 'react';
import { Fuel, DollarSign, Activity, Zap, TrendingUp, Waves } from 'lucide-react';
import { OBDLiveMetrics, TripAnalytics } from '../types/obd';
import { FUEL_BLENDS, FuelBlendId, FuelBlendProperties } from '../services/obd2/civicSpecs';

interface MpgTelemetryCardProps {
  metrics: OBDLiveMetrics;
  trip: TripAnalytics;
  activeBlend: FuelBlendProperties;
  onSelectFuelBlend: (id: FuelBlendId) => void;
}

export const MpgTelemetryCard: React.FC<MpgTelemetryCardProps> = ({
  metrics,
  trip,
  activeBlend,
  onSelectFuelBlend,
}) => {
  const isDfco = metrics.isDfcoActive;

  // AFR status, judged against the blend rather than a fixed 14.7. Stoichiometry moves
  // with the fuel, so comparing an E10 mixture to gasoline's ratio would read every
  // perfectly normal cruise as rich.
  const afr = metrics.airFuelRatio;
  const lambda = afr / activeBlend.stoichAfr;
  let afrStatus = `Stoich (${activeBlend.stoichAfr.toFixed(2)})`;
  let afrColor = 'text-[#eef0f2]';
  if (lambda < 0.97) {
    afrStatus = 'Rich (Power)';
    afrColor = 'text-[#c8952e]';
  } else if (lambda > 1.03) {
    afrStatus = 'Lean (Eco)';
    afrColor = 'text-[#9aa1a9]';
  }

  return (
    <div className="telemetry-card flex flex-col justify-between gap-3">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="p-1.5 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#c8952e] border border-[rgba(255,255,255,0.08)]">
            <Fuel size={16} />
          </div>
          <div>
            <h3 className="text-xs font-bold text-[#eef0f2] tracking-wide">
              PHYSICS MPG & FUEL FLOW
            </h3>
            <p className="text-[10px] text-[#6b727a]">MAF + Lambda + Fuel Trim Dynamics</p>
          </div>
        </div>

        {isDfco ? (
          <div className="badge-pill">
            <Zap size={11} />
            DFCO ACTIVE (0.00 GPH)
          </div>
        ) : (
          <div className="badge-pill">
            <Activity size={11} />
            CLOSED LOOP
          </div>
        )}
      </div>

      {/* Main Split: Instant MPG & Flow Rate */}
      <div className="grid grid-cols-2 gap-3 items-center telemetry-card-subtle">
        {/* Instantaneous MPG */}
        <div className="flex flex-col">
          <span className="text-[9px] uppercase font-bold text-[#6b727a] tracking-wider">
            Instantaneous
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span
              className={`text-3xl font-medium tabular-nums tracking-tight ${
                isDfco ? 'text-[#9aa1a9]' : metrics.instantMpg >= 35 ? 'text-[#eef0f2]' : 'text-[#eef0f2]'
              }`}
            >
              {isDfco ? '99.9+' : metrics.instantMpg.toFixed(1)}
            </span>
            <span className="text-[10px] font-bold text-[#6b727a]">MPG</span>
          </div>
          <span className="text-[9px] text-[#9aa1a9]">
            30s Avg: <strong className="text-[#9aa1a9]">{metrics.rolling30sMpg} MPG</strong>
          </span>
        </div>

        {/* Burn Rate */}
        <div className="flex flex-col border-l border-[rgba(255,255,255,0.06)] pl-3">
          <span className="text-[9px] uppercase font-bold text-[#6b727a] tracking-wider">
            Fuel Burn Rate
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-2xl font-bold text-[#c8952e] tabular-nums">
              {metrics.fuelFlowGalPerHour.toFixed(2)}
            </span>
            <span className="text-[10px] font-bold text-[#6b727a]">GAL/HR</span>
          </div>
          <span className="text-[9px] text-[#9aa1a9]">
            ({metrics.fuelFlowLitersPerHour.toFixed(2)} L/hr)
          </span>
        </div>
      </div>

      {/* Secondary Row: AFR & Idle Loss */}
      <div className="grid grid-cols-2 gap-2 text-xs">
        {/* AFR */}
        <div className="bg-[#101215] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col justify-between">
          <div className="flex items-center justify-between text-[9px] font-bold text-[#6b727a]">
            <span>AIR:FUEL</span>
            <span className={afrColor}>{afrStatus}</span>
          </div>
          <div className="flex items-baseline gap-1 mt-1">
            <span className="text-sm font-bold text-[#eef0f2] tabular-nums">
              {afr.toFixed(2)} : 1
            </span>
            <span className="text-[8px] text-[#6b727a]">λ {metrics.equivalenceRatio.toFixed(3)}</span>
          </div>
          {/* Micro stoich bar */}
          <div className="w-full bg-[#1f2328] h-1 rounded-full mt-1 overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-[#c8952e] via-[#eef0f2] to-[#9aa1a9]"
              style={{
                width: `${Math.min(100, Math.max(0, ((afr - 10) / (20 - 10)) * 100))}%`,
              }}
            />
          </div>
        </div>

        {/* Idle Wastage */}
        <div className="bg-[#101215] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col justify-between">
          <div className="flex items-center justify-between text-[9px] font-bold text-[#6b727a]">
            <span className="flex items-center gap-0.5">
              <DollarSign size={9} className="text-[#d8453b]" />
              IDLE LOSS
            </span>
            <span className="text-[#d8453b]">
              ${trip.idleCostDollars.toFixed(2)}
            </span>
          </div>
          <div className="flex items-baseline gap-1 mt-1">
            <span className="text-sm font-bold text-[#eef0f2] tabular-nums">
              {(trip.idleFuelGallons * 1000).toFixed(0)}
            </span>
            <span className="text-[8px] text-[#6b727a]">mL wasted</span>
          </div>
          <span className="text-[8px] text-[#6b727a]">
            {Math.floor(trip.idleTimeSec / 60)}m {Math.round(trip.idleTimeSec % 60)}s idle
          </span>
        </div>
      </div>

      {/* Fuel Trims */}
      <div className="flex items-center justify-between px-1 text-[10px] text-[#6b727a]">
        <span className="flex items-center gap-1">
          <TrendingUp size={11} />
          ECU Fuel Trims:
        </span>
        <div className="flex items-center gap-3">
          <span>
            STFT: <strong className={metrics.shortTermFuelTrim >= 0 ? 'text-[#eef0f2]' : 'text-[#c8952e]'}>
              {metrics.shortTermFuelTrim > 0 ? `+${metrics.shortTermFuelTrim}` : metrics.shortTermFuelTrim}%
            </strong>
          </span>
          <span>
            LTFT: <strong className={metrics.longTermFuelTrim >= 0 ? 'text-[#eef0f2]' : 'text-[#c8952e]'}>
              {metrics.longTermFuelTrim > 0 ? `+${metrics.longTermFuelTrim}` : metrics.longTermFuelTrim}%
            </strong>
          </span>
        </div>
      </div>

      {/* Fuel blend - drives the stoichiometric ratio and density every fuel figure
          on this screen depends on, so it is set here rather than buried in a menu. */}
      <div className="bg-[#101215] border border-[rgba(255,255,255,0.06)] rounded-lg p-2.5 flex flex-col gap-2">
        <div className="flex items-center justify-between gap-2">
          <span className="text-[11px] font-bold text-[#9aa1a9] uppercase tracking-wider">
            Fuel In Tank
          </span>
          <span className="text-[11px] text-[#6b727a] tabular-nums">
            {activeBlend.stoichAfr.toFixed(2)}:1 &bull; {activeBlend.densityGramsPerLiter.toFixed(0)} g/L
          </span>
        </div>
        <div className="grid grid-cols-3 gap-1.5">
          {(Object.keys(FUEL_BLENDS) as FuelBlendId[]).map((id) => (
            <button
              key={id}
              onClick={() => onSelectFuelBlend(id)}
              className={`py-1.5 rounded-md text-[12px] font-bold border transition-all ${
                activeBlend.id === id
                  ? 'bg-[#d8453b] text-white border-[#d8453b]'
                  : 'bg-[#181b20] text-[#9aa1a9] border-[rgba(255,255,255,0.08)]'
              }`}
            >
              {id}
            </button>
          ))}
        </div>
      </div>

      {/* Oxygen Sensors - Catalyst Health Trace */}
      <div className="bg-[#101215] border border-[rgba(255,255,255,0.06)] rounded-lg p-2.5 flex flex-col gap-2">
        <div className="flex items-center gap-1.5 text-[11px] font-bold text-[#9aa1a9] uppercase tracking-wider">
          <Waves size={13} className="text-[#9aa1a9]" />
          Oxygen Sensors (Catalyst Health)
        </div>
        <div className="grid grid-cols-2 gap-3">
          {[
            { label: 'O2 S1 · Pre-Cat', volts: metrics.o2Sensor1Voltage, color: '#9aa1a9' },
            { label: 'O2 S2 · Post-Cat', volts: metrics.o2Sensor2Voltage, color: '#d8453b' },
          ].map((sensor) => (
            <div key={sensor.label} className="flex flex-col gap-1">
              <div className="flex items-center justify-between text-[11px] text-[#9aa1a9]">
                <span>{sensor.label}</span>
                <span className="font-bold text-[#eef0f2] tabular-nums text-[13px]">
                  {sensor.volts.toFixed(2)}V
                </span>
              </div>
              {/* Scaled 0 - 1.0V: a narrowband sensor only swings ~0.1-0.9V of the PID's
                  1.275V full scale, so scaling to full scale would flatten the trace. */}
              <div className="relative w-full bg-[#1f2328] h-2 rounded-full overflow-hidden">
                <div
                  className="h-full transition-all duration-150"
                  style={{
                    width: `${Math.max(0, Math.min(100, (sensor.volts / 1.0) * 100))}%`,
                    backgroundColor: sensor.color,
                  }}
                />
                {/* 0.45V stoichiometric switch point */}
                <div className="absolute inset-y-0 left-[45%] w-px bg-[rgba(255,255,255,0.45)]" />
              </div>
              <span className="text-[10px] text-[#6b727a]">
                {sensor.volts >= 0.55 ? 'RICH' : sensor.volts <= 0.35 ? 'LEAN' : 'SWITCHING'}
              </span>
            </div>
          ))}
        </div>
        <p className="text-[11px] text-[#9aa1a9] leading-relaxed">
          Healthy: pre-cat swings actively across the 0.45V line, post-cat stays comparatively
          steady. A post-cat trace that starts mirroring the pre-cat swing is the live signature
          behind code P0420 (Catalyst System Efficiency).
        </p>
      </div>
    </div>
  );
};
