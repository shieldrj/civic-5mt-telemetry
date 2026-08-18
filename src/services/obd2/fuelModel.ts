import {
  CIVIC_2013_SPECS,
  FUEL_BLENDS,
  DEFAULT_FUEL_BLEND,
  FuelBlendId,
  FuelBlendProperties,
} from './civicSpecs';

/**
 * What the instant-MPG readout is actually showing. Two of these are not economy figures
 * at all, and rendering them as numbers is what made the old readout untrustworthy: at a
 * red light `calculateInstantMpg` returns 0 because the car is not moving, and on a closed
 * throttle it returns the 99.9 cap because the injectors are off. Neither is "your car is
 * getting N mpg", so neither should be drawn as a value.
 */
export type MpgDisplayState = 'idle' | 'coasting' | 'driving';

export interface MpgDisplayReading {
  value: number;
  state: MpgDisplayState;
}

export class FuelModelEngine {
  private recentMpgHistory: number[] = [];

  // 30 seconds of samples at the loop's real rate. Derived rather than written down,
  // because the two drifted apart last time: this was 600 with a comment claiming 20Hz,
  // while the loop runs at 80ms, so `rolling30sMpg` was averaging 48 seconds.
  private readonly MAX_HISTORY_SAMPLES = Math.round(
    30_000 / CIVIC_2013_SPECS.telemetryTickMs
  );

  // ── Instant-MPG display damping ──────────────────────────────────────────────
  // The loop recomputes instant MPG 12.5 times a second, and the underlying figure
  // genuinely swings between single digits under acceleration and the 99.9 cap on
  // overrun. Both facts are real; a numeral that reflects them directly is unreadable.
  //
  // So the needle and the numeral are damped differently, which is what OEM consumption
  // gauges do. The arc follows a 1.5s exponential average - the eye reads a moving shape
  // fine, and you still see it dive within about a second of opening the throttle. The
  // numeral is resampled from that same average twice a second, because a digit changing
  // 12 times a second carries no information a driver can use.
  private displayMpgAverage = 0;
  private displayMpgLatched = 0;
  private displayMpgLatchAgeSec = 0;
  private readonly DISPLAY_TIME_CONSTANT_SEC = 1.5;
  private readonly DISPLAY_LATCH_INTERVAL_SEC = 0.5;

  /**
   * The blend in the tank. Every mass-to-volume and air-to-fuel conversion below depends
   * on it: the ECU targets stoichiometry for whatever fuel is actually present, so the
   * lambda it reports is relative to that fuel's ratio, not to pure gasoline's 14.7.
   */
  private blend: FuelBlendProperties = FUEL_BLENDS[DEFAULT_FUEL_BLEND];

  public setFuelBlend(id: FuelBlendId): void {
    this.blend = FUEL_BLENDS[id] ?? FUEL_BLENDS[DEFAULT_FUEL_BLEND];
  }

  public getFuelBlend(): FuelBlendProperties {
    return this.blend;
  }

  /**
   * Calculates actual Air:Fuel Ratio from wideband lambda, or from fuel trims when the car
   * has no wideband PID to read.
   *
   * The lambda argument is `number | null` and has no default, which is the whole point.
   * It used to default to 1.0, and a car with no wideband PID therefore arrived here with
   * a lambda of exactly 1.0 on every single tick - forever. That is not a neutral value:
   * 1.0 passes the validity range below, so the function took the wideband branch, returned
   * bare stoichiometry, and never reached the trim fallback. The app reported a mixture it
   * had never measured while discarding the fuel trims it actually had.
   *
   * Passing null now means "not measured" and is the only way to reach the fallback, so a
   * missing reading can no longer impersonate a stoichiometric one.
   */
  public calculateAirFuelRatio(
    equivalenceRatioLambda: number | null,
    shortTermFuelTrimPercent: number = 0,
    longTermFuelTrimPercent: number = 0
  ): number {
    // A real wideband reading already reflects post-trim combustion AFR. Applying trims on
    // top of it would double-count them.
    if (
      equivalenceRatioLambda !== null &&
      Number.isFinite(equivalenceRatioLambda) &&
      equivalenceRatioLambda > 0.5 &&
      equivalenceRatioLambda < 2.0
    ) {
      const dynamicAfr = this.blend.stoichAfr * equivalenceRatioLambda;
      return Math.max(6.0, Math.min(22.0, dynamicAfr));
    }
    // Narrowband fallback: positive trim = ECU injecting MORE fuel = lower AFR
    const totalTrimFactor = 1.0 + (shortTermFuelTrimPercent + longTermFuelTrimPercent) / 100.0;
    const dynamicAfr = this.blend.stoichAfr / (totalTrimFactor > 0 ? totalTrimFactor : 1.0);
    return Math.max(6.0, Math.min(22.0, dynamicAfr));
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

    const afr = airFuelRatio > 0 ? airFuelRatio : this.blend.stoichAfr;
    const maf = Math.max(0, mafGramsPerSec);

    // Fuel mass flow (g/s) = Air mass flow (g/s) / AFR.
    // MAF measures air mass directly, so this chain stays in mass until the final
    // division by density - which is where the blend's density has to be the real one.
    const fuelFlowGramsPerSec = maf / afr;

    const fuelFlowGalPerHour = (fuelFlowGramsPerSec * 3600) / this.blend.densityGramsPerGallon;
    const fuelFlowLitersPerHour = (fuelFlowGramsPerSec * 3600) / this.blend.densityGramsPerLiter;

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
   * Shapes instant MPG into something a driver can read at a glance, and says which of the
   * three things it currently is. See the field comments above for why the damping exists.
   *
   * Standing still and coasting deliberately do not feed the average. Letting them in was
   * the whole problem: every red light dragged it to zero and every off-throttle moment
   * pulled it toward 99.9, so the figure spent most of a drive recovering from states that
   * were never economy readings in the first place. Frozen instead of reset, so pulling
   * away from a light resumes from what you were getting rather than climbing from nothing.
   */
  public updateDisplayMpg(
    instantMpg: number,
    speedMph: number,
    isDfcoActive: boolean,
    dtSec: number
  ): MpgDisplayReading {
    const state: MpgDisplayState =
      speedMph <= 1.0 ? 'idle' : isDfcoActive ? 'coasting' : 'driving';

    if (state === 'driving') {
      // Frame-rate independent: a dropped frame damps by the time that actually passed
      // rather than by one fixed step, so the needle behaves the same on a slow phone.
      const alpha = 1 - Math.exp(-Math.max(0, dtSec) / this.DISPLAY_TIME_CONSTANT_SEC);
      this.displayMpgAverage += (instantMpg - this.displayMpgAverage) * alpha;
    }

    this.displayMpgLatchAgeSec += Math.max(0, dtSec);
    if (this.displayMpgLatchAgeSec >= this.DISPLAY_LATCH_INTERVAL_SEC) {
      this.displayMpgLatchAgeSec = 0;
      this.displayMpgLatched = this.displayMpgAverage;
    }

    return { value: this.displayMpgLatched, state };
  }

  /**
   * Estimates remaining range in miles from tank fuel level and the current rolling MPG.
   * Falls back to the EPA combined rating before a real rolling MPG sample has built up
   * (e.g. right at startup), so the readout doesn't show 0 or blow up on a near-zero divisor.
   */
  public calculateFuelRange(
    fuelLevelPercent: number,
    tankCapacityGallons: number,
    rollingMpg: number
  ): number {
    const gallonsRemaining = (Math.max(0, Math.min(100, fuelLevelPercent)) / 100) * tankCapacityGallons;
    const effectiveMpg = rollingMpg > 1 ? rollingMpg : CIVIC_2013_SPECS.epaCombinedMpgDefault;
    return Math.max(0, gallonsRemaining * effectiveMpg);
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
