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
  const radius = 38;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference - (percent / 100) * circumference;

  return (
    <div className="telemetry-card flex flex-col justify-between gap-3">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="p-1.5 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#ff2a40] border border-[rgba(255,255,255,0.08)]">
            <Droplet size={16} />
          </div>
          <div>
            <h3 className="text-xs font-bold text-[#f8fafc] font-['Chakra_Petch'] tracking-wide">
              OIL LIFE & WEAR MODEL
            </h3>
            <p className="text-[10px] text-[#64748b]">Revolutions + Cold Starts + Thermal Shear</p>
          </div>
        </div>

        <button
          onClick={() => setShowResetModal(true)}
          className="flex items-center gap-1 px-2 py-1 rounded-md bg-[rgba(255,255,255,0.05)] hover:bg-[rgba(255,255,255,0.1)] text-[#94a3b8] hover:text-[#f8fafc] border border-[rgba(255,255,255,0.08)] text-[10px] font-['Chakra_Petch'] transition-colors"
        >
          <RotateCcw size={11} />
          Reset
        </button>
      </div>

      {/* Main Health Split */}
      <div className="grid grid-cols-2 gap-3 items-center telemetry-card-subtle">
        {/* Left: Circular Health Ring */}
        <div className="relative flex items-center justify-center">
          <svg width={88} height={88} className="transform -rotate-90">
            <circle
              cx={44}
              cy={44}
              r={radius}
              stroke="#161a26"
              strokeWidth={6}
              fill="transparent"
            />
            <circle
              cx={44}
              cy={44}
              r={radius}
              stroke={statusColor}
              strokeWidth={6}
              fill="transparent"
              strokeDasharray={circumference}
              strokeDashoffset={strokeDashoffset}
              strokeLinecap="round"
              className="transition-all duration-300 ease-out"
            />
          </svg>
          <div className="absolute flex flex-col items-center justify-center text-center">
            <span
              className="text-xl font-black font-['Chakra_Petch'] tabular-nums"
              style={{ color: statusColor }}
            >
              {Math.round(percent)}%
            </span>
            <span className="text-[8px] uppercase font-bold text-[#64748b] font-['Chakra_Petch']">
              HEALTH
            </span>
          </div>
        </div>

        {/* Right: Projected Remaining */}
        <div className="flex flex-col gap-1">
          <div>
            <span className={`badge-pill ${badgeClass}`}>
              <ShieldCheck size={10} />
              {oilProfile.oilConditionGrade.toUpperCase()}
            </span>
          </div>
          <div className="flex flex-col mt-0.5">
            <span className="text-[9px] uppercase font-bold text-[#64748b] font-['Chakra_Petch']">
              EST. REMAINING
            </span>
            <span className="text-base font-bold text-[#f8fafc] font-['Chakra_Petch'] tabular-nums">
              ~{oilProfile.estimatedMilesRemaining.toLocaleString()} <span className="text-[10px] text-[#64748b]">mi</span>
            </span>
            <span className="text-[9px] text-[#94a3b8]">
              approx {oilProfile.estimatedDaysRemaining} days left
            </span>
          </div>
        </div>
      </div>

      {/* Secondary Metrics: Revolutions & Cold Starts */}
      <div className="grid grid-cols-2 gap-2 text-xs">
        <div className="bg-[#08090d] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col justify-between">
          <span className="text-[9px] font-bold text-[#64748b] font-['Chakra_Petch']">
            MECHANICAL REVS
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-sm font-bold text-[#f8fafc] font-['Chakra_Petch'] tabular-nums">
              {(oilProfile.accumulatedRevolutions / 1000000).toFixed(2)}M
            </span>
            <span className="text-[8px] text-[#64748b]">cycles</span>
          </div>
          <span className="text-[8px] text-[#64748b]">
            Rev wear: {oilProfile.degradationBreakdown.revWearFactor}%
          </span>
        </div>

        <div className="bg-[#08090d] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col justify-between">
          <div className="flex items-center justify-between text-[9px] font-bold text-[#64748b] font-['Chakra_Petch']">
            <span className="flex items-center gap-1">
              <ThermometerSnowflake size={10} className="text-[#00d2ff]" />
              COLD STARTS
            </span>
            <span className="text-[#00d2ff] font-['Chakra_Petch']">{oilProfile.coldStartsCount}</span>
          </div>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-sm font-bold text-[#f8fafc] font-['Chakra_Petch'] tabular-nums">
              {oilProfile.shortTripsCount}
            </span>
            <span className="text-[8px] text-[#64748b]">short trips</span>
          </div>
          <span className="text-[8px] text-[#64748b]">
            Dilution: +{oilProfile.degradationBreakdown.coldStartPenalty}%
          </span>
        </div>
      </div>

      {/* Wear Factor Decomposition Bar */}
      <div className="bg-[#08090d] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col gap-1 text-[9px] font-['Chakra_Petch']">
        <div className="flex justify-between text-[#64748b]">
          <span>WEAR FACTOR BREAKDOWN</span>
          <span>{oilProfile.highThermalStressSec}s high RPM</span>
        </div>
        <div className="w-full bg-[#161a26] h-1.5 rounded-full flex overflow-hidden">
          <div
            title="Mechanical Revs"
            className="bg-[#00e676] h-full"
            style={{ width: `${oilProfile.degradationBreakdown.revWearFactor}%` }}
          />
          <div
            title="Cold Starts"
            className="bg-[#00d2ff] h-full"
            style={{ width: `${oilProfile.degradationBreakdown.coldStartPenalty}%` }}
          />
          <div
            title="Short Trips"
            className="bg-[#ffaa00] h-full"
            style={{ width: `${oilProfile.degradationBreakdown.shortTripPenalty}%` }}
          />
          <div
            title="Thermal Shear"
            className="bg-[#ff2a40] h-full"
            style={{ width: `${oilProfile.degradationBreakdown.thermalShearPenalty}%` }}
          />
        </div>
      </div>

      {/* Reset Confirmation Modal */}
      {showResetModal && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-xs flex items-center justify-center p-4">
          <div className="bg-[#0e111a] border border-[rgba(255,255,255,0.12)] rounded-2xl max-w-sm w-full p-5 shadow-2xl flex flex-col gap-3.5">
            <div className="flex items-center gap-2 text-[#ffaa00]">
              <AlertTriangle size={20} />
              <h3 className="text-sm font-bold font-['Chakra_Petch'] text-[#f8fafc]">
                Reset Oil Life Tracker?
              </h3>
            </div>
            <p className="text-xs text-[#94a3b8] leading-relaxed">
              Reset the accumulated crank revolutions, cold start log, and degradation penalty to <strong>100% (7,500 miles)</strong> after completing an oil and filter change.
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
