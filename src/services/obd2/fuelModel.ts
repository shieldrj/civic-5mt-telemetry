import { CIVIC_2013_SPECS } from './civicSpecs';

export class FuelModelEngine {
  private recentMpgHistory: number[] = [];
  private readonly MAX_HISTORY_SAMPLES = 600; // 600 samples (30 seconds at 20Hz)

  /**
   * Calculates actual Air:Fuel Ratio from wideband lambda and fuel trims.
   * If lambda is 1.0 (stoich) and fuel trims are 0%, AFR is 14.7.
   */
  public calculateAirFuelRatio(
    equivalenceRatioLambda: number = 1.0,
    shortTermFuelTrimPercent: number = 0,
    longTermFuelTrimPercent: number = 0
  ): number {
    // If wideband lambda (PID 0124) is available and valid, it already reflects
    // post-trim combustion AFR. Using trims ON TOP of lambda double-counts.
    if (equivalenceRatioLambda > 0.5 && equivalenceRatioLambda < 2.0) {
      const dynamicAfr = CIVIC_2013_SPECS.stoichiometricAfr * equivalenceRatioLambda;
      return Math.max(9.0, Math.min(22.0, dynamicAfr));
    }
    // Narrowband fallback: positive trim = ECU injecting MORE fuel = lower AFR
    const totalTrimFactor = 1.0 + (shortTermFuelTrimPercent + longTermFuelTrimPercent) / 100.0;
    const dynamicAfr = CIVIC_2013_SPECS.stoichiometricAfr / (totalTrimFactor > 0 ? totalTrimFactor : 1.0);
    return Math.max(9.0, Math.min(22.0, dynamicAfr));
  }

  /**
   * Computes Deceleration Fuel Cut-Off (DFCO) status.
   * In a 5-speed manual Honda Civic, when throttle is closed (<= 1%) and engine speed > 1200 RPM
   * while the car is moving in gear, the ECU completely cuts fuel injector pulses.
   */
  public checkDfco(
    throttlePosPercent: number,
    rpm: number,
    speedKmh: number,
    currentGear: number | string
  ): boolean {
    const isThrottleClosed = throttlePosPercent <= CIVIC_2013_SPECS.closedThrottleBaselinePercent;
    const isAboveIdleRpm = rpm >= 1200;
    const isMoving = speedKmh >= 10;
    const isInGear = typeof currentGear === 'number' && currentGear >= 1 && currentGear <= 5;

    return isThrottleClosed && isAboveIdleRpm && isMoving && isInGear;
  }

  /**
   * Calculates real-time Fuel Flow in grams/sec, gallons/hour, and liters/hour.
   */
  public calculateFuelFlow(
    mafGramsPerSec: number,
    airFuelRatio: number,
    isDfcoActive: boolean
  ): {
    fuelFlowGramsPerSec: number;
    fuelFlowGalPerHour: number;
    fuelFlowLitersPerHour: number;
  } {
    if (isDfcoActive) {
      return {
        fuelFlowGramsPerSec: 0,
        fuelFlowGalPerHour: 0,
        fuelFlowLitersPerHour: 0,
      };
    }

    const afr = airFuelRatio > 0 ? airFuelRatio : CIVIC_2013_SPECS.stoichiometricAfr;
    const maf = Math.max(0, mafGramsPerSec);
    
    // Fuel mass flow (g/s) = Air mass flow (g/s) / AFR
    const fuelFlowGramsPerSec = maf / afr;

    // Convert to Gallons / Hour:
    // (g/s * 3600 s/hr) / (2788 g/gal)
    const fuelFlowGalPerHour = (fuelFlowGramsPerSec * 3600) / CIVIC_2013_SPECS.gasolineDensityGramsPerGallon;
    
    // Convert to Liters / Hour:
    // (g/s * 3600 s/hr) / (736.5 g/L)
    const fuelFlowLitersPerHour = (fuelFlowGramsPerSec * 3600) / CIVIC_2013_SPECS.gasolineDensityGramsPerLiter;

    return {
      fuelFlowGramsPerSec,
      fuelFlowGalPerHour,
      fuelFlowLitersPerHour,
    };
  }

  /**
   * Calculates Instantaneous MPG.
   * If DFCO is active and moving, returns 99.9 (capped display) or Infinity mathematically.
   * If vehicle is stopped (0 mph), returns 0.0 MPG.
   */
  public calculateInstantMpg(
    speedMph: number,
    fuelFlowGalPerHour: number,
    isDfcoActive: boolean
  ): number {
    if (speedMph <= 1.0) {
      return 0.0;
    }

    if (isDfcoActive || fuelFlowGalPerHour <= 0.001) {
      return 99.9; // Standard automotive digital gauge cap for DFCO coasting
    }

    const rawMpg = speedMph / fuelFlowGalPerHour;
    return Math.min(99.9, Math.max(0.0, rawMpg));
  }

  /**
   * Updates rolling smoothed MPG buffer to eliminate noisy single-packet sensor spikes.
   */
  public updateRollingMpg(instantMpg: number): number {
    this.recentMpgHistory.push(instantMpg);
    if (this.recentMpgHistory.length > this.MAX_HISTORY_SAMPLES) {
      this.recentMpgHistory.shift();
    }

    // Harmonic mean: N / Σ(1/MPG_i), excluding zero/DFCO entries
    // This prevents 99.9 MPG coasting spikes from inflating the average
    let reciprocalSum = 0;
    let validCount = 0;
    for (const mpg of this.recentMpgHistory) {
      if (mpg > 0.1 && mpg < 99.9) {
        reciprocalSum += 1.0 / mpg;
        validCount++;
      }
    }
    return validCount > 0 ? validCount / reciprocalSum : 0;
  }

  /**
   * Speed-Density estimation fallback if MAF sensor is disconnected or faulty.
   * Uses MAP (Manifold Absolute Pressure), IAT (Intake Air Temp), RPM, and Civic R18 Volumetric Efficiency.
   */
  public estimateMafFromSpeedDensity(
    mapKpa: number,
    intakeAirTempC: number,
    rpm: number,
    volumetricEfficiency: number = 0.85
  ): number {
    const iatKelvin = intakeAirTempC + 273.15;
    const displacementL = CIVIC_2013_SPECS.engineDisplacementLiters;
    // Ideal Gas Law: Air Density rho = P / (R_specific * T)
    // R_specific for dry air = 287.058 J/(kg*K) = 0.287058 kPa*m^3/(kg*K)
    // MAF (g/s) = (MAP * (RPM/120) * Displacement * VE * 28.97) / (8.314 * (IAT + 273.15))
    const mafGramsPerSec = (mapKpa * (rpm / 120) * displacementL * volumetricEfficiency * 28.97) / (8.314 * iatKelvin);
    return Math.max(0, mafGramsPerSec);
  }
}
