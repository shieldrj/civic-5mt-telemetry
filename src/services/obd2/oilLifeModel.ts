import { OilLifeProfile } from '../../types/obd';
import { CIVIC_2013_SPECS } from './civicSpecs';

const STORAGE_KEY = 'civic_2013_oil_profile_v1';

export class OilLifeEngine {
  private profile: OilLifeProfile;
  private currentTripDurationSec: number = 0;
  private currentTripMaxTempC: number = 0;

  constructor() {
    this.profile = this.loadProfile();
  }

  private getDefaultProfile(): OilLifeProfile {
    return {
      lastResetTimestamp: Date.now() - 30 * 24 * 60 * 60 * 1000, // 30 days ago default
      lastResetOdometer: 112000,
      currentOdometer: 114250,
      oilLifePercent: 78.5,
      accumulatedRevolutions: 3200000,
      coldStartsCount: 42,
      timeBelowOperatingTempSec: 28400,
      shortTripsCount: 14,
      highThermalStressSec: 920,
      estimatedMilesRemaining: 5887,
      estimatedDaysRemaining: 74,
      oilConditionGrade: 'Good',
      degradationBreakdown: {
        revWearFactor: 22.0,
        coldStartPenalty: 4.8,
        shortTripPenalty: 3.2,
        thermalShearPenalty: 1.5,
      },
    };
  }

  public loadProfile(): OilLifeProfile {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved) {
        return JSON.parse(saved);
      }
    } catch {
      // Fallback
    }
    const defaultProf = this.getDefaultProfile();
    this.saveProfile(defaultProf);
    return defaultProf;
  }

  public saveProfile(profile: OilLifeProfile = this.profile): void {
    this.profile = profile;
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(profile));
    } catch {
      // Ignore storage quota errors
    }
  }

  public getProfile(): OilLifeProfile {
    return { ...this.profile };
  }

  /**
   * Called on each live engine update to accumulate wear factors.
   * @param rpm Current engine speed
   * @param coolantTempC Current engine coolant temperature in Celsius
   * @param engineLoadPercent Calculated engine load (0 - 100%)
   * @param speedMph Current vehicle speed
   * @param dtSec Delta time since last update (seconds)
   */
  public recordTelemetryStep(
    rpm: number,
    coolantTempC: number,
    engineLoadPercent: number,
    speedMph: number,
    dtSec: number
  ): OilLifeProfile {
    if (rpm < 400) {
      // Engine is not running
      return this.profile;
    }

    this.currentTripDurationSec += dtSec;
    this.currentTripMaxTempC = Math.max(this.currentTripMaxTempC, coolantTempC);

    // 1. Mechanical Revolutions Accumulation
    const stepRevs = (rpm / 60) * dtSec;
    this.profile.accumulatedRevolutions += stepRevs;

    // Mileage increment estimation
    const stepMiles = (speedMph / 3600) * dtSec;
    this.profile.currentOdometer += stepMiles;

    // 2. Cold Operation & Warmup Penalty
    // Normal operating temperature is ~71°C to 90°C (160°F - 195°F)
    const isCold = coolantTempC < CIVIC_2013_SPECS.operatingTempThresholdC;
    if (isCold) {
      this.profile.timeBelowOperatingTempSec += dtSec;
    }

    // 3. High Thermal / RPM Stress Penalty
    if (rpm > CIVIC_2013_SPECS.highThermalThresholdRpm && engineLoadPercent > CIVIC_2013_SPECS.highLoadThresholdPercent) {
      this.profile.highThermalStressSec += dtSec;
    }

    // Recompute Remaining Oil Life %
    this.recalculateOilHealth();
    this.saveProfile();
    return { ...this.profile };
  }

  /**
   * Marks a new engine start. If coolant is below 60°C (140°F), increments cold start.
   */
  public registerEngineStart(coolantTempC: number): void {
    this.currentTripDurationSec = 0;
    this.currentTripMaxTempC = coolantTempC;

    if (coolantTempC < 60) {
      this.profile.coldStartsCount++;
    }
    this.saveProfile();
  }

  /**
   * Marks trip completion: detects short trips (< 15 mins without reaching 85°C)
   * which cause moisture and fuel dilution in engine oil.
   */
  public registerEngineStop(): void {
    if (this.currentTripDurationSec > 60 && this.currentTripDurationSec < 900 && this.currentTripMaxTempC < 80) {
      // Trip under 15 minutes that didn't get hot enough to vaporize fuel/water condensation
      this.profile.shortTripsCount++;
    }
    this.currentTripDurationSec = 0;
    this.currentTripMaxTempC = 0;
    this.recalculateOilHealth();
    this.saveProfile();
  }

  /**
   * Resets oil life tracker to 100% after an oil & filter change.
   */
  public resetOilLife(odometerAtReset?: number): OilLifeProfile {
    const currentOdo = odometerAtReset || this.profile.currentOdometer;
    this.profile = {
      lastResetTimestamp: Date.now(),
      lastResetOdometer: currentOdo,
      currentOdometer: currentOdo,
      oilLifePercent: 100.0,
      accumulatedRevolutions: 0,
      coldStartsCount: 0,
      timeBelowOperatingTempSec: 0,
      shortTripsCount: 0,
      highThermalStressSec: 0,
      estimatedMilesRemaining: CIVIC_2013_SPECS.baselineOilLifeMiles,
      estimatedDaysRemaining: 180, // ~6 months default
      oilConditionGrade: 'Excellent',
      degradationBreakdown: {
        revWearFactor: 0,
        coldStartPenalty: 0,
        shortTripPenalty: 0,
        thermalShearPenalty: 0,
      },
    };
    this.saveProfile();
    return { ...this.profile };
  }

  private recalculateOilHealth(): void {
    // 1. Baseline Revolutions Wear:
    // 14.5M revolutions = 100% baseline wear
    const revWearPercent = (this.profile.accumulatedRevolutions / CIVIC_2013_SPECS.baselineLifetimeRevolutions) * 100;

    // 2. Cold Start & Warmup Penalty (each cold start & minute below 160°F shears oil molecules)
    const coldStartPenaltyPercent = (this.profile.coldStartsCount * 0.15) + (this.profile.timeBelowOperatingTempSec / 3600) * 0.4;

    // 3. Short Trip Moisture Dilution Penalty
    const shortTripPenaltyPercent = this.profile.shortTripsCount * 0.35;

    // 4. High Thermal / RPM Shear Penalty
    const thermalPenaltyPercent = (this.profile.highThermalStressSec / 60) * 0.25;

    const totalDegradation = revWearPercent + coldStartPenaltyPercent + shortTripPenaltyPercent + thermalPenaltyPercent;
    const remainingPercent = Math.max(0, Math.min(100, 100.0 - totalDegradation));

    this.profile.oilLifePercent = parseFloat(remainingPercent.toFixed(1));
    this.profile.degradationBreakdown = {
      revWearFactor: parseFloat(revWearPercent.toFixed(1)),
      coldStartPenalty: parseFloat(coldStartPenaltyPercent.toFixed(1)),
      shortTripPenalty: parseFloat(shortTripPenaltyPercent.toFixed(1)),
      thermalShearPenalty: parseFloat(thermalPenaltyPercent.toFixed(1)),
    };

    // Estimated Miles Remaining
    const milesDriven = Math.max(0, this.profile.currentOdometer - this.profile.lastResetOdometer);
    const degradationRatio = totalDegradation > 0 ? totalDegradation / 100 : 0.01;
    const effectiveTotalMiles = milesDriven > 50 ? (milesDriven / degradationRatio) : CIVIC_2013_SPECS.baselineOilLifeMiles;
    this.profile.estimatedMilesRemaining = Math.max(0, Math.round((remainingPercent / 100) * effectiveTotalMiles));

    // Condition Grade
    if (remainingPercent > 70) {
      this.profile.oilConditionGrade = 'Excellent';
    } else if (remainingPercent > 40) {
      this.profile.oilConditionGrade = 'Good';
    } else if (remainingPercent > 15) {
      this.profile.oilConditionGrade = 'Fair';
    } else if (remainingPercent > 5) {
      this.profile.oilConditionGrade = 'Service Due';
    } else {
      this.profile.oilConditionGrade = 'Degraded';
    }
  }
}
