import { useState, useEffect, useRef, useCallback } from 'react';
import {
  Bluetooth,
  Maximize2,
  Minimize2,
  Thermometer,
  Wind,
  Activity,
  Cpu,
  RefreshCw,
  Car,
  Gauge,
  ScanLine,
  Droplet,
  Fuel,
  Navigation,
  Layers,
  BatteryCharging,
  Sun
} from 'lucide-react';
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

type TabId = 'cockpit' | 'fuel_physics' | 'trip' | 'oil_wear' | 'dtc' | 'bench';

const TABS: { id: TabId; label: string; icon: typeof Gauge }[] = [
  { id: 'cockpit', label: 'Cockpit', icon: Gauge },
  { id: 'fuel_physics', label: 'Fuel', icon: Fuel },
  { id: 'trip', label: 'Trip', icon: Navigation },
  { id: 'oil_wear', label: 'Oil Life', icon: Droplet },
  { id: 'dtc', label: 'Codes', icon: ScanLine },
  { id: 'bench', label: 'Sim', icon: Cpu },
];

type VitalTone = 'red' | 'amber' | 'green' | 'cyan' | 'neutral';

const VITAL_TONES: Record<VitalTone, string> = {
  red: 'bg-[#ff2a40]/10 text-[#ff6b7b] border-[#ff2a40]/30',
  amber: 'bg-[#ffaa00]/10 text-[#ffc966] border-[#ffaa00]/30',
  green: 'bg-[#0e111a] text-[#f8fafc] border-[rgba(255,255,255,0.08)]',
  cyan: 'bg-[#00d2ff]/10 text-[#70e4ff] border-[#00d2ff]/30',
  neutral: 'bg-[#0e111a] text-[#f8fafc] border-[rgba(255,255,255,0.08)]',
};

const VITAL_ICON_TONES: Record<VitalTone, string> = {
  red: 'text-[#ff2a40]',
  amber: 'text-[#ffaa00]',
  green: 'text-[#00e676]',
  cyan: 'text-[#00d2ff]',
  neutral: 'text-[#64748b]',
};

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
  const cockpitObserver = useRef<ResizeObserver | null>(null);
  const [cockpitBox, setCockpitBox] = useState({ width: 0, height: 0 });

  // The cockpit is sized from the live viewport height so it always fits without
  // scrolling - on any phone, in either orientation, with or without browser chrome.
  useEffect(() => {
    const onResize = () =>
      setViewport({ width: window.innerWidth, height: window.innerHeight });
    window.addEventListener('resize', onResize);
    window.addEventListener('orientationchange', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      window.removeEventListener('orientationchange', onResize);
    };
  }, []);

  // Measure the space the cockpit actually gets rather than estimating the chrome around
  // it. The container is flex-1 with min-h-0, so its height is fixed by its siblings and
  // never by the gauges inside it - sizing the gauges from it cannot feed back on itself.
  //
  // This has to be a callback ref, not an effect. Telemetry starts as null, so the first
  // commit is always the loading screen and any effect firing then sees a null ref. An
  // effect keyed on the tab would not re-run when the telemetry arrives, so the observer
  // never attached and the gauges were stuck on the fallback estimate until you happened
  // to switch tabs. A callback ref runs when the node itself mounts, which is the event
  // that actually matters here.
  const cockpitRef = useCallback((node: HTMLElement | null) => {
    cockpitObserver.current?.disconnect();
    if (!node) {
      cockpitObserver.current = null;
      return;
    }
    const observer = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      setCockpitBox({ width, height });
    });
    observer.observe(node);
    cockpitObserver.current = observer;
  }, []);

  // Swipe left/right anywhere to move between tabs. Guarded on the horizontal
  // delta dominating, so it never hijacks a vertical scroll on the deeper tabs.
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
      <div className="flex h-screen w-screen items-center justify-center bg-[#08090d] text-[#f8fafc]">
        <div className="flex items-center gap-3">
          <RefreshCw className="animate-spin text-[#ff2a40]" size={24} />
          <span className="font-['Chakra_Petch'] text-sm font-bold tracking-wider">
            Initializing Civic 5MT Telemetry...
          </span>
        </div>
      </div>
    );
  }

  // Engine temperature status
  const isColdEngine = metrics.coolantTempF < 160;
  const isOverheating = metrics.coolantTempF > 220;

  // Charging system status (control module / battery voltage)
  const isBatteryLow = metrics.batteryVoltage < 12.0;
  const isBatteryHigh = metrics.batteryVoltage > 15.0;

  // A tenth of a mile of real driving is enough to stop showing a placeholder, but the
  // figure stays visibly provisional until there is a meaningful sample behind it.
  const hasLifetimeData = metrics.lifetimeMiles >= 0.1 && metrics.lifetimeMpg > 0;

  const vitals: { icon: typeof Thermometer; label: string; value: string; tone: VitalTone }[] = [
    {
      icon: Thermometer,
      label: 'COOLANT',
      value: `${metrics.coolantTempF}°`,
      tone: isColdEngine ? 'cyan' : isOverheating ? 'red' : 'green',
    },
    {
      icon: BatteryCharging,
      label: 'BATTERY',
      value: `${metrics.batteryVoltage.toFixed(1)}V`,
      tone: isBatteryLow ? 'red' : isBatteryHigh ? 'amber' : 'green',
    },
    { icon: Wind, label: 'INTAKE', value: `${metrics.intakeAirTempF}°`, tone: 'neutral' },
    { icon: Sun, label: 'OUTSIDE', value: `${metrics.ambientAirTempF}°`, tone: 'neutral' },
    {
      icon: Activity,
      label: 'LOAD',
      value: `${Math.round(metrics.engineLoadPercent)}%`,
      tone: 'amber',
    },
  ];

  // A phone in landscape is too short to carry the secondary strips and still leave room
  // for a readable dial, so they step aside there - the values stay on the other tabs.
  const isShortViewport = viewport.height < 500;

  // Space inside the cockpit, once the fuel bar, the gap and the card padding are taken.
  // Falls back to a viewport estimate for the first paint, before the observer reports.
  const measuredHeight = cockpitBox.height || Math.max(160, viewport.height - 400);
  const measuredWidth = cockpitBox.width || Math.min(viewport.width, 1024) - 20;
  const contentHeight = Math.max(120, measuredHeight - (isShortViewport ? 24 : 88));
  const contentWidth = Math.max(200, measuredWidth - 24);

  // At md and up the three dials sit in one row, so the hero only has to fit the height
  // once; stacked below that, the hero and a twin have to share it.
  const isRowLayout = viewport.width >= 768;
  const heroGaugeSize = Math.round(
    isRowLayout
      ? Math.min(contentWidth / 3 - 16, 344, contentHeight)
      : Math.min(contentWidth, 344, contentHeight * 0.62)
  );
  const twinGaugeSize = Math.round(
    isRowLayout
      ? heroGaugeSize * 0.74
      : Math.min((contentWidth - 12) / 2, 200, contentHeight * 0.33)
  );

  return (
    <div
      className="h-screen w-full bg-[#08090d] text-[#f8fafc] flex flex-col px-2.5 sm:px-4 max-w-5xl mx-auto gap-2 select-none overflow-hidden"
      onTouchStart={handleTouchStart}
      onTouchEnd={handleTouchEnd}
      style={{
        // Clears the punch-hole camera / status bar up top and the gesture-nav pill down
        // below on a phone this large, on top of the app's own baseline padding.
        paddingTop: 'calc(0.625rem + env(safe-area-inset-top, 0px))',
        paddingBottom: 'calc(0.625rem + env(safe-area-inset-bottom, 0px))',
      }}
    >
      {/* Sleek Minimal Header Bar */}
      <header className="flex items-center justify-between bg-[#0e111a] border border-[rgba(255,255,255,0.08)] rounded-xl px-3 py-2.5 shadow-sm shrink-0">
        {/* Left: Vehicle Badge */}
        <div className="flex items-center gap-2.5">
          <div className="w-9 h-9 rounded-lg bg-[rgba(255,42,64,0.12)] border border-[rgba(255,42,64,0.3)] flex items-center justify-center text-[#ff2a40] shrink-0">
            <Car size={18} />
          </div>
          <div>
            <div className="flex items-center gap-1.5">
              <h1 className="text-sm font-bold font-['Chakra_Petch'] tracking-wide text-[#f8fafc]">
                2013 CIVIC LX <span className="text-[#ff2a40]">5MT</span>
              </h1>
              <span className="badge-pill badge-red text-[10px] py-0 px-1.5">R18Z1</span>
            </div>
            <p className="text-[11px] text-[#64748b]">1.8L i-VTEC • OBDLink MX+</p>
          </div>
        </div>

        {/* Right: OBD Status & Fullscreen */}
        <div className="flex items-center gap-1.5 shrink-0">
          <button
            onClick={() => setIsBluetoothModalOpen(true)}
            className={`flex items-center gap-1.5 px-3 py-2 rounded-lg border text-[13px] font-bold font-['Chakra_Petch'] transition-all ${
              status === 'connected'
                ? 'bg-[#00e676]/15 text-[#5aff9f] border-[#00e676]/40'
                : status === 'simulating'
                ? 'bg-[#00d2ff]/15 text-[#70e4ff] border-[#00d2ff]/40'
                : 'bg-[#161a26] text-[#94a3b8] border-[rgba(255,255,255,0.08)]'
            }`}
          >
            {status === 'connected' ? (
              <>
                <Bluetooth size={15} className="text-[#00e676]" />
                LIVE
              </>
            ) : status === 'simulating' ? (
              <>
                <Cpu size={15} className="text-[#00d2ff]" />
                SIM
              </>
            ) : (
              <>
                <Bluetooth size={15} />
                CONNECT
              </>
            )}
          </button>

          <button
            onClick={toggleFullscreen}
            className="p-2 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#94a3b8] hover:text-[#f8fafc] border border-[rgba(255,255,255,0.08)] transition-colors"
            title="Toggle Fullscreen"
          >
            {isFullscreen ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
          </button>
        </div>
      </header>

      {/* Live Engine Vitals - every value stays visible at phone width.
          These used to be pills inside the header behind sm:/md: breakpoints, which
          never fire on a phone in portrait, so most of them simply never rendered. */}
      <div className={`grid grid-cols-5 gap-1.5 shrink-0 ${isShortViewport ? 'hidden' : ''}`}>
        {vitals.map((vital) => (
          <div
            key={vital.label}
            className={`flex flex-col items-center justify-center gap-0.5 py-2 rounded-lg border ${VITAL_TONES[vital.tone]}`}
          >
            <vital.icon size={14} className={VITAL_ICON_TONES[vital.tone]} />
            <span className="text-[15px] font-bold font-['Chakra_Petch'] tabular-nums leading-none">
              {vital.value}
            </span>
            <span className="text-[10px] font-semibold text-[#64748b] tracking-wide leading-none">
              {vital.label}
            </span>
          </div>
        ))}
      </div>

      {/* Formula 1 Shift Tachometer Ribbon with Active Gear */}
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

      {/* TAB 1: DRIVING COCKPIT - the gauges are sized to fit the screen exactly, so in
          normal use this never scrolls. It stays scrollable rather than clipped for the
          extremes (phone landscape) where the chrome alone leaves no room for a dial. */}
      {activeTab === 'cockpit' && (
        <main ref={cockpitRef} className="flex-1 min-h-0 flex flex-col gap-2 overflow-y-auto">
          <section className="bg-[#0e111a] border border-[rgba(255,255,255,0.08)] rounded-2xl p-3 sm:p-5 shadow-sm flex flex-col md:grid md:grid-cols-3 items-center justify-around gap-3 flex-1 min-h-0">
            {/* 1. Hero Coolant Temp Dial (Large & High-Contrast) */}
            <div className="order-1 md:order-2 flex justify-center">
              <RadialGauge
                value={metrics.coolantTempF}
                min={100}
                max={260}
                title="COOLANT TEMP"
                unit="°F"
                subValue={`${metrics.coolantTempC}°C • ${isColdEngine ? 'COLD' : isOverheating ? 'OVERHEAT' : 'OPTIMAL'}`}
                subLabel="Status"
                accentColor={isColdEngine ? '#00d2ff' : isOverheating ? '#ff2a40' : '#00e676'}
                redlineStart={225}
                ticks={[100, 140, 180, 220, 260]}
                size={heroGaugeSize}
                isHero={true}
              />
            </div>

            {/* 2. Twin Large Driving Dials: Real-Time Instant MPG & Oil Health */}
            <div className="order-2 md:contents w-full grid grid-cols-2 gap-3 sm:gap-6 justify-items-center items-center">
              {/* Lifetime MPG - real vehicle miles only, so it reads 0 until an adapter
                  has actually been connected. The sub-label carries the mileage behind
                  it, because an average over 4 miles and one over 4,000 are not the
                  same claim. */}
              <div className="flex justify-center">
                <RadialGauge
                  value={hasLifetimeData ? metrics.lifetimeMpg : 0}
                  min={0}
                  max={50}
                  title="LIFETIME MPG"
                  unit="MPG"
                  subValue={
                    hasLifetimeData
                      ? `${Math.round(metrics.lifetimeMiles).toLocaleString()} mi`
                      : 'Needs OBD'
                  }
                  subLabel={hasLifetimeData ? 'Tracked' : 'Status'}
                  accentColor={
                    !hasLifetimeData
                      ? '#475569'
                      : metrics.lifetimeMpg >= 32
                      ? '#00e676'
                      : metrics.lifetimeMpg >= 26
                      ? '#f8fafc'
                      : '#ffaa00'
                  }
                  ticks={[0, 10, 20, 30, 40, 50]}
                  size={twinGaugeSize}
                />
              </div>

              {/* Oil Health Remaining */}
              <div className="flex justify-center">
                <RadialGauge
                  value={oil.oilLifePercent}
                  min={0}
                  max={100}
                  title="OIL HEALTH"
                  unit="%"
                  subValue={`~${oil.estimatedMilesRemaining.toLocaleString()} mi`}
                  subLabel="Left"
                  accentColor={oil.oilLifePercent < 15 ? '#ff2a40' : oil.oilLifePercent < 40 ? '#ffaa00' : '#00e676'}
                  ticks={[0, 25, 50, 75, 100]}
                  size={twinGaugeSize}
                />
              </div>
            </div>
          </section>

          {/* Fuel Level & Range-to-Empty */}
          <section
            className={`telemetry-card-subtle items-center gap-3 shrink-0 ${
              isShortViewport ? 'hidden' : 'flex'
            }`}
          >
            <Fuel
              size={18}
              className={
                metrics.fuelLevelPercent < 15
                  ? 'text-[#ff2a40]'
                  : metrics.fuelLevelPercent < 30
                  ? 'text-[#ffaa00]'
                  : 'text-[#94a3b8]'
              }
            />
            <div className="flex-1">
              <div className="flex items-center justify-between text-[12px] font-bold font-['Chakra_Petch'] text-[#64748b] tracking-wider">
                <span>FUEL</span>
                <span className="text-[#f8fafc]">
                  {Math.round(metrics.fuelLevelPercent)}% &bull; ~{metrics.fuelRangeMiles} mi to empty
                </span>
              </div>
              <div className="w-full bg-[#161a26] h-2 rounded-full mt-1.5 overflow-hidden">
                <div
                  className={`h-full transition-all duration-500 ${
                    metrics.fuelLevelPercent < 15
                      ? 'bg-[#ff2a40]'
                      : metrics.fuelLevelPercent < 30
                      ? 'bg-[#ffaa00]'
                      : 'bg-[#00e676]'
                  }`}
                  style={{ width: `${Math.max(0, Math.min(100, metrics.fuelLevelPercent))}%` }}
                />
              </div>
            </div>
          </section>
        </main>
      )}

      {/* TAB 2: DEDICATED PHYSICS MPG & FUEL FLOW */}
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

      {/* TAB 3: DEDICATED TRIP TELEMETRY & ECO STATS */}
      {activeTab === 'trip' && (
        <main className="flex-1 min-h-0 overflow-y-auto flex flex-col gap-3 pb-1">
          <TripSummaryBar
            trip={trip}
            onResetTrip={() => telemetryManager.resetTrip()}
            engineRuntimeSec={metrics.engineRuntimeSec}
          />
        </main>
      )}

      {/* TAB 4: DEDICATED OIL LIFE & WEAR DIAGNOSTICS */}
      {activeTab === 'oil_wear' && (
        <main className="flex-1 min-h-0 overflow-y-auto flex flex-col gap-3 pb-1">
          <OilLifeCard
            oilProfile={oil}
            coolantTempF={metrics.coolantTempF}
            onResetOil={() => telemetryManager.resetOilLife()}
          />

          {/* Deep Explanation */}
          <section className="telemetry-card flex flex-col gap-2.5 text-xs text-[#94a3b8]">
            <h3 className="text-xs font-bold font-['Chakra_Petch'] text-[#f8fafc] flex items-center gap-1.5">
              <Layers size={14} className="text-[#ff2a40]" />
              How the Deep Oil Life Algorithm Works (2013 Civic LX R18Z1)
            </h3>
            <p className="text-[11px] leading-relaxed">
              Unlike generic dash odometers that only count distance, this custom model calculates oil additive depletion using real-time telemetry from your <strong>2013 Civic 5MT</strong>:
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-[11px]">
              <div className="telemetry-card-subtle flex flex-col gap-0.5">
                <strong className="text-[#00e676] font-['Chakra_Petch']">1. Mechanical Revolutions (Cycles)</strong>
                <p className="text-[#64748b]">Crankshaft revolution counter. Stop-and-go 1st/2nd gear city driving shears oil 3x faster than 5th gear cruising.</p>
              </div>
              <div className="telemetry-card-subtle flex flex-col gap-0.5">
                <strong className="text-[#00d2ff] font-['Chakra_Petch']">2. Cold-Start & Condensation</strong>
                <p className="text-[#64748b]">Starts below 160°F accumulate moisture and fuel blowby, increasing additive depletion until warmed up.</p>
              </div>
              <div className="telemetry-card-subtle flex flex-col gap-0.5">
                <strong className="text-[#ffaa00] font-['Chakra_Petch']">3. Short Trip Dilution</strong>
                <p className="text-[#64748b]">Trips ending under 15 minutes before reaching 185°F fail to vaporize unburned gasoline out of the crankcase.</p>
              </div>
              <div className="telemetry-card-subtle flex flex-col gap-0.5">
                <strong className="text-[#ff2a40] font-['Chakra_Petch']">4. High-RPM Thermal Stress</strong>
                <p className="text-[#64748b]">VTEC high-RPM pulls (&gt;4,500 RPM under load) calculate viscosity breakdown from elevated oil film heat.</p>
              </div>
            </div>
          </section>
        </main>
      )}

      {/* TAB 5: DIAGNOSTIC DTC SCANNER */}
      {activeTab === 'dtc' && (
        <main className="flex-1 min-h-0 overflow-y-auto flex flex-col gap-3 pb-1">
          <DtcScannerCard />
        </main>
      )}

      {/* TAB 6: ECU BENCH / SIMULATOR */}
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

      {/* Bottom Tab Bar - all six destinations in thumb reach, one tap each, and
          swipe left/right anywhere moves between them in this same order. */}
      <nav className="grid grid-cols-6 gap-1 shrink-0">
        {TABS.map((tab) => (
          <button
            key={tab.id}
            onClick={() => setActiveTab(tab.id)}
            className={`flex flex-col items-center justify-center gap-1 py-2 rounded-lg font-['Chakra_Petch'] font-bold transition-all border ${
              activeTab === tab.id
                ? 'bg-[#ff2a40] text-white border-[#ff4b5c]'
                : 'bg-[#0e111a] text-[#94a3b8] border-[rgba(255,255,255,0.08)]'
            }`}
          >
            <tab.icon size={17} />
            <span className="text-[10px] leading-none">{tab.label}</span>
          </button>
        ))}
      </nav>

      {/* Bluetooth Connection Modal */}
      <BluetoothModal
        isOpen={isBluetoothModalOpen}
        onClose={() => setIsBluetoothModalOpen(false)}
        status={status}
        statusMessage={telemetryManager.statusMessage}
        onConnect={(options) => telemetryManager.connectBluetooth(options)}
        checkEnvironment={() => telemetryManager.getBluetoothEnvironment()}
        onDisconnect={() => telemetryManager.disconnect()}
        onStartSim={() => telemetryManager.startSimulation()}
      />
    </div>
  );
}

export default App;
