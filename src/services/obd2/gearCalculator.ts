import { CIVIC_2013_SPECS } from './civicSpecs';

export type GearSelection = 1 | 2 | 3 | 4 | 5 | 'N' | 'CLUTCH';

export interface GearAnalysisResult {
  currentGear: GearSelection;
  calculatedRatio: number;
  expectedRatio: number;
  ratioToleranceDelta: number;
  isClutchSlipping: boolean;
  optimalShiftRpm: number;
  shouldShiftUp: boolean;
  shiftLightStage: number; // 0 to 5 (0: normal, 1-3: approaching, 4: optimal shift, 5: flashing redline)
}

export class GearCalculatorEngine {
  private previousRpm: number = 0;
  private previousSpeedKmh: number = 0;
  private previousTimestamp: number = Date.now();
  private slipConfirmationCounter: number = 0;

  // Expected overall transmission gear ratio (RPM / Wheel RPM)
  // Wheel RPM = (SpeedKmh / 60) / TireCircumferenceKm
  // Total Ratio = Engine RPM / Wheel RPM = Gear Ratio * Final Drive Ratio
  private targetOverallRatios: Record<1 | 2 | 3 | 4 | 5, number>;

  constructor() {
    this.targetOverallRatios = {
      1: CIVIC_2013_SPECS.gearRatios[1] * CIVIC_2013_SPECS.finalDriveRatio, // 3.143 * 4.294 = 13.496
      2: CIVIC_2013_SPECS.gearRatios[2] * CIVIC_2013_SPECS.finalDriveRatio, // 1.870 * 4.294 = 8.030
      3: CIVIC_2013_SPECS.gearRatios[3] * CIVIC_2013_SPECS.finalDriveRatio, // 1.235 * 4.294 = 5.303
      4: CIVIC_2013_SPECS.gearRatios[4] * CIVIC_2013_SPECS.finalDriveRatio, // 0.949 * 4.294 = 4.075
      5: CIVIC_2013_SPECS.gearRatios[5] * CIVIC_2013_SPECS.finalDriveRatio, // 0.727 * 4.294 = 3.122
    };
  }

  /**
   * Evaluates the active gear based on real-time RPM and vehicle speed.
   */
  public analyzeGear(
    rpm: number,
    speedKmh: number,
    throttlePercent: number,
    shiftMode: 'eco' | 'power' = 'eco'
  ): GearAnalysisResult {
    const now = Date.now();
    const dt = Math.max(0.05, (now - this.previousTimestamp) / 1000);

    // If car is stationary or barely moving (< 3 km/h)
    if (speedKmh < 3.0) {
      this.previousRpm = rpm;
      this.previousSpeedKmh = speedKmh;
      this.previousTimestamp = now;
      return {
        currentGear: rpm > 1100 ? 'CLUTCH' : 'N',
        calculatedRatio: 0,
        expectedRatio: 0,
        ratioToleranceDelta: 0,
        isClutchSlipping: false,
        optimalShiftRpm: CIVIC_2013_SPECS.ecoShiftPoints[1],
        shouldShiftUp: false,
        shiftLightStage: 0,
      };
    }

    // Calculate Wheel RPM from Speed and Civic Tire Circumference
    const wheelRpm = (speedKmh / 60) / CIVIC_2013_SPECS.tireCircumferenceKm;
    const currentOverallRatio = wheelRpm > 0 ? rpm / wheelRpm : 0;

    // Match with 5-speed gear ratios within a ±7% tolerance window
    let detectedGear: GearSelection = 'N';
    let bestDelta = 999;
    let expectedRatio = 0;

    const gears: (1 | 2 | 3 | 4 | 5)[] = [1, 2, 3, 4, 5];

    for (const g of gears) {
      const target = this.targetOverallRatios[g];
      const deltaPercent = Math.abs(currentOverallRatio - target) / target;

      if (deltaPercent <= 0.08 && deltaPercent < bestDelta) {
        bestDelta = deltaPercent;
        detectedGear = g;
        expectedRatio = target;
      }
    }

    // If ratio doesn't match any gear:
    // If RPM is significantly higher than expected (e.g. revving with clutch pressed)
    // or RPM dropped to idle (750-900) while rolling fast -> 'N' or 'CLUTCH'
    if (detectedGear === 'N') {
      if (rpm <= 1000 && speedKmh > 10) {
        detectedGear = 'N'; // Rolling in neutral with engine at idle
      } else {
        detectedGear = 'CLUTCH'; // Shifting / clutch disengaged / rev-matching
      }
    }

    // Clutch Slip Detection:
    // If vehicle is in gear, throttle is high (> 35%), RPM is rising rapidly (> 1200 RPM/s)
    // but vehicle speed is NOT accelerating proportionally:
    let isClutchSlipping = false;
    if (typeof detectedGear === 'number' && throttlePercent > 35 && rpm > 2500) {
      const rpmRate = (rpm - this.previousRpm) / dt;
      const speedRate = (speedKmh - this.previousSpeedKmh) / dt;

      // In gear, RPM rate should directly match speed rate
      // If RPM is flaring upwards with near-zero vehicle acceleration:
      if (rpmRate > 1200 && speedRate < 1.0) {
        this.slipConfirmationCounter++;
        if (this.slipConfirmationCounter >= 3) {
          isClutchSlipping = true;
        }
      } else {
        this.slipConfirmationCounter = Math.max(0, this.slipConfirmationCounter - 1);
      }
    } else {
      this.slipConfirmationCounter = 0;
    }

    // Shift Light & Shift Point calculation
    let optimalShiftRpm = 6500;
    let shouldShiftUp = false;
    let shiftLightStage = 0;

    if (typeof detectedGear === 'number') {
      if (shiftMode === 'eco') {
        optimalShiftRpm = CIVIC_2013_SPECS.ecoShiftPoints[detectedGear as 1 | 2 | 3 | 4] || 2500;
        
        if (detectedGear < 5 && rpm >= optimalShiftRpm) {
          shouldShiftUp = true;
        }
        
        // Eco shift stages (0-5)
        if (rpm < 1800) shiftLightStage = 0;
        else if (rpm < 2000) shiftLightStage = 1;
        else if (rpm < 2150) shiftLightStage = 2;
        else if (rpm < optimalShiftRpm + 100) shiftLightStage = 3;
        else if (rpm < 3000) shiftLightStage = 4; // Shift now indicator
        else shiftLightStage = 5; // Exceeded eco threshold
      } else {
        // Power / Sport Shift Light
        optimalShiftRpm = CIVIC_2013_SPECS.powerShiftPointRpm; // 6,500 RPM
        shouldShiftUp = detectedGear < 5 && rpm >= 6300;

        if (rpm < 3500) shiftLightStage = 0;
        else if (rpm < 4500) shiftLightStage = 1; // Green 1
        else if (rpm < 5200) shiftLightStage = 2; // Green 2 / VTEC window
        else if (rpm < 5900) shiftLightStage = 3; // Yellow
        else if (rpm < 6400) shiftLightStage = 4; // Orange / Peak HP
        else shiftLightStage = 5; // Flashing Red Redline
      }
    }

    this.previousRpm = rpm;
    this.previousSpeedKmh = speedKmh;
    this.previousTimestamp = now;

    return {
      currentGear: detectedGear,
      calculatedRatio: currentOverallRatio,
      expectedRatio,
      ratioToleranceDelta: bestDelta !== 999 ? bestDelta : 0,
      isClutchSlipping,
      optimalShiftRpm,
      shouldShiftUp,
      shiftLightStage,
    };
  }
}
