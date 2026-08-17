import React from 'react';
import { CIVIC_2013_SPECS } from '../services/obd2/civicSpecs';

/*
 * The shift cue, as one line.
 *
 * This was sixteen discrete LEDs in four colours with a 0.12s strobe over the top at
 * redline. It was the loudest thing in the app, and it was also worse at its own job: a
 * shift light is read in peripheral vision, where a moving edge registers and a pattern of
 * coloured squares does not, and a 12Hz flash at the exact moment the driver's attention
 * is worth most makes the bar harder to read rather than easier.
 *
 * One continuous fill, one colour at a time, and a word when it is time to shift.
 */

interface ShiftLightBarProps {
  stage: number; // 0 to 5
  rpm: number;
  shiftMode: 'eco' | 'power';
  shouldShiftUp: boolean;
  onToggleMode: () => void;
  currentGear?: number | 'N' | 'CLUTCH';
}

const INK = '#eef0f2';
const WARN = '#c8952e';
const ALERT = '#d8453b';

export const ShiftLightBar: React.FC<ShiftLightBarProps> = ({
  stage,
  rpm,
  shiftMode,
  shouldShiftUp,
  onToggleMode,
  currentGear = 'N',
}) => {
  const ratio = Math.max(0, Math.min(1, rpm / CIVIC_2013_SPECS.revLimiterRpm));

  // Colour is state, not decoration: white while there is nothing to do, amber once the
  // shift point is reached, red at the limiter. Three states, not sixteen segments.
  const atLimiter = stage >= 5;
  const fillColor = atLimiter ? ALERT : shouldShiftUp ? WARN : INK;

  const gearLabel = currentGear === 'CLUTCH' ? 'Clutch' : currentGear === 'N' ? 'Neutral' : currentGear;

  return (
    <div className="w-full flex flex-col gap-2.5">
      <div className="flex items-baseline justify-between gap-3">
        {/* Gear, then the mode switch. Both plain text - the gear was a red-bordered pill
            and the mode a filled green capsule, which between them put two boxes and two
            colours around information that changes a few times a minute. */}
        <div className="flex items-baseline gap-3 min-w-0">
          <span className="t-value shrink-0">
            {gearLabel}
            {typeof currentGear === 'number' && (
              <span className="t-label ml-1.5" style={{ letterSpacing: '0.14em' }}>
                gear
              </span>
            )}
          </span>

          <button
            onClick={onToggleMode}
            className="t-label shrink-0 transition-colors"
            style={{ color: shiftMode === 'power' ? 'var(--accent)' : 'var(--ink-3)' }}
            aria-pressed={shiftMode === 'power'}
            title="Switch between economy and power shift points"
          >
            {shiftMode === 'eco' ? 'Eco shifts' : 'Power shifts'}
          </button>
        </div>

        <span className="t-value shrink-0">
          {rpm.toLocaleString('en-US')}
          <span className="t-label ml-1" style={{ letterSpacing: '0.14em' }}>
            rpm
          </span>
        </span>
      </div>

      <div className="meter">
        <i style={{ width: `${ratio * 100}%`, backgroundColor: fillColor, transition: 'width 90ms linear, background-color 220ms ease' }} />
      </div>

      {/* Reserved height, so the layout does not jump every time the cue appears. */}
      <div className="h-3">
        {shouldShiftUp && (
          <span
            className="t-label"
            style={{ color: atLimiter ? ALERT : WARN }}
          >
            {atLimiter ? 'Redline' : 'Shift up'}
          </span>
        )}
      </div>
    </div>
  );
};
