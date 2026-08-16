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
  Car
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
      <div className="flex h-screen w-screen items-center justify-center bg-[#07080b] text-[#f8fafc]">
        <div className="flex items-center gap-3">
          <RefreshCw className="animate-spin text-[#ff2a40]" size={28} />
          <span className="font-['Chakra_Petch'] text-lg font-bold">Initializing Civic 5MT Telemetry...</span>
        </div>
      </div>
    );
  }

  // Cold Engine Warning Flag (< 160°F)
  const isColdEngine = metrics.coolantTempF < 160;

  return (
    <div className="min-h-screen w-full bg-[#07080b] text-[#f8fafc] flex flex-col p-2 sm:p-4 max-w-7xl mx-auto gap-3.5 select-none">
      {/* Top Application Bar */}
      <header className="flex items-center justify-between bg-[#0e1118]/80 backdrop-blur-md border border-[#1f2537] rounded-2xl px-3.5 py-2.5 shadow-xl">
        {/* Brand & Vehicle Details */}
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-xl bg-[#ff2a40]/20 border border-[#ff2a40]/40 flex items-center justify-center text-[#ff2a40]">
            <Car size={18} />
          </div>
          <div>
            <div className="flex items-center gap-1.5">
              <h1 className="text-xs sm:text-sm font-extrabold font-['Chakra_Petch'] tracking-wider text-[#f8fafc]">
                2013 CIVIC LX <span className="text-[#ff2a40]">5MT</span>
              </h1>
              <span className="badge-pill badge-red text-[9px] py-0 px-1.5">R18Z1</span>
            </div>
            <p className="text-[10px] text-[#64748b] hidden sm:block">1.8L SOHC i-VTEC • Vgate vLinker MC+</p>
          </div>
        </div>

        {/* Engine Vital Badges */}
        <div className="flex items-center gap-2 sm:gap-4 font-['Chakra_Petch'] text-xs">
          {/* Coolant Temp */}
          <div className={`flex items-center gap-1 px-2 py-1 rounded-lg border ${
            isColdEngine ? 'bg-[#00d2ff]/10 text-[#00d2ff] border-[#00d2ff]/30' : 'bg-[#121622] text-[#94a3b8] border-[#1a2030]'
          }`}>
            <Thermometer size={14} className={isColdEngine ? 'text-[#00d2ff]' : 'text-[#00e676]'} />
            <span className="font-bold">{metrics.coolantTempF}°F</span>
            {isColdEngine && <span className="text-[9px] text-[#00d2ff] font-semibold hidden md:inline">COLD</span>}
          </div>

          {/* Intake Temp & Load */}
          <div className="hidden md:flex items-center gap-1.5 px-2 py-1 rounded-lg bg-[#121622] text-[#94a3b8] border border-[#1a2030]">
            <Wind size={13} className="text-[#64748b]" />
            <span>IAT {metrics.intakeAirTempF}°F</span>
          </div>

          <div className="hidden sm:flex items-center gap-1.5 px-2 py-1 rounded-lg bg-[#121622] text-[#94a3b8] border border-[#1a2030]">
            <Activity size={13} className="text-[#ffaa00]" />
            <span>LOAD {Math.round(metrics.engineLoadPercent)}%</span>
          </div>

          {/* Quick DTC Status link */}
          <button
            onClick={() => setActiveTab('dtc')}
            className="flex items-center gap-1 px-2 py-1 rounded-lg bg-[#121622] hover:bg-[#1a2030] text-[#94a3b8] hover:text-[#f8fafc] border border-[#1a2030] text-xs font-['Chakra_Petch'] transition-colors"
            title="Open Diagnostic Trouble Code Scanner"
          >
            <span className="w-2 h-2 rounded-full bg-[#00e676]" />
            <span className="hidden md:inline">OBD HEALTH:</span> <span>0 CODES</span>
          </button>
        </div>

        {/* Action Controls: Hardware Connection & Fullscreen */}
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => setIsBluetoothModalOpen(true)}
            className={`flex items-center gap-1.5 px-2.5 sm:px-3.5 py-1.5 rounded-xl border text-xs font-bold font-['Chakra_Petch'] transition-all ${
              status === 'connected'
                ? 'bg-[#00e676]/15 text-[#00e676] border-[#00e676]/40 shadow-[0_0_12px_rgba(0,230,118,0.2)]'
                : status === 'simulating'
                ? 'bg-[#00d2ff]/15 text-[#00d2ff] border-[#00d2ff]/40'
                : 'bg-[#161a26] text-[#94a3b8] border-[#252b3d]'
            }`}
          >
            {status === 'connected' ? (
              <>
                <Bluetooth size={14} className="text-[#00e676]" />
                <span className="hidden sm:inline">OBD LIVE</span>
              </>
            ) : status === 'simulating' ? (
              <>
                <Cpu size={14} className="text-[#00d2ff]" />
                <span className="hidden sm:inline">SIMULATOR</span>
              </>
            ) : (
              <>
                <Bluetooth size={14} />
                <span>CONNECT</span>
              </>
            )}
          </button>

          <button
            onClick={toggleFullscreen}
            className="p-1.5 sm:p-2 rounded-xl bg-[#161a26] text-[#94a3b8] hover:text-[#f8fafc] border border-[#252b3d] transition-colors"
            title="Toggle Fullscreen"
          >
            {isFullscreen ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
          </button>
        </div>
      </header>

      {/* Top F1 Shift Light Bar */}
      <ShiftLightBar
        stage={metrics.shiftLightStage}
        rpm={metrics.rpm}
        shiftMode={shiftMode}
        shouldShiftUp={metrics.shouldShiftUp}
        onToggleMode={toggleShiftMode}
      />

      {/* View Switcher Tabs for Mobile/Desktop */}
      <div className="flex items-center justify-between border-b border-[#1b2030] pb-1 overflow-x-auto">
        <div className="flex items-center gap-1.5 sm:gap-2">
          <button
            onClick={() => setActiveTab('cockpit')}
            className={`px-3 py-1.5 rounded-lg text-xs font-bold font-['Chakra_Petch'] whitespace-nowrap transition-all ${
              activeTab === 'cockpit'
                ? 'bg-[#ff2a40] text-white shadow-[0_0_10px_#ff2a40]'
                : 'text-[#94a3b8] hover:text-[#f8fafc] hover:bg-[#121622]'
            }`}
          >
            🏎️ Primary Cockpit
          </button>
          <button
            onClick={() => setActiveTab('dtc')}
            className={`px-3 py-1.5 rounded-lg text-xs font-bold font-['Chakra_Petch'] whitespace-nowrap transition-all ${
              activeTab === 'dtc'
                ? 'bg-[#ff2a40] text-white shadow-[0_0_10px_#ff2a40]'
                : 'text-[#94a3b8] hover:text-[#f8fafc] hover:bg-[#121622]'
            }`}
          >
            🔍 Code Scanner (DTC)
          </button>
          <button
            onClick={() => setActiveTab('oil_wear')}
            className={`px-3 py-1.5 rounded-lg text-xs font-bold font-['Chakra_Petch'] whitespace-nowrap transition-all ${
              activeTab === 'oil_wear'
                ? 'bg-[#ff2a40] text-white shadow-[0_0_10px_#ff2a40]'
                : 'text-[#94a3b8] hover:text-[#f8fafc] hover:bg-[#121622]'
            }`}
          >
            💧 Oil Life & Diagnostics
          </button>
          <button
            onClick={() => setActiveTab('bench')}
            className={`px-3 py-1.5 rounded-lg text-xs font-bold font-['Chakra_Petch'] whitespace-nowrap transition-all ${
              activeTab === 'bench'
                ? 'bg-[#ff2a40] text-white shadow-[0_0_10px_#ff2a40]'
                : 'text-[#94a3b8] hover:text-[#f8fafc] hover:bg-[#121622]'
            }`}
          >
            🕹️ ECU Bench / Simulator
          </button>
        </div>
      </div>

      {/* TAB 1: PRIMARY COCKPIT DASHBOARD */}
      {activeTab === 'cockpit' && (
        <div className="flex flex-col gap-3.5">
          {/* Main Radial Dial Cluster - Option A: Hero Coolant Top + Dual Companion Dials (Portrait Mobile) / 3 Across (Desktop) */}
          <div className="flex flex-col md:grid md:grid-cols-3 gap-2.5 sm:gap-3 bg-[#0a0d14]/70 border border-[#1b2030] rounded-2xl p-3 sm:p-4 shadow-xl items-center justify-center">
            {/* 1. Hero Center: Coolant Temperature (Large, Prominent Thermal Vital) */}
            <div className="order-1 md:order-2 flex justify-center">
              <RadialGauge
                value={metrics.coolantTempF}
                min={100}
                max={260}
                title="COOLANT TEMP"
                unit="°F"
                subValue={`${metrics.coolantTempC}°C • ${metrics.coolantTempF < 160 ? 'COLD' : metrics.coolantTempF > 220 ? 'OVERHEAT' : 'OPTIMAL'}`}
                subLabel="Engine Status"
                accentColor={metrics.coolantTempF < 160 ? '#00d2ff' : metrics.coolantTempF > 220 ? '#ff2a40' : '#00e676'}
                redlineStart={225}
                ticks={[100, 140, 180, 220, 260]}
                size={200}
              />
            </div>

            {/* Twin Companion Dials for Mobile: Lifetime MPG (Left) & Oil Life (Right) */}
            <div className="order-2 md:contents w-full grid grid-cols-2 gap-2 justify-items-center items-center">
              {/* Lifetime Cumulative MPG */}
              <div className="flex justify-center">
                <RadialGauge
                  value={metrics.lifetimeMpg}
                  min={0}
                  max={60}
                  title="LIFETIME MPG"
                  unit="MPG"
                  subValue={`${telemetryManager.getLifetimeStats().totalMiles.toFixed(0)} mi`}
                  subLabel="Cumulative"
                  accentColor="#00e676"
                  ticks={[0, 15, 30, 45, 60]}
                  size={155}
                />
              </div>

              {/* Engine Oil Life */}
              <div className="flex justify-center">
                <RadialGauge
                  value={oil.oilLifePercent}
                  min={0}
                  max={100}
                  title="OIL LIFE"
                  unit="%"
                  subValue={`~${oil.estimatedMilesRemaining.toLocaleString()} mi`}
                  subLabel="Remaining"
                  accentColor={oil.oilLifePercent < 15 ? '#ff2a40' : oil.oilLifePercent < 40 ? '#ffaa00' : '#00e676'}
                  ticks={[0, 25, 50, 75, 100]}
                  size={155}
                />
              </div>
            </div>
          </div>

          {/* Core Analytics Cards Grid */}
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            <ManualTransmissionCard metrics={metrics} />
            <MpgTelemetryCard metrics={metrics} trip={trip} />
            <div className="hidden lg:block">
              <OilLifeCard
                oilProfile={oil}
                coolantTempF={metrics.coolantTempF}
                onResetOil={() => telemetryManager.resetOilLife()}
              />
            </div>
          </div>

          {/* Trip Analytics Bar */}
          <TripSummaryBar
            trip={trip}
            onResetTrip={() => telemetryManager.resetTrip()}
          />
        </div>
      )}

      {/* TAB 2: DIAGNOSTIC DTC SCANNER */}
      {activeTab === 'dtc' && (
        <div className="flex flex-col gap-3.5">
          <DtcScannerCard />
        </div>
      )}

      {/* TAB 3: OIL LIFE & WEAR DIAGNOSTICS */}
      {activeTab === 'oil_wear' && (
        <div className="flex flex-col gap-3.5">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
            <OilLifeCard
              oilProfile={oil}
              coolantTempF={metrics.coolantTempF}
              onResetOil={() => telemetryManager.resetOilLife()}
            />
            <MpgTelemetryCard metrics={metrics} trip={trip} />
          </div>

          {/* Deep Explanation of 2013 Civic Oil Degradation Model */}
          <div className="telemetry-card flex flex-col gap-3 text-xs leading-relaxed text-[#94a3b8]">
            <h3 className="text-sm font-bold font-['Chakra_Petch'] text-[#f8fafc] flex items-center gap-2">
              <Activity size={16} className="text-[#ff2a40]" />
              How the Deep Oil Life Algorithm Works
            </h3>
            <p>
              Unlike generic dash odometers that only count distance, this custom model processes real-time engine telemetry from your <strong>2013 Civic LX R18Z1</strong>:
            </p>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-[11px]">
              <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3">
                <strong className="text-[#00e676] font-['Chakra_Petch']">1. Mechanical Revolutions (Cycles)</strong>
                <p className="mt-1 text-[#64748b]">Counts actual crankshaft revolutions. 10 miles of stop-and-go 1st/2nd gear driving shears oil 3x faster than high gear cruising.</p>
              </div>
              <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3">
                <strong className="text-[#00d2ff] font-['Chakra_Petch']">2. Cold-Start & Condensation</strong>
                <p className="mt-1 text-[#64748b]">Engine starts below 160°F accumulate moisture and fuel blowby, accelerating additive depletion until fully warmed up.</p>
              </div>
              <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3">
                <strong className="text-[#ffaa00] font-['Chakra_Petch']">3. Short Trip Penalty</strong>
                <p className="mt-1 text-[#64748b]">Trips ending under 15 minutes before reaching 185°F fail to vaporize unburned gasoline out of the crankcase.</p>
              </div>
              <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3">
                <strong className="text-[#ff2a40] font-['Chakra_Petch']">4. High-RPM Thermal Shear</strong>
                <p className="mt-1 text-[#64748b]">VTEC high-RPM pulls (&gt;4,500 RPM under load) calculate viscosity breakdown from elevated oil film temperatures.</p>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* TAB 3: ECU BENCH / SIMULATOR */}
      {activeTab === 'bench' && (
        <div className="flex flex-col gap-3.5">
          <SimulatorControls
            scenario={telemetryManager.simulator.scenario}
            onSelectScenario={(sc) => telemetryManager.startSimulation(sc)}
          />

          <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
            <ManualTransmissionCard metrics={metrics} />
            <MpgTelemetryCard metrics={metrics} trip={trip} />
          </div>

          <TripSummaryBar
            trip={trip}
            onResetTrip={() => telemetryManager.resetTrip()}
          />
        </div>
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
