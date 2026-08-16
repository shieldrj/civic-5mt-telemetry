import React, { useState } from 'react';
import { Droplet, RotateCcw, AlertTriangle, ShieldCheck, ThermometerSnowflake } from 'lucide-react';
import { OilLifeProfile } from '../types/obd';

interface OilLifeCardProps {
  oilProfile: OilLifeProfile;
  coolantTempF?: number;
  onResetOil: () => void;
}

export const OilLifeCard: React.FC<OilLifeCardProps> = ({
  oilProfile,
  onResetOil,
}) => {
  const [showResetModal, setShowResetModal] = useState(false);

  const percent = oilProfile.oilLifePercent;
  let statusColor = '#00e676';
  let badgeClass = 'badge-green';

  if (percent < 15) {
    statusColor = '#ff2a40';
    badgeClass = 'badge-red';
  } else if (percent < 40) {
    statusColor = '#ffaa00';
    badgeClass = 'badge-amber';
  }

  // Circular progress math
  const radius = 44;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference - (percent / 100) * circumference;

  return (
    <div className="telemetry-card flex flex-col justify-between gap-3">
      {/* Card Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="p-1.5 rounded-lg bg-[#ff2a40]/15 text-[#ff2a40] border border-[#ff2a40]/30">
            <Droplet size={18} />
          </div>
          <div>
            <h3 className="text-sm font-bold text-[#f8fafc] font-['Chakra_Petch'] tracking-wide">
              OIL LIFE & WEAR MODEL
            </h3>
            <p className="text-[10px] text-[#64748b] font-medium">Revs + Cold Starts + Thermal Stress</p>
          </div>
        </div>

        <button
          onClick={() => setShowResetModal(true)}
          className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-[#161a26] hover:bg-[#1f2638] text-[#94a3b8] hover:text-[#f8fafc] border border-[#252b3d] text-[11px] font-['Chakra_Petch'] transition-colors"
        >
          <RotateCcw size={12} />
          Reset
        </button>
      </div>

      {/* Main Health Display */}
      <div className="flex items-center justify-between bg-[#090b10] border border-[#161a26] rounded-xl p-3.5">
        {/* Radial Circle */}
        <div className="relative flex items-center justify-center">
          <svg width={104} height={104} className="transform -rotate-90">
            <circle
              cx={52}
              cy={52}
              r={radius}
              stroke="#161a26"
              strokeWidth={8}
              fill="transparent"
            />
            <circle
              cx={52}
              cy={52}
              r={radius}
              stroke={statusColor}
              strokeWidth={8}
              fill="transparent"
              strokeDasharray={circumference}
              strokeDashoffset={strokeDashoffset}
              strokeLinecap="round"
              className="transition-all duration-500 ease-out"
            />
          </svg>
          <div className="absolute flex flex-col items-center justify-center text-center">
            <span
              className="text-2xl font-extrabold font-['Chakra_Petch']"
              style={{ color: statusColor }}
            >
              {Math.round(percent)}%
            </span>
            <span className="text-[9px] uppercase font-bold text-[#64748b] font-['Chakra_Petch']">
              HEALTH
            </span>
          </div>
        </div>

        {/* Projected Wear & Remaining Mileage */}
        <div className="flex flex-col gap-1.5 pl-2">
          <div className="flex items-center gap-1.5">
            <span className={`badge-pill ${badgeClass}`}>
              <ShieldCheck size={12} />
              {oilProfile.oilConditionGrade.toUpperCase()}
            </span>
          </div>
          <div className="flex flex-col">
            <span className="text-[10px] uppercase font-bold text-[#64748b] font-['Chakra_Petch']">
              EST. REMAINING
            </span>
            <span className="text-lg font-bold text-[#f8fafc] font-['Chakra_Petch']">
              ~{oilProfile.estimatedMilesRemaining.toLocaleString()} <span className="text-xs text-[#64748b]">mi</span>
            </span>
            <span className="text-[10px] text-[#475569]">
              approx {oilProfile.estimatedDaysRemaining} days at current driving rate
            </span>
          </div>
        </div>
      </div>

      {/* Deep Factor Metrics Grid */}
      <div className="grid grid-cols-2 gap-2 text-xs">
        {/* Accumulated Revolutions */}
        <div className="bg-[#0b0e16] border border-[#161a26] rounded-lg p-2 flex flex-col justify-between">
          <span className="text-[10px] font-bold text-[#64748b] font-['Chakra_Petch']">
            MECHANICAL REVS
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-base font-bold text-[#f8fafc] font-['Chakra_Petch']">
              {(oilProfile.accumulatedRevolutions / 1000000).toFixed(2)}M
            </span>
            <span className="text-[9px] text-[#64748b]">cycles</span>
          </div>
          <span className="text-[9px] text-[#475569]">
            Wear factor: {oilProfile.degradationBreakdown.revWearFactor}%
          </span>
        </div>

        {/* Cold Starts & Short Trips */}
        <div className="bg-[#0b0e16] border border-[#161a26] rounded-lg p-2 flex flex-col justify-between">
          <div className="flex items-center justify-between text-[10px] font-bold text-[#64748b] font-['Chakra_Petch']">
            <span className="flex items-center gap-1">
              <ThermometerSnowflake size={11} className="text-[#00d2ff]" />
              COLD STARTS
            </span>
            <span className="text-[#00d2ff]">{oilProfile.coldStartsCount} logged</span>
          </div>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-sm font-bold text-[#94a3b8] font-['Chakra_Petch']">
              {oilProfile.shortTripsCount}
            </span>
            <span className="text-[9px] text-[#64748b]">short trips (&lt;15m)</span>
          </div>
          <span className="text-[9px] text-[#475569]">
            Dilution penalty: +{oilProfile.degradationBreakdown.coldStartPenalty}%
          </span>
        </div>
      </div>

      {/* Degradation Breakdown Visual Bar */}
      <div className="bg-[#08090d] border border-[#141722] rounded-lg p-2 flex flex-col gap-1 text-[10px] font-['Chakra_Petch']">
        <div className="flex justify-between text-[#64748b]">
          <span>WEAR DECOMPOSITION</span>
          <span>{oilProfile.highThermalStressSec}s high RPM load</span>
        </div>
        <div className="w-full bg-[#161a26] h-2 rounded-full flex overflow-hidden">
          <div
            title="Mechanical Revs"
            className="bg-[#00e676] h-full"
            style={{ width: `${oilProfile.degradationBreakdown.revWearFactor}%` }}
          />
          <div
            title="Cold Start Shear"
            className="bg-[#00d2ff] h-full"
            style={{ width: `${oilProfile.degradationBreakdown.coldStartPenalty}%` }}
          />
          <div
            title="Short Trip Dilution"
            className="bg-[#ffaa00] h-full"
            style={{ width: `${oilProfile.degradationBreakdown.shortTripPenalty}%` }}
          />
          <div
            title="High Thermal Stress"
            className="bg-[#ff2a40] h-full"
            style={{ width: `${oilProfile.degradationBreakdown.thermalShearPenalty}%` }}
          />
        </div>
      </div>

      {/* Reset Modal */}
      {showResetModal && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-[#0e1118] border border-[#252b3d] rounded-2xl max-w-sm w-full p-5 shadow-2xl flex flex-col gap-4">
            <div className="flex items-center gap-2 text-[#ffaa00]">
              <AlertTriangle size={24} />
              <h3 className="text-base font-bold font-['Chakra_Petch'] text-[#f8fafc]">
                Reset Oil Life Tracker?
              </h3>
            </div>
            <p className="text-xs text-[#94a3b8] leading-relaxed">
              This will reset the accumulated revolutions, cold start counter, and oil health grade back to <strong>100% (7,500 miles)</strong>. Perform this after fresh oil and filter replacement.
            </p>
            <div className="flex justify-end gap-2 pt-2">
              <button
                onClick={() => setShowResetModal(false)}
                className="px-3 py-1.5 rounded-lg bg-[#161a26] text-[#94a3b8] hover:text-[#f8fafc] text-xs font-['Chakra_Petch']"
              >
                Cancel
              </button>
              <button
                onClick={() => {
                  onResetOil();
                  setShowResetModal(false);
                }}
                className="px-4 py-1.5 rounded-lg bg-[#ff2a40] hover:bg-[#d61c2f] text-white text-xs font-bold font-['Chakra_Petch'] transition-colors"
              >
                Confirm Reset (100%)
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
