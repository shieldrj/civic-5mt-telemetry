import React from 'react';
import { Fuel, DollarSign, Activity, Zap, TrendingUp, Waves } from 'lucide-react';
import { OBDLiveMetrics, TripAnalytics } from '../types/obd';

interface MpgTelemetryCardProps {
  metrics: OBDLiveMetrics;
  trip: TripAnalytics;
}

export const MpgTelemetryCard: React.FC<MpgTelemetryCardProps> = ({ metrics, trip }) => {
  const isDfco = metrics.isDfcoActive;
  
  // AFR status
  const afr = metrics.airFuelRatio;
  let afrStatus = 'Stoich (14.7)';
  let afrColor = 'text-[#00e676]';
  if (afr < 13.2) {
    afrStatus = 'Rich (Power)';
    afrColor = 'text-[#ffaa00]';
  } else if (afr > 15.8) {
    afrStatus = 'Lean (Eco)';
    afrColor = 'text-[#00d2ff]';
  }

  return (
    <div className="telemetry-card flex flex-col justify-between gap-3">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="p-1.5 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#ffaa00] border border-[rgba(255,255,255,0.08)]">
            <Fuel size={16} />
          </div>
          <div>
            <h3 className="text-xs font-bold text-[#f8fafc] font-['Chakra_Petch'] tracking-wide">
              PHYSICS MPG & FUEL FLOW
            </h3>
            <p className="text-[10px] text-[#64748b]">MAF + Lambda + Fuel Trim Dynamics</p>
          </div>
        </div>

        {isDfco ? (
          <div className="badge-pill badge-cyan animate-pulse">
            <Zap size={11} />
            DFCO ACTIVE (0.00 GPH)
          </div>
        ) : (
          <div className="badge-pill badge-green">
            <Activity size={11} />
            CLOSED LOOP
          </div>
        )}
      </div>

      {/* Main Split: Instant MPG & Flow Rate */}
      <div className="grid grid-cols-2 gap-3 items-center telemetry-card-subtle">
        {/* Instantaneous MPG */}
        <div className="flex flex-col">
          <span className="text-[9px] uppercase font-bold text-[#64748b] tracking-wider font-['Chakra_Petch']">
            Instantaneous
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span
              className={`text-3xl font-black font-['Chakra_Petch'] tabular-nums tracking-tight ${
                isDfco ? 'text-[#00d2ff]' : metrics.instantMpg >= 35 ? 'text-[#00e676]' : 'text-[#f8fafc]'
              }`}
            >
              {isDfco ? '99.9+' : metrics.instantMpg.toFixed(1)}
            </span>
            <span className="text-[10px] font-bold text-[#64748b] font-['Chakra_Petch']">MPG</span>
          </div>
          <span className="text-[9px] text-[#94a3b8]">
            30s Avg: <strong className="text-[#cbd5e1] font-['Chakra_Petch']">{metrics.rolling30sMpg} MPG</strong>
          </span>
        </div>

        {/* Burn Rate */}
        <div className="flex flex-col border-l border-[rgba(255,255,255,0.06)] pl-3">
          <span className="text-[9px] uppercase font-bold text-[#64748b] tracking-wider font-['Chakra_Petch']">
            Fuel Burn Rate
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-2xl font-bold text-[#ffaa00] font-['Chakra_Petch'] tabular-nums">
              {metrics.fuelFlowGalPerHour.toFixed(2)}
            </span>
            <span className="text-[10px] font-bold text-[#64748b] font-['Chakra_Petch']">GAL/HR</span>
          </div>
          <span className="text-[9px] text-[#94a3b8]">
            ({metrics.fuelFlowLitersPerHour.toFixed(2)} L/hr)
          </span>
        </div>
      </div>

      {/* Secondary Row: AFR & Idle Loss */}
      <div className="grid grid-cols-2 gap-2 text-xs">
        {/* AFR */}
        <div className="bg-[#08090d] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col justify-between">
          <div className="flex items-center justify-between text-[9px] font-bold text-[#64748b] font-['Chakra_Petch']">
            <span>AIR:FUEL</span>
            <span className={afrColor}>{afrStatus}</span>
          </div>
          <div className="flex items-baseline gap-1 mt-1">
            <span className="text-sm font-bold text-[#f8fafc] font-['Chakra_Petch'] tabular-nums">
              {afr.toFixed(2)} : 1
            </span>
            <span className="text-[8px] text-[#64748b]">λ {metrics.equivalenceRatio.toFixed(3)}</span>
          </div>
          {/* Micro stoich bar */}
          <div className="w-full bg-[#161a26] h-1 rounded-full mt-1 overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-[#ffaa00] via-[#00e676] to-[#00d2ff]"
              style={{
                width: `${Math.min(100, Math.max(0, ((afr - 10) / (20 - 10)) * 100))}%`,
              }}
            />
          </div>
        </div>

        {/* Idle Wastage */}
        <div className="bg-[#08090d] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col justify-between">
          <div className="flex items-center justify-between text-[9px] font-bold text-[#64748b] font-['Chakra_Petch']">
            <span className="flex items-center gap-0.5">
              <DollarSign size={9} className="text-[#ff6b7b]" />
              IDLE LOSS
            </span>
            <span className="text-[#ff6b7b] font-['Chakra_Petch']">
              ${trip.idleCostDollars.toFixed(2)}
            </span>
          </div>
          <div className="flex items-baseline gap-1 mt-1">
            <span className="text-sm font-bold text-[#f8fafc] font-['Chakra_Petch'] tabular-nums">
              {(trip.idleFuelGallons * 1000).toFixed(0)}
            </span>
            <span className="text-[8px] text-[#64748b]">mL wasted</span>
          </div>
          <span className="text-[8px] text-[#64748b]">
            {Math.floor(trip.idleTimeSec / 60)}m {Math.round(trip.idleTimeSec % 60)}s idle
          </span>
        </div>
      </div>

      {/* Fuel Trims */}
      <div className="flex items-center justify-between px-1 text-[10px] font-['Chakra_Petch'] text-[#64748b]">
        <span className="flex items-center gap-1">
          <TrendingUp size={11} />
          ECU Fuel Trims:
        </span>
        <div className="flex items-center gap-3">
          <span>
            STFT: <strong className={metrics.shortTermFuelTrim >= 0 ? 'text-[#00e676]' : 'text-[#ffaa00]'}>
              {metrics.shortTermFuelTrim > 0 ? `+${metrics.shortTermFuelTrim}` : metrics.shortTermFuelTrim}%
            </strong>
          </span>
          <span>
            LTFT: <strong className={metrics.longTermFuelTrim >= 0 ? 'text-[#00e676]' : 'text-[#ffaa00]'}>
              {metrics.longTermFuelTrim > 0 ? `+${metrics.longTermFuelTrim}` : metrics.longTermFuelTrim}%
            </strong>
          </span>
        </div>
      </div>

      {/* Oxygen Sensors - Catalyst Health Trace */}
      <div className="bg-[#08090d] border border-[rgba(255,255,255,0.06)] rounded-lg p-2.5 flex flex-col gap-2">
        <div className="flex items-center gap-1.5 text-[11px] font-bold text-[#94a3b8] font-['Chakra_Petch'] uppercase tracking-wider">
          <Waves size={13} className="text-[#00d2ff]" />
          Oxygen Sensors (Catalyst Health)
        </div>
        <div className="grid grid-cols-2 gap-3">
          {[
            { label: 'O2 S1 · Pre-Cat', volts: metrics.o2Sensor1Voltage, color: '#00d2ff' },
            { label: 'O2 S2 · Post-Cat', volts: metrics.o2Sensor2Voltage, color: '#ff2a40' },
          ].map((sensor) => (
            <div key={sensor.label} className="flex flex-col gap-1">
              <div className="flex items-center justify-between text-[11px] text-[#94a3b8]">
                <span>{sensor.label}</span>
                <span className="font-bold text-[#f8fafc] font-['Chakra_Petch'] tabular-nums text-[13px]">
                  {sensor.volts.toFixed(2)}V
                </span>
              </div>
              {/* Scaled 0 - 1.0V: a narrowband sensor only swings ~0.1-0.9V of the PID's
                  1.275V full scale, so scaling to full scale would flatten the trace. */}
              <div className="relative w-full bg-[#161a26] h-2 rounded-full overflow-hidden">
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
              <span className="text-[10px] text-[#64748b] font-['Chakra_Petch']">
                {sensor.volts >= 0.55 ? 'RICH' : sensor.volts <= 0.35 ? 'LEAN' : 'SWITCHING'}
              </span>
            </div>
          ))}
        </div>
        <p className="text-[11px] text-[#94a3b8] leading-relaxed">
          Healthy: pre-cat swings actively across the 0.45V line, post-cat stays comparatively
          steady. A post-cat trace that starts mirroring the pre-cat swing is the live signature
          behind code P0420 (Catalyst System Efficiency).
        </p>
      </div>
    </div>
  );
};
