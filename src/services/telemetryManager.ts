import { OBDLiveMetrics, TripAnalytics, OilLifeProfile, LifetimeStats, ConnectionStatus } from '../types/obd';
import {
  CIVIC_2013_SPECS,
  FUEL_BLENDS,
  FuelBlendId,
  FuelBlendProperties,
} from './obd2/civicSpecs';
import { FuelModelEngine } from './obd2/fuelModel';
import { GearCalculatorEngine } from './obd2/gearCalculator';
import { OilLifeEngine } from './obd2/oilLifeModel';
import { DtcScannerEngine, DtcScanReport } from './obd2/dtcScanner';
import { OBDLinkBluetoothManager, RawObdData } from './bluetooth/obdlinkBluetooth';
import { CivicSimulatorEngine, SimulatorScenario } from './simulator/civicSimulator';
import { resolveIntegrationStep, shouldRecordLifetime } from './obd2/integrationRules';

export class TelemetryManager {
  private fuelModel: FuelModelEngine;
  private gearCalculator: GearCalculatorEngine;
  public oilLifeModel: OilLifeEngine;
  public dtcScanner: DtcScannerEngine;
  public bluetooth: OBDLinkBluetoothManager;
  public simulator: CivicSimulatorEngine;

  private listeners: ((metrics: OBDLiveMetrics, trip: TripAnalytics, oil: OilLifeProfile, status: ConnectionStatus) => void)[] = [];
  
  public connectionStatus: ConnectionStatus = 'disconnected';
  public statusMessage: string = 'Disconnected';
  public shiftMode: 'eco' | 'power' = 'eco';
  public gasPricePerGallon: number = CIVIC_2013_SPECS.gasPriceDefaultDollarsPerGallon;
  public latestDtcReport: DtcScanReport | null = null;

  private currentMetrics: OBDLiveMetrics;
  private tripAnalytics: TripAnalytics;
  private lifetimeStats: LifetimeStats;
  private lifetimeSaveTimestamp: number = 0;
  private timerHandle: any = null;
  private lastUpdateTimestamp: number = Date.now();

  private static readonly LIFETIME_KEY = 'civic_2013_lifetime_stats_v2';
  private static readonly LEGACY_LIFETIME_KEY = 'civic_2013_lifetime_stats_v1';
  private static readonly FUEL_BLEND_KEY = 'civic_2013_fuel_blend_v1';

  constructor() {
    this.fuelModel = new FuelModelEngine();
    this.gearCalculator = new GearCalculatorEngine();
    this.oilLifeModel = new OilLifeEngine();
    this.bluetooth = new OBDLinkBluetoothManager();
    this.dtcScanner = new DtcScannerEngine(this.bluetooth);
    this.simulator = new CivicSimulatorEngine();

    this.currentMetrics = this.getInitialMetrics();
    this.tripAnalytics = this.getInitialTripAnalytics();
    this.lifetimeStats = this.loadLifetimeStats();
    this.loadFuelBlend();

    // The 30s save debounce would otherwise lose the tail of a drive whenever the app is
    // backgrounded or closed, which on a phone is how it ends every single time.
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') this.saveLifetimeStats();
      });
    }

    // Start in simulator mode by default so user immediately sees live reactive gauges on launch!
    this.startSimulation();
  }

  public async runDtcScan(): Promise<DtcScanReport> {
    const isSimulating = this.connectionStatus === 'simulating';
    const report = await this.dtcScanner.performFullScan(isSimulating);
    this.latestDtcReport = report;
    this.notify();
    return report;
  }

  public async clearDtcCodes(): Promise<boolean> {
    const isSimulating = this.connectionStatus === 'simulating';
    const success = await this.dtcScanner.clearAllCodes(isSimulating);
    if (success) {
      if (this.latestDtcReport) {
        this.latestDtcReport = {
          ...this.latestDtcReport,
          milOn: false,
          totalDtcCount: 0,
          pendingCodes: [],
          confirmedCodes: [],
          permanentCodes: [],
        };
      }
      this.notify();
    }
    return success;
  }

  private getInitialMetrics(): OBDLiveMetrics {
    return {
      rpm: 750,
      speedKmh: 0,
      speedMph: 0,
      mafGramsPerSec: 2.4,
      coolantTempC: 85,
      coolantTempF: 185,
      intakeAirTempC: 22,
      intakeAirTempF: 72,
      engineLoadPercent: 18,
      throttlePosPercent: 12,
      shortTermFuelTrim: 0,
      longTermFuelTrim: 1.2,
      timingAdvanceDeg: 12,
      equivalenceRatio: 1.0,
      batteryVoltage: 14.2,
      fuelLevelPercent: 65,
      ambientAirTempC: 22,
      ambientAirTempF: 72,
      o2Sensor1Voltage: 0.45,
      o2Sensor2Voltage: 0.65,
      engineRuntimeSec: 0,
      instantMpg: 0,
      isDfcoActive: false,
      fuelFlowGalPerHour: 0.22,
      fuelFlowLitersPerHour: 0.83,
      airFuelRatio: 14.7,
      rolling30sMpg: 32.5,
      lifetimeMpg: this.lifetimeStats?.lifetimeMpg ?? 0,
      lifetimeMiles: this.lifetimeStats?.totalMiles ?? 0,
      fuelRangeMiles: 275,
      currentGear: 'N',
      gearRatio: 0,
      isClutchSlipping: false,
      optimalShiftRpm: 2200,
      shouldShiftUp: false,
      shiftLightStage: 0,
      timestamp: Date.now(),
    };
  }

  private getInitialTripAnalytics(): TripAnalytics {
    return {
      tripStartTime: Date.now(),
      tripDurationSec: 0,
      distanceMiles: 0,
      totalFuelUsedGallons: 0,
      avgMpg: 0,
      idleTimeSec: 0,
      idleFuelGallons: 0,
      idleCostDollars: 0,
      coastingDfcoTimeSec: 0,
      coastingFuelSavedGallons: 0,
      maxSpeedMph: 0,
      maxRpm: 750,
      avgSpeedMph: 0,
      ecoScore: 92,
    };
  }

  public subscribe(callback: (metrics: OBDLiveMetrics, trip: TripAnalytics, oil: OilLifeProfile, status: ConnectionStatus) => void): () => void {
    this.listeners.push(callback);
    callback(this.currentMetrics, this.tripAnalytics, this.oilLifeModel.getProfile(), this.connectionStatus);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== callback);
    };
  }

  private notify(): void {
    const oil = this.oilLifeModel.getProfile();
    for (const listener of this.listeners) {
      listener(this.currentMetrics, this.tripAnalytics, oil, this.connectionStatus);
    }
  }

  public async connectBluetooth(): Promise<void> {
    this.stopLoop();
    this.connectionStatus = 'connecting';
    this.statusMessage = 'Connecting to OBDLink MX+...';
    this.notify();

    try {
      await this.bluetooth.connect((msg) => {
        this.statusMessage = msg;
        this.notify();
      });
      this.connectionStatus = 'connected';
      this.statusMessage = 'Connected via Bluetooth';
      this.resetTrip();
      this.oilLifeModel.registerEngineStart(this.bluetooth.latestData.coolantC);
      this.startLoop();
    } catch (err: any) {
      this.connectionStatus = 'error';
      this.statusMessage = err.message || 'Bluetooth connection failed';
      this.notify();
    }
  }

  public disconnect(): void {
    this.bluetooth.disconnect();
    this.connectionStatus = 'disconnected';
    this.statusMessage = 'Disconnected';
    this.oilLifeModel.registerEngineStop();
    this.stopLoop();
    this.notify();
  }

  public startSimulation(scenario: SimulatorScenario = 'city_commute'): void {
    this.stopLoop();
    this.simulator.scenario = scenario;
    this.connectionStatus = 'simulating';
    this.statusMessage = `Simulator Active (${scenario.replace('_', ' ').toUpperCase()})`;
    this.startLoop();
  }

  private startLoop(): void {
    this.lastUpdateTimestamp = Date.now();
    if (this.timerHandle) clearInterval(this.timerHandle);
    
    // Run telemetry processing at 12.5Hz (80ms interval) with GPU CSS needle interpolation for battery efficiency
    this.timerHandle = setInterval(() => this.processUpdateStep(), 80);
  }

  private stopLoop(): void {
    if (this.timerHandle) {
      clearInterval(this.timerHandle);
      this.timerHandle = null;
    }
  }

  private processUpdateStep(): void {
    const now = Date.now();
    const rawDtSec = (now - this.lastUpdateTimestamp) / 1000;
    this.lastUpdateTimestamp = now;

    // Display smoothing can use the raw step; the permanent record cannot. See
    // resolveIntegrationStep for why an over-long gap is dropped rather than integrated.
    const dtSec = Math.max(0.01, rawDtSec);
    const integrationDtSec = resolveIntegrationStep(rawDtSec);

    let raw: RawObdData;
    if (this.connectionStatus === 'simulating') {
      raw = this.simulator.tick(dtSec);
    } else if (this.connectionStatus === 'connected') {
      raw = this.bluetooth.latestData;
    } else {
      return;
    }

    // 1. Speeds (use raw precision for integration, not pre-rounded)
    const speedMphRaw = raw.speedKmh * 0.621371;
    const speedMph = parseFloat(speedMphRaw.toFixed(1));
    const coolantF = Math.round((raw.coolantC * 9) / 5 + 32);
    const iatF = Math.round((raw.iatC * 9) / 5 + 32);
    const ambientF = Math.round((raw.ambientC * 9) / 5 + 32);

    // 2. Gear & Manual Transmission Analysis
    const gearResult = this.gearCalculator.analyzeGear(
      raw.rpm,
      raw.speedKmh,
      raw.throttlePos,
      this.shiftMode
    );

    // 3. Air-Fuel & Fuel Flow
    const afr = this.fuelModel.calculateAirFuelRatio(raw.lambda, raw.stft, raw.ltft);
    const isDfco = this.fuelModel.checkDfco(raw.throttlePos, raw.rpm, raw.speedKmh, gearResult.currentGear);
    const fuelFlow = this.fuelModel.calculateFuelFlow(raw.maf, afr, isDfco);
    
    // 4. Instantaneous MPG & Rolling Window
    const instantMpg = this.fuelModel.calculateInstantMpg(speedMph, fuelFlow.fuelFlowGalPerHour, isDfco);
    const rollingMpg = this.fuelModel.updateRollingMpg(instantMpg);
    const fuelRangeMiles = this.fuelModel.calculateFuelRange(
      raw.fuelLevelPercent,
      CIVIC_2013_SPECS.fuelTankCapacityGallons,
      rollingMpg
    );

    // 5. Update Live Metrics
    this.currentMetrics = {
      rpm: raw.rpm,
      speedKmh: raw.speedKmh,
      speedMph,
      mafGramsPerSec: raw.maf,
      coolantTempC: raw.coolantC,
      coolantTempF: coolantF,
      intakeAirTempC: raw.iatC,
      intakeAirTempF: iatF,
      engineLoadPercent: raw.engineLoad,
      throttlePosPercent: raw.throttlePos,
      shortTermFuelTrim: raw.stft,
      longTermFuelTrim: raw.ltft,
      timingAdvanceDeg: raw.timingAdvance,
      equivalenceRatio: raw.lambda,
      batteryVoltage: parseFloat(raw.batteryVoltage.toFixed(2)),
      fuelLevelPercent: parseFloat(raw.fuelLevelPercent.toFixed(1)),
      ambientAirTempC: raw.ambientC,
      ambientAirTempF: ambientF,
      o2Sensor1Voltage: parseFloat(raw.o2Sensor1Voltage.toFixed(3)),
      o2Sensor2Voltage: parseFloat(raw.o2Sensor2Voltage.toFixed(3)),
      engineRuntimeSec: raw.engineRuntimeSec,
      instantMpg: parseFloat(instantMpg.toFixed(1)),
      isDfcoActive: isDfco,
      fuelFlowGalPerHour: parseFloat(fuelFlow.fuelFlowGalPerHour.toFixed(3)),
      fuelFlowLitersPerHour: parseFloat(fuelFlow.fuelFlowLitersPerHour.toFixed(2)),
      airFuelRatio: parseFloat(afr.toFixed(2)),
      rolling30sMpg: parseFloat(rollingMpg.toFixed(1)),
      lifetimeMpg: parseFloat(this.lifetimeStats.lifetimeMpg.toFixed(1)),
      lifetimeMiles: this.lifetimeStats.totalMiles,
      fuelRangeMiles: Math.round(fuelRangeMiles),
      currentGear: gearResult.currentGear,
      gearRatio: parseFloat(gearResult.calculatedRatio.toFixed(2)),
      isClutchSlipping: gearResult.isClutchSlipping,
      optimalShiftRpm: gearResult.optimalShiftRpm,
      shouldShiftUp: gearResult.shouldShiftUp,
      shiftLightStage: gearResult.shiftLightStage,
      timestamp: now,
    };

    // 6. Integrate Trip Statistics
    this.updateTripAnalytics(integrationDtSec, speedMphRaw, fuelFlow.fuelFlowGalPerHour, isDfco, raw.rpm);

    // 7. Record Engine Wear to Oil Life Model
    this.oilLifeModel.recordTelemetryStep(raw.rpm, raw.coolantC, raw.engineLoad, speedMph, dtSec);

    this.notify();
  }

  private updateTripAnalytics(
    dtSec: number,
    speedMph: number,
    fuelFlowGph: number,
    isDfco: boolean,
    rpm: number
  ): void {
    if (rpm < 350) return; // Engine off
    if (dtSec <= 0) return; // Unobserved gap - see MAX_INTEGRATION_STEP_SEC

    this.tripAnalytics.tripDurationSec += dtSec;
    const stepMiles = (speedMph / 3600) * dtSec;
    this.tripAnalytics.distanceMiles += stepMiles;

    const stepFuelGal = (fuelFlowGph / 3600) * dtSec;
    this.tripAnalytics.totalFuelUsedGallons += stepFuelGal;

    // Accumulate into persistent lifetime stats - REAL VEHICLE DATA ONLY.
    if (shouldRecordLifetime(this.connectionStatus)) {
      this.updateLifetimeStats(stepMiles, stepFuelGal);
    }

    // Idle fuel tracking
    if (speedMph <= 1.0) {
      this.tripAnalytics.idleTimeSec += dtSec;
      this.tripAnalytics.idleFuelGallons += stepFuelGal;
      this.tripAnalytics.idleCostDollars = parseFloat((this.tripAnalytics.idleFuelGallons * this.gasPricePerGallon).toFixed(2));
    }

    // DFCO fuel savings (compared to idling at 0.22 gal/hr while coasting)
    if (isDfco) {
      this.tripAnalytics.coastingDfcoTimeSec += dtSec;
      const baselineIdleBurnRate = 0.22; // gal/hr
      this.tripAnalytics.coastingFuelSavedGallons += (baselineIdleBurnRate / 3600) * dtSec;
    }

    // Max stats
    this.tripAnalytics.maxSpeedMph = Math.max(this.tripAnalytics.maxSpeedMph, speedMph);
    this.tripAnalytics.maxRpm = Math.max(this.tripAnalytics.maxRpm, rpm);

    // Averages
    if (this.tripAnalytics.totalFuelUsedGallons > 0.005) {
      this.tripAnalytics.avgMpg = parseFloat((this.tripAnalytics.distanceMiles / this.tripAnalytics.totalFuelUsedGallons).toFixed(1));
    }
    if (this.tripAnalytics.tripDurationSec > 5) {
      this.tripAnalytics.avgSpeedMph = parseFloat(((this.tripAnalytics.distanceMiles / this.tripAnalytics.tripDurationSec) * 3600).toFixed(1));
    }

    // Eco Score (0 - 100) based on average MPG and smoothness
    const targetMpg = 34.0; // EPA highway rating for 2013 Civic 5MT is ~38 mpg / 32 combined
    const mpgRatio = this.tripAnalytics.avgMpg > 0 ? Math.min(1.2, this.tripAnalytics.avgMpg / targetMpg) : 1.0;
    const idlePenalty = Math.min(25, (this.tripAnalytics.idleTimeSec / Math.max(60, this.tripAnalytics.tripDurationSec)) * 40);
    this.tripAnalytics.ecoScore = Math.max(10, Math.min(100, Math.round(mpgRatio * 100 - idlePenalty)));
  }

  public resetTrip(): void {
    this.tripAnalytics = this.getInitialTripAnalytics();
    this.notify();
  }

  public resetOilLife(): void {
    this.oilLifeModel.resetOilLife();
    this.notify();
  }

  // --- Lifetime Stats Persistence ---

  private loadLifetimeStats(): LifetimeStats {
    try {
      // v1 mixed simulated driving in with real driving and cannot be separated after the
      // fact, so it is dropped rather than migrated. Starting from zero on real data is
      // worth more than a large number that means nothing.
      localStorage.removeItem(TelemetryManager.LEGACY_LIFETIME_KEY);

      const saved = localStorage.getItem(TelemetryManager.LIFETIME_KEY);
      if (saved) {
        const parsed = JSON.parse(saved) as Partial<LifetimeStats>;
        const totalMiles = Number(parsed.totalMiles);
        const totalFuelGallons = Number(parsed.totalFuelGallons);
        if (Number.isFinite(totalMiles) && Number.isFinite(totalFuelGallons)) {
          return {
            totalMiles: Math.max(0, totalMiles),
            totalFuelGallons: Math.max(0, totalFuelGallons),
            lifetimeMpg:
              totalFuelGallons > 0.01 ? totalMiles / totalFuelGallons : 0,
            firstTrackedTimestamp: Number(parsed.firstTrackedTimestamp) || Date.now(),
          };
        }
      }
    } catch {
      // Fallback
    }
    return {
      totalMiles: 0,
      totalFuelGallons: 0,
      lifetimeMpg: 0,
      firstTrackedTimestamp: Date.now(),
    };
  }

  private saveLifetimeStats(): void {
    try {
      localStorage.setItem(TelemetryManager.LIFETIME_KEY, JSON.stringify(this.lifetimeStats));
    } catch {
      // Ignore storage quota errors
    }
  }

  /** Clears the lifetime record and starts accumulating again from zero. */
  public resetLifetimeStats(): void {
    this.lifetimeStats = {
      totalMiles: 0,
      totalFuelGallons: 0,
      lifetimeMpg: 0,
      firstTrackedTimestamp: Date.now(),
    };
    this.saveLifetimeStats();
    this.notify();
  }

  public setFuelBlend(id: FuelBlendId): void {
    this.fuelModel.setFuelBlend(id);
    try {
      localStorage.setItem(TelemetryManager.FUEL_BLEND_KEY, id);
    } catch {
      // Ignore storage quota errors
    }
    this.notify();
  }

  public getFuelBlend(): FuelBlendProperties {
    return this.fuelModel.getFuelBlend();
  }

  private loadFuelBlend(): void {
    try {
      const saved = localStorage.getItem(TelemetryManager.FUEL_BLEND_KEY) as FuelBlendId | null;
      if (saved && FUEL_BLENDS[saved]) {
        this.fuelModel.setFuelBlend(saved);
      }
    } catch {
      // Keep the default blend
    }
  }

  private updateLifetimeStats(stepMiles: number, stepFuelGal: number): void {
    if (stepMiles <= 0 && stepFuelGal <= 0) return;

    this.lifetimeStats.totalMiles += stepMiles;
    this.lifetimeStats.totalFuelGallons += stepFuelGal;

    if (this.lifetimeStats.totalFuelGallons > 0.01) {
      this.lifetimeStats.lifetimeMpg = this.lifetimeStats.totalMiles / this.lifetimeStats.totalFuelGallons;
    }

    // Debounce saves to once per 30 seconds
    const now = Date.now();
    if (now - this.lifetimeSaveTimestamp >= 30000) {
      this.lifetimeSaveTimestamp = now;
      this.saveLifetimeStats();
    }
  }

  public getLifetimeStats(): LifetimeStats {
    return { ...this.lifetimeStats };
  }
}

// Global Singleton Instance
export const telemetryManager = new TelemetryManager();
