import React from 'react';
import { TripAnalytics } from '../types/obd';

/*
 * The trip tab. Five filled tiles in a grid became a list, because these are five
 * unrelated figures rather than a set to compare - a grid implies a relationship between
 * neighbours that does not exist between "distance" and "eco score".
 */

interface TripSummaryBarProps {
  trip: TripAnalytics;
  onResetTrip: () => void;
  engineRuntimeSec?: number;
}

const INK = '#eef0f2';
const INK_2 = '#9aa1a9';
const INK_3 = '#6b727a';
const WARN = '#c8952e';
const ALERT = '#d8453b';

function Figure({
  label,
  value,
  unit,
  note,
  tone = INK,
}: {
  label: string;
  value: string;
  unit?: string;
  note?: string;
  tone?: string;
}) {
  return (
    <div className="flex flex-col gap-1.5 py-4" style={{ borderTop: '1px solid var(--hairline)' }}>
      <span className="t-label">{label}</span>
      <div className="flex items-baseline gap-1.5">
        <span className="t-hero tabular-nums" style={{ fontSize: 30, color: tone }}>
          {value}
        </span>
        {unit && (
          <span className="t-hero t-unit" style={{ fontSize: 12 }}>
            {unit}
          </span>
        )}
      </div>
      {note && <span style={{ fontSize: 11.5, color: INK_3 }}>{note}</span>}
    </div>
  );
}

export const TripSummaryBar: React.FC<TripSummaryBarProps> = ({
  trip,
  onResetTrip,
  engineRuntimeSec,
}) => {
  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    if (mins < 60) return `${mins}m ${secs}s`;
    const hrs = Math.floor(mins / 60);
    return `${hrs}h ${mins % 60}m`;
  };

  const ecoTone = trip.ecoScore < 40 ? ALERT : trip.ecoScore < 60 ? WARN : INK;

  return (
    <div className="flex flex-col">
      <div className="flex items-baseline justify-between gap-3 pb-1">
        <div>
          <h3 style={{ fontSize: 15, fontWeight: 500, letterSpacing: '-0.01em', color: INK }}>
            This trip
          </h3>
          <p style={{ fontSize: 11.5, color: INK_3, marginTop: 3 }}>
            Since the last reset
          </p>
        </div>
        {/* A text button. It was a bordered, filled capsule competing with the figures. */}
        <button
          onClick={onResetTrip}
          className="transition-colors shrink-0"
          style={{ fontSize: 12.5, color: INK_2 }}
        >
          Reset
        </button>
      </div>

      {/* An em dash at 30px and weight 250 renders as a horizontal rule, not as a value -
          it read as a stray divider above the note. Toned down so it reads as "nothing
          yet" rather than as part of the furniture. */}
      <Figure
        label="Average"
        value={trip.avgMpg > 0 ? trip.avgMpg.toFixed(1) : '—'}
        unit={trip.avgMpg > 0 ? 'mpg' : undefined}
        tone={trip.avgMpg > 0 ? INK : INK_3}
        note={`${trip.totalFuelUsedGallons.toFixed(2)} gal used`}
      />
      <Figure
        label="Distance"
        value={trip.distanceMiles.toFixed(1)}
        unit="mi"
        note={`${trip.avgSpeedMph.toFixed(0)} mph average, ${trip.maxSpeedMph.toFixed(0)} peak`}
      />
      <Figure
        label="Duration"
        value={formatTime(trip.tripDurationSec)}
        note={`${formatTime(trip.idleTimeSec)} of it standing still`}
      />
      <Figure
        label="Eco score"
        value={String(trip.ecoScore)}
        unit="/ 100"
        tone={ecoTone}
        note={`Coasting saved ${(trip.coastingFuelSavedGallons * 1000).toFixed(0)} mL`}
      />
      {engineRuntimeSec !== undefined && (
        <Figure
          label="Engine runtime"
          value={formatTime(engineRuntimeSec)}
          note="Since last start, reported by the ECU"
        />
      )}
    </div>
  );
};
