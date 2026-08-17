import React, { useState } from 'react';
import { OilLifeProfile } from '../types/obd';

/*
 * The oil tab. The health ring stays - a percentage of a fixed span is exactly what a
 * ring is for - but loses its 7px stroke and rounded cap for a hairline track and a thin
 * arc, matching RadialGauge. The four boxes underneath become rows.
 */

interface OilLifeCardProps {
  oilProfile: OilLifeProfile;
  coolantTempF?: number;
  onResetOil: () => void;
}

const INK = '#eef0f2';
const INK_2 = '#9aa1a9';
const INK_3 = '#6b727a';
const WARN = '#c8952e';
const ALERT = '#d8453b';

function Row({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <div className="stat-row">
      <span className="t-key shrink-0">{label}</span>
      <span className="t-value text-right tabular-nums">
        {value}
        {note && (
          <span className="ml-2.5" style={{ fontSize: 11.5, color: INK_3, letterSpacing: 0 }}>
            {note}
          </span>
        )}
      </span>
    </div>
  );
}

export const OilLifeCard: React.FC<OilLifeCardProps> = ({ oilProfile, onResetOil }) => {
  const [showResetModal, setShowResetModal] = useState(false);

  const percent = oilProfile.oilLifePercent;
  const tone = percent < 15 ? ALERT : percent < 40 ? WARN : INK;

  const ringSize = 132;
  const center = ringSize / 2;
  const radius = 58;
  const circumference = 2 * Math.PI * radius;
  const dashoffset = circumference - (percent / 100) * circumference;

  const { revWearFactor, coldStartPenalty, shortTripPenalty, thermalShearPenalty } =
    oilProfile.degradationBreakdown;

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-baseline justify-between gap-3">
        <div>
          <h3 style={{ fontSize: 15, fontWeight: 500, letterSpacing: '-0.01em', color: INK }}>
            Oil life
          </h3>
          <p style={{ fontSize: 11.5, color: INK_3, marginTop: 3 }}>
            {oilProfile.oilConditionGrade}
          </p>
        </div>
        <button
          onClick={() => setShowResetModal(true)}
          className="transition-colors shrink-0"
          style={{ fontSize: 12.5, color: INK_2 }}
        >
          Reset
        </button>
      </div>

      <div className="flex items-center justify-center gap-7 py-2">
        <div className="relative shrink-0" style={{ width: ringSize, height: ringSize }}>
          <svg width={ringSize} height={ringSize} className="-rotate-90">
            <circle
              cx={center}
              cy={center}
              r={radius}
              stroke="rgba(255,255,255,0.09)"
              strokeWidth={1.5}
              fill="none"
            />
            <circle
              cx={center}
              cy={center}
              r={radius}
              stroke={tone}
              strokeWidth={3}
              fill="none"
              strokeDasharray={circumference}
              strokeDashoffset={dashoffset}
              strokeLinecap="butt"
              style={{ transition: 'stroke-dashoffset 300ms ease, stroke 240ms ease' }}
            />
          </svg>
          <div className="absolute inset-0 flex items-center justify-center">
            <span className="t-hero tabular-nums" style={{ fontSize: 40, color: tone }}>
              {Math.round(percent)}
              <span className="t-unit" style={{ fontSize: 15 }}>
                %
              </span>
            </span>
          </div>
        </div>

        <div className="flex flex-col gap-1 min-w-0">
          <span className="t-label">Remaining</span>
          <span className="t-hero tabular-nums" style={{ fontSize: 26 }}>
            {oilProfile.estimatedMilesRemaining.toLocaleString()}
            <span className="t-unit" style={{ fontSize: 12, marginLeft: 4 }}>
              mi
            </span>
          </span>
          <span style={{ fontSize: 11.5, color: INK_3 }}>
            about {oilProfile.estimatedDaysRemaining} days
          </span>
        </div>
      </div>

      <div className="flex flex-col">
        <Row
          label="Crank revolutions"
          value={`${(oilProfile.accumulatedRevolutions / 1_000_000).toFixed(2)}M`}
          note={`${revWearFactor}% of wear`}
        />
        <Row
          label="Cold starts"
          value={String(oilProfile.coldStartsCount)}
          note={`+${coldStartPenalty}% dilution`}
        />
        <Row
          label="Short trips"
          value={String(oilProfile.shortTripsCount)}
          note={`+${shortTripPenalty}%`}
        />
        <Row
          label="High-rpm time"
          value={`${oilProfile.highThermalStressSec}s`}
          note={`+${thermalShearPenalty}%`}
        />
      </div>

      {/* Where the wear came from. The one place more than one value shares a bar, so the
          segments have to stay distinguishable - they run light to dark rather than
          through four hues, and the legend below names them in the same order. */}
      <div className="flex flex-col gap-2.5 pt-4" style={{ borderTop: '1px solid var(--hairline)' }}>
        <span className="t-label">What used it up</span>
        <div className="meter flex">
          <span style={{ width: `${revWearFactor}%`, background: INK }} />
          <span style={{ width: `${coldStartPenalty}%`, background: INK_2 }} />
          <span style={{ width: `${shortTripPenalty}%`, background: INK_3 }} />
          <span style={{ width: `${thermalShearPenalty}%`, background: WARN }} />
        </div>
        <div className="flex flex-wrap gap-x-4 gap-y-1" style={{ fontSize: 11, color: INK_3 }}>
          <span>Revolutions</span>
          <span>Cold starts</span>
          <span>Short trips</span>
          <span>Thermal</span>
        </div>
      </div>

      {showResetModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-5" style={{ background: 'rgba(0,0,0,0.8)' }}>
          <div
            className="w-full max-w-sm flex flex-col gap-4 p-6 rounded-2xl"
            style={{ background: 'var(--panel)', border: '1px solid var(--hairline-strong)' }}
          >
            <h3 style={{ fontSize: 15, fontWeight: 500, color: INK }}>Reset oil life?</h3>
            <p style={{ fontSize: 13, color: INK_2, lineHeight: 1.6 }}>
              Clears the accumulated crank revolutions, the cold-start log and the
              degradation penalty, and starts again at 100% — 7,500 miles. Do this after an
              oil and filter change, not before.
            </p>
            <div className="flex justify-end gap-5 pt-1">
              <button
                onClick={() => setShowResetModal(false)}
                style={{ fontSize: 13, color: INK_2 }}
              >
                Cancel
              </button>
              <button
                onClick={() => {
                  onResetOil();
                  setShowResetModal(false);
                }}
                style={{ fontSize: 13, color: ALERT, fontWeight: 500 }}
              >
                Reset to 100%
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
