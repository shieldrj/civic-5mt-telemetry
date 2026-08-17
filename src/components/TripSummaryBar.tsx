import React from 'react';
import { Navigation, Clock, RotateCcw, Award, Timer } from 'lucide-react';
import { TripAnalytics } from '../types/obd';

interface TripSummaryBarProps {
  trip: TripAnalytics;
  onResetTrip: () => void;
  engineRuntimeSec?: number;
}

export const TripSummaryBar: React.FC<TripSummaryBarProps> = ({ trip, onResetTrip, engineRuntimeSec }) => {
  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    if (mins < 60) {
      return `${mins}m ${secs}s`;
    }
    const hrs = Math.floor(mins / 60);
    return `${hrs}h ${mins % 60}m`;
  };

  const ecoScore = trip.ecoScore;
  let ecoColor = 'text-[#eef0f2]';
  if (ecoScore < 60) ecoColor = 'text-[#c8952e]';
  if (ecoScore < 40) ecoColor = 'text-[#d8453b]';

  return (
    <div className="telemetry-card flex flex-col gap-2.5">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="p-1.5 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#eef0f2] border border-[rgba(255,255,255,0.08)]">
            <Navigation size={16} />
          </div>
          <div>
            <h3 className="text-xs font-bold text-[#eef0f2] tracking-wide">
              TRIP TELEMETRY & EFFICIENCY
            </h3>
            <p className="text-[10px] text-[#6b727a]">Drive Cycle Stats & Distance</p>
          </div>
        </div>

        <button
          onClick={onResetTrip}
          className="flex items-center gap-1 px-2 py-1 rounded-md bg-[rgba(255,255,255,0.05)] hover:bg-[rgba(255,255,255,0.1)] text-[#9aa1a9] hover:text-[#eef0f2] border border-[rgba(255,255,255,0.08)] text-[10px] transition-colors"
        >
          <RotateCcw size={11} />
          Reset Trip
        </button>
      </div>

      {/* Grid of 4 Clean Tiles */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
        {/* Average MPG */}
        <div className="telemetry-card-subtle flex flex-col">
          <span className="text-[9px] uppercase font-bold text-[#6b727a]">
            Trip Average
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-xl font-medium text-[#eef0f2] tabular-nums">
              {trip.avgMpg > 0 ? trip.avgMpg.toFixed(1) : '--'}
            </span>
            <span className="text-[9px] font-bold text-[#6b727a]">MPG</span>
          </div>
          <span className="text-[9px] text-[#6b727a]">
            {trip.totalFuelUsedGallons.toFixed(2)} gal used
          </span>
        </div>

        {/* Distance */}
        <div className="telemetry-card-subtle flex flex-col">
          <span className="text-[9px] uppercase font-bold text-[#6b727a]">
            Distance
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-xl font-medium text-[#eef0f2] tabular-nums">
              {trip.distanceMiles.toFixed(1)}
            </span>
            <span className="text-[9px] font-bold text-[#6b727a]">MI</span>
          </div>
          <span className="text-[9px] text-[#6b727a]">
            Avg {trip.avgSpeedMph.toFixed(0)} mph (Max {trip.maxSpeedMph.toFixed(0)})
          </span>
        </div>

        {/* Duration */}
        <div className="telemetry-card-subtle flex flex-col">
          <span className="text-[9px] uppercase font-bold text-[#6b727a] flex items-center gap-1">
            <Clock size={9} />
            Duration
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-xl font-medium text-[#eef0f2] tabular-nums">
              {formatTime(trip.tripDurationSec)}
            </span>
          </div>
          <span className="text-[9px] text-[#c8952e]">
            Idle: {formatTime(trip.idleTimeSec)}
          </span>
        </div>

        {/* Eco Score */}
        <div className="telemetry-card-subtle flex flex-col">
          <span className="text-[9px] uppercase font-bold text-[#6b727a] flex items-center gap-1">
            <Award size={9} className="text-[#eef0f2]" />
            Eco Score
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className={`text-xl font-medium tabular-nums ${ecoColor}`}>
              {ecoScore}
            </span>
            <span className="text-[9px] font-bold text-[#6b727a]">/100</span>
          </div>
          <span className="text-[9px] text-[#9aa1a9]">
            DFCO saved: +{(trip.coastingFuelSavedGallons * 1000).toFixed(0)} mL
          </span>
        </div>

        {/* Engine Runtime (ECU-reported, independent of the app's own trip timer) */}
        {engineRuntimeSec !== undefined && (
          <div className="telemetry-card-subtle flex flex-col">
            <span className="text-[9px] uppercase font-bold text-[#6b727a] flex items-center gap-1">
              <Timer size={9} />
              Engine Runtime
            </span>
            <div className="flex items-baseline gap-1 mt-0.5">
              <span className="text-xl font-medium text-[#eef0f2] tabular-nums">
                {formatTime(engineRuntimeSec)}
              </span>
            </div>
            <span className="text-[9px] text-[#6b727a]">
              Since last start (ECU PID 011F)
            </span>
          </div>
        )}
      </div>
    </div>
  );
};
