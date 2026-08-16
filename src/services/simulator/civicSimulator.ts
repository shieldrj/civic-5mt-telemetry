import { CIVIC_2013_SPECS } from '../obd2/civicSpecs';
import { RawObdData } from '../bluetooth/obdlinkBluetooth';

export type SimulatorScenario = 'manual' | 'city_commute' | 'spirited_pull' | 'highway_cruise';

export class CivicSimulatorEngine {
  public scenario: SimulatorScenario = 'city_commute';
  
  // Driving controls
  public throttlePos: number = 0; // 0 - 100%
  public brakePos: number = 0;    // 0 - 100%
  public clutchPressed: boolean = false;
  public manualGear: 1 | 2 | 3 | 4 | 5 | 'N' = 1;

  // Physical State
  private rpm: number = CIVIC_2013_SPECS.idleRpm;
  private speedKmh: number = 0;
  private coolantTempC: number = 45; // Starts cool to demonstrate cold start tracker
  private iatC: number = 24;
  private simulationTimeSec: number = 0;

  // Autopilot script state
  private autoPhase: number = 0;
  private phaseTimerSec: number = 0;

  /**
   * Ticks the physical simulation by dt seconds (typically 0.05s / 50ms)
   */
  public tick(dt: number = 0.05): RawObdData {
    this.simulationTimeSec += dt;
    this.phaseTimerSec += dt;

    if (this.scenario !== 'manual') {
      this.runAutopilotLogic(dt);
    }

    // Engine Coolant Warming Dynamics
    if (this.coolantTempC < CIVIC_2013_SPECS.optimalOperatingTempC) {
      // Warm up gradually as engine runs
      const warmupRate = 0.08 + (this.rpm / 3000) * 0.12;
      this.coolantTempC = Math.min(CIVIC_2013_SPECS.optimalOperatingTempC, this.coolantTempC + warmupRate * dt);
    }

    // 5-Speed Manual Drivetrain Physics
    const inGear = typeof this.manualGear === 'number';
    const gearRatio = inGear ? CIVIC_2013_SPECS.gearRatios[this.manualGear as 1 | 2 | 3 | 4 | 5] : 0;
    const totalRatio = gearRatio * CIVIC_2013_SPECS.finalDriveRatio;

    if (this.clutchPressed || !inGear) {
      // Disengaged Clutch / Neutral: Free revving flywheel
      const targetRpm = this.throttlePos > 2 
        ? CIVIC_2013_SPECS.idleRpm + (this.throttlePos / 100) * (CIVIC_2013_SPECS.redlineRpm - CIVIC_2013_SPECS.idleRpm)
        : CIVIC_2013_SPECS.idleRpm;
      
      const revSpeed = this.throttlePos > 2 ? 3500 : 1800; // Flywheel acceleration vs drop rate
      if (this.rpm < targetRpm) {
        this.rpm = Math.min(targetRpm, this.rpm + revSpeed * dt);
      } else {
        this.rpm = Math.max(targetRpm, this.rpm - revSpeed * dt);
      }

      // Vehicle coasts down with air drag + rolling resistance
      const dragLoss = (0.0005 * Math.pow(this.speedKmh, 2) + 0.5) * dt;
      const brakeLoss = (this.brakePos / 100) * 35 * dt;
      this.speedKmh = Math.max(0, this.speedKmh - dragLoss - brakeLoss);

    } else {
      // Clutch Engaged in Gear: RPM is directly locked to wheel speed
      // Engine Torque Generation
      const engineLoad = Math.min(100, Math.max(10, this.throttlePos * 1.05));
      const torqueAvailable = (engineLoad / 100) * 174; // 174 Nm peak torque on R18Z1

      // Acceleration Force at wheels = (Engine Torque * Total Ratio * Efficiency) / Tire Radius
      const tireRadiusMeters = (CIVIC_2013_SPECS.tireCircumferenceKm * 1000) / (2 * Math.PI);
      const tractiveForceN = (torqueAvailable * totalRatio * 0.92) / tireRadiusMeters;

      // Resistive forces (Aerodynamic drag + Rolling resistance + Brakes)
      const speedMs = this.speedKmh / 3.6;
      const aeroDragN = 0.5 * 1.2 * CIVIC_2013_SPECS.dragCoefficientCd * CIVIC_2013_SPECS.frontalAreaM2 * Math.pow(speedMs, 2);
      const rollingResistN = CIVIC_2013_SPECS.curbWeightKg * 9.81 * 0.015;
      const brakeForceN = (this.brakePos / 100) * 7500;

      // Engine Braking when throttle is 0
      const engineBrakingN = this.throttlePos <= 1.0 ? (this.rpm / 1000) * totalRatio * 15 : 0;

      const netForceN = tractiveForceN - aeroDragN - rollingResistN - brakeForceN - engineBrakingN;
      const accelMs2 = netForceN / CIVIC_2013_SPECS.curbWeightKg;

      const newSpeedMs = Math.max(0, speedMs + accelMs2 * dt);
      this.speedKmh = newSpeedMs * 3.6;

      // Calculate RPM from wheel speed
      const wheelRpm = (this.speedKmh / 60) / CIVIC_2013_SPECS.tireCircumferenceKm;
      const connectedRpm = Math.round(wheelRpm * totalRatio);
      this.rpm = Math.max(CIVIC_2013_SPECS.idleRpm, Math.min(CIVIC_2013_SPECS.revLimiterRpm, connectedRpm));
    }

    // Calculate realistic MAF (g/s) based on engine load & RPM
    // At idle (750 RPM): ~2.2 - 2.8 g/s. At WOT (6500 RPM): ~115 - 130 g/s.
    const baseAirIdle = 2.4;
    const volumetricAir = (this.rpm / 6000) * 115 * (Math.max(15, this.throttlePos) / 100);
    const calculatedMaf = parseFloat((baseAirIdle + volumetricAir).toFixed(2));

    // Dynamic Fuel Trim and Lambda
    const isWot = this.throttlePos > 85;
    const lambda = isWot ? 0.88 : 1.0; // Open loop enrichment at wide open throttle
    const stft = isWot ? 0 : parseFloat(((Math.sin(this.simulationTimeSec * 1.5) * 2.2) - 0.5).toFixed(1));
    const ltft = 1.2;

    const calculatedLoad = parseFloat(Math.min(100, Math.max(12, (this.throttlePos * 0.9) + (this.rpm / 6700) * 15)).toFixed(1));
    const timingAdvance = parseFloat((this.throttlePos > 50 ? 24.5 : 12.0 + (this.rpm / 500)).toFixed(1));

    return {
      rpm: Math.round(this.rpm),
      speedKmh: parseFloat(this.speedKmh.toFixed(1)),
      maf: calculatedMaf,
      coolantC: Math.round(this.coolantTempC),
      iatC: this.iatC,
      engineLoad: calculatedLoad,
      throttlePos: parseFloat(this.throttlePos.toFixed(1)),
      stft,
      ltft,
      timingAdvance,
      lambda,
    };
  }

  /**
   * Autopilot script mimicking natural daily driving
   */
  private runAutopilotLogic(_dt: number): void {
    if (this.scenario === 'city_commute') {
      // Cycle: Idle at light -> Accel 1st -> 2nd -> 3rd -> 4th Cruise -> DFCO coast to stop
      switch (this.autoPhase) {
        case 0: // Stopped at red light (demonstrates idle fuel waste counter)
          this.manualGear = 'N';
          this.throttlePos = 0;
          this.brakePos = 30;
          this.clutchPressed = false;
          if (this.phaseTimerSec > 6.0) {
            this.autoPhase = 1;
            this.phaseTimerSec = 0;
          }
          break;
        case 1: // Start rolling in 1st gear
          this.manualGear = 1;
          this.brakePos = 0;
          this.clutchPressed = false;
          this.throttlePos = 25;
          if (this.rpm >= CIVIC_2013_SPECS.ecoShiftPoints[1] || this.phaseTimerSec > 3.0) {
            this.autoPhase = 2;
            this.phaseTimerSec = 0;
          }
          break;
        case 2: // Shift to 2nd gear
          this.manualGear = 2;
          this.throttlePos = 28;
          if (this.rpm >= CIVIC_2013_SPECS.ecoShiftPoints[2] || this.phaseTimerSec > 3.5) {
            this.autoPhase = 3;
            this.phaseTimerSec = 0;
          }
          break;
        case 3: // Shift to 3rd gear
          this.manualGear = 3;
          this.throttlePos = 30;
          if (this.rpm >= CIVIC_2013_SPECS.ecoShiftPoints[3] || this.phaseTimerSec > 4.0) {
            this.autoPhase = 4;
            this.phaseTimerSec = 0;
          }
          break;
        case 4: // Cruise in 4th gear (~35-40 mph city speed)
          this.manualGear = 4;
          this.throttlePos = 16;
          if (this.phaseTimerSec > 8.0) {
            this.autoPhase = 5;
            this.phaseTimerSec = 0;
          }
          break;
        case 5: // Deceleration Fuel Cut-Off (DFCO) Engine Braking Demo
          // Throttle 0%, still in 4th gear rolling: fuel drops to 0, MPG goes to 99.9!
          this.manualGear = 4;
          this.throttlePos = 0;
          this.brakePos = 15;
          if (this.speedKmh < 15 || this.phaseTimerSec > 5.0) {
            this.autoPhase = 0; // Loop back to red light
            this.phaseTimerSec = 0;
          }
          break;
      }
    } else if (this.scenario === 'spirited_pull') {
      // Spirited acceleration: full power through 1st, 2nd, 3rd up to 6,500 RPM VTEC redline
      switch (this.autoPhase) {
        case 0: // Launch
          this.manualGear = 1;
          this.brakePos = 0;
          this.throttlePos = 95;
          if (this.rpm >= 6400 || this.phaseTimerSec > 3.5) {
            this.autoPhase = 1;
            this.phaseTimerSec = 0;
          }
          break;
        case 1: // 2nd gear pull
          this.manualGear = 2;
          this.throttlePos = 100;
          if (this.rpm >= 6450 || this.phaseTimerSec > 4.0) {
            this.autoPhase = 2;
            this.phaseTimerSec = 0;
          }
          break;
        case 2: // 3rd gear pull
          this.manualGear = 3;
          this.throttlePos = 100;
          if (this.rpm >= 6200 || this.phaseTimerSec > 5.0) {
            this.autoPhase = 3;
            this.phaseTimerSec = 0;
          }
          break;
        case 3: // Coast down
          this.manualGear = 4;
          this.throttlePos = 0;
          this.brakePos = 20;
          if (this.speedKmh < 30 || this.phaseTimerSec > 6.0) {
            this.autoPhase = 0;
            this.phaseTimerSec = 0;
          }
          break;
      }
    } else if (this.scenario === 'highway_cruise') {
      // 5th gear smooth 65-70 mph cruising (steady high MPG)
      this.manualGear = 5;
      this.brakePos = 0;
      this.throttlePos = 20 + Math.sin(this.simulationTimeSec * 0.3) * 3; // slight terrain variation
    }
  }
}
