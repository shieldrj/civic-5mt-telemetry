export interface OBDLiveMetrics {
  // Raw / Base OBD-II PIDs
  rpm: number;                  // PID 010C (RPM)
  speedKmh: number;             // PID 010D (km/h)
  speedMph: number;             // Calculated (mph)
  mafGramsPerSec: number;       // PID 0110 (g/s)
  coolantTempC: number;         // PID 0105 (°C)
  coolantTempF: number;         // Calculated (°F)
  intakeAirTempC: number;       // PID 010F (°C)
  intakeAirTempF: number;       // Calculated (°F)
  engineLoadPercent: number;    // PID 0104 (%)
  throttlePosPercent: number;   // PID 0111 (%)
  shortTermFuelTrim: number;    // PID 0106 (%)
  longTermFuelTrim: number;     // PID 0107 (%)
  timingAdvanceDeg: number;     // PID 010E (°)
  equivalenceRatio: number;     // PID 0124 (Lambda, default 1.0)
  batteryVoltage: number;       // PID 0142 (Control module / charging voltage, V)
  fuelLevelPercent: number;     // PID 012F (%)
  ambientAirTempC: number;      // PID 0146 (°C)
  ambientAirTempF: number;      // Calculated (°F)
  o2Sensor1Voltage: number;     // PID 0114 (Bank 1 Sensor 1, pre-catalyst, V)
  o2Sensor2Voltage: number;     // PID 0115 (Bank 1 Sensor 2, post-catalyst, V)
  engineRuntimeSec: number;     // PID 011F (ECU-reported runtime since engine start, sec)

  // Custom Computed / Physics Fuel Metrics
  instantMpg: number;           // Calculated MPG (0 - 99.9 or Infinity on DFCO)
  isDfcoActive: boolean;        // Deceleration Fuel Cut-Off (Engine braking in gear)
  fuelFlowGalPerHour: number;   // Current fuel consumption (gal/hr)
  fuelFlowLitersPerHour: number;// Current fuel consumption (L/hr)
  airFuelRatio: number;         // Actual Air:Fuel ratio (e.g. 14.7)
  rolling30sMpg: number;        // Smooth 30s rolling window
  lifetimeMpg: number;           // Cumulative lifetime MPG (persisted, real OBD data only)
  lifetimeMiles: number;         // Real vehicle miles behind lifetimeMpg (0 = never connected)
  fuelRangeMiles: number;        // Estimated miles-to-empty from fuel level + rolling MPG

  // Manual Transmission Dynamics
  currentGear: 1 | 2 | 3 | 4 | 5 | 'N' | 'CLUTCH';
  gearRatio: number;            // Current computed engine/wheel ratio
  isClutchSlipping: boolean;    // Warning flag
  optimalShiftRpm: number;      // Target shift RPM (Eco or Power)
  shouldShiftUp: boolean;
  shiftLightStage: number;      // 0 to 5 progressive LED stages
  
  // Timestamp
  timestamp: number;
}

export interface TripAnalytics {
  tripStartTime: number;
  tripDurationSec: number;
  distanceMiles: number;
  totalFuelUsedGallons: number;
  avgMpg: number;
  idleTimeSec: number;
  idleFuelGallons: number;
  idleCostDollars: number;
  coastingDfcoTimeSec: number;
  coastingFuelSavedGallons: number;
  maxSpeedMph: number;
  maxRpm: number;
  avgSpeedMph: number;
  ecoScore: number; // 0 - 100
}

export interface OilLifeProfile {
  lastResetTimestamp: number;
  lastResetOdometer: number;
  currentOdometer: number;
  oilLifePercent: number;       // 0 - 100%
  
  // Deep tracking factors
  accumulatedRevolutions: number; // Total crankshaft cycles
  coldStartsCount: number;        // Starts with Coolant < 160°F
  timeBelowOperatingTempSec: number; // Seconds under 160°F
  shortTripsCount: number;        // Trips under 15m without full warmup
  highThermalStressSec: number;   // Seconds above 4500 RPM / high load
  
  // Projected wear
  estimatedMilesRemaining: number;
  estimatedDaysRemaining: number;
  oilConditionGrade: 'Excellent' | 'Good' | 'Fair' | 'Service Due' | 'Degraded';
  degradationBreakdown: {
    revWearFactor: number;
    coldStartPenalty: number;
    shortTripPenalty: number;
    thermalShearPenalty: number;
  };
}

export interface LifetimeStats {
  totalMiles: number;
  totalFuelGallons: number;
  lifetimeMpg: number;
  firstTrackedTimestamp: number;
}

export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'simulating' | 'error';
