import React from 'react';
import { Navigation, Clock, RotateCcw, Award } from 'lucide-react';
import { TripAnalytics } from '../types/obd';

interface TripSummaryBarProps {
  trip: TripAnalytics;
  onResetTrip: () => void;
}

export const TripSummaryBar: React.FC<TripSummaryBarProps> = ({ trip, onResetTrip }) => {
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
  let ecoColor = 'text-[#00e676]';
  if (ecoScore < 60) ecoColor = 'text-[#ffaa00]';
  if (ecoScore < 40) ecoColor = 'text-[#ff2a40]';

  return (
    <div className="telemetry-card flex flex-col gap-3">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <div className="p-1.5 rounded-lg bg-[#00e676]/15 text-[#00e676] border border-[#00e676]/30">
            <Navigation size={18} />
          </div>
          <div>
            <h3 className="text-sm font-bold text-[#f8fafc] font-['Chakra_Petch'] tracking-wide">
              TRIP TELEMETRY & ECO SCORE
            </h3>
            <p className="text-[10px] text-[#64748b] font-medium">Commute Efficiency & Distance</p>
          </div>
        </div>

        <button
          onClick={onResetTrip}
          className="flex items-center gap-1 px-2.5 py-1 rounded-lg bg-[#161a26] hover:bg-[#1f2638] text-[#94a3b8] hover:text-[#f8fafc] border border-[#252b3d] text-[11px] font-['Chakra_Petch'] transition-colors"
        >
          <RotateCcw size={12} />
          Reset Trip
        </button>
      </div>

      {/* Grid of Trip Metrics */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
        {/* Average MPG */}
        <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3 flex flex-col">
          <span className="text-[10px] uppercase font-bold text-[#64748b] font-['Chakra_Petch']">
            Trip Average
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-2xl font-extrabold text-[#00e676] font-['Chakra_Petch']">
              {trip.avgMpg > 0 ? trip.avgMpg.toFixed(1) : '--'}
            </span>
            <span className="text-xs font-bold text-[#64748b] font-['Chakra_Petch']">MPG</span>
          </div>
          <span className="text-[9px] text-[#475569]">
            {trip.totalFuelUsedGallons.toFixed(2)} gal used
          </span>
        </div>

        {/* Distance */}
        <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3 flex flex-col">
          <span className="text-[10px] uppercase font-bold text-[#64748b] font-['Chakra_Petch']">
            Trip Distance
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-2xl font-extrabold text-[#f8fafc] font-['Chakra_Petch']">
              {trip.distanceMiles.toFixed(1)}
            </span>
            <span className="text-xs font-bold text-[#64748b] font-['Chakra_Petch']">MI</span>
          </div>
          <span className="text-[9px] text-[#475569]">
            Avg: {trip.avgSpeedMph.toFixed(0)} mph (Max: {trip.maxSpeedMph.toFixed(0)})
          </span>
        </div>

        {/* Trip Time & Idle */}
        <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3 flex flex-col">
          <span className="text-[10px] uppercase font-bold text-[#64748b] font-['Chakra_Petch'] flex items-center gap-1">
            <Clock size={10} />
            Duration
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className="text-2xl font-extrabold text-[#f8fafc] font-['Chakra_Petch']">
              {formatTime(trip.tripDurationSec)}
            </span>
          </div>
          <span className="text-[9px] text-[#ffaa00]">
            Idle: {formatTime(trip.idleTimeSec)}
          </span>
        </div>

        {/* Eco Score */}
        <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3 flex flex-col">
          <span className="text-[10px] uppercase font-bold text-[#64748b] font-['Chakra_Petch'] flex items-center gap-1">
            <Award size={10} className="text-[#00e676]" />
            Eco Score
          </span>
          <div className="flex items-baseline gap-1 mt-0.5">
            <span className={`text-2xl font-extrabold font-['Chakra_Petch'] ${ecoColor}`}>
              {ecoScore}
            </span>
            <span className="text-xs font-bold text-[#64748b] font-['Chakra_Petch']">/ 100</span>
          </div>
          <span className="text-[9px] text-[#00d2ff]">
            DFCO saved: +{(trip.coastingFuelSavedGallons * 1000).toFixed(0)} mL
          </span>
        </div>
      </div>
    </div>
  );
};
