import { useState, useEffect } from 'react';
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
  Layers
} from 'lucide-react';
import { OBDLiveMetrics, TripAnalytics, OilLifeProfile, ConnectionStatus } from './types/obd';
import { telemetryManager } from './services/telemetryManager';
import { ShiftLightBar } from './components/ShiftLightBar';
import { RadialGauge } from './components/RadialGauge';
import { MpgTelemetryCard } from './components/MpgTelemetryCard';
import { OilLifeCard } from './components/OilLifeCard';
import { ManualTransmissionCard } from './components/ManualTransmissionCard';
import { TripSummaryBar } from './components/TripSummaryBar';
import { SimulatorControls } from './components/SimulatorControls';
import { BluetoothModal } from './components/BluetoothModal';
import { DtcScannerCard } from './components/DtcScannerCard';

export function App() {
  const [metrics, setMetrics] = useState<OBDLiveMetrics | null>(null);
  const [trip, setTrip] = useState<TripAnalytics | null>(null);
  const [oil, setOil] = useState<OilLifeProfile | null>(null);
  const [status, setStatus] = useState<ConnectionStatus>('simulating');
  const [shiftMode, setShiftMode] = useState<'eco' | 'power'>('eco');
  const [isBluetoothModalOpen, setIsBluetoothModalOpen] = useState(false);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [activeTab, setActiveTab] = useState<'cockpit' | 'dtc' | 'oil_wear' | 'bench'>('cockpit');

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

  return (
    <div className="min-h-screen w-full bg-[#08090d] text-[#f8fafc] flex flex-col p-2.5 sm:p-4 max-w-6xl mx-auto gap-3 select-none">
      {/* Sleek Minimal Header */}
      <header className="flex items-center justify-between bg-[#0e111a] border border-[rgba(255,255,255,0.08)] rounded-xl px-3 py-2 shadow-sm">
        {/* Left: Vehicle Badge */}
        <div className="flex items-center gap-2.5">
          <div className="w-7 h-7 rounded-lg bg-[rgba(255,42,64,0.12)] border border-[rgba(255,42,64,0.3)] flex items-center justify-center text-[#ff2a40]">
            <Car size={15} />
          </div>
          <div>
            <div className="flex items-center gap-1.5">
              <h1 className="text-xs sm:text-sm font-bold font-['Chakra_Petch'] tracking-wide text-[#f8fafc]">
                2013 CIVIC LX <span className="text-[#ff2a40]">5MT</span>
              </h1>
              <span className="badge-pill badge-red text-[8px] py-0 px-1">R18Z1</span>
            </div>
            <p className="text-[9px] text-[#64748b] hidden sm:block">1.8L i-VTEC • OBDLink MX+ / vLinker</p>
          </div>
        </div>

        {/* Center: Live Engine Vitals */}
        <div className="flex items-center gap-2 font-['Chakra_Petch'] text-xs">
          {/* Coolant chip */}
          <div
            className={`flex items-center gap-1 px-2 py-0.5 rounded-md border text-[11px] ${
              isColdEngine
                ? 'bg-[#00d2ff]/10 text-[#70e4ff] border-[#00d2ff]/30'
                : isOverheating
                ? 'bg-[#ff2a40]/10 text-[#ff6b7b] border-[#ff2a40]/30'
                : 'bg-[#08090d] text-[#94a3b8] border-[rgba(255,255,255,0.06)]'
            }`}
          >
            <Thermometer size={12} className={isColdEngine ? 'text-[#00d2ff]' : isOverheating ? 'text-[#ff2a40]' : 'text-[#00e676]'} />
            <span className="font-bold tabular-nums">{metrics.coolantTempF}°F</span>
            {isColdEngine && <span className="text-[8px] text-[#00d2ff] font-semibold hidden sm:inline">COLD</span>}
          </div>

          {/* IAT & Load for tablet/desktop */}
          <div className="hidden md:flex items-center gap-1 px-2 py-0.5 rounded-md bg-[#08090d] text-[#94a3b8] border border-[rgba(255,255,255,0.06)] text-[11px]">
            <Wind size={11} className="text-[#64748b]" />
            <span className="tabular-nums">IAT {metrics.intakeAirTempF}°F</span>
          </div>

          <div className="hidden sm:flex items-center gap-1 px-2 py-0.5 rounded-md bg-[#08090d] text-[#94a3b8] border border-[rgba(255,255,255,0.06)] text-[11px]">
            <Activity size={11} className="text-[#ffaa00]" />
            <span className="tabular-nums">LOAD {Math.round(metrics.engineLoadPercent)}%</span>
          </div>
        </div>

        {/* Right: OBD Status Pill & Fullscreen */}
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => setIsBluetoothModalOpen(true)}
            className={`flex items-center gap-1 px-2.5 py-1 rounded-lg border text-xs font-bold font-['Chakra_Petch'] transition-all ${
              status === 'connected'
                ? 'bg-[#00e676]/15 text-[#5aff9f] border-[#00e676]/40'
                : status === 'simulating'
                ? 'bg-[#00d2ff]/15 text-[#70e4ff] border-[#00d2ff]/40'
                : 'bg-[#161a26] text-[#94a3b8] border-[rgba(255,255,255,0.08)]'
            }`}
          >
            {status === 'connected' ? (
              <>
                <Bluetooth size={13} className="text-[#00e676]" />
                <span className="hidden sm:inline">OBD LIVE</span>
              </>
            ) : status === 'simulating' ? (
              <>
                <Cpu size={13} className="text-[#00d2ff]" />
                <span className="hidden sm:inline">SIMULATOR</span>
              </>
            ) : (
              <>
                <Bluetooth size={13} />
                <span>CONNECT</span>
              </>
            )}
          </button>

          <button
            onClick={toggleFullscreen}
            className="p-1.5 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#94a3b8] hover:text-[#f8fafc] border border-[rgba(255,255,255,0.08)] transition-colors"
            title="Toggle Fullscreen"
          >
            {isFullscreen ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
          </button>
        </div>
      </header>

      {/* Formula 1 / GT3 Shift Light Ribbon */}
      <ShiftLightBar
        stage={metrics.shiftLightStage}
        rpm={metrics.rpm}
        shiftMode={shiftMode}
        shouldShiftUp={metrics.shouldShiftUp}
        onToggleMode={toggleShiftMode}
      />

      {/* Minimalist Segmented Navigation Tabs */}
      <nav className="flex items-center gap-1.5 overflow-x-auto pb-0.5">
        <button
          onClick={() => setActiveTab('cockpit')}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold font-['Chakra_Petch'] whitespace-nowrap transition-all ${
            activeTab === 'cockpit'
              ? 'bg-[#ff2a40] text-white shadow-xs'
              : 'text-[#94a3b8] hover:text-[#f8fafc] hover:bg-[rgba(255,255,255,0.05)]'
          }`}
        >
          <Gauge size={13} />
          Cockpit
        </button>
        <button
          onClick={() => setActiveTab('dtc')}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold font-['Chakra_Petch'] whitespace-nowrap transition-all ${
            activeTab === 'dtc'
              ? 'bg-[#ff2a40] text-white shadow-xs'
              : 'text-[#94a3b8] hover:text-[#f8fafc] hover:bg-[rgba(255,255,255,0.05)]'
          }`}
        >
          <ScanLine size={13} />
          Code Scanner (DTC)
        </button>
        <button
          onClick={() => setActiveTab('oil_wear')}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold font-['Chakra_Petch'] whitespace-nowrap transition-all ${
            activeTab === 'oil_wear'
              ? 'bg-[#ff2a40] text-white shadow-xs'
              : 'text-[#94a3b8] hover:text-[#f8fafc] hover:bg-[rgba(255,255,255,0.05)]'
          }`}
        >
          <Droplet size={13} />
          Oil Diagnostics
        </button>
        <button
          onClick={() => setActiveTab('bench')}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold font-['Chakra_Petch'] whitespace-nowrap transition-all ${
            activeTab === 'bench'
              ? 'bg-[#ff2a40] text-white shadow-xs'
              : 'text-[#94a3b8] hover:text-[#f8fafc] hover:bg-[rgba(255,255,255,0.05)]'
          }`}
        >
          <Cpu size={13} />
          ECU Bench / Simulator
        </button>
      </nav>

      {/* TAB 1: PRIMARY COCKPIT DASHBOARD */}
      {activeTab === 'cockpit' && (
        <main className="flex flex-col gap-3">
          {/* Main Dial Cluster: Hero Coolant Temp on Top + Twin Dials Underneath on Mobile / 3 Across on Desktop */}
          <section className="bg-[#0e111a] border border-[rgba(255,255,255,0.08)] rounded-2xl p-3 sm:p-4 shadow-sm flex flex-col md:grid md:grid-cols-3 items-center justify-center gap-2">
            {/* 1. Hero Center: Engine Coolant Temperature */}
            <div className="order-1 md:order-2 flex justify-center scale-105 my-0.5">
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
                size={175}
              />
            </div>

            {/* 2. Twin Companion Dials (2-column on mobile portrait, left/right on desktop) */}
            <div className="order-2 md:contents w-full grid grid-cols-2 gap-2 justify-items-center items-center">
              {/* Companion Left: Cumulative MPG */}
              <div className="flex justify-center">
                <RadialGauge
                  value={metrics.lifetimeMpg}
                  min={0}
                  max={60}
                  title="LIFETIME MPG"
                  unit="MPG"
                  subValue={`${telemetryManager.getLifetimeStats().totalMiles.toFixed(0)} mi`}
                  subLabel="Total"
                  accentColor="#00e676"
                  ticks={[0, 15, 30, 45, 60]}
                  size={140}
                />
              </div>

              {/* Companion Right: Oil Health */}
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
                  size={140}
                />
              </div>
            </div>
          </section>

          {/* Core Analytics Cards Grid */}
          <section className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <ManualTransmissionCard metrics={metrics} />
            <MpgTelemetryCard metrics={metrics} trip={trip} />
          </section>

          {/* Trip Analytics Bar */}
          <TripSummaryBar
            trip={trip}
            onResetTrip={() => telemetryManager.resetTrip()}
          />
        </main>
      )}

      {/* TAB 2: DIAGNOSTIC DTC SCANNER */}
      {activeTab === 'dtc' && (
        <main className="flex flex-col gap-3">
          <DtcScannerCard />
        </main>
      )}

      {/* TAB 3: OIL LIFE & WEAR DIAGNOSTICS */}
      {activeTab === 'oil_wear' && (
        <main className="flex flex-col gap-3">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <OilLifeCard
              oilProfile={oil}
              coolantTempF={metrics.coolantTempF}
              onResetOil={() => telemetryManager.resetOilLife()}
            />
            <MpgTelemetryCard metrics={metrics} trip={trip} />
          </div>

          {/* Deep Explanation of 2013 Civic Oil Degradation Model */}
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

      {/* TAB 4: ECU BENCH / SIMULATOR */}
      {activeTab === 'bench' && (
        <main className="flex flex-col gap-3">
          <SimulatorControls
            scenario={telemetryManager.simulator.scenario}
            onSelectScenario={(sc) => telemetryManager.startSimulation(sc)}
          />

          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <ManualTransmissionCard metrics={metrics} />
            <MpgTelemetryCard metrics={metrics} trip={trip} />
          </div>

          <TripSummaryBar
            trip={trip}
            onResetTrip={() => telemetryManager.resetTrip()}
          />
        </main>
      )}

      {/* Bluetooth Connection Modal */}
      <BluetoothModal
        isOpen={isBluetoothModalOpen}
        onClose={() => setIsBluetoothModalOpen(false)}
        status={status}
        statusMessage={telemetryManager.statusMessage}
        onConnect={() => telemetryManager.connectBluetooth()}
        onDisconnect={() => telemetryManager.disconnect()}
        onStartSim={() => telemetryManager.startSimulation()}
      />
    </div>
  );
}

export default App;
