import React from 'react';
import { OBDLiveMetrics, TripAnalytics } from '../types/obd';
import { FUEL_BLENDS, FuelBlendId, FuelBlendProperties } from '../services/obd2/civicSpecs';

/*
 * The fuel tab, restructured to match the cockpit.
 *
 * It was five nested boxes deep in places, every heading was shouted in caps, and the
 * selected fuel blend was a solid red fill - the loudest block of colour in the app, on a
 * setting you change roughly once a year. The figures underneath were always good; they
 * were just buried.
 */

interface MpgTelemetryCardProps {
  metrics: OBDLiveMetrics;
  trip: TripAnalytics;
  activeBlend: FuelBlendProperties;
  onSelectFuelBlend: (id: FuelBlendId) => void;
}

const INK = '#eef0f2';
const INK_2 = '#9aa1a9';
const INK_3 = '#6b727a';
const WARN = '#c8952e';

/** A section heading. Sentence case, hairline above, nothing else. */
function Section({ title, aside, children }: { title: string; aside?: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-3 pt-4" style={{ borderTop: '1px solid var(--hairline)' }}>
      <div className="flex items-baseline justify-between gap-3">
        <h3 style={{ fontSize: 14, fontWeight: 500, letterSpacing: '-0.01em', color: INK }}>
          {title}
        </h3>
        {aside && (
          <span className="tabular-nums" style={{ fontSize: 11.5, color: INK_3 }}>
            {aside}
          </span>
        )}
      </div>
      {children}
    </section>
  );
}

function Row({ label, value, note }: { label: string; value: React.ReactNode; note?: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <span className="t-key">{label}</span>
      <span className="t-value" style={{ color: INK }}>
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

export const MpgTelemetryCard: React.FC<MpgTelemetryCardProps> = ({
  metrics,
  trip,
  activeBlend,
  onSelectFuelBlend,
}) => {
  const isDfco = metrics.isDfcoActive;

  // AFR judged against the blend rather than a fixed 14.7. Stoichiometry moves with the
  // fuel, so comparing an E10 mixture to gasoline's ratio reads every normal cruise as rich.
  const afr = metrics.airFuelRatio;
  const lambda = afr / activeBlend.stoichAfr;
  const afrStatus = lambda < 0.97 ? 'Rich' : lambda > 1.03 ? 'Lean' : 'Stoichiometric';

  const idleMinutes = Math.floor(trip.idleTimeSec / 60);
  const idleSeconds = Math.round(trip.idleTimeSec % 60);

  return (
    <div className="flex flex-col gap-5">
      {/* The two live figures, given the room they deserve. These were a two-column grid
          inside a filled sub-card inside the main card. */}
      <div className="grid grid-cols-2 gap-4">
        <div className="flex flex-col gap-1">
          <span className="t-label">Instant</span>
          <div className="flex items-baseline gap-1.5">
            <span
              className="t-hero tabular-nums"
              style={{ fontSize: 40, color: isDfco ? INK_2 : INK }}
            >
              {isDfco ? '—' : metrics.displayMpg.toFixed(1)}
            </span>
            {!isDfco && (
              <span className="t-hero t-unit" style={{ fontSize: 13 }}>
                mpg
              </span>
            )}
          </div>
          <span style={{ fontSize: 11.5, color: INK_3 }}>
            {isDfco ? 'Coasting — no fuel' : `${metrics.rolling30sMpg.toFixed(1)} over 30s`}
          </span>
        </div>

        <div className="flex flex-col gap-1">
          <span className="t-label">Burn rate</span>
          <div className="flex items-baseline gap-1.5">
            <span className="t-hero tabular-nums" style={{ fontSize: 40 }}>
              {metrics.fuelFlowGalPerHour.toFixed(2)}
            </span>
            <span className="t-hero t-unit" style={{ fontSize: 13 }}>
              gal/hr
            </span>
          </div>
          <span className="tabular-nums" style={{ fontSize: 11.5, color: INK_3 }}>
            {metrics.fuelFlowLitersPerHour.toFixed(2)} L/hr
          </span>
        </div>
      </div>

      <Section title="Combustion" aside={isDfco ? 'Fuel cut' : 'Closed loop'}>
        <Row
          label="Air to fuel"
          value={
            <span className="tabular-nums">
              {afr.toFixed(2)}
              <span className="t-label ml-1" style={{ letterSpacing: '0.1em' }}>
                : 1
              </span>
            </span>
          }
          note={`${afrStatus} · λ ${metrics.equivalenceRatio.toFixed(3)}`}
        />
        {/* Where this mixture sits between 10:1 and 20:1. A hairline marks the blend's
            own stoichiometric point, so "rich" and "lean" are read off the scale rather
            than from a colour. */}
        <div className="meter relative">
          <i
            style={{
              width: `${Math.min(100, Math.max(0, ((afr - 10) / 10) * 100))}%`,
              backgroundColor: lambda < 0.97 || lambda > 1.03 ? WARN : INK,
            }}
          />
          <span
            className="absolute inset-y-0 w-px"
            style={{
              left: `${((activeBlend.stoichAfr - 10) / 10) * 100}%`,
              background: 'rgba(255,255,255,0.45)',
            }}
          />
        </div>

        <Row
          label="ECU fuel trims"
          value={
            <span className="tabular-nums" style={{ fontSize: 13, color: INK_2 }}>
              Short {metrics.shortTermFuelTrim > 0 ? '+' : ''}
              {metrics.shortTermFuelTrim}% &nbsp; Long {metrics.longTermFuelTrim > 0 ? '+' : ''}
              {metrics.longTermFuelTrim}%
            </span>
          }
        />
      </Section>

      <Section title="Idling" aside={`${idleMinutes}m ${idleSeconds}s this trip`}>
        <Row
          label="Fuel burned at a standstill"
          value={
            <span className="tabular-nums">
              {(trip.idleFuelGallons * 1000).toFixed(0)}
              <span className="t-label ml-1" style={{ letterSpacing: '0.1em' }}>
                mL
              </span>
            </span>
          }
          note={`$${trip.idleCostDollars.toFixed(2)}`}
        />
      </Section>

      <Section
        title="Fuel in the tank"
        aside={`${activeBlend.stoichAfr.toFixed(2)}:1 · ${activeBlend.densityGramsPerLiter.toFixed(0)} g/L`}
      >
        <p style={{ fontSize: 12.5, color: INK_3, lineHeight: 1.55 }}>
          Sets the stoichiometric ratio and density behind every fuel figure on this screen.
        </p>
        {/* Selected is a hairline underline and white text, not a filled block. */}
        <div className="grid grid-cols-3 gap-6 pt-1">
          {(Object.keys(FUEL_BLENDS) as FuelBlendId[]).map((id) => {
            const isActive = activeBlend.id === id;
            return (
              <button
                key={id}
                onClick={() => onSelectFuelBlend(id)}
                className="pb-2 transition-colors"
                style={{
                  fontSize: 13,
                  fontWeight: 400,
                  letterSpacing: '0.02em',
                  color: isActive ? INK : INK_3,
                  borderBottom: `1px solid ${isActive ? 'var(--accent)' : 'var(--hairline)'}`,
                }}
                aria-pressed={isActive}
              >
                {id}
              </button>
            );
          })}
        </div>
      </Section>

      <Section title="Oxygen sensors">
        <div className="flex flex-col gap-4">
          {[
            { label: 'Pre-catalyst', volts: metrics.o2Sensor1Voltage },
            { label: 'Post-catalyst', volts: metrics.o2Sensor2Voltage },
          ].map((sensor) => (
            <div key={sensor.label} className="flex flex-col gap-2">
              <div className="flex items-baseline justify-between gap-3">
                <span className="t-key">{sensor.label}</span>
                <span className="t-value tabular-nums">
                  {sensor.volts.toFixed(2)}
                  <span className="t-label ml-1" style={{ letterSpacing: '0.1em' }}>
                    V
                  </span>
                  <span className="ml-2.5" style={{ fontSize: 11.5, color: INK_3, letterSpacing: 0 }}>
                    {sensor.volts >= 0.55 ? 'Rich' : sensor.volts <= 0.35 ? 'Lean' : 'Switching'}
                  </span>
                </span>
              </div>
              {/* Scaled 0-1.0V: a narrowband sensor only swings ~0.1-0.9V of the PID's
                  1.275V full scale, so scaling to full scale would flatten the trace.
                  The hairline is the 0.45V stoichiometric switch point. */}
              <div className="meter relative">
                <i
                  style={{
                    width: `${Math.max(0, Math.min(100, sensor.volts * 100))}%`,
                    transition: 'width 150ms linear',
                  }}
                />
                <span
                  className="absolute inset-y-0 left-[45%] w-px"
                  style={{ background: 'rgba(255,255,255,0.45)' }}
                />
              </div>
            </div>
          ))}
        </div>
        <p style={{ fontSize: 12.5, color: INK_3, lineHeight: 1.6 }}>
          A healthy pre-catalyst sensor swings actively across the 0.45 V line while the
          post-catalyst one stays comparatively steady. A post-catalyst trace that starts
          mirroring the pre-catalyst swing is the live signature behind code P0420.
        </p>
      </Section>
    </div>
  );
};
