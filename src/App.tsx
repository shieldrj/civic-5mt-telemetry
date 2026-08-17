import { useState, useEffect, useRef, useCallback } from 'react';
import { Gauge, Fuel, Navigation, Droplet, ScanLine, Cpu, Maximize2, Minimize2 } from 'lucide-react';
import { OBDLiveMetrics, TripAnalytics, OilLifeProfile, ConnectionStatus } from './types/obd';
import { telemetryManager } from './services/telemetryManager';
import { ShiftLightBar } from './components/ShiftLightBar';
import { RadialGauge } from './components/RadialGauge';
import { MpgTelemetryCard } from './components/MpgTelemetryCard';
import { OilLifeCard } from './components/OilLifeCard';
import { TripSummaryBar } from './components/TripSummaryBar';
import { SimulatorControls } from './components/SimulatorControls';
import { BluetoothModal } from './components/BluetoothModal';
import { DtcScannerCard } from './components/DtcScannerCard';
import { PidDiscoveryCard } from './components/PidDiscoveryCard';

type TabId = 'cockpit' | 'fuel_physics' | 'trip' | 'oil_wear' | 'dtc' | 'bench';

const TABS: { id: TabId; label: string; icon: typeof Gauge }[] = [
  { id: 'cockpit', label: 'Drive', icon: Gauge },
  { id: 'fuel_physics', label: 'Fuel', icon: Fuel },
  { id: 'trip', label: 'Trip', icon: Navigation },
  { id: 'oil_wear', label: 'Oil', icon: Droplet },
  { id: 'dtc', label: 'Codes', icon: ScanLine },
  { id: 'bench', label: 'Sim', icon: Cpu },
];

const INK = '#eef0f2';
const INK_2 = '#9aa1a9';
const INK_3 = '#6b727a';
const WARN = '#c8952e';
const ALERT = '#d8453b';

/** The instant-MPG dial's scale. A 2013 Civic cruises in the low 40s and rarely holds
 *  above 55, so 60 puts normal driving across the readable middle of the sweep rather
 *  than bunched at one end. */
const MPG_SCALE_MAX = 60;

/**
 * A row of the cockpit's value list. Replaces the bordered, filled tiles - a hairline and
 * a baseline are enough to group these, and they were carrying five different accent
 * colours between them for values that were almost always normal.
 */
function StatRow({
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
    <div className="stat-row">
      <span className="t-key shrink-0">{label}</span>
      <span className="t-value text-right" style={{ color: tone }}>
        {value}
        {unit && (
          <span className="t-label ml-1" style={{ letterSpacing: '0.1em', color: INK_3 }}>
            {unit}
          </span>
        )}
        {note && (
          <span className="ml-2.5" style={{ fontSize: 11.5, color: INK_3, letterSpacing: 0 }}>
            {note}
          </span>
        )}
      </span>
    </div>
  );
}

export function App() {
  const [metrics, setMetrics] = useState<OBDLiveMetrics | null>(null);
  const [trip, setTrip] = useState<TripAnalytics | null>(null);
  const [oil, setOil] = useState<OilLifeProfile | null>(null);
  const [status, setStatus] = useState<ConnectionStatus>('simulating');
  const [shiftMode, setShiftMode] = useState<'eco' | 'power'>('eco');
  const [isBluetoothModalOpen, setIsBluetoothModalOpen] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [activeTab, setActiveTab] = useState<TabId>('cockpit');
  const [fuelBlend, setFuelBlend] = useState(() => telemetryManager.getFuelBlend());
  const [viewport, setViewport] = useState(() => ({
    width: typeof window !== 'undefined' ? window.innerWidth : 412,
    height: typeof window !== 'undefined' ? window.innerHeight : 900,
  }));
  const touchStart = useRef<{ x: number; y: number } | null>(null);
  const heroObserver = useRef<ResizeObserver | null>(null);
  const [heroBox, setHeroBox] = useState({ width: 0, height: 0 });

  useEffect(() => {
    const onResize = () => setViewport({ width: window.innerWidth, height: window.innerHeight });
    window.addEventListener('resize', onResize);
    window.addEventListener('orientationchange', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      window.removeEventListener('orientationchange', onResize);
    };
  }, []);

  // Measure the space the dial actually gets rather than estimating the chrome around it.
  // The hero is flex-1 with min-h-0 between two fixed-height siblings, so its height is
  // set by them and never by the gauge inside it - sizing the gauge from it cannot feed
  // back on itself.
  //
  // This has to be a callback ref, not an effect. Telemetry starts null, so the first
  // commit is always the loading screen and any effect firing then sees a null ref. An
  // effect keyed on the tab would not re-run when telemetry arrives, so the observer never
  // attached and the dial was stuck on the fallback estimate until you switched tabs.
  const heroRef = useCallback((node: HTMLElement | null) => {
    heroObserver.current?.disconnect();
    if (!node) {
      heroObserver.current = null;
      return;
    }
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      setHeroBox({ width, height });
    });
    observer.observe(node);
    heroObserver.current = observer;
  }, []);

  // Swipe left/right anywhere to move between tabs. Guarded on the horizontal delta
  // dominating, so it never hijacks a vertical scroll on the deeper tabs.
  const goToAdjacentTab = (direction: 1 | -1) => {
    const index = TABS.findIndex((t) => t.id === activeTab);
    const next = index + direction;
    if (next >= 0 && next < TABS.length) setActiveTab(TABS[next].id);
  };

  const handleTouchStart = (e: React.TouchEvent) => {
    const t = e.touches[0];
    touchStart.current = { x: t.clientX, y: t.clientY };
  };

  const handleTouchEnd = (e: React.TouchEvent) => {
    if (!touchStart.current) return;
    const t = e.changedTouches[0];
    const dx = t.clientX - touchStart.current.x;
    const dy = t.clientY - touchStart.current.y;
    touchStart.current = null;
    if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) {
      goToAdjacentTab(dx < 0 ? 1 : -1);
    }
  };

  useEffect(() => {
    const unsubscribe = telemetryManager.subscribe((m, t, o, s) => {
      setMetrics(m);
      setTrip(t);
      setOil(o);
      setStatus(s);
    });
    return () => unsubscribe();
  }, []);

  const toggleFullscreen = () => {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen().catch(() => {});
      setIsFullscreen(true);
    } else {
      document.exitFullscreen().catch(() => {});
      setIsFullscreen(false);
    }
  };

  const toggleShiftMode = () => {
    const nextMode = shiftMode === 'eco' ? 'power' : 'eco';
    setShiftMode(nextMode);
    telemetryManager.shiftMode = nextMode;
  };

  if (!metrics || !trip || !oil) {
    return (
      <div
        className="flex h-screen w-screen items-center justify-center"
        style={{ background: 'var(--ground)' }}
      >
        <span className="t-label">Connecting</span>
      </div>
    );
  }

  const isColdEngine = metrics.coolantTempF < 160;
  const isOverheating = metrics.coolantTempF > 220;
  const isBatteryLow = metrics.batteryVoltage < 12.0;
  const isBatteryHigh = metrics.batteryVoltage > 15.0;

  // A tenth of a mile of real driving is enough to stop showing a placeholder, but the
  // figure stays visibly provisional until there is a meaningful sample behind it.
  const hasLifetimeData = metrics.lifetimeMiles >= 0.1 && metrics.lifetimeMpg > 0;

  // A phone in landscape is too short to carry the value list and still leave room for a
  // readable dial, so it steps aside there - every figure stays on the other tabs.
  const isShortViewport = viewport.height < 500;

  const measuredHeight = heroBox.height || Math.max(160, viewport.height - 420);
  const measuredWidth = heroBox.width || Math.min(viewport.width, 1024) - 24;
  // Fill the width it is given. Capped at 380 rather than 340 because on a tall phone the
  // hero box is far taller than it is wide, so width is what binds - and a dial floating
  // in a column of empty space reads as unfinished rather than as restraint.
  const gaugeSize = Math.round(Math.max(140, Math.min(measuredWidth, measuredHeight, 380)));

  // The dial shows one of three things, and only one of them is a number. Standing still
  // instant MPG is zero because the car is not moving, and on a closed throttle it is the
  // 99.9 cap because the injectors are off; drawing either as a figure is what made the
  // old readout impossible to trust. See FuelModelEngine.updateDisplayMpg.
  const mpgState = metrics.mpgDisplayState;
  const mpgOverride =
    mpgState === 'idle' ? '—' : mpgState === 'coasting' ? '—' : undefined;
  const mpgArcValue =
    mpgState === 'idle' ? 0 : mpgState === 'coasting' ? MPG_SCALE_MAX : metrics.displayMpg;
  const mpgNote =
    mpgState === 'idle'
      ? 'Stopped'
      : mpgState === 'coasting'
      ? 'Coasting — no fuel'
      : `${metrics.rolling30sMpg.toFixed(1)} over 30s`;

  const statusLabel =
    status === 'connected'
      ? 'Live'
      : status === 'simulating'
      ? 'Simulated'
      : status === 'connecting'
      ? 'Connecting'
      : status === 'error'
      ? 'Error'
      : 'Not connected';
  const statusTone = status === 'error' ? ALERT : status === 'connected' ? INK : INK_3;

  return (
    <div
      className="h-screen w-full flex flex-col px-4 sm:px-6 max-w-3xl mx-auto gap-4 select-none overflow-hidden"
      style={{
        background: 'var(--ground)',
        color: INK,
        // Clears the punch-hole camera / status bar up top and the gesture-nav pill below,
        // on top of the app's own baseline padding.
        paddingTop: 'calc(0.875rem + env(safe-area-inset-top, 0px))',
        paddingBottom: 'calc(0.5rem + env(safe-area-inset-bottom, 0px))',
      }}
      onTouchStart={handleTouchStart}
      onTouchEnd={handleTouchEnd}
    >
      {/* Header. The old one was a bordered bar carrying a red icon tile, a model badge, an
          engine-code pill and a filled status capsule - five boxes for information that
          never changes. It is now two lines of text and a status word. */}
      <header className="flex items-start justify-between gap-3 shrink-0">
        <div className="min-w-0">
          <h1 style={{ fontSize: 17, fontWeight: 500, letterSpacing: '-0.015em', lineHeight: 1.1 }}>
            Civic
          </h1>
          <p style={{ fontSize: 11, color: INK_3, marginTop: 3, letterSpacing: '0.02em' }}>
            2013 LX &middot; 5-speed manual
          </p>
        </div>

        <div className="flex items-center gap-3 shrink-0">
          <button
            onClick={() => setIsBluetoothModalOpen(true)}
            className="flex items-center gap-2 transition-colors"
            style={{ fontSize: 11, color: statusTone, letterSpacing: '0.02em' }}
          >
            <span
              className="block rounded-full"
              style={{ width: 5, height: 5, background: statusTone }}
            />
            {statusLabel}
          </button>

          <button
            onClick={toggleFullscreen}
            style={{ color: INK_3 }}
            className="transition-colors"
            title="Toggle fullscreen"
            aria-label="Toggle fullscreen"
          >
            {isFullscreen ? <Minimize2 size={15} /> : <Maximize2 size={15} />}
          </button>
        </div>
      </header>

      {/* TAB 1: DRIVING. The dial is sized to the space it is given, so in normal use this
          never scrolls. It stays scrollable rather than clipped for the extremes (phone
          landscape) where the chrome alone leaves no room for a dial. */}
      {activeTab === 'cockpit' && (
        <main className="flex-1 min-h-0 flex flex-col gap-3 overflow-y-auto">
          <div className="shrink-0">
            <ShiftLightBar
              stage={metrics.shiftLightStage}
              rpm={metrics.rpm}
              shiftMode={shiftMode}
              shouldShiftUp={metrics.shouldShiftUp}
              onToggleMode={toggleShiftMode}
              currentGear={metrics.currentGear}
            />
          </div>

          {/* Hero: what you are getting right now, with what you have averaged since the
              app started tracking directly underneath it. The two figures answer the same
              question over two timescales, so they belong together and nothing should sit
              between them. */}
          <div
            ref={heroRef}
            className="flex-1 min-h-0 flex flex-col items-center justify-center gap-1"
          >
            <RadialGauge
              value={mpgArcValue}
              overrideValue={mpgOverride}
              min={0}
              max={MPG_SCALE_MAX}
              title="Instant"
              unit="mpg"
              subValue={mpgNote}
              ticks={[0, 30, 60]}
              size={gaugeSize}
              isHero
            />

            {/* Inside the hero group, not below it. The two figures answer the same
                question over two timescales; separated by the flex gap they read as two
                unrelated things with an accident of adjacency. */}
            <p
              className="text-center tabular-nums"
              style={{ fontSize: 12.5, color: INK_2, letterSpacing: '0.01em' }}
            >
              {hasLifetimeData ? (
                <>
                  Lifetime{' '}
                  <span style={{ color: INK, fontSize: 14 }}>{metrics.lifetimeMpg.toFixed(1)}</span>{' '}
                  mpg
                  <span style={{ color: INK_3 }}>
                    {' '}
                    &middot; {Math.round(metrics.lifetimeMiles).toLocaleString()} mi
                  </span>
                </>
              ) : (
                <span style={{ color: INK_3 }}>
                  Lifetime average starts once an adapter is connected
                </span>
              )}
            </p>
          </div>

          {/* The value list. Coolant leads it: it was the hero dial, which gave the largest
              readout on the screen to a number you look at twice a year. It matters when it
              is wrong, and a row that turns amber says that as well as a dial did. */}
          <div className={`shrink-0 ${isShortViewport ? 'hidden' : ''}`}>
            <StatRow
              label="Coolant"
              value={String(metrics.coolantTempF)}
              unit="°F"
              note={isColdEngine ? 'Warming up' : isOverheating ? 'Too hot' : `${metrics.coolantTempC}°C`}
              tone={isOverheating ? ALERT : isColdEngine ? WARN : INK}
            />
            <StatRow
              label="Oil life"
              value={String(Math.round(oil.oilLifePercent))}
              unit="%"
              note={`≈ ${oil.estimatedMilesRemaining.toLocaleString()} mi left`}
              tone={oil.oilLifePercent < 15 ? ALERT : oil.oilLifePercent < 40 ? WARN : INK}
            />
            <StatRow
              label="Battery"
              value={metrics.batteryVoltage.toFixed(1)}
              unit="V"
              tone={isBatteryLow || isBatteryHigh ? WARN : INK}
            />
            <StatRow
              label="Engine load"
              value={String(Math.round(metrics.engineLoadPercent))}
              unit="%"
            />
            <StatRow label="Outside" value={String(metrics.ambientAirTempF)} unit="°F" />

            {/* Fuel keeps a bar because it is the one value on this screen you read as a
                proportion rather than a figure - how much is left, not how many percent. */}
            <div className="stat-row flex-col items-stretch gap-2.5">
              <div className="flex items-baseline justify-between gap-3">
                <span className="t-key">Fuel</span>
                <span className="t-value tabular-nums">
                  {Math.round(metrics.fuelLevelPercent)}
                  <span className="t-label ml-1" style={{ letterSpacing: '0.1em', color: INK_3 }}>
                    %
                  </span>
                  <span className="ml-2.5" style={{ fontSize: 11.5, color: INK_3, letterSpacing: 0 }}>
                    {metrics.fuelRangeMiles} mi to empty
                  </span>
                </span>
              </div>
              <div className="meter">
                <i
                  style={{
                    width: `${Math.max(0, Math.min(100, metrics.fuelLevelPercent))}%`,
                    backgroundColor:
                      metrics.fuelLevelPercent < 15
                        ? ALERT
                        : metrics.fuelLevelPercent < 30
                        ? WARN
                        : INK_2,
                  }}
                />
              </div>
            </div>
          </div>
        </main>
      )}

      {activeTab === 'fuel_physics' && (
        <main className="flex-1 min-h-0 overflow-y-auto flex flex-col gap-3 pb-1">
          <MpgTelemetryCard
            metrics={metrics}
            trip={trip}
            activeBlend={fuelBlend}
            onSelectFuelBlend={(id) => {
              telemetryManager.setFuelBlend(id);
              setFuelBlend(telemetryManager.getFuelBlend());
            }}
          />
        </main>
      )}

      {activeTab === 'trip' && (
        <main className="flex-1 min-h-0 overflow-y-auto flex flex-col gap-3 pb-1">
          <TripSummaryBar
            trip={trip}
            onResetTrip={() => telemetryManager.resetTrip()}
            engineRuntimeSec={metrics.engineRuntimeSec}
          />
        </main>
      )}

      {activeTab === 'oil_wear' && (
        <main className="flex-1 min-h-0 overflow-y-auto flex flex-col gap-3 pb-1">
          <OilLifeCard
            oilProfile={oil}
            coolantTempF={metrics.coolantTempF}
            onResetOil={() => telemetryManager.resetOilLife()}
          />

          {/* Card-less, like everything above it. A single card appearing at the bottom of
              a hairline-separated page reads as a component someone forgot to convert. */}
          <section
            className="flex flex-col gap-3 pt-4"
            style={{ borderTop: '1px solid var(--hairline)' }}
          >
            <h3 style={{ fontSize: 14, fontWeight: 500, letterSpacing: '-0.01em' }}>
              How oil life is calculated
            </h3>
            <p style={{ fontSize: 13, color: INK_2, lineHeight: 1.6 }}>
              A dash odometer counts distance. This model reads four things off the engine that
              wear oil at very different rates, so a month of short cold trips costs more life
              than the same miles on a motorway.
            </p>
            <div className="flex flex-col gap-4 pt-1">
              {[
                {
                  h: 'Crankshaft revolutions',
                  p: 'Stop-and-go first and second gear shears oil roughly three times faster than fifth-gear cruising over the same distance.',
                },
                {
                  h: 'Cold starts',
                  p: 'Starting below 160°F draws moisture and unburned fuel into the crankcase until the engine warms through.',
                },
                {
                  h: 'Short trips',
                  p: 'A trip that ends under fifteen minutes never reaches 185°F, so the fuel that got in never boils back out.',
                },
                {
                  h: 'Thermal stress',
                  p: 'Sustained pulls above 4,500 rpm under load thin the oil film and break down viscosity.',
                },
              ].map((item) => (
                <div key={item.h} className="flex flex-col gap-1">
                  <strong style={{ fontSize: 12.5, fontWeight: 500, color: INK }}>{item.h}</strong>
                  <p style={{ fontSize: 12.5, color: INK_3, lineHeight: 1.6 }}>{item.p}</p>
                </div>
              ))}
            </div>
          </section>
        </main>
      )}

      {activeTab === 'dtc' && (
        <main className="flex-1 min-h-0 overflow-y-auto flex flex-col gap-3 pb-1">
          <DtcScannerCard />
          <PidDiscoveryCard />
        </main>
      )}

      {activeTab === 'bench' && (
        <main className="flex-1 min-h-0 overflow-y-auto flex flex-col gap-3 pb-1">
          <SimulatorControls
            scenario={telemetryManager.simulator.scenario}
            onSelectScenario={(sc) => telemetryManager.startSimulation(sc)}
          />
          <MpgTelemetryCard
            metrics={metrics}
            trip={trip}
            activeBlend={fuelBlend}
            onSelectFuelBlend={(id) => {
              telemetryManager.setFuelBlend(id);
              setFuelBlend(telemetryManager.getFuelBlend());
            }}
          />
          <TripSummaryBar
            trip={trip}
            onResetTrip={() => telemetryManager.resetTrip()}
            engineRuntimeSec={metrics.engineRuntimeSec}
          />
        </main>
      )}

      {/* Tab bar. Every button used to carry a border and the active one a solid red fill,
          which put the heaviest block of colour on the screen at the bottom edge where
          nothing is being read. Active is now white text and a coloured icon. */}
      <nav
        className="grid grid-cols-6 shrink-0 pt-3"
        style={{ borderTop: '1px solid var(--hairline)' }}
      >
        {TABS.map((tab) => {
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className="flex flex-col items-center justify-center gap-1.5 py-1 transition-colors"
              style={{ color: isActive ? INK : '#464c53' }}
              aria-current={isActive ? 'page' : undefined}
            >
              <tab.icon size={17} strokeWidth={1.5} color={isActive ? ALERT : '#464c53'} />
              <span style={{ fontSize: 9.5, fontWeight: 500, letterSpacing: '0.05em' }}>
                {tab.label}
              </span>
            </button>
          );
        })}
      </nav>

      <BluetoothModal
        isOpen={isBluetoothModalOpen}
        onClose={() => setIsBluetoothModalOpen(false)}
        status={status}
        statusMessage={telemetryManager.statusMessage}
        onConnect={(options) => telemetryManager.connectBluetooth(options)}
        checkEnvironment={() => telemetryManager.getBluetoothEnvironment()}
        getProtocolLog={() => telemetryManager.getProtocolLog()}
        setProtocolLogListener={(fn) => telemetryManager.setProtocolLogListener(fn)}
        onDisconnect={() => telemetryManager.disconnect()}
        onStartSim={() => telemetryManager.startSimulation()}
      />
    </div>
  );
}

export default App;
