import React from 'react';
import { Fuel, DollarSign, Activity, Zap, TrendingUp } from 'lucide-react';
import { OBDLiveMetrics, TripAnalytics } from '../types/obd';

interface MpgTelemetryCardProps {
  metrics: OBDLiveMetrics;
  trip: TripAnalytics;
}

export const MpgTelemetryCard: React.FC<MpgTelemetryCardProps> = ({ metrics, trip }) => {
  const isDfco = metrics.isDfcoActive;
  
  // Calculate AFR color status (14.7 is optimal stoich)
  const afr = metrics.airFuelRatio;
  let afrStatus = 'Optimal Stoich';
  let afrColor = 'text-[#00e676]';
  if (afr < 13.0) {
    afrStatus = 'Rich (Power)';
    afrColor = 'text-[#ffaa00]';
  } else if (afr > 16.0) {
    afrStatus = 'Lean (Economy)';
    afrColor = 'text-[#00d2ff]';
  }

  return (
    <div className="telemetry-card flex flex-col justify-between gap-3">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="p-1.5 rounded-lg bg-[#ffaa00]/15 text-[#ffaa00] border border-[#ffaa00]/30">
            <Fuel size={18} />
          </div>
          <div>
            <h3 className="text-sm font-bold text-[#f8fafc] font-['Chakra_Petch'] tracking-wide">
              PHYSICS MPG & FUEL FLOW
            </h3>
            <p className="text-[10px] text-[#64748b] font-medium">MAF + Lambda + Fuel Trim Dynamics</p>
          </div>
        </div>

        {/* DFCO Status Badge */}
        {isDfco ? (
          <div className="badge-pill badge-cyan animate-pulse">
            <Zap size={12} />
            DFCO ACTIVE (0.00 GPH)
          </div>
        ) : (
          <div className="badge-pill badge-green">
            <Activity size={12} />
            CLOSED LOOP
          </div>
        )}
      </div>

      {/* Main Big MPG & Flow Split */}
      <div className="grid grid-cols-2 gap-3 bg-[#090b10] border border-[#161a26] rounded-xl p-3.5 items-center">
        {/* Instant MPG */}
        <div className="flex flex-col">
          <span className="text-[10px] uppercase font-bold text-[#64748b] tracking-wider font-['Chakra_Petch']">
            Instantaneous
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span
              className={`text-3xl font-extrabold font-['Chakra_Petch'] tracking-tight ${
                isDfco ? 'text-[#00d2ff] glow-cyan' : metrics.instantMpg >= 35 ? 'text-[#00e676] glow-green' : 'text-[#f8fafc]'
              }`}
            >
              {isDfco ? '99.9+' : metrics.instantMpg.toFixed(1)}
            </span>
            <span className="text-xs font-bold text-[#64748b] font-['Chakra_Petch']">MPG</span>
          </div>
          <span className="text-[10px] text-[#475569] font-medium">
            30s Avg: <strong className="text-[#94a3b8]">{metrics.rolling30sMpg} MPG</strong>
          </span>
        </div>

        {/* Real-time Fuel Flow */}
        <div className="flex flex-col border-l border-[#1a2030] pl-3">
          <span className="text-[10px] uppercase font-bold text-[#64748b] tracking-wider font-['Chakra_Petch']">
            Fuel Burn Rate
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-2xl font-extrabold text-[#ffaa00] font-['Chakra_Petch']">
              {metrics.fuelFlowGalPerHour.toFixed(2)}
            </span>
            <span className="text-xs font-bold text-[#64748b] font-['Chakra_Petch']">GAL/HR</span>
          </div>
          <span className="text-[10px] text-[#475569] font-medium">
            ({metrics.fuelFlowLitersPerHour.toFixed(2)} L/hr)
          </span>
        </div>
      </div>

      {/* Secondary Telemetry Grid: AFR & Idle Fuel Loss */}
      <div className="grid grid-cols-2 gap-2 text-xs">
        {/* Air-Fuel Ratio Monitor */}
        <div className="bg-[#0b0e16] border border-[#161a26] rounded-lg p-2 flex flex-col justify-between">
          <div className="flex items-center justify-between text-[10px] font-bold text-[#64748b] font-['Chakra_Petch']">
            <span>AIR : FUEL RATIO</span>
            <span className={afrColor}>{afrStatus}</span>
          </div>
          <div className="flex items-baseline gap-1 mt-1">
            <span className="text-base font-bold text-[#f8fafc] font-['Chakra_Petch']">
              {afr.toFixed(2)} : 1
            </span>
            <span className="text-[9px] text-[#64748b]">λ {metrics.equivalenceRatio.toFixed(3)}</span>
          </div>
          {/* Visual stoich bar */}
          <div className="w-full bg-[#161a26] h-1.5 rounded-full mt-1.5 relative overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-[#ffaa00] via-[#00e676] to-[#00d2ff] transition-all duration-150"
              style={{
                width: `${Math.min(100, Math.max(0, ((afr - 10) / (20 - 10)) * 100))}%`,
              }}
            />
          </div>
        </div>

        {/* Idle Fuel Wastage Counter */}
        <div className="bg-[#0b0e16] border border-[#161a26] rounded-lg p-2 flex flex-col justify-between">
          <div className="flex items-center justify-between text-[10px] font-bold text-[#64748b] font-['Chakra_Petch']">
            <span className="flex items-center gap-1">
              <DollarSign size={10} className="text-[#ff2a40]" />
              IDLE FUEL LOSS
            </span>
            <span className="text-[#ff6b7b]">
              ${trip.idleCostDollars.toFixed(2)}
            </span>
          </div>
          <div className="flex items-baseline gap-1 mt-1">
            <span className="text-base font-bold text-[#f8fafc] font-['Chakra_Petch']">
              {(trip.idleFuelGallons * 1000).toFixed(0)}
            </span>
            <span className="text-[9px] text-[#64748b]">mL wasted</span>
          </div>
          <span className="text-[9px] text-[#475569]">
            {Math.floor(trip.idleTimeSec / 60)}m {Math.round(trip.idleTimeSec % 60)}s stationary
          </span>
        </div>
      </div>

      {/* Fuel Trims (STFT + LTFT) */}
      <div className="flex items-center justify-between bg-[#08090d] border border-[#141722] rounded-lg px-2.5 py-1.5 text-[11px] font-['Chakra_Petch']">
        <span className="text-[#64748b] flex items-center gap-1">
          <TrendingUp size={12} />
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
    </div>
  );
};
